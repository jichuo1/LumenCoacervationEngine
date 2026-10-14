package com.lumen.coacervation.engine.fluent

import com.lumen.coacervation.engine.host.LumenAcrylicFallbackReason
import com.lumen.coacervation.engine.host.LumenAcrylicKind
import com.lumen.coacervation.engine.host.LumenAcrylicOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fluent acrylic recipe as pure ARGB arithmetic, ported from microsoft-ui-xaml
 * `controls/dev/Materials/Acrylic/AcrylicBrush.cpp` and `AcrylicBrush_themeresources.xaml`.
 * No android.graphics types, so every number is covered by JVM tests.
 */
internal object FluentAcrylicPolicy {
    /** AcrylicBrush.h sc_noiseOpacity. */
    const val NOISE_OPACITY = 0.02f
    /** AcrylicBrush.h sc_blurRadius; Direct2D treats BlurAmount as a standard deviation. */
    const val FLUENT_BLUR_SIGMA = 30f
    /** AcrylicBrush::PlayCrossFadeAnimation switchDuration. */
    const val FALLBACK_TRANSITION_MS = 167L
    /** Below API 29 the luminosity layer is a source-over wash; it is weakened so the backdrop hue still shows. */
    const val APPROXIMATE_LUMINOSITY_SCALE = 0.82f

    class Defaults(val tintColor: Int, val tintOpacity: Float, val luminosityOpacity: Float, val fallbackColor: Int)

    /** Effective layer colours; [fallback] is always opaque. */
    class Recipe(val tint: Int, val luminosity: Int, val fallback: Int, val noiseAlpha: Int)

    fun defaults(kind: LumenAcrylicKind, dark: Boolean, accent: Int): Defaults = when (kind) {
        LumenAcrylicKind.DEFAULT -> if (dark) Defaults(0xFF2C2C2C.toInt(), 0.15f, 0.96f, 0xFF2C2C2C.toInt())
            else Defaults(0xFFFCFCFC.toInt(), 0f, 0.85f, 0xFFF9F9F9.toInt())
        LumenAcrylicKind.BASE -> if (dark) Defaults(0xFF202020.toInt(), 0.5f, 0.96f, 0xFF1C1C1C.toInt())
            else Defaults(0xFFF3F3F3.toInt(), 0f, 0.9f, 0xFFEEEEEE.toInt())
        LumenAcrylicKind.ACCENT -> Defaults(opaque(accent), 0.8f, if (dark) 0.8f else 0.9f, opaque(accent))
    }

    fun resolve(options: LumenAcrylicOptions, dark: Boolean, accent: Int): Recipe {
        val base = defaults(options.kind, dark, accent)
        val tintColor = options.tintColor ?: base.tintColor
        val tintOpacity = options.tintOpacity ?: base.tintOpacity
        // Theme brushes specify TintLuminosityOpacity; a customised tint without it derives luminosity like WinUI.
        val luminosityOpacity = options.luminosityOpacity
            ?: if (options.tintColor == null && options.tintOpacity == null) base.luminosityOpacity else null
        val withOpacity = withAlpha(tintColor, (alpha(tintColor) * tintOpacity).roundToInt())
        return Recipe(
            tint = effectiveTint(tintColor, tintOpacity, luminosityOpacity != null),
            luminosity = luminosityColor(withOpacity, luminosityOpacity),
            fallback = opaque(options.fallbackColor ?: base.fallbackColor),
            noiseAlpha = (255f * options.noiseOpacity).roundToInt()
        )
    }

    /** AcrylicBrush::GetEffectiveTintColor. */
    fun effectiveTint(tintColor: Int, tintOpacity: Float, luminositySpecified: Boolean): Int {
        val modifier = if (luminositySpecified) 1.0 else tintOpacityModifier(tintColor)
        return withAlpha(tintColor, Math.round(alpha(tintColor) * tintOpacity * modifier).toInt())
    }

    /** GetTintOpacityModifier: caps tint opacity at 45% (white), 90% (mid grey), 85% (black), relaxed by saturation. */
    fun tintOpacityModifier(tintColor: Int): Double {
        val midPoint = 0.50
        val whiteMaxOpacity = 0.45
        val midPointMaxOpacity = 0.90
        val blackMaxOpacity = 0.85
        val hsv = hsv(tintColor)
        var modifier = midPointMaxOpacity
        if (hsv[2] != midPoint) {
            var lowestMaxOpacity = midPointMaxOpacity
            var maxDeviation = midPoint
            if (hsv[2] > midPoint) {
                lowestMaxOpacity = whiteMaxOpacity
                maxDeviation = 1 - maxDeviation
            } else if (hsv[2] < midPoint) {
                lowestMaxOpacity = blackMaxOpacity
            }
            var maxOpacitySuppression = midPointMaxOpacity - lowestMaxOpacity
            val normalizedDeviation = abs(hsv[2] - midPoint) / maxDeviation
            if (hsv[1] > 0) maxOpacitySuppression *= max(1 - hsv[1] * 2, 0.0)
            modifier = midPointMaxOpacity - maxOpacitySuppression * normalizedDeviation
        }
        return modifier
    }

    /**
     * GetLuminosityColor. [tintColor] carries the user's tint opacity in its alpha (no modifier).
     * Without an explicit opacity, HSV value is clamped to 0.125..0.965 and alpha maps 0..1 to 0.15..1.03.
     */
    fun luminosityColor(tintColor: Int, luminosityOpacity: Float?): Int {
        if (luminosityOpacity != null) return withAlpha(tintColor, (luminosityOpacity.coerceIn(0f, 1f) * 255f).roundToInt())
        val minHsvV = 0.125
        val maxHsvV = 0.965
        val minLuminosityOpacity = 0.15
        val maxLuminosityOpacity = 1.03
        val hsv = hsv(tintColor)
        val rgb = hsvToRgb(hsv[0], hsv[1], hsv[2].coerceIn(minHsvV, maxHsvV))
        val mapped = alpha(tintColor) / 255.0 * (maxLuminosityOpacity - minLuminosityOpacity) + minLuminosityOpacity
        return withAlpha(rgb, Math.round(min(mapped, 1.0) * 255).toInt())
    }

    /** HWUI converts a blur radius to sigma as 0.57735·r + 0.5; this is the inverse. */
    fun blurRadiusForSigma(sigma: Float): Float = if (sigma <= 0.5f) 0f else (sigma - 0.5f) / 0.57735f

    fun approximateLuminosityAlpha(alpha: Int): Int = (alpha * APPROXIMATE_LUMINOSITY_SCALE).roundToInt().coerceIn(0, 255)

    /** Windows evaluates fallback policy before rendering; the first matching reason wins. */
    fun policyFallback(
        enabled: Boolean, reduceTransparency: Boolean, lowRamDevice: Boolean, powerSave: Boolean,
        windowInactive: Boolean, hasBackdrop: Boolean
    ): LumenAcrylicFallbackReason = when {
        !enabled -> LumenAcrylicFallbackReason.DISABLED
        reduceTransparency -> LumenAcrylicFallbackReason.REDUCE_TRANSPARENCY
        lowRamDevice -> LumenAcrylicFallbackReason.LOW_RAM_DEVICE
        powerSave -> LumenAcrylicFallbackReason.POWER_SAVE
        windowInactive -> LumenAcrylicFallbackReason.WINDOW_INACTIVE
        !hasBackdrop -> LumenAcrylicFallbackReason.NO_BACKDROP
        else -> LumenAcrylicFallbackReason.NONE
    }

    /** Reasons that persist long enough to release the sampling binding; WINDOW_INACTIVE toggles too often. */
    fun releasesBackdrop(reason: LumenAcrylicFallbackReason): Boolean = when (reason) {
        LumenAcrylicFallbackReason.NONE, LumenAcrylicFallbackReason.WINDOW_INACTIVE,
        LumenAcrylicFallbackReason.BACKDROP_UNAVAILABLE -> false
        else -> true
    }

    /** The acrylic/fallback crossfade easing: cubic-bezier(0.5, 0, 0, 0.9). */
    fun crossfadeEase(t: Float): Float = cubicBezier(t.coerceIn(0f, 1f), 0.5f, 0f, 0f, 0.9f)

    internal fun cubicBezier(x: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // Solve bx(u) = x by Newton steps, then bisection if the slope is too flat.
        var u = x
        repeat(8) {
            val error = bezier(u, x1, x2) - x
            if (abs(error) < 1e-6f) return bezier(u, y1, y2)
            val slope = bezierSlope(u, x1, x2)
            if (abs(slope) < 1e-6f) return@repeat
            u = (u - error / slope).coerceIn(0f, 1f)
        }
        var low = 0f
        var high = 1f
        u = x
        repeat(24) {
            val value = bezier(u, x1, x2)
            if (abs(value - x) < 1e-6f) return bezier(u, y1, y2)
            if (value < x) low = u else high = u
            u = (low + high) / 2f
        }
        return bezier(u, y1, y2)
    }

    private fun bezier(u: Float, p1: Float, p2: Float): Float {
        val v = 1f - u
        return 3f * v * v * u * p1 + 3f * v * u * u * p2 + u * u * u
    }

    private fun bezierSlope(u: Float, p1: Float, p2: Float): Float {
        val v = 1f - u
        return 3f * v * v * p1 + 6f * v * u * (p2 - p1) + 3f * u * u * (1f - p2)
    }

    /** ColorConversion RgbToHsv: hue in degrees, saturation and value in 0..1. */
    fun hsv(color: Int): DoubleArray {
        val r = red(color) / 255.0
        val g = green(color) / 255.0
        val b = blue(color) / 255.0
        val maximum = max(r, max(g, b))
        val minimum = min(r, min(g, b))
        val chroma = maximum - minimum
        var hue = when {
            chroma == 0.0 -> 0.0
            maximum == r -> 60.0 * (((g - b) / chroma) % 6.0)
            maximum == g -> 60.0 * ((b - r) / chroma + 2.0)
            else -> 60.0 * ((r - g) / chroma + 4.0)
        }
        if (hue < 0) hue += 360.0
        return doubleArrayOf(hue, if (maximum == 0.0) 0.0 else chroma / maximum, maximum)
    }

    /** Opaque RGB for the given HSV. */
    fun hsvToRgb(hue: Double, saturation: Double, value: Double): Int {
        val chroma = value * saturation
        val sector = (hue % 360.0) / 60.0
        val x = chroma * (1 - abs(sector % 2.0 - 1))
        val (r, g, b) = when {
            sector < 1 -> Triple(chroma, x, 0.0)
            sector < 2 -> Triple(x, chroma, 0.0)
            sector < 3 -> Triple(0.0, chroma, x)
            sector < 4 -> Triple(0.0, x, chroma)
            sector < 5 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        val m = value - chroma
        return argb(255, Math.round((r + m) * 255).toInt(), Math.round((g + m) * 255).toInt(), Math.round((b + m) * 255).toInt())
    }
}

internal fun alpha(color: Int): Int = color ushr 24
internal fun red(color: Int): Int = color shr 16 and 0xFF
internal fun green(color: Int): Int = color shr 8 and 0xFF
internal fun blue(color: Int): Int = color and 0xFF
internal fun argb(a: Int, r: Int, g: Int, b: Int): Int =
    (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
internal fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
internal fun opaque(color: Int): Int = color or 0xFF000000.toInt()
/** Relative luminance (sRGB, WCAG); matches ColorUtils.calculateLuminance without android.graphics. */
internal fun luminance(color: Int): Double {
    fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(red(color)) + 0.7152 * channel(green(color)) + 0.0722 * channel(blue(color))
}
