package com.lumen.coacervation.engine.motion.expansion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NestedExpansionPolicyTest {
    private fun shrink(height: Float, children: Float, p: Float) =
        NestedExpansionPolicy.ownShrink(height, children, p)

    @Test fun nestedCollapseNeverConsumesTheHeaderOrUnrelatedSettings() {
        val retained = 500f
        for (parentStep in 0..100) for (childStep in 0..100) {
            val parent = parentStep / 100f
            val child = childStep / 100f
            val childShrink = shrink(800f, 0f, child)
            val parentShrink = shrink(1200f, childShrink, parent)
            val visible = retained + 1200f - childShrink - parentShrink
            assertTrue("preserved area must never be clipped", visible >= retained - 0.001f)
            assertEquals(retained + parent * (400f + 800f * child), visible, 0.001f)
        }
    }

    @Test fun childGoneLayoutCommitHasNoVisualHeightDiscontinuity() {
        for (step in 0..100) {
            val parent = step / 100f
            val before = 1700f - 800f - shrink(1200f, 800f, parent)
            val after = 900f - shrink(400f, 0f, parent)
            assertEquals(before, after, 0.001f)
        }
    }

    @Test fun insertingCollapsedChildDuringParentAnimationHasNoHeightJump() {
        for (step in 0..100) {
            val parent = step / 100f
            val before = 900f - shrink(400f, 0f, parent)
            val after = 1700f - 800f - shrink(1200f, 800f, parent)
            assertEquals(before, after, 0.001f)
        }
    }

    @Test fun siblingsAddWhileThreeNestedLevelsCompose() {
        val leaf = shrink(600f, 0f, 0.2f)
        val middle = shrink(1000f, leaf, 0.4f)
        val sibling = shrink(300f, 0f, 0.5f)
        val outer = shrink(1800f, leaf + middle + sibling, 0.7f)
        val visible = 1800f - leaf - middle - sibling - outer
        assertEquals(0.7f * (500f + 0.4f * (400f + 0.2f * 600f) + 150f), visible, 0.001f)
    }

    @Test fun parentFinishingFirstHidesExactlyItsOwnContent() {
        for (step in 0..100) {
            val child = shrink(800f, 0f, step / 100f)
            assertEquals(1200f, child + shrink(1200f, child, 0f), 0.001f)
        }
    }

    @Test fun siblingLayoutCommitKeepsFoldedRowPositionContinuous() {
        for (step in 0..100) {
            val p = step / 100f
            val before = NestedExpansionPolicy.rowTop(2, 1000f, -800f, p, 18f)
            val after = NestedExpansionPolicy.rowTop(2, 200f, 0f, p, 18f)
            assertEquals(before, after, 0.001f)
        }
    }

    @Test fun reversingEitherSpringDoesNotResetCompositeGeometry() {
        val parent = ExpansionMotionPolicy.Spring(1f, 0f, 0f)
        val child = ExpansionMotionPolicy.Spring(1f, 0f, 0f)
        repeat(10) { child.step(1f / 120f) }
        repeat(12) { parent.step(1f / 120f); child.step(1f / 120f) }
        fun visible(): Float {
            val nested = shrink(800f, 0f, child.p)
            return 1700f - nested - shrink(1200f, nested, parent.p)
        }
        val before = visible()
        parent.target = 1f
        child.target = 1f
        assertEquals(before, visible(), 0f)
        repeat(240) {
            parent.step(1f / 120f)
            child.step(1f / 120f)
            assertTrue(visible() >= 500f - 0.001f)
        }
        assertEquals(1700f, visible(), 0.1f)
    }

    @Test fun longParentAndShortChildCanSettleOnDifferentFrames() {
        val parent = ExpansionMotionPolicy.Spring(1f, 0f, 0f)
        val child = ExpansionMotionPolicy.Spring(1f, 0f, 0f)
        parent.adoptTravel(12000f)
        child.adoptTravel(800f)
        var childInLayout = true
        repeat(300) {
            if (it > 15) parent.step(1f / 120f)
            val childRest = child.step(1f / 120f)
            val nested = if (childInLayout) shrink(800f, 0f, child.p) else 0f
            val height = if (childInLayout) 12000f else 11200f
            val visible = 500f + height - nested - shrink(height, nested, parent.p)
            assertTrue(visible >= 500f - 0.01f)
            if (childInLayout && childRest) {
                val after = 11700f - shrink(11200f, 0f, parent.p)
                assertEquals(visible, after, 0.01f)
                childInLayout = false
            }
        }
        assertTrue(!childInLayout)
        assertEquals(0f, parent.p, 0.0001f)
    }

    @Test fun scrollRebaseAcrossChildGoneIsContinuousAndReachesTheNewLimit() {
        val before = NestedExpansionPolicy.scrollPosition(1100f, 0f, 1200f, 1400, 200)
        // 子级从布局移除 800px，当前收缩量与终点同时减少 800px。
        val after = NestedExpansionPolicy.scrollPosition(300f, 300f, 400f, before.toInt(), 200)
        assertEquals(before, after, 0.001f)
        assertEquals(200f, NestedExpansionPolicy.scrollPosition(400f, 300f, 400f, before.toInt(), 200), 0f)
    }

    @Test fun scrollWithoutRangeLossDoesNotMove() {
        for (step in 0..100) {
            assertEquals(100f, NestedExpansionPolicy.scrollPosition(step * 10f, 0f, 1000f, 100, 100), 0f)
        }
    }
}
