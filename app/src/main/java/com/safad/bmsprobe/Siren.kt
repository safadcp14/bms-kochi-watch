package com.safad.bmsprobe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synth siren on the ALARM stream. Square wave, so it cuts through a sleeping room
 * and through earbuds that are still connected from the night before.
 */
object Siren {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start(context: Context) {
        if (running) return
        running = true
        crankVolume(context)
        vibrate(context, true)

        thread = Thread {
            val sr = 44100
            val buf = ShortArray(sr / 2)
            val t = runCatching { buildTrack(sr, buf.size * 2) }.getOrNull() ?: return@Thread
            track = t
            runCatching { t.play() }
            var phase = 0.0
            var n = 0L
            while (running) {
                for (i in buf.indices) {
                    val sec = n / sr.toDouble()
                    val cycle = sec % 1.2
                    if (cycle < 1.0) {
                        val freq = 520.0 + 1100.0 * (cycle / 1.0)
                        phase += 2 * PI * freq / sr
                        buf[i] = if (sin(phase) >= 0) 12000 else -12000
                    } else {
                        buf[i] = 0
                    }
                    n++
                }
                runCatching { t.write(buf, 0, buf.size) }
            }
            runCatching { t.stop(); t.release() }
            track = null
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop(context: Context) {
        running = false
        runCatching { track?.stop() }
        track = null
        thread = null
        vibrate(context, false)
    }

    private fun buildTrack(sr: Int, bytes: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bytes, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

    private fun crankVolume(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }
    }

    private fun vibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    private fun vibrate(context: Context, on: Boolean) {
        val v = vibrator(context) ?: return
        runCatching {
            if (on) {
                val pattern = longArrayOf(0, 600, 250, 600, 250)
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                v.cancel()
            }
        }
    }
}
