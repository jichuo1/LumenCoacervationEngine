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
 * 2. 角标之类的叠加控件不独占手势，整枚图标（含角标）作为一个形变组——那是宿主义务（给角标打
 *    `EXCLUDED_TAG`），见 docs/INTEGRATION_STANDARD.md §12.1，来源工程里对应的用例不迁。
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


    private fun source(relative: String): String =
        MotionSource.file(relative.substringAfterLast('/').removeSuffix(".kt"))
}
