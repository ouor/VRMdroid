package com.ouor.vrmdroid.tracking

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Hands a CameraX RGBA frame to the tracker as tightly packed pixels, copying only when the
 * camera pads its rows. The frame must already be upright: [TrackingService] has CameraX rotate
 * it while converting from YUV, which is done in native code and costs next to nothing.
 *
 * Rotation never goes through MediaPipe's ImageProcessingOptions, whose handling of rotation
 * differed between devices and left landmarks/pose 90 degrees off on some.
 *
 * The returned buffer is either the camera's own or one reused by the next [pixels] call, so it
 * is only valid until the frame is closed or the next call. Use from the analysis thread only.
 */
class FrameConverter {
    private var packed: ByteBuffer? = null

    fun pixels(image: ImageProxy): ByteBuffer {
        check(image.imageInfo.rotationDegrees == 0) { "expected an upright frame" }
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val rowBytes = w * BYTES_PER_PIXEL
        if (plane.rowStride == rowBytes && buffer.remaining() == rowBytes * h) return buffer

        // Rows are padded; pack them.
        val dst = packed?.takeIf { it.capacity() == rowBytes * h } ?: ByteBuffer.allocateDirect(rowBytes * h).also { packed = it }
        dst.clear()
        val row = buffer.duplicate()
        for (y in 0 until h) {
            row.limit(y * plane.rowStride + rowBytes)
            row.position(y * plane.rowStride)
            dst.put(row)
        }
        dst.rewind()
        return dst
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
