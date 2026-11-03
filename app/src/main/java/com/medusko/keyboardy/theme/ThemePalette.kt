package com.medusko.keyboardy.theme

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList

data class ThemePalette(
    val isDark: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val border: Int,
    val accentBlue: Int,
    val accentBluePressed: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val keyboardTrayBg: Int,
    val keyBg: Int,
    val keyText: Int,
    val chipActiveBg: Int,
    val chipActiveText: Int,
    val chipInactiveBg: Int,
    val chipInactiveText: Int,
    val successGreen: Int
) {
    fun createRoundedRect(
        fillColor: Int,
        cornerRadiusPx: Float,
        strokeWidthPx: Int = 0,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = cornerRadiusPx
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    fun createKeyDrawable(
        isAccent: Boolean,
        cornerRadiusPx: Float,
        strokeWidthPx: Int
    ): RippleDrawable {
        val baseColor = if (isAccent) accentBlue else keyBg
        val borderColor = if (isAccent) accentBluePressed else border
        val rippleColor = if (isDark) Color.parseColor("#4DFFFFFF") else Color.parseColor("#332E6BD9")
        val content = createRoundedRect(
            fillColor = baseColor,
            cornerRadiusPx = cornerRadiusPx,
            strokeWidthPx = strokeWidthPx,
            strokeColor = borderColor
        )
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)
    }

    companion object {
        /**
         * Blue & Black Dark Palette matching Lagatron's DarkBackground (#101216) with crisp White text (#FFFFFF)
         * and Cobalt Blue (#2E6BD9) accents.
         */
        val DARK = ThemePalette(
            isDark = true,
            background = Color.parseColor("#101216"),
            surface = Color.parseColor("#191C23"),
            surfaceVariant = Color.parseColor("#222631"),
            border = Color.parseColor("#2E3342"),
            accentBlue = Color.parseColor("#2E6BD9"),
            accentBluePressed = Color.parseColor("#4782EE"),
            textPrimary = Color.parseColor("#FFFFFF"),
            textSecondary = Color.parseColor("#B8C2D4"),
            keyboardTrayBg = Color.parseColor("#0B0D11"),
            keyBg = Color.parseColor("#1C212C"),
            keyText = Color.parseColor("#FFFFFF"),
            chipActiveBg = Color.parseColor("#2E6BD9"),
            chipActiveText = Color.parseColor("#FFFFFF"),
            chipInactiveBg = Color.parseColor("#191C23"),
            chipInactiveText = Color.parseColor("#D8E0EE"),
            successGreen = Color.parseColor("#00C853")
        )

        /**
         * Warm Cream / Linen Light Palette matching Lagatron's LightBackground (#F6F1E8) and AccentBlue (#2E6BD9).
         */
        val LIGHT = ThemePalette(
            isDark = false,
            background = Color.parseColor("#F6F1E8"),
            surface = Color.parseColor("#FFFDF9"),
            surfaceVariant = Color.parseColor("#ECE5D9"),
            border = Color.parseColor("#DCD4C6"),
            accentBlue = Color.parseColor("#2E6BD9"),
            accentBluePressed = Color.parseColor("#1E54B7"),
            textPrimary = Color.parseColor("#1E1A16"),
            textSecondary = Color.parseColor("#6B6358"),
            keyboardTrayBg = Color.parseColor("#E6DEC8"),
            keyBg = Color.parseColor("#FFFDF9"),
            keyText = Color.parseColor("#1E1A16"),
            chipActiveBg = Color.parseColor("#2E6BD9"),
            chipActiveText = Color.parseColor("#FFFFFF"),
            chipInactiveBg = Color.parseColor("#FFFDF9"),
            chipInactiveText = Color.parseColor("#4E473E"),
            successGreen = Color.parseColor("#00A844")
        )

        fun forMode(isDarkMode: Boolean): ThemePalette = if (isDarkMode) DARK else LIGHT
    }
}
