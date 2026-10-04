package com.ouor.vrmdroid.avatar

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.view.View
import com.unity3d.player.IUnityPermissionRequestSupport
import com.unity3d.player.IUnityPlayerLifecycleEvents
import com.unity3d.player.IUnityPlayerSupport
import com.unity3d.player.PermissionRequest
import com.unity3d.player.UnityPlayer
import com.unity3d.player.UnityPlayerForActivityOrService

/**
 * Embeds the Unity avatar player as a View inside the host activity (compiled only when the
 * exported unityLibrary is present; see app/src/nounity for the stub).
 *
 * Unity's native code looks the player up through the activity, so the host activity must
 * implement [UnityActivitySupport].
 */
class UnityHost(private val activity: Activity) {

    private val player = UnityPlayerForActivityOrService(activity, object : IUnityPlayerLifecycleEvents {
        override fun onUnityPlayerUnloaded() = Unit
        override fun onUnityPlayerQuitted() = Unit
    })

    /** The view to place in the layout, or null when Unity isn't linked into this build. */
    val view: View? = player.frameLayout

    internal val connection: UnityPlayer get() = player

    fun onStart() = player.onStart()
    fun onStop() = player.onStop()
    fun onResume() = player.onResume()
    fun onPause() = player.onPause()

    /**
     * Throttles rendering while the screen is dimmed. Cheaper to wake from than pausing the
     * player, and the avatar keeps following the face in the meantime.
     */
    fun setLowPower(on: Boolean) = UnityPlayer.UnitySendMessage(UNITY_OBJECT, "SetLowPower", if (on) "1" else "0")

    /** Unity terminates the process here, so only call this when the app is really closing. */
    fun onDestroy() = player.destroy()

    fun onConfigurationChanged(config: Configuration) = player.configurationChanged(config)
    fun onWindowFocusChanged(hasFocus: Boolean) = player.windowFocusChanged(hasFocus)

    fun onTrimMemory(level: Int) {
        val usage = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> UnityPlayerForActivityOrService.MemoryUsage.Medium
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> UnityPlayerForActivityOrService.MemoryUsage.High
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> UnityPlayerForActivityOrService.MemoryUsage.Critical
            else -> return
        }
        player.onTrimMemory(usage)
    }

    internal fun addPermissionRequest(request: PermissionRequest) = player.addPermissionRequest(request)

    companion object {
        const val AVAILABLE = true

        /** The GameObject PreviewApp.Bootstrap creates. */
        private const val UNITY_OBJECT = "VrmDroid"
    }
}

/** Lets Unity's native side find the embedded player through the host activity. */
interface UnityActivitySupport : IUnityPlayerSupport, IUnityPermissionRequestSupport {
    val unityHost: UnityHost?

    override fun getUnityPlayerConnection(): UnityPlayer? = unityHost?.connection

    override fun requestPermissions(request: PermissionRequest) {
        unityHost?.addPermissionRequest(request)
    }
}
