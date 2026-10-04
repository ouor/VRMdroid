package com.ouor.vrmdroid.service

import com.ouor.vrmdroid.output.IFacialMocapSender
import com.ouor.vrmdroid.output.TrackingSender
import com.ouor.vrmdroid.processing.FaceProcessor
import com.ouor.vrmdroid.settings.OutputProtocol
import com.ouor.vrmdroid.settings.TrackingConfig
import com.ouor.vrmdroid.tracking.FaceFrame
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Where to send, as read from settings when the senders are (re)built. */
data class SenderSettings(
    val protocol: OutputProtocol,
    val host: String,
    val iFacialMocapPort: Int,
    val vmcPort: Int,
    val autoDetectHost: Boolean,
)

/** User-facing messages the engine reports, resolved by the Android side. */
interface EngineMessages {
    val sendFailed: String
    fun portBusy(port: Int): String
    fun senderSetupFailed(reason: String?): String
}

/**
 * Everything between a tracked frame and the network, free of Android framework classes so it
 * runs in JVM unit tests: calibration, processing, publishing to [TrackingHub], fps, send-rate
 * limiting, the latest-wins hand-off to the network thread, sender lifecycle, send-failure
 * reporting and recovery, and the iFacialMocap handshake.
 *
 * [TrackingService] owns the Android side (camera, tracker, Wi-Fi lock, notification) and feeds
 * frames in through [onFrame].
 */
class TrackingEngine(
    /** Single thread; senders are created, used and closed only here. */
    private val networkExecutor: Executor,
    private val senderSettings: () -> SenderSettings,
    private val createSenders: (SenderSettings, onHandshake: (String) -> Unit) -> List<TrackingSender>,
    /** Persists a PC address learned from a handshake; settings then call [reconfigureSenders]. */
    private val adoptHost: (String) -> Unit,
    private val messages: EngineMessages,
    private val clock: () -> Long,
    private val warn: (String, Throwable?) -> Unit = { _, _ -> },
    private val processor: FaceProcessor = FaceProcessor(),
) {
    /** Settings snapshot for the per-frame path; replaced whenever a preference changes. */
    @Volatile var config = TrackingConfig()

    private val calibrationRequested = AtomicBoolean(false)
    @Volatile private var stopped = false

    private val pendingSend = AtomicReference<com.ouor.vrmdroid.processing.TrackingResult?>(null)
    private var lastSendAt = Long.MIN_VALUE / 2
    private var frameCount = 0
    private var fpsWindowStart = clock()

    /** Touched only on [networkExecutor]. */
    private var senders: List<TrackingSender> = emptyList()
    private var consecutiveSendFailures = 0
    private var lastSendFailureLog = Long.MIN_VALUE / 2
    private var lastSenderRetry = Long.MIN_VALUE / 2

    /** Address of the PC that last sent an iFacialMocap handshake this session. */
    @Volatile private var handshakeHost: String? = null

    /** Re-zeroes head pose on the next frame. Safe from any thread. */
    fun requestCalibration() = calibrationRequested.set(true)

    /** Rebuilds the senders from current settings, on the network thread. */
    fun reconfigureSenders() = post { recreateSenders() }

    /** Called on the tracker's result thread. */
    fun onFrame(frame: FaceFrame) {
        // A result can still arrive from the tracker's thread after shutdown started.
        if (stopped) return
        if (calibrationRequested.getAndSet(false)) processor.requestCalibration()
        val cfg = config
        val result = processor.process(frame, cfg)
        TrackingHub.publish(result)
        updateFps(frame)

        val now = clock()
        if (now - lastSendAt < 1000L / cfg.sendRate - 2) return
        lastSendAt = now
        // Latest-wins hand-off so a slow network never backs up the tracker.
        if (pendingSend.getAndSet(result) == null) {
            post {
                val r = pendingSend.getAndSet(null) ?: return@post
                var failed: Exception? = null
                for (s in senders) {
                    try { s.send(r, cfg) } catch (e: Exception) { failed = e }
                }
                onSendResult(failed)
            }
        }
    }

    /** Stops accepting frames and closes the senders (on the network thread). */
    fun shutdown() {
        stopped = true
        post { senders.forEach { runCatching { it.close() } }; senders = emptyList() }
    }

    private fun post(block: () -> Unit) {
        try {
            networkExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Shutting down; dropping work is fine.
        }
    }

    private fun updateFps(frame: FaceFrame) {
        frameCount++
        val now = clock()
        val elapsed = now - fpsWindowStart
        if (elapsed >= FPS_WINDOW_MS) {
            val fps = frameCount * 1000f / elapsed
            frameCount = 0
            fpsWindowStart = now
            TrackingHub.updateStatus {
                it.copy(fps = fps, inferenceMs = frame.inferenceMs, faceDetected = frame.detected)
            }
        } else if (frame.detected != TrackingHub.status.value.faceDetected) {
            TrackingHub.updateStatus { it.copy(faceDetected = frame.detected) }
        }
    }

    /** Runs on [networkExecutor]. */
    private fun recreateSenders() {
        senders.forEach { runCatching { it.close() } }
        val s = senderSettings()
        senders = try {
            createSenders(s, ::onPcHandshake)
        } catch (e: Exception) {
            warn("sender setup failed", e)
            TrackingHub.updateStatus { it.copy(error = messages.senderSetupFailed(e.message)) }
            emptyList()
        }
        val portBusy = senders.any { it is IFacialMocapSender && !it.listening }
        val portMsg = messages.portBusy(s.iFacialMocapPort)
        TrackingHub.updateStatus {
            when {
                portBusy -> it.copy(error = portMsg)
                it.error == portMsg -> it.copy(error = null)
                else -> it
            }
        }
        publishLink(s)
    }

    /**
     * Runs on [networkExecutor]. Logs at most every few seconds instead of every frame, and
     * reports a sustained failure in the status so it never goes unnoticed.
     */
    private fun onSendResult(error: Exception?) {
        if (error == null) {
            if (consecutiveSendFailures >= SEND_FAILURE_THRESHOLD) {
                TrackingHub.updateStatus { if (it.error == messages.sendFailed) it.copy(error = null) else it }
            }
            consecutiveSendFailures = 0
            return
        }
        consecutiveSendFailures++
        val now = clock()
        if (now - lastSendFailureLog > SEND_FAILURE_LOG_MS) {
            lastSendFailureLog = now
            warn("send failing ($consecutiveSendFailures in a row)", error)
        }
        if (consecutiveSendFailures == SEND_FAILURE_THRESHOLD) {
            TrackingHub.updateStatus { it.copy(error = messages.sendFailed) }
        }
        // Addresses resolve once, when the sender is created. A PC name (*.local) that failed to
        // resolve, or a network that came back, needs fresh senders to recover.
        if (consecutiveSendFailures >= SEND_FAILURE_THRESHOLD && now - lastSenderRetry > SENDER_RETRY_MS) {
            lastSenderRetry = now
            recreateSenders()
        }
    }

    /** Called from the iFacialMocap listener thread whenever a PC app says hello. */
    private fun onPcHandshake(address: String) {
        val isNew = handshakeHost != address
        handshakeHost = address
        val s = senderSettings()
        if (s.autoDetectHost && s.host != address) {
            adoptHost(address) // settings change rebuilds the senders, which republishes the link
        } else {
            publishLink(s)
        }
        if (isNew) TrackingHub.notifyPcFound(address)
    }

    private fun publishLink(s: SenderSettings) {
        val link = when {
            s.protocol == OutputProtocol.NONE -> PcLink.OFF
            s.host.isNotEmpty() -> PcLink.SENDING
            else -> PcLink.WAITING
        }
        TrackingHub.updateStatus { it.copy(protocol = s.protocol, pcAddress = s.host, pcLink = link) }
    }

    companion object {
        const val SEND_FAILURE_THRESHOLD = 30
        const val SENDER_RETRY_MS = 5_000L
        private const val SEND_FAILURE_LOG_MS = 5_000L
        private const val FPS_WINDOW_MS = 500L
    }
}
