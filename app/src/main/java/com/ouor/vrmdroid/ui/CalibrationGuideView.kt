package com.ouor.vrmdroid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ouor.vrmdroid.R
import kotlin.math.min

/**
 * "Look straight ahead" guide: a face-sized ring with a progress arc, a big countdown number and
 * one line of instruction. Purely visual; [com.ouor.vrmdroid.MainActivity] drives its state.
 */
class CalibrationGuideView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { COUNTDOWN, NO_FACE, DONE }

    var mode = Mode.COUNTDOWN
        set(value) { field = value; invalidate() }

    /** 0..1 progress of the hold-still countdown. */
    var progress = 0f
        set(value) { field = value; invalidate() }

    var countdown = 3
        set(value) { field = value; invalidate() }

    // Named so it can't be shadowed: TextPaint has its own `density` field (1.0), and using it
    // inside TextPaint.apply{} made the instruction text 18px instead of 18sp.
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val brand = ContextCompat.getColor(context, R.color.brand)
    private val ok = ContextCompat.getColor(context, R.color.status_ok)
    private val wait = ContextCompat.getColor(context, R.color.status_wait)

    private val scrim = Paint().apply { color = Color.argb(150, 15, 14, 23) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        color = Color.argb(90, 255, 255, 255)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6 * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 64 * dp
        isFakeBoldText = true
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22 * sp
        isFakeBoldText = true
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8 * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val cx = width / 2f
        val cy = height * 0.42f
        val rx = min(width * 0.32f, 150 * dp)
        val ry = rx * 1.3f
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(oval, ring)

        val message: String
        when (mode) {
            Mode.COUNTDOWN -> {
                arc.color = brand
                canvas.drawArc(oval, -90f, 360f * progress, false, arc)
                canvas.drawText(countdown.toString(), cx, cy + numberPaint.textSize / 3f, numberPaint)
                message = context.getString(R.string.calib_title)
            }
            Mode.NO_FACE -> {
                arc.color = wait
                canvas.drawOval(oval, arc)
                message = context.getString(R.string.calib_no_face)
            }
            Mode.DONE -> {
                arc.color = ok
                canvas.drawOval(oval, arc)
                val s = rx * 0.45f
                canvas.drawLine(cx - s, cy, cx - s * 0.25f, cy + s * 0.7f, checkPaint)
                canvas.drawLine(cx - s * 0.25f, cy + s * 0.7f, cx + s, cy - s * 0.6f, checkPaint)
                message = context.getString(R.string.calib_done)
            }
        }

        val textWidth = (width - 64 * dp).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(message, 0, message.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate(32 * dp, oval.bottom + 32 * dp)
        layout.draw(canvas)
        canvas.restore()
    }
}
