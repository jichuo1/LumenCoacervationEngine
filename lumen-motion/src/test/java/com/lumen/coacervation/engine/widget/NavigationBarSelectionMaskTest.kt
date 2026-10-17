package com.lumen.coacervation.engine.widget

import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationBarSelectionMaskTest {
    private val palette = LumenPalette.neutral(false)

    @Test fun strengthMapsToAlphaAndKeepsTheBaseRgb() {
        val base = palette.textPrimary
        val rgb = base and 0x00FFFFFF
        assertEquals(rgb, NavigationBarSelectionMask.color(palette, 0f) and 0x00FFFFFF)
        assertEquals(0, (NavigationBarSelectionMask.color(palette, 0f) ushr 24) and 0xFF)
        assertEquals(128, (NavigationBarSelectionMask.color(palette, .5f) ushr 24) and 0xFF)
        assertEquals(255, (NavigationBarSelectionMask.color(palette, 1f) ushr 24) and 0xFF)
        // 越界收进 0..1，不抛异常（滑块的连续输入直接可用）。
        assertEquals(255, (NavigationBarSelectionMask.color(palette, 2f) ushr 24) and 0xFF)
        assertEquals(0, (NavigationBarSelectionMask.color(palette, -1f) ushr 24) and 0xFF)
    }
}
