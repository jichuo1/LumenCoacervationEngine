package com.lumen.coacervation.engine.motion.expansion

/** 嵌套区域先扣子级，再收父级；每段高度只由一个控制器计入共享祖先。 */
internal object NestedExpansionPolicy {
    fun ownShrink(height: Float, descendantShrink: Float, progress: Float): Float =
        (height - descendantShrink).coerceAtLeast(0f) * (1f - progress.coerceAtLeast(0f))

    /** 子级引起的前序行收缩必须先进入自然行顶，再做父级的牌堆折叠。 */
    fun rowTop(index: Int, layoutTop: Float, precedingOffset: Float, progress: Float, peek: Float): Float =
        ExpansionMotionPolicy.rowFoldedTop(index, layoutTop + precedingOffset, progress, peek)

    fun scrollPosition(
        shrink: Float,
        startShrink: Float,
        endShrink: Float,
        startScroll: Int,
        endScroll: Int
    ): Float {
        val distance = endShrink - startShrink
        if (kotlin.math.abs(distance) < 0.001f) return startScroll.toFloat()
        val fraction = ((shrink - startShrink) / distance).coerceIn(0f, 1f)
        return startScroll + (endScroll - startScroll) * fraction
    }
}

/**
 * 手风琴在不同排布里怎样把"卡片变矮多少"传给周围（适配标准 §14.3）。
 *
 * 来源工程的分节都在竖向 `LinearLayout` 里：下面的兄弟平移整段收缩量、上面每层 wrap 祖先一起变矮同一个量。
 * 放进网格或横排时这不成立：一行的高度是这一行最高那格的高度，卡片变矮多少，行只矮
 * `max(E, m) - max(E - s, m)`（E = 卡片展开高度，m = 同行其他格的最高高度，s = 卡片当前收缩量）——
 * 同行有更高的格子时，下面的行一动不动。
 */
internal object ExpansionFollowPolicy {
    /** 一个"行"容器（横排、网格）把卡片的收缩量换算成行的收缩量。 */
    fun rowShrink(expandedHeight: Float, othersMax: Float, shrink: Float): Float {
        if (!expandedHeight.isFinite() || expandedHeight <= 0f || !shrink.isFinite()) return 0f
        val s = shrink.coerceIn(0f, expandedHeight)
        val m = if (othersMax.isFinite()) othersMax.coerceAtLeast(0f) else 0f
        return maxOf(expandedHeight, m) - maxOf(expandedHeight - s, m)
    }

    /** 在节点正下方（顶边不高于节点底边 1px 以内）。 */
    fun isBelow(top: Float, nodeBottom: Float): Boolean = top >= nodeBottom - 1f

    /** 与节点在同一列（横向有重叠）。瀑布流、约束链里只有同一列的会被推下去。 */
    fun sharesColumn(left: Float, right: Float, nodeLeft: Float, nodeRight: Float): Boolean =
        left < nodeRight && right > nodeLeft

    /** 与节点在同一行（纵向有重叠）：网格里同行格子的高度决定行高。 */
    fun sharesRow(top: Float, bottom: Float, nodeTop: Float, nodeBottom: Float): Boolean =
        top < nodeBottom && bottom > nodeTop
}
