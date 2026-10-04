package com.ouor.vrmdroid.tracking

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Turns CameraX RGBA frames into an upright Bitmap without allocating per frame: the source
 * and rotated bitmaps are reused and only reallocated when the frame size changes.
 *
 * Rotation happens here rather than via MediaPipe's ImageProcessingOptions, whose handling of
 * rotation differed between devices and left landmarks/pose 90 degrees off on some.
 *
 * The returned Bitmap is overwritten by the next [convert] call, so callers must be done with
 * it first (the tracker only accepts a new frame after the previous result came back).
 * Not thread-safe; use from the analysis thread only.
 */
class FrameConverter {
    private var source: Bitmap? = null
    private var upright: Bitmap? = null
    private var packed: ByteBuffer? = null
    private val canvas = Canvas()
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun convert(image: ImageProxy): Bitmap {
        val w = image.width
        val h = image.height
        val src = reuse(source, w, h).also { source = it }

        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val rowBytes = w * BYTES_PER_PIXEL
        if (plane.rowStride == rowBytes) {
            src.copyPixelsFromBuffer(buffer)
        } else {
            // Rows are padded; pack them before copying.
            val dst = packed?.takeIf { it.capacity() == rowBytes * h } ?: ByteBuffer.allocateDirect(rowBytes * h).also { packed = it }
            dst.clear()
            val row = buffer.duplicate()
            for (y in 0 until h) {
                row.limit(y * plane.rowStride + rowBytes)
                row.position(y * plane.rowStride)
                dst.put(row)
            }
            dst.rewind()
            src.copyPixelsFromBuffer(dst)
        }

        val rotation = image.imageInfo.rotationDegrees
        if (rotation % 360 == 0) return src

        val (uw, uh) = if (rotation % 180 == 0) w to h else h to w
        val out = reuse(upright, uw, uh).also { upright = it }
        matrix.reset()
        matrix.postRotate(rotation.toFloat())
        // Rotating about the origin moves the image out of the positive quadrant; shift it back.
        when (rotation) {
            90 -> matrix.postTranslate(h.toFloat(), 0f)
            180 -> matrix.postTranslate(w.toFloat(), h.toFloat())
            270 -> matrix.postTranslate(0f, w.toFloat())
        }
        canvas.setBitmap(out)
        canvas.drawBitmap(src, matrix, paint)
        canvas.setBitmap(null)
        return out
    }

    private fun reuse(bitmap: Bitmap?, w: Int, h: Int): Bitmap =
        bitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
