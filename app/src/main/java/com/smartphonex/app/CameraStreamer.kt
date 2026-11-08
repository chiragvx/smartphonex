package com.smartphonex.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.smartphonex.core.CameraAccess
import com.smartphonex.core.CameraConfig
import com.smartphonex.core.VehicleRuntime
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * One viewer path. A frame is only sent once the previous one was acknowledged, so a slow link
 * skips frames instead of building a queue that would delay everything else on that connection.
 */
class VideoSink(private val transmit: (ByteArray) -> Unit) {
    @Volatile private var sentAt = 0L
    val ready: Boolean get() = sentAt == 0L || System.currentTimeMillis() - sentAt > ACK_TIMEOUT_MS
    fun send(frame: ByteArray) {
        sentAt = System.currentTimeMillis()
        transmit(frame)
    }
    fun acked() { sentAt = 0L }
    private companion object { const val ACK_TIMEOUT_MS = 1500L }
}

/** Runs whichever camera the operator chose, only while someone is actually watching. */
class CameraStreamer(private val context: Context, private val rt: VehicleRuntime) : CameraAccess {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val sinks = CopyOnWriteArrayList<VideoSink>()
    private var catalogCache: JsonArray? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var running: CameraConfig? = null
    private var patternOn = false

    @Volatile private var state = "off"
    @Volatile private var latest: ByteArray? = null
    @Volatile private var latestAt = 0L
    @Volatile private var oneShotUntil = 0L
    private var lastFrameAt = 0L
    private var rotation = 0
    private var frames = 0
    private var fpsWindowStart = 0L
    @Volatile private var liveFps = 0.0
    private val out = ByteArrayOutputStream(64 * 1024)

    override fun refresh() = reconcile()
    private fun visionMode() = rt.params.get("VIS_LINE").toInt()

    override fun status(): String = if (state == "live") "live ${"%.1f".format(liveFps)} fps" else state

    /** True while the real camera is capturing, for the on-phone indicator. */
    val isLive: Boolean get() = state == "live"

    /** Gets every frame while a run is being saved. Null when nothing is recording. */
    @Volatile var recorder: ((ByteArray) -> Unit)? = null
    private fun recording() = recorder != null && rt.flightLog.running

    fun addSink(s: VideoSink) { sinks += s; reconcile() }
    fun removeSink(s: VideoSink) { sinks -= s; reconcile() }

    override fun catalog(): JsonArray {
        catalogCache?.let { return it }
        val cams = buildJsonArray {
            runCatching {
                for (id in manager.cameraIdList) {
                    val ch = manager.getCameraCharacteristics(id)
                    val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_BACK -> "Back"
                        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                        else -> "Camera"
                    }
                    val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
                        ?.filter { it.width <= 1920 }?.sortedBy { it.width * it.height }.orEmpty()
                    val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    addJsonObject {
                        put("id", id)
                        put("name", "$facing camera $id" + (focal?.let { " (${"%.1f".format(it)} mm)" } ?: ""))
                        putJsonArray("sizes") { sizes.forEach { add("${it.width}x${it.height}") } }
                    }
                }
            }
            addJsonObject {
                put("id", TEST_PATTERN)
                put("name", "Test pattern (no camera used)")
                putJsonArray("sizes") { listOf("160x120", "320x240", "640x480").forEach { add(it) } }
            }
        }
        catalogCache = cams
        return cams
    }

    override fun snapshot(timeoutMs: Long): ByteArray? {
        val asked = System.currentTimeMillis()
        oneShotUntil = asked + timeoutMs
        reconcile()
        val end = asked + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (latestAt > asked) return latest
            Thread.sleep(50)
        }
        return null
    }

    /** Starts, stops or switches the camera to match the settings and who's watching. */
    fun reconcile() {
        handler.post {
            val cfg = rt.sensors.camera
            val wanted = cfg.enabled && (sinks.isNotEmpty() || recording() || System.currentTimeMillis() < oneShotUntil || visionMode() > 0)
            if (!wanted) {
                close()
                state = if (cfg.enabled) "on, waiting for a viewer" else "off"
                return@post
            }
            val r = running
            if (r != null && r.cameraId == resolveId(cfg) && r.width == cfg.width && r.height == cfg.height) {
                running = cfg.copy(cameraId = r.cameraId) // Frame rate and quality apply without a restart.
                return@post
            }
            close()
            open(cfg)
        }
        if (System.currentTimeMillis() < oneShotUntil) handler.postDelayed({ reconcile() }, oneShotUntil - System.currentTimeMillis() + 50)
    }

    private fun resolveId(cfg: CameraConfig): String {
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        if (cfg.cameraId == TEST_PATTERN || cfg.cameraId in ids) return cfg.cameraId
        // Prefer the rear camera, and fall back to the test pattern on a device without any camera.
        return ids.firstOrNull { runCatching { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }.getOrNull() == CameraCharacteristics.LENS_FACING_BACK }
            ?: ids.firstOrNull() ?: TEST_PATTERN
    }

    private fun open(cfg: CameraConfig) {
        val id = resolveId(cfg)
        running = cfg.copy(cameraId = id)
        lastFrameAt = 0
        if (id == TEST_PATTERN) {
            rotation = 0
            state = "live"
            patternOn = true
            handler.post(patternStep)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            running = null
            state = "allow the camera for SmartPhoneX on the phone"
            return
        }
        state = "starting"
        try {
            openCamera(id, cfg)
        } catch (e: Exception) {
            running = null
            state = "could not open camera: ${e.javaClass.simpleName}"
        }
    }

    @SuppressLint("MissingPermission") // Checked by the caller.
    private fun openCamera(id: String, cfg: CameraConfig) {
        val ch = manager.getCameraCharacteristics(id)
        rotation = (ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) / 90
        val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
        val size = sizes?.minByOrNull { abs(it.width * it.height - cfg.width * cfg.height) }
        if (size == null) { state = "this camera offers no usable video format"; running = null; return }
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try { onCameraImage(image) } catch (e: Exception) { state = "camera frame error: ${e.javaClass.simpleName}" } finally { image.close() }
        }, handler)
        reader = r
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                try {
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            try {
                                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(r.surface) }.build()
                                s.setRepeatingRequest(req, null, handler)
                                state = "live"
                                rt.log.event("camera", "phone", "started", "camera $id at ${size.width}x${size.height}")
                            } catch (e: Exception) {
                                state = "could not start camera: ${e.javaClass.simpleName}"
                            }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { state = "camera refused this video format" }
                    }, handler)
                } catch (e: Exception) {
                    state = "could not start camera: ${e.javaClass.simpleName}"
                }
            }
            override fun onDisconnected(camera: CameraDevice) {
                // Another app took the camera, or Android stopped it.
                close()
                state = "camera taken by another app or stopped by Android"
            }
            override fun onError(camera: CameraDevice, error: Int) {
                close()
                state = "camera error $error"
            }
        }, handler)
    }

    private fun due(asked: Double): Boolean {
        val fps = asked * shed
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < 1000.0 / fps) return false
        // No viewer can take a frame and nobody wants a still: don't spend battery encoding one.
        if (now >= oneShotUntil && sinks.none { it.ready } && visionMode() == 0 && !recording()) return false
        lastFrameAt = now
        return true
    }

    private fun onCameraImage(image: Image) {
        val cfg = running ?: return
        if (!due(cfg.fps)) return
        val nv21 = toNv21(image)
        val vision = visionMode()
        if (vision > 0) {
            // Stays on the phone: only the found flag and position are shared, as a sensor.
            rt.sensors.update("vision.line", com.smartphonex.core.LineTracker.track(nv21, image.width, image.height, vision == 1))
        }
        out.reset()
        YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null).compressToJpeg(Rect(0, 0, image.width, image.height), cfg.quality, out)
        emit(out.toByteArray(), cfg)
    }

    private val patternStep = object : Runnable {
        var x = 0
        override fun run() {
            val cfg = running
            if (!patternOn || cfg == null) return
            if (due(cfg.fps)) {
                val bmp = Bitmap.createBitmap(cfg.width, cfg.height, Bitmap.Config.RGB_565)
                val c = Canvas(bmp)
                c.drawColor(Color.rgb(30, 40, 60))
                val p = Paint().apply { color = Color.rgb(74, 163, 255) }
                x = (x + cfg.width / 20) % cfg.width
                c.drawRect(x.toFloat(), 0f, x + cfg.width / 8f, cfg.height.toFloat(), p)
                p.color = Color.WHITE
                p.textSize = cfg.height / 8f
                c.drawText("TEST PATTERN", cfg.width * 0.05f, cfg.height * 0.5f, p)
                out.reset()
                bmp.compress(Bitmap.CompressFormat.JPEG, cfg.quality, out)
                bmp.recycle()
                emit(out.toByteArray(), cfg)
            }
            handler.postDelayed(this, 20)
        }
    }

    private fun emit(jpeg: ByteArray, cfg: CameraConfig) {
        val now = System.currentTimeMillis()
        latest = jpeg
        latestAt = now
        if (fpsWindowStart == 0L) fpsWindowStart = now
        frames++
        if (now - fpsWindowStart >= 2000) {
            liveFps = frames * 1000.0 / (now - fpsWindowStart)
            frames = 0
            fpsWindowStart = now
        }
        if (recording()) recorder?.invoke(jpeg)
        val free = sinks.filter { it.ready }
        if (free.isEmpty()) return
        if (!rt.sensors.takeVideo(jpeg.size, cfg.priority)) return
        // Header: 8-byte capture time, 1-byte rotation in quarter turns, then the image.
        val frame = ByteBuffer.allocate(9 + jpeg.size).putLong(now).put(rotation.toByte()).put(jpeg).array()
        free.forEach { runCatching { it.send(frame) } }
    }

    private fun toNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = image.planes[0]
        val yBuf = y.buffer
        var pos = 0
        for (row in 0 until h) {
            yBuf.position(row * y.rowStride)
            yBuf.get(nv21, pos, w)
            pos += w
        }
        val u = image.planes[1]
        val v = image.planes[2]
        val uBuf = u.buffer
        val vBuf = v.buffer
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[pos++] = vBuf.get(row * v.rowStride + col * v.pixelStride)
                nv21[pos++] = uBuf.get(row * u.rowStride + col * u.pixelStride)
            }
        }
        return nv21
    }

    private fun close() {
        patternOn = false
        handler.removeCallbacks(patternStep)
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        running = null
        liveFps = 0.0
    }

    fun stop() {
        handler.post { close() }
        thread.quitSafely()
    }

    companion object {
        private const val TEST_PATTERN = "test"
        /** Lowered when the phone is hot: half or a quarter of the frames. Set from Android's thermal status. */
        @Volatile var shed = 1.0
    }
}
