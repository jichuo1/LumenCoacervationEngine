package com.lumen.coacervation.engine.interaction

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按拖动弹性手势的两条约定：
 * 1. 捕获门槛看**命中控件**的 pressed 状态——形变组可能是无点击语义的外层包裹
 *    （GitHub 图标的角标 FrameLayout），它永远不 pressed，拿它判会把长按拖动误杀；
 * 2. 普通装饰角标不独占手势：保持不可点击、不消费事件，不设置阻断命中的 `EXCLUDED_TAG`；
 *    需要整枚图标一起形变时由宿主提供共同包装/表面归属，见 docs/INTEGRATION_STANDARD.md §12.1。
 */
class ElasticCaptureGateTest {

    @Test fun captureGateChecksTheHitViewNotThePromotedGroup() {
        val controller = source(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/interaction/ElasticInteractionController.kt")
        assertTrue(controller.contains("val pressedView = highlightHost ?: target"))
        assertTrue(controller.contains("if (pressedView?.isPressed != true)"))
        assertFalse(controller.contains("if (target?.isPressed != true)"))
    }

    @Test fun hitViewIsAssignedAfterTheVisualTeardown() {
        val controller = source(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/interaction/ElasticInteractionController.kt")
        val begin = controller.after("private fun begin(event: MotionEvent)")
            .before("private fun activatePreparedPress()")
        // removeVisual 会把 highlightHost 清空；命中控件必须在它之后赋值。
        // 提前赋值时每次新按压 highlightHost 都是 null，捕获门槛退回到形变组
        // （GitHub 图标是外层 FrameLayout，永不 pressed），长按拖动被静默清掉。
        assertTrue(begin.contains("highlightHost = view"))
        assertTrue(begin.indexOf("highlightHost = view") >
            begin.indexOf("removeVisual(restore = true, releaseLease = true)"))
    }

    @Test fun crossChildRecaptureOnlyHandsOffTheChildVisualWithinTheExistingGroupLease() {
        val controller = MotionSource.file("ElasticInteractionController")
        val begin = MotionSource.functions(controller, "begin").single()
        val continuing = begin.after("if (continuing) {").before("} else {")
        assertTrue(continuing.contains("if (highlightHost !== view)"))
        assertTrue(continuing.indexOf("highlight?.forView(view)") < continuing.indexOf("releaseHighlight()"))
        assertTrue(continuing.contains("view.addOnAttachStateChangeListener(targetAttachListener)"))
        for (forbidden in listOf("removeVisual(", "leases.acquire(", "pressAxis.reset(", "overflowPortal =")) {
            assertFalse(forbidden, continuing.contains(forbidden))
        }
        val release = MotionSource.functions(controller, "releaseHighlight").single()
        assertTrue(release.contains("host?.removeOnAttachStateChangeListener(targetAttachListener)"))
        assertTrue(release.contains("host?.overlay?.remove(it)"))
        assertTrue(controller.contains("if (view === highlightHost) clear()"))
    }


    private fun source(relative: String): String =
        MotionSource.file(relative.substringAfterLast('/').removeSuffix(".kt"))
}
