package com.lumen.coacervation.engine.prism

import com.lumen.coacervation.engine.host.LumenPrismFallbackReason
import com.lumen.coacervation.engine.host.LumenPrismOptions
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 棱镜玻璃传给局部视效会话的 GPU 配方：只含影响采样节点效果链的参数，其余层由背景 Drawable 自己画。
 * 数值为 dp / 倍率；换算像素在 `SurfaceCaptureApi31.Glass` 里完成。
 */
internal data class PrismRecipe(
    val blurRadiusDp: Float,
    val saturation: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val depthEffect: Float,
    val dispersion: Float
) {
    val lensEnabled: Boolean get() = refractionHeightDp > 0f && refractionAmountDp > 0f
}

/** 纯函数部分：默认色、配方、回退判定与几何，全部可在 JVM 上测试。 */
internal object PrismPolicy {
    /** 参考库底栏容器的表面色。 */
    const val LIGHT_TINT = 0xFFFAFAFA.toInt()
    const val DARK_TINT = 0xFF121212.toInt()
    const val DEFAULT_TINT_OPACITY = 0.4f
    /** 胶囊半径上限：与 `LumenSurfaceOptions.radiusDp` 的上限一致，超过的高度显示为大圆角矩形。 */
    const val CAPSULE_RADIUS_DP = 128f
    /** 按压光晕：全强半径与渐隐半径（相对短边），取自参考库 InteractiveHighlight。 */
    const val GLOW_RADIUS_FACTOR = 1.5f
    const val GLOW_SOLID_FRACTION = 0.5f
    const val PRESS_IN_MS = 120L
    const val PRESS_OUT_MS = 260L
    const val FALLBACK_TRANSITION_MS = 167L

    fun tint(options: LumenPrismOptions, dark: Boolean): Int {
        val rgb = (options.surface.tintColor ?: if (dark) DARK_TINT else LIGHT_TINT) and 0xFFFFFF
        val opacity = options.surface.tintOpacity ?: DEFAULT_TINT_OPACITY
        return rgb or ((opacity * 255f).roundToInt().coerceIn(0, 255) shl 24)
    }

    /**
     * 回退色（不透明）：[LumenPrismSurfaceOptions.fallbackColor] 优先；否则中性底（深浅色默认表面色）上
     * 叠 `fallbackOpacity` 的表面色 RGB。
     */
    fun fallback(options: LumenPrismOptions, dark: Boolean): Int {
        options.surface.fallbackColor?.let { return (it and 0xFFFFFF) or (0xFF shl 24) }
        val neutral = if (dark) DARK_TINT else LIGHT_TINT
        val tint = options.surface.tintColor ?: neutral
        val t = options.surface.fallbackOpacity
        fun mix(shift: Int): Int {
            val base = (neutral shr shift) and 0xFF
            val top = (tint shr shift) and 0xFF
            return (base + (top - base) * t).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    fun recipe(options: LumenPrismOptions): PrismRecipe {
        val blur = options.blur
        val lens = options.lens
        val lensOn = lens.enabled && lens.refractionHeightDp > 0f && lens.refractionAmountDp > 0f
        return PrismRecipe(
            blurRadiusDp = if (blur.enabled) blur.radiusDp else 0f,
            // Saturation is its own layer: switching the blur off keeps it.
            saturation = blur.saturation,
            refractionHeightDp = if (lensOn) lens.refractionHeightDp else 0f,
            refractionAmountDp = if (lensOn) lens.refractionAmountDp else 0f,
            depthEffect = if (lensOn) lens.depthEffect else 0f,
            dispersion = if (lensOn) lens.dispersion else 0f
        )
    }

    /** 采样节点外扩：只有模糊需要外侧内容；折射向内取样，不需要外扩。 */
    fun paddingPx(recipe: PrismRecipe, density: Float): Int =
        (recipe.blurRadiusDp * 2f * density).roundToInt() + 2

    fun cornerRadiusDp(options: LumenPrismOptions): Float = options.cornerRadiusDp ?: CAPSULE_RADIUS_DP

    /**
     * 实际绘制半径：胶囊取短边一半，上限 [CAPSULE_RADIUS_DP]（与传给会话的采样半径一致，折射轮廓与可见边缘重合）；
     * 任何半径都不超过短边一半（与 Skia 圆角矩形的钳制一致）。
     */
    fun radiusPx(options: LumenPrismOptions, density: Float, width: Float, height: Float): Float {
        val half = max(0f, minOf(width, height) / 2f)
        val requested = (options.cornerRadiusDp ?: CAPSULE_RADIUS_DP) * density
        return minOf(requested, half)
    }

    fun policyFallback(
        options: LumenPrismOptions,
        lowRamDevice: Boolean,
        powerSave: Boolean
    ): LumenPrismFallbackReason = when {
        !options.enabled -> LumenPrismFallbackReason.DISABLED
        options.reduceTransparency -> LumenPrismFallbackReason.REDUCE_TRANSPARENCY
        options.fallbackOnLowRamDevice && lowRamDevice -> LumenPrismFallbackReason.LOW_RAM_DEVICE
        options.fallbackOnPowerSave && powerSave -> LumenPrismFallbackReason.POWER_SAVE
        else -> LumenPrismFallbackReason.NONE
    }

    /** 这些原因下不保留采样绑定：省掉 GPU 录制与软件取样。 */
    fun releasesBackdrop(reason: LumenPrismFallbackReason): Boolean =
        reason != LumenPrismFallbackReason.NONE && reason != LumenPrismFallbackReason.BACKDROP_UNAVAILABLE &&
            reason != LumenPrismFallbackReason.NO_BACKDROP

    /**
     * 参考库 `colorControls(saturation = s)` 的 4×5 颜色矩阵（亮度 0、对比度 1）：
     * 行 `[r + s, g, b, 0, 0]` 等，`r/g/b = 0.213/0.715/0.072 · (1 − s)`。
     */
    fun saturationMatrix(saturation: Float): FloatArray {
        val inv = 1f - saturation
        val r = 0.213f * inv
        val g = 0.715f * inv
        val b = 0.072f * inv
        return floatArrayOf(
            r + saturation, g, b, 0f, 0f,
            r, g + saturation, b, 0f, 0f,
            r, g, b + saturation, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    /** 高光光向（屏幕坐标，y 向下）。 */
    fun lightDirection(angleDegrees: Float, out: FloatArray) {
        val radians = angleDegrees * PI / 180.0
        out[0] = cos(radians).toFloat()
        out[1] = sin(radians).toFloat()
    }

    /**
     * 圆角矩形距离场的近似法线（与 shader 同一算法，供 JVM 测试与 API 33 以下的退化路径对照）。
     * [x]/[y] 为相对中心的坐标。
     */
    fun gradient(x: Float, y: Float, halfWidth: Float, halfHeight: Float, radius: Float, out: FloatArray) {
        val r = minOf(radius, halfWidth, halfHeight)
        val qx = kotlin.math.abs(x) - (halfWidth - r)
        val qy = kotlin.math.abs(y) - (halfHeight - r)
        val sx = if (x < 0f) -1f else 1f
        val sy = if (y < 0f) -1f else 1f
        val mx = max(qx, 0f)
        val my = max(qy, 0f)
        val length = kotlin.math.sqrt(mx * mx + my * my)
        if (length > 1e-4f) {
            out[0] = sx * mx / length
            out[1] = sy * my / length
        } else {
            val gx = if (qx >= qy) 1f else 0f
            out[0] = sx * gx
            out[1] = sy * (1f - gx)
        }
    }

    /** 高光强度 `|dot(n, l)|^falloff`；falloff 为 0 时是均匀细边。 */
    fun highlightIntensity(nx: Float, ny: Float, lx: Float, ly: Float, falloff: Float): Float {
        if (falloff <= 0f) return 1f
        val d = kotlin.math.abs(nx * lx + ny * ly)
        return Math.pow(d.toDouble(), falloff.toDouble()).toFloat()
    }

    /** 折射剖面（shader 同式）：距边缘 [inside] 像素处的取样偏移，向内为负。 */
    fun refractionOffset(inside: Float, heightPx: Float, amountPx: Float): Float {
        if (heightPx <= 0f || inside >= heightPx) return 0f
        val x = 1f - inside.coerceAtLeast(0f) / heightPx
        return -(1f - kotlin.math.sqrt(max(0f, 1f - x * x))) * amountPx
    }
}
