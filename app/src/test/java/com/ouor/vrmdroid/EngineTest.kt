package com.ouor.vrmdroid

import android.content.SharedPreferences
import com.ouor.vrmdroid.output.TrackingSender
import com.ouor.vrmdroid.processing.TrackingResult
import com.ouor.vrmdroid.service.EngineMessages
import com.ouor.vrmdroid.service.PcLink
import com.ouor.vrmdroid.service.PerfStats
import com.ouor.vrmdroid.service.SenderSettings
import com.ouor.vrmdroid.service.ThermalGovernor
import com.ouor.vrmdroid.service.ThermalLevel
import com.ouor.vrmdroid.service.TrackingEngine
import com.ouor.vrmdroid.service.TrackingHub
import com.ouor.vrmdroid.service.TrackingStatus
import com.ouor.vrmdroid.settings.AppSettings
import com.ouor.vrmdroid.settings.OutputProtocol
import com.ouor.vrmdroid.settings.TrackingConfig
import com.ouor.vrmdroid.tracking.Arkit
import com.ouor.vrmdroid.tracking.FaceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackingEngineTest {
    private class FakeSender(var fail: Boolean = false) : TrackingSender {
        override val destination = "fake"
        var sent = 0
        var closed = false
        override fun send(result: TrackingResult, config: TrackingConfig) {
            if (fail) throw java.io.IOException("unreachable")
            sent++
        }
        override fun close() { closed = true }
    }

    private var now = 0L
    private var settings = SenderSettings(OutputProtocol.VMC, "192.168.0.10", 49983, 39539, autoDetectHost = true)
    private val created = mutableListOf<FakeSender>()
    private var nextFails = false
    private var handshake: ((String) -> Unit)? = null
    private val adopted = mutableListOf<String>()

    private val engine = TrackingEngine(
        networkExecutor = { it.run() }, // run network work inline
        senderSettings = { settings },
        createSenders = { _, onHandshake ->
            handshake = onHandshake
            listOf(FakeSender(nextFails).also { created += it })
        },
        adoptHost = { adopted += it },
        messages = object : EngineMessages {
            override val sendFailed = "send failed"
            override fun portBusy(port: Int) = "port $port busy"
            override fun senderSetupFailed(reason: String?) = "setup: $reason"
        },
        clock = { now },
    )

    private fun frame(yaw: Float = 0f) = FaceFrame(
        timestampMs = now, detected = true, blendshapes = FloatArray(Arkit.COUNT),
        pitch = 0f, yaw = yaw, roll = 0f, z = -0.4f,
    )

    @Before fun resetHub() {
        TrackingHub.updateStatus { TrackingStatus() }
        TrackingHub.publish(null)
    }

    @Test fun sendRateLimitsFrames() {
        engine.config = TrackingConfig(sendRate = 30)
        engine.reconfigureSenders()
        repeat(30) { engine.onFrame(frame()); now += 10 } // 300 ms of 100 fps input
        // 30 Hz needs ~31 ms between sends; on a 10 ms frame grid that is every 4th frame.
        assertEquals(8, created.last().sent)
    }

    @Test fun sustainedFailureIsReportedAndCleared() {
        nextFails = true
        engine.reconfigureSenders()
        repeat(TrackingEngine.SEND_FAILURE_THRESHOLD) { engine.onFrame(frame()); now += 50 }
        assertEquals("send failed", TrackingHub.status.value.error)

        created.last().fail = false
        engine.onFrame(frame())
        assertNull(TrackingHub.status.value.error)
    }

    @Test fun failingSendersAreRebuiltToReResolveTheAddress() {
        nextFails = true
        engine.reconfigureSenders()
        repeat(TrackingEngine.SEND_FAILURE_THRESHOLD) { engine.onFrame(frame()); now += 50 }
        assertEquals(2, created.size) // rebuilt once the failure was confirmed
        assertTrue(created.first().closed)
        // No rebuild storm: the next one waits for the retry interval.
        repeat(10) { engine.onFrame(frame()); now += 50 }
        assertEquals(2, created.size)
        now += TrackingEngine.SENDER_RETRY_MS
        engine.onFrame(frame())
        assertEquals(3, created.size)
    }

    @Test fun linkStateFollowsSettings() {
        engine.reconfigureSenders()
        assertEquals(PcLink.SENDING, TrackingHub.status.value.pcLink)
        settings = settings.copy(host = "")
        engine.reconfigureSenders()
        assertEquals(PcLink.WAITING, TrackingHub.status.value.pcLink)
        settings = settings.copy(protocol = OutputProtocol.NONE)
        engine.reconfigureSenders()
        assertEquals(PcLink.OFF, TrackingHub.status.value.pcLink)
    }

    @Test fun handshakeAdoptsANewPcOnlyWhenAutoDetecting() {
        engine.reconfigureSenders()
        handshake!!("192.168.0.20")
        assertEquals(listOf("192.168.0.20"), adopted)

        settings = settings.copy(autoDetectHost = false)
        handshake!!("192.168.0.30")
        assertEquals(listOf("192.168.0.20"), adopted)
    }

    @Test fun shutdownDropsLateFramesAndClosesSenders() {
        engine.reconfigureSenders()
        engine.shutdown()
        assertTrue(created.last().closed)
        engine.onFrame(frame())
        assertEquals(0, created.last().sent)
        assertNull(TrackingHub.latest.value)
    }

    @Test fun calibrationRequestReZeroesTheNextFrame() {
        engine.reconfigureSenders()
        fun settle(yaw: Float): Float {
            repeat(60) { engine.onFrame(frame(yaw)); now += 33 } // let smoothing converge
            return TrackingHub.latest.value!!.subject.yaw
        }
        settle(20f) // the first frame calibrates at 20°
        assertEquals(20f, settle(40f), 1f)
        engine.requestCalibration()
        assertEquals(0f, settle(40f), 1f)
    }
}

class AppSettingsMigrationTest {
    /** Just enough SharedPreferences for AppSettings, kept in memory. */
    private class MemoryPrefs(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {
        val values = initial.toMutableMap()
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val puts = mutableMapOf<String, Any?>()
            val removes = mutableSetOf<String>()
            var clear = false
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) values.clear()
                removes.forEach { values.remove(it) }
                values.putAll(puts)
            }
        }
    }

    @Test fun version1DropsRetiredKeysAndKeepsTheRest() {
        val prefs = MemoryPrefs(mapOf(
            AppSettings.KEY_BLINK_SENSITIVITY to 150,
            AppSettings.KEY_MOUTH_SENSITIVITY to 80,
            AppSettings.KEY_TARGET_HOST to "192.168.0.10",
        ))
        AppSettings(prefs).migrate()
        assertFalse(prefs.contains(AppSettings.KEY_BLINK_SENSITIVITY))
        assertFalse(prefs.contains(AppSettings.KEY_MOUTH_SENSITIVITY))
        assertEquals("192.168.0.10", AppSettings(prefs).targetHost)
        assertEquals(2, prefs.getInt("prefs_version", 0))
    }

    @Test fun currentVersionIsLeftAlone() {
        val prefs = MemoryPrefs(mapOf("prefs_version" to 2, AppSettings.KEY_BLINK_SENSITIVITY to 150))
        AppSettings(prefs).migrate()
        assertEquals(150, prefs.getInt(AppSettings.KEY_BLINK_SENSITIVITY, 0))
    }
}

class PerfStatsTest {
    private var now = 0L
    private val perf = PerfStats({ now }, windowMs = 1_000)

    @Test fun summarizesAWindowThenStartsOver() {
        repeat(20) { i ->
            perf.onConvert(2f)
            perf.onResult(detected = i % 4 != 0, inferenceMs = (i + 1).toFloat(), ageMs = 40f)
        }
        now = 500
        assertNull(perf.poll()) // window still running
        now = 1_000
        assertEquals(
            "hz=20.0 face=75% conv=2.0/2.0 infer=10.0/19.0 age=40.0/40.0 temp",
            perf.poll { "temp" },
        )
        now = 2_000
        assertEquals("hz=0.0 face=0% conv=- infer=- age=-", perf.poll())
    }

    @Test fun percentileIsNearestRank() {
        val sorted = FloatArray(100) { it + 1f }
        assertEquals(50f, PerfStats.percentile(sorted, 0.5f))
        assertEquals(95f, PerfStats.percentile(sorted, 0.95f))
        assertEquals(7f, PerfStats.percentile(floatArrayOf(7f), 0.95f))
    }
}

class ThermalGovernorTest {
    private val governor = ThermalGovernor()

    @Test fun stepsUpRightAwayAndDownOnlyAfterCalmReadings() {
        assertFalse(governor.update(0.80f, 0))
        assertEquals(ThermalLevel.NORMAL, governor.level)
        assertFalse(governor.update(1.02f, 0)) // needs a second reading
        assertTrue(governor.update(1.02f, 0))
        assertEquals(ThermalLevel.HOT, governor.level)

        // Just under the threshold isn't calm enough to step down.
        repeat(20) { governor.update(0.98f, 0) }
        assertEquals(ThermalLevel.HOT, governor.level)

        repeat(ThermalGovernor.CALM_READINGS - 1) { governor.update(0.90f, 0) }
        assertEquals(ThermalLevel.HOT, governor.level)
        assertTrue(governor.update(0.90f, 0))
        assertEquals(ThermalLevel.WARM, governor.level) // one step at a time
    }

    @Test fun aOneOffHeadroomSpikeIsIgnored() {
        // Right after start-up the forecast jumps once (camera + avatar loading), then settles.
        assertFalse(governor.update(0.97f, 0))
        assertFalse(governor.update(0.87f, 0))
        assertEquals(ThermalLevel.NORMAL, governor.level)
        // Two readings that disagree step up only as far as both agree.
        governor.update(1.02f, 0)
        governor.update(0.96f, 0)
        assertEquals(ThermalLevel.WARM, governor.level)
    }

    @Test fun thermalStatusAloneRaisesTheLevel() {
        governor.update(Float.NaN, ThermalGovernor.STATUS_LIGHT)
        assertEquals(ThermalLevel.WARM, governor.level)
        governor.update(Float.NaN, ThermalGovernor.STATUS_MODERATE)
        assertEquals(ThermalLevel.HOT, governor.level)
    }
}
