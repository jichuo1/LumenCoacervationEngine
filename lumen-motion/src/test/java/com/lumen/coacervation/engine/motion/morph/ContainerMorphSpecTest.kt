package com.lumen.coacervation.engine.motion.morph

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.motion.MotionRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupMotionSpecTest {

    private val collapsedBounds = MotionRect(15f, 420f, 1065f, 540f)
    private val expandedBounds = MotionRect(0f, 0f, 1080f, 2160f)
    private val collapsedTitle = MotionRect(65f, 445f, 500f, 490f)
    private val expandedTitle = MotionRect(60f, 10f, 1020f, 58f)

    @Test
    fun collapsedFrameMatchesSourceCardAndLeavesUnderlyingCardVisible() {
        val frame = frame(0f)

        assertEquals(collapsedBounds, frame.bounds)
        assertEquals(45f, frame.cornerRadiusPx, 0f)
        assertEquals(0f, frame.surfaceAlpha, 0f)
        assertEquals(0f, frame.contentAlpha, 0f)
        assertEquals(36f, frame.contentTranslationYPx, 0f)
        assertEquals(collapsedTitle.left, frame.titleX, 0f)
        assertEquals(collapsedTitle.top, frame.titleY, 0f)
        assertEquals(45f, frame.titleTextSizePx, 0f)
    }

    @Test
    fun expandedFrameMatchesWindowAndToolbar() {
        val frame = frame(1f)

        assertEquals(expandedBounds, frame.bounds)
        assertEquals(0f, frame.cornerRadiusPx, 0f)
        assertEquals(1f, frame.surfaceAlpha, 0f)
        assertEquals(1f, frame.contentAlpha, 0f)
        assertEquals(0f, frame.contentTranslationYPx, 0f)
        assertEquals(expandedTitle.left, frame.titleX, 0f)
        assertEquals(expandedTitle.top, frame.titleY, 0f)
        assertEquals(51f, frame.titleTextSizePx, 0f)
    }

    @Test
    fun contentWaitsUntilContainerAndTitleAreNearTheirDestinations() {
        val early = frame(0.8f)
        val middle = frame(0.92f)
        val late = frame(0.99f)

        assertEquals(0f, early.contentAlpha, 0f)
        assertTrue(middle.contentAlpha in 0f..1f)
        assertTrue(middle.contentAlpha > early.contentAlpha)
        assertEquals(1f, late.contentAlpha, 0f)
        assertTrue(middle.contentTranslationYPx < early.contentTranslationYPx)
    }

    @Test
    fun predictiveContentUsesWiderRangeThanTimedAnimation() {
        val predictiveStart = frame(0.45f, ContainerMorphContentTiming.PREDICTIVE)
        val predictiveMiddle = frame(0.6f, ContainerMorphContentTiming.PREDICTIVE)
        val predictiveEnd = frame(0.8f, ContainerMorphContentTiming.PREDICTIVE)

        assertEquals(0f, predictiveStart.contentAlpha, 0f)
        assertTrue(predictiveMiddle.contentAlpha in 0f..1f)
        assertTrue(predictiveMiddle.contentAlpha > predictiveStart.contentAlpha)
        assertEquals(1f, predictiveEnd.contentAlpha, 0f)
        assertEquals(0f, frame(0.8f).contentAlpha, 0f)
        assertEquals(
            1f,
            frame(0.8f, ContainerMorphContentTiming.PREDICTIVE).contentAlpha,
            0f
        )
    }

    @Test
    fun progressOutsideRangeIsClampedToEndpoints() {
        assertEquals(frame(0f), frame(-2f))
        assertEquals(frame(1f), frame(3f))
    }

    @Test
    fun closeCurveSettlesAtEndpointAndKeepsBoundedContinuationTime() {
        assertEquals(1f, ContainerMorphSpec.CLOSE_EASING_Y2, 0f)
        assertEquals(1f, ContainerMorphSpec.COMMIT_EASING_Y2, 0f)
        assertTrue(ContainerMorphSpec.CLOSE_EASING_X2 > ContainerMorphSpec.COMMIT_EASING_X1)
        assertEquals(300L, ContainerMorphSpec.closeDurationMs(300L, 1f, 80L))
        assertEquals(150L, ContainerMorphSpec.closeDurationMs(300L, 0.5f, 80L))
        assertEquals(80L, ContainerMorphSpec.closeDurationMs(300L, 0.1f, 80L))
        assertEquals(300L, ContainerMorphSpec.closeDurationMs(300L, 3f, 80L))
    }

    @Test
    fun movingTitleRequiresSingleLineLeftToRightEndpoints() {
        assertTrue(ContainerMorphSpec.canMoveTitle(1, 1, true, true))
        assertEquals(false, ContainerMorphSpec.canMoveTitle(2, 1, true, true))
        assertEquals(false, ContainerMorphSpec.canMoveTitle(1, 2, true, true))
        assertEquals(false, ContainerMorphSpec.canMoveTitle(1, 1, false, true))
        assertEquals(false, ContainerMorphSpec.canMoveTitle(1, 1, true, false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCollapsedBoundsAreRejected() {
        ContainerMorphSpec.frame(
            expansion = 0.5f,
            collapsedBounds = MotionRect(10f, 10f, 10f, 20f),
            expandedBounds = expandedBounds,
            collapsedTitleBounds = collapsedTitle,
            expandedTitleBounds = expandedTitle,
            collapsedTitleTextSizePx = 45f,
            expandedTitleTextSizePx = 51f,
            collapsedCornerRadiusPx = 45f,
            contentTravelPx = 36f
        )
    }

    private fun frame(
        expansion: Float,
        contentTiming: ContainerMorphContentTiming = ContainerMorphContentTiming.TIMED
    ): ContainerMorphFrame =
        ContainerMorphSpec.frame(
            expansion = expansion,
            collapsedBounds = collapsedBounds,
            expandedBounds = expandedBounds,
            collapsedTitleBounds = collapsedTitle,
            expandedTitleBounds = expandedTitle,
            collapsedTitleTextSizePx = 45f,
            expandedTitleTextSizePx = 51f,
            collapsedCornerRadiusPx = 45f,
            contentTravelPx = 36f,
            contentTiming = contentTiming
        )

    /**
     * 形变中正文钉在框顶随框平移（2026-09-24 用户报告"二级页展开/收回时中间两个画面割断"）：
     * 原来正文只按淡入行程平移、不跟框走，缩小的轮廓从页面中段裁出半透明的卡片碎片。
     */
    @Test
    fun morphingContentIsPinnedToTheFrameTop() {
        val host = SourceContract.read("motion/morph/ContainerMorphHost.kt")
        val apply = host.after("fun applyExpansion(").before("fun applyFallbackExpansion(")
        assertTrue(apply.contains("(motionFrame.top - geometry.expandedBounds.top)"))
        // 只平移不缩放：玻璃卡片按屏幕位置采样背景，缩放会让卡片里的背景错位。
        val page = apply.after("currentPage?.apply {").before("}")
        assertTrue(!page.contains("scaleX =") && !page.contains("scaleY ="))
    }

    /**
     * 正文平移/缩放必须通知皮肤重录移动过的玻璃（2026-09-24 真机逐帧：动画结束约 0.35s 后
     * 两张卡片内部反向变色 4～5 级——父层 translation 不让子 View 重录，卡片一直按过期屏幕
     * 位置采样背景，直到抑制解除整组重录才对齐）。
     */
    @Test
    fun movingPageContentNotifiesTheSkin() {
        val host = SourceContract.read("motion/morph/ContainerMorphHost.kt")
        // 三处写正文位移的地方都要比对并回调。
        assertEquals(3, host.split("notifyIfMoved(beforeY,").size - 1)
        assertTrue(host.contains("onContentMoved?.invoke()"))
        // 来源工程由两个目标页各自接线；抽离后由控制器统一接上引擎的位移通知，并在销毁时摘掉。
        val controller = SourceContract.read("motion/morph/ContainerMorphController.kt")
        assertTrue(controller.contains("host.onContentMoved = notifyPositionChanged"))
        assertTrue(controller.contains("this(activity, host, lumen::notifyPositionChanged"))
        assertTrue(controller.contains("host.onContentMoved = null"))
    }
}
