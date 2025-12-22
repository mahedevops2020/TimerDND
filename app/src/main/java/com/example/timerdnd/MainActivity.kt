package com.example.timerdnd

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private lateinit var timerText: TextView
    private lateinit var milliTimerText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var resetBtn: Button

    private var startTime = 0L
    private var elapsedTime = 0L
    private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager

    private val updateTimer = object : Runnable {
        override fun run() {
            if (isRunning) {
                elapsedTime = System.currentTimeMillis() - startTime
                updateTimerDisplay()
                handler.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        timerText = findViewById(R.id.timerText)
        milliTimerText = findViewById(R.id.milliTimerText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        resetBtn = findViewById(R.id.resetBtn)

        checkDndPermission()

        startBtn.setOnClickListener { startTimer() }
        stopBtn.setOnClickListener { stopTimer() }
        resetBtn.setOnClickListener { resetTimer() }

        val observer = timerText.viewTreeObserver
        observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (timerText.lineCount > 1) {
                    val newSize = timerText.textSize - 2f
                    timerText.setTextSize(TypedValue.COMPLEX_UNIT_PX, newSize)
                }
            }
        })
    }

    private fun checkDndPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                showPermissionDialog()
                return false
            }
        }
        return true
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("DND Permission Required")
            .setMessage("This app needs permission to control Do Not Disturb. Grant permission in the next screen.")
            .setPositiveButton("Grant") { _, _ ->
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTimer() {
        if (!isRunning) {
            if (checkDndPermission()) {
                enableDnd()
                startTime = System.currentTimeMillis() - elapsedTime
                isRunning = true
                handler.post(updateTimer)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startBtn.isActivated = true
            }
        }
    }

    private fun stopTimer() {
        if (isRunning) {
            disableDnd()
            isRunning = false
            handler.removeCallbacks(updateTimer)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startBtn.isActivated = false
        }
    }

    private fun resetTimer() {
        stopTimer()
        elapsedTime = 0L
        updateTimerDisplay()
    }

    private fun updateTimerDisplay() {
        val seconds = (elapsedTime / 1000).toInt()
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        val millis = (elapsedTime % 1000).toInt()
        timerText.text = String.format("%02d:%02d:%02d", hours, minutes, secs)
        milliTimerText.text = String.format("%03d", millis)
    }

    private fun enableDnd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                Toast.makeText(this, "DND Enabled", Toast.LENGTH_SHORT).show()
            } catch (e: SecurityException) {
                Toast.makeText(this, "Permission denied. Please grant DND access.", Toast.LENGTH_LONG).show()
                showPermissionDialog()
            }
        }
    }

    private fun disableDnd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                Toast.makeText(this, "DND Disabled", Toast.LENGTH_SHORT).show()
            } catch (e: SecurityException) {
                Toast.makeText(this, "Permission denied. Please grant DND access.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateTimer)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}