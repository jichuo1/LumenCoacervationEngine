package com.lumen.coacervation.engine.material

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 悬浮胶囊的"玻璃透镜"采样策略：纯标量/像素数组运算，不碰 android.graphics，可在 JVM 单测里跑。
 *
 * 悬浮栏下方的实时内容先按 [sampleScale] 缩到很小的位图（几千像素量级），做一次预乘空间的
 * 三重盒式模糊，再由 [remap] 按透镜函数重采样成表面尺寸的纹理：
 * - **中心凸透放大**：dest 归一化坐标 u ∈ [-1,1] 采样自 u·(1 − k(1 − u²))，中心放大、边缘钉住；
 * - **边沿折射**：|u| 越过 [RIM_START] 后采样点被推出表面、进入 [marginPx] 的外沿采样区，
 *   把胶囊外侧的内容"折"进边沿——这是玻璃厚边的特征，也是与单纯模糊透明最大的差别。
 *
 * 所有函数都是 C¹ 连续的，透镜内没有任何分段折点；边界像素用 CLAMP。
 */
internal object LensRefractionPolicy {
    /** 采样位图的像素预算：悬浮栏区域（含外沿）缩放后不超过这么多像素。 */
    const val MAX_SAMPLE_PIXELS = 24_000
    const val MIN_SCALE = 3
    /** 外沿采样区宽度（dp）：边沿折射最多能从表面外这么远处取样。 */
    const val MARGIN_DP = 14f
    /** 采样位图上的模糊半径（dp，除以 scale 后取整）。 */
    const val BLUR_DP = 10f
    /** 长轴（宽）/短轴（高）方向的中心放大系数：短轴更"厚"，放大更明显。 */
    const val CENTER_GAIN_X = 0.06f
    const val CENTER_GAIN_Y = 0.16f
    /** 边沿折射起点（归一化 |u|）与满额外推量（以半轴长为单位）。 */
    const val RIM_START = 0.62f
    const val RIM_PUSH_X = 0.10f
    const val RIM_PUSH_Y = 0.34f
    /**
     * 磨砂提亮（预乘空间）：真实磨砂会把环境光散进表面，暗色内容下的映射才肉眼可辨；
     * 底光按 alpha 缩放，保持预乘一致性。
     */
    const val LUMINANCE_GAIN = 1.08f
    const val LUMINANCE_BIAS = 10f

    /** 整数缩放因子：表面越大缩得越狠，位图像素数恒有界。 */
    fun sampleScale(widthPx: Int, heightPx: Int): Int {
        if (widthPx <= 0 || heightPx <= 0) return MIN_SCALE
        val needed = sqrt(widthPx.toDouble() * heightPx / MAX_SAMPLE_PIXELS)
        return ceil(needed).toInt().coerceAtLeast(MIN_SCALE)
    }

    fun marginPx(density: Float): Int = (MARGIN_DP * density).roundToInt().coerceAtLeast(1)

    /** 节点输入被裁到输出区域时，取两倍最大位移作收敛宽度，至少留住一半边缘距离。 */
    fun nodeTravelBudget(size: Float, centerGain: Float, rimPush: Float): Float =
        (size * maxOf(abs(rimPush), abs(centerGain) * 0.385f)).coerceAtLeast(1f)

    fun nodeSampleCoordinate(position: Float, size: Float, centerGain: Float, rimPush: Float): Float {
        val raw = (lens(position / size * 2f - 1f, centerGain, rimPush) + 1f) * size * 0.5f
        val room = minOf(position - 0.5f, size - 0.5f - position).coerceAtLeast(0f)
        val reach = (room / nodeTravelBudget(size, centerGain, rimPush)).coerceIn(0f, 1f)
        return position + (raw - position) * reach
    }

    fun blurRadius(scale: Int, density: Float): Int =
        (BLUR_DP * density / scale.coerceAtLeast(1)).roundToInt().coerceIn(1, 32)

    /** 单轴透镜：dest 归一化坐标 → src 归一化坐标（可越出 ±1 进入外沿）。 */
    fun lens(u: Float, centerGain: Float, rimPush: Float): Float {
        val clamped = u.coerceIn(-1f, 1f)
        val magnified = clamped * (1f - centerGain * (1f - clamped * clamped))
        val rim = smooth((abs(clamped) - RIM_START) / (1f - RIM_START))
        val push = rimPush * rim * rim
        return magnified + (if (clamped < 0f) -push else push)
    }

    /**
     * 把含外沿的模糊采样 [source]（sw × sh，外沿 [margin] 像素）按透镜重采样到 [out]（dw × dh）。
     * dest 满幅对应 source 的内区 [margin, sw − margin] × [margin, sh − margin]。双线性、CLAMP。
     */
    fun remap(source: IntArray, sw: Int, sh: Int, margin: Int, out: IntArray, dw: Int, dh: Int, strength: Float = 1f) {
        require(sw > 0 && sh > 0 && source.size >= sw * sh)
        require(dw > 0 && dh > 0 && out.size >= dw * dh)
        val innerW = (sw - 2 * margin).coerceAtLeast(1).toFloat()
        val innerH = (sh - 2 * margin).coerceAtLeast(1).toFloat()
        val maxX = (sw - 1).toFloat()
        val maxY = (sh - 1).toFloat()
        // 列映射只依赖 x：原来在内层逐像素重算 dw×dh 次 lens()，现在每列算一次存成表
        // （dw≈88 时 7744 次 → 88 次）。纯提取，输出逐位不变。
        val columnLeft = IntArray(dw)
        val columnRight = IntArray(dw)
        val columnFrac = FloatArray(dw)
        for (x in 0 until dw) {
            val u = (x + 0.5f) / dw * 2f - 1f
            val sx = ((lens(u, CENTER_GAIN_X * strength, RIM_PUSH_X * strength) + 1f) * 0.5f * innerW + margin - 0.5f).coerceIn(0f, maxX)
            val x0 = sx.toInt().coerceIn(0, sw - 1)
            columnLeft[x] = x0
            columnRight[x] = (x0 + 1).coerceAtMost(sw - 1)
            columnFrac[x] = sx - x0
        }
        for (y in 0 until dh) {
            val v = (y + 0.5f) / dh * 2f - 1f
            val sy = ((lens(v, CENTER_GAIN_Y * strength, RIM_PUSH_Y * strength) + 1f) * 0.5f * innerH + margin - 0.5f).coerceIn(0f, maxY)
            val y0 = sy.toInt().coerceIn(0, sh - 1)
            val y1 = (y0 + 1).coerceAtMost(sh - 1)
            val fy = sy - y0
            val row = y * dw
            val rowTop = y0 * sw
            val rowBottom = y1 * sw
            for (x in 0 until dw) {
                val x0 = columnLeft[x]
                val x1 = columnRight[x]
                val c00 = source[rowTop + x0]
                val c10 = source[rowTop + x1]
                val c01 = source[rowBottom + x0]
                val c11 = source[rowBottom + x1]
                // 四邻同色时插值结果必然等于该色：四个权重之和与 1 的浮点误差乘上 ≤255 的通道值
                // 远小于 0.5，加 0.5 截断后落回原值（BitExactKernelsTest 穷举守住）。
                if (c00 == c10 && c00 == c01 && c00 == c11) {
                    out[row + x] = c00
                    continue
                }
                out[row + x] = bilinear(c00, c10, c01, c11, columnFrac[x], fy)
            }
        }
    }

    /** 非预乘 ARGB → 预乘（模糊必须在预乘空间做，否则透明像素把黑色渗进邻域）。 */
    fun premultiply(pixels: IntArray) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            if (a == 255) continue
            if (a == 0) { pixels[i] = 0; continue }
            val r = ((c ushr 16) and 255) * a / 255
            val g = ((c ushr 8) and 255) * a / 255
            val b = (c and 255) * a / 255
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** 预乘 RGB 提亮：增益拉开明暗差，底光让纯暗区也带一点磨砂灰，输入输出同为预乘。 */
    fun illuminate(pixels: IntArray) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            if (a == 0) continue
            val bias = LUMINANCE_BIAS * a / 255f
            val r = ((c ushr 16 and 255) * LUMINANCE_GAIN + bias).toInt().coerceAtMost(255)
            val g = ((c ushr 8 and 255) * LUMINANCE_GAIN + bias).toInt().coerceAtMost(255)
            val b = ((c and 255) * LUMINANCE_GAIN + bias).toInt().coerceAtMost(255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    fun unpremultiply(pixels: IntArray) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            if (a == 255 || a == 0) continue
            val r = (((c ushr 16) and 255) * 255 / a).coerceAtMost(255)
            val g = (((c ushr 8) and 255) * 255 / a).coerceAtMost(255)
            val b = ((c and 255) * 255 / a).coerceAtMost(255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun smooth(t: Float): Float {
        val f = t.coerceIn(0f, 1f)
        return f * f * (3f - 2f * f)
    }

    /**
     * 四个通道手工展开：原写法每像素调 4 次 9 参数的 `channel()`，ART（含 R8 后）不内联它，
     * 重映射因此慢约 2–4 倍（2026-10-25 API 35 模拟器实测）。权重与逐通道的浮点运算顺序不变，
     * 输出逐位相同。
     */
    private fun bilinear(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Int {
        val w00 = (1f - fx) * (1f - fy)
        val w10 = fx * (1f - fy)
        val w01 = (1f - fx) * fy
        val w11 = fx * fy
        val a = (c00 ushr 24) * w00 + (c10 ushr 24) * w10 + (c01 ushr 24) * w01 + (c11 ushr 24) * w11
        val r = ((c00 ushr 16) and 255) * w00 + ((c10 ushr 16) and 255) * w10 +
            ((c01 ushr 16) and 255) * w01 + ((c11 ushr 16) and 255) * w11
        val g = ((c00 ushr 8) and 255) * w00 + ((c10 ushr 8) and 255) * w10 +
            ((c01 ushr 8) and 255) * w01 + ((c11 ushr 8) and 255) * w11
        val b = (c00 and 255) * w00 + (c10 and 255) * w10 + (c01 and 255) * w01 + (c11 and 255) * w11
        return (channelByte(a) shl 24) or (channelByte(r) shl 16) or (channelByte(g) shl 8) or channelByte(b)
    }

    private fun channelByte(value: Float): Int {
        val rounded = (value + 0.5f).toInt()
        return if (rounded < 0) 0 else if (rounded > 255) 255 else rounded
    }
}
