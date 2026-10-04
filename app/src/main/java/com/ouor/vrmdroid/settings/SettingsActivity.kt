package com.ouor.vrmdroid.settings

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ouor.vrmdroid.R
import androidx.lifecycle.ViewModelProvider
import com.ouor.vrmdroid.ui.ConnectActivity
import com.ouor.vrmdroid.ui.Rows
import com.ouor.vrmdroid.ui.Rows.Option
import com.ouor.vrmdroid.ui.StepViewModel

/**
 * Settings grouped by what the user is trying to do. Everyday items come first in plain words;
 * technical knobs live under a collapsed "고급" section and only for the active sending method.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var rows: Rows
    private lateinit var body: LinearLayout
    private val state by lazy { ViewModelProvider(this)[StepViewModel::class.java] }
    /** Whether the "고급" section is expanded; kept across rotation. */
    private var advancedOpen: Boolean
        get() = state.step == STEP_ADVANCED
        set(value) { state.step = if (value) STEP_ADVANCED else null }
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        rows = Rows(this, settings.prefs)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ContextCompat.getColorStateList(context, R.color.text_primary)
            setBackgroundResource(android.R.color.transparent)
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Title)
            text = getString(R.string.action_settings)
        })
        root.addView(header)

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        build() // the connect guide may have changed the sending method
    }

    private fun build() {
        body.removeAllViews()
        val protocol = settings.protocol

        // PC 연결
        rows.section(body, getString(R.string.set_section_pc))
        val pc = rows.card(body)
        rows.link(pc, getString(R.string.connect_title), getString(R.string.set_connect_desc)) {
            startActivity(ConnectActivity.intent(this))
        }
        rows.divider(pc)
        rows.choice(pc, AppSettings.KEY_PROTOCOL, getString(R.string.set_protocol), options = listOf(
            Option("ifacialmocap", getString(R.string.set_protocol_ifm), getString(R.string.set_protocol_ifm_desc)),
            Option("vmc", getString(R.string.set_protocol_vmc), getString(R.string.set_protocol_vmc_desc)),
            Option("none", getString(R.string.set_protocol_none), getString(R.string.set_protocol_none_desc)),
        ), default = "ifacialmocap") { build() }
        if (protocol != OutputProtocol.NONE) {
            rows.divider(pc)
            rows.textField(pc, AppSettings.KEY_TARGET_HOST, getString(R.string.connect_pc_address),
                if (protocol == OutputProtocol.IFACIALMOCAP) getString(R.string.set_pc_address_auto_desc) else getString(R.string.connect_pc_address_help),
                default = "", hint = "192.168.0.10",
                display = { it.ifEmpty { getString(if (protocol == OutputProtocol.IFACIALMOCAP && settings.autoDetectHost) R.string.set_pc_auto else R.string.set_pc_unset) } })
        }
        if (protocol == OutputProtocol.IFACIALMOCAP) {
            rows.divider(pc)
            rows.switch(pc, AppSettings.KEY_AUTO_DETECT, getString(R.string.set_auto_detect),
                getString(R.string.set_auto_detect_desc), true)
        }

        // 내 표정
        rows.section(body, getString(R.string.set_section_face))
        val face = rows.card(body)
        rows.slider(face, AppSettings.KEY_BLINK_SENSITIVITY, getString(R.string.adjust_blink),
            getString(R.string.less_sensitive), getString(R.string.more_sensitive), 50, 200, AppSettings.DEFAULT_SENSITIVITY)
        rows.slider(face, AppSettings.KEY_MOUTH_SENSITIVITY, getString(R.string.adjust_mouth),
            getString(R.string.less_sensitive), getString(R.string.more_sensitive), 50, 200, AppSettings.DEFAULT_SENSITIVITY)
        rows.slider(face, AppSettings.KEY_SMOOTHING, getString(R.string.adjust_smooth),
            getString(R.string.faster), getString(R.string.smoother), 0, 100, AppSettings.DEFAULT_SMOOTHING,
            getString(R.string.set_smooth_desc))
        rows.divider(face)
        rows.switch(face, AppSettings.KEY_MIRROR, getString(R.string.adjust_mirror), getString(R.string.adjust_mirror_desc), true)
        rows.divider(face)
        rows.switch(face, AppSettings.KEY_LINK_EYES, getString(R.string.adjust_link_eyes), getString(R.string.adjust_link_eyes_desc), false)

        // 화면
        rows.section(body, getString(R.string.set_section_screen))
        val screen = rows.card(body)
        rows.switch(screen, AppSettings.KEY_PREVIEW_OVERLAY, getString(R.string.adjust_face_dots), getString(R.string.adjust_face_dots_desc), false)
        rows.divider(screen)
        rows.choice(screen, AppSettings.KEY_DIM_TIMEOUT, getString(R.string.set_dim), options = listOf(
            Option("0", getString(R.string.set_dim_off), getString(R.string.set_dim_off_desc)),
            Option("30", getString(R.string.set_dim_30s)),
            Option("60", getString(R.string.set_dim_1m)),
            Option("180", getString(R.string.set_dim_3m)),
            Option("300", getString(R.string.set_dim_5m)),
        ), default = settings.dimTimeoutSec.toString(), description = getString(R.string.set_dim_option_desc))

        // 고급 (접힘)
        rows.section(body, getString(R.string.set_section_advanced))
        val adv = rows.card(body)
        rows.link(adv, getString(if (advancedOpen) R.string.set_advanced_close else R.string.set_advanced_open),
            getString(R.string.set_advanced_desc)) {
            advancedOpen = !advancedOpen
            build()
        }
        if (advancedOpen) buildAdvanced(adv, protocol)

        body.addView((layoutInflater.inflate(R.layout.button_quiet, body, false) as MaterialButton).apply {
            text = getString(R.string.reset_defaults)
            setOnClickListener { confirmReset() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28)
        })
    }

    private fun buildAdvanced(card: LinearLayout, protocol: OutputProtocol) {
        when (protocol) {
            OutputProtocol.IFACIALMOCAP -> {
                rows.divider(card)
                rows.switch(card, AppSettings.KEY_INVERT_PITCH, getString(R.string.set_invert_pitch), getString(R.string.set_invert_desc), false)
                rows.switch(card, AppSettings.KEY_INVERT_YAW, getString(R.string.set_invert_yaw), null, false)
                rows.switch(card, AppSettings.KEY_INVERT_ROLL, getString(R.string.set_invert_roll), null, false)
                rows.divider(card)
                rows.textField(card, AppSettings.KEY_IFM_PORT, getString(R.string.set_port), getString(R.string.set_port_ifm_desc), "49983", numeric = true)
            }
            OutputProtocol.VMC -> {
                rows.divider(card)
                rows.switch(card, AppSettings.KEY_VMC_POSITION, getString(R.string.set_vmc_position), getString(R.string.set_vmc_position_desc), true)
                rows.slider(card, AppSettings.KEY_VMC_POSITION_SCALE, getString(R.string.set_vmc_position_scale),
                    getString(R.string.smaller), getString(R.string.bigger), 0, 300, 100)
                rows.divider(card)
                rows.switch(card, AppSettings.KEY_VMC_PRESETS, getString(R.string.set_vmc_presets), getString(R.string.set_vmc_presets_desc), true)
                rows.switch(card, AppSettings.KEY_VMC_PERFECT_SYNC, getString(R.string.set_vmc_perfect_sync), getString(R.string.set_vmc_perfect_sync_desc), true)
                rows.switch(card, AppSettings.KEY_VMC_EMOTIONS, getString(R.string.set_vmc_emotions), getString(R.string.set_vmc_emotions_desc), false)
                rows.switch(card, AppSettings.KEY_VMC_ARM_POSE, getString(R.string.set_vmc_arm_pose), getString(R.string.set_vmc_arm_pose_desc), true)
                rows.divider(card)
                rows.textField(card, AppSettings.KEY_VMC_PORT, getString(R.string.set_port), getString(R.string.set_port_vmc_desc), "39539", numeric = true)
            }
            OutputProtocol.NONE -> Unit
        }
        rows.divider(card)
        rows.switch(card, AppSettings.KEY_DATA_CONSOLE, getString(R.string.set_data_console), getString(R.string.set_data_console_desc), false)
        rows.divider(card)
        rows.textField(card, AppSettings.KEY_SEND_RATE, getString(R.string.set_send_rate), getString(R.string.set_send_rate_desc), "60", numeric = true,
            display = { getString(R.string.set_send_rate_value, it.ifEmpty { "60" }) })
        rows.switch(card, AppSettings.KEY_USE_GPU, getString(R.string.set_gpu), getString(R.string.set_gpu_desc), true)
    }


    private companion object {
        const val STEP_ADVANCED = "advanced"
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_defaults)
            .setMessage(R.string.set_reset_confirm)
            .setPositiveButton(R.string.set_reset_yes) { _, _ ->
                settings.resetAll()
                Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
