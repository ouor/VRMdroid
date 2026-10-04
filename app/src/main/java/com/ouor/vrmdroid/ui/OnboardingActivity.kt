package com.ouor.vrmdroid.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ouor.vrmdroid.MainActivity
import com.ouor.vrmdroid.R
import com.ouor.vrmdroid.avatar.AvatarStore
import com.ouor.vrmdroid.service.TrackingService
import com.ouor.vrmdroid.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First run, one question per screen: welcome → camera → avatar → where to use it → ready.
 * Finishing starts tracking and hands over to the main screen's guided "정면 맞추기".
 */
class OnboardingActivity : AppCompatActivity() {

    private enum class Step { WELCOME, CAMERA, AVATAR, WHERE, READY }

    private lateinit var settings: AppSettings
    private lateinit var progress: TextView
    private lateinit var body: LinearLayout
    private lateinit var primary: MaterialButton
    private lateinit var secondary: MaterialButton
    private var step = Step.WELCOME
    /** Steps actually shown; the camera step is left out when permission was already given. */
    private lateinit var steps: List<Step>
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) go(Step.AVATAR)
        else CameraPermission.onDenied(this)
    }

    private val pickVrm = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AvatarStore.import(this@OnboardingActivity, uri) } }
            result.onSuccess {
                Toast.makeText(this@OnboardingActivity, getString(R.string.vrm_loaded, it.substringBeforeLast('.')), Toast.LENGTH_SHORT).show()
                go(Step.WHERE)
            }.onFailure {
                Toast.makeText(this@OnboardingActivity, getString(R.string.vrm_load_failed, it.message ?: getString(R.string.vrm_load_failed_generic)), Toast.LENGTH_LONG).show()
            }
        }
    }

    private val connect = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        go(Step.READY)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        progress = TextView(this).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Caption)
            setPadding(dp(24), dp(20), dp(24), 0)
        }
        root.addView(progress)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        primary = layoutInflater.inflate(R.layout.button_primary, buttons, false) as MaterialButton
        secondary = (layoutInflater.inflate(R.layout.button_text, null) as MaterialButton).apply {
            setTextColor(getColor(R.color.text_secondary))
        }
        buttons.addView(primary)
        buttons.addView(secondary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(buttons)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val index = steps.indexOf(step)
                if (index <= 0) finish() else go(steps[index - 1])
            }
        })
        steps = Step.entries.filter { it != Step.CAMERA || !hasCamera() }
        val restored = savedInstanceState?.getString(STATE_STEP)?.let { name -> Step.entries.firstOrNull { it.name == name } }
        go(restored ?: Step.WELCOME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_STEP, step.name)
    }

    private companion object {
        const val STATE_STEP = "step"
    }

    private fun go(next: Step) {
        step = if (next in steps) next else steps[(steps.indexOfFirst { it.ordinal > next.ordinal }).coerceAtLeast(0)]
        body.removeAllViews()
        progress.text = "${steps.indexOf(step) + 1} / ${steps.size}"
        secondary.visibility = View.GONE
        when (step) {
            Step.WELCOME -> {
                hero(R.drawable.ic_face)
                body.addView(display(getString(R.string.ob_welcome_title)), spaced(24))
                body.addView(caption(getString(R.string.ob_welcome_body)), spaced(12))
                feature(R.drawable.ic_face, getString(R.string.ob_feature_face))
                feature(R.drawable.ic_computer, getString(R.string.ob_feature_pc))
                feature(R.drawable.ic_phone, getString(R.string.ob_feature_phone))
                primary(getString(R.string.start)) { go(Step.CAMERA) }
            }
            Step.CAMERA -> {
                hero(R.drawable.ic_videocam)
                body.addView(display(getString(R.string.ob_camera_title)), spaced(24))
                body.addView(caption(getString(R.string.ob_camera_body)), spaced(12))
                primary(getString(R.string.ob_camera_cta)) {
                    if (CameraPermission.isPermanentlyDenied(this)) {
                        CameraPermission.showSettingsDialog(this)
                    } else {
                        CameraPermission.markAsked(this)
                        requestPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
                // Never a dead end: the rest of setup works without the camera for now.
                secondary(getString(R.string.ob_later)) { go(Step.AVATAR) }
            }
            Step.AVATAR -> {
                hero(R.drawable.ic_folder)
                body.addView(display(getString(R.string.ob_avatar_title)), spaced(24))
                body.addView(caption(getString(R.string.ob_avatar_body)), spaced(12))
                body.addView((layoutInflater.inflate(R.layout.button_text, null) as MaterialButton).apply {
                    text = getString(R.string.empty_help)
                    setTextColor(getColor(R.color.brand_text))
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@OnboardingActivity)
                            .setTitle(R.string.vrm_help_title)
                            .setMessage(R.string.vrm_help_body)
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                primary(getString(R.string.ob_avatar_cta)) {
                    pickVrm.launch(arrayOf("application/octet-stream", "model/gltf-binary", "*/*"))
                }
                secondary(getString(R.string.ob_later)) { go(Step.WHERE) }
            }
            Step.WHERE -> {
                body.addView(display(getString(R.string.ob_where_title)))
                body.addView(caption(getString(R.string.ob_where_body)), spaced(12))
                choice(R.drawable.ic_computer, getString(R.string.ob_where_pc), getString(R.string.ob_where_pc_desc)) {
                    connect.launch(ConnectActivity.intent(this))
                }
                choice(R.drawable.ic_phone, getString(R.string.ob_where_phone), getString(R.string.ob_where_phone_desc)) {
                    settings.protocolValue = "none"
                    go(Step.READY)
                }
                primary.visibility = View.GONE
            }
            Step.READY -> {
                hero(R.drawable.ic_check, R.color.status_ok)
                body.addView(display(getString(R.string.ob_ready_title)), spaced(24))
                body.addView(caption(getString(R.string.ob_ready_body)), spaced(12))
                primary(getString(R.string.start)) { finishOnboarding() }
            }
        }
    }

    private fun finishOnboarding() {
        settings.onboardingDone = true
        if (hasCamera()) TrackingService.start(this)
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CALIBRATE, true))
        finish()
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hero(icon: Int, tint: Int = R.color.brand_text) {
        body.addView(ImageView(this).apply {
            setImageResource(icon)
            setBackgroundResource(R.drawable.bg_icon_circle)
            imageTintList = ContextCompat.getColorStateList(context, tint)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }, LinearLayout.LayoutParams(dp(80), dp(80)))
    }

    private fun feature(icon: Int, text: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ContextCompat.getColorStateList(context, R.color.brand_text)
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        row.addView(TextView(this).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Body)
            this.text = text
            setPadding(dp(14), 0, 0, 0)
        })
        body.addView(row, spaced(if (body.childCount <= 3) 32 else 16))
    }

    private fun choice(icon: Int, title: String, desc: String, onClick: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
            setPadding(dp(16), dp(20), dp(12), dp(20))
            isClickable = true
            isFocusable = true
            foreground = themeRipple(context)
            setOnClickListener { onClick() }
        }
        card.addView(ImageView(this).apply {
            setImageResource(icon)
            setBackgroundResource(R.drawable.bg_icon_circle)
            imageTintList = ContextCompat.getColorStateList(context, R.color.brand_text)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        labels.addView(TextView(this).apply { setTextAppearance(R.style.Text_Vrmdroid_Body); this.text = title; paint.isFakeBoldText = true })
        labels.addView(caption(desc))
        card.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ContextCompat.getColorStateList(context, R.color.text_tertiary)
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        body.addView(card, spaced(if (body.childCount == 2) 28 else 12))
    }

    private fun primary(text: String, onClick: () -> Unit) {
        primary.visibility = View.VISIBLE
        primary.text = text
        primary.setOnClickListener { onClick() }
    }

    private fun secondary(text: String, onClick: () -> Unit) {
        secondary.visibility = View.VISIBLE
        secondary.text = text
        secondary.setOnClickListener { onClick() }
    }

    private fun display(text: String) = TextView(this).apply { setTextAppearance(R.style.Text_Vrmdroid_Display); this.text = text }
    private fun caption(text: String) = TextView(this).apply { setTextAppearance(R.style.Text_Vrmdroid_Caption); textSize = 15f; this.text = text }
    private fun spaced(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
}
