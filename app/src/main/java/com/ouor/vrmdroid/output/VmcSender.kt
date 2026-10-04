package com.ouor.vrmdroid.output

import android.os.SystemClock
import com.ouor.vrmdroid.processing.Quat
import com.ouor.vrmdroid.processing.TrackingResult
import com.ouor.vrmdroid.processing.VrmPresets
import com.ouor.vrmdroid.settings.TrackingConfig
import com.ouor.vrmdroid.settings.OutputProtocol
import com.ouor.vrmdroid.tracking.Arkit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * VMC protocol (OSC over UDP) performer -> marionette sender.
 * Spec: https://protocol.vmc.info/english
 *
 * Sends head/neck/eye bone rotations, head translation as a root offset, and blendshape values
 * (VRM presets and/or ARKit perfect-sync names). Bone positions are sent as zero; common
 * receivers (VSeeFace, VNyan, Warudo) only apply rotations for non-root bones. Without arm data
 * those receivers leave the arms in a T-pose, so a relaxed arm pose can be sent as well.
 */
class VmcSender(private val host: String, private val port: Int) : TrackingSender {

    override val destination = "VMC → $host:$port"

    private val socket = DatagramSocket()
    private val address = InetSocketAddress(host, port)
    private val osc = OscWriter()
    private val presets = FloatArray(VrmPresets.COUNT)
    private val startTime = SystemClock.elapsedRealtime()

    /** Decoded view of this frame's OSC messages, built only while the console is shown. */
    private val decoded = StringBuilder(1024)
    private var frameBytes = 0
    /** Whether this frame's payload text goes to the debug console. */
    private var capture = false
    private val monitorDestination = "$host:$port"

    override fun send(result: TrackingResult, config: TrackingConfig) {
        val avatar = result.avatar
        capture = SentDataMonitor.wantsPayload()
        decoded.setLength(0)
        frameBytes = 0
        osc.beginBundle()
        osc.message("/VMC/Ext/OK").i(if (avatar.detected) 1 else 0).end()
        osc.message("/VMC/Ext/T").f((SystemClock.elapsedRealtime() - startTime) / 1000f).end()

        // Head translation moves the whole avatar via the root transform (VSeeFace applies it
        // unless it tracks the lower body itself). Offset is relative to the calibrated pose.
        if (config.vmcSendPosition) {
            val k = config.vmcPositionScale / 100f
            val p = avatar.headPosition
            osc.message("/VMC/Ext/Root/Pos").s("root")
                .f(p[0] * k).f(p[1] * k).f(p[2] * k)
                .f(0f).f(0f).f(0f).f(1f)
                .end()
            if (capture) {
                decoded.append(String.format(java.util.Locale.US, "root(%.2f,%.2f,%.2f) ", p[0] * k, p[1] * k, p[2] * k))
            }
        }

        // Split the head rotation between neck and head for a more natural bend.
        val head = avatar.headRotation
        val neck = slerpFromIdentity(head, NECK_SHARE)
        val headLocal = slerpFromIdentity(head, 1f - NECK_SHARE)
        bone("Neck", neck)
        bone("Head", headLocal)
        bone("LeftEye", avatar.leftEye)
        bone("RightEye", avatar.rightEye)
        if (config.vmcSendArmPose) {
            for ((name, q) in ARM_REST_POSE) bone(name, q)
        }
        flush()

        osc.beginBundle()
        if (config.vmcSendVrmPresets) {
            VrmPresets.compute(avatar.blendshapes, presets)
            for (p in VrmPresets.Preset.entries) {
                if (!config.vmcSendEmotions && p in VrmPresets.EMOTIONS) continue
                blend(p.vrm0, presets[p.ordinal])
            }
        }
        if (config.vmcSendPerfectSync) {
            for (shape in Arkit.entries) blend(shape.perfectSyncName, avatar.blendshapes[shape.ordinal])
        }
        osc.message("/VMC/Ext/Blend/Apply").end()
        flush()
        if (SentDataMonitor.enabled) {
            SentDataMonitor.record(OutputProtocol.VMC, monitorDestination, if (capture) decoded.toString() else null, result, frameBytes)
        }
    }

    private fun bone(name: String, q: FloatArray) {
        if (capture) {
            decoded.append(name).append(String.format(java.util.Locale.US, "(%.2f,%.2f,%.2f,%.2f) ", q[0], q[1], q[2], q[3]))
        }
        osc.message("/VMC/Ext/Bone/Pos").s(name)
            .f(0f).f(0f).f(0f)
            .f(q[0]).f(q[1]).f(q[2]).f(q[3])
            .end()
    }

    private fun blend(name: String, value: Float) {
        if (capture && value >= 0.01f) {
            decoded.append(name).append(String.format(java.util.Locale.US, " %.2f ", value))
        }
        osc.message("/VMC/Ext/Blend/Val").s(name).f(value).end()
        // Keep datagrams near the Ethernet MTU; Apply comes in the last bundle.
        if (osc.size > MAX_BUNDLE_BYTES) {
            flush()
            osc.beginBundle()
        }
    }

    private fun flush() {
        socket.send(DatagramPacket(osc.bytes, 0, osc.size, address))
        frameBytes += osc.size
    }

    override fun close() = socket.close()

    companion object {
        private const val NECK_SHARE = 0.3f
        private const val MAX_BUNDLE_BYTES = 1200
        private const val ARM_DOWN_DEG = 72f
        private const val ELBOW_BEND_DEG = 12f

        /**
         * Local rotations from the VRM T-pose, matching the preview's rest pose (AvatarController
         * ApplyRestPose). Avatar faces +Z with its right side on +X, so roll lowers the arms.
         */
        val ARM_REST_POSE = listOf(
            "LeftUpperArm" to Quat.fromUnityEuler(0f, 0f, ARM_DOWN_DEG),
            "RightUpperArm" to Quat.fromUnityEuler(0f, 0f, -ARM_DOWN_DEG),
            "LeftLowerArm" to Quat.fromUnityEuler(0f, ELBOW_BEND_DEG, 0f),
            "RightLowerArm" to Quat.fromUnityEuler(0f, -ELBOW_BEND_DEG, 0f),
        )

        /** Rotation `t` of the way from identity to q (q assumed normalized, w >= 0 path). */
        fun slerpFromIdentity(q: FloatArray, t: Float): FloatArray {
            val w = q[3].coerceIn(-1f, 1f)
            val sign = if (w < 0) -1f else 1f
            val angle = 2.0 * Math.acos((w * sign).toDouble())
            val s = Math.sqrt((1.0 - w * w).coerceAtLeast(0.0))
            if (s < 1e-6) return floatArrayOf(0f, 0f, 0f, 1f)
            val half = angle * t / 2.0
            val k = (Math.sin(half) / s).toFloat() * sign
            return floatArrayOf(q[0] * k, q[1] * k, q[2] * k, Math.cos(half).toFloat())
        }
    }
}
