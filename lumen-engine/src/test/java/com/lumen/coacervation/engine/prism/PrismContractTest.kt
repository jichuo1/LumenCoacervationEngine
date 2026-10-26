package com.lumen.coacervation.engine.prism

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for the Prism material: platform types stay in *Api33 classes, the draw path allocates nothing,
 * the adapted shader keeps its upstream attribution, and existing materials keep their chains.
 */
class PrismContractTest {
    private val drawable = SourceContract.read("prism/PrismGlassDrawable.kt")

    @Test fun runtimeShaderAndRenderEffectStayInIsolatedClasses() {
        for (path in listOf("prism/PrismGlassDrawable.kt", "prism/PrismPolicy.kt", "prism/PrismShaders.kt", "host/LumenPrismGlass.kt", "host/LumenPrismOptions.kt")) {
            // Code only: KDoc may name the platform types it explains.
            val source = SourceContract.read(path).replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\n]*"), "")
            assertFalse(path, source.contains("RuntimeShader"))
            assertFalse(path, source.contains("RenderEffect"))
        }
        assertTrue(SourceContract.read("prism/PrismLensApi33.kt").contains("@RequiresApi(33)\ninternal object PrismLensApi33"))
        assertTrue(SourceContract.read("prism/PrismHighlightApi33.kt").contains("@RequiresApi(33)\ninternal class PrismHighlightApi33"))
        assertTrue(drawable.contains("if (Build.VERSION.SDK_INT >= 33 && value.highlight.enabled && value.highlight.falloff > 0f) {"))
    }

    @Test fun drawPathAllocatesNothing() {
        val draw = drawable.after("override fun draw(canvas: Canvas) {").before("override fun onBoundsChange(")
        // Constructor calls only: `setLocalMatrix(` is a setter, `Matrix(` alone would allocate.
        for (token in listOf("Paint", "LinearGradient", "RadialGradient", "Matrix", "Path", "RectF", "FloatArray", "IntArray", "listOf")) {
            assertFalse(token, Regex("(?<![A-Za-z])$token\\(").containsMatchIn(draw))
        }
        // The press glow moves by local matrix, never by a new gradient.
        assertTrue(draw.contains("glowShader.setLocalMatrix(glowMatrix)"))
    }

    @Test fun adaptedShaderKeepsUpstreamAttribution() {
        val shaders = SourceContract.read("prism/PrismShaders.kt")
        assertTrue(shaders.contains("Copyright 2025 Kyant"))
        assertTrue(shaders.contains("65ab177e90e5c1d8c62e70cf7755841982da65f6"))
        assertTrue(shaders.contains("Licensed under the Apache License, Version 2.0"))
        // Fixes over upstream: one radius clamped to the short side, guarded normalisation.
        assertTrue(shaders.contains("float r = min(radius, min(halfSize.x, halfSize.y));"))
        assertFalse(shaders.contains("radiusAt("))
        assertFalse(shaders.contains("normalize("))
    }

    @Test fun lensSamplesInwardAndChainsAfterTheBlur() {
        val lens = SourceContract.read("prism/PrismLensApi33.kt")
        assertTrue(lens.contains("shader.setFloatUniform(\"refractionAmount\", -recipe.refractionAmountDp * density)"))
        assertTrue(lens.contains("RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, \"content\"), input)"))
    }

    @Test fun sessionGlassUsesThePrismChainOnlyWithARecipe() {
        val glass = SourceContract.read("host/SurfaceCaptureApi31.kt").after("class Glass(")
        assertTrue(glass.contains("private val lens: Any? = if (prism == null && !preserveBackdropColors"))
        val prism = glass.after("if (prism != null) {").before("if(options.material==LumenSurfaceMaterial.FROSTED")
        assertTrue(prism.contains("return effect"))
        assertFalse(prism.contains("LUMINANCE_GAIN"))
        assertFalse(prism.contains("fadeEnabled"))
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        // Existing draw-only callers (Fluent acrylic) keep a null recipe.
        assertTrue(session.contains("preserveBackdropColors: Boolean): LumenSurfaceBinding = bindViewDrawOnly(view, surface, source, preserveBackdropColors, null)"))
        assertTrue(session.contains("preserveBackdropColors = entry.preserveBackdropColors, prism = entry.prism).also { entry.gpu = it }"))
    }

    @Test fun shadeRuleMatchesFluentAndMica() {
        assertTrue(drawable.contains("dark = luminance(palette.background) < 0.5"))
        // Review lesson: a wrapper background swallows the glass's invalidations (View.verifyDrawable), so the
        // glass is the background itself and draws its own shadow, outside the outline only.
        val controller = SourceContract.read("host/LumenPrismGlass.kt")
        assertFalse(controller.contains("LumenControlShadow.wrap("))
        assertTrue(controller.contains("replaceBackground(view, drawable)"))
        assertTrue(controller.contains("if (view.background === drawable) replaceBackground(view, original)"))
        val shadow = drawable.after("private fun drawShadow(canvas: Canvas) {").before("private fun drawPress(")
        assertTrue(shadow.contains("clipOutPath(clip)"))
        assertTrue(shadow.contains("Build.VERSION.SDK_INT < 28"))
        // The blur mask is rebuilt only when its radius changes.
        assertTrue(drawable.after("private fun configureShadow(").before("fun setPolicy(").contains("radius != shadowRadiusPx"))
    }

    @Test fun sessionUpdateRebuildsOnce() {
        val controller = SourceContract.read("host/LumenPrismGlass.kt")
        val update = controller.after("public fun update(value: LumenPrismOptions) {").before("public fun updatePalette(")
        assertEquals(1, Regex("binding\\?\\.").findAll(update).count())
        assertTrue(update.contains("binding?.updatePrism(PrismPolicy.recipe(value), surfaceOptions())"))
        assertTrue(controller.contains("refractionStrength = 0f"))
    }

    @Test fun controllerDoesNotOwnScaleOrTouch() {
        val controller = SourceContract.read("host/LumenPrismGlass.kt")
        for (token in listOf("setOnTouchListener", "scaleX", "scaleY", "elevation")) assertFalse(token, controller.contains(token))
    }
}
