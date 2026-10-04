package com.ouor.vrmdroid.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.ouor.vrmdroid.R

/** Small building blocks shared by the programmatically built screens (connect, onboarding). */

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.displayText(text: String) = TextView(this).apply {
    setTextAppearance(R.style.Text_Vrmdroid_Display)
    this.text = text
}

fun Context.captionText(text: String) = TextView(this).apply {
    setTextAppearance(R.style.Text_Vrmdroid_Caption)
    this.text = text
}

/** Full-width layout params with a top margin, for stacking blocks in a vertical column. */
fun Context.stacked(topDp: Int) = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
).apply { topMargin = dp(topDp) }

/** Tappable card: icon in a tinted circle, bold title, one-line explanation, chevron. */
fun Context.optionCard(
    @DrawableRes icon: Int, title: String, description: String,
    verticalPaddingDp: Int = 16, onClick: () -> Unit,
): View {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(16), dp(verticalPaddingDp), dp(12), dp(verticalPaddingDp))
        isClickable = true
        isFocusable = true
        applyRipple()
        setOnClickListener { onClick() }
    }
    card.addView(ImageView(this).apply {
        setImageResource(icon)
        setBackgroundResource(R.drawable.bg_icon_circle)
        imageTintList = ContextCompat.getColorStateList(context, R.color.brand_text)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(44), dp(44)))
    val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
    labels.addView(TextView(this).apply {
        setTextAppearance(R.style.Text_Vrmdroid_Body)
        text = title
        paint.isFakeBoldText = true
    })
    labels.addView(captionText(description))
    card.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    card.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron_right)
        imageTintList = ContextCompat.getColorStateList(context, R.color.text_tertiary)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(20), dp(20)))
    return card
}
