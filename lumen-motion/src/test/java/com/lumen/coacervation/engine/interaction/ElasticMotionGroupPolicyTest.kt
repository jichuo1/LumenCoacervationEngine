package com.lumen.coacervation.engine.interaction

import org.junit.Assert.*
import org.junit.Test

class ElasticMotionGroupPolicyTest {
    private fun node(surface: Boolean, children: Int = 1, fills: Boolean = false,
                     container: Boolean = false) =
        ElasticGroupNode(surface, fills, container, children)

    @Test fun leafWithOwnSurfaceDoesNotPromote() {
        val path = listOf(node(true), node(false, children = 3), node(true, fills = true))
        assertEquals(0, ElasticMotionGroupPolicy.promotionDepth(path))
    }

    @Test fun surfacelessLeafPromotesToItsSurfaceOwner() {
        val path = listOf(node(false), node(true, children = 2), node(true, fills = true))
        assertEquals(1, ElasticMotionGroupPolicy.promotionDepth(path))
    }

    @Test fun cardOfRowsUnifiesIntoTheCard() {
        val row = node(true)
        val card = node(true, children = 3)
        val window = node(false, fills = true)
        assertEquals(1, ElasticMotionGroupPolicy.promotionDepth(listOf(row, card, window)))
    }

    @Test fun windowFillersAndContainerModalsStopPromotion() {
        val underWindow = listOf(node(false), node(false), node(false, fills = true))
        assertEquals(1, ElasticMotionGroupPolicy.promotionDepth(underWindow))
        val inModal = listOf(node(false), node(false, container = true), node(true))
        assertEquals(0, ElasticMotionGroupPolicy.promotionDepth(inModal))
    }

    @Test fun promotionIsBounded() {
        val deep = List(12) { node(false) } + node(true, fills = true)
        assertEquals(ElasticMotionGroupPolicy.MAX_PROMOTION_STEPS,
            ElasticMotionGroupPolicy.promotionDepth(deep))
        assertEquals(0, ElasticMotionGroupPolicy.promotionDepth(emptyList()))
    }

    @Test fun clampKeepsTheGroupInsideParentEdges() {
        val out = ElasticVector()
        ElasticMotionGroupPolicy.clampToParent(30f, -30f, 10f, 4f, 12f, 8f, out)
        assertEquals(12f, out.x, 0f); assertEquals(-4f, out.y, 0f)
        ElasticMotionGroupPolicy.clampToParent(-30f, 30f, 10f, 4f, 12f, 8f, out)
        assertEquals(-10f, out.x, 0f); assertEquals(8f, out.y, 0f)
        ElasticMotionGroupPolicy.clampToParent(Float.NaN, 3f, 1f, 1f, 1f, 1f, out)
        assertEquals(0f, out.x, 0f); assertEquals(1f, out.y, 0f)
        ElasticMotionGroupPolicy.clampToParent(5f, 5f, -3f, -4f, -1f, -2f, out)
        assertEquals(0f, out.x, 0f); assertEquals(0f, out.y, 0f)
    }

    @Test fun zeroTrailingGapKeepsTheTravelBudget() {
        // WRAP_CONTENT 父容器里最后一个子元素的尾边间隙恒为 0；不补预算会把该方向
        // 的拖动整体钳死（真机实证：「兼容」卡向下纹丝不动，它上面的「外观」卡正常）。
        val out = ElasticVector()
        ElasticMotionGroupPolicy.clampToParent(100f, -100f, 0f, 30f, 0f, 0f, out, minTravel = 36f)
        assertEquals(36f, out.x, 0f); assertEquals(-36f, out.y, 0f)
        // 预算内的位移原样通过——不传预算时这两个值都会被 0 间隙钳成 0。
        ElasticMotionGroupPolicy.clampToParent(20f, -20f, 0f, 30f, 0f, 0f, out, minTravel = 36f)
        assertEquals(20f, out.x, 0f); assertEquals(-20f, out.y, 0f)
    }

    @Test fun travelBudgetNeverShrinksRealGaps() {
        // 真实内边距大于预算时钳制结果与不传预算逐字一致（弹窗 24dp 内边距即此类）。
        val plain = ElasticVector()
        val budgeted = ElasticVector()
        ElasticMotionGroupPolicy.clampToParent(100f, -100f, 10f, 4f, 12f, 8f, plain)
        ElasticMotionGroupPolicy.clampToParent(100f, -100f, 10f, 4f, 12f, 8f, budgeted, minTravel = 3f)
        assertEquals(plain.x, budgeted.x, 0f); assertEquals(plain.y, budgeted.y, 0f)
    }

    @Test fun nonFiniteBudgetFallsBackToPureGeometry() {
        val out = ElasticVector()
        ElasticMotionGroupPolicy.clampToParent(30f, 30f, 0f, 0f, 0f, 0f, out, minTravel = Float.NaN)
        assertEquals(0f, out.x, 0f); assertEquals(0f, out.y, 0f)
        ElasticMotionGroupPolicy.clampToParent(30f, 30f, 0f, 0f, 0f, 0f, out, minTravel = -5f)
        assertEquals(0f, out.x, 0f); assertEquals(0f, out.y, 0f)
    }

    @Test fun growthIsCappedButPressShrinkSurvives() {
        assertEquals(1.05f,
            ElasticMotionGroupPolicy.cappedScale(1.05f, 100, 5f), 0f)
        assertEquals(1.02f,
            ElasticMotionGroupPolicy.cappedScale(1.06f, 250, 5f), .0001f)
        assertEquals(.984f,
            ElasticMotionGroupPolicy.cappedScale(.984f, 100, 5f), 0f)
        assertEquals(1f, ElasticMotionGroupPolicy.cappedScale(.98f, 0, 5f), 0f)
        assertEquals(1f,
            ElasticMotionGroupPolicy.cappedScale(Float.NaN, 100, 5f), 0f)
    }

    @Test fun zeroGapWrapperIsTight() {
        // The badge frame around the GitHub icon: 48dp child inside a 48dp parent.
        assertTrue(ElasticMotionGroupPolicy.isTightWrap(0f, 0f, 0f, 0f, 4f))
    }

    @Test fun slackOnAnySideBreaksTightness() {
        // The toolbar offers margins/padding on every side of its icons.
        assertFalse(ElasticMotionGroupPolicy.isTightWrap(20f, 13f, 20f, 5f, 4f))
        assertFalse(ElasticMotionGroupPolicy.isTightWrap(0f, 0f, 0f, 5f, 4f))
        // Gaps within the slack threshold still count as tight.
        assertTrue(ElasticMotionGroupPolicy.isTightWrap(4f, 3f, 4f, 0f, 4f))
    }

    @Test fun negativeOrInfiniteSlackIsNeverTight() {
        assertFalse(ElasticMotionGroupPolicy.isTightWrap(0f, 0f, 0f, 0f, -1f))
        assertFalse(ElasticMotionGroupPolicy.isTightWrap(0f, 0f, 0f, 0f, Float.NaN))
        assertFalse(ElasticMotionGroupPolicy.isTightWrap(0f, 0f, 0f, 0f, Float.POSITIVE_INFINITY))
    }
}
