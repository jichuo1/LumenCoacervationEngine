package com.lumen.coacervation.engine.interaction

/**
 * 形变组只有一段可见时（展开后高过视口的首级手风琴），按可见段形变。
 *
 * 拉伸与收缩是围绕整组 pivot 的比例缩放，拉伸再按整组尺寸封顶
 * （[ElasticMotionGroupPolicy.STRETCH_CAP_DP]）。几千像素高的卡片 pivot 远在屏幕外：
 * 按压收缩 1.6% 把标题整体推向屏幕外的中心近百像素，竖向拉伸却被整组高度压到接近 1，
 * 看得见的那一段几乎没有形变；拉伸从按压姿态恢复时，标题还会逆着手指走。
 *
 * 这里让可见段像一张同尺寸的独立卡片那样形变：缩放按可见段长度封顶，等效 pivot 取可见段
 * 中心（经平移补偿实现，不写 View.pivot）。可见段之外的部分仍在原视口裁剪之外。
 * 整组可见时逐位返回原值。
 */
internal object ElasticVisibleExtent {
    /**
     * 整组应写入的相对缩放。[extent] 是该轴可见段长度（px）；不小于 [size] 或无效时
     * 与 [ElasticMotionGroupPolicy.cappedScale] 完全一致。
     */
    fun scale(base: Float, size: Int, extent: Float, capPx: Float): Float {
        if (!extent.isFinite() || extent <= 0f || extent >= size) {
            return ElasticMotionGroupPolicy.cappedScale(base, size, capPx)
        }
        if (!base.isFinite() || !capPx.isFinite() || capPx < 0f) return 1f
        val maxScale = 1f + capPx / extent
        return if (base > maxScale) maxScale else base
    }

    /** 相对真实 pivot 的平移补偿：令 [scale] 看起来围绕 [effectivePivot] 发生。 */
    fun pivotShift(effectivePivot: Float, realPivot: Float, scale: Float): Float =
        if (effectivePivot == realPivot || !effectivePivot.isFinite() || !scale.isFinite()) 0f
        else (effectivePivot - realPivot) * (1f - scale)

    /**
     * 被视口或窗口藏起 [hidden] px 的那条边，在任何可达姿态下都回不到可见段里：
     * 位移按 2 × [limit] 计（回抓与回弹过冲都在其内），收缩按按压过冲 1.1 加满额交叉收缩计，
     * 作用距离是等效 pivot（可见段中心）到这条边。部分露出的普通行不满足，保持原有裁剪契约。
     */
    fun unreachable(hidden: Float, extent: Float, limit: Float, deformation: Float): Boolean {
        if (!hidden.isFinite() || hidden <= 0f || !extent.isFinite() || extent <= 0f ||
            !limit.isFinite() || limit < 0f || !deformation.isFinite() || deformation < 0f) return false
        val shrink = (ElasticMotionPolicy.PRESS_DEPTH * 1.1f + CROSS_SHRINK) * deformation
        if (shrink >= 1f) return false
        return hidden * (1f - shrink) > 2f * limit + extent / 2f * shrink
    }

    /**
     * 不可达的被藏边早已越过某道保留裁剪（[gap] < 0）：那道裁剪之外的形变始终看不见，不再约束。
     * 该边仍在其内的裁剪（[openGap]，例如被窗口切掉的整宽行自己的视口）照样保留。
     */
    fun retainedGap(gap: Float, openGap: Float, unreachableEdge: Boolean): Float =
        if (unreachableEdge && gap < 0f) openGap else gap

    /**
     * 朝不可达被藏边的行程：那一侧的邻居与父容器内缘都在视口之外，按满额 [limit] 放行；
     * 原本更宽的边界（PARENT_BOUNDS 下的大间隙）保持。其余情况原样返回。
     */
    fun travelBound(bound: Float, unreachableEdge: Boolean, limit: Float): Float =
        if (unreachableEdge && limit.isFinite() && limit > bound) limit else bound

    /** [ElasticMotionPolicy.scale] 的满额交叉收缩。 */
    private const val CROSS_SHRINK = .009f
}
