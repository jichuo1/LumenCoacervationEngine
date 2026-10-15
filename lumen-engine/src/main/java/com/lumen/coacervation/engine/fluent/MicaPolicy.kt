package com.lumen.coacervation.engine.fluent

import com.lumen.coacervation.engine.host.LumenMicaFallbackReason
import com.lumen.coacervation.engine.host.LumenMicaKind
import com.lumen.coacervation.engine.host.LumenMicaLayer
import com.lumen.coacervation.engine.host.LumenMicaOptions
import kotlin.math.max
import kotlin.math.roundToInt

/** Pure policy. Layer/fallback colours are WinUI tokens; compositing strengths are Android adaptations. */
internal object MicaPolicy {
    const val MAX_EDGE = 96
    const val MAX_PIXELS = MAX_EDGE * MAX_EDGE
    const val MAX_BITMAP_BYTES = MAX_PIXELS * 4
    /** One snapshot, two pixel buffers and the final immutable copy during preparation. */
    const val MAX_PREPARATION_BYTES = MAX_BITMAP_BYTES * 4
    /** Deliberately shares the engine's Fluent transition; not a claimed Windows Mica timing constant. */
    const val TRANSITION_MS = 167L

    fun sampleSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, MAX_EDGE.toDouble() / max(width, height))
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    class Recipe(val fallback: Int, val tint: Int, val luminosityTarget: Int, val luminosity: Float)

    fun resolve(options: LumenMicaOptions, dark: Boolean, background: Int): Recipe {
        val alt = options.kind == LumenMicaKind.BASE_ALT
        val baseColor = when {
            dark && alt -> 0xFF0A0A0A.toInt()
            dark -> 0xFF202020.toInt()
            alt -> 0xFFDADADA.toInt()
            else -> 0xFFF3F3F3.toInt()
        }
        val fallback = if (options.highContrast) opaque(background) else opaque(options.fallbackColor ?: baseColor)
        val tint = options.tintColor ?: baseColor
        // Mica Alt retains a stronger spatial impression of the wallpaper than Base.
        val opacity = options.tintOpacity ?: when {
            dark && alt -> 0.60f
            dark -> 0.80f
            alt -> 0.72f
            else -> 0.88f
        }
        return Recipe(fallback, withAlpha(tint, (opacity * 255).roundToInt()), baseColor,
            options.luminosityOpacity ?: 0.65f)
    }

    /** WinUI Common_themeresources_any.xaml: LayerFillColorDefault / LayerOnMicaBaseAltFillColorDefault. */
    fun layerColor(layer: LumenMicaLayer, kind: LumenMicaKind, dark: Boolean): Int =
        if (layer == LumenMicaLayer.COMMAND && kind == LumenMicaKind.BASE_ALT) {
            if (dark) 0x733A3A3A else 0xB3FFFFFF.toInt()
        } else if (layer == LumenMicaLayer.COMMAND) 0
        else if (dark) 0x4C3A3A3A else 0x80FFFFFF.toInt()

    fun fallback(options: LumenMicaOptions, lowRam: Boolean, powerSave: Boolean, inactive: Boolean,
        hasImage: Boolean, memoryReleased: Boolean): LumenMicaFallbackReason = when {
        !options.enabled -> LumenMicaFallbackReason.DISABLED
        options.highContrast -> LumenMicaFallbackReason.HIGH_CONTRAST
        options.reduceTransparency -> LumenMicaFallbackReason.REDUCE_TRANSPARENCY
        options.fallbackOnLowRamDevice && lowRam -> LumenMicaFallbackReason.LOW_RAM_DEVICE
        options.fallbackOnPowerSave && powerSave -> LumenMicaFallbackReason.POWER_SAVE
        memoryReleased -> LumenMicaFallbackReason.MEMORY_PRESSURE
        options.fallbackWhenWindowInactive && inactive -> LumenMicaFallbackReason.WINDOW_INACTIVE
        !hasImage -> LumenMicaFallbackReason.NO_BACKGROUND
        else -> LumenMicaFallbackReason.NONE
    }

    /** RGB luma wash, preserving local hue, followed by an opaque-tint source-over layer.
     * This bounded CPU recipe is shared by all Android versions, without high-API blend types. */
    fun materialPixel(color: Int, recipe: Recipe): Int {
        val sourceLuma = (red(color) * 0.299f + green(color) * 0.587f + blue(color) * 0.114f)
        val targetLuma = red(recipe.luminosityTarget) * 0.299f + green(recipe.luminosityTarget) * 0.587f +
            blue(recipe.luminosityTarget) * 0.114f
        val shift = (targetLuma - sourceLuma) * recipe.luminosity
        val weight = alpha(recipe.tint) / 255f
        fun channel(source: Int, tint: Int): Int =
            ((source + shift).coerceIn(0f, 255f) * (1f - weight) + tint * weight).roundToInt()
        return argb(255, channel(red(color), red(recipe.tint)), channel(green(color), green(recipe.tint)),
            channel(blue(color), blue(recipe.tint)))
    }
}
