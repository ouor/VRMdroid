package com.ouor.vrmdroid.output

import com.ouor.vrmdroid.processing.EyeGaze
import com.ouor.vrmdroid.settings.TrackingConfig
import com.ouor.vrmdroid.tracking.Arkit
import com.ouor.vrmdroid.tracking.FaceFrame
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The iFacialMocap text payload, kept free of sockets so it can be unit tested:
 *
 *     eyeBlink_L-37|...|tongueOut-0|=head#pitch,yaw,roll,x,y,z|rightEye#p,y,r|leftEye#p,y,r|
 *
 * Blendshapes are integers 0..100; angles in degrees, positions in meters, 4 decimals.
 */
object IFacialMocapFormat {

    /** Writes the payload for [f] into [out] (cleared first). Allocation-free apart from growth. */
    fun encode(f: FaceFrame, config: TrackingConfig, out: StringBuilder) {
        out.setLength(0)
        for (shape in Arkit.entries) {
            out.append(shape.iFacialMocapName).append('-')
                .append((f.blendshapes[shape.ordinal] * 100f).roundToInt().coerceIn(0, 100))
                .append('|')
        }
        out.append("=head#")
        appendFixed(out, if (config.invertPitch) -f.pitch else f.pitch).append(',')
        appendFixed(out, if (config.invertYaw) -f.yaw else f.yaw).append(',')
        appendFixed(out, if (config.invertRoll) -f.roll else f.roll).append(',')
        appendFixed(out, f.x).append(',')
        appendFixed(out, f.y).append(',')
        appendFixed(out, f.z)
        val (rp, ry) = EyeGaze.subjectEyeEuler(f, left = false)
        val (lp, ly) = EyeGaze.subjectEyeEuler(f, left = true)
        out.append("|rightEye#")
        appendFixed(out, rp).append(',')
        appendFixed(out, ry).append(',')
        appendFixed(out, 0f)
        out.append("|leftEye#")
        appendFixed(out, lp).append(',')
        appendFixed(out, ly).append(',')
        appendFixed(out, 0f)
        out.append('|')
    }

    /** Same output as String.format(Locale.US, "%.4f", v) without the Formatter allocation. */
    internal fun appendFixed(out: StringBuilder, v: Float): StringBuilder {
        if (v.isNaN() || v.isInfinite()) return out.append("0.0000")
        val scaled = (abs(v.toDouble()) * 10_000).roundToLong()
        if (v < 0 && scaled != 0L) out.append('-')
        out.append(scaled / 10_000).append('.')
        val frac = (scaled % 10_000).toInt()
        if (frac < 1000) out.append('0')
        if (frac < 100) out.append('0')
        if (frac < 10) out.append('0')
        return out.append(frac)
    }
}
