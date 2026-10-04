package com.ouor.vrmdroid.output

import android.util.Log
import com.ouor.vrmdroid.processing.TrackingResult
import com.ouor.vrmdroid.settings.TrackingConfig
import com.ouor.vrmdroid.settings.OutputProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Emulates the iFacialMocap iPhone app, which VSeeFace, VNyan, Warudo and others accept as
 * "iPhone tracking": one UDP text datagram per frame in [IFacialMocapFormat], in ARKit
 * face-anchor convention (not mirrored; the PC app mirrors).
 *
 * The PC app starts the stream by sending a handshake ("iFacialMocap_…") to the phone's port 49983. Every handshake
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
        get() = target?.let { "iFacialMocap → ${it.hostString}:$port" } ?: "iFacialMocap (waiting for PC)"

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

    override fun send(result: TrackingResult, config: TrackingConfig) {
        val dest = target ?: return
        val f = result.subject
        if (!f.detected) return

        IFacialMocapFormat.encode(f, config, text)

        val payload = text.toString()
        val bytes = payload.toByteArray(Charsets.US_ASCII)
        socket.send(DatagramPacket(bytes, bytes.size, dest))
        if (SentDataMonitor.enabled) {
            val shown = if (SentDataMonitor.wantsPayload()) payload else null
            SentDataMonitor.record(OutputProtocol.IFACIALMOCAP, "${dest.hostString}:$port", shown, result, bytes.size)
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
        /** VSeeFace sends "iFacialMocap_sahuasouryya9218sauhuiayeta91555dy3719"; newer receivers vary the suffix. */
        private const val HANDSHAKE_PREFIX = "iFacialMocap_"
    }
}
