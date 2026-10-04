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
     * Submits a frame as delivered by the camera (unmirrored). [rotationDegrees] is the clockwise
     * rotation that makes it upright. Ownership of [bitmap] passes to the tracker.
     * The tracker may drop frames while busy.
     */
    fun submit(bitmap: Bitmap, rotationDegrees: Int, timestampMs: Long)
}
