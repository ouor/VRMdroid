package com.ouor.vrmdroid.processing

import kotlin.math.cos
import kotlin.math.sin

/** Minimal quaternion helpers; quaternions are FloatArray(x, y, z, w). */
object Quat {
    val IDENTITY: FloatArray get() = floatArrayOf(0f, 0f, 0f, 1f)

    /**
     * Same as Unity's `Quaternion.Euler(pitch, yaw, roll)`: rotates about z, then x, then y
     * (q = qy * qx * qz). Angles in degrees.
     */
    fun fromUnityEuler(pitchDeg: Float, yawDeg: Float, rollDeg: Float): FloatArray {
        val hx = Math.toRadians(pitchDeg.toDouble()) * 0.5
        val hy = Math.toRadians(yawDeg.toDouble()) * 0.5
        val hz = Math.toRadians(rollDeg.toDouble()) * 0.5
        val sx = sin(hx); val cx = cos(hx)
        val sy = sin(hy); val cy = cos(hy)
        val sz = sin(hz); val cz = cos(hz)
        // qy * qx
        val x1 = cy * sx; val y1 = sy * cx; val z1 = -sy * sx; val w1 = cy * cx
        // (qy * qx) * qz
        return floatArrayOf(
            (x1 * cz + y1 * sz).toFloat(),
            (y1 * cz - x1 * sz).toFloat(),
            (w1 * sz + z1 * cz).toFloat(),
            (w1 * cz - z1 * sz).toFloat(),
        )
    }
}
