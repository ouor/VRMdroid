package com.ouor.vrmdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ouor.vrmdroid.MainActivity
import com.ouor.vrmdroid.R
import com.ouor.vrmdroid.avatar.UnityHost
import com.ouor.vrmdroid.output.IFacialMocapSender
import com.ouor.vrmdroid.output.PreviewSender
import com.ouor.vrmdroid.output.TrackingSender
import com.ouor.vrmdroid.output.VmcSender
import com.ouor.vrmdroid.settings.AppSettings
import com.ouor.vrmdroid.settings.OutputProtocol
import com.ouor.vrmdroid.settings.TrackingConfig
import com.ouor.vrmdroid.tracking.FaceFrame
import com.ouor.vrmdroid.tracking.FaceTracker
import com.ouor.vrmdroid.tracking.FaceTrackerFactory
import com.ouor.vrmdroid.tracking.FrameConverter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/**
 * The Android shell around [TrackingEngine]: owns the camera, the tracker, the Wi-Fi lock and the
 * notification, and turns settings changes into engine calls. Runs as a camera foreground service
 * so tracking and sending continue while the app is in the background.
 */
class TrackingService : LifecycleService() {

    private lateinit var settings: AppSettings
    private lateinit var engine: TrackingEngine

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var tracker: FaceTracker? = null
    private var analysis: ImageAnalysis? = null
    private val converter = FrameConverter()
    /**
     * The camera frame being tracked, held open until the tracker is done with it. While it is
     * open CameraX keeps only the newest frame waiting and hands it over the moment this one is
     * closed, so the tracker starts the next frame right away instead of waiting up to a frame
     * interval for the camera, and frames that would be dropped are never converted.
     */
    private val inFlight = AtomicReference<ImageProxy?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    private var wifiLock: WifiManager.WifiLock? = null
    private val perf = PerfStats(SystemClock::uptimeMillis)

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

    @Volatile private var stopped = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        engine.config = TrackingConfig.from(settings)
        when (key) {
            in AppSettings.RESTART_KEYS -> analysisExecutor.execute { recreateTracker() }
            AppSettings.KEY_PROTOCOL, AppSettings.KEY_TARGET_HOST, AppSettings.KEY_VMC_PORT,
            AppSettings.KEY_IFM_PORT, AppSettings.KEY_AUTO_DETECT -> engine.reconfigureSenders()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        engine = TrackingEngine(
            networkExecutor = networkExecutor,
            senderSettings = {
                SenderSettings(settings.protocol, settings.targetHost, settings.iFacialMocapPort, settings.vmcPort, settings.autoDetectHost)
            },
            createSenders = ::createSenders,
            adoptHost = { settings.targetHost = it },
            messages = object : EngineMessages {
                override val sendFailed get() = getString(R.string.error_send_failed)
                override fun portBusy(port: Int) = getString(R.string.error_port_busy, port)
                override fun senderSetupFailed(reason: String?) = getString(R.string.error_sender_setup, reason ?: "")
            },
            clock = SystemClock::uptimeMillis,
            warn = { msg, e -> Log.w(TAG, msg, e) },
        )
        engine.config = TrackingConfig.from(settings)
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        lifecycleScope.launch { TrackingHub.calibrationRequests.collect { engine.requestCalibration() } }
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
        engine.reconfigureSenders()
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
        val builder = ImageAnalysis.Builder()
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
            // Upright frames straight from CameraX's native YUV conversion (see FrameConverter).
            .setOutputImageRotationEnabled(true)
        // Auto exposure otherwise stretches frames in dim rooms and tracking drops to ~15 fps.
        // A steady frame rate matters more to tracking than a brighter image.
        frontCameraFpsRange()?.let {
            Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }
        val analysis = builder.build()
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.let { analysis.targetRotation = it.rotation }
        this.analysis = analysis
        analysis.setAnalyzer(analysisExecutor, ::track)
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

    /** Runs on [analysisExecutor] for each camera frame CameraX hands over. */
    private fun track(image: ImageProxy) {
        val t = tracker
        if (t == null || stopped) { image.close(); return }
        // CameraX delivers a frame only after the previous one is closed, so this is normally null.
        inFlight.getAndSet(image)?.close()
        // If the tracker never answers (e.g. GPU context lost), release the frame so the camera
        // keeps flowing.
        mainHandler.postDelayed({
            if (inFlight.compareAndSet(image, null)) {
                Log.w(TAG, "No result for ${STALL_MS}ms; moving on")
                image.close()
            }
        }, STALL_MS)
        try {
            val start = SystemClock.elapsedRealtimeNanos()
            Trace.beginSection("vrm.convert")
            val pixels = try { converter.pixels(image) } finally { Trace.endSection() }
            perf.onConvert((SystemClock.elapsedRealtimeNanos() - start) / 1e6f)
            // Sensor timestamp (ns) rather than "now", so queueing jitter stays out of the
            // filters' time steps.
            t.submit(pixels, image.width, image.height, image.imageInfo.timestamp / 1_000_000)
        } catch (e: Exception) {
            Log.e(TAG, "frame submit failed", e)
            releaseFrame()
        }
    }

    /** Closes the frame being tracked, which lets CameraX deliver the newest one. */
    private fun releaseFrame() {
        inFlight.getAndSet(null)?.close()
    }

    /** Runs on [analysisExecutor]. */
    private fun recreateTracker() {
        tracker?.close()
        tracker = null
        releaseFrame() // the old tracker will never finish it
        try {
            val t = FaceTrackerFactory.create(this, settings)
            // Ignore late results from a tracker that has since been replaced.
            t.frameDone = { if (t === tracker) releaseFrame() }
            t.listener = { frame -> if (t === tracker) onFrame(frame) }
            tracker = t
            TrackingHub.updateStatus { it.copy(trackerName = t.name) }
        } catch (e: Exception) {
            fail("얼굴 인식을 시작하지 못했어요: ${e.message}", e)
        }
    }

    /** Runs on the tracker's result thread. */
    private fun onFrame(frame: FaceFrame) {
        perf.onResult(frame.detected, frame.inferenceMs.toFloat(), frameAgeMs(frame.timestampMs))
        Trace.beginSection("vrm.process")
        try { engine.onFrame(frame) } finally { Trace.endSection() }
        perf.poll(::deviceState)?.let { Log.i(PERF_TAG, it) }
    }

    /**
     * Milliseconds from capture to now. Camera timestamps use either the uptime or the realtime
     * clock depending on the device; the one giving a plausible age is the right one.
     */
    private fun frameAgeMs(sensorMs: Long): Float? =
        longArrayOf(SystemClock.uptimeMillis() - sensorMs, SystemClock.elapsedRealtime() - sensorMs)
            .firstOrNull { it in 0..MAX_FRAME_AGE_MS }?.toFloat()

    /** Thermal state for the perf log; headroom 1.0 is where the device starts throttling. */
    private fun deviceState(): String {
        val power = getSystemService(PowerManager::class.java)
        val headroom = power.getThermalHeadroom(0)
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
        return "thermal=${power.currentThermalStatus}" +
            " headroom=" + (if (headroom.isNaN()) "-" else "%.2f".format(headroom)) +
            " battery=" + (battery?.let { "%.1fC".format(it / 10f) } ?: "-")
    }

    /** Runs on the network thread (see [TrackingEngine]). */
    private fun createSenders(s: SenderSettings, onHandshake: (String) -> Unit): List<TrackingSender> {
        // The local avatar feed is only useful when Unity is linked into this build.
        val list = mutableListOf<TrackingSender>()
        if (UnityHost.AVAILABLE) list += PreviewSender()
        when (s.protocol) {
            OutputProtocol.IFACIALMOCAP -> list += IFacialMocapSender(
                host = s.host,
                port = s.iFacialMocapPort,
                adoptHandshakeHost = s.autoDetectHost,
                onHandshake = onHandshake,
            )
            OutputProtocol.VMC -> if (s.host.isNotEmpty()) list += VmcSender(s.host, s.vmcPort)
            OutputProtocol.NONE -> Unit
        }
        return list
    }

    /**
     * The front camera's auto-exposure range closest to a fixed 30 fps: highest upper bound up to
     * 30, then the highest lower bound. Null when the camera can't be queried; CameraX then keeps
     * its default.
     */
    private fun frontCameraFpsRange(): Range<Int>? = runCatching {
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
        val ranges = manager.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ranges?.filter { it.upper <= TARGET_FPS }
            ?.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    }.onFailure { Log.w(TAG, "fps ranges unavailable", it) }.getOrNull()

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
        engine.shutdown()
        tracker?.listener = null
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        wifiLock?.takeIf { it.isHeld }?.release()
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor.execute { tracker?.close(); tracker = null; releaseFrame() }
        analysisExecutor.shutdown()
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
        private const val TARGET_FPS = 30
        private const val PERF_TAG = "VrmPerf"
        private const val MAX_FRAME_AGE_MS = 2_000L
        private const val STALL_MS = 1_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
