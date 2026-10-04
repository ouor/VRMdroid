package com.ouor.vrmdroid.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/**
 * Where a multi-step screen is (onboarding step, chosen PC program, settings section open),
 * kept in a [SavedStateHandle] so it survives rotation and the process being reclaimed. The
 * screens are rebuilt from this single value, so it is all they need to restore.
 */
class StepViewModel(private val handle: SavedStateHandle) : ViewModel() {
    var step: String?
        get() = handle[KEY_STEP]
        set(value) { handle[KEY_STEP] = value }

    private companion object {
        const val KEY_STEP = "step"
    }
}
