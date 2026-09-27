package com.lumen.coacervation.engine.motion.modal

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹窗动画（锚点形变的内容平移、无锚点的缩放）会移动卡片里玻璃表面的屏幕位置，而属性动画
 * 不触发滚动回调、不重录子 View 显示列表。不逐帧通知皮肤的话，按钮的采样停在动画中途的原点，
 * 动画结束约半秒后才被无关刷新补上，表现为"弹窗打开半秒后按钮光影跳一下"
 * （2026-09-26 真机：形变路径插桩确认原点 2273 → 2236，修复后录屏 0 跳变）。
 */
class ModalSkinPositionContractTest {
    private val main = MotionSource.file("LumenModalPresenter")

    @Test fun defaultModalEntryNotifiesSkinEveryFrameAndAtTheEnd() {
        val entry = main.after("container.post {\n            container.animate()\n                .scaleX(1f).scaleY(1f).alpha(1f)")
            .before("private companion object")
        val update = entry.after(".setUpdateListener {").before(".withEndAction {")
        assertTrue(update.contains("lumen.notifyPositionChanged()"))
        assertTrue(entry.after(".withEndAction {").contains("lumen.notifyPositionChanged()"))
    }

    @Test fun sharedExitAnimationNotifiesSkinEveryFrame() {
        val exit = main.after("public fun dismiss(").before(".setListener(")
        assertTrue(exit.contains("lumen.notifyPositionChanged()"))
    }

    @Test fun predictiveBackScaleAndBounceNotifySkin() {
        val progress = main.after("container.scaleX = 1f - 0.05f * progress").before("onCancelled = {")
        assertTrue(progress.contains("lumen.notifyPositionChanged()"))
        val bounce = main.after("container.scaleX = 1f - 0.05f * progress")
            .after("onCancelled = {").before("onInvoked = {")
        assertTrue(bounce.contains("lumen.notifyPositionChanged()"))
        // 黏性监听器：回弹显式设置时必须带上原本隐式沿用的模糊与压暗推进。
        assertTrue(bounce.contains("backdropBlur?.apply(container.alpha)"))
    }


    /** 锚点形变每帧平移卡片内容：平移写入之后必须通知皮肤（真机实证的跳变来源）。 */
    @Test fun anchoredMorphNotifiesSkinAfterMovingContent() {
        val motion = MotionSource.file("IconAnchoredMotionController")
        val frame = motion.after("content.translationX = frame.contentTranslationXPx").before("titleMotion?.apply(clamped)")
        assertTrue(frame.contains("onContentMoved()"))
        val settle = motion.after("private fun settleExpanded()").before("content.elevation = contentElevation")
        assertTrue(settle.indexOf("content.translationY = 0f") < settle.indexOf("onContentMoved()"))
        assertTrue(main.contains("onContentMoved = { lumen.notifyPositionChanged() }"))
    }

    /**
     * 弹窗窗口里的玻璃移动/形变不触发 Liquid 的实时采样抑制：被截的主窗口内容是静止的，弹窗形变层也不在
     * 主窗口截图里。抑制会让每次开关弹窗整组失效两次，解除那次真机实测主页面一帧 37ms、弹窗被拖到 40ms。
     * 主窗口自己的滚动与二级页形变仍按原规则抑制。
     */
    @Test fun dialogWindowMotionDoesNotSuppressMainWindowSampling() {
        val liquid = MotionSource.engineFile("LiquidActivityRenderer")
        val flush = liquid.after("private fun flushSurfaceRefresh(").before("private fun isSurfacePotentiallyVisible(")
        assertTrue(flush.contains("if (originChanged && windowRoot === mainWindowRoot) surfaceMoved = true"))
        val register = liquid.after("internal fun registerSurfaceView(").before("footprint.update(bounds, radiusPx, originX, originY)")
        assertTrue(register.contains("view is LiquidMotionSurfaceFrameProvider && view.rootView === boundRoot?.rootView"))
    }
}
