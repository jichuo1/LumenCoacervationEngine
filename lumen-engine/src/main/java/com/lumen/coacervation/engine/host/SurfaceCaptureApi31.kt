package com.lumen.coacervation.engine.host

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import com.lumen.coacervation.engine.glow.GlowChromeBlurApi31
import com.lumen.coacervation.engine.glow.GlowChromeGlassApi31
import com.lumen.coacervation.engine.glow.GlowContentCaptureApi31
import com.lumen.coacervation.engine.liquid.LiquidChromeLensApi33
import com.lumen.coacervation.engine.liquid.LiquidTokenResolver
import com.lumen.coacervation.engine.liquid.LiquidVisualTuningPolicy
import com.lumen.coacervation.engine.material.FrostedChromeLensApi33
import com.lumen.coacervation.engine.runtime.LumenGraphicsCounters
import kotlin.math.floor
import kotlin.math.roundToInt

/** No platform 31/33 type escapes this isolation layer. One recording serves all local surfaces. */
@RequiresApi(31)
internal class SurfaceCaptureApi31 : AutoCloseable {
    private val content = GlowContentCaptureApi31()
    var scaleX = 1f
        private set
    var scaleY = 1f
        private set
    var recordedWidth = 0
        private set
    var recordedHeight = 0
        private set
    val recorded: Boolean get() = content.recorded
    var pixels = 0L
        private set

    fun record(source: LumenContentSource, options: LumenSurfaceSessionOptions, availablePixels: Int): Boolean {
        val view = source.coordinateView
        if (view.width <= 0 || view.height <= 0) return false
        if (availablePixels < 1_024) return false
        val scale = minOf(LumenSurfacePolicy.gpuScale(view.width, view.height, options.gpuScale, availablePixels),
            options.maxGpuDimension.toFloat() / maxOf(view.width, view.height))
        val width = floor(view.width * scale).toInt().coerceAtLeast(1)
        val height = floor(view.height * scale).toInt().coerceAtLeast(1)
        if (width.toLong() * height > availablePixels) return false
        pixels = width.toLong() * height
        scaleX = width.toFloat() / view.width
        scaleY = height.toFloat() / view.height
        val canvas = content.begin(width, height)
        try {
            canvas.scale(scaleX, scaleY)
            source.drawContent(canvas)
        } finally { content.end() }
        recordedWidth = width
        recordedHeight = height
        return true
    }

    fun draw(glass: Glass, canvas: Canvas, bounds: Rect, radius: Float, matrix: Matrix, alpha: Float) {
        matrix.preScale(1f / scaleX, 1f / scaleY)
        glass.renderer.draw(canvas, bounds, radius, content, 0f, 0f, alpha, 1f, 0f, null, matrix)
    }

    override fun close() { content.release(); pixels = 0L }

    @RequiresApi(31)
    class LensCache {
        private var frosted: Any? = null
        private var liquid: Any? = null
        fun lens(material: LumenSurfaceMaterial, parameters: com.lumen.coacervation.engine.model.LiquidParameters, density: Float, strength: Float, counters: LumenGraphicsCounters? = null): Any? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return if (material == LumenSurfaceMaterial.LIQUID) {
                val current = liquid ?: LiquidChromeLensApi33.create(parameters, density).also { liquid = it; counters?.shaderCompiled() }
                LiquidChromeLensApi33.configure(current, parameters, density); current
            } else {
                val current = frosted ?: FrostedChromeLensApi33.create(strength).also { frosted = it; counters?.shaderCompiled() }
                FrostedChromeLensApi33.configure(current, strength); current
            }
        }
    }

    @RequiresApi(31)
    class Glass(options: LumenSurfaceOptions, density: Float, dark: Boolean, cache: LensCache, counters: LumenGraphicsCounters? = null) : AutoCloseable {
        private val sampling = options.sampling
        private val pad = (maxOf(20f * sampling.refractionStrength, (if (sampling.blurEnabled) sampling.blurRadiusDp else 0f) * 2f) * density).roundToInt() + 2
        private val blur = if (!sampling.blurEnabled || sampling.blurRadiusDp == 0f) RenderEffect.createOffsetEffect(0f, 0f)
            else GlowChromeBlurApi31.create(sampling.blurRadiusDp * density)
        private val liquidParameters = LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(dark)).let {
            it.copy(refractionAmountDp = it.refractionAmountDp * sampling.refractionStrength,
                interiorDistortionDp = it.interiorDistortionDp * sampling.refractionStrength)
        }
        private val lens: Any? = if (sampling.refractionEnabled && sampling.refractionStrength > 0f &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cache.lens(options.material, liquidParameters, density, sampling.refractionStrength, counters)
        } else null
        val renderer = GlowChromeGlassApi31(pad) { width, height, padding, radius, intensity, direction ->
            var effect = blur
            if (lens != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                effect = if (options.material == LumenSurfaceMaterial.LIQUID)
                    LiquidChromeLensApi33.effect(lens, effect, width, height, padding, radius, intensity, direction)
                else FrostedChromeLensApi33.effect(lens, effect, width, height, padding, sampling.refractionStrength)
            }
            if (sampling.fadeEnabled) {
                val colors = IntArray(33)
                val positions = FloatArray(33)
                for (i in colors.indices) {
                    val t = i / 32f
                    val weight = LumenSurfacePolicy.fade(t, sampling.fadeHold, sampling.fadeEnd,
                        sampling.fadeDirection == LumenSurfaceFadeDirection.BOTTOM_TO_TOP)
                    colors[i] = Color.argb((255 * weight).roundToInt(), 255, 255, 255)
                    positions[i] = t
                }
                val mask = LinearGradient(0f, padding.toFloat(), 0f, padding + height.toFloat(), colors, positions, Shader.TileMode.CLAMP)
                effect = RenderEffect.createBlendModeEffect(effect, RenderEffect.createShaderEffect(mask), BlendMode.DST_IN)
            }
            effect
        }.apply { this.counters = counters }
        override fun close() = renderer.close()
    }
}
