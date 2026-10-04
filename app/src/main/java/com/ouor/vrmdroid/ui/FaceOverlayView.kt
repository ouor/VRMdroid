package com.ouor.vrmdroid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import com.ouor.vrmdroid.R
import com.ouor.vrmdroid.processing.TrackingResult
import com.ouor.vrmdroid.tracking.Arkit
import java.util.Locale
import kotlin.math.min

/**
 * Debug view: face landmarks (drawn mirrored, like a selfie), head angles, and the strongest
 * blendshapes as bars. Deliberately shows no camera image.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var result: TrackingResult? = null
        set(value) { field = value; invalidate() }

    /** Small round inset over the avatar: translucent, landmarks only. */
    var compact = false
        set(value) {
            field = value
            outlineProvider = if (value) object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            } else ViewOutlineProvider.BACKGROUND
            clipToOutline = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 220, 255)
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val lostPaint = Paint(pointPaint).apply { color = Color.rgb(110, 110, 110) }
    /** Landmarks in view coordinates, reused so a redraw per frame allocates nothing. */
    private var points = FloatArray(0)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
    }
    private val barBg = Paint().apply { color = Color.argb(60, 255, 255, 255) }
    private val barFg = Paint().apply { color = Color.rgb(255, 170, 60) }
    private val order = Array(Arkit.COUNT) { it }

    override fun onDraw(canvas: Canvas) {
        val r = result
        if (compact) {
            canvas.drawColor(Color.argb(170, 26, 24, 38))
            if (r == null) return
        } else {
            canvas.drawColor(Color.rgb(18, 18, 22))
            if (r == null) {
                canvas.drawText(context.getString(R.string.overlay_tracking_off), 16 * density, 28 * density, textPaint)
                return
            }
        }

        // Fit the camera frame into the view, mirrored horizontally.
        val iw = r.imageWidth.takeIf { it > 0 } ?: 480
        val ih = r.imageHeight.takeIf { it > 0 } ?: 640
        val scale = min(width.toFloat() / iw, height.toFloat() / ih)
        val ox = (width - iw * scale) / 2f
        val oy = (height - ih * scale) / 2f
        val lm = r.landmarks
        if (points.size < lm.size) points = FloatArray(lm.size)
        var i = 0
        while (i + 1 < lm.size) {
            points[i] = ox + (1f - lm[i]) * iw * scale
            points[i + 1] = oy + lm[i + 1] * ih * scale
            i += 2
        }
        // One call for all ~478 points instead of a circle each.
        canvas.drawPoints(points, 0, i, if (r.subject.detected) pointPaint else lostPaint)

        val s = r.subject
        if (compact) return
        val line = 16f * density
        var ty = 24f * density
        if (s.detected) {
            canvas.drawText(String.format(Locale.US, "pitch %6.1f  yaw %6.1f  roll %6.1f", s.pitch, s.yaw, s.roll), 12 * density, ty, textPaint)
            ty += line
            canvas.drawText(String.format(Locale.US, "x %5.2f  y %5.2f  z %5.2f m", s.x, s.y, s.z), 12 * density, ty, textPaint)
        } else {
            canvas.drawText(context.getString(R.string.overlay_searching), 12 * density, ty, textPaint)
        }

        // Top blendshapes.
        val shapes = s.blendshapes
        order.sortByDescending { shapes[it] }
        val barW = 90f * density
        val barH = 10f * density
        val left = width - barW - 12 * density
        var by = 16f * density
        for (k in 0 until 10) {
            val idx = order[k]
            val v = shapes[idx]
            canvas.drawRect(left, by, left + barW, by + barH, barBg)
            canvas.drawRect(left, by, left + barW * v, by + barH, barFg)
            val label = Arkit.entries[idx].arkitName
            canvas.drawText(label, left - textPaint.measureText(label) - 6 * density, by + barH, textPaint)
            by += barH + 6 * density
        }
    }
}
