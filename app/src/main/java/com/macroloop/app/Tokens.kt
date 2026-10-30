package com.macroloop.app

import androidx.compose.ui.graphics.Color

/** Colors from design.md. Dark theme only for now. */
object Tokens {
    val background = Color(0xFF0F1115)
    val surface = Color(0xFF1A1D24)
    val surfaceHigh = Color(0xFF242833)
    val text = Color(0xFFF2F4F8)
    val textMuted = Color(0xFFA6ADBB)
    val primary = Color(0xFF38BDF8)
    val onPrimary = Color(0xFF04202E)
    val success = Color(0xFF4ADE80)
    val warning = Color(0xFFFBBF24)
    val danger = Color(0xFFF87171)
    val onDanger = Color(0xFF2B0A0A)
}

/** Same colors as Android ints, for the overlay views. */
object TokenInts {
    const val surface = 0xFF1A1D24.toInt()
    const val surfaceHigh = 0xFF242833.toInt()
    const val text = 0xFFF2F4F8.toInt()
    const val textMuted = 0xFFA6ADBB.toInt()
    const val primary = 0xFF38BDF8.toInt()
    const val onPrimary = 0xFF04202E.toInt()
    const val success = 0xFF4ADE80.toInt()
    const val warning = 0xFFFBBF24.toInt()
    const val danger = 0xFFF87171.toInt()
    const val onDanger = 0xFF2B0A0A.toInt()
    const val scrim = 0x99000000.toInt()
}
