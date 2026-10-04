package com.ouor.vrmdroid.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

enum class OutputProtocol { IFACIALMOCAP, VMC, NONE }

/**
 * Typed view over the app's SharedPreferences. Keys are also used by the settings and sheet UIs
 * (via [com.ouor.vrmdroid.ui.Rows]), so keep them stable: renaming one loses users' values.
 */
class AppSettings(val prefs: SharedPreferences) {

    constructor(context: Context) : this(PreferenceManager.getDefaultSharedPreferences(context))

    val protocol: OutputProtocol
        get() = when (prefs.getString(KEY_PROTOCOL, "ifacialmocap")) {
            "vmc" -> OutputProtocol.VMC
            "none" -> OutputProtocol.NONE
            else -> OutputProtocol.IFACIALMOCAP
        }

    var targetHost: String
        get() = prefs.getString(KEY_TARGET_HOST, "")!!.trim()
        set(value) = prefs.edit().putString(KEY_TARGET_HOST, value).apply()

    val vmcPort: Int get() = intPref(KEY_VMC_PORT, 39539)
    val iFacialMocapPort: Int get() = intPref(KEY_IFM_PORT, 49983)
    val autoDetectHost: Boolean get() = prefs.getBoolean(KEY_AUTO_DETECT, true)

    val mirror: Boolean get() = prefs.getBoolean(KEY_MIRROR, true)
    val sendRate: Int get() = intPref(KEY_SEND_RATE, 60).coerceIn(10, 120)

    val vmcSendPerfectSync: Boolean get() = prefs.getBoolean(KEY_VMC_PERFECT_SYNC, true)
    val vmcSendVrmPresets: Boolean get() = prefs.getBoolean(KEY_VMC_PRESETS, true)
    val vmcSendEmotions: Boolean get() = prefs.getBoolean(KEY_VMC_EMOTIONS, false)
    /** Sends a relaxed arm pose so receivers without arm tracking don't show a T-pose. */
    val vmcSendArmPose: Boolean get() = prefs.getBoolean(KEY_VMC_ARM_POSE, true)
    val vmcSendPosition: Boolean get() = prefs.getBoolean(KEY_VMC_POSITION, true)
    /** Percent applied to head translation before it is sent as the root offset. */
    val vmcPositionScale: Int get() = prefs.getInt(KEY_VMC_POSITION_SCALE, 100)

    val invertPitch: Boolean get() = prefs.getBoolean(KEY_INVERT_PITCH, false)
    val invertYaw: Boolean get() = prefs.getBoolean(KEY_INVERT_YAW, false)
    val invertRoll: Boolean get() = prefs.getBoolean(KEY_INVERT_ROLL, false)

    val trackerBackend: String get() = prefs.getString(KEY_TRACKER, "mediapipe")!!
    /**
     * GPU by default: with the avatar preview running, the CPU delegate was no faster (34 ms p50
     * either way on a Dimensity 8300) but had a worse p95 (57 vs 51 ms), ran hotter and used more
     * CPU.
     */
    val useGpu: Boolean get() = prefs.getBoolean(KEY_USE_GPU, true)

    /** 0 = raw, 100 = heavy smoothing. */
    val smoothing: Int get() = prefs.getInt(KEY_SMOOTHING, DEFAULT_SMOOTHING)
    /** Percent; 100 = calibrated default (see FaceProcessor for the blink curve). */
    val blinkSensitivity: Int get() = prefs.getInt(KEY_BLINK_SENSITIVITY, DEFAULT_SENSITIVITY)
    val mouthSensitivity: Int get() = prefs.getInt(KEY_MOUTH_SENSITIVITY, DEFAULT_SENSITIVITY)
    val linkEyes: Boolean get() = prefs.getBoolean(KEY_LINK_EYES, false)

    /** Small landmark inset on the main screen; off by default (it covers the avatar). */
    val previewEnabled: Boolean get() = prefs.getBoolean(KEY_PREVIEW_OVERLAY, false)

    /**
     * Seconds without a touch before the screen dims (0 = never). Unset means: dim after a
     * minute when sending to a PC, never in phone-only mode where the screen is the output.
     */
    val dimTimeoutSec: Int
        get() = prefs.getString(KEY_DIM_TIMEOUT, null)?.toIntOrNull()
            ?: if (protocol == OutputProtocol.NONE) 0 else DEFAULT_DIM_TIMEOUT

    /** Shows the real-time debug console (what is sent to the PC) on the main screen. */
    val dataConsole: Boolean get() = prefs.getBoolean(KEY_DATA_CONSOLE, false)

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    var protocolValue: String
        get() = prefs.getString(KEY_PROTOCOL, "ifacialmocap")!!
        set(value) = prefs.edit().putString(KEY_PROTOCOL, value).apply()

    /**
     * One-time fixes for values persisted by older versions. v2: the old settings screen saved
     * its defaults (blink 130%, mouth 120%, face dots on); those meanings changed.
     */
    fun migrate() {
        if (prefs.getInt(KEY_PREFS_VERSION, 1) >= 2) return
        prefs.edit()
            .remove(KEY_BLINK_SENSITIVITY).remove(KEY_MOUTH_SENSITIVITY).remove(KEY_PREVIEW_OVERLAY)
            .putInt(KEY_PREFS_VERSION, 2)
            .apply()
    }

    /** Restores the expression controls shown in the quick-adjust sheet. */
    fun resetExpression() {
        prefs.edit().apply { EXPRESSION_KEYS.forEach { remove(it) } }.apply()
    }

    /** Restores everything except where the PC is and whether onboarding was seen. */
    fun resetAll() {
        val keep = setOf(KEY_TARGET_HOST, KEY_ONBOARDING_DONE, KEY_PREFS_VERSION)
        prefs.edit().apply { prefs.all.keys.filter { it !in keep }.forEach { remove(it) } }.apply()
    }

    private fun intPref(key: String, default: Int): Int =
        prefs.getString(key, null)?.trim()?.toIntOrNull() ?: default

    companion object {
        const val KEY_PROTOCOL = "protocol"
        const val KEY_TARGET_HOST = "target_host"
        const val KEY_VMC_PORT = "vmc_port"
        const val KEY_IFM_PORT = "ifm_port"
        const val KEY_AUTO_DETECT = "auto_detect_host"
        const val KEY_MIRROR = "mirror"
        const val KEY_SEND_RATE = "send_rate"
        const val KEY_VMC_PERFECT_SYNC = "vmc_perfect_sync"
        const val KEY_VMC_PRESETS = "vmc_presets"
        const val KEY_VMC_EMOTIONS = "vmc_emotions"
        const val KEY_VMC_ARM_POSE = "vmc_arm_pose"
        const val KEY_VMC_POSITION = "vmc_position"
        const val KEY_VMC_POSITION_SCALE = "vmc_position_scale"
        const val KEY_INVERT_PITCH = "invert_pitch"
        const val KEY_INVERT_YAW = "invert_yaw"
        const val KEY_INVERT_ROLL = "invert_roll"
        const val KEY_TRACKER = "tracker"
        const val KEY_USE_GPU = "use_gpu"
        const val KEY_SMOOTHING = "smoothing"
        const val KEY_BLINK_SENSITIVITY = "blink_sensitivity"
        const val KEY_MOUTH_SENSITIVITY = "mouth_sensitivity"
        const val KEY_LINK_EYES = "link_eyes"
        const val KEY_PREVIEW_OVERLAY = "preview_overlay"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_PREFS_VERSION = "prefs_version"

        const val KEY_DIM_TIMEOUT = "dim_timeout"
        const val KEY_DATA_CONSOLE = "data_console"
        const val DEFAULT_DIM_TIMEOUT = 60

        const val DEFAULT_SMOOTHING = 40
        const val DEFAULT_SENSITIVITY = 100

        val EXPRESSION_KEYS = listOf(
            KEY_BLINK_SENSITIVITY, KEY_MOUTH_SENSITIVITY, KEY_SMOOTHING,
            KEY_MIRROR, KEY_LINK_EYES, KEY_PREVIEW_OVERLAY,
        )

        /** Keys whose change requires restarting the camera/tracker. */
        val RESTART_KEYS = setOf(KEY_TRACKER, KEY_USE_GPU)
    }
}
