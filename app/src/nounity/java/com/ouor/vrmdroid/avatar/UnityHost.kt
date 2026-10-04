package com.ouor.vrmdroid.avatar

import android.app.Activity
import android.content.res.Configuration
import android.view.View

/** Stub used when the Unity library hasn't been exported; see app/src/unity for the real one. */
class UnityHost(@Suppress("UNUSED_PARAMETER") activity: Activity) {
    val view: View? = null

    fun onStart() = Unit
    fun onStop() = Unit
    fun onResume() = Unit
    fun onPause() = Unit
    fun setLowPower(@Suppress("UNUSED_PARAMETER") on: Boolean) = Unit
    fun onDestroy() = Unit
    fun onConfigurationChanged(@Suppress("UNUSED_PARAMETER") config: Configuration) = Unit
    fun onWindowFocusChanged(@Suppress("UNUSED_PARAMETER") hasFocus: Boolean) = Unit
    fun onTrimMemory(@Suppress("UNUSED_PARAMETER") level: Int) = Unit

    companion object {
        const val AVAILABLE = false
    }
}

interface UnityActivitySupport {
    val unityHost: UnityHost?
}
