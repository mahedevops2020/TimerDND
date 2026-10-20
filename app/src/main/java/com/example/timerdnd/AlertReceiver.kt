package com.example.timerdnd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Fired by AlarmManager at a marker's time, even when the app is idle or the screen is locked. */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Alerts.fireOnce(
            context.applicationContext,
            intent.getStringExtra("id") ?: "",
            intent.getIntExtra("type", AlertMarker.VIBRATE)
        )
        // Keep the receiver alive long enough for the beep to play and be released.
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 1500)
    }
}
