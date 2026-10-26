package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.glow.FilteredLayerRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CI lesson (1.4.4 review, API 31 emulator SE1A.220826.008): Android 12 HWUI kept compositing the first filtered
 * image of a blurred glass node after its content changed, so a scrolled source still showed scroll position 0 on
 * the GPU path. 12L and later key that cache on the layer generation. On API 31 only, every filtered node swaps
 * between two pixel-identical filters when its content may have changed; on other API levels the new branches are inert.
 * The pixel-level checks are sample/src/androidTest/.../ScrollSourceSamplingTest.
 */
class FilteredLayerRefreshContractTest {
    @Test fun onlyAndroid12NeedsTheSwap() {
        assertTrue(FilteredLayerRefresh.required(31))
        for (sdk in intArrayOf(27, 29, 30, 32, 33, 34, 36, 37)) assertFalse("sdk $sdk", FilteredLayerRefresh.required(sdk))
    }

    @Test fun glassSwapsOnEveryReRecordWithoutBuildingEffects() {
        val glass = SourceContract.read("glow/GlowChromeGlassApi31.kt")
        assertTrue(glass.contains("if (!updateEffect(width, height, radiusPx, opticalIntensity, stretchDirY, variant)) swapEquivalentEffect()"))
        val update = glass.after("private fun updateEffect(").before("private fun swapEquivalentEffect()")
        assertTrue(update.contains("val refresh = FilteredLayerRefresh.required()"))
        // A cached material-opacity variant reuses its own alias; a new chain gets a new pass-through alias.
        assertTrue(update.contains("alias = if (refresh) variantAliases?.get(slot) ?: RenderEffect.createOffsetEffect(0f, 0f, built) else null"))
        assertTrue(update.contains("effect = if (refresh) built else null"))
        val swap = glass.after("private fun swapEquivalentEffect()").before("fun releaseDisplayList()")
        assertFalse(swap.contains("RenderEffect."))
        assertFalse(swap.contains("effectBuilt"))
        assertTrue(swap.contains("val next = (if (aliasActive) effect else alias) ?: return"))
    }

    @Test fun liquidBlurSwapsOnlyAfterTheLastSwapWasDrawn() {
        val blur = SourceContract.read("liquid/LiquidBlurBackendApi31.kt")
        val bind = blur.after("override fun bindBackdrop(").before("override fun drawBackdrop(")
        assertTrue(bind.contains("aliasEffect = if (FilteredLayerRefresh.required()) RenderEffect.createOffsetEffect(0f, 0f, effect) else null"))
        val swap = bind.after("} else if (drawnSinceSwap) {")
        assertFalse(swap.before("renderNode = node").contains("RenderEffect."))
        assertTrue(swap.contains("drawnSinceSwap = false"))
        assertTrue(blur.after("override fun drawBackdrop(").before("node.alpha").contains("drawnSinceSwap = true"))
    }

    @Test fun sessionRedrawsGpuTargetsAfterContentOnlyRecordsOnAndroid12() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val record = session.after("if (current.record(recorder, options, available)) {").before("else { budgetRejected = true")
        val refresh = record.after("} else if (FilteredLayerRefresh.required()) {")
        assertTrue(refresh.contains("entry.invalidateFrame()"))
        // Targets inside a surface-excluding source must not dirty it again on every record.
        assertTrue(refresh.contains("!isWithin(entry.host.get(), content)) entry.invalidateFrame()"))
        assertTrue(refresh.contains("for (i in 0 until entries.size)"))
        // The geometry branch is unchanged on every API level.
        assertTrue(record.contains("current.recordedHeight, current.scaleX, current.scaleY)) {\n"))
    }

    @Test fun floatingChromeRedrawsBarsWhenContentChangesOnAndroid12() {
        val target = SourceContract.read("glow/GlowBackdropTarget.kt")
        assertEquals(2, Regex("onContentInvalidated\\?\\.run\\(\\)").findAll(target).count())
        assertTrue(target.after("override fun onDescendantInvalidated(").before("override fun dispatchDraw(").contains("onContentInvalidated?.run()"))
        val chrome = SourceContract.read("glow/GlowFloatingChrome.kt")
        assertTrue(chrome.contains("target.onContentInvalidated = contentInvalidated.takeIf { FilteredLayerRefresh.required() }"))
        // Cleared on disable and on dispose, before content capture stops.
        assertEquals(2, Regex("target\\.onContentInvalidated = null\\s+target\\.contentCaptureEnabled = false").findAll(chrome).count())
        val runnable = chrome.after("private val contentInvalidated = Runnable {").before("private val hostLayoutListener")
        assertFalse(runnable.contains(" in surfaces"))
        assertTrue(runnable.contains("for (i in 0 until surfaces.size)"))
    }
}
