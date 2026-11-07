package com.smartphonex.app

import com.smartphonex.core.VehicleRuntime
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Saves frames beside the flight log while a run is being logged and the camera is on, so a run can
 * be replayed in sync. It's about two pictures a second: enough to see what happened, and small
 * enough to keep. It never turns the camera on by itself: that remains an operator's choice.
 */
class VideoRecorder(private val rt: VehicleRuntime, private val camera: CameraStreamer) {
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "video-record").apply { isDaemon = true } }
    private var lastSaved = 0L
    private var wasRunning = false
    private var sinceTrim = 0
    @Volatile private var pending = false

    fun start() {
        camera.recorder = ::onFrame
        // The camera has to wake up and sleep with the run.
        io.scheduleWithFixedDelay({
            val running = rt.flightLog.running
            if (running != wasRunning) { wasRunning = running; camera.reconcile() }
        }, 1, 1, TimeUnit.SECONDS)
    }

    fun stop() { camera.recorder = null; io.shutdown() }

    private fun onFrame(jpeg: ByteArray) {
        val log = rt.flightLog.currentFile ?: return
        val now = System.currentTimeMillis()
        if (now - lastSaved < INTERVAL_MS || pending) return
        lastSaved = now
        pending = true
        io.execute {
            runCatching {
                val dir = File(rt.ops.videoDir, log.nameWithoutExtension).apply { mkdirs() }
                if ((dir.list()?.size ?: 0) < MAX_FRAMES) File(dir, "$now.jpg").writeBytes(jpeg)
                trim()
            }
            pending = false
        }
    }

    /** Oldest runs go first when the total gets too big. */
    private fun trim() {
        if (++sinceTrim < 60) return
        sinceTrim = 0
        val runs = rt.ops.videoDir.listFiles()?.sortedByDescending { it.name } ?: return
        var total = 0L
        for (r in runs) {
            total += r.listFiles()?.sumOf { it.length() } ?: 0
            if (total > MAX_BYTES) r.deleteRecursively()
        }
    }

    private companion object {
        const val INTERVAL_MS = 500L
        const val MAX_FRAMES = 7200
        const val MAX_BYTES = 500L * 1024 * 1024
    }
}
