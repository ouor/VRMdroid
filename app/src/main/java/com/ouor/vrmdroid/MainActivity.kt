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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ouor.vrmdroid.avatar.AvatarState
import com.ouor.vrmdroid.avatar.AvatarStore
import com.ouor.vrmdroid.avatar.UnityBridge
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val isDimmed get() = dimmer?.dimmed == true
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
        // A clean stage: the status bar stays hidden and peeks in on a swipe from the top.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
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
        findViewById<View>(R.id.sample_chip).setOnClickListener { pickAvatar() }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { AvatarStore.ensureAvatar(this@MainActivity) }
            updateEmptyState()
        }
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
            // Nobody is looking: barely draw the avatar and stop updating what's hidden under
            // the dim overlay, to save heat and battery. Tracking and sending carry on.
            onDim = {
                unityHost?.setLowPower(true)
                SentDataMonitor.enabled = false
            },
            onWake = {
                unityHost?.setLowPower(false)
                SentDataMonitor.enabled = settings.dataConsole
                render(TrackingHub.status.value)
            },
        ).also { it.start(lifecycleScope) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TrackingHub.status.collect { if (!isDimmed) render(it); dimmer?.render(it) } }
                launch { TrackingHub.latest.collect { if (!isDimmed) facePreview.result = it } }
                launch { TrackingHub.pcFound.collect(::showPcFound) }
                launch { UnityBridge.avatarState.collect(::renderAvatarState) }
                launch {
                    // Re-sent when the avatar (re)loads too: a message sent before the player
                    // was up is lost.
                    combine(TrackingHub.status.map { it.thermalLevel }.distinctUntilChanged(), UnityBridge.avatarState) { level, _ -> level }
                        .collect { unityHost?.setRenderBudget(it.maxAvatarFps, it.renderScale, it.outlines) }
                }
                launch {
                    // A few refreshes per second keeps the numbers readable and cheap.
                    while (true) {
                        if (dataConsole.visibility == View.VISIBLE && !isDimmed) dataConsole.refresh(TrackingHub.status.value, TrackingHub.latest.value)
                        delay(250)
                    }
                }
            }
        }

        // Benchmarks: `adb shell am start -n com.ouor.vrmdroid/.MainActivity --ez start_tracking true`
        // (debug builds only) starts tracking without a tap, so runs are repeatable.
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_START, false)) {
            intent.removeExtra(EXTRA_DEBUG_START)
            startTracking()
        }

        // Coming from onboarding: guide the first "look straight ahead" once the camera is up
        // and the avatar is on screen; a guide over an empty stage is confusing.
        if (intent.getBooleanExtra(EXTRA_CALIBRATE, false)) {
            intent.removeExtra(EXTRA_CALIBRATE)
            lifecycleScope.launch {
                // The camera can fail to start (permission revoked, camera busy); don't spring the
                // guide on the user minutes later.
                val started = withTimeoutOrNull(CALIBRATE_WAIT_MS) { TrackingHub.status.first { it.running } }
                // A first load of a large VRM can take a while; past that, guide anyway.
                if (started != null && unityHost != null) {
                    withTimeoutOrNull(AVATAR_WAIT_MS) { UnityBridge.avatarSettled.first { it } }
                }
                if (started != null && TrackingHub.status.value.running) runCalibration()
                else Toast.makeText(this@MainActivity, R.string.calib_later, Toast.LENGTH_LONG).show()
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
        val sidePad = topBar.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            // Cutouts and a side navigation bar matter in landscape.
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            topBar.updatePadding(top = topPad + bars.top, left = sidePad + bars.left, right = sidePad + bars.right)
            controls.updatePadding(
                // Landscape: a side panel whose gradient fades in from its left edge.
                left = if (landscape) dp(LANDSCAPE_PANEL_FADE_DP) else dp(20) + bars.left,
                top = if (landscape) dp(16) else dp(56),
                right = dp(20) + bars.right,
                bottom = bottomPad + bars.bottom,
            )
            facePreview.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(72) }
            findViewById<View>(R.id.sample_chip).updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = bars.top + dp(72)
                leftMargin = dp(16) + bars.left
            }
            // The power-saving countdown card sits just below the status pill.
            findViewById<View>(R.id.dim_warning).updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(72) }
            insets
        }
        applyOrientationLayout(resources.configuration)
    }

    /**
     * Portrait: controls span the bottom. Landscape: the same controls sit in a panel on the end
     * side and the avatar is centered in the remaining area. Done in code because MainActivity handles
     * orientation changes itself (recreating it would tear down the embedded Unity player).
     */
    private fun applyOrientationLayout(config: Configuration) {
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val controls = findViewById<View>(R.id.controls)
        controls.updateLayoutParams<FrameLayout.LayoutParams> {
            width = if (landscape) dp(LANDSCAPE_PANEL_DP) else ViewGroup.LayoutParams.MATCH_PARENT
            gravity = if (landscape) Gravity.BOTTOM or Gravity.END else Gravity.BOTTOM
        }
        controls.setBackgroundResource(if (landscape) R.drawable.bg_end_scrim else R.drawable.bg_bottom_scrim)
        // Landscape: shrink the avatar stage to the area left of the panel, so the avatar is
        // centered there instead of behind the controls. Unity re-fits its camera to the new
        // aspect; behind the panel the root shows the same stage color, so there's no seam.
        findViewById<View>(R.id.stage).updateLayoutParams<FrameLayout.LayoutParams> {
            marginEnd = if (landscape) dp(LANDSCAPE_PANEL_DP - LANDSCAPE_PANEL_FADE_DP) else 0
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.root))
    }

    /** Places the Unity avatar on the stage; the landmark view becomes a small round inset. */
    private fun attachUnity() {
        val host = UnityHost(this)
        val view = host.view ?: return attachLandmarkStage()
        unityHost = host
        // Index 0: under the status text that shares the stage.
        findViewById<FrameLayout>(R.id.stage).addView(view, 0, FrameLayout.LayoutParams(
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

    private fun renderAvatarState(state: AvatarState) {
        val text = findViewById<TextView>(R.id.stage_status)
        val message = when (state) {
            AvatarState.NONE -> null
            AvatarState.LOADING -> R.string.avatar_loading
            AvatarState.FAILED -> R.string.avatar_failed
            AvatarState.RESTORED -> R.string.avatar_restored
        }
        text.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) text.setText(message)
        // A rolled-back import may have brought the sample avatar back.
        if (state == AvatarState.NONE || state == AvatarState.RESTORED) updateEmptyState()
    }

    private fun updateEmptyState() {
        val hasAvatar = AvatarStore.file(this).exists()
        emptyState.visibility = if (hasAvatar || unityHost == null) View.GONE else View.VISIBLE
        val sample = unityHost != null && AvatarStore.isSample(this)
        findViewById<View>(R.id.sample_chip).visibility = if (sample) View.VISIBLE else View.GONE
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
            TrackingHub.requestCalibration()
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
        private const val EXTRA_DEBUG_START = "start_tracking"
        private const val LANDSCAPE_PANEL_DP = 440
        private const val CALIBRATE_WAIT_MS = 10_000L
        private const val AVATAR_WAIT_MS = 20_000L
        private const val LANDSCAPE_PANEL_FADE_DP = 72
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
        applyOrientationLayout(newConfig)
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
