package com.ouor.vrmdroid

import com.ouor.vrmdroid.output.IFacialMocapFormat
import com.ouor.vrmdroid.output.PreviewSender
import com.ouor.vrmdroid.processing.FaceProcessor
import com.ouor.vrmdroid.processing.VrmPresets
import com.ouor.vrmdroid.settings.TrackingConfig
import com.ouor.vrmdroid.tracking.Arkit
import com.ouor.vrmdroid.tracking.FaceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

private fun frame(
    t: Long = 0, pitch: Float = 0f, yaw: Float = 0f, roll: Float = 0f,
    shapes: Map<Arkit, Float> = emptyMap(),
) = FaceFrame(
    timestampMs = t, detected = true,
    blendshapes = FloatArray(Arkit.COUNT).also { a -> shapes.forEach { (k, v) -> a[k.ordinal] = v } },
    pitch = pitch, yaw = yaw, roll = roll, z = -0.4f,
)

class FaceProcessorTest {
    @Test fun firstFrameCalibratesToNeutral() {
        val r = FaceProcessor().process(frame(pitch = -20f, yaw = 5f, roll = 3f), TrackingConfig())
        assertEquals(0f, r.subject.pitch, 1e-3f)
        assertEquals(0f, r.subject.yaw, 1e-3f)
        assertEquals(0f, r.subject.roll, 1e-3f)
    }

    @Test fun relaxedEyesReadAsOpen() {
        // MediaPipe reports ~0.15 for open, relaxed eyes; that must not half-close the avatar.
        val r = FaceProcessor().process(frame(shapes = mapOf(Arkit.EyeBlinkLeft to 0.15f)), TrackingConfig())
        assertEquals(0f, r.subject[Arkit.EyeBlinkLeft], 1e-6f)
    }

    @Test fun closedEyesReachFullBlink() {
        val r = FaceProcessor().process(frame(shapes = mapOf(Arkit.EyeBlinkLeft to 0.8f)), TrackingConfig())
        assertEquals(1f, r.subject[Arkit.EyeBlinkLeft], 1e-6f)
    }

    @Test fun mirroringSwapsSidesForTheAvatarOnly() {
        val input = frame(shapes = mapOf(Arkit.EyeBlinkLeft to 0.8f))
        val mirrored = FaceProcessor().process(input, TrackingConfig(mirror = true))
        assertEquals(1f, mirrored.subject[Arkit.EyeBlinkLeft], 1e-6f) // what the PC gets (iFacialMocap)
        assertEquals(1f, mirrored.avatar[Arkit.EyeBlinkRight], 1e-6f) // avatar's other eye
        val direct = FaceProcessor().process(input, TrackingConfig(mirror = false))
        assertEquals(1f, direct.avatar[Arkit.EyeBlinkLeft], 1e-6f)
    }

    @Test fun linkedEyesAverage() {
        val r = FaceProcessor().process(
            frame(shapes = mapOf(Arkit.EyeBlinkLeft to 0.75f, Arkit.EyeBlinkRight to 0.2f)),
            TrackingConfig(linkEyes = true),
        )
        assertEquals(r.subject[Arkit.EyeBlinkLeft], r.subject[Arkit.EyeBlinkRight], 1e-6f)
    }
}

class IFacialMocapFormatTest {
    @Test fun payloadShape() {
        val f = frame(shapes = mapOf(Arkit.EyeBlinkLeft to 0.37f, Arkit.JawOpen to 1.2f))
        val out = StringBuilder()
        IFacialMocapFormat.encode(f, TrackingConfig(), out)
        val text = out.toString()
        assertTrue(text.startsWith("browDown_L-0|"))
        assertTrue("eyeBlink_L-37|" in text)
        assertTrue("jawOpen-100|" in text) // clamped
        assertTrue("mouthLeft-0|" in text) // directional shapes keep their names
        assertTrue(text.contains("|=head#0.0000,0.0000,0.0000,0.0000,0.0000,-0.4000|rightEye#"))
        assertTrue(text.endsWith("|"))
        assertEquals(Arkit.COUNT, text.substringBefore("=head#").count { it == '|' })
    }

    @Test fun invertFlagsFlipAngles() {
        val out = StringBuilder()
        IFacialMocapFormat.encode(frame(pitch = 10f, yaw = -5f, roll = 2f), TrackingConfig(invertPitch = true, invertYaw = true, invertRoll = true), out)
        assertTrue(out.contains("=head#-10.0000,5.0000,-2.0000,"))
    }

    @Test fun fixedFormatMatchesStringFormat() {
        for (v in listOf(0f, -0f, 1.23456f, -0.00004f, -0.00006f, 12.5f, -123.45678f, 0.99999f)) {
            val expected = String.format(Locale.US, "%.4f", v).let { if (it == "-0.0000") "0.0000" else it }
            assertEquals("value $v", expected, IFacialMocapFormat.appendFixed(StringBuilder(), v).toString())
        }
    }
}

/** The Unity receiver parses the same packet; keep both sides in step. */
class PreviewPacketLayoutTest {
    private val cs: String by lazy {
        val candidates = listOf("../unity/Assets/VrmDroid/Scripts/TrackingPacket.cs", "unity/Assets/VrmDroid/Scripts/TrackingPacket.cs")
        candidates.map(::File).first { it.exists() }.readText()
    }

    private fun csConst(name: String, symbols: Map<String, Int>): Int {
        val expr = Regex("""public const int $name = ([^;]+);""").find(cs)!!.groupValues[1].trim()
        return expr.split('+').sumOf { term -> term.trim().let { it.toIntOrNull() ?: symbols.getValue(it) } }
    }

    @Test fun offsetsMatch() {
        val s = mutableMapOf("ArkitCount" to csConst("ArkitCount", emptyMap()), "PresetCount" to csConst("PresetCount", emptyMap()))
        for (n in listOf("Detected", "Blendshapes", "HeadRotation", "HeadPosition", "Gaze", "Presets", "FloatCount")) s[n] = csConst(n, s)
        assertEquals(Arkit.COUNT, s["ArkitCount"])
        assertEquals(VrmPresets.COUNT, s["PresetCount"])
        assertEquals(PreviewSender.I_BLENDSHAPES, s["Blendshapes"])
        assertEquals(PreviewSender.I_HEAD_ROT, s["HeadRotation"])
        assertEquals(PreviewSender.I_HEAD_POS, s["HeadPosition"])
        assertEquals(PreviewSender.I_GAZE, s["Gaze"])
        assertEquals(PreviewSender.I_PRESETS, s["Presets"])
        assertEquals(PreviewSender.FLOAT_COUNT, s["FloatCount"])
    }

    @Test fun blendshapeOrderMatches() {
        val names = Regex("\"(\\w+)\"").findAll(cs.substringAfter("ArkitNames").substringBefore("};")).map { it.groupValues[1] }.toList()
        assertEquals(Arkit.entries.map { it.arkitName }, names)
    }

    @Test fun presetOrderMatches() {
        val csPresets = cs.substringAfter("public enum Preset {").substringBefore("}").split(',').map { it.trim() }
        assertEquals(VrmPresets.Preset.entries.size, csPresets.size)
    }
}

class VmcArmPoseTest {
    /** Rotates v by unit quaternion q (x, y, z, w). */
    private fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        val (x, y, z, w) = q.toList()
        val tx = 2 * (y * v[2] - z * v[1]); val ty = 2 * (z * v[0] - x * v[2]); val tz = 2 * (x * v[1] - y * v[0])
        return floatArrayOf(
            v[0] + w * tx + (y * tz - z * ty),
            v[1] + w * ty + (z * tx - x * tz),
            v[2] + w * tz + (x * ty - y * tx),
        )
    }

    @Test fun upperArmsPointDown() {
        val pose = com.ouor.vrmdroid.output.VmcSender.ARM_REST_POSE.toMap()
        // Avatar faces +Z, so its right arm points along +X and its left arm along -X in the T-pose.
        val right = rotate(pose.getValue("RightUpperArm"), floatArrayOf(1f, 0f, 0f))
        val left = rotate(pose.getValue("LeftUpperArm"), floatArrayOf(-1f, 0f, 0f))
        assertTrue("right arm $right", right[1] < -0.9f && right[0] > 0f)
        assertTrue("left arm $left", left[1] < -0.9f && left[0] < 0f)
    }
}

class StatusPillTest {
    private val sending = com.ouor.vrmdroid.service.TrackingStatus(
        running = true, faceDetected = true, pcLink = com.ouor.vrmdroid.service.PcLink.SENDING,
    )

    @Test fun sendingNeverClaimsAConnection() {
        assertEquals(R.string.pill_pc_sending, com.ouor.vrmdroid.ui.StatusText.pillRes(sending).first)
    }

    @Test fun errorWinsOverEverything() {
        val s = sending.copy(faceDetected = false, error = "boom")
        assertEquals(R.string.pill_error, com.ouor.vrmdroid.ui.StatusText.pillRes(s).first)
    }

    @Test fun noFaceWinsOverPcState() {
        assertEquals(R.string.pill_no_face, com.ouor.vrmdroid.ui.StatusText.pillRes(sending.copy(faceDetected = false)).first)
    }
}
