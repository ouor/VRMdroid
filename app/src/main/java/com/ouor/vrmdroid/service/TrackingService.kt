package com.ouor.vrmdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Display
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.ouor.vrmdroid.MainActivity
import com.ouor.vrmdroid.R
import com.ouor.vrmdroid.output.IFacialMocapSender
import com.ouor.vrmdroid.output.PreviewSender
import com.ouor.vrmdroid.output.TrackingSender
import com.ouor.vrmdroid.output.VmcSender
import com.ouor.vrmdroid.processing.FaceProcessor
import com.ouor.vrmdroid.processing.TrackingResult
import com.ouor.vrmdroid.settings.AppSettings
import com.ouor.vrmdroid.settings.OutputProtocol
import com.ouor.vrmdroid.tracking.FaceFrame
import com.ouor.vrmdroid.tracking.FaceTracker
import com.ouor.vrmdroid.tracking.FaceTrackerFactory
import com.ouor.vrmdroid.tracking.FrameConverter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the camera, the tracker and the network senders. Runs as a camera foreground service so
 * tracking continues while the Unity preview (another activity/process) is in front.
 */
class TrackingService : LifecycleService() {

    private lateinit var settings: AppSettings
    private val processor = FaceProcessor()

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var tracker: FaceTracker? = null
    private var analysis: ImageAnalysis? = null
    private val converter = FrameConverter()
    private var started = false
    private var wifiLock: WifiManager.WifiLock? = null

    /** Keeps the analysis rotation in step with the display (e.g. phone moved to a landscape stand). */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val rotation = getSystemService(DisplayManager::class.java).getDisplay(displayId)?.rotation ?: return
            analysis?.targetRotation = rotation
        }
    }

    /** Touched only on [networkExecutor]. */
    private var consecutiveSendFailures = 0
    private var lastSendFailureLog = 0L

    /** Touched only on [networkExecutor]. */
    private var senders: List<TrackingSender> = emptyList()
    private val pendingSend = AtomicReference<TrackingResult?>(null)
    private var lastSendAt = 0L
    @Volatile private var stopped = false
    /** Address of the PC that last sent an iFacialMocap handshake this session. */
    @Volatile private var handshakeHost: String? = null

    private var frameCount = 0
    private var fpsWindowStart = SystemClock.uptimeMillis()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            in AppSettings.RESTART_KEYS -> analysisExecutor.execute { recreateTracker() }
            AppSettings.KEY_PROTOCOL, AppSettings.KEY_TARGET_HOST, AppSettings.KEY_VMC_PORT,
            AppSettings.KEY_IFM_PORT, AppSettings.KEY_AUTO_DETECT -> networkExecutor.execute { recreateSenders() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        // Provider binding is async, so guard with a flag rather than cameraProvider.
        if (!started) { started = true; start() }
        return START_NOT_STICKY
    }

    private fun start() {
        TrackingHub.updateStatus { it.copy(running = true, error = null) }
        networkExecutor.execute { recreateSenders() }
        analysisExecutor.execute { recreateTracker() }
        acquireWifiLock()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (stopped) return@addListener // stopped before the camera came up
            try {
                val provider = future.get()
                cameraProvider = provider
                bindCamera(provider)
            } catch (e: Exception) {
                fail(getString(R.string.error_camera_lost), e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.let { analysis.targetRotation = it.rotation }
        this.analysis = analysis
        analysis.setAnalyzer(analysisExecutor) { image ->
            image.use {
                val t = tracker
                // Skip conversion entirely when the tracker would drop the frame anyway.
                if (t == null || !t.isReady) return@use
                // Sensor timestamp (ns) rather than "now", so queueing jitter stays out of the
                // filters' time steps.
                t.submit(converter.convert(it), it.imageInfo.timestamp / 1_000_000)
            }
        }
        provider.unbindAll()
        val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        // Errors after binding (another app grabbed the camera, device policy, …) only show up
        // here; surface them instead of silently reporting "no face".
        camera.cameraInfo.cameraState.observe(this) { state ->
            val error = state.error
            if (error != null) {
                fail(getString(R.string.error_camera_lost), IllegalStateException("camera error ${error.code}"))
            } else if (state.type == CameraState.Type.OPEN) {
                TrackingHub.updateStatus { if (it.error == getString(R.string.error_camera_lost)) it.copy(error = null) else it }
            }
        }
    }

    /** Runs on [analysisExecutor]. */
    private fun recreateTracker() {
        tracker?.close()
        tracker = null
        try {
            val t = FaceTrackerFactory.create(this, settings)
            // Ignore late results from a tracker that has since been replaced.
            t.listener = { frame -> if (t === tracker) onFrame(frame) }
            tracker = t
            TrackingHub.updateStatus { it.copy(trackerName = t.name) }
        } catch (e: Exception) {
            fail("얼굴 인식을 시작하지 못했어요: ${e.message}", e)
        }
    }

    /** Called on the tracker's result thread. */
    private fun onFrame(frame: FaceFrame) {
        // A result can still arrive from MediaPipe's thread after onDestroy started.
        if (stopped) return
        if (TrackingHub.calibrationRequested) {
            TrackingHub.calibrationRequested = false
            processor.requestCalibration()
        }
        val result = processor.process(frame, settings)
        TrackingHub.publish(result)
        updateFps(frame)

        val now = SystemClock.uptimeMillis()
        if (now - lastSendAt < 1000L / settings.sendRate - 2) return
        lastSendAt = now
        // Latest-wins hand-off so a slow network never backs up the tracker.
        if (pendingSend.getAndSet(result) == null) {
            try {
                networkExecutor.execute {
                    val r = pendingSend.getAndSet(null) ?: return@execute
                    var failed: Exception? = null
                    for (s in senders) {
                        try { s.send(r, settings) } catch (e: Exception) { failed = e }
                    }
                    onSendResult(failed)
                }
            } catch (_: RejectedExecutionException) {
                // Shutting down; dropping the last frame is fine.
            }
        }
    }

    private fun updateFps(frame: FaceFrame) {
        frameCount++
        val now = SystemClock.uptimeMillis()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 500) {
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
        val list = mutableListOf<TrackingSender>(PreviewSender())
        try {
            when (settings.protocol) {
                OutputProtocol.IFACIALMOCAP -> list += IFacialMocapSender(
                    host = settings.targetHost,
                    port = settings.iFacialMocapPort,
                    adoptHandshakeHost = settings.autoDetectHost,
                    onHandshake = ::onPcHandshake,
                )
                OutputProtocol.VMC -> {
                    if (settings.targetHost.isNotEmpty()) list += VmcSender(settings.targetHost, settings.vmcPort)
                }
                OutputProtocol.NONE -> Unit
            }
        } catch (e: Exception) {
            Log.e(TAG, "sender setup failed", e)
            TrackingHub.updateStatus { it.copy(error = "보내기 설정에 문제가 있어요: ${e.message}") }
        }
        senders = list
        val portBusy = list.any { it is IFacialMocapSender && !it.listening }
        val portMsg = getString(R.string.error_port_busy, settings.iFacialMocapPort)
        TrackingHub.updateStatus {
            when {
                portBusy -> it.copy(error = portMsg)
                it.error == portMsg -> it.copy(error = null)
                else -> it
            }
        }
        publishLink()
    }

    /**
     * Runs on [networkExecutor]. Logs at most every few seconds instead of every frame, and
     * reports a sustained failure in the status so it never goes unnoticed.
     */
    private fun onSendResult(error: Exception?) {
        if (error == null) {
            if (consecutiveSendFailures >= SEND_FAILURE_THRESHOLD) {
                val msg = getString(R.string.error_send_failed)
                TrackingHub.updateStatus { if (it.error == msg) it.copy(error = null) else it }
            }
            consecutiveSendFailures = 0
            return
        }
        consecutiveSendFailures++
        val now = SystemClock.uptimeMillis()
        if (now - lastSendFailureLog > SEND_FAILURE_LOG_MS) {
            lastSendFailureLog = now
            Log.w(TAG, "send failing ($consecutiveSendFailures in a row)", error)
        }
        if (consecutiveSendFailures == SEND_FAILURE_THRESHOLD) {
            TrackingHub.updateStatus { it.copy(error = getString(R.string.error_send_failed)) }
        }
    }

    /** Called from the iFacialMocap listener thread whenever a PC app says hello. */
    private fun onPcHandshake(address: String) {
        val isNew = handshakeHost != address
        handshakeHost = address
        if (settings.autoDetectHost && settings.targetHost != address) {
            settings.targetHost = address // recreates senders via prefsListener, which republishes
        } else {
            publishLink()
        }
        if (isNew) TrackingHub.notifyPcFound(address)
    }

    private fun publishLink() {
        val protocol = settings.protocol
        val host = settings.targetHost
        val link = when {
            protocol == OutputProtocol.NONE -> PcLink.OFF
            protocol == OutputProtocol.IFACIALMOCAP && handshakeHost != null && handshakeHost == host -> PcLink.CONNECTED
            host.isNotEmpty() -> PcLink.SENDING
            else -> PcLink.WAITING
        }
        TrackingHub.updateStatus { it.copy(protocol = protocol, pcAddress = host, pcLink = link) }
    }

    /**
     * Wi-Fi power saving can hold outgoing UDP for 100ms+, especially with the screen dimmed.
     * A low-latency lock keeps the radio awake while tracking runs.
     */
    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "vrmdroid:tracking").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun fail(message: String, e: Throwable) {
        Log.e(TAG, message, e)
        TrackingHub.updateStatus { it.copy(error = message) }
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
    }

    override fun onDestroy() {
        // Order matters: stop accepting frames first, then tear down, and reset shared state
        // last so a late result can't republish a stale frame after the reset.
        stopped = true
        tracker?.listener = null
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        wifiLock?.takeIf { it.isHeld }?.release()
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor.execute { tracker?.close(); tracker = null }
        analysisExecutor.shutdown()
        networkExecutor.execute { senders.forEach { runCatching { it.close() } }; senders = emptyList() }
        networkExecutor.shutdown()
        TrackingHub.publish(null)
        TrackingHub.updateStatus { TrackingStatus() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.ouor.vrmdroid.STOP"
        private const val SEND_FAILURE_THRESHOLD = 30
        private const val SEND_FAILURE_LOG_MS = 5000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
