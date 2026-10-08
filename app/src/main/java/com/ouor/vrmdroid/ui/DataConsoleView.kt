package com.ouor.vrmdroid.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ouor.vrmdroid.R
import com.ouor.vrmdroid.output.SentDataMonitor
import com.ouor.vrmdroid.processing.EyeGaze
import com.ouor.vrmdroid.processing.TrackingResult
import com.ouor.vrmdroid.service.PcLink
import com.ouor.vrmdroid.service.TrackingStatus
import com.ouor.vrmdroid.settings.OutputProtocol
import com.ouor.vrmdroid.tracking.Arkit
import com.ouor.vrmdroid.tracking.FaceFrame
import java.util.Locale

/**
 * Real-time debug console: what the PC senders actually transmitted (rate and destination,
 * head angles, all 52 blendshape values as a sparkline, gaze, and the raw payload), or, with no
 * PC yet, what the phone is tracking.
 *
 * Purely informational: it never takes touches, so avatar gestures work through it.
 */
class DataConsoleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val tx: TextView
    private val head: TextView
    private val face: SparklineView
    private val gaze: TextView
    private val raw: TextView
    private val dp = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.argb(150, 0, 0, 0))
            cornerRadius = 12 * dp
        }
        setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        isClickable = false
        isFocusable = false

        tx = line(ContextCompat.getColor(context, R.color.status_ok)).also { addView(it) }
        head = line().also { addView(it) }
        face = SparklineView(context).apply {
            // Visual breaks between brow · cheek · eye · jaw · mouth · nose/tongue.
            groupStarts = setOf(
                Arkit.CheekPuff.ordinal, Arkit.EyeBlinkLeft.ordinal, Arkit.JawForward.ordinal,
                Arkit.MouthClose.ordinal, Arkit.NoseSneerLeft.ordinal,
            )
        }
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(line().apply { text = "face  " })
            addView(face, LayoutParams(0, (13 * dp).toInt(), 1f))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (2 * dp).toInt(); bottomMargin = (2 * dp).toInt()
        })
        gaze = line().also { addView(it) }
        raw = line(Color.argb(150, 230, 228, 240)).also { addView(it) }
    }

    private fun line(color: Int = Color.argb(230, 230, 228, 240)) = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 10.5f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(color)
    }

    /**
     * Refresh from [SentDataMonitor]; call a few times per second while visible. With nothing
     * going to a PC, the head, face and gaze lines show what the phone is tracking instead, so
     * the console is useful before any PC is connected.
     */
    fun refresh(status: TrackingStatus, latest: TrackingResult?) {
        val sample = SentDataMonitor.last
        val (packets, bytes) = SentDataMonitor.rate()
        if (sample == null || packets == 0) {
            tx.text = when {
                !status.running -> "tx    idle · tracking off"
                status.pcLink == PcLink.OFF -> String.format(Locale.US, "tx    off · phone only · track %.0fHz", status.fps)
                else -> String.format(Locale.US, "tx    idle · waiting for PC · track %.0fHz", status.fps)
            }
            val local = latest?.subject?.takeIf { status.running && it.detected }
            if (local == null) {
                head.text = "head  -"
                face.values = FloatArray(Arkit.COUNT)
                gaze.text = "gaze  -"
            } else {
                showFace(local)
            }
            raw.text = "raw   -"
            return
        }
        val proto = if (sample.protocol == OutputProtocol.VMC) "vmc" else "ifm"
        tx.text = String.format(Locale.US, "tx    %.1fKB/s · %dHz · %s → %s", bytes / 1024f, packets, proto, sample.destination)
        showFace(sample.result.subject)
        raw.text = "raw   " + sample.payload.replace('\n', ' ')
    }

    private fun showFace(f: FaceFrame) {
        head.text = String.format(Locale.US, "head  pitch %6.1f  yaw %6.1f  roll %6.1f", f.pitch, f.yaw, f.roll)
        face.values = f.blendshapes
        gaze.text = gazeText(f)
    }

    private fun gazeText(f: FaceFrame): String {
        val (lp, ly) = EyeGaze.subjectEyeEuler(f, left = true)
        val (rp, ry) = EyeGaze.subjectEyeEuler(f, left = false)
        return String.format(Locale.US, "gaze  L(%5.1f, %5.1f)  R(%5.1f, %5.1f)", ly, lp, ry, rp)
    }
}
