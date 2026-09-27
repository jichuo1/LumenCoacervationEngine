package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽离时新做的决定（来源工程里没有对应测试）：
 * 编排从 Activity 抽成呈现器/控制器后，最容易丢的是**顺序**与**记账**，这里把它们钉住。
 */
class MotionExtractionContractTest {

    /** 不依赖 AppCompat：开关类判定泛化为 CompoundButton，复选框/单选框仍参与弹性（与来源工程行为一致）。 */
    @Test fun elasticSwitchExclusionIsGeneralizedWithoutAppCompat() {
        val controller = MotionSource.file("ElasticInteractionController")
        assertTrue(controller.contains("view is CompoundButton && view !is CheckBox && view !is RadioButton"))
        assertTrue(controller.contains("isSwitchLike(view) || !stableTransformAtDown(view)"))
        for ((name, text) in MotionSource.files()) {
            assertFalse(name, text.contains("androidx.appcompat"))
            assertFalse(name, text.contains("androidx.recyclerview"))
        }
    }

    /** 零反射（ENGINEERING_RULES §3）：来源工程按窗口开关预测式返回的反射实现没有迁入。 */
    @Test fun motionLayerUsesNoReflection() {
        for ((name, text) in MotionSource.files()) {
            for (forbidden in listOf("Class.forName", "getDeclaredMethod", "getDeclaredField", "kavaref", "setAccessible")) {
                assertFalse("$name uses $forbidden", text.contains(forbidden))
            }
        }
    }

    /** 覆盖式子面板：两张矩形原样交给呈现逻辑；子面板里"去别处"的动作与自己的退场并行收起父面板。 */
    @Test fun subPanelApiForwardsBothRectanglesAndClosesTheParentInParallel() {
        val presenter = MotionSource.file("LumenModalPresenter")
        val sub = presenter.after("public fun presentSubPanel(").before("public fun dismissParent(")
        assertTrue(sub.contains("anchorBounds = origin.anchorBounds, coverBounds = origin.coverBounds"))
        val capture = presenter.after("public fun captureSubPanel(").before("public fun presentSubPanel(")
        assertTrue(capture.contains("anchorBounds(source), source.declaredCornerRadius(),"))
        assertTrue(capture.contains("surfaceBounds(parentDialog, parentContainer), parentDialog, parentContainer"))
        // 子面板的折叠端圆角同样在点击那一刻取（适配标准 §14.2）。
        assertTrue(sub.contains("anchorCornerRadiusPx = origin.anchorCornerRadiusPx"))
        val parent = presenter.after("public fun dismissParent(").before("// ---------------- 关闭")
        assertTrue(parent.contains("if (origin.parentDialog.isShowing) dismiss(origin.parentDialog, origin.parentContainer) {}"))
    }

    /** 两条真机顺序：返回回调在 show() 之后注册；窗口动画在 setContentView 之后关掉。 */
    @Test fun presenterKeepsTheWindowOrderingLessons() {
        val present = MotionSource.function("present")
        val show = present.indexOf("dialog.show()")
        assertTrue(show > 0)
        assertTrue(present.indexOf("registerBackCallback()\n", show) > show)
        assertTrue(present.indexOf("dialog.window?.setWindowAnimations(0)") > present.indexOf("dialog.setContentView(windowFrame)"))
        // 宿主自己的 OnDismissListener 会被覆盖：收尾一律在这里做（适配标准 §13.1）。
        assertEquals(1, Regex("dialog\\.setOnDismissListener").findAll(present).count())
    }

    /** 来源几何按目标页分开登记；目标页控制器每次都现解析，不缓存来源。 */
    @Test fun containerMorphOriginsAreKeyedByDestination() {
        val origin = MotionSource.file("ContainerMorphOrigin")
        assertTrue(origin.contains("registries.getOrPut(destination.name) { ContainerMorphOriginRegistry() }"))
        val launcher = MotionSource.file("ContainerMorphController")
        assertTrue(launcher.contains("ContainerMorphOriginRegistry.of(destination)"))
        assertTrue(launcher.contains("registry.snapshot()?.putInto(intent)"))
        // 退出时优先读当前可见坐标（旋转/滚动后的最新位置），入场时优先读启动时带来的几何。
        assertTrue(launcher.contains("if (preferLiveOrigin) sequenceOf(liveOrigin, launchOrigin) else sequenceOf(launchOrigin, liveOrigin)"))
    }
}
