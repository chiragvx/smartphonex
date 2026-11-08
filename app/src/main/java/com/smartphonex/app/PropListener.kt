package com.smartphonex.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.smartphonex.core.PropTone
import com.smartphonex.core.VehicleRuntime

/**
 * Experimental (MIC_PROP): listens for the propeller's blade tone and hands it to the flight controller.
 * Sound is analyzed in memory, a few frames at a time, and never recorded, stored or sent.
 * Runs at low priority on its own thread, so it can never delay the control loop.
 */
class PropListener(private val context: Context, private val rt: VehicleRuntime) {
    @Volatile private var running = true
    private val thread = Thread(::loop, "spx-prop").apply { isDaemon = true; start() }

    fun stop() { running = false; thread.interrupt() }

    private fun enabled() = rt.params.get("MIC_PROP") >= 1

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        while (running) {
            try {
                when {
                    !enabled() -> { rt.fc.propUnavailable = null; Thread.sleep(1000) }
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                        rt.fc.propUnavailable = "allow the microphone for SmartPhoneX on the phone"
                        Thread.sleep(2000)
                    }
                    else -> listen()
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                rt.fc.propUnavailable = "microphone error: ${e.message ?: e.javaClass.simpleName}"
                runCatching { Thread.sleep(2000) }
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // Checked in loop().
    private fun listen() {
        val rate = 44_100 // The one rate every Android phone must support.
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Unprocessed sound where the phone offers it: noise suppression would treat the propeller as noise.
        val am = context.getSystemService(AudioManager::class.java)
        val source = if (am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rec = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16_384))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("couldn't open the microphone") }
        val analyzer = PropTone(rate, 8192)
        val ring = DoubleArray(8192)
        val buf = ShortArray(4096) // About ten analyses a second, each over the last 0.19 s.
        var silentSince = 0L
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("the microphone is in use by another app")
            rt.fc.propUnavailable = null
            while (running && enabled()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) throw IllegalStateException("microphone read failed ($n)")
                System.arraycopy(ring, n, ring, 0, ring.size - n)
                var any = false
                for (i in 0 until n) { ring[ring.size - n + i] = buf[i] / 32768.0; if (buf[i].toInt() != 0) any = true }
                // Android hands an app pure silence when it may not use the microphone in the background.
                val now = System.currentTimeMillis()
                if (any) { silentSince = 0L; if (rt.fc.propUnavailable != null) rt.fc.propUnavailable = null }
                else if (silentSince == 0L) silentSince = now
                else if (now - silentSince > 2000) rt.fc.propUnavailable = "Android is giving silence: open SmartPhoneX on the phone once so it may use the microphone"
                rt.fc.onPropTone(analyzer.analyze(ring, rt.params.get("MIC_F_MIN"), rt.params.get("MIC_F_MAX"), rt.params.get("MIC_SNR"), rt.fc.propExclusions()), outputsAtRest = rt.ops.tester.active() == null)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }
}
