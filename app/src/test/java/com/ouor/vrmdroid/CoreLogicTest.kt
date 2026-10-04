package com.ouor.vrmdroid

import com.ouor.vrmdroid.output.OscWriter
import com.ouor.vrmdroid.output.VmcSender
import com.ouor.vrmdroid.processing.Quat
import com.ouor.vrmdroid.processing.VrmPresets
import com.ouor.vrmdroid.tracking.Arkit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

/** Mirrors the Euler extraction in MediaPipeFaceTracker for R = Ry(yaw) Rx(pitch) Rz(roll). */
class EulerExtractionTest {
    private fun mat(pitch: Double, yaw: Double, roll: Double): Array<DoubleArray> {
        val (p, y, r) = listOf(pitch, yaw, roll).map { Math.toRadians(it) }
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(p), -sin(p)), doubleArrayOf(0.0, sin(p), cos(p)))
        val ry = arrayOf(doubleArrayOf(cos(y), 0.0, sin(y)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(y), 0.0, cos(y)))
        val rz = arrayOf(doubleArrayOf(cos(r), -sin(r), 0.0), doubleArrayOf(sin(r), cos(r), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
            Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { a[i][it] * b[it][j] } } }
        return mul(mul(ry, rx), rz)
    }

    @Test fun roundTrip() {
        val m = mat(-12.0, 25.0, 8.0)
        val pitch = Math.toDegrees(Math.asin(-m[1][2]))
        val yaw = Math.toDegrees(Math.atan2(m[0][2], m[2][2]))
        val roll = Math.toDegrees(Math.atan2(m[1][0], m[1][1]))
        assert(abs(pitch + 12) < 1e-9 && abs(yaw - 25) < 1e-9 && abs(roll - 8) < 1e-9)
    }
}

class VrmPresetsTest {
    @Test fun openJawIsA() {
        val s = FloatArray(Arkit.COUNT).also { it[Arkit.JawOpen.ordinal] = 0.7f }
        val out = VrmPresets.compute(s)
        assert(out[VrmPresets.Preset.A.ordinal] > 0.8f)
        assertEquals(0f, out[VrmPresets.Preset.O.ordinal], 0f)
    }

    @Test fun outputsClamped() {
        val out = VrmPresets.compute(FloatArray(Arkit.COUNT) { 1f })
        out.forEach { assert(it in 0f..1f) }
    }
}
