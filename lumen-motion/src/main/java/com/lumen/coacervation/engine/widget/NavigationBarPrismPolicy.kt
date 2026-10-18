package com.lumen.coacervation.engine.widget

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/** 棱镜选中框的纯函数部分：速度挤压、速度平滑、遮罩预算与静止判定，全部可在 JVM 上测试。 */
internal object NavigationBarPrismPolicy {
    /** 选中色遮罩（ALPHA_8）的像素预算：1 字节 / 像素，512 KiB。超出时按比例降采样。 */
    const val MAX_MASK_PIXELS = 512 * 1024
    /** 参考库：速度先除以 10，再按横向 0.75、纵向 0.25 换算，并各自限制在 ±0.2。 */
    const val VELOCITY_DIVISOR = 10f
    const val SQUASH_X = 0.75f
    const val SQUASH_Y = 0.25f
    const val SQUASH_LIMIT = 0.2f
    /** 速度平滑的时间常数（秒）：替代参考库的速度弹簧（阻尼比 0.5、刚度 300，约 60ms 量级）。 */
    const val VELOCITY_TIME_CONSTANT = 0.06f
    /** 小于这些值视为静止，不再续帧。 */
    const val SETTLED_SCALE = 0.001f
    const val SETTLED_VELOCITY = 0.01f
    const val SHADOW_RADIUS_DP = 24f
    const val SHADOW_OFFSET_DP = 4f
    const val INNER_SHADOW_RADIUS_DP = 8f
    const val HIGHLIGHT_WIDTH_DP = 0.5f

    /** 选中框阴影纹理（ALPHA_8）的像素预算：256 KiB。 */
    const val MAX_SHADOW_PIXELS = 256 * 1024

    /** BlurMaskFilter 半径对应的可见外扩：σ = 0.57735·r + 0.5（Skia / HWUI 的换算），取 3σ。 */
    fun shadowExtent(blurRadiusPx: Float): Float = 3f * (0.57735f * blurRadiusPx.coerceAtLeast(0f) + 0.5f)

    fun shadowScale(width: Int, height: Int): Float = scaleFor(width, height, MAX_SHADOW_PIXELS)

    /** 遮罩缩放：整栏面积不超过 [MAX_MASK_PIXELS] 时为 1，否则按面积开方等比缩小。 */
    fun maskScale(width: Int, height: Int): Float = scaleFor(width, height, MAX_MASK_PIXELS)

    private fun scaleFor(width: Int, height: Int, budget: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        val area = width.toLong() * height
        if (area <= budget) return 1f
        return sqrt(budget.toDouble() / area).toFloat()
    }

    fun maskSize(length: Int, scale: Float): Int = floor(length * scale).toInt().coerceAtLeast(1)

    /** 横向缩放系数（÷，速度向前时变宽）与纵向缩放系数（×）。[velocity] 以"可选范围/秒"计。 */
    fun squashX(velocity: Float, deformation: Float): Float {
        val v = velocity / VELOCITY_DIVISOR
        return 1f / (1f - (v * SQUASH_X * deformation).coerceIn(-SQUASH_LIMIT, SQUASH_LIMIT))
    }

    fun squashY(velocity: Float, deformation: Float): Float {
        val v = velocity / VELOCITY_DIVISOR
        return 1f - (v * SQUASH_Y * deformation).coerceIn(-SQUASH_LIMIT, SQUASH_LIMIT)
    }

    /** 以槽位/秒计的位移速度换算为"可选范围/秒"（与参考库一致，范围为 count − 1，最少 1）。 */
    fun rangeVelocity(slotsPerSecond: Float, count: Int): Float =
        if (slotsPerSecond.isFinite()) slotsPerSecond / (count - 1).coerceAtLeast(1) else 0f

    /** 一阶平滑：[dtSeconds] 越长越贴近目标；dt ≤ 0 不变。 */
    fun smooth(current: Float, target: Float, dtSeconds: Float): Float {
        if (dtSeconds <= 0f || !dtSeconds.isFinite()) return current
        val k = 1f - exp(-dtSeconds / VELOCITY_TIME_CONSTANT)
        return current + (target - current) * k
    }

    fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    fun settled(scaleX: Float, scaleY: Float, targetScale: Float, velocity: Float, scaleVelocityX: Float, scaleVelocityY: Float): Boolean =
        abs(scaleX - targetScale) < SETTLED_SCALE && abs(scaleY - targetScale) < SETTLED_SCALE &&
            abs(velocity) < SETTLED_VELOCITY && abs(scaleVelocityX) < SETTLED_SCALE && abs(scaleVelocityY) < SETTLED_SCALE
}
