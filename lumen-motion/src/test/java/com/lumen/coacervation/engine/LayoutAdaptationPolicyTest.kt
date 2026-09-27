package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.interaction.ElasticMotionGroupPolicy
import com.lumen.coacervation.engine.motion.MorphCornerPolicy
import com.lumen.coacervation.engine.motion.expansion.ExpansionFollowPolicy
import com.lumen.coacervation.engine.motion.reveal.RevealPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多种卡片尺寸与排布的适配（适配标准 §14）：只测纯几何，排布用矩形表达。 */
class LayoutAdaptationPolicyTest {

    // ---------------- §14.1 长按弹性：不钻到邻居底下 ----------------

    private fun gaps(left: Float, top: Float, right: Float, bottom: Float, vararg siblings: Float): FloatArray =
        FloatArray(4).also { ElasticMotionGroupPolicy.neighborGaps(left, top, right, bottom, siblings, it) }

    @Test fun gridNeighborsCountOnlyAlongSharedRowsAndColumns() {
        // 2×2 网格，格子 100、间距 12；按下左上那格。
        val g = gaps(0f, 0f, 100f, 100f,
            112f, 0f, 212f, 100f,     // 右邻
            0f, 112f, 100f, 212f,     // 下邻
            112f, 112f, 212f, 212f)   // 对角：不在任何方向上
        assertEquals(Float.POSITIVE_INFINITY, g[0], 0f)
        assertEquals(Float.POSITIVE_INFINITY, g[1], 0f)
        assertEquals(12f, g[2], 0f)
        assertEquals(12f, g[3], 0f)
    }

    @Test fun staggeredColumnsSeeTheNeighborThatOverlapsTheirSpan() {
        // 瀑布流：左列一张高卡，右列一张矮卡只与它的中段重叠，仍算右邻。
        val g = gaps(0f, 0f, 100f, 150f, 110f, 40f, 210f, 100f)
        assertEquals(10f, g[2], 0f)
        assertEquals(Float.POSITIVE_INFINITY, g[3], 0f)
    }

    @Test fun carouselNeighborsAreLeftAndRight() {
        val g = gaps(120f, 0f, 220f, 160f, 0f, 0f, 108f, 160f, 232f, 0f, 332f, 160f)
        assertEquals(12f, g[0], 0f)
        assertEquals(12f, g[2], 0f)
        assertEquals(Float.POSITIVE_INFINITY, g[1], 0f)
        assertEquals(Float.POSITIVE_INFINITY, g[3], 0f)
    }

    @Test fun overlappingAndInvalidSiblingsAreIgnored() {
        val g = gaps(0f, 0f, 100f, 100f,
            50f, 50f, 150f, 150f,                              // 叠放：与自身相交
            Float.NaN, Float.NaN, Float.NaN, Float.NaN,        // 不可见占位
            120f, 0f, 110f, 100f)                              // 退化矩形
        for (value in g) assertEquals(Float.POSITIVE_INFINITY, value, 0f)
    }

    @Test fun travelStopsBeforeTheNeighborButKeepsTheParentBudget() {
        val limit = 18f
        val margin = 2.5f
        // 没有邻居：沿用"父容器内缘 max(间隙, 预算)"（WRAP 尾边间隙为 0 也能拖满预算）。
        assertEquals(limit, ElasticMotionGroupPolicy.travelBound(0f, Float.POSITIVE_INFINITY, limit, margin), 0f)
        assertEquals(40f, ElasticMotionGroupPolicy.travelBound(40f, Float.POSITIVE_INFINITY, limit, margin), 0f)
        // 间距 12 的网格：最多走到离邻居 2.5 的地方。
        assertEquals(9.5f, ElasticMotionGroupPolicy.travelBound(0f, 12f, limit, margin), 1e-6f)
        // 邻居比余量还近：这个方向不动。
        assertEquals(0f, ElasticMotionGroupPolicy.travelBound(0f, 1f, limit, margin), 0f)
        // 邻居很远：父容器预算说了算。
        assertEquals(limit, ElasticMotionGroupPolicy.travelBound(0f, 200f, limit, margin), 0f)
    }

    // ---------------- §14.2 形变折叠端圆角 ----------------

    @Test fun collapsedCornerFollowsTheSourceInsteadOfAlwaysBecomingACircle() {
        // 无背景图标：没有声明 → 短边一半（正圆），与来源工程一致。
        assertEquals(40.5f, MorphCornerPolicy.collapsedRadius(Float.NaN, 81f, 81f), 0f)
        // 网格方块 170×170、圆角 15：从圆角方块长出来，而不是从一个圆。
        assertEquals(15f, MorphCornerPolicy.collapsedRadius(15f, 170f, 170f), 0f)
        // 声明的圆角比短边一半还大：钳到胶囊。
        assertEquals(32f, MorphCornerPolicy.collapsedRadius(100f, 360f, 64f), 0f)
        // 声明的直角是直角。
        assertEquals(0f, MorphCornerPolicy.collapsedRadius(0f, 200f, 120f), 0f)
        assertEquals(0f, MorphCornerPolicy.collapsedRadius(12f, 0f, 120f), 0f)
        assertEquals(0f, MorphCornerPolicy.collapsedRadius(12f, Float.NaN, 120f), 0f)
    }

    // ---------------- §14.3 手风琴：网格与横排的行高换算 ----------------

    @Test fun rowShrinkEqualsCardShrinkWhenTheCardIsTheOnlyOrTallestCell() {
        for (s in listOf(0f, 10f, 80f, 200f)) {
            assertEquals(s, ExpansionFollowPolicy.rowShrink(200f, 0f, s), 1e-4f)
        }
        // 同行另一格 60 高，卡片展开 200：收缩到 140 以内时整行一起变矮。
        assertEquals(100f, ExpansionFollowPolicy.rowShrink(200f, 60f, 100f), 1e-4f)
    }

    @Test fun rowStopsShrinkingOnceATallerNeighborHoldsIt() {
        // 卡片 200 → 收缩 180（只剩 20），同行另一格 60：行只矮到 60，即 140。
        assertEquals(140f, ExpansionFollowPolicy.rowShrink(200f, 60f, 180f), 1e-4f)
        // 同行另一格比卡片展开后还高：行高不变，下面的行一动不动。
        assertEquals(0f, ExpansionFollowPolicy.rowShrink(200f, 260f, 150f), 0f)
        // 单调、有界。
        var previous = 0f
        for (step in 0..200) {
            val value = ExpansionFollowPolicy.rowShrink(200f, 90f, step.toFloat())
            assertTrue(value >= previous && value <= step + 1e-4f)
            previous = value
        }
        assertEquals(0f, ExpansionFollowPolicy.rowShrink(0f, 0f, 10f), 0f)
        assertEquals(200f, ExpansionFollowPolicy.rowShrink(200f, 0f, 999f), 0f)
    }

    @Test fun followersAreBelowAndInTheRightColumnOrRow() {
        assertTrue(ExpansionFollowPolicy.isBelow(112f, 112.5f))
        assertFalse(ExpansionFollowPolicy.isBelow(50f, 112f))
        assertTrue(ExpansionFollowPolicy.sharesColumn(0f, 100f, 50f, 150f))
        assertFalse(ExpansionFollowPolicy.sharesColumn(112f, 212f, 0f, 100f))
        assertTrue(ExpansionFollowPolicy.sharesRow(0f, 60f, 0f, 200f))
        assertFalse(ExpansionFollowPolicy.sharesRow(212f, 300f, 0f, 200f))
    }

    // ---------------- §14.6 定位：横向轮播与超高目标 ----------------

    @Test fun horizontalRevealOnlyScrollsWhatIsNeeded() {
        // 视口 [16, 1064]，边距 36。
        assertEquals(0, RevealPolicy.horizontalDelta(100, 400, 16, 1064, 36))
        assertEquals(1100 - 1064 + 36, RevealPolicy.horizontalDelta(800, 1100, 16, 1064, 36))
        assertEquals(-40 - 16 - 36, RevealPolicy.horizontalDelta(-40, 260, 16, 1064, 36))
    }

    @Test fun targetsWiderThanTheViewportAlignToTheirStart() {
        // 目标 1200 宽、视口 1048 宽：起始边对齐，不来回找中点。
        assertEquals(300 - 16, RevealPolicy.horizontalDelta(300, 1500, 16, 1064, 36))
        // 正好放得下但放不下两侧边距：居中。
        assertEquals(0, RevealPolicy.horizontalDelta(18, 1062, 16, 1064, 36))
        assertEquals(0, RevealPolicy.horizontalDelta(100, 100, 16, 1064, 36))
    }

    @Test fun tallTargetsAlignTheirTopAndClampToTheScrollRange() {
        assertEquals(1500 - 300, RevealPolicy.verticalDestination(1500, 300, 5000))
        assertEquals(0, RevealPolicy.verticalDestination(100, 300, 5000))
        assertEquals(800, RevealPolicy.verticalDestination(4000, 300, 800))
        assertEquals(0, RevealPolicy.verticalDestination(4000, 300, -5))
    }
}
