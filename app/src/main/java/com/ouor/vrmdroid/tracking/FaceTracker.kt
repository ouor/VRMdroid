package com.ouor.vrmdroid.tracking

import android.graphics.Bitmap

/**
 * A face tracking backend. Implementations receive upright (rotation-corrected) camera frames
 * and report results asynchronously through [listener].
 *
 * MediaPipe is the default; other models can be dropped in by implementing this interface and
 * adding them to [FaceTrackerFactory].
 */
interface FaceTracker : AutoCloseable {
    var listener: ((FaceFrame) -> Unit)?

    /** Human-readable backend name for the status line. */
    val name: String

    /**
     * True when a new frame would be accepted. Callers check this before converting a camera
     * image, so frames that would be dropped anyway cost nothing.
     */
    val isReady: Boolean

    /**
     * Submits an upright, unmirrored frame. The tracker may read [bitmap] until its result is
     * delivered, so the caller must not overwrite it before then (see [isReady]).
     */
    fun submit(bitmap: Bitmap, timestampMs: Long)
}
