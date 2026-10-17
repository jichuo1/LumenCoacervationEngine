package com.lumen.coacervation.engine.material

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 控件软阴影的纯几何、剖面与预算策略（不依赖 android.graphics，JVM 可测）。
 *
 * 阴影按**到控件轮廓的距离**烘焙成连续剖面，只画在轮廓之外：
 * 环境项（1.6× 模糊半径的高斯尾）负责宽而柔的浮起感，贴边接触带补一点接触。
 * 轮廓内侧完全不画，控件边缘保持自己的干净轮廓，叠加多个控件时不会互相压出暗边。
 * 尺寸量化到 [RADIUS_QUANTUM_PX]/[BLUR_QUANTUM_PX]，避免参数微抖制造新键。
 */
internal object ControlShadowPolicy {
    const val RADIUS_QUANTUM_PX = 4
    const val BLUR_QUANTUM_PX = 4
    const val SPREAD_QUANTUM_PX = 2

    /** 逻辑（屏幕 px）模糊上限；更大的请求被压缩，避免纹理边长失控。 */
    const val MAX_BLUR_LOGICAL_PX = 64
    const val CORE_STRAIGHT_PX = 16

    /** 逻辑（屏幕 px）边长上限；九宫格的目标侧坐标始终按它换算。 */
    const val MAX_LOGICAL_SIDE_PX = 640

    /**
     * 纹理采样倍率。软影是低频图像，0.4× 双线性放大不可分辨；像素量与烘焙运算同时降到约 1/6，
     * 使同屏并存的多种圆角仍落在 [MAX_CACHE_BYTES] 内。
     */
    const val TEXTURE_SCALE = 0.4f
    /** 实际位图边长上限 = [MAX_LOGICAL_SIDE_PX] × [TEXTURE_SCALE]。 */
    const val MAX_TEXTURE_SIDE_PX = 256
    const val MAX_TEXTURE_BYTES = MAX_TEXTURE_SIDE_PX * MAX_TEXTURE_SIDE_PX * 4
    const val MAX_CACHE_BYTES = 2 * 1024 * 1024

    // ---------------- 剖面常数 ----------------

    /** 环境项的高斯 σ = 模糊半径 × 此倍率；1.6 让阴影显著外扩、不再堆在边框周围。 */
    const val CAST_WIDE_SCALE = 1.6f
    /** 近边收紧项的 σ（相对宽幅 σ）与权重；权重之和为 1，轮廓处归一。 */
    const val CAST_TIGHT_SCALE = 0.5f
    const val CAST_TIGHT_WEIGHT = 0.15f
    const val CAST_WIDE_WEIGHT = 0.85f

    /** 轮廓处的峰值倍率；与旧实现的 0.5×opacity 保持一致，升级不改变总深浅。 */
    const val CAST_PEAK_SCALE = 0.5f

    /** 贴边接触带：中心距轮廓 0.5dp，宽度（σ）0.9dp，纹理向外留 3dp。 */
    const val CONTACT_CENTER_DP = 0.5f
    const val CONTACT_WIDTH_DP = 0.9f
    const val CONTACT_EDGE_DP = 3f

    /** 一维 alpha 查表样本数：d 从 0 到 pad，每纹理只建一次。 */
    const val LUT_SAMPLES = 512

    /** 重叠减弱默认保留比例；宿主可经 [LumenControlShadowOptions.overlapAttenuation] 覆盖。 */
    const val DEFAULT_OVERLAP_ATTENUATION = 0.55f

    /** 纹理层：环境影与贴边接触带分别成图，强度独立、键空间互不污染。 */
    enum class Layer { CAST, CONTACT }

    /** 环境项留白：2.5σ 之外的能量约 1%，再留 2px 抗尾部。 */
    fun padPx(sigmaPx: Float): Int = ceil(sigmaPx * 2.5f).toInt() + 2

    /** 接触层留白（px）：软带在 [CONTACT_EDGE_DP] 之外已不可见。 */
    fun contactPadPx(density: Float): Int = ceil(CONTACT_EDGE_DP * density).toInt().coerceAtLeast(1)

    fun quantizeRadius(radiusPx: Float): Int =
        (radiusPx / RADIUS_QUANTUM_PX).roundToInt() * RADIUS_QUANTUM_PX
    fun quantizeBlur(blurPx: Float): Int =
        (blurPx / BLUR_QUANTUM_PX).roundToInt() * BLUR_QUANTUM_PX
    fun quantizeSpread(spreadPx: Float): Int =
        (spreadPx / SPREAD_QUANTUM_PX).roundToInt() * SPREAD_QUANTUM_PX

    /** 逻辑 px -> 纹理 px。 */
    fun scalePx(value: Int): Int = (value * TEXTURE_SCALE).roundToInt()

    /**
     * 逻辑坐标（绘制用）与纹理坐标（建图、九宫格取样用）成对保存；绘制路径不再换算。
     * 九宫格单元格内边界是圆角本身（内侧不画，不需要额外的内侧留白）。
     */
    data class TextureMetrics(
        val layer: Layer,
        val blurPx: Int,
        val padPx: Int,
        val cornerPx: Int,
        val sidePx: Int,
        val textureBlurPx: Int,
        val texturePadPx: Int,
        val textureCornerPx: Int,
        val textureSidePx: Int,
        val offsetBias: Float,
        val textureContactCenterPx: Float,
        val textureContactWidthPx: Float
    ) {
        val bytes: Int get() = textureSidePx * textureSidePx * 4

        /** 进程级缓存键：层、圆角、留白、模糊与竖向偏移增益，任一不同都不能共用位图。 */
        val key: Long
            get() = (layer.ordinal.toLong() shl 56) or
                (textureCornerPx.toLong() shl 40) or
                (texturePadPx.toLong() shl 24) or
                ((((offsetBias + 0.5f) * 64f).roundToInt().toLong() and 0x7F) shl 8) or
                textureBlurPx.toLong()
    }

    /**
     * 竖向增益：`1 + bias·(2y−1)`，y 是从表面顶部到底部的归一化位置。
     * 正的 [bias] 让底部阴影更强、顶部更弱，得到顶光感；内边界始终贴着表面轮廓，
     * 不会像平移纹理那样在边缘留出亮缝。
     */
    fun verticalGain(yNorm: Float, bias: Float): Float =
        (1f + bias * (2f * yNorm - 1f)).coerceIn(0.3f, 1.7f)

    /**
     * 双层纹理度量。环境层留白由 1.6× 模糊半径决定；接触层留白固定为 [CONTACT_EDGE_DP]。
     * 模糊或圆角超过 [MAX_LOGICAL_SIDE_PX] 预算时先压缩留白、再压缩圆角，保证纹理有界。
     */
    fun textureMetrics(
        layer: Layer,
        radiusPx: Float,
        blurRadiusPx: Float,
        density: Float,
        offsetYPx: Float = 0f
    ): TextureMetrics {
        require(density.isFinite() && density > 0f) { "density must be positive, was $density" }
        val blur = quantizeBlur(blurRadiusPx).coerceIn(0, MAX_BLUR_LOGICAL_PX)
        val maxCorner = (MAX_LOGICAL_SIDE_PX - 2 * 2 - CORE_STRAIGHT_PX) / 2
        val corner = min(quantizeRadius(radiusPx).coerceAtLeast(0), maxCorner)
        val maxPad = ((MAX_LOGICAL_SIDE_PX - 2 * corner - CORE_STRAIGHT_PX) / 2).coerceAtLeast(2)
        val nominalPad = if (layer == Layer.CAST) padPx(blur * CAST_WIDE_SCALE) else contactPadPx(density)
        val pad = min(nominalPad, maxPad)
        val sigma = if (layer == Layer.CAST) min(blur * CAST_WIDE_SCALE, (pad - 2) / 2.5f) else 0f
        val side = 2 * pad + 2 * corner + CORE_STRAIGHT_PX
        require(side <= MAX_LOGICAL_SIDE_PX) { "shadow texture $side exceeds $MAX_LOGICAL_SIDE_PX" }
        val bias = if (layer == Layer.CAST && blur > 0 && offsetYPx.isFinite()) {
            (offsetYPx / blur.toFloat()).coerceIn(-0.5f, 0.5f)
        } else 0f

        val texturePad = scalePx(pad)
        val textureCorner = scalePx(corner)
        val textureSide = 2 * texturePad + 2 * textureCorner +
            scalePx(CORE_STRAIGHT_PX).coerceAtLeast(1)
        return TextureMetrics(
            layer = layer,
            blurPx = blur,
            padPx = pad,
            cornerPx = corner,
            sidePx = side,
            textureBlurPx = if (sigma <= 0f) 0 else scalePx(sigma.roundToInt()).coerceAtLeast(1),
            texturePadPx = texturePad,
            textureCornerPx = textureCorner,
            textureSidePx = textureSide,
            offsetBias = bias,
            textureContactCenterPx = CONTACT_CENTER_DP * density * TEXTURE_SCALE,
            textureContactWidthPx = CONTACT_WIDTH_DP * density * TEXTURE_SCALE
        )
    }

    /**
     * 圆角矩形的有符号距离：内侧为负、轮廓为 0、外侧为正。
     * [halfWidth]/[halfHeight] 是矩形半宽高，[radius] 是圆角半径。
     */
    fun roundedRectDistance(
        dx: Float,
        dy: Float,
        halfWidth: Float,
        halfHeight: Float,
        radius: Float
    ): Float {
        val qx = abs(dx) - (halfWidth - radius)
        val qy = abs(dy) - (halfHeight - radius)
        val outside = sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f))
        val inside = min(max(qx, qy), 0f)
        return outside + inside - radius
    }

    /**
     * 环境层剖面：轮廓处峰值，外侧按"宽幅为主 + 近边轻微收紧"衰减；内侧恒为 0。
     * [blurPx] 是纹理空间的高斯 σ。
     */
    fun castAlpha(distancePx: Float, blurPx: Float): Float {
        if (blurPx <= 0f || distancePx <= 0f) return 0f
        val u = distancePx / blurPx
        val tight = exp(-(u / CAST_TIGHT_SCALE) * (u / CAST_TIGHT_SCALE))
        val wide = exp(-u * u)
        return CAST_PEAK_SCALE * (CAST_TIGHT_WEIGHT * tight + CAST_WIDE_WEIGHT * wide)
    }

    /** 接触带剖面：中心在轮廓外 [centerPx]，宽度 [widthPx]；内侧恒为 0。 */
    fun contactAlpha(distancePx: Float, centerPx: Float, widthPx: Float): Float {
        if (widthPx <= 0f || distancePx <= 0f) return 0f
        val t = (distancePx - centerPx) / widthPx
        return exp(-t * t)
    }

    /**
     * 一维 alpha 查表：覆盖 `d ∈ [0, pad]`（纹理 px），值域 0..255。
     * 建图时每像素只做一次距离查询 + 查表，不再逐像素调用 exp。
     */
    fun buildAlphaLut(metrics: TextureMetrics, samples: Int = LUT_SAMPLES): IntArray {
        require(samples >= 2)
        val max = metrics.texturePadPx.toFloat()
        val span = max.coerceAtLeast(1f)
        return IntArray(samples) { index ->
            val d = span * index / (samples - 1)
            val alpha = when (metrics.layer) {
                Layer.CAST -> castAlpha(d, metrics.textureBlurPx.toFloat())
                Layer.CONTACT -> contactAlpha(
                    d, metrics.textureContactCenterPx, metrics.textureContactWidthPx)
            }
            (alpha * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    /** 阴影外扩量（px）：控件边界之外需要这么多空间才能完整画出阴影。 */
    fun outsetPx(blurRadiusPx: Float, spreadPx: Float, offsetYPx: Float): Int {
        val blur = quantizeBlur(blurRadiusPx).coerceIn(0, MAX_BLUR_LOGICAL_PX)
        val pad = padPx(blur * CAST_WIDE_SCALE)
        return (pad + max(0f, spreadPx) + max(0f, offsetYPx)).roundToInt()
    }

    /** 两个轴对齐矩形是否相交（开区间；相切不算重叠，避免相邻项误判）。 */
    fun rectsOverlap(
        leftA: Float, topA: Float, rightA: Float, bottomA: Float,
        leftB: Float, topB: Float, rightB: Float, bottomB: Float
    ): Boolean = rightA > leftB && leftA < rightB && bottomA > topB && topA < bottomB

    /**
     * 重叠减弱时的强度倍率：未开启或未检测到重叠时为 1；开启且重叠时取
     * [attenuation]（收进 0.1..1）。纯函数，供绘制层与 JVM 测试共用。
     */
    fun overlapGain(soften: Boolean, overlap: Boolean, attenuation: Float): Float =
        if (soften && overlap) attenuation.coerceIn(0.1f, 1f) else 1f

    /** 深色主题下投影加强、接触带减弱；浅色系主题原样返回。 */
    fun adaptAlphas(opacity: Float, contactOpacity: Float, dark: Boolean, adaptToTheme: Boolean): FloatArray {
        if (!adaptToTheme || !dark) return floatArrayOf(opacity, contactOpacity)
        return floatArrayOf(min(opacity * 1.35f, 0.6f), contactOpacity * 0.6f)
    }

    /**
     * 一组圆角在同一参数下的双层纹理工作集（按缓存键去重）字节数。
     *
     * 只验证单张上限会漏掉"同屏多种圆角合计超预算"的循环抖动：超过 [MAX_CACHE_BYTES] 后
     * LRU 每帧淘汰并重建，绘制路径退化为每次全量烘焙。预算回归测试必须按真实工作集断言。
     */
    fun workingSetBytes(radiiPx: FloatArray, blurRadiusPx: Float, density: Float): Long {
        val seen = HashSet<Long>(radiiPx.size * 4)
        var total = 0L
        for (radius in radiiPx) {
            for (layer in Layer.entries) {
                val metrics = textureMetrics(layer, radius, blurRadiusPx, density)
                if (metrics.textureSidePx <= 0) continue
                // 环境层模糊为 0 时不生成位图（只留接触带），与缓存路径保持一致。
                if (layer == Layer.CAST && metrics.textureBlurPx <= 0) continue
                if (seen.add(metrics.key)) total += metrics.bytes
            }
        }
        return total
    }
}
