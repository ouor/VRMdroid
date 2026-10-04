package com.ouor.vrmdroid.service

/**
 * How hard the phone may work, from its thermal state. Long streams heat the phone until the
 * vendor throttles the CPU and GPU without warning, and tracking slows down. Stepping down the
 * avatar preview first (it's only a preview; the PC gets the tracking data) keeps tracking steady.
 */
enum class ThermalLevel(
    /** Highest idle frame rate for the avatar preview. */
    val maxAvatarFps: Int,
    /** Fraction of the screen resolution the avatar is drawn at. */
    val renderScale: Float,
    val outlines: Boolean,
    /** Highest tracking rate, or 0 for as fast as the camera goes. */
    val maxTrackingHz: Int,
) {
    NORMAL(maxAvatarFps = 30, renderScale = 0.7f, outlines = true, maxTrackingHz = 0),
    WARM(maxAvatarFps = 20, renderScale = 0.6f, outlines = true, maxTrackingHz = 0),
    HOT(maxAvatarFps = 15, renderScale = 0.5f, outlines = false, maxTrackingHz = 15),
}

/**
 * Picks the [ThermalLevel] from Android's thermal headroom (1.0 = the device starts throttling)
 * and thermal status. Steps up as soon as a threshold is crossed, but steps down only after the
 * reading has been [HYSTERESIS] below it for a while, so the preview doesn't flicker between
 * levels. Pure logic; [TrackingService] feeds it every few seconds.
 */
class ThermalGovernor {
    var level = ThermalLevel.NORMAL
        private set
    private var calmReadings = 0

    /**
     * [headroom] may be NaN when the device doesn't report it; [status] is a
     * `PowerManager.THERMAL_STATUS_*` value. Returns true when the level changed.
     */
    fun update(headroom: Float, status: Int): Boolean {
        val target = levelFor(headroom, status, margin = 0f)
        val previous = level
        when {
            target > level -> { level = target; calmReadings = 0 }
            target < level -> {
                // Only count readings that are clearly below the current level's threshold.
                if (levelFor(headroom, status, margin = HYSTERESIS) < level) calmReadings++ else calmReadings = 0
                if (calmReadings >= CALM_READINGS) { level = ThermalLevel.entries[level.ordinal - 1]; calmReadings = 0 }
            }
            else -> calmReadings = 0
        }
        return level != previous
    }

    private fun levelFor(headroom: Float, status: Int, margin: Float): ThermalLevel {
        val h = if (headroom.isNaN()) 0f else headroom + margin
        return when {
            h >= HOT_HEADROOM || status >= STATUS_MODERATE -> ThermalLevel.HOT
            h >= WARM_HEADROOM || status >= STATUS_LIGHT -> ThermalLevel.WARM
            else -> ThermalLevel.NORMAL
        }
    }

    companion object {
        const val WARM_HEADROOM = 0.95f
        const val HOT_HEADROOM = 1.0f
        const val HYSTERESIS = 0.05f
        /** Consecutive calm readings before stepping down (about 30 s at one reading per 5 s). */
        const val CALM_READINGS = 6
        // PowerManager.THERMAL_STATUS_LIGHT / _MODERATE, kept here so this stays pure JVM.
        const val STATUS_LIGHT = 1
        const val STATUS_MODERATE = 2
    }
}
