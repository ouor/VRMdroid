package com.ouor.vrmdroid.processing

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al. 2012): low jitter when still, low lag when moving fast.
 */
class OneEuroFilter(
    var minCutoff: Float = 1.0f,
    var beta: Float = 0.0f,
    private val derivativeCutoff: Float = 1.0f,
) {
    private var initialized = false
    private var lastValue = 0f
    private var lastDerivative = 0f
    private var lastTimeMs = 0L

    fun reset() { initialized = false }

    fun filter(value: Float, timeMs: Long): Float {
        if (!initialized) {
            initialized = true
            lastValue = value
            lastDerivative = 0f
            lastTimeMs = timeMs
            return value
        }
        val dt = ((timeMs - lastTimeMs).coerceAtLeast(1)) / 1000f
        lastTimeMs = timeMs
        val derivative = (value - lastValue) / dt
        lastDerivative = lerp(lastDerivative, derivative, alpha(derivativeCutoff, dt))
        val cutoff = minCutoff + beta * abs(lastDerivative)
        lastValue = lerp(lastValue, value, alpha(cutoff, dt))
        return lastValue
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
