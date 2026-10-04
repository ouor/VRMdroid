package com.ouor.vrmdroid.output

import android.util.Log
import com.ouor.vrmdroid.processing.EyeGaze
import com.ouor.vrmdroid.processing.TrackingResult
import com.ouor.vrmdroid.settings.AppSettings
import com.ouor.vrmdroid.settings.OutputProtocol
import com.ouor.vrmdroid.tracking.Arkit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Emulates the iFacialMocap iPhone app, which VSeeFace, VNyan, Warudo and others accept as
 * "iPhone tracking". Data is a single UDP text datagram per frame:
 *
 *     eyeBlink_L-37|...|tongueOut-0|=head#pitch,yaw,roll,x,y,z|rightEye#p,y,r|leftEye#p,y,r|
 *
 * Blendshapes are integers 0..100, angles are degrees, positions are meters, all in ARKit
 * face-anchor convention (not mirrored; the PC app mirrors).
 *
 * The PC app starts the stream by sending [HANDSHAKE] to the phone's port 49983. Every handshake
 * is reported through [onHandshake]; with [adoptHandshakeHost] the sender also switches its
 * target to that PC.
 */
class IFacialMocapSender(
    host: String,
    private val port: Int,
    private val adoptHandshakeHost: Boolean,
    private val onHandshake: (String) -> Unit,
) : TrackingSender {

    @Volatile private var target: InetSocketAddress? = host.takeIf { it.isNotEmpty() }?.let { InetSocketAddress(it, port) }
    override val destination: String
        get() = target?.let { "iFacialMocap → ${it.hostString}:$port" } ?: "iFacialMocap (PC 연결 대기 중)"

    /** False when another app holds the port, so PC handshakes can't be heard. */
    var listening = true
        private set

    private val socket: DatagramSocket = try {
        val listenAddress = InetSocketAddress(port) // outside apply{}: DatagramSocket has its own `port`
        DatagramSocket(null).apply { reuseAddress = true; bind(listenAddress) }
    } catch (e: SocketException) {
        Log.w(TAG, "Port $port busy; handshake detection disabled", e)
        listening = false
        DatagramSocket()
    }
    private val text = StringBuilder(2048)
    private val listener = Thread(::listenForHandshake, "ifm-handshake").apply { isDaemon = true; start() }

    override fun send(result: TrackingResult, settings: AppSettings) {
        val dest = target ?: return
        val f = result.subject
        if (!f.detected) return

        text.setLength(0)
        for (shape in Arkit.entries) {
            text.append(shape.iFacialMocapName).append('-')
                .append((f.blendshapes[shape.ordinal] * 100f).roundToInt().coerceIn(0, 100))
                .append('|')
        }
        val pitch = if (settings.invertPitch) -f.pitch else f.pitch
        val yaw = if (settings.invertYaw) -f.yaw else f.yaw
        val roll = if (settings.invertRoll) -f.roll else f.roll
        text.append("=head#")
        appendNumbers(pitch, yaw, roll, f.x, f.y, f.z)
        val (rp, ry) = EyeGaze.subjectEyeEuler(f, left = false)
        val (lp, ly) = EyeGaze.subjectEyeEuler(f, left = true)
        text.append("|rightEye#")
        appendNumbers(rp, ry, 0f)
        text.append("|leftEye#")
        appendNumbers(lp, ly, 0f)
        text.append('|')

        val payload = text.toString()
        val bytes = payload.toByteArray(Charsets.US_ASCII)
        socket.send(DatagramPacket(bytes, bytes.size, dest))
        SentDataMonitor.record(OutputProtocol.IFACIALMOCAP, "${dest.hostString}:$port", payload, result, bytes.size)
    }

    private fun appendNumbers(vararg values: Float) {
        values.forEachIndexed { i, v ->
            if (i > 0) text.append(',')
            text.append(String.format(Locale.US, "%.4f", v))
        }
    }

    private fun listenForHandshake() {
        val buf = ByteArray(256)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val message = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                if (message.startsWith(HANDSHAKE_PREFIX)) {
                    val from: InetAddress = packet.address
                    val address = from.hostAddress ?: continue
                    if (adoptHandshakeHost && target?.address != from) {
                        Log.i(TAG, "Handshake from $address")
                        target = InetSocketAddress(from, port)
                    }
                    onHandshake(address)
                }
            } catch (_: SocketException) {
                break // closed
            } catch (e: Exception) {
                Log.w(TAG, "Handshake listener error", e)
            }
        }
    }

    override fun close() {
        socket.close()
        listener.interrupt()
    }

    companion object {
        private const val TAG = "IFacialMocapSender"
        /** VSeeFace sends this (v1); newer receivers may append a version suffix. */
        const val HANDSHAKE = "iFacialMocap_sahuasouryya9218sauhuiayeta91555dy3719"
        private const val HANDSHAKE_PREFIX = "iFacialMocap_"
    }
}
