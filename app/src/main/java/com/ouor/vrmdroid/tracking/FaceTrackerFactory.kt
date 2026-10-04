package com.ouor.vrmdroid.tracking

import android.content.Context
import com.ouor.vrmdroid.settings.AppSettings

/** Creates the tracker selected in settings. Register new backends here. */
object FaceTrackerFactory {
    fun create(context: Context, settings: AppSettings): FaceTracker = when (settings.trackerBackend) {
        else -> MediaPipeFaceTracker(context, settings.useGpu)
    }
}
