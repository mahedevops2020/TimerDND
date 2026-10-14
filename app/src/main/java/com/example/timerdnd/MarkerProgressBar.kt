package com.example.timerdnd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToLong

/** A point on the bar where the timer should vibrate or beep. */
class AlertMarker(var timeMs: Long, var type: Int) {
    companion object {
        const val VIBRATE = 0
        const val BEEP = 1
    }
}

/**
 * Horizontal progress bar with user-placed alert markers.
 * Tap empty space on the bar to request a new marker; tap an existing marker to edit it.
 */
class MarkerProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var maxMs: Long = 10 * 60 * 1000L
        set(value) {
            field = value.coerceAtLeast(1000L)
            invalidate()
        }

    var progressMs: Long = 0L
        set(value) {
            field = value.coerceAtLeast(0L)
            invalidate()
        }

    val markers = mutableListOf<AlertMarker>()

    /** Called with the tapped time (whole seconds, in ms) when the user taps empty space. */
    var onAddRequested: ((Long) -> Unit)? = null

    /** Called when the user taps an existing marker. */
    var onMarkerTapped: ((AlertMarker) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val sidePad = dp(24f)
    private val trackHeight = dp(8f)
    private val markerRadius = dp(9f)
    private val hitSlop = dp(24f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2D7A2D.toInt() }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF000000.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textSize = sp(11f)
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val trackRect = RectF()

    private fun colorFor(type: Int) =
        if (type == AlertMarker.BEEP) 0xFF26C6DA.toInt() else 0xFFFFB300.toInt()

    private fun xFor(timeMs: Long): Float {
        val left = sidePad
        val right = width - sidePad
        val frac = (timeMs.toFloat() / maxMs).coerceIn(0f, 1f)
        return left + frac * (right - left)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val left = sidePad
        val right = width - sidePad

        // Track
        trackRect.set(left, cy - trackHeight / 2, right, cy + trackHeight / 2)
        canvas.drawRoundRect(trackRect, trackHeight / 2, trackHeight / 2, trackPaint)

        // Progress fill
        val fillX = xFor(progressMs)
        if (fillX > left) {
            trackRect.set(left, cy - trackHeight / 2, fillX, cy + trackHeight / 2)
            canvas.drawRoundRect(trackRect, trackHeight / 2, trackHeight / 2, fillPaint)
        }

        // Markers, sorted so labels can alternate above/below and not collide with neighbours
        val sorted = markers.sortedBy { it.timeMs }
        sorted.forEachIndexed { index, marker ->
            val x = xFor(marker.timeMs)
            val reached = marker.timeMs <= progressMs
            markerPaint.color = colorFor(marker.type)
            markerPaint.alpha = if (reached) 110 else 255
            canvas.drawCircle(x, cy, markerRadius, markerPaint)
            canvas.drawCircle(x, cy, markerRadius, ringPaint)

            val label = formatTime(marker.timeMs)
            val textW = textPaint.measureText(label)
            val tx = (x - textW / 2).coerceIn(dp(2f), width - textW - dp(2f))
            val above = index % 2 == 0
            val ty = if (above) {
                cy - markerRadius - dp(8f)
            } else {
                cy + markerRadius + dp(8f) + textPaint.textSize
            }
            canvas.drawText(label, tx, ty, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                handleTap(event.x)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleTap(x: Float) {
        val nearest = markers.minByOrNull { abs(xFor(it.timeMs) - x) }
        if (nearest != null && abs(xFor(nearest.timeMs) - x) <= hitSlop) {
            onMarkerTapped?.invoke(nearest)
            return
        }
        val span = (width - 2 * sidePad).coerceAtLeast(1f)
        val frac = ((x - sidePad) / span).coerceIn(0f, 1f)
        val seconds = (frac * maxMs / 1000f).roundToLong().coerceAtLeast(1L)
        onAddRequested?.invoke((seconds * 1000L).coerceAtMost(maxMs))
    }

    companion object {
        fun formatTime(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
        }
    }
}
