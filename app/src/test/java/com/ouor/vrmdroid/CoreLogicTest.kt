package com.ouor.vrmdroid

import com.ouor.vrmdroid.output.OscWriter
import com.ouor.vrmdroid.output.VmcSender
import com.ouor.vrmdroid.processing.Quat
import com.ouor.vrmdroid.processing.VrmPresets
import com.ouor.vrmdroid.tracking.Arkit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ArkitTest {
    @Test fun has52Shapes() = assertEquals(52, Arkit.entries.size)

    @Test fun iFacialMocapNames() {
        assertEquals("eyeBlink_L", Arkit.EyeBlinkLeft.iFacialMocapName)
        assertEquals("mouthSmile_R", Arkit.MouthSmileRight.iFacialMocapName)
        assertEquals("mouthLeft", Arkit.MouthLeft.iFacialMocapName)
        assertEquals("jawRight", Arkit.JawRight.iFacialMocapName)
        assertEquals("browInnerUp", Arkit.BrowInnerUp.iFacialMocapName)
    }

    @Test fun perfectSyncNames() = assertEquals("EyeBlinkLeft", Arkit.EyeBlinkLeft.perfectSyncName)

    @Test fun mirroring() {
        assertEquals(Arkit.EyeBlinkRight, Arkit.EyeBlinkLeft.mirrored)
        assertEquals(Arkit.MouthLeft, Arkit.MouthRight.mirrored)
        assertEquals(Arkit.JawOpen, Arkit.JawOpen.mirrored)
        for (i in 0 until Arkit.COUNT) assertEquals(i, Arkit.MIRROR_INDEX[Arkit.MIRROR_INDEX[i]])
    }
}

class OscWriterTest {
    @Test fun encodesBundle() {
        val osc = OscWriter()
        osc.beginBundle()
        osc.message("/VMC/Ext/Blend/Val").s("A").f(0.5f).end()
        val buf = ByteBuffer.wrap(osc.bytes, 0, osc.size)
        val header = ByteArray(8).also { buf.get(it) }
        assertEquals("#bundle\u0000", String(header, Charsets.US_ASCII))
        assertEquals(1L, buf.long)
        val size = buf.int
        // "/VMC/Ext/Blend/Val" (18+pad=20) + ",sf" (4) + "A" (4) + float (4)
        assertEquals(32, size)
        assertEquals(osc.size, 16 + 4 + size)
        assertEquals(0, size % 4)
    }
}

class QuatTest {
    private fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        val (x, y, z, w) = q
        // v' = q v q*
        val ix = w * v[0] + y * v[2] - z * v[1]
        val iy = w * v[1] + z * v[0] - x * v[2]
        val iz = w * v[2] + x * v[1] - y * v[0]
        val iw = -x * v[0] - y * v[1] - z * v[2]
        return floatArrayOf(
            ix * w + iw * -x + iy * -z - iz * -y,
            iy * w + iw * -y + iz * -x - ix * -z,
            iz * w + iw * -z + ix * -y - iy * -x,
        )
    }

    private fun assertVec(expected: FloatArray, actual: FloatArray) =
        assertArrayEquals(expected, actual, 1e-4f)

    @Test fun yawTurnsForwardTowardPlusX() =
        assertVec(floatArrayOf(1f, 0f, 0f), rotate(Quat.fromUnityEuler(0f, 90f, 0f), floatArrayOf(0f, 0f, 1f)))

    @Test fun positivePitchLooksDown() =
        assertVec(floatArrayOf(0f, -1f, 0f), rotate(Quat.fromUnityEuler(90f, 0f, 0f), floatArrayOf(0f, 0f, 1f)))

    @Test fun eulerOrderIsZxy() {
        // Unity applies roll, then pitch, then yaw: rotating up (0,1,0) by roll 90 gives (-1,0,0),
        // pitch leaves x alone, yaw 90 maps -x to +z.
        // pitch leaves (-1,0,0) unchanged, yaw 90 maps -x to +z.
        assertVec(floatArrayOf(0f, 0f, 1f), rotate(Quat.fromUnityEuler(30f, 90f, 90f), floatArrayOf(0f, 1f, 0f)))
    }

    @Test fun slerpHalf() {
        val q = Quat.fromUnityEuler(0f, 60f, 0f)
        assertArrayEquals(Quat.fromUnityEuler(0f, 30f, 0f), VmcSender.slerpFromIdentity(q, 0.5f), 1e-5f)
    }
}

class VrmPresetsTest {
    @Test fun openJawIsA() {
        val s = FloatArray(Arkit.COUNT).also { it[Arkit.JawOpen.ordinal] = 0.7f }
        val out = VrmPresets.compute(s)
        assertTrue(out[VrmPresets.Preset.A.ordinal] > 0.8f)
        assertEquals(0f, out[VrmPresets.Preset.O.ordinal], 0f)
    }

    @Test fun outputsClamped() {
        val out = VrmPresets.compute(FloatArray(Arkit.COUNT) { 1f })
        out.forEach { assertTrue(it in 0f..1f) }
    }
}

class RotationTest {
    @Test fun eulerRoundTrip() {
        val e = com.ouor.vrmdroid.processing.Rotation.toEuler(
            com.ouor.vrmdroid.processing.Rotation.fromEuler(-12f, 25f, 8f))
        assertArrayEquals(floatArrayOf(-12f, 25f, 8f), e, 1e-3f)
    }

    /** Phone below the face: turning the head must read as pure yaw, not yaw + roll. */
    @Test fun calibratedYawStaysPureWhenCameraIsBelow() {
        val r = com.ouor.vrmdroid.processing.Rotation
        val neutral = r.fromEuler(-20f, 0f, 0f)
        // Head turned 30 degrees about its own vertical axis while the camera looks up at it.
        val turned = FloatArray(9).also { out ->
            val a = neutral; val b = r.fromEuler(0f, 30f, 0f)
            for (i in 0 until 3) for (j in 0 until 3) out[i * 3 + j] = (0 until 3).sumOf { k -> (a[i * 3 + k] * b[k * 3 + j]).toDouble() }.toFloat()
        }
        val rel = r.toEuler(r.relative(neutral, turned))
        assertArrayEquals(floatArrayOf(0f, 30f, 0f), rel, 1e-3f)
    }
}

class HostValidationTest {
    private fun ok(v: String) = com.ouor.vrmdroid.ui.ConnectActivity.isPlausibleHost(v)

    @Test fun acceptsCompleteAddresses() {
        assertTrue(ok("192.168.0.10"))
        assertTrue(ok("10.0.0.1"))
        assertTrue(ok("my-pc.local"))
    }

    @Test fun rejectsPartialOrBroken() {
        assertTrue(!ok("192.168.0"))
        assertTrue(!ok("192.168.0."))
        assertTrue(!ok("300.1.1.1"))
        assertTrue(!ok("pc name"))
    }
}
