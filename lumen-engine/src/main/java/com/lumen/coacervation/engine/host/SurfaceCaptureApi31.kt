package com.lumen.coacervation.engine.host

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import com.lumen.coacervation.engine.glow.GlowChromeBlurApi31
import com.lumen.coacervation.engine.glow.GlowChromeBlurPolicy
import com.lumen.coacervation.engine.glow.GlowChromeGlassApi31
import com.lumen.coacervation.engine.glow.GlowContentCaptureApi31
import com.lumen.coacervation.engine.liquid.LiquidChromeLensApi33
import com.lumen.coacervation.engine.liquid.LiquidTokenResolver
import com.lumen.coacervation.engine.liquid.LiquidVisualTuningPolicy
import com.lumen.coacervation.engine.material.FrostedChromeLensApi33
import com.lumen.coacervation.engine.prism.PrismLensApi33
import com.lumen.coacervation.engine.prism.PrismPolicy
import com.lumen.coacervation.engine.prism.PrismRecipe
import com.lumen.coacervation.engine.runtime.LumenGraphicsCounters
import com.lumen.coacervation.engine.runtime.MaterialOpacityPolicy
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

    fun draw(glass: Glass, canvas: Canvas, bounds: Rect, radius: Float, matrix: Matrix, alpha: Float,
        blurLevel: Int = MaterialOpacityPolicy.BLUR_LEVELS) {
        matrix.preScale(1f / scaleX, 1f / scaleY)
        // The configured radius keeps the original single chain; thinner levels are cached variants of it.
        val variant = if (!glass.blurControllable || blurLevel >= MaterialOpacityPolicy.BLUR_LEVELS)
            GlowChromeGlassApi31.DEFAULT_VARIANT else blurLevel.coerceAtLeast(0)
        glass.renderer.draw(canvas, bounds, radius, content, 0f, 0f, alpha, 1f, 0f, null, matrix, variant)
    }

    /** Pre-compiles one blur level of [glass] (see `GlowChromeGlassApi31.drawWarm`). */
    fun drawWarm(glass: Glass, canvas: Canvas, bounds: Rect, radius: Float, matrix: Matrix, level: Int) {
        if (!glass.blurControllable || level !in 1 until MaterialOpacityPolicy.BLUR_LEVELS) return
        matrix.preScale(1f / scaleX, 1f / scaleY)
        glass.renderer.drawWarm(canvas, bounds, radius, content, matrix, level)
    }

    override fun close() { content.release(); pixels = 0L }

    @RequiresApi(31)
    class LensCache {
        private var frosted: Any? = null
        private var liquid: Any? = null
        private var prism: Any? = null
        fun prism(counters: LumenGraphicsCounters? = null): Any? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return prism ?: PrismLensApi33.create().also { prism = it; counters?.shaderCompiled() }
        }
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
    class Glass(private val options: LumenSurfaceOptions, private val density: Float, dark: Boolean, cache: LensCache,
        counters: LumenGraphicsCounters? = null, private val preserveBackdropColors: Boolean = false,
        private val prism: PrismRecipe? = null) : AutoCloseable {
        private val sampling = options.sampling
        private val pad = if (prism != null) PrismPolicy.paddingPx(prism, density)
            else (maxOf(20f * sampling.refractionStrength, (if (sampling.blurEnabled) sampling.blurRadiusDp else 0f) * 2f) * density).roundToInt() + 2
        private val blurRadiusPx = if (sampling.blurEnabled) sampling.blurRadiusDp * density else 0f
        private val blur = if (!sampling.blurEnabled || sampling.blurRadiusDp == 0f) RenderEffect.createOffsetEffect(0f, 0f)
            else GlowChromeBlurApi31.create(sampling.blurRadiusDp * density)
        /** Material opacity can thin this glass by switching blur levels (MATERIAL_OPACITY.md §13). */
        val blurControllable: Boolean = blurRadiusPx > 0f
        /** Base blur per material-opacity level; the full level is [blur] itself. Built once per level. */
        private var levelBlurs: Array<RenderEffect?>? = null
        private val levelSpec = FloatArray(2)

        private fun blurFor(variant: Int): RenderEffect {
            if (variant < 0 || variant >= MaterialOpacityPolicy.BLUR_LEVELS || !blurControllable) return blur
            val cache = levelBlurs ?: arrayOfNulls<RenderEffect>(MaterialOpacityPolicy.BLUR_LEVELS).also { levelBlurs = it }
            cache[variant]?.let { return it }
            // Half-octave steps of the main sigma: Skia's downscale then reuses a few blur programs (§14).
            GlowChromeBlurPolicy.level(blurRadiusPx, MaterialOpacityPolicy.levelFraction(variant), levelSpec)
            val built = GlowChromeBlurApi31.createLevel(levelSpec[0] > 0.5f, levelSpec[1])
            cache[variant] = built
            return built
        }
        private val liquidParameters = LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(dark)).let {
            it.copy(refractionAmountDp = it.refractionAmountDp * sampling.refractionStrength,
                interiorDistortionDp = it.interiorDistortionDp * sampling.refractionStrength)
        }
        private val lensStrength = if (sampling.refractionEnabled) sampling.refractionStrength else 0f
        // ColorMatrix can retain extended-range RGB through the following alpha blend. The existing
        // frosted shader clamps RGB to premultiplied alpha before fading, including when the lens is off.
        private val lens: Any? = if (prism == null && !preserveBackdropColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (options.material == LumenSurfaceMaterial.FROSTED || lensStrength > 0f)) {
            cache.lens(options.material, liquidParameters, density, lensStrength, counters)
        } else null
        private val prismLens: Any? = if (prism != null && prism.lensEnabled) cache.prism(counters) else null
        /** Prism refraction is in this glass's effect chain (API 33+ with a non-zero lens). */
        val prismLensActive: Boolean get() = prismLens != null
        private val prismSaturation = if (prism != null && prism.saturation != 1f)
            ColorMatrixColorFilter(ColorMatrix(PrismPolicy.saturationMatrix(prism.saturation))) else null
        val renderer = GlowChromeGlassApi31(pad, object : GlowChromeGlassApi31.Effects {
            override fun create(width: Int, height: Int, padding: Int, radiusPx: Float, opticalIntensity: Float,
                stretchDirY: Float): RenderEffect = chain(width, height, padding, radiusPx, opticalIntensity, stretchDirY,
                GlowChromeGlassApi31.DEFAULT_VARIANT)
            override fun create(width: Int, height: Int, padding: Int, radiusPx: Float, opticalIntensity: Float,
                stretchDirY: Float, variant: Int): RenderEffect =
                chain(width, height, padding, radiusPx, opticalIntensity, stretchDirY, variant)
        }).apply { this.counters = counters }

        private fun chain(width: Int, height: Int, padding: Int, radius: Float, intensity: Float, direction: Float,
            variant: Int): RenderEffect {
            var effect = blurFor(variant)
            if (prism != null) {
                // Prism: blur → saturation → rounded-rect refraction. No frosted gain, no fade.
                if (prismSaturation != null) effect = RenderEffect.createColorFilterEffect(prismSaturation, effect)
                if (prismLens != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    effect = PrismLensApi33.effect(prismLens, effect, width, height, padding, radius, prism, density)
                return effect
            }
            if(options.material==LumenSurfaceMaterial.FROSTED&&lens==null&&!preserveBackdropColors) {
                val gain=com.lumen.coacervation.engine.material.LensRefractionPolicy.LUMINANCE_GAIN
                val bias=com.lumen.coacervation.engine.material.LensRefractionPolicy.LUMINANCE_BIAS
                effect=RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    gain,0f,0f,0f,bias,0f,gain,0f,0f,bias,0f,0f,gain,0f,bias,0f,0f,0f,1f,0f))),effect)
            }
            if (lens != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                effect = if (options.material == LumenSurfaceMaterial.LIQUID)
                    LiquidChromeLensApi33.effect(lens, effect, width, height, padding, radius, intensity, direction)
                else FrostedChromeLensApi33.effect(lens, effect, width, height, padding, lensStrength)
            }
            if (sampling.fadeEnabled) {
                val colors = IntArray(33)
                val positions = FloatArray(33)
                for (i in colors.indices) {
                    val t = i / 32f
                    val weight = LumenSurfacePolicy.fade(t, sampling.fadeHold, sampling.fadeEnd,
                        sampling.fadeDirection == LumenSurfaceFadeDirection.BOTTOM_TO_TOP, sampling.fadeCurve)
                    colors[i] = Color.argb((255 * weight).roundToInt(), 255, 255, 255)
                    positions[i] = t
                }
                val mask = LinearGradient(0f, padding.toFloat(), 0f, padding + height.toFloat(), colors, positions, Shader.TileMode.CLAMP)
                effect = RenderEffect.createBlendModeEffect(effect, RenderEffect.createShaderEffect(mask), BlendMode.DST_IN)
            }
            return effect
        }

        override fun close() { renderer.close(); levelBlurs = null }
    }

}
