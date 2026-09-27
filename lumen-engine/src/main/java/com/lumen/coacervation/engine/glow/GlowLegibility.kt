package com.lumen.coacervation.engine.glow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 悬浮表面下方内容的一次采样（见 [GlowContentProbe]）。
 *
 * @property luma 感知亮度均值：sRGB **编码域**的 Rec.709 加权（0..1）。窗口合成就发生在编码域，
 *   在这里混合着色才与屏幕上真正看到的一致。
 * @property busyness 细节强度：相邻采样点亮度差的均值（0..1）。文字笔画、图标边缘越多越高。
 */
public data class GlowContentSample(val luma: Float, val busyness: Float)

/**
 * 悬浮表面的可读性补偿量，由 [GlowLegibilityPolicy.target] 从采样算出。
 *
 * @property boost 0..1：表面着色加厚、前景同向加强、标签加光晕。
 * @property edgeDefinition 0..1：下方偏亮时加深边缘环，让浅色表面从亮背景里分出来。
 */
public data class GlowLegibility(val boost: Float, val edgeDefinition: Float) {
    init {
        require(boost in 0f..1f && edgeDefinition in 0f..1f)
    }

    companion object {
        val NEUTRAL = GlowLegibility(0f, 0f)
    }
}

/**
 * 引擎给出的悬浮表面光学参数。可读性计算只依赖这几个数，不依赖渲染实现。
 *
 * @property surfaceColor 着色色（不含 alpha）。
 * @property baseTintAlpha 常态着色不透明度。
 * @property maxTintAlpha 加厚上限。
 * @property seeThrough 真实下层内容**直透**的比例（0..1）：玻璃层不透明度之外的那部分。
 */
public data class GlowSurfaceOptics(
    val surfaceColor: Int,
    val baseTintAlpha: Float,
    val maxTintAlpha: Float,
    val seeThrough: Float
)

/**
 * 悬浮栏可读性的纯策略（2026-09-23 悬浮栏可读性改造 A 期）。
 *
 * 两个独立的失效模式，各自一条补偿：
 * - **对比度不足**：下方内容整体与前景同明暗（浅色主题下的深色壁纸、深色主题下的亮图）。
 *   求使前景对比度达到 [TARGET_CONTRAST] 的最小着色不透明度，折算成 boost。
 * - **细节透字**：下方文字笔画经直透比例漏进表面。按"细节 × 直透 × (1 − 着色)"估计漏出量，
 *   超过阈值按比例加厚——这一项不看平均亮度，平均亮度正常的密集文字同样会串字。
 *
 * 输出量化到 [QUANTUM]、再经 [settle] 的迟滞，采样噪声不会让表面来回闪。
 */
public object GlowLegibilityPolicy {
    /** WCAG 正文对比度。底栏标签是 12sp 正文，按正文算。 */
    const val TARGET_CONTRAST = 4.5f
    const val QUANTUM = 1f / 16f
    /** 必须大于一个量化步长，否则采样在步长边界抖动时会逐帧翻转。 */
    const val HYSTERESIS = 0.1f

    // 漏字估计的线性区间：低于 LOW 视为干净，高于 HIGH 按 DETAIL_BOOST_CAP 封顶。
    // 数值按真机采样标定（见 development_experience.md 2026-09-23 可读性条目）。
    const val LEAK_LOW = 0.004f
    const val LEAK_HIGH = 0.02f
    const val DETAIL_BOOST_CAP = 0.6f

    // 边缘加深只在下方偏亮时出现；深色表面本身就与亮背景分得开，只给一小份。
    const val BRIGHT_LOW = 0.62f
    const val BRIGHT_HIGH = 0.9f
    const val DARK_SURFACE_EDGE_SHARE = 0.35f

    /** 前景同向加强的最大混合量（向黑或向白）。 */
    const val FOREGROUND_PUSH = 0.6f
    /** 强调色前景只推一小份：推多了色相发灰，选中态就认不出来了。 */
    const val ACCENT_FOREGROUND_PUSH = 0.3f

    fun target(sample: GlowContentSample, foregroundColor: Int, optics: GlowSurfaceOptics): GlowLegibility {
        val foreground = relativeLuminance(foregroundColor)
        val surface = encodedLuma(optics.surfaceColor)
        val content = sample.luma.coerceIn(0f, 1f)
        val base = optics.baseTintAlpha.coerceIn(0f, 1f)
        val ceiling = optics.maxTintAlpha.coerceIn(base, 1f)

        fun contrastAt(tint: Float): Float =
            contrast(foreground, linearize(tint * surface + (1f - tint) * content))

        val contrastBoost = when {
            ceiling <= base -> 0f
            contrastAt(base) >= TARGET_CONTRAST -> 0f
            contrastAt(ceiling) < TARGET_CONTRAST -> 1f
            else -> {
                // 着色越厚背景越接近表面色；在 [base, ceiling] 上对比度单调，二分即可。
                var low = base
                var high = ceiling
                repeat(12) {
                    val mid = (low + high) * 0.5f
                    if (contrastAt(mid) >= TARGET_CONTRAST) high = mid else low = mid
                }
                (high - base) / (ceiling - base)
            }
        }
        val leak = sample.busyness.coerceIn(0f, 1f) * optics.seeThrough.coerceIn(0f, 1f) * (1f - base)
        val detailBoost = smoothstep(LEAK_LOW, LEAK_HIGH, leak) * DETAIL_BOOST_CAP
        val bright = smoothstep(BRIGHT_LOW, BRIGHT_HIGH, content)
        val edge = if (surface > 0.5f) bright else bright * DARK_SURFACE_EDGE_SHARE
        return GlowLegibility(quantize(max(contrastBoost, detailBoost)), quantize(edge))
    }

    /**
     * 迟滞：新目标与当前值差不到 [HYSTERESIS] 时保持不动，返回 null。
     * 落到端点（完全撤销或封顶）不受迟滞限制——否则 0.06 的残留补偿会永远撤不掉。
     */
    fun settle(current: GlowLegibility, target: GlowLegibility): GlowLegibility? {
        if (current == target) return null
        fun moves(from: Float, to: Float) =
            abs(to - from) >= HYSTERESIS || (from != to && (to == 0f || to == 1f))
        return if (moves(current.boost, target.boost) || moves(current.edgeDefinition, target.edgeDefinition)) {
            target
        } else null
    }

    fun lerp(from: GlowLegibility, to: GlowLegibility, fraction: Float): GlowLegibility {
        val t = fraction.coerceIn(0f, 1f)
        return GlowLegibility(
            (from.boost + (to.boost - from.boost) * t).coerceIn(0f, 1f),
            (from.edgeDefinition + (to.edgeDefinition - from.edgeDefinition) * t).coerceIn(0f, 1f)
        )
    }

    /**
     * 前景同向加强：深色前景往黑推、浅色前景往白推。表面着色随 boost 朝主题方向加厚，
     * 前景朝反方向拉开，两者一起把对比度拉回来，而不是翻转整条栏的明暗。
     */
    fun foreground(color: Int, boost: Float, push: Float = FOREGROUND_PUSH): Int {
        val amount = (boost.coerceIn(0f, 1f) * push).coerceIn(0f, 1f)
        if (amount <= 0f) return color
        val toward = if (relativeLuminance(color) < 0.18f) 0 else 255
        fun mix(channel: Int) = (channel + (toward - channel) * amount).roundToInt().coerceIn(0, 255)
        return (color and ALPHA_MASK) or
            (mix(color ushr 16 and 0xFF) shl 16) or
            (mix(color ushr 8 and 0xFF) shl 8) or
            mix(color and 0xFF)
    }

    fun quantize(value: Float): Float =
        ((value.coerceIn(0f, 1f) / QUANTUM).roundToInt() * QUANTUM).coerceIn(0f, 1f)

    /** sRGB 编码域亮度（Rec.709 权重），0..1。 */
    fun encodedLuma(color: Int): Float =
        (0.2126f * (color ushr 16 and 0xFF) + 0.7152f * (color ushr 8 and 0xFF) + 0.0722f * (color and 0xFF)) / 255f

    /** WCAG 相对亮度。 */
    fun relativeLuminance(color: Int): Float =
        0.2126f * channelLinear(color ushr 16 and 0xFF) +
            0.7152f * channelLinear(color ushr 8 and 0xFF) +
            0.0722f * channelLinear(color and 0xFF)

    fun contrast(a: Float, b: Float): Float {
        val light = max(a, b)
        val dark = minOf(a, b)
        return (light + 0.05f) / (dark + 0.05f)
    }

    /** 编码域亮度近似转回线性；混合后的颜色只有亮度可用，按单通道 EOTF 近似。 */
    fun linearize(encoded: Float): Float {
        val value = encoded.coerceIn(0f, 1f)
        return if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun channelLinear(channel: Int): Float = linearize(channel / 255f)

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val ALPHA_MASK = -0x1000000
}
