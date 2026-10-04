package com.ouor.vrmdroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.ouor.vrmdroid.R
import com.ouor.vrmdroid.service.TrackingHub
import com.ouor.vrmdroid.service.TrackingStatus
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Opened from the status pill: plain-language state, with the technical numbers tucked under a
 * small "자세히" line, and the way into the PC connection guide.
 */
object StatusSheet {

    fun show(activity: AppCompatActivity) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val dialog = BottomSheetDialog(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        content.addView(TextView(activity).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Title)
            text = activity.getString(R.string.status_title)
        })

        fun row(label: String): Pair<android.view.View, TextView> {
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, 0)
            }
            line.addView(TextView(activity).apply {
                setTextAppearance(R.style.Text_Vrmdroid_Caption)
                text = label
            }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            val dot = android.view.View(activity).apply { setBackgroundResource(R.drawable.bg_dot) }
            line.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            val value = TextView(activity).apply { setTextAppearance(R.style.Text_Vrmdroid_Body) }
            line.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(line)
            return dot to value
        }

        val (trackDot, trackText) = row(activity.getString(R.string.status_tracking))
        val (pcDot, pcText) = row(activity.getString(R.string.status_pc))

        // Phone address with a copy button: what the PC app asks for.
        val addressLine = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        addressLine.addView(TextView(activity).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Caption)
            text = activity.getString(R.string.status_phone_address)
        }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        val address = StatusText.phoneAddress() ?: "-"
        addressLine.addView(TextView(activity).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Body)
            text = address
            paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addressLine.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.ic_copy)
            iconTint = ColorStateList.valueOf(activity.getColor(R.color.text_secondary))
            contentDescription = activity.getString(R.string.copy)
            setOnClickListener { copy(activity, address) }
        })
        content.addView(addressLine)

        val details = TextView(activity).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Caption)
            setTextColor(activity.getColor(R.color.text_secondary))
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(details)

        val connect = MaterialButton(activity).apply {
            text = activity.getString(R.string.status_connect_cta)
            setIconResource(R.drawable.ic_computer)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setOnClickListener {
                dialog.dismiss()
                activity.startActivity(Intent(activity, ConnectActivity::class.java))
            }
        }
        content.addView(connect, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })

        fun render(s: TrackingStatus) {
            val t = StatusText.tracking(activity, s)
            trackText.text = t.text
            trackDot.backgroundTintList = ContextCompat.getColorStateList(activity, t.color)
            val p = StatusText.pc(activity, s)
            pcText.text = p.text
            pcDot.backgroundTintList = ContextCompat.getColorStateList(activity, p.color)
            details.text = buildString {
                append(activity.getString(R.string.status_details)).append(": ")
                if (s.running) append(s.trackerName).append(String.format(Locale.US, " · %.0f fps · %d ms", s.fps, s.inferenceMs))
                else append(activity.getString(R.string.status_off))
                s.error?.let { append("\n").append(it) }
            }
        }

        val job = activity.lifecycleScope.launch { TrackingHub.status.collect(::render) }
        dialog.setOnDismissListener { job.cancel() }
        dialog.setContentView(content)
        dialog.show()
    }

    fun copy(activity: AppCompatActivity, text: String) {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("ip", text))
        Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
