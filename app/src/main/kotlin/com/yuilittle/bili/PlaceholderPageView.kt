package com.yuilittle.bili

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Lightweight static root page; it starts no timers, animations or background work. */
class PlaceholderPageView(context: Context, title: String, message: String) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setGravity(Gravity.CENTER)
        setBackgroundColor(COLOR_BACKGROUND)
        setPadding(dp(34), 0, dp(34), dp(70))
        addView(TextView(context).apply {
            text = title
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(COLOR_INK)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = message
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(0, dp(12), 0, 0)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
