package com.lumen.coacervation.engine.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 气泡路径几何：箭头底边必须落在圆角之外的直边上，描边必须留在 View 边界内。 */
class BubbleDrawableGeometryTest {

    @Test fun githubBadgeArrowBaseClearsTheCornerArc() {
        // NEW 气泡：22dp 宽、12dp 高、6dp 箭头、5dp 圆角，请求箭头偏移 5dp。
        val r = BubbleDrawable.resolvedCornerRadius(5f, 22f, 12f, 6f)
        assertEquals(5f, r, 0f)
        val cx = BubbleDrawable.arrowCenterX(5f, 22f, 6f, r)
        // 旧实现允许 cx=5：底边 2~8dp 伸进 5dp 圆角，顶部直线回扫，
        // 描边在气泡左下角多画一截悬空直线（用户截图红圈处）。
        assertEquals(8f, cx, 0f)
        assertTrue("箭头底边必须整体位于圆角之外的直边上", cx - 3f >= r)
    }

    @Test fun freeCopyBubbleKeepsItsRequestedCornerAndStillAnchors() {
        val r = BubbleDrawable.resolvedCornerRadius(14f, 320f, 120f, 12f)
        assertEquals(14f, r, 0f)
        // 贴左极限（HookEntry 下限 aw/2 + r = 20dp）与居中各自成立。
        assertEquals(20f, BubbleDrawable.arrowCenterX(6f, 320f, 12f, r), 0f)
        assertEquals(160f, BubbleDrawable.arrowCenterX(160f, 320f, 12f, r), 0f)
    }

    @Test fun narrowBubbleShrinksTheCornerSoTheArrowStillFits() {
        // 10dp 宽、6dp 箭头：圆角最多 (10-6)/2=2dp，箭头被顶到中线两侧对称。
        assertEquals(2f, BubbleDrawable.resolvedCornerRadius(5f, 10f, 20f, 6f), 0f)
        assertEquals(5f, BubbleDrawable.arrowCenterX(1f, 10f, 6f, 2f), 0f)
    }

    @Test fun bubbleNarrowerThanTheArrowCentersInsteadOfInvertingTheRange() {
        assertEquals(0f, BubbleDrawable.resolvedCornerRadius(5f, 4f, 20f, 6f), 0f)
        assertEquals(2f, BubbleDrawable.arrowCenterX(1f, 4f, 6f, 0f), 0f)
    }

    @Test fun invalidDimensionsDegradeToZeroCorner() {
        assertEquals(0f, BubbleDrawable.resolvedCornerRadius(5f, 0f, 0f, 6f), 0f)
        assertEquals(0f, BubbleDrawable.resolvedCornerRadius(5f, -1f, 10f, 6f), 0f)
        assertEquals(0f, BubbleDrawable.arrowCenterX(3f, 0f, 6f, 0f), 0f)
    }


}
