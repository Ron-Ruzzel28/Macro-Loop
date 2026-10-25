package com.macroloop.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

/** Small helpers for the overlay views (plain Android views, not Compose). */
object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    /** A button at least 48 dp tall with a 16 sp label. */
    fun button(
        ctx: Context,
        label: String,
        fill: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = label
        textSize = 16f
        setTextColor(textColor)
        gravity = Gravity.CENTER
        minHeight = dp(ctx, 48)
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        background = rounded(fill, dp(ctx, 12).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
    }
}
