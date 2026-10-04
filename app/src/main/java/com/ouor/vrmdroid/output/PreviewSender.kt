package com.ouor.vrmdroid.output

import com.ouor.vrmdroid.processing.TrackingResult
import com.ouor.vrmdroid.processing.VrmPresets
import com.ouor.vrmdroid.settings.AppSettings
import com.ouor.vrmdroid.tracking.Arkit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Feeds the Unity avatar preview, which runs in a separate process (`:unity`), over localhost UDP.
 *
 * Packet: ASCII "VDF1" followed by [FLOAT_COUNT] little-endian floats laid out as described by
 * the `I_*` constants. Keep in sync with `unity/Assets/VrmDroid/Scripts/TrackingPacket.cs`.
 */
class PreviewSender(host: String = "127.0.0.1", port: Int = PORT) : TrackingSender {

    override val destination = "Unity preview → $host:$port"

    private val socket = DatagramSocket()
    private val address = InetSocketAddress(host, port)
    private val buffer = ByteBuffer.allocate(4 + FLOAT_COUNT * 4).order(ByteOrder.LITTLE_ENDIAN)
    private val presets = FloatArray(VrmPresets.COUNT)

    override fun send(result: TrackingResult, settings: AppSettings) {
        val a = result.avatar
        buffer.clear()
        buffer.put(MAGIC)
        buffer.putFloat(if (a.detected) 1f else 0f)
        for (v in a.blendshapes) buffer.putFloat(v)
        for (v in a.headRotation) buffer.putFloat(v)
        for (v in a.headPosition) buffer.putFloat(v)
        buffer.putFloat(a.gazeYaw)
        buffer.putFloat(a.gazePitch)
        VrmPresets.compute(a.blendshapes, presets)
        for (v in presets) buffer.putFloat(v)
        socket.send(DatagramPacket(buffer.array(), 0, buffer.position(), address))
    }

    override fun close() = socket.close()

    companion object {
        const val PORT = 39540
        private val MAGIC = "VDF1".toByteArray(Charsets.US_ASCII)

        const val I_DETECTED = 0
        const val I_BLENDSHAPES = 1
        const val I_HEAD_ROT = I_BLENDSHAPES + Arkit.COUNT // 53
        const val I_HEAD_POS = I_HEAD_ROT + 4 // 57
        const val I_GAZE = I_HEAD_POS + 3 // 60: yaw, pitch
        const val I_PRESETS = I_GAZE + 2 // 62
        val FLOAT_COUNT = I_PRESETS + VrmPresets.COUNT // 74
    }
}
