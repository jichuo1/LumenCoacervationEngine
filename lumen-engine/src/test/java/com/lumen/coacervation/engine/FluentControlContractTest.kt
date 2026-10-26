package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.fluent.FluentNoiseTexture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Write-only guarantees of the Fluent control effect that a pure policy test cannot see. */
class FluentControlContractTest {
    private val drawable = SourceContract.read("fluent/FluentControlDrawable.kt")
    private val control = SourceContract.read("host/LumenFluentControl.kt")

    @Test fun noiseTileHasAConstantBudgetAndIsDeterministic() {
        assertEquals(64 * 64 * 4, FluentNoiseTexture.BYTES)
        assertTrue(FluentNoiseTexture.BYTES <= 16 * 1024)
        val a = FluentNoiseTexture.pixels(7)
        assertEquals(FluentNoiseTexture.SIZE * FluentNoiseTexture.SIZE, a.size)
        assertTrue(a.contentEquals(FluentNoiseTexture.pixels(7)))
        assertTrue(a.all { it ushr 24 == 0xFF && (it shr 16 and 0xFF) == (it and 0xFF) })
        // Uniform grey: the mean sits near mid grey and both extremes occur.
        val grey = a.map { it and 0xFF }
        assertEquals(127.5, grey.average(), 8.0)
        assertTrue(grey.min() < 16 && grey.max() > 240)
    }

    @Test fun noiseIsNeverRecycledBecauseDisplayListsMayStillHoldIt() {
        val noise = SourceContract.read("fluent/FluentNoiseTexture.kt")
        assertFalse(noise.contains(".recycle("))
        assertTrue(noise.after("fun release()").before("fun pixels").contains("bitmap = null"))
    }

    @Test fun blendModeTypeOnlyLivesInTheApi29IsolationClass() {
        val root = SourceContract.file("fluent/FluentControlDrawable.kt").parentFile.parentFile
        // ENGINEERING_RULES §2: an API 29 type may appear only in an *ApiNN isolation class with NN >= 29.
        val isolated = Regex("Api(\\d+)\\.kt$")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (!SourceContract.normalize(file.readText()).contains("android.graphics.BlendMode")) return@forEach
            val level = isolated.find(file.name)?.groupValues?.get(1)?.toInt() ?: 0
            assertTrue(file.path, level >= 29)
        }
        val isolation = SourceContract.read("fluent/AcrylicBlendApi29.kt")
        assertTrue(isolation.contains("@RequiresApi(29)"))
        assertTrue(drawable.contains("if (exact != null && Build.VERSION.SDK_INT >= 29)"))
        assertTrue(drawable.contains("value.acrylic.exactBlend && Build.VERSION.SDK_INT >= 29"))
    }

    @Test fun drawPathAllocatesNothing() {
        val draw = drawable.after("override fun draw(canvas: Canvas)").before("override fun onBoundsChange")
        for (forbidden in listOf("Paint(", "LinearGradient(", "BitmapShader(", "Path()", "RectF(", "Matrix(", "saveLayer",
            "RenderEffect", "RuntimeShader", "BlurMaskFilter", "AcrylicBlendApi29(", "FluentNoiseTexture.acquire", "listOf(", "arrayOf("))
            assertFalse("draw path contains $forbidden", draw.contains(forbidden))
    }

    @Test fun backdropIsCompositedOverAnOpaqueBaseAndRecordingIsSkipped() {
        val draw = drawable.after("override fun draw(canvas: Canvas)").before("private fun drawAcrylic")
        val base = draw.indexOf("canvas.drawRect(rect, fill)")
        val backdrop = draw.indexOf("binding.draw(canvas, alphaValue / 255f)")
        assertTrue(base in 0 until backdrop)
        assertTrue(draw.contains("binding != null && !binding.isRecordingContent"))
        // The Drawable alpha reaches the backdrop, and the opaque base gives way together with the backdrop weight
        // (before 1.5.6 the base faded while the blurred backdrop stayed opaque).
        assertTrue(draw.contains("fill.color = withAlpha(recipe.fallback, (alphaValue * backdrop).roundToInt())"))
        assertTrue(draw.contains("binding.setMaterialWeights(backdrop, 1f, 1f, 1f)"))
        // The crossfade draws the fallback over the acrylic, matching WinUI's CrossFadeEffect weight.
        assertTrue(draw.contains("if (weight < 1f)"))
    }

    @Test fun animationWorkStopsWhenTransitionsSettle() {
        assertTrue(drawable.contains("if (crossfade.isRunning(now) || overlay.isRunning(now)) invalidateSelf()"))
        assertTrue(drawable.contains("options.reduceMotion || !ValueAnimator.areAnimatorsEnabled()"))
    }

    /** Device regression: Paint.setAlpha replaced the solid stroke's token alpha and drew an opaque border. */
    @Test fun solidStrokeAlphaComesFromTheColourToken() {
        val draw = drawable.after("override fun draw(canvas: Canvas)").before("private fun drawAcrylic")
        assertTrue(draw.contains("if (strokeShader == null) strokePaint.color = withAlpha(stroke.body, alpha(stroke.body) * alphaValue / 255)"))
        assertFalse(draw.contains("strokePaint.alpha = alphaValue\n        if"))
    }

    @Test fun closeRestoresOnlyWhatTheControlStillOwns() {
        val close = control.after("override fun close()").before("private fun refreshPolicy")
        assertTrue(close.contains("if (view.background === drawable) replaceBackground(view, original)"))
        assertTrue(close.contains("view.elevation == ownedElevation"))
        assertTrue(close.contains("restoreShadowColor(view)"))
        assertTrue(close.contains("binding?.close()"))
        assertTrue(close.contains("drawable.release()"))
        val restore = control.after("private fun restoreShadowColor").before("private fun isDark")
        assertTrue(restore.contains("if (view.outlineSpotShadowColor == owned)"))
        assertTrue(restore.contains("if (view.outlineAmbientShadowColor == owned)"))
    }

    @Test fun listenersHaveRemovalPathsAndRespectObserverLiveness() {
        assertTrue(control.contains("observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)"))
        assertTrue(control.contains("view.removeOnAttachStateChangeListener(attach)"))
        assertTrue(control.contains("override fun onViewAttachedToWindow(v: View) { observe(v); refreshPolicy() }"))
    }

    @Test fun sessionBudgetRejectionDegradesLocally() {
        val bind = control.after("private fun ensureBinding").before("private fun releaseBinding")
        assertTrue(bind.contains("catch (_: IllegalArgumentException)"))
        assertTrue(bind.contains("bindRejected = true"))
    }

    @Test fun drawOnlyBindingsShareOneRecordingPerSourceView() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val bind = session.after("internal fun bindViewDrawOnly").before("internal fun backendOf")
        assertTrue(bind.contains("groups.firstOrNull { it.source is ViewContentSource && it.source.coordinateView === source }"))
        assertTrue(bind.contains("ViewSurfaceTarget(view, matrices), false)"))
        val backend = session.after("internal fun backendOf").before("internal val isRecordingContent")
        assertFalse(backend.contains("firstOrNull"))
    }

    @Test fun noReflectionOrHiddenApi() {
        val dir = File(SourceContract.file("fluent/FluentControlDrawable.kt").parentFile.path)
        val sources = dir.listFiles()!!.filter { it.extension == "kt" }.map { it.readText() } + control
        for (text in sources) for (forbidden in listOf("Class.forName", "getDeclaredField", "getDeclaredMethod", "runCatching"))
            assertFalse(text.contains(forbidden))
    }
}
