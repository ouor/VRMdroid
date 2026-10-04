package com.ouor.vrmdroid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ouor.vrmdroid.R

/**
 * One thin bar per value (0..1), drawn left to right. [groupStarts] adds a small gap before
 * those indices so related values (brow, eye, mouth, …) read as clusters.
 */
class SparklineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var values: FloatArray = FloatArray(0)
        set(value) { field = value; invalidate() }

    var groupStarts: Set<Int> = emptySet()

    private val dp = resources.displayMetrics.density
    private val bar = Paint().apply { color = ContextCompat.getColor(context, R.color.brand) }
    private val track = Paint().apply { color = Color.argb(40, 255, 255, 255) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize((14 * dp).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return
        val groupGap = 3 * dp
        val barGap = 1 * dp
        val gaps = groupStarts.count { it in 1 until n } * groupGap + (n - 1) * barGap
        val w = ((width - gaps) / n).coerceAtLeast(1f)
        val h = height.toFloat()
        var x = 0f
        for (i in 0 until n) {
            if (i > 0) x += barGap + if (i in groupStarts) groupGap else 0f
            canvas.drawRect(x, 0f, x + w, h, track)
            val v = values[i].coerceIn(0f, 1f)
            canvas.drawRect(x, h - (h * v).coerceAtLeast(dp * 0.5f), x + w, h, bar)
            x += w
        }
    }
}
