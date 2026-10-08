package com.ouor.vrmdroid.ui

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.ouor.vrmdroid.R
import com.ouor.vrmdroid.settings.AppSettings

/**
 * "표정 조절": the expression controls in a sheet over the live avatar, so every change is seen
 * immediately. Values are written to preferences, which the tracker reads every frame.
 *
 * The sheet opens to just the sliders and leaves the stage undimmed, so the avatar's face stays
 * in view above it; the switches below are a drag away.
 */
object AdjustSheet {

    fun show(context: Context, onFacePreviewChanged: (Boolean) -> Unit) {
        val settings = AppSettings(context)
        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        lateinit var sliders: View
        fun build() {
            content.removeAllViews()
            content.addView(TextView(context).apply {
                setTextAppearance(R.style.Text_Vrmdroid_Title)
                text = context.getString(R.string.adjust_title)
                setPadding(dp(8), 0, dp(8), 0)
            })
            content.addView(TextView(context).apply {
                setTextAppearance(R.style.Text_Vrmdroid_Caption)
                text = context.getString(R.string.adjust_caption)
                setPadding(dp(8), dp(4), dp(8), dp(12))
            })

            val rows = Rows(context, settings.prefs)
            val card = rows.card(content)
            sliders = card
            rows.slider(card, AppSettings.KEY_BLINK_SENSITIVITY, context.getString(R.string.adjust_blink),
                context.getString(R.string.less_sensitive), context.getString(R.string.more_sensitive),
                50, 200, AppSettings.DEFAULT_SENSITIVITY)
            rows.slider(card, AppSettings.KEY_MOUTH_SENSITIVITY, context.getString(R.string.adjust_mouth),
                context.getString(R.string.less_sensitive), context.getString(R.string.more_sensitive),
                50, 200, AppSettings.DEFAULT_SENSITIVITY)
            rows.slider(card, AppSettings.KEY_SMOOTHING, context.getString(R.string.adjust_smooth),
                context.getString(R.string.faster), context.getString(R.string.smoother),
                0, 100, AppSettings.DEFAULT_SMOOTHING)

            content.addView(View(context), LinearLayout.LayoutParams(1, dp(12)))
            val card2 = rows.card(content)
            rows.switch(card2, AppSettings.KEY_MIRROR, context.getString(R.string.adjust_mirror),
                context.getString(R.string.adjust_mirror_desc), true)
            rows.divider(card2)
            rows.switch(card2, AppSettings.KEY_LINK_EYES, context.getString(R.string.adjust_link_eyes),
                context.getString(R.string.adjust_link_eyes_desc), false)
            rows.divider(card2)
            rows.switch(card2, AppSettings.KEY_PREVIEW_OVERLAY, context.getString(R.string.adjust_face_dots),
                context.getString(R.string.adjust_face_dots_desc), false, onFacePreviewChanged)

            content.addView((android.view.LayoutInflater.from(context).inflate(R.layout.button_text, null) as MaterialButton).apply {
                text = context.getString(R.string.reset_expression)
                setTextColor(context.getColor(R.color.text_secondary))
                setOnClickListener {
                    settings.resetExpression()
                    onFacePreviewChanged(settings.previewEnabled)
                    Toast.makeText(context, R.string.reset_expression_done, Toast.LENGTH_SHORT).show()
                    build()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        build()

        dialog.setContentView(ScrollView(context).apply { addView(content) })
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Collapsed: the sliders plus the top edge of the switches card, a hint there's more.
        // Landscape: over the control panel on the end side, beside the avatar instead of on it.
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            dialog.behavior.maxWidth = dp(LANDSCAPE_WIDTH_DP)
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.updateLayoutParams<CoordinatorLayout.LayoutParams> { gravity = Gravity.END }
        }
        content.doOnLayout {
            dialog.behavior.peekHeight = sliders.bottom + dp(PEEK_PAST_SLIDERS_DP)
            dialog.behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        dialog.show()
    }

    private const val PEEK_PAST_SLIDERS_DP = 40
    /** Same as MainActivity's landscape control panel. */
    private const val LANDSCAPE_WIDTH_DP = 440
}
