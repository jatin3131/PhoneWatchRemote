package com.example.phoneremotewatch

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.sqrt

/**
 * Listens to the room with the watch microphone and pulses the motor on bass hits.
 * Levels: 0 = battery saver, 1 = medium, 2 = high.
 */
class HapticsEngine(private val context: Context) {

    @Volatile
    private var running = false

    @Volatile
    private var level = 1

    private var worker: Thread? = null

    fun start(newLevel: Int) {
        level = newLevel
        if (running) return
        running = true
        val t = Thread { loop() }
        t.name = "haptics"
        worker = t
        t.start()
    }

    fun stop() {
        running = false
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(source: Int, rate: Int, bufSize: Int): AudioRecord? {
        return try {
            val r = AudioRecord(
                source,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                null
            } else {
                r
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loop() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running = false
            return
        }
        val rate = 16000
        val frame = 512
        val minBuf = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, frame * 8)
        val rec = openRecorder(MediaRecorder.AudioSource.UNPROCESSED, rate, bufSize)
            ?: openRecorder(MediaRecorder.AudioSource.MIC, rate, bufSize)
        if (rec == null) {
            running = false
            return
        }

        val vibrator = context.getSystemService(Vibrator::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val hasAmp = vibrator?.hasAmplitudeControl() == true
        val buf = ShortArray(frame)
        var recording = false
        var lp = 0f
        var avg = 0f
        var prev = 0f
        var lastPulse = 0L
        var blankUntil = 0L

        try {
            while (running) {
                val lvl = level
                if (lvl == 0 && pm != null && !pm.isInteractive) {
                    if (recording) {
                        rec.stop()
                        recording = false
                    }
                    Thread.sleep(700)
                    continue
                }
                if (!recording) {
                    rec.startRecording()
                    recording = true
                    avg = 0f
                    prev = 0f
                }
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue

                var sum = 0.0
                for (i in 0 until n) {
                    val x = buf[i].toFloat()
                    lp += 0.0556f * (x - lp)
                    sum += (lp * lp).toDouble()
                }
                val bass = sqrt(sum / n).toFloat()
                val now = SystemClock.elapsedRealtime()

                if (now < blankUntil) {
                    prev = bass
                    continue
                }
                if (avg == 0f) avg = bass

                val ratio = if (avg > 1f) bass / avg else 0f
                val rise = bass - prev
                prev = bass
                avg += (bass - avg) * 0.04f

                val threshold = when (lvl) {
                    0 -> 1.7f
                    1 -> 1.45f
                    else -> 1.28f
                }
                val minGap = when (lvl) {
                    0 -> 330L
                    1 -> 250L
                    else -> 200L
                }
                val floor = when (lvl) {
                    0 -> 220f
                    1 -> 150f
                    else -> 110f
                }
                val maxAmp = when (lvl) {
                    0 -> 140
                    1 -> 200
                    else -> 255
                }
                val baseMs = when (lvl) {
                    0 -> 16L
                    1 -> 22L
                    else -> 26L
                }

                if (bass > floor && ratio > threshold && rise > floor * 0.25f && now - lastPulse > minGap) {
                    val strong = ratio > 2.2f
                    val ms = baseMs + (if (strong && lvl == 2) 14L else 0L)
                    val strength = minOf(1f, 0.55f + (ratio - threshold) * 0.5f)
                    val amplitude = (maxAmp * strength).toInt().coerceIn(40, maxAmp)
                    try {
                        val effect = if (hasAmp) {
                            VibrationEffect.createOneShot(ms, amplitude)
                        } else {
                            VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                        }
                        vibrator?.vibrate(effect)
                    } catch (e: Exception) {
                        // Ignore vibration problems.
                    }
                    lastPulse = now
                    blankUntil = now + ms + 110L
                }
            }
        } catch (e: Exception) {
            // Stop quietly if the microphone becomes unavailable.
        } finally {
            try {
                rec.stop()
            } catch (e: Exception) {
                // Ignore.
            }
            try {
                rec.release()
            } catch (e: Exception) {
                // Ignore.
            }
            running = false
        }
    }
}
