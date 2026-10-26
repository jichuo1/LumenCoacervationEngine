package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Write-only guarantees of material opacity (MATERIAL_OPACITY.md §9.2) that the pure policy test cannot see. */
class MaterialOpacityContractTest {
    private val forbidden = listOf("RenderEffect.create", "RuntimeShader(", "Bitmap.createBitmap", "saveLayer",
        "Paint(", "Matrix(", "Path()", "LinearGradient(", "BlurMaskFilter(", "updatePrism", "rebuild()", "configure(")

    private fun assertCheap(name: String, body: String) {
        for (word in forbidden) assertFalse("$name: material opacity path contains $word", body.contains(word))
    }

    @Test fun settersOnlyStoreFloatsAndInvalidate() {
        val fluent = SourceContract.read("host/LumenFluentControl.kt")
        assertCheap("fluent", fluent.after("public fun setMaterialOpacity(value: Float, legibility").before("override fun materialOpacity"))
        val prism = SourceContract.read("host/LumenPrismGlass.kt")
        assertCheap("prism", prism.after("public fun setMaterialOpacity(value: Float, legibility").before("override fun materialOpacity"))
        val mica = SourceContract.read("host/LumenMicaBackdrop.kt")
        assertCheap("mica", mica.after("override fun setMaterialOpacity(value: Float)").before("override fun materialOpacity"))
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        assertCheap("session weights", session.after("fun applyMaterialWeights(").before("fun resolveMaterialOpacity()"))
        assertCheap("session resolve", session.after("fun resolveMaterialOpacity()").before("val drawable = SurfaceDrawable(this)"))
        val fluentDrawable = SourceContract.read("fluent/FluentControlDrawable.kt")
        assertCheap("fluent drawable", fluentDrawable.after("fun setMaterialOpacity(").before("fun release()"))
        val prismDrawable = SourceContract.read("prism/PrismGlassDrawable.kt")
        assertCheap("prism drawable", prismDrawable.after("fun setMaterialOpacity(").before("fun release()"))
    }

    @Test fun compositeControlsPassTheirAlphaAndWeightToTheBackdrop() {
        for (path in listOf("fluent/FluentControlDrawable.kt", "prism/PrismGlassDrawable.kt")) {
            val draw = SourceContract.read(path).after("override fun draw(canvas: Canvas)")
            assertTrue(path, draw.contains("binding.draw(canvas, alphaValue / 255f)"))
            assertTrue(path, draw.contains("binding.setMaterialWeights(backdrop, 1f, 1f, 1f)"))
            // A clear control leaves its binding unsampled instead of drawing a zero-alpha backdrop.
            assertTrue(path, draw.contains("MaterialOpacityPolicy.needsBackdrop(material)"))
        }
    }

    @Test fun clearSurfacesStopSampling() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val preDraw = session.after("override fun onPreDraw(): Boolean").before("private var trailingPosted")
        assertTrue(preDraw.contains("if (!entry.needsBackdrop()) continue"))
        val weights = session.after("fun applyMaterialWeights(").before("fun resolveMaterialOpacity()")
        assertTrue(weights.contains("if (wasSampling && !needsBackdrop()) group?.sampler?.unregister(target)"))
        val draw = session.after("override fun draw(canvas: Canvas) {\n            val host = entry.host.get()")
        assertTrue(draw.contains("c.sampling.backend != LumenSurfaceBackend.STATIC && entry.needsBackdrop()) {"))
        // An intentionally unsampled surface is not reported as a pending frame.
        assertTrue(draw.contains("c.sampling.backend != LumenSurfaceBackend.STATIC && !clearGlass) reason = LumenSurfaceFailure.FRAME_PENDING"))
    }

    @Test fun micaStaysOpaqueAtAnyMaterialOpacity() {
        val mica = SourceContract.read("fluent/MicaBackdropDrawable.kt")
        assertTrue(mica.contains("override fun getOpacity(): Int = if (alphaValue == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT"))
        val draw = mica.after("override fun draw(canvas: Canvas)").before("override fun onBoundsChange")
        // The opaque base ignores the image weight; only the wallpaper material is multiplied.
        assertTrue(draw.contains("fill.color = withAlpha(recipe.fallback, alphaValue)"))
        assertTrue(draw.contains("(weight * alphaValue * imageWeight).roundToInt()"))
    }

    @Test fun shadowOpacityStaysOutOfTheTextureCache() {
        val shadow = SourceContract.read("material/ControlShadowDrawable.kt")
        val draw = shadow.after("override fun draw(canvas: Canvas)").before("private fun siblingShadowOverlaps")
        assertTrue(draw.contains("gain * materialGain"))
        assertFalse(shadow.after("var materialGain").before("private val xStops").contains("ControlShadowTextureCache"))
    }
}
