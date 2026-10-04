package com.ouor.vrmdroid.ui

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.ouor.vrmdroid.R

/**
 * Builders for the app's card-and-row UI (quick sheet, settings, guides), bound directly to
 * SharedPreferences so every change applies immediately.
 */
class Rows(private val context: Context, private val prefs: SharedPreferences) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun section(parent: ViewGroup, title: String) {
        parent.addView(TextView(context).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Section)
            text = title
            setPadding(dp(8), dp(24), dp(8), dp(8))
        })
    }

    fun card(parent: ViewGroup): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(4))
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Title + optional one-line explanation, laid out as a column. */
    private fun labels(title: String, description: String?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Body)
            text = title
        })
        if (!description.isNullOrEmpty()) {
            addView(TextView(context).apply {
                setTextAppearance(R.style.Text_Vrmdroid_Caption)
                text = description
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    fun switch(
        card: ViewGroup, key: String, title: String, description: String? = null,
        default: Boolean, onChange: ((Boolean) -> Unit)? = null,
    ): MaterialSwitch {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        row.addView(labels(title, description), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = MaterialSwitch(context).apply {
            isChecked = prefs.getBoolean(key, default)
            // The whole row is the control: one focus stop, one label, one ripple.
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                onChange?.invoke(checked)
                row.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
            }
        }
        row.addView(toggle)
        row.isClickable = true
        row.isFocusable = true
        row.foreground = themeRipple(context)
        row.setOnClickListener { toggle.toggle() }
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true
                info.isChecked = toggle.isChecked
            }
        })
        card.addView(row)
        return toggle
    }

    /** Slider whose ends are labelled with meaning ("덜 민감 ↔ 더 민감") instead of numbers. */
    fun slider(
        card: ViewGroup, key: String, title: String, left: String, right: String,
        min: Int, max: Int, default: Int, description: String? = null,
    ): Slider {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        column.addView(labels(title, description))
        val slider = Slider(context).apply {
            valueFrom = min.toFloat()
            valueTo = max.toFloat()
            stepSize = 5f
            value = prefs.getInt(key, default).coerceIn(min, max).let { it - (it - min) % 5 }.toFloat()
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            contentDescription = "$title, $left ↔ $right"
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) prefs.edit().putInt(key, v.toInt()).apply()
            }
        }
        column.addView(slider)
        val ends = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        ends.addView(TextView(context).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Caption); text = left
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ends.addView(TextView(context).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Caption); text = right; gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(ends)
        card.addView(column)
        return slider
    }

    /** Tappable row with a chevron; [value] shows the current state on the right. */
    fun link(card: ViewGroup, title: String, description: String? = null, value: String? = null, onClick: () -> Unit): TextView {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
            isClickable = true
            isFocusable = true
            foreground = themeRipple(context)
            setOnClickListener { onClick() }
        }
        row.addView(labels(title, description), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = TextView(context).apply {
            setTextAppearance(R.style.Text_Vrmdroid_Caption)
            text = value.orEmpty()
            setPadding(dp(8), 0, dp(4), 0)
        }
        row.addView(valueView)
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ContextCompat.getColorStateList(context, R.color.text_tertiary)
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        card.addView(row)
        return valueView
    }

    /** Text/number setting edited in a small dialog. */
    fun textField(
        card: ViewGroup, key: String, title: String, description: String? = null,
        default: String, hint: String = "", numeric: Boolean = false,
        display: (String) -> String = { it.ifEmpty { "-" } },
    ) {
        lateinit var valueView: TextView
        valueView = link(card, title, description, display(prefs.getString(key, default) ?: default)) {
            val input = EditText(context).apply {
                setText(prefs.getString(key, default))
                this.hint = hint
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSelection(text.length)
            }
            val box = FrameLayout(context).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val v = input.text.toString().trim()
                    prefs.edit().putString(key, v).apply()
                    valueView.text = display(v)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * Pick one of several options. Each option is a row with a bold title and, when present, a
     * small grey explanation, laid out compactly so a handful of options fit without scrolling.
     */
    fun choice(
        card: ViewGroup, key: String, title: String, options: List<Option>, default: String,
        description: String? = null, onChange: ((String) -> Unit)? = null,
    ) {
        fun labelOf(v: String?) = options.firstOrNull { it.value == v }?.title ?: options.first().title
        lateinit var valueView: TextView
        valueView = link(card, title, description, labelOf(prefs.getString(key, default))) {
            val current = prefs.getString(key, default)
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(16), dp(4))
            }
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(title)
                // Scrolls only when it must (large font sizes, small screens).
                .setView(android.widget.ScrollView(context).apply { addView(list) })
                .setNegativeButton(R.string.cancel, null)
                .create()
            for (option in options) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    isClickable = true
                    isFocusable = true
                    foreground = themeRipple(context)
                }
                val radio = com.google.android.material.radiobutton.MaterialRadioButton(context).apply {
                    isChecked = option.value == current
                    isClickable = false
                    isFocusable = false
                }
                row.addView(radio)
                val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, 0, 0) }
                labels.addView(TextView(context).apply {
                    setTextAppearance(R.style.Text_Vrmdroid_Body)
                    text = option.title
                    paint.isFakeBoldText = true
                })
                if (option.description.isNotEmpty()) {
                    labels.addView(TextView(context).apply {
                        setTextAppearance(R.style.Text_Vrmdroid_Caption)
                        text = option.description
                        setPadding(0, dp(2), 0, 0)
                    })
                }
                row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.setOnClickListener {
                    prefs.edit().putString(key, option.value).apply()
                    valueView.text = labelOf(option.value)
                    onChange?.invoke(option.value)
                    dialog.dismiss()
                }
                list.addView(row)
            }
            dialog.show()
        }
    }

    fun divider(card: ViewGroup) {
        card.addView(View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.outline))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(16); marginEnd = dp(16)
        })
    }

    data class Option(val value: String, val title: String, val description: String = "")
}
