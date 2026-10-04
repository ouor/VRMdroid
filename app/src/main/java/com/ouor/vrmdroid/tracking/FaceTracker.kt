package com.ouor.vrmdroid.tracking

import java.nio.ByteBuffer

/**
 * A face tracking backend. Implementations receive upright (rotation-corrected) camera frames
 * and report results asynchronously through [listener].
 *
 * MediaPipe is the default; other models can be dropped in by implementing this interface and
 * adding them to [FaceTrackerFactory].
 */
interface FaceTracker : AutoCloseable {
    var listener: ((FaceFrame) -> Unit)?

    /**
     * Called once per submitted frame when the tracker is done with it, whether or not it produced
     * a result, before [listener] gets that result. The caller feeds the next frame from here, so
     * the tracker never sits idle waiting for the camera.
     */
    var frameDone: (() -> Unit)?

    /** Human-readable backend name for the status line. */
    val name: String

    /**
     * Submits an upright, unmirrored, tightly packed RGBA frame, one at a time: the next submit
     * waits for [frameDone]. [pixels] is only read during this call.
     */
    fun submit(pixels: ByteBuffer, width: Int, height: Int, timestampMs: Long)
}
