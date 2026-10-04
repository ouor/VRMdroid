package com.ouor.vrmdroid.processing

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3x3 rotation matrices (row-major FloatArray(9)) for the head pose convention used throughout:
 * R = Ry(yaw) · Rx(pitch) · Rz(roll), angles in degrees.
 */
object Rotation {

    fun fromEuler(pitchDeg: Float, yawDeg: Float, rollDeg: Float): FloatArray {
        val p = Math.toRadians(pitchDeg.toDouble())
        val y = Math.toRadians(yawDeg.toDouble())
        val r = Math.toRadians(rollDeg.toDouble())
        val cp = cos(p); val sp = sin(p)
        val cy = cos(y); val sy = sin(y)
        val cr = cos(r); val sr = sin(r)
        // Ry · Rx · Rz expanded.
        return floatArrayOf(
            (cy * cr + sy * sp * sr).toFloat(), (-cy * sr + sy * sp * cr).toFloat(), (sy * cp).toFloat(),
            (cp * sr).toFloat(), (cp * cr).toFloat(), (-sp).toFloat(),
            (-sy * cr + cy * sp * sr).toFloat(), (sy * sr + cy * sp * cr).toFloat(), (cy * cp).toFloat(),
        )
    }

    /** Inverse of [fromEuler]: returns (pitch, yaw, roll) in degrees. */
    fun toEuler(m: FloatArray): FloatArray {
        val pitch = Math.toDegrees(asin((-m[5]).coerceIn(-1f, 1f).toDouble()))
        val yaw = Math.toDegrees(atan2(m[2].toDouble(), m[8].toDouble()))
        val roll = Math.toDegrees(atan2(m[3].toDouble(), m[4].toDouble()))
        return floatArrayOf(pitch.toFloat(), yaw.toFloat(), roll.toFloat())
    }

    /** Rotation of [current] relative to [reference]: referenceᵀ · current. */
    fun relative(reference: FloatArray, current: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var sum = 0f
            for (k in 0 until 3) sum += reference[k * 3 + i] * current[k * 3 + j]
            out[i * 3 + j] = sum
        }
        return out
    }
}
