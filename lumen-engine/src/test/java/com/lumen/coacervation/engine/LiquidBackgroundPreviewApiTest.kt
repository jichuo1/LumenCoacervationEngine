package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidBackgroundPreviewApiTest {
    @Test fun publicPreviewDecodingUsesEngineBudgetAndExplicitPalette() {
        val store = SourceContract.read("background/LiquidBackgroundStore.kt")
        val preview = store.after("fun decodePreview(").before("internal fun decodeBackdrop(")
        assertTrue(store.contains("@WorkerThread\n    fun decodePreview("))
        assertTrue(preview.contains("Looper.myLooper() !== Looper.getMainLooper()"))
        assertTrue(preview.contains("viewWidth <= 0 || viewHeight <= 0"))
        assertTrue(preview.contains("resolvePreview(viewWidth, viewHeight)"))
        assertTrue(preview.contains("decodeBackdrop(context, config, target.width, target.height, palette.background"))
        assertTrue(preview.contains("ColorUtils.calculateLuminance(palette.surface)"))
        assertTrue(preview.contains("}.getOrNull()"))
        assertFalse(preview.contains("getSharedPreferences"))
        assertFalse(preview.contains("Executors"))
        assertFalse(preview.contains("resources"))
    }
}
