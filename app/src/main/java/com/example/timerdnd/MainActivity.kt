package com.example.timerdnd

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.net.Uri
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private lateinit var timerText: TextView
    private lateinit var milliTimerText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var lengthBtn: Button
    private lateinit var markerBar: MarkerProgressBar

    private var startTime = 0L
    private var elapsedTime = 0L
    private var isRunning = false
    private var scheduledCount = 0

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager

    // DND filter that was active before the timer started, restored when it stops.
    private var originalFilter = NotificationManager.INTERRUPTION_FILTER_ALL

    // Markers whose time has already been reached, so each one alerts only once.
    private val firedMarkers = mutableSetOf<AlertMarker>()

    // Remembers the last alert type the user picked, to pre-select it for the next marker.
    private var lastType = AlertMarker.VIBRATE

    private val prefs by lazy { getSharedPreferences("timerdnd_prefs", Context.MODE_PRIVATE) }

    private val updateTimer = object : Runnable {
        override fun run() {
            if (isRunning) {
                elapsedTime = System.currentTimeMillis() - startTime
                updateTimerDisplay()
                checkMarkers()
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
        lengthBtn = findViewById(R.id.lengthBtn)
        markerBar = findViewById(R.id.markerBar)

        loadMarkerState()
        markerBar.onAddRequested = { timeMs -> showMarkerDialog(null, timeMs) }
        markerBar.onMarkerTapped = { marker -> showMarkerDialog(marker, marker.timeMs) }
        lengthBtn.setOnClickListener { showLengthDialog() }
        updateLengthButton()

        setupLevelBar(R.id.vibrateLevel, "vibrate_level") { Alerts.vibrate(this) }
        setupLevelBar(R.id.beepLevel, "beep_level") { Alerts.beep(this) }

        setupToneSpinner()

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

    // ---------------------------------------------------------------- DND

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

    private fun enableDnd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val current = notificationManager.currentInterruptionFilter
                originalFilter =
                    if (current == NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    else current
                // "Alarms only" (not "Total silence") so the marker beep/vibration, which are
                // played with alarm usage, are still allowed through while DND is on.
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
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
                notificationManager.setInterruptionFilter(originalFilter)
                Toast.makeText(this, "DND Disabled", Toast.LENGTH_SHORT).show()
            } catch (e: SecurityException) {
                Toast.makeText(this, "Permission denied. Please grant DND access.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // -------------------------------------------------------------- Timer

    private fun startTimer() {
        if (!isRunning) {
            if (checkDndPermission()) {
                enableDnd()
                startTime = System.currentTimeMillis() - elapsedTime
                isRunning = true
                handler.post(updateTimer)
                scheduleAlarms()
                requestBatteryExemption()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startBtn.isActivated = true
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, TimerService::class.java)
                )
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
            stopService(Intent(this, TimerService::class.java))
            cancelAlarms()
        }
    }

    private fun resetTimer() {
        stopTimer()
        elapsedTime = 0L
        firedMarkers.clear()
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
        markerBar.progressMs = elapsedTime
    }

    // ------------------------------------------------------------ Markers

    private fun checkMarkers() {
        for (marker in markerBar.markers) {
            if (marker.timeMs <= elapsedTime && firedMarkers.add(marker)) {
                Alerts.fireOnce(this, alertId(marker), marker.type)
            }
        }
    }

    /** After any edit: markers already behind the current time count as fired, so they don't alert late. */
    private fun onMarkersChanged() {
        firedMarkers.clear()
        firedMarkers.addAll(markerBar.markers.filter { it.timeMs <= elapsedTime })
        saveMarkerState()
        markerBar.invalidate()
        if (isRunning) scheduleAlarms()
    }

    private fun setupToneSpinner() {
        val spinner = findViewById<Spinner>(R.id.toneSpinner)
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, Alerts.tones.map { it.name }
        ) {
            private fun tint(v: View): View = v.also { (it as TextView).setTextColor(0xFFDDDDDD.toInt()) }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                tint(super.getView(position, convertView, parent))
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) =
                super.getDropDownView(position, convertView, parent).also {
                    (it as TextView).setTextColor(0xFF000000.toInt())
                }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(prefs.getInt("beep_tone", 0).coerceIn(0, Alerts.tones.size - 1), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt("beep_tone", pos).apply()
                Alerts.beep(this@MainActivity)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** Seek bar that stores a 0-100 level and plays a preview when released. */
    private fun setupLevelBar(id: Int, key: String, preview: () -> Unit) {
        val bar = findViewById<SeekBar>(id)
        bar.progress = prefs.getInt(key, 50)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) prefs.edit().putInt(key, p).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = preview()
        })
    }

    // Backup for a locked screen: the activity fires alerts itself while it is running, and these alarms
    // fire them if the app is frozen. Alerts.fireOnce prevents doubles.
    private fun scheduleAlarms() {
        cancelAlarms()
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // setAlarmClock is exempt from Doze and OEM battery deferral, so alerts fire on time while locked.
        val showApp = PendingIntent.getActivity(
            this, 100, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        markerBar.markers.forEachIndexed { i, marker ->
            if (marker.timeMs <= elapsedTime) return@forEachIndexed
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(startTime + marker.timeMs, showApp),
                alarmIntent(i, marker)
            )
        }
        scheduledCount = markerBar.markers.size
    }

    private fun cancelAlarms() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until maxOf(scheduledCount, markerBar.markers.size)) {
            am.cancel(alarmIntent(i, null))
        }
        scheduledCount = 0
    }

    private fun alertId(marker: AlertMarker) = "$startTime:${marker.timeMs}"

    private fun alarmIntent(index: Int, marker: AlertMarker?): PendingIntent {
        val intent = Intent(this, AlertReceiver::class.java)
        if (marker != null) intent.putExtra("type", marker.type).putExtra("id", alertId(marker))
        return PendingIntent.getBroadcast(
            this, index, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: RuntimeException) {
                // Not available on this device; alarms still work.
            }
        }
    }

    private fun showMarkerDialog(existing: AlertMarker?, defaultTimeMs: Long) {
        val view = layoutInflater.inflate(R.layout.dialog_marker, null)
        val minutesInput = view.findViewById<EditText>(R.id.markerMinutes)
        val secondsInput = view.findViewById<EditText>(R.id.markerSeconds)
        val typeGroup = view.findViewById<RadioGroup>(R.id.markerTypeGroup)

        minutesInput.setText((defaultTimeMs / 60000).toString())
        secondsInput.setText(((defaultTimeMs / 1000) % 60).toString())
        val startType = existing?.type ?: lastType
        view.findViewById<RadioButton>(
            if (startType == AlertMarker.BEEP) R.id.typeBeep else R.id.typeVibrate
        ).isChecked = true

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add alert point" else "Edit alert point")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                var timeMs = readTimeMs(minutesInput, secondsInput)
                if (timeMs < 1000L) timeMs = 1000L
                if (timeMs > markerBar.maxMs) {
                    timeMs = markerBar.maxMs
                    Toast.makeText(
                        this,
                        "Past the end of the bar, set to ${MarkerProgressBar.formatTime(timeMs)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                val type =
                    if (typeGroup.checkedRadioButtonId == R.id.typeBeep) AlertMarker.BEEP
                    else AlertMarker.VIBRATE
                lastType = type
                if (existing == null) {
                    markerBar.markers.add(AlertMarker(timeMs, type))
                } else {
                    existing.timeMs = timeMs
                    existing.type = type
                }
                onMarkersChanged()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                markerBar.markers.remove(existing)
                onMarkersChanged()
            }
        }
        builder.show()
    }

    private fun showLengthDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_marker, null)
        val minutesInput = view.findViewById<EditText>(R.id.markerMinutes)
        val secondsInput = view.findViewById<EditText>(R.id.markerSeconds)
        view.findViewById<TextView>(R.id.markerTimeLabel).text = "Bar length"
        view.findViewById<View>(R.id.markerTypeLabel).visibility = View.GONE
        view.findViewById<View>(R.id.markerTypeGroup).visibility = View.GONE

        minutesInput.setText((markerBar.maxMs / 60000).toString())
        secondsInput.setText(((markerBar.maxMs / 1000) % 60).toString())

        AlertDialog.Builder(this)
            .setTitle("Set bar length")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val newMax = readTimeMs(minutesInput, secondsInput)
                val lastMarker = markerBar.markers.maxOfOrNull { it.timeMs } ?: 0L
                when {
                    newMax < 5000L ->
                        Toast.makeText(this, "Length must be at least 0:05", Toast.LENGTH_SHORT).show()
                    newMax < lastMarker ->
                        Toast.makeText(
                            this,
                            "Last alert point is at ${MarkerProgressBar.formatTime(lastMarker)}; " +
                                "remove or move it first",
                            Toast.LENGTH_LONG
                        ).show()
                    else -> {
                        markerBar.maxMs = newMax
                        updateLengthButton()
                        saveMarkerState()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun readTimeMs(minutesInput: EditText, secondsInput: EditText): Long {
        val minutes = minutesInput.text.toString().toLongOrNull() ?: 0L
        val seconds = secondsInput.text.toString().toLongOrNull() ?: 0L
        return (minutes * 60L + seconds) * 1000L
    }

    private fun updateLengthButton() {
        lengthBtn.text = "Length ${MarkerProgressBar.formatTime(markerBar.maxMs)}"
    }

    // -------------------------------------------------------- Persistence

    private fun saveMarkerState() {
        val encoded = markerBar.markers.joinToString(",") { "${it.timeMs}:${it.type}" }
        prefs.edit()
            .putLong("bar_length_ms", markerBar.maxMs)
            .putString("markers", encoded)
            .apply()
    }

    private fun loadMarkerState() {
        markerBar.maxMs = prefs.getLong("bar_length_ms", markerBar.maxMs)
        val encoded = prefs.getString("markers", "") ?: ""
        markerBar.markers.clear()
        for (part in encoded.split(",")) {
            val pieces = part.split(":")
            if (pieces.size != 2) continue
            val time = pieces[0].toLongOrNull() ?: continue
            val type = pieces[1].toIntOrNull() ?: continue
            markerBar.markers.add(AlertMarker(time.coerceIn(1000L, markerBar.maxMs), type))
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
