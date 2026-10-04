package com.ouor.vrmdroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ouor.vrmdroid.avatar.AvatarStore
import com.ouor.vrmdroid.avatar.UnityActivitySupport
import com.ouor.vrmdroid.avatar.UnityHost
import com.ouor.vrmdroid.output.SentDataMonitor
import com.ouor.vrmdroid.service.TrackingHub
import com.ouor.vrmdroid.service.TrackingService
import com.ouor.vrmdroid.service.TrackingStatus
import com.ouor.vrmdroid.settings.AppSettings
import com.ouor.vrmdroid.settings.SettingsActivity
import com.ouor.vrmdroid.ui.AdjustSheet
import com.ouor.vrmdroid.ui.CalibrationGuideView
import com.ouor.vrmdroid.ui.CameraPermission
import com.ouor.vrmdroid.ui.DataConsoleView
import com.ouor.vrmdroid.ui.FaceOverlayView
import com.ouor.vrmdroid.ui.IdleDimmer
import com.ouor.vrmdroid.ui.OnboardingActivity
import com.ouor.vrmdroid.ui.StatusSheet
import com.ouor.vrmdroid.ui.StatusText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The stage: the avatar fills the screen; a status pill on top and one big button at the bottom.
 */
class MainActivity : AppCompatActivity(), UnityActivitySupport {

    override var unityHost: UnityHost? = null
        private set

    private lateinit var settings: AppSettings
    private lateinit var facePreview: FaceOverlayView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var primary: MaterialButton
    private lateinit var calibrateAction: View
    private lateinit var emptyState: View
    private lateinit var chrome: View
    private lateinit var restoreUi: View
    private lateinit var guide: CalibrationGuideView
    private var dimmer: IdleDimmer? = null
    private lateinit var dataConsole: DataConsoleView
    private var calibrationJob: Job? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) TrackingService.start(this)
        else CameraPermission.onDenied(this)
    }

    private val pickVrm = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { AvatarStore.import(this@MainActivity, uri) }
                    .fold({ getString(R.string.vrm_loaded, it.substringBeforeLast('.')) }, { getString(R.string.vrm_load_failed, it.message ?: getString(R.string.vrm_load_failed_generic)) })
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            updateEmptyState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        settings.migrate()
        if (!settings.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        facePreview = findViewById(R.id.face_preview)
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        primary = findViewById(R.id.primary)
        calibrateAction = findViewById(R.id.action_calibrate)
        emptyState = findViewById(R.id.empty_state)
        chrome = findViewById(R.id.chrome)
        restoreUi = findViewById(R.id.restore_ui)
        guide = findViewById(R.id.calibration_guide)
        dataConsole = findViewById(R.id.data_console)

        applyInsets()
        if (UnityHost.AVAILABLE) attachUnity() else attachLandmarkStage()

        primary.setOnClickListener {
            if (TrackingHub.status.value.running) TrackingService.stop(this) else startTracking()
        }
        calibrateAction.setOnClickListener { runCalibration() }
        findViewById<View>(R.id.action_adjust).setOnClickListener { AdjustSheet.show(this, ::setFacePreviewVisible) }
        findViewById<View>(R.id.action_avatar).setOnClickListener { pickAvatar() }
        findViewById<View>(R.id.empty_cta).setOnClickListener { pickAvatar() }
        findViewById<View>(R.id.empty_help).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vrm_help_title)
                .setMessage(R.string.vrm_help_body)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        findViewById<View>(R.id.status_pill).setOnClickListener { StatusSheet.show(this) }
        findViewById<View>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.hide_ui).setOnClickListener { setChromeVisible(false) }
        restoreUi.setOnClickListener { setChromeVisible(true) }

        // Back leaves the app running in the background (like a streaming app) instead of
        // finishing it, which would end the process and stop sending to the PC.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (restoreUi.visibility == View.VISIBLE) setChromeVisible(true) else moveTaskToBack(true)
            }
        })

        dimmer = IdleDimmer(
            activity = this,
            overlay = findViewById(R.id.dim_overlay),
            badge = findViewById(R.id.dim_badge),
            dot = findViewById(R.id.dim_dot),
            text = findViewById(R.id.dim_text),
            warning = findViewById(R.id.dim_warning),
            warningCount = findViewById(R.id.dim_warning_count),
            warningTitle = findViewById(R.id.dim_warning_title),
            timeoutSec = { settings.dimTimeoutSec },
            // Only while tracking runs and nothing (sheet, dialog, guide) is in front.
            canDim = {
                TrackingHub.status.value.running && hasWindowFocus() &&
                    guide.visibility != View.VISIBLE &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            },
            // Nobody is looking: stop drawing the avatar to save heat and battery.
            onDim = { unityHost?.onPause() },
            onWake = { unityHost?.onResume() },
        ).also { it.start(lifecycleScope) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TrackingHub.status.collect { render(it); dimmer?.render(it) } }
                launch { TrackingHub.latest.collect { facePreview.result = it } }
                launch { TrackingHub.pcFound.collect(::showPcFound) }
                launch {
                    // A few refreshes per second keeps the numbers readable and cheap.
                    while (true) {
                        if (dataConsole.visibility == View.VISIBLE) dataConsole.refresh(TrackingHub.status.value.running)
                        delay(250)
                    }
                }
            }
        }

        // Coming from onboarding: guide the first "look straight ahead" once the camera is up.
        if (intent.getBooleanExtra(EXTRA_CALIBRATE, false)) {
            intent.removeExtra(EXTRA_CALIBRATE)
            lifecycleScope.launch {
                TrackingHub.status.first { it.running }
                runCalibration()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dimmer?.reset()
        unityHost?.onResume()
        // Settings may have changed while we were away.
        setFacePreviewVisible(settings.previewEnabled)
        val showConsole = settings.dataConsole
        SentDataMonitor.enabled = showConsole
        dataConsole.visibility = if (showConsole) View.VISIBLE else View.GONE
        updateEmptyState()
    }

    private fun applyInsets() {
        val topBar = findViewById<View>(R.id.top_bar)
        val controls = findViewById<View>(R.id.controls)
        val topPad = topBar.paddingTop
        val bottomPad = controls.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = topPad + bars.top)
            controls.updatePadding(bottom = bottomPad + bars.bottom)
            facePreview.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(72) }
            // The power-saving countdown card sits just below the status pill.
            findViewById<View>(R.id.dim_warning).updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(72) }
            insets
        }
    }

    /** Places the Unity avatar on the stage; the landmark view becomes a small round inset. */
    private fun attachUnity() {
        val host = UnityHost(this)
        val view = host.view ?: return attachLandmarkStage()
        unityHost = host
        findViewById<FrameLayout>(R.id.stage).addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        facePreview.compact = true
    }

    /** Without Unity, show the landmark view as the stage itself. */
    private fun attachLandmarkStage() {
        findViewById<FrameLayout>(R.id.stage).addView(FaceOverlayView(this).also { full ->
            lifecycleScope.launch { TrackingHub.latest.collect { full.result = it } }
        })
    }

    private fun pickAvatar() = pickVrm.launch(arrayOf("application/octet-stream", "model/gltf-binary", "*/*"))

    private fun startTracking() {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        when {
            needed.isEmpty() -> TrackingService.start(this)
            CameraPermission.isPermanentlyDenied(this) -> CameraPermission.showSettingsDialog(this)
            else -> {
                CameraPermission.markAsked(this)
                requestPermissions.launch(needed.toTypedArray())
            }
        }
    }

    private fun render(s: TrackingStatus) {
        val pill = StatusText.pill(this, s)
        statusText.text = pill.text
        statusDot.backgroundTintList = ContextCompat.getColorStateList(this, pill.color)

        if (s.running) {
            primary.setText(R.string.stop)
            primary.setIconResource(R.drawable.ic_stop)
            primary.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_high)
        } else {
            primary.setText(R.string.start)
            primary.setIconResource(R.drawable.ic_play)
            primary.backgroundTintList = ContextCompat.getColorStateList(this, R.color.brand)
        }
        // Calibrating only makes sense while the camera is running.
        calibrateAction.alpha = if (s.running) 1f else 0.4f
        ViewCompat.setStateDescription(calibrateAction, if (s.running) null else getString(R.string.calib_need_start))
    }

    private fun updateEmptyState() {
        val hasAvatar = AvatarStore.file(this).exists()
        emptyState.visibility = if (hasAvatar || unityHost == null) View.GONE else View.VISIBLE
    }

    private fun setFacePreviewVisible(visible: Boolean) {
        facePreview.visibility = if (visible && unityHost != null) View.VISIBLE else View.GONE
    }

    private fun setChromeVisible(visible: Boolean) {
        chrome.visibility = if (visible) View.VISIBLE else View.GONE
        restoreUi.visibility = if (visible) View.GONE else View.VISIBLE
        if (!visible) {
            Toast.makeText(this, R.string.hide_ui_hint, Toast.LENGTH_SHORT).show()
            restoreUi.announceForAccessibility(getString(R.string.hide_ui_hint))
        }
    }

    /**
     * Guided "look straight ahead": the countdown only advances while a face is visible, then
     * sets the neutral pose and shows a check mark.
     */
    private fun runCalibration() {
        if (!TrackingHub.status.value.running) {
            Toast.makeText(this, R.string.calib_need_start, Toast.LENGTH_SHORT).show()
            return
        }
        calibrationJob?.cancel()
        guide.visibility = View.VISIBLE
        guide.setOnClickListener { calibrationJob?.cancel(); guide.visibility = View.GONE }
        calibrationJob = lifecycleScope.launch {
            val holdMs = 2400L
            var held = 0L
            var last = SystemClock.uptimeMillis()
            while (held < holdMs) {
                delay(50)
                val now = SystemClock.uptimeMillis()
                if (TrackingHub.status.value.faceDetected) {
                    held += now - last
                    guide.mode = CalibrationGuideView.Mode.COUNTDOWN
                } else {
                    held = 0
                    guide.mode = CalibrationGuideView.Mode.NO_FACE
                }
                last = now
                guide.progress = held / holdMs.toFloat()
                guide.countdown = 3 - (held * 3 / holdMs).toInt().coerceAtMost(2)
            }
            TrackingHub.calibrationRequested = true
            guide.mode = CalibrationGuideView.Mode.DONE
            delay(900)
            guide.visibility = View.GONE
        }
    }

    /** Earbud-style confirmation when a PC app connects on its own. */
    private fun showPcFound(address: String) {
        val dialog = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            setBackgroundResource(R.drawable.bg_icon_circle)
            imageTintList = ContextCompat.getColorStateList(context, R.color.status_ok)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        content.addView(TextView(this).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Title)
            text = getString(R.string.pc_found_title)
            setPadding(0, dp(12), 0, 0)
        })
        content.addView(TextView(this).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Caption)
            text = getString(R.string.pc_found_body, address)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(16))
        })
        content.addView((layoutInflater.inflate(R.layout.button_primary, content, false) as MaterialButton).apply {
            setText(R.string.ok)
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(content)
        dialog.show()
        // No time limit for screen-reader users (WCAG 2.2.1).
        val a11y = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (a11y?.isTouchExplorationEnabled != true) {
            lifecycleScope.launch { delay(5000); if (dialog.isShowing) dialog.dismiss() }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (dimmer?.onTouch(ev) == true) return true
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        const val EXTRA_CALIBRATE = "calibrate"
    }

    // Unity player lifecycle forwarding.
    override fun onStart() { super.onStart(); unityHost?.onStart() }
    override fun onStop() { super.onStop(); unityHost?.onStop() }
    override fun onPause() { super.onPause(); unityHost?.onPause() }

    override fun onDestroy() {
        // Unity's destroy() ends the whole process, tracking service included. Only do it when
        // the activity is really finishing; anything else (config change, system reclaim)
        // must not take the stream down with it.
        if (isFinishing && !isChangingConfigurations) unityHost?.onDestroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        unityHost?.onConfigurationChanged(newConfig)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        unityHost?.onWindowFocusChanged(hasFocus)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        unityHost?.onTrimMemory(level)
    }
}
