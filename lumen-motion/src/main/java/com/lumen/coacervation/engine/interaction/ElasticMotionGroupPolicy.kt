package com.lumen.coacervation.engine.interaction

/**
 * Groups an elastic press into the visual unit that must move together,
 * clamps its travel inside the parent layer, and caps stretch in pixels.
 */

internal data class ElasticGroupNode(
    val hasSurface: Boolean,
    val fillsWindow: Boolean,
    val containerOnly: Boolean,
    val childCount: Int
)

internal object ElasticMotionGroupPolicy {
    const val MAX_PROMOTION_STEPS = 6
    const val STRETCH_CAP_DP = 2.5f

    /** path[0] = hit view, path[i] = i-th ancestor. Returns promotion depth. */
    fun promotionDepth(path: List<ElasticGroupNode>): Int {
        if (path.isEmpty()) return 0
        var depth = 0
        while (depth < path.size - 1 && depth < MAX_PROMOTION_STEPS) {
            val current = path[depth]
            val parent = path[depth + 1]
            if (parent.fillsWindow || parent.containerOnly) break
            val parentUnifies = parent.hasSurface && parent.childCount > 1
            if (current.hasSurface && !parentUnifies) break
            depth++
        }
        return depth
    }

    /**
     * True when the child hugs its parent's inner box on all four sides within [slack],
     * i.e. the parent adds no travel room for a clamped drag (the badge frame around
     * the GitHub icon wraps its 48dp child with zero gap on every side).
     */
    fun isTightWrap(gapLeft: Float, gapTop: Float, gapRight: Float, gapBottom: Float, slack: Float): Boolean =
        slack.isFinite() && slack >= 0f &&
            gapLeft <= slack && gapTop <= slack && gapRight <= slack && gapBottom <= slack

    /**
     * Clamp a drag offset inside the parent inner edges (gaps in px).
     *
     * [minTravel] 是每个方向的最低行程预算。WRAP_CONTENT 父容器里**最后一个子元素**的
     * 尾边间隙恒为 0（父高正好包住内容：`parent.height - paddingBottom - child.bottom == 0`），
     * 不补预算会把该方向的拖动整体钳死——真机实证：「兼容」卡只能上/左/右形变、
     * 向下纹丝不动，而它上面的「外观」卡（后面还有兄弟）四向都正常。弹窗与卡片的真实
     * 内边距（24dp/18dp 起）都大于典型 limit，补预算对它们不生效。
     */
    fun clampToParent(dx: Float, dy: Float, gapLeft: Float, gapTop: Float,
                      gapRight: Float, gapBottom: Float, out: ElasticVector,
                      minTravel: Float = 0f) {
        val budget = if (minTravel.isFinite()) minTravel.coerceAtLeast(0f) else 0f
        fun edge(gap: Float) = maxOf(gap.coerceAtLeast(0f), budget)
        val minX = -edge(gapLeft)
        val maxX = edge(gapRight)
        val minY = -edge(gapTop)
        val maxY = edge(gapBottom)
        out.x = if (dx.isFinite()) dx.coerceIn(minX, maxX) else 0f
        out.y = if (dy.isFinite()) dy.coerceIn(minY, maxY) else 0f
    }

    /**
     * 形变组到同层兄弟的最近间距（px），按左、上、右、下写入 [out]；该方向没有兄弟时为 +∞。
     *
     * 只算"在那个方向上、且在另一轴上有重叠"的兄弟：网格里右边那张卡算右邻，右下角那张不算。
     * 与形变组本身相交的兄弟（叠放、浮层）不在任何方向上，忽略。坐标都是父容器坐标（含平移）。
     * [siblings] 每 4 个数一组：left, top, right, bottom。
     */
    fun neighborGaps(left: Float, top: Float, right: Float, bottom: Float, siblings: FloatArray, out: FloatArray) {
        out.fill(Float.POSITIVE_INFINITY)
        var i = 0
        while (i + 3 < siblings.size) {
            val sl = siblings[i]
            val st = siblings[i + 1]
            val sr = siblings[i + 2]
            val sb = siblings[i + 3]
            i += 4
            if (!sl.isFinite() || !st.isFinite() || !sr.isFinite() || !sb.isFinite() || sr <= sl || sb <= st) continue
            val overlapsVertically = st < bottom && sb > top
            val overlapsHorizontally = sl < right && sr > left
            if (overlapsVertically && overlapsHorizontally) continue
            if (overlapsVertically) {
                if (sl >= right) out[2] = minOf(out[2], sl - right)
                if (sr <= left) out[0] = minOf(out[0], left - sr)
            } else if (overlapsHorizontally) {
                if (st >= bottom) out[3] = minOf(out[3], st - bottom)
                if (sb <= top) out[1] = minOf(out[1], top - sb)
            }
        }
    }

    /**
     * 一个方向的行程上限。
     *
     * 朝父容器内缘：按 max(间隙, [minTravel]) 放行（WRAP_CONTENT 父容器尾边间隙为 0 的老问题，见 [clampToParent]）。
     * 朝同层兄弟：不越过兄弟，并留出 [marginPx]（拉伸会让形变组再长一点）——网格、瀑布流、横向轮播里
     * 卡片之间只隔 8～12dp，按父容器算行程会让卡片钻到邻居底下，半透明玻璃叠在一起。
     */
    fun travelBound(parentGap: Float, neighborGap: Float, minTravel: Float, marginPx: Float,
                    policy: ElasticTravelPolicy = ElasticTravelPolicy.AVOID_NEIGHBORS): Float {
        val budget = if (minTravel.isFinite()) minTravel.coerceAtLeast(0f) else 0f
        val parent = maxOf(if (parentGap.isFinite()) parentGap.coerceAtLeast(0f) else 0f, budget)
        if (policy == ElasticTravelPolicy.PARENT_BOUNDS) return parent
        if (neighborGap.isNaN() || neighborGap == Float.POSITIVE_INFINITY) return parent
        val margin = if (marginPx.isFinite()) marginPx.coerceAtLeast(0f) else 0f
        return minOf(parent, (neighborGap - margin).coerceAtLeast(0f))
    }

    /** Cap proportional stretch to capPx absolute growth per axis. */
    fun cappedScale(base: Float, sizePx: Int, capPx: Float): Float {
        if (!base.isFinite() || sizePx <= 0 || !capPx.isFinite() || capPx < 0f) return 1f
        val maxScale = 1f + capPx / sizePx
        return if (base > maxScale) maxScale else base
    }
}
