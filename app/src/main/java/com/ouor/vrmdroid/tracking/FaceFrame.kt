package com.ouor.vrmdroid.tracking

/**
 * Raw output of a [FaceTracker] for a single camera frame.
 *
 * Pose is expressed the way ARKit expresses a face anchor: right-handed camera space
 * (x = image right, y = up, z = toward the viewer), with the face's local +x pointing to the
 * subject's left and +z pointing out of the nose. This keeps iPhone-compatible output a
 * pass-through and leaves mirroring / Unity conversion to [FaceProcessor].
 */
class FaceFrame(
    /** Monotonic capture time in milliseconds. */
    val timestampMs: Long,
    val detected: Boolean,
    /** ARKit blendshape weights in `[0, 1]`, indexed by [Arkit.ordinal]. */
    val blendshapes: FloatArray = FloatArray(Arkit.COUNT),
    /** Head rotation as Euler angles in degrees, R = Ry(yaw) * Rx(pitch) * Rz(roll). */
    val pitch: Float = 0f,
    val yaw: Float = 0f,
    val roll: Float = 0f,
    /** Head position in meters, camera space. */
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    /** Normalized (0..1) landmark coordinates as x,y pairs, image orientation (unmirrored). */
    val landmarks: FloatArray = FloatArray(0),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** Time spent in inference, for the status display. */
    val inferenceMs: Long = 0,
) {
    operator fun get(shape: Arkit): Float = blendshapes[shape.ordinal]

    companion object {
        fun lost(timestampMs: Long) = FaceFrame(timestampMs, detected = false)
    }
}
