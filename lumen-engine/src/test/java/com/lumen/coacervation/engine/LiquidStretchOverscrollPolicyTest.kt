package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidStretchEdge
import com.lumen.coacervation.engine.liquid.LiquidStretchOverscrollPolicy
import com.lumen.coacervation.engine.liquid.LiquidStretchUnconsumedAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before

class LiquidStretchOverscrollPolicyTest {

    @Test
    fun `unconsumed direction selects matching edge`() {
        assertEquals(LiquidStretchEdge.TOP, LiquidStretchOverscrollPolicy.pullEdge(-24))
        assertEquals(LiquidStretchEdge.NONE, LiquidStretchOverscrollPolicy.pullEdge(0))
        assertEquals(LiquidStretchEdge.BOTTOM, LiquidStretchOverscrollPolicy.pullEdge(24))
    }

    @Test
    fun `reverse scroll only releases an active opposite edge`() {
        assertEquals(
            LiquidStretchEdge.TOP,
            LiquidStretchOverscrollPolicy.releaseEdge(12, topDistance = 0.2f, bottomDistance = 0f)
        )
        assertEquals(
            LiquidStretchEdge.BOTTOM,
            LiquidStretchOverscrollPolicy.releaseEdge(-12, topDistance = 0f, bottomDistance = 0.2f)
        )
        assertEquals(
            LiquidStretchEdge.NONE,
            LiquidStretchOverscrollPolicy.releaseEdge(12, topDistance = 0f, bottomDistance = 0.2f)
        )
    }

    @Test
    fun `distance normalizes by viewport and clamps invalid extremes`() {
        assertEquals(0.25f, LiquidStretchOverscrollPolicy.normalizedDistance(50, 200), 0.0001f)
        assertEquals(1f, LiquidStretchOverscrollPolicy.normalizedDistance(-500, 200), 0.0001f)
        assertEquals(0f, LiquidStretchOverscrollPolicy.normalizedDistance(50, 0), 0.0001f)
    }

    @Test
    fun `bottom displacement mirrors top around viewport center`() {
        assertEquals(
            0.25f,
            LiquidStretchOverscrollPolicy.displacement(25f, 100, LiquidStretchEdge.TOP),
            0.0001f
        )
        assertEquals(
            0.75f,
            LiquidStretchOverscrollPolicy.displacement(25f, 100, LiquidStretchEdge.BOTTOM),
            0.0001f
        )
        assertEquals(
            0.5f,
            LiquidStretchOverscrollPolicy.displacement(25f, 0, LiquidStretchEdge.TOP),
            0.0001f
        )
    }

    @Test
    fun `consumption signs follow nested scroll direction`() {
        assertEquals(-20, LiquidStretchOverscrollPolicy.consumedPixels(
            LiquidStretchEdge.TOP, 0.1f, 200
        ))
        assertEquals(20, LiquidStretchOverscrollPolicy.consumedPixels(
            LiquidStretchEdge.BOTTOM, 0.1f, 200
        ))
        assertEquals(20, LiquidStretchOverscrollPolicy.releaseConsumedPixels(
            LiquidStretchEdge.TOP, -0.1f, 200
        ))
        assertEquals(-20, LiquidStretchOverscrollPolicy.releaseConsumedPixels(
            LiquidStretchEdge.BOTTOM, -0.1f, 200
        ))
    }

    @Test
    fun `absorb velocity is positive and bounded`() {
        assertEquals(720, LiquidStretchOverscrollPolicy.absorbVelocity(-720f))
        assertEquals(1, LiquidStretchOverscrollPolicy.absorbVelocity(0f))
        assertEquals(100_000, LiquidStretchOverscrollPolicy.absorbVelocity(Float.MAX_VALUE))
    }

    @Test
    fun `touch pulls and consumes while fling always propagates`() {
        assertEquals(
            LiquidStretchUnconsumedAction.PULL_AND_CONSUME,
            LiquidStretchOverscrollPolicy.unconsumedAction(
                isTouch = true,
                hasFlingVelocity = true
            )
        )
        assertEquals(
            LiquidStretchUnconsumedAction.ABSORB_AND_PROPAGATE,
            LiquidStretchOverscrollPolicy.unconsumedAction(
                isTouch = false,
                hasFlingVelocity = true
            )
        )
        assertEquals(
            LiquidStretchUnconsumedAction.PROPAGATE,
            LiquidStretchOverscrollPolicy.unconsumedAction(
                isTouch = false,
                hasFlingVelocity = false
            )
        )
    }

    @Test
    fun `dominant edge picks the stronger side and never loses direction`() {
        // 方向随距离一起上报：高光按"哪条边在拉伸"投射，只报标量会让四边等亮。
        assertEquals(
            LiquidStretchEdge.TOP,
            LiquidStretchOverscrollPolicy.dominantEdge(0.3f, 0f)
        )
        assertEquals(
            LiquidStretchEdge.BOTTOM,
            LiquidStretchOverscrollPolicy.dominantEdge(0f, 0.3f)
        )
        // 两侧瞬时共存（一侧衰减、一侧拉起）时取更强的一侧。
        assertEquals(
            LiquidStretchEdge.TOP,
            LiquidStretchOverscrollPolicy.dominantEdge(0.4f, 0.1f)
        )
        assertEquals(
            LiquidStretchEdge.BOTTOM,
            LiquidStretchOverscrollPolicy.dominantEdge(0.1f, 0.4f)
        )
        assertEquals(
            LiquidStretchEdge.NONE,
            LiquidStretchOverscrollPolicy.dominantEdge(0f, 0f)
        )
    }

    @Test
    fun `stop releases touch and adjusted fling but preserves absorb`() {
        assertTrue(
            LiquidStretchOverscrollPolicy.shouldReleaseOnStop(
                isTouch = true,
                nonTouchAbsorbed = false,
                nonTouchAdjusted = false
            )
        )
        assertTrue(
            LiquidStretchOverscrollPolicy.shouldReleaseOnStop(
                isTouch = false,
                nonTouchAbsorbed = false,
                nonTouchAdjusted = true
            )
        )
        assertFalse(
            LiquidStretchOverscrollPolicy.shouldReleaseOnStop(
                isTouch = false,
                nonTouchAbsorbed = true,
                nonTouchAdjusted = true
            )
        )
    }

    /**
     * 两条相反方向的回弹同时衰减时必须有迟滞（2026-09-22 用户报告）。
     *
     * 没有迟滞时两个距离会反复穿越，而渲染层对"方向变了"是无条件发布的（强度量化拦不住），
     * 每穿越一次就把整组可见玻璃表面重录一遍——短页面里一甩到底、立刻反向甩最容易撞上，
     * 现场就是"短时间触发两个相反方向回弹会有迟滞感"。
     */
    @Test
    fun `opposite rebounds do not trade dominance on every crossing`() {
        val ratio = LiquidStretchOverscrollPolicy.EDGE_FLIP_RATIO
        assertTrue(ratio > 1f)
        // 量级相近时保持当前边，不随微小穿越翻转。
        assertEquals(LiquidStretchEdge.TOP,
            LiquidStretchOverscrollPolicy.dominantEdge(0.20f, 0.21f, LiquidStretchEdge.TOP))
        assertEquals(LiquidStretchEdge.BOTTOM,
            LiquidStretchOverscrollPolicy.dominantEdge(0.21f, 0.20f, LiquidStretchEdge.BOTTOM))
        // 另一边显著更大才交权。
        assertEquals(LiquidStretchEdge.BOTTOM,
            LiquidStretchOverscrollPolicy.dominantEdge(0.20f, 0.20f * ratio + 0.01f, LiquidStretchEdge.TOP))
        // 当前边归零必须立刻交权，不能卡在已经消失的那条边上。
        assertEquals(LiquidStretchEdge.BOTTOM,
            LiquidStretchOverscrollPolicy.dominantEdge(0f, 0.05f, LiquidStretchEdge.TOP))
        assertEquals(LiquidStretchEdge.TOP,
            LiquidStretchOverscrollPolicy.dominantEdge(0.05f, 0f, LiquidStretchEdge.BOTTOM))
        // 两边都归零就是 NONE。
        assertEquals(LiquidStretchEdge.NONE,
            LiquidStretchOverscrollPolicy.dominantEdge(0f, 0f, LiquidStretchEdge.TOP))
        // 无状态调用（默认参数）保持原语义：谁大选谁。
        assertEquals(LiquidStretchEdge.TOP, LiquidStretchOverscrollPolicy.dominantEdge(0.4f, 0.1f))
        assertEquals(LiquidStretchEdge.BOTTOM, LiquidStretchOverscrollPolicy.dominantEdge(0.1f, 0.4f))
    }
    /**
     * 回弹可打断（2026-09-23 用户要求）。真机实证：按住正在回弹的页面能冻结形变，但按下事件照常
     * 下发给手指下的卡片，原地松手就打开了"设置备份与恢复"。接住回弹的这段手势必须整段交给滚动
     * 容器自己处理，内容收不到按下/点击/长按；拖动、甩动、松手回弹仍走滚动容器原逻辑。
     */
    @Test
    fun `catching a rebound takes the whole gesture away from the content`() {
        val viewport = SourceContract.read("liquid/LiquidStretchViewport.kt")
        val dispatch = viewport.after("override fun dispatchTouchEvent(").before("override fun onInterceptTouchEvent(")
        assertTrue("只有真的接住了回弹才接管", dispatch.contains("stopEffectsForTouch()") &&
            viewport.contains("private fun stopEffectsForTouch(): Boolean") && viewport.contains("return stopped"))
        assertTrue("不允许回弹时照旧清零，不接管", dispatch.contains("finishStretch()\n                false"))
        assertTrue("手势结束必须复位接管标记", dispatch.indexOf("super.dispatchTouchEvent(event)") in
            0 until dispatch.indexOf("catchingStretch = false"))
        assertTrue(viewport.contains("catchingStretch || super.onInterceptTouchEvent(event)"))
        val touch = viewport.after("override fun onTouchEvent(").before("override fun draw(")
        assertTrue(touch.contains("if (!catchingStretch) return super.onTouchEvent(event)"))
        assertTrue("交给滚动容器的 onTouchEvent，不经它的子 View", touch.contains("scrollTarget.onTouchEvent(forwarded)") &&
            !touch.contains("scrollTarget.dispatchTouchEvent"))
        assertTrue("观察者先于滚动容器处理", touch.indexOf("observeTouch(forwarded)") in
            0 until touch.indexOf("scrollTarget.onTouchEvent(forwarded)"))
        assertTrue(touch.contains("forwarded.recycle()"))
        // 全局长按弹性挂在 Activity 分发上、自己做命中测试：接住回弹时也不得点亮按压高光（柔光真机实证）。
        assertTrue(viewport.contains("override val claimsCurrentGesture: Boolean get() = catchingStretch"))
        // 宿主的按压高光/弹性控制器据此判断"这段手势已被祖先接管"（宿主义务见 INTEGRATION_STANDARD.md §5.4）。
        val claim = SourceContract.read("interaction/ElasticGestureClaim.kt")
        assertTrue(claim.contains("fun claimedAbove(view: View): Boolean"))
        assertTrue(claim.contains("parent is ElasticGestureClaim && parent.claimsCurrentGesture"))
    }
}
