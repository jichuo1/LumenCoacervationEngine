package com.lumen.coacervation.engine.motion.pager

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页打断：按下不冻结进行中的动画（竖滑/轻点时动画照常走完），只有横向接手那一刻才从动画当前位置
 * 交给手指；文字链式联动只改绘制矩阵，不碰弹性交互与手风琴使用的 translation 属性。
 */
class SettingsPageInterruptionContractTest {
    private val pager = MotionSource.file("LumenPagePager")
    private val chain = MotionSource.file("PageTextChain")

    @Test fun downDoesNotFreezeTheRunningAnimation() {
        val begin = pager.after("private fun beginGesture(event: MotionEvent) {").before("private fun protectedChildAt(")
        assertFalse(begin.contains("stopMotion()"))
        assertFalse(pager.contains("resumePausedMotion"))
        assertFalse(pager.contains("motionPaused"))
    }

    @Test fun horizontalTakeoverStartsFromTheCurrentAnimatedPosition() {
        val own = pager.after("private fun tryOwnGesture(event: MotionEvent) {").before("override fun onTouchEvent(")
        val takeover = own.after("gestureOwned = true")
        assertTrue(takeover.indexOf("stopMotion()") < takeover.indexOf("dragStart = position"))
        assertTrue(takeover.contains("downX = event.getX(pointer)"))
        assertTrue(takeover.contains("motionAnchorY = downY"))
    }

    @Test fun settleUsesVelocityMatchedHandoff() {
        val settle = pager.after("private fun settle(").before("private fun stopMotion()")
        assertTrue(settle.contains("PageMotionPolicy.handoffDuration(base, position, target.toFloat(), velocity)"))
    }

    @Test fun textChainOnlyTouchesTheDrawMatrixAndSkipsSurfacedControls() {
        assertFalse(chain.contains(".translationX ="))
        assertFalse(chain.contains(".translationY ="))
        assertTrue(chain.contains("view.animationMatrix = matrix"))
        assertTrue(chain.contains("if (view is EditText || (view is TextView && view !is CompoundButton && view.background != null)) return"))
        assertTrue(chain.contains("ElasticInteractionController.EXCLUDED_TAG"))
        val dispose = chain.after("fun dispose() {")
        assertTrue(dispose.contains("removeFrameCallback(frame)"))
        // 宿主在翻页器的 onPositionChanged 里驱动文字链、销毁时 dispose（适配标准 §13.6）。
    }

    /** 文字不被行矩形、内边距或卡片边缘切断：放开中间容器与内边距裁剪，幅度按到卡片边缘的余量收紧，结束后恢复。 */
    @Test fun textNeverGetsClippedByRowsPaddingOrCardEdges() {
        assertTrue(chain.contains("val room = if (direction >= 0f) item.roomRight else item.roomLeft"))
        assertTrue(chain.contains("val goal = direction * minOf(room, limit) * share * bump"))
        assertTrue(chain.contains("private fun isClipBoundary(view: View): Boolean = view.clipToOutline || isScrollContainer(view)"))
        // 横向轮播同样是滚动容器（适配标准 §14.4）：不放开它的裁剪，滚出视口的卡片不参与。
        assertTrue(chain.contains("view is ScrollView || view is NestedScrollView || view is HorizontalScrollView"))
        assertTrue(chain.contains("view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)"))
        assertTrue(chain.contains("if (rect.right <= boundaryRect.left || rect.left >= boundaryRect.right) return"))
        assertTrue(chain.contains("if (isScrollContainer(it)) return@forEach"))
        assertTrue(chain.contains("if (it.clipToPadding) relaxPadding += it"))
        assertTrue(chain.contains("if (boundary !== page && boundary is ViewGroup && boundary.clipToPadding && !isScrollContainer(boundary)) relaxPadding += boundary"))
        assertTrue(chain.contains("relax.remove(page as? ViewGroup)"))
        assertTrue(chain.contains("relaxPadding.remove(page as? ViewGroup)"))
        val release = chain.after("private fun release(index: Int) {").before("private fun isClipBoundary(")
        // 页数不设上限后按页号存（适配标准 §15.1），恢复语义不变。
        assertTrue(release.contains("relaxed.remove(index)?.forEach { it.clipChildren = true }"))
        assertTrue(release.contains("paddingRelaxed.remove(index)?.forEach { it.clipToPadding = true }"))
        assertTrue(chain.after("fun dispose() {").contains("release(index)"))
    }

    /**
     * 偏移由本段翻页进度决定：sin² 鼓包起止斜率为 0、到达目标页时归零（与切页同步）；链式顺序用进度相位错开，
     * 远近按离锚点最远的屏幕边缘归一。
     */
    @Test fun chainIsAProgressBumpPhasedByDistanceFromTheGesture() {
        assertTrue(chain.contains("val anchor = pager.motionAnchorY.takeIf { it.isFinite() } ?: height"))
        assertTrue(chain.contains("val span = maxOf(anchor, height - anchor, height * REACH_SPAN_MIN)"))
        assertTrue(chain.contains("val phase = progress.pow(skew)"))
        assertTrue(chain.contains("val bump = sin(PI.toFloat() * phase).let { it * it }"))
        assertTrue(chain.contains("if (!motionFrom.isFinite()) motionFrom = position.roundToInt().toFloat()"))
        assertFalse(chain.contains("velocity"))
        assertTrue(chain.contains("val icon = view is ImageView && view.background == null"))
    }
}
