package com.ouor.vrmdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    private var tracker: FaceTracker? = null

    /** Touched only on [networkExecutor]. */
    private var senders: List<TrackingSender> = emptyList()
    private val pendingSend = AtomicReference<TrackingResult?>(null)
    private var lastSendAt = 0L
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
        if (cameraProvider == null) start()
        return START_NOT_STICKY
    }

    private fun start() {
        TrackingHub.updateStatus { it.copy(running = true, error = null) }
        networkExecutor.execute { recreateSenders() }
        analysisExecutor.execute { recreateTracker() }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindCamera(provider)
            } catch (e: Exception) {
                fail("카메라를 열 수 없어요. 다른 앱이 카메라를 쓰고 있는지 확인해 주세요.", e)
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
        analysis.setAnalyzer(analysisExecutor) { image ->
            val rotation = image.imageInfo.rotationDegrees
            val bitmap = try { image.toBitmap() } finally { image.close() }
            tracker?.submit(bitmap, rotation, SystemClock.uptimeMillis())
        }
        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
    }

    /** Runs on [analysisExecutor]. */
    private fun recreateTracker() {
        tracker?.close()
        tracker = null
        try {
            val t = FaceTrackerFactory.create(this, settings)
            t.listener = ::onFrame
            tracker = t
            TrackingHub.updateStatus { it.copy(trackerName = t.name) }
        } catch (e: Exception) {
            fail("얼굴 인식을 시작하지 못했어요: ${e.message}", e)
        }
    }

    /** Called on the tracker's result thread. */
    private fun onFrame(frame: FaceFrame) {
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
            networkExecutor.execute {
                val r = pendingSend.getAndSet(null) ?: return@execute
                for (s in senders) {
                    try { s.send(r, settings) } catch (e: Exception) { Log.w(TAG, "send failed: ${s.destination}", e) }
                }
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
        publishLink()
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
