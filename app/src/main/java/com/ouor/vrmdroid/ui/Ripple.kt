package com.ouor.vrmdroid.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View

/** The theme's ripple (selectableItemBackground), clipped to the view's rounded background. */
fun View.applyRipple() {
    foreground = themeRipple(context)
    clipToOutline = true
}

fun themeRipple(context: Context): Drawable? {
    val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
    return try { a.getDrawable(0) } finally { a.recycle() }
}
