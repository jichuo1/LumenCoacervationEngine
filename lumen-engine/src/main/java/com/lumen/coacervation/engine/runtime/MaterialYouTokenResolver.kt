package com.lumen.coacervation.engine.runtime

import androidx.core.graphics.ColorUtils
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.UiColorTokens
import com.lumen.coacervation.engine.model.UiShapeTokens
import com.lumen.coacervation.engine.model.UiTokens

/**
 * 把宿主调色板一一包装为语义令牌。
 *
 * 来源工程从资源读取两个文字色；独立引擎改为由 [LumenPalette.textPrimary] / [LumenPalette.textSecondary]
 * 提供，取值与映射关系不变。
 */
internal object MaterialYouTokenResolver {

    fun resolve(palette: LumenPalette): UiTokens {
        val textPrimary = palette.textPrimary
        val textSecondary = palette.textSecondary
        val surfaceIsDark = ColorUtils.calculateLuminance(palette.surface) < 0.5
        return UiTokens(
            colors = UiColorTokens(
                background = palette.background,
                surface = palette.surface,
                surfaceVariant = palette.surfaceVariant,
                primary = palette.primary,
                secondary = palette.secondary,
                tertiary = palette.tertiary,
                onAccent = palette.onPrimary,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                textDisabled = ColorUtils.setAlphaComponent(textSecondary, 0x61),
                outline = ColorUtils.setAlphaComponent(textSecondary, 0x38),
                divider = ColorUtils.setAlphaComponent(textSecondary, 0x20),
                ripple = ColorUtils.setAlphaComponent(textPrimary, 0x30),
                warning = if (surfaceIsDark) 0xFFFFB95C.toInt() else 0xFF7A4F00.toInt(),
                error = if (surfaceIsDark) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt(),
                scrim = 0x52000000,
                systemBarBackground = palette.background,
                useLightSystemBarIcons = ColorUtils.calculateLuminance(palette.background) < 0.5
            ),
            shapes = UiShapeTokens(
                cardRadiusDp = 15f,
                modalRadiusDp = 28f,
                controlRadiusDp = 14f,
                chipRadiusDp = 20f,
                filledButtonRadiusDp = 20f,
                textButtonRippleRadiusDp = 14f,
                collapsedMotionRadiusDp = 15f
            )
        )
    }
}
