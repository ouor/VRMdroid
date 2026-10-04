package com.ouor.vrmdroid.settings

/**
 * Immutable snapshot of the settings the per-frame pipeline reads (processing and sending).
 * Rebuilt only when a preference changes, so the hot path never touches SharedPreferences, and
 * plain enough to construct directly in JVM unit tests.
 */
data class TrackingConfig(
    val mirror: Boolean = true,
    val smoothing: Int = AppSettings.DEFAULT_SMOOTHING,
    val blinkSensitivity: Int = AppSettings.DEFAULT_SENSITIVITY,
    val mouthSensitivity: Int = AppSettings.DEFAULT_SENSITIVITY,
    val linkEyes: Boolean = false,
    val sendRate: Int = 60,
    val invertPitch: Boolean = false,
    val invertYaw: Boolean = false,
    val invertRoll: Boolean = false,
    val vmcSendPosition: Boolean = true,
    val vmcPositionScale: Int = 100,
    val vmcSendVrmPresets: Boolean = true,
    val vmcSendPerfectSync: Boolean = true,
    val vmcSendEmotions: Boolean = false,
    val vmcSendArmPose: Boolean = true,
) {
    companion object {
        fun from(s: AppSettings) = TrackingConfig(
            mirror = s.mirror,
            smoothing = s.smoothing,
            blinkSensitivity = s.blinkSensitivity,
            mouthSensitivity = s.mouthSensitivity,
            linkEyes = s.linkEyes,
            sendRate = s.sendRate,
            invertPitch = s.invertPitch,
            invertYaw = s.invertYaw,
            invertRoll = s.invertRoll,
            vmcSendPosition = s.vmcSendPosition,
            vmcPositionScale = s.vmcPositionScale,
            vmcSendVrmPresets = s.vmcSendVrmPresets,
            vmcSendPerfectSync = s.vmcSendPerfectSync,
            vmcSendEmotions = s.vmcSendEmotions,
            vmcSendArmPose = s.vmcSendArmPose,
        )
    }
}
