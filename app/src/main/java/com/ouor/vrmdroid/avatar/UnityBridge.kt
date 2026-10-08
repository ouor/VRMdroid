package com.ouor.vrmdroid.avatar

import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Kept in step with VrmDroid.AvatarState in Unity (HostBridge.cs). */
enum class AvatarState { NONE, LOADING, FAILED, RESTORED }

/**
 * Entry point for calls from the Unity player (HostBridge.cs, via JNI). Lives in the main source
 * set so the stub build compiles too; there it is simply never called.
 */
@Keep
object UnityBridge {
    private val _avatarState = MutableStateFlow(AvatarState.NONE)
    val avatarState: StateFlow<AvatarState> = _avatarState.asStateFlow()

    private val _avatarSettled = MutableStateFlow(false)
    /**
     * True once the first avatar load has finished, shown or failed. NONE is both the state
     * before Unity reports anything and the state after a load, so this is what tells them apart.
     */
    val avatarSettled: StateFlow<Boolean> = _avatarSettled.asStateFlow()

    @JvmStatic
    @Keep
    fun onAvatarState(state: Int) {
        val next = AvatarState.entries.getOrElse(state) { AvatarState.NONE }
        if (_avatarState.value == AvatarState.LOADING && next != AvatarState.LOADING) _avatarSettled.value = true
        _avatarState.value = next
    }
}
