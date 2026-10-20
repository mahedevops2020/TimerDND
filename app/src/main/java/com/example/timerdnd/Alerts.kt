package com.example.timerdnd

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Plays the marker vibration / beep. Usable from the activity and from the alarm receiver. */
object Alerts {

    /** Selectable beep tones: display name, ToneGenerator tone, play length in ms. */
    class Tone(val name: String, val tone: Int, val durationMs: Int)

    val tones = listOf(
        Tone("Beep", ToneGenerator.TONE_PROP_BEEP, 200),
        Tone("Double beep", ToneGenerator.TONE_PROP_BEEP2, 400),
        Tone("Chime", ToneGenerator.TONE_PROP_ACK, 500),
        Tone("Pip", ToneGenerator.TONE_SUP_PIP, 400),
        Tone("Triple pip", ToneGenerator.TONE_CDMA_PIP, 600),
        Tone("Alert", ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600),
        Tone("Ringback", ToneGenerator.TONE_CDMA_ABBR_ALERT, 700)
    )

    fun prefs(context: Context) =
        context.getSharedPreferences("timerdnd_prefs", Context.MODE_PRIVATE)

    private fun level(context: Context, key: String): Int =
        prefs(context).getInt(key, 50).coerceIn(1, 100)

    private val fired = HashMap<String, Long>()

    /** Fires at most once per id within a few seconds, so the activity and the alarm backup never double up. */
    @Synchronized
    fun fireOnce(context: Context, id: String, type: Int) {
        val now = System.currentTimeMillis()
        val last = fired[id]
        if (last != null && now - last < 10_000) return
        fired[id] = now
        fire(context, type)
    }

    fun fire(context: Context, type: Int) {
        if (type == AlertMarker.BEEP) beep(context) else vibrate(context)
    }

    fun beep(context: Context) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, level(context, "beep_level"))
            val choice = tones[prefs(context).getInt("beep_tone", 0).coerceIn(0, tones.size - 1)]
            tone.startTone(choice.tone, choice.durationMs)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, choice.durationMs + 300L)
        } catch (e: RuntimeException) {
            // ToneGenerator can fail to initialise on some devices; skip the beep rather than crash.
        }
    }

    @Suppress("DEPRECATION")
    fun vibrate(context: Context) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return

        // Alarm usage so the vibration is not suppressed by Do Not Disturb ("Alarms only").
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val lvl = level(context, "vibrate_level")
        val duration = 100L + lvl * 6L // 106..700 ms, so it is stronger even without amplitude control
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude =
                if (vibrator.hasAmplitudeControl()) (lvl * 255 / 100).coerceAtLeast(1)
                else VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude), attrs)
        } else {
            vibrator.vibrate(longArrayOf(0, duration), -1, attrs)
        }
    }
}
