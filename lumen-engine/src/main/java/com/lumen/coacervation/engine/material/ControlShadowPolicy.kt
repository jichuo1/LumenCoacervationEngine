package com.lumen.coacervation.engine.material

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 控件软阴影的纯几何与预算策略（不依赖 android.graphics，JVM 可测）。
 *
 * 投射层纹理是"白色不透明圆角矩形 + 盒式三趟近似高斯模糊"的九宫格位图：
 * 颜色与强度在绘制时由 ColorFilter/alpha 施加，同一份纹理服务所有颜色。
 * 尺寸全部量化到 [RADIUS_QUANTUM_PX]/[BLUR_QUANTUM_PX]，避免参数微抖制造新缓存条目。
 */
internal object ControlShadowPolicy {
    const val RADIUS_QUANTUM_PX = 4
    const val BLUR_QUANTUM_PX = 4
    const val SPREAD_QUANTUM_PX = 2

    /** 纹理可容纳的最大模糊半径（px）；与 ModernBackdropBlur 的单程窗口上限一致，
     *  更大的请求被压缩到此值，阴影依然成立只是衰减略短。 */
    const val MAX_BLUR_TEXTURE_PX = 32
    const val CORE_STRAIGHT_PX = 16
    const val MAX_TEXTURE_SIDE_PX = 384
    const val MAX_TEXTURE_BYTES = MAX_TEXTURE_SIDE_PX * MAX_TEXTURE_SIDE_PX * 4
    const val MAX_CACHE_BYTES = 2 * 1024 * 1024

    /** 模糊半径 -> 纹理留白：2.5σ 之外的能量约 1%，再留 2px 抗盒式尾部。 */
    fun padPx(blurPx: Int): Int = ceil(blurPx * 2.5f).toInt() + 2

    fun quantizeRadius(radiusPx: Float): Int =
        (radiusPx / RADIUS_QUANTUM_PX).roundToInt() * RADIUS_QUANTUM_PX
    fun quantizeBlur(blurPx: Float): Int =
        (blurPx / BLUR_QUANTUM_PX).roundToInt() * BLUR_QUANTUM_PX
    fun quantizeSpread(spreadPx: Float): Int =
        (spreadPx / SPREAD_QUANTUM_PX).roundToInt() * SPREAD_QUANTUM_PX

    data class TextureMetrics(
        val blurPx: Int,
        val padPx: Int,
        val cornerPx: Int,
        val sidePx: Int
    ) {
        val bytes: Int get() = sidePx * sidePx * 4
    }

    /**
     * 纹理尺寸：side = 2*pad + 2*corner + 直边芯。圆角超过预算时压缩纹理里的角（绘制时角部按比例
     * 缩放，软影的曲率误差不影响阅读）；模糊超过 [MAX_BLUR_TEXTURE_PX] 时压缩衰减长度。
     */
    fun textureMetrics(radiusPx: Float, blurRadiusPx: Float): TextureMetrics {
        val blur = quantizeBlur(blurRadiusPx).coerceIn(0, MAX_BLUR_TEXTURE_PX)
        val pad = padPx(blur)
        val maxCorner = (MAX_TEXTURE_SIDE_PX - 2 * pad - CORE_STRAIGHT_PX) / 2
        val corner = min(quantizeRadius(radiusPx).coerceAtLeast(0), maxOf(maxCorner, 4))
        val side = 2 * pad + 2 * corner + CORE_STRAIGHT_PX
        require(side <= MAX_TEXTURE_SIDE_PX) { "shadow texture $side exceeds $MAX_TEXTURE_SIDE_PX" }
        return TextureMetrics(blur, pad, corner, side)
    }

    /** 阴影外扩量（px）：控件边界之外需要这么多空间才能完整画出阴影。 */
    fun outsetPx(blurRadiusPx: Float, spreadPx: Float, offsetYPx: Float): Int {
        val pad = padPx(quantizeBlur(blurRadiusPx).coerceIn(0, MAX_BLUR_TEXTURE_PX))
        return (pad + max(0f, spreadPx) + max(0f, offsetYPx)).roundToInt()
    }

    /** 深色主题下投射加强、接触环减弱；浅色系主题原样返回。 */
    fun adaptAlphas(opacity: Float, contactOpacity: Float, dark: Boolean, adaptToTheme: Boolean): FloatArray {
        if (!adaptToTheme || !dark) return floatArrayOf(opacity, contactOpacity)
        return floatArrayOf(min(opacity * 1.35f, 0.6f), contactOpacity * 0.6f)
    }

    /** 接触环两道描边：贴边的细线 + 略宽的低透明晕，两道都是常量几何，不需要位图。 */
    fun contactWidths(density: Float): FloatArray =
        floatArrayOf(max(density * 0.75f, 1f), max(density * 2.25f, 2f))

    fun contactAlphas(contactOpacity: Float): FloatArray =
        floatArrayOf(contactOpacity, contactOpacity * 0.4f)
}
