package com.macroloop.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

/** Small helpers for the overlay views (plain Android views, not Compose). */
object Ui {
    /** How big the overlay looks: 0.75 Small, 0.85 Normal (default), 1.0 Large (the first look). */
    @Volatile var scale: Float = 0.85f
        private set

    const val PREF_SIZE = "ui_size"

    fun sizeIndex(ctx: Context): Int = ctx.getSharedPreferences(MacroService.PREFS, Context.MODE_PRIVATE).getInt(PREF_SIZE, 1).coerceIn(0, 2)

    fun loadScale(ctx: Context) {
        scale = when (sizeIndex(ctx)) { 0 -> 0.75f; 2 -> 1f; else -> 0.85f }
    }

    fun setSize(ctx: Context, index: Int) {
        ctx.getSharedPreferences(MacroService.PREFS, Context.MODE_PRIVATE).edit().putInt(PREF_SIZE, index.coerceIn(0, 2)).apply()
        loadScale(ctx)
    }

    /** Sizes follow the Size setting. */
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density * scale).toInt()

    /** Same on every Size setting: things drawn on the real screen, and the least size of a touch area. */
    fun dpFixed(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** Text sizes follow the Size setting too. */
    fun sp(v: Float): Float = v * scale

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
        textSize = sp(15f)
        setTextColor(textColor)
        gravity = Gravity.CENTER
        minHeight = maxOf(dp(ctx, 44), dpFixed(ctx, 40))
        setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
        background = rounded(fill, dp(ctx, 10).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** The full screen size in pixels. Android 11 has a direct call for it; Android 10 needs the older one. */
    @Suppress("DEPRECATION")
    fun screenBounds(wm: android.view.WindowManager): android.graphics.Rect =
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds
        else android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }.let { android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels) }
}
