package com.ouor.vrmdroid.output

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Allocation-light OSC 1.0 encoder for bundles of messages with string/float/int arguments,
 * which is everything the VMC protocol needs.
 */
class OscWriter(capacity: Int = 8192) {
    private val buffer: ByteBuffer = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)
    private var messageStart = -1
    private val typeTags = StringBuilder(16)
    private val args = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN)
    private var address = ""

    val size: Int get() = buffer.position()
    val bytes: ByteArray get() = buffer.array()

    fun beginBundle() {
        buffer.clear()
        writeString(buffer, "#bundle")
        buffer.putLong(1L) // "immediately" time tag
    }

    fun message(address: String): OscWriter {
        this.address = address
        typeTags.setLength(0)
        typeTags.append(',')
        args.clear()
        return this
    }

    fun s(value: String): OscWriter { typeTags.append('s'); writeString(args, value); return this }
    fun f(value: Float): OscWriter { typeTags.append('f'); args.putFloat(value); return this }
    fun i(value: Int): OscWriter { typeTags.append('i'); args.putInt(value); return this }

    /** Appends the message started by [message] to the bundle. */
    fun end() {
        val sizePos = buffer.position()
        buffer.putInt(0)
        messageStart = buffer.position()
        writeString(buffer, address)
        writeString(buffer, typeTags)
        buffer.put(args.array(), 0, args.position())
        buffer.putInt(sizePos, buffer.position() - messageStart)
    }

    private fun writeString(target: ByteBuffer, value: CharSequence) {
        for (c in value) target.put(c.code.toByte()) // OSC strings here are ASCII
        target.put(0)
        while (target.position() % 4 != 0) target.put(0)
    }
}
