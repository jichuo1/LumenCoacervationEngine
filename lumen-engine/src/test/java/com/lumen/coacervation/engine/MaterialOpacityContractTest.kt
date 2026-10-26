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
            assertTrue(path, draw.contains("binding.setMaterialWeights(backdrop, 1f, 1f, 1f, material.blurRadius, material.radiusBackdrop)"))
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

    @Test fun sharedLiquidShaderGetsTheMaterialBeforeEveryDraw() {
        val renderer = SourceContract.read("liquid/LiquidActivityRenderer.kt")
        // One refraction shader serves every surface: a draw without its own material would inherit the previous one.
        assertTrue(Regex("\\.drawBackdrop\\(").findAll(renderer).count() == 2)
        assertTrue(renderer.contains("panelDriver.setMaterial(material)\n                        panelDriver.drawBackdrop("))
        assertTrue(renderer.contains("driver.setMaterial(material)\n                        driver.drawBackdrop("))
        val backend = SourceContract.read("liquid/LiquidRefractionBackendApi33.kt")
        assertTrue(backend.after("checkNotNull(source) { \"Liquid refraction backdrop is not bound\" }")
            .trimStart().startsWith("applyMaterialUniforms()"))
        assertCheap("liquid uniforms", backend.after("private fun applyMaterialUniforms()").before("override fun drawBackdrop("))
    }

    @Test fun activitySkinForwardsToEveryEngineAndSurvivesPrepare() {
        val engine = SourceContract.read("glow/GlowEngine.kt")
        assertTrue(engine.contains("fun setMaterialOpacity(role: SurfaceRole, value: Float) = Unit"))
        val session = SourceContract.read("runtime/ActivitySkinSession.kt")
        assertTrue(session.after("fun setMaterialOpacity(role: SurfaceRole, value: Float)")
            .contains("for (i in engines.indices) engines[i].setMaterialOpacity(role, value)"))
        val delegate = SourceContract.read("host/LumenActivityDelegate.kt")
        assertTrue(delegate.after("public fun prepare()").before("public fun setMaterialOpacity(role: SurfaceRole")
            .contains("created.setMaterialOpacity(role, value)"))
        for (path in listOf("liquid/LiquidActivityRenderer.kt", "material/FrostedMaterialRenderer.kt")) {
            val source = SourceContract.read(path)
            assertCheap(path, source.after("override fun setMaterialOpacity(role: SurfaceRole, value: Float)")
                .before("for (view in "))
        }
    }

    @Test fun gpuGlassSwitchesCachedBlurLevelsInsteadOfBuildingPerFrame() {
        val glass = SourceContract.read("glow/GlowChromeGlassApi31.kt")
        val update = glass.after("private fun updateEffect(").before("private fun remember(")
        // Same geometry and variant: nothing to do. A known variant comes from the cache; only a new one is built.
        assertTrue(update.contains("if (sameGeometry && variant == effectVariant) return false"))
        assertTrue(update.contains("val built = cached ?: effects.create(width, height, paddingPx, radiusPx, intensity, direction, variant)"))
        assertTrue(update.contains("variantEffects?.fill(null)"))
        val capture = SourceContract.read("host/SurfaceCaptureApi31.kt")
        val blurFor = capture.after("private fun blurFor(variant: Int): RenderEffect").before("val renderer = ")
        assertTrue(blurFor.contains("cache[variant]?.let { return it }"))
        // The configured radius keeps the original single chain (bit-identical at full opacity).
        assertTrue(capture.contains("GlowChromeGlassApi31.DEFAULT_VARIANT else blurLevel.coerceAtLeast(0)"))
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        assertTrue(session.contains("group.capture!!.draw(glass, canvas, nodeBounds, c.radiusDp * density, sampleMatrix, gpuAlpha, level)"))
    }

    @Test fun shadowOpacityStaysOutOfTheTextureCache() {
        val shadow = SourceContract.read("material/ControlShadowDrawable.kt")
        val draw = shadow.after("override fun draw(canvas: Canvas)").before("private fun siblingShadowOverlaps")
        assertTrue(draw.contains("gain * materialGain"))
        assertFalse(shadow.after("var materialGain").before("private val xStops").contains("ControlShadowTextureCache"))
    }
}
