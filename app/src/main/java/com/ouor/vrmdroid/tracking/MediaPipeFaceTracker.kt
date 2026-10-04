package com.ouor.vrmdroid.tracking

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.ouor.vrmdroid.processing.Rotation
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaPipe Face Landmarker backend. It natively outputs ARKit-named blendshapes (minus
 * `tongueOut`) and a facial transformation matrix, so no landmark-to-expression solving is needed.
 */
class MediaPipeFaceTracker(context: Context, useGpu: Boolean) : FaceTracker {

    override var listener: ((FaceFrame) -> Unit)? = null
    override val name: String

    private val landmarker: FaceLandmarker
    private val busy = AtomicBoolean(false)
    @Volatile private var submittedAt = 0L
    private var lastTimestamp = -1L
    @Volatile private var frameSize = 0 to 0
    private var lastDiagnosticMs = 0L

    /** MediaPipe category index -> [Arkit] ordinal, resolved lazily by name on first result. */
    private var categoryMap: IntArray? = null

    init {
        var created: FaceLandmarker? = null
        var delegate = if (useGpu) Delegate.GPU else Delegate.CPU
        while (created == null) {
            try {
                created = FaceLandmarker.createFromOptions(context, options(delegate))
            } catch (e: Throwable) {
                // Some devices fail GPU setup with native errors rather than exceptions.
                if (delegate == Delegate.CPU) throw e
                Log.w(TAG, "GPU delegate unavailable, falling back to CPU", e)
                delegate = Delegate.CPU
            }
        }
        landmarker = created
        name = "MediaPipe ($delegate)"
    }

    private fun options(delegate: Delegate) = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(
            BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build()
        )
        .setRunningMode(RunningMode.LIVE_STREAM)
        .setNumFaces(1)
        .setMinFaceDetectionConfidence(0.5f)
        .setMinFacePresenceConfidence(0.5f)
        .setMinTrackingConfidence(0.5f)
        .setOutputFaceBlendshapes(true)
        .setOutputFacialTransformationMatrixes(true)
        .setResultListener(::onResult)
        .setErrorListener { e ->
            Log.e(TAG, "Face landmarker error", e)
            busy.set(false)
        }
        .build()

    override val isReady: Boolean
        get() {
            // Watchdog: if MediaPipe never answered (e.g. GPU context lost), don't stall forever.
            if (busy.get() && SystemClock.uptimeMillis() - submittedAt > STALL_MS) {
                Log.w(TAG, "No result for ${STALL_MS}ms; resubmitting")
                busy.set(false)
            }
            return !busy.get()
        }

    override fun submit(bitmap: Bitmap, timestampMs: Long) {
        // LIVE_STREAM queues internally; dropping while busy keeps latency at one frame.
        if (!busy.compareAndSet(false, true)) return
        // MediaPipe rejects non-increasing timestamps.
        val ts = if (timestampMs <= lastTimestamp) lastTimestamp + 1 else timestampMs
        lastTimestamp = ts
        submittedAt = SystemClock.uptimeMillis()
        frameSize = bitmap.width to bitmap.height
        val image = BitmapImageBuilder(bitmap).build()
        try {
            landmarker.detectAsync(image, ts)
        } catch (e: Exception) {
            Log.e(TAG, "detectAsync failed", e)
            busy.set(false)
        }
    }

    private fun onResult(result: FaceLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: Any?) {
        // Read per-frame fields before releasing `busy`: the next submit overwrites them.
        val elapsed = SystemClock.uptimeMillis() - submittedAt
        val size = frameSize
        busy.set(false)
        listener?.invoke(convert(result, elapsed, size))
    }

    private fun convert(result: FaceLandmarkerResult, inferenceMs: Long, size: Pair<Int, Int>): FaceFrame {
        val ts = result.timestampMs()
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return FaceFrame.lost(ts)

        val shapes = FloatArray(Arkit.COUNT)
        result.faceBlendshapes().ifPresent { all ->
            val categories = all.firstOrNull() ?: return@ifPresent
            val map = categoryMap ?: IntArray(categories.size) { i ->
                Arkit.fromName(categories[i].categoryName())?.ordinal ?: -1
            }.also { categoryMap = it }
            for (i in categories.indices) {
                val target = map.getOrElse(i) { -1 }
                if (target >= 0) shapes[target] = categories[i].score()
            }
        }

        val points = faces[0]
        val landmarks = FloatArray(points.size * 2)
        for (i in points.indices) {
            landmarks[i * 2] = points[i].x()
            landmarks[i * 2 + 1] = points[i].y()
        }

        var pitch = 0f; var yaw = 0f; var roll = 0f
        var x = 0f; var y = 0f; var z = 0f
        result.facialTransformationMatrixes().ifPresent { matrices ->
            val m = matrices.firstOrNull() ?: return@ifPresent
            // Column-major 4x4: element (row, col) = m[col * 4 + row]. Units are centimeters.
            fun r(row: Int, col: Int) = m[col * 4 + row]
            val euler = Rotation.toEuler(floatArrayOf(
                r(0, 0), r(0, 1), r(0, 2),
                r(1, 0), r(1, 1), r(1, 2),
                r(2, 0), r(2, 1), r(2, 2),
            ))
            pitch = euler[0]; yaw = euler[1]; roll = euler[2]
            x = r(0, 3) * 0.01f
            y = r(1, 3) * 0.01f
            z = r(2, 3) * 0.01f
        }

        if (ts - lastDiagnosticMs > 3000) {
            lastDiagnosticMs = ts
            Log.i(TAG, "upright=${size.first}x${size.second} " +
                "pitch=%.1f yaw=%.1f roll=%.1f z=%.2f".format(pitch, yaw, roll, z))
        }

        return FaceFrame(
            timestampMs = ts,
            detected = true,
            blendshapes = shapes,
            pitch = pitch, yaw = yaw, roll = roll,
            x = x, y = y, z = z,
            landmarks = landmarks,
            imageWidth = size.first,
            imageHeight = size.second,
            inferenceMs = inferenceMs,
        )
    }

    override fun close() {
        listener = null
        landmarker.close()
    }

    companion object {
        private const val TAG = "MediaPipeFaceTracker"
        const val MODEL_ASSET = "face_landmarker.task"
        private const val STALL_MS = 1000L
    }
}
