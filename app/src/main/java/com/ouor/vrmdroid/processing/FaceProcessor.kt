package com.ouor.vrmdroid.processing

import com.ouor.vrmdroid.settings.TrackingConfig
import com.ouor.vrmdroid.tracking.Arkit
import com.ouor.vrmdroid.tracking.FaceFrame

/**
 * Turns raw tracker output into what the senders need:
 *  - [TrackingResult.subject]: smoothed, calibrated, sensitivity-adjusted data still in ARKit's
 *    subject space (what an iPhone would send), used by the iFacialMocap sender.
 *  - [TrackingResult.avatar]: the same data converted to the avatar's point of view in Unity
 *    coordinates, mirrored if requested. Used by the VMC sender and the Unity preview.
 *
 * Not thread-safe; call from a single thread.
 */
class FaceProcessor {

    private val shapeFilters = Array(Arkit.COUNT) { OneEuroFilter() }
    private val poseFilters = Array(6) { OneEuroFilter() }

    /** Head rotation at calibration (see [Rotation]); identity until calibrated. */
    private var neutralRotation = Rotation.fromEuler(0f, 0f, 0f)
    private var neutralPosition = FloatArray(3)
    private var calibrateRequested = true
    private var lastResult: TrackingResult? = null
    private var appliedSmoothing = -1

    fun requestCalibration() { calibrateRequested = true }

    fun process(raw: FaceFrame, config: TrackingConfig): TrackingResult {
        if (!raw.detected) {
            // Hold the last pose so the avatar doesn't snap while the face is briefly lost.
            val last = lastResult
            return TrackingResult(
                subject = FaceFrame.lost(raw.timestampMs),
                avatar = last?.avatar?.copy(detected = false) ?: AvatarPose.NEUTRAL,
                landmarks = raw.landmarks,
                imageWidth = raw.imageWidth,
                imageHeight = raw.imageHeight,
            )
        }
        configureFilters(config.smoothing)

        if (calibrateRequested) {
            neutralRotation = Rotation.fromEuler(raw.pitch, raw.yaw, raw.roll)
            neutralPosition = floatArrayOf(raw.x, raw.y, raw.z)
            poseFilters.forEach { it.reset() }
            calibrateRequested = false
        }

        val t = raw.timestampMs
        val shapes = FloatArray(Arkit.COUNT)
        for (i in 0 until Arkit.COUNT) {
            shapes[i] = shapeFilters[i].filter(raw.blendshapes[i], t)
        }
        applySensitivity(shapes, config)

        val pose = floatArrayOf(raw.pitch, raw.yaw, raw.roll, raw.x, raw.y, raw.z)
        // Rotation relative to the calibrated pose, not per-angle subtraction: with the phone
        // below the face (neutral pitch around -20 degrees), subtracting angles mixed yaw
        // into roll, so turning the head also tilted the avatar.
        val rel = Rotation.toEuler(Rotation.relative(neutralRotation, Rotation.fromEuler(raw.pitch, raw.yaw, raw.roll)))
        pose[0] = rel[0]; pose[1] = rel[1]; pose[2] = rel[2]
        for (i in 3 until 6) pose[i] -= neutralPosition[i - 3]
        for (i in 0 until 6) pose[i] = poseFilters[i].filter(pose[i], t)

        val subject = FaceFrame(
            timestampMs = t,
            detected = true,
            blendshapes = shapes,
            pitch = pose[0], yaw = pose[1], roll = pose[2],
            x = pose[3], y = pose[4], z = pose[5] + neutralPosition[2],
            inferenceMs = raw.inferenceMs,
        )
        val result = TrackingResult(
            subject = subject,
            avatar = toAvatar(subject, pose, config.mirror),
            landmarks = raw.landmarks,
            imageWidth = raw.imageWidth,
            imageHeight = raw.imageHeight,
        )
        lastResult = result
        return result
    }

    private fun configureFilters(smoothing: Int) {
        if (smoothing == appliedSmoothing) return
        appliedSmoothing = smoothing
        val s = smoothing.coerceIn(0, 100) / 100f
        // Lower cutoff = smoother but laggier. Expressions get a higher floor so blinks stay crisp.
        val poseCutoff = 10f - 9.5f * s
        val shapeCutoff = 15f - 12f * s
        shapeFilters.forEach { it.minCutoff = shapeCutoff; it.beta = 1.5f }
        for (i in 0 until 3) poseFilters[i].apply { minCutoff = poseCutoff; beta = 0.02f }
        for (i in 3 until 6) poseFilters[i].apply { minCutoff = poseCutoff; beta = 4f }
    }

    private fun applySensitivity(shapes: FloatArray, config: TrackingConfig) {
        // Blink: MediaPipe reports ~0.1-0.3 for open, relaxed eyes, which made avatars look
        // sleepy when simply scaled. Ignore that floor and stretch the rest to fully closed.
        val blink = config.blinkSensitivity / 100f
        for (s in BLINK_SHAPES) {
            shapes[s.ordinal] = ((shapes[s.ordinal] * blink - BLINK_OPEN_FLOOR) / BLINK_RANGE)
        }
        val mouth = config.mouthSensitivity / 100f * MOUTH_GAIN
        for (s in MOUTH_SHAPES) shapes[s.ordinal] *= mouth
        if (config.linkEyes) {
            for ((l, r) in EYE_PAIRS) {
                val avg = (shapes[l.ordinal] + shapes[r.ordinal]) * 0.5f
                shapes[l.ordinal] = avg
                shapes[r.ordinal] = avg
            }
        }
        for (i in shapes.indices) shapes[i] = shapes[i].coerceIn(0f, 1f)
    }

    private fun toAvatar(subject: FaceFrame, pose: FloatArray, mirror: Boolean): AvatarPose {
        // Derivation (see FaceFrame for subject space): with the avatar facing the viewer and
        // copying the user like a mirror, Unity euler == subject euler and x keeps its sign.
        // Without mirroring, yaw, roll and x flip and left/right blendshapes are not swapped.
        val sign = if (mirror) 1f else -1f
        val headRot = Quat.fromUnityEuler(pose[0], sign * pose[1], sign * pose[2])
        val headPos = floatArrayOf(sign * pose[3], pose[4], pose[5])

        val shapes = if (mirror) {
            FloatArray(Arkit.COUNT) { subject.blendshapes[Arkit.MIRROR_INDEX[it]] }
        } else subject.blendshapes.copyOf()

        val (lp, ly) = EyeGaze.subjectEyeEuler(subject, left = true)
        val (rp, ry) = EyeGaze.subjectEyeEuler(subject, left = false)
        // Avatar's left eye follows the user's right eye when mirrored.
        val leftEye = if (mirror) Quat.fromUnityEuler(rp, ry, 0f) else Quat.fromUnityEuler(lp, -ly, 0f)
        val rightEye = if (mirror) Quat.fromUnityEuler(lp, ly, 0f) else Quat.fromUnityEuler(rp, -ry, 0f)

        return AvatarPose(
            detected = true,
            blendshapes = shapes,
            headRotation = headRot,
            headPosition = headPos,
            leftEye = leftEye,
            rightEye = rightEye,
            gazeYaw = sign * (ly + ry) * 0.5f,
            gazePitch = (lp + rp) * 0.5f,
        )
    }

    companion object {
        private const val BLINK_OPEN_FLOOR = 0.2f
        private const val BLINK_RANGE = 0.55f
        private const val MOUTH_GAIN = 1.2f
        private val BLINK_SHAPES = listOf(Arkit.EyeBlinkLeft, Arkit.EyeBlinkRight)
        private val MOUTH_SHAPES = listOf(
            Arkit.JawOpen, Arkit.MouthFunnel, Arkit.MouthPucker,
            Arkit.MouthLowerDownLeft, Arkit.MouthLowerDownRight,
            Arkit.MouthUpperUpLeft, Arkit.MouthUpperUpRight,
            Arkit.MouthStretchLeft, Arkit.MouthStretchRight,
            Arkit.MouthSmileLeft, Arkit.MouthSmileRight,
        )
        private val EYE_PAIRS = listOf(
            Arkit.EyeBlinkLeft to Arkit.EyeBlinkRight,
            Arkit.EyeSquintLeft to Arkit.EyeSquintRight,
            Arkit.EyeWideLeft to Arkit.EyeWideRight,
        )
    }
}

/** Processed output for one frame. */
class TrackingResult(
    val subject: FaceFrame,
    val avatar: AvatarPose,
    val landmarks: FloatArray,
    val imageWidth: Int,
    val imageHeight: Int,
)

/**
 * Avatar-relative pose in Unity coordinates (left-handed, +y up, avatar faces +z, its right is +x).
 * Rotations are local bone rotations relative to the rest pose, as (x, y, z, w) quaternions.
 */
data class AvatarPose(
    val detected: Boolean,
    /** Blendshapes from the avatar's point of view (left = avatar's left). */
    val blendshapes: FloatArray,
    val headRotation: FloatArray,
    /** Meters, relative to the calibrated position. */
    val headPosition: FloatArray,
    val leftEye: FloatArray,
    val rightEye: FloatArray,
    /** Combined gaze in degrees: positive yaw turns toward the avatar's right, positive pitch looks down. */
    val gazeYaw: Float = 0f,
    val gazePitch: Float = 0f,
) {
    operator fun get(shape: Arkit): Float = blendshapes[shape.ordinal]

    companion object {
        val NEUTRAL = AvatarPose(
            detected = false,
            blendshapes = FloatArray(Arkit.COUNT),
            headRotation = Quat.IDENTITY,
            headPosition = FloatArray(3),
            leftEye = Quat.IDENTITY,
            rightEye = Quat.IDENTITY,
        )
    }
}

/** Eye rotation estimated from ARKit eyeLook* blendshapes. */
object EyeGaze {
    const val MAX_YAW_DEG = 25f
    const val MAX_PITCH_DEG = 20f

    /**
     * Returns (pitch, yaw) in degrees in subject space: positive pitch looks down,
     * positive yaw turns toward the subject's left (same convention as the head).
     */
    fun subjectEyeEuler(f: FaceFrame, left: Boolean): Pair<Float, Float> = if (left) {
        val pitch = (f[Arkit.EyeLookDownLeft] - f[Arkit.EyeLookUpLeft]) * MAX_PITCH_DEG
        val yaw = (f[Arkit.EyeLookOutLeft] - f[Arkit.EyeLookInLeft]) * MAX_YAW_DEG
        pitch to yaw
    } else {
        val pitch = (f[Arkit.EyeLookDownRight] - f[Arkit.EyeLookUpRight]) * MAX_PITCH_DEG
        val yaw = (f[Arkit.EyeLookInRight] - f[Arkit.EyeLookOutRight]) * MAX_YAW_DEG
        pitch to yaw
    }
}
