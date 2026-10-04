package com.ouor.vrmdroid.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.ouor.vrmdroid.R
import com.ouor.vrmdroid.service.PcLink
import com.ouor.vrmdroid.service.TrackingHub
import com.ouor.vrmdroid.service.TrackingService
import com.ouor.vrmdroid.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "PC와 연결하기": one question per screen. First pick the PC program, then follow a few
 * numbered steps while the bottom card shows live whether the PC has connected.
 */
class ConnectActivity : AppCompatActivity() {

    private enum class Program(val protocol: String) { VSEEFACE("ifacialmocap"), IPHONE_APPS("ifacialmocap"), VMC("vmc") }

    private lateinit var settings: AppSettings
    private lateinit var body: LinearLayout
    private lateinit var primary: MaterialButton
    private lateinit var titleView: TextView
    private var program: Program? = null
    private var statusJob: Job? = null
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)

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
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleView = TextView(this).apply { setTextAppearance(R.style.Text_Vrmdroid_Body) }
        header.addView(titleView)
        root.addView(header)

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        primary = layoutInflater.inflate(R.layout.button_primary, null) as MaterialButton
        root.addView(FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(16))
            addView(primary, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        showChooser()
    }

    private fun onBack() {
        if (program != null) showChooser() else finish()
    }

    // Step 1: which PC program?
    private fun showChooser() {
        program = null
        statusJob?.cancel()
        titleView.text = getString(R.string.connect_title)
        body.removeAllViews()
        body.addView(display(getString(R.string.connect_which)))
        body.addView(caption(getString(R.string.connect_which_desc)), spaced(8))

        option(R.drawable.ic_computer, getString(R.string.connect_vseeface), getString(R.string.connect_vseeface_desc)) { showGuide(Program.VSEEFACE) }
        option(R.drawable.ic_phone, getString(R.string.connect_iphone_apps), getString(R.string.connect_iphone_apps_desc)) { showGuide(Program.IPHONE_APPS) }
        option(R.drawable.ic_wifi, getString(R.string.connect_vmc), getString(R.string.connect_vmc_desc)) { showGuide(Program.VMC) }

        primary.text = getString(R.string.connect_phone_only)
        primary.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_high)
        primary.setOnClickListener {
            settings.protocolValue = "none"
            finishFlow()
        }
    }

    private fun option(icon: Int, title: String, desc: String, onClick: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(16), dp(12), dp(16))
            isClickable = true
            isFocusable = true
            foreground = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }
        card.addView(ImageView(this).apply {
            setImageResource(icon)
            setBackgroundResource(R.drawable.bg_icon_circle)
            imageTintList = ContextCompat.getColorStateList(context, R.color.brand)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        labels.addView(TextView(this).apply { setTextAppearance(R.style.Text_Vrmdroid_Body); text = title; paint.isFakeBoldText = true })
        labels.addView(caption(desc))
        card.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ContextCompat.getColorStateList(context, R.color.text_tertiary)
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        body.addView(card, spaced(12))
    }

    // Step 2: follow the steps; the status card updates live.
    private fun showGuide(p: Program) {
        program = p
        settings.protocolValue = p.protocol
        ensureTracking()
        body.removeAllViews()

        val steps = when (p) {
            Program.VSEEFACE -> listOf(R.string.connect_vsf_1, R.string.connect_vsf_2, R.string.connect_vsf_3)
            Program.IPHONE_APPS -> listOf(R.string.connect_ifm_1, R.string.connect_ifm_2)
            Program.VMC -> listOf(R.string.connect_vmc_1, R.string.connect_vmc_2)
        }
        titleView.text = when (p) {
            Program.VSEEFACE -> getString(R.string.connect_vseeface)
            Program.IPHONE_APPS -> getString(R.string.connect_iphone_apps)
            Program.VMC -> getString(R.string.connect_vmc)
        }
        body.addView(display(getString(R.string.connect_steps_title)))
        steps.forEachIndexed { i, res -> body.addView(step(i + 1, getString(res)), spaced(if (i == 0) 20 else 14)) }

        if (p == Program.VMC) body.addView(pcAddressCard(), spaced(20)) else body.addView(phoneAddressCard(), spaced(20))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card_high)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        val check = ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ContextCompat.getColorStateList(context, R.color.status_ok)
            visibility = View.GONE
        }
        statusCard.addView(spinner, LinearLayout.LayoutParams(dp(24), dp(24)))
        statusCard.addView(check, LinearLayout.LayoutParams(dp(24), dp(24)))
        val statusText = TextView(this).apply { setTextAppearance(R.style.Text_Vrmdroid_Body); setPadding(dp(12), 0, 0, 0) }
        statusCard.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(statusCard, spaced(20))
        body.addView(caption(getString(R.string.connect_trouble)), spaced(12))

        primary.text = getString(R.string.done)
        primary.backgroundTintList = ContextCompat.getColorStateList(this, R.color.brand)
        primary.setOnClickListener { finishFlow() }

        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackingHub.status.collect { s ->
                    val connected = s.pcLink == PcLink.CONNECTED
                    spinner.visibility = if (connected) View.GONE else View.VISIBLE
                    check.visibility = if (connected) View.VISIBLE else View.GONE
                    statusText.text = when {
                        !s.running -> getString(R.string.connect_need_start)
                        connected -> getString(R.string.connect_connected)
                        p == Program.VMC && s.pcLink == PcLink.SENDING -> getString(R.string.connect_vmc_sending)
                        else -> getString(R.string.connect_waiting)
                    }
                }
            }
        }
    }

    private fun phoneAddressCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(20), dp(16), dp(12), dp(16))
        }
        card.addView(caption(getString(R.string.status_phone_address)))
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val address = StatusText.phoneAddress() ?: "-"
        line.addView(TextView(this).apply {
            text = address
            textSize = 30f
            setTextColor(getColor(R.color.text_primary))
            paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView((layoutInflater.inflate(R.layout.button_quiet, line, false) as MaterialButton).apply {
            text = getString(R.string.copy)
            setIconResource(R.drawable.ic_copy)
            setOnClickListener { StatusSheet.copy(this@ConnectActivity, address) }
        })
        card.addView(line)
        return card
    }

    private fun pcAddressCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        card.addView(caption(getString(R.string.connect_pc_address)))
        card.addView(EditText(this).apply {
            setText(settings.targetHost)
            hint = "192.168.0.10"
            textSize = 24f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { settings.targetHost = s.toString().trim() }
            })
        })
        card.addView(caption(getString(R.string.connect_pc_address_help)))
        return card
    }

    private fun step(n: Int, text: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        row.addView(TextView(this).apply {
            this.text = n.toString()
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.brand))
            paint.isFakeBoldText = true
            setBackgroundResource(R.drawable.bg_icon_circle)
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        row.addView(TextView(this).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Body)
            this.text = text
            setPadding(dp(12), dp(2), 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun display(text: String) = TextView(this).apply { setTextAppearance(R.style.Text_Vrmdroid_Display); this.text = text }
    private fun caption(text: String) = TextView(this).apply { setTextAppearance(R.style.Text_Vrmdroid_Caption); this.text = text }
    private fun spaced(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    /** The PC can only connect while tracking runs, so start it if we're allowed to. */
    private fun ensureTracking() {
        if (TrackingHub.status.value.running) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            TrackingService.start(this)
        }
    }

    private fun finishFlow() {
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        fun intent(context: Context) = Intent(context, ConnectActivity::class.java)
    }
}
