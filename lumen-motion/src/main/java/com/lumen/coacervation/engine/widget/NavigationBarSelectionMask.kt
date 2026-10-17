package com.lumen.coacervation.engine.widget

import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.roundToInt

/**
 * 底栏选中滑块的对比遮罩（适配标准 §12.4）。
 *
 * 策略保持不变：浅色主题用深色（[LumenPalette.textPrimary]）、深色主题用浅色；
 * [strength] 0..1 无极调节遮罩 alpha，0 = 不显示选中底，1 = 最重。
 * 返回值直接交给 `LumenActivityDelegate.chromeOverlayBackground(color, radius, selected = true)`。
 */
public object NavigationBarSelectionMask {
    @JvmStatic
    public fun color(palette: LumenPalette, strength: Float): Int {
        val alpha = (strength.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return (palette.textPrimary and 0x00FFFFFF) or (alpha shl 24)
    }
}
