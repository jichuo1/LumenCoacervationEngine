package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换上新 drawable 之后必须立刻把 View 当前状态推给它（来源工程 2026-09-22 真机实证）。
 *
 * `SwitchCompat.setThumbDrawable/setTrackDrawable` 与 `CompoundButton.setButtonDrawable` **都不会**把状态推给新
 * drawable，框架只在下一次 `drawableStateChanged()` 时推。冷启动时窗口获焦会补上那一次；而切换材质走 `recreate()`——
 * 新视图在**已获焦**的窗口里挂载，`state_window_focused` 不变，那次刷新永远不来。现场：已开启的开关滑块在右边、
 * 配色却是未选中的灰（`LiquidChoiceDrawable` 收不到 `state_checked`）。
 *
 * 来源工程里还有一条断言应用自定义开关（MaterialSwitch）自取配色后同样刷新——那是宿主控件，
 * 见 docs/INTEGRATION_STANDARD.md §5.3「自定义控件」。
 */
class ControlDrawableStateHandoffTest {

    private fun controlsSource(): String = SourceContract.normalize(
        listOf("../lumen-controls", "lumen-controls")
            .map { File("$it/src/main/java/com/lumen/coacervation/engine/controls/LumenControls.kt") }
            .first(File::isFile)
            .readText()
    )

    @Test fun theControlPassRefreshesStateAfterSwappingDrawables() {
        val pass = controlsSource().after("public fun style(")

        val switchBranch = pass.after("is SwitchCompat -> {").before("is CheckBox ->")
        assertTrue("开关换 drawable 后必须刷新状态", switchBranch.contains("refreshDrawableState()"))
        assertTrue(switchBranch.indexOf("thumbDrawable = choice(") < switchBranch.indexOf("refreshDrawableState()"))

        val checkBoxBranch = pass.after("is CheckBox -> {").before("is EditText ->")
        assertTrue("复选框换 drawable 后必须刷新状态", checkBoxBranch.contains("refreshDrawableState()"))
        assertTrue(checkBoxBranch.indexOf("buttonDrawable = choice(") < checkBoxBranch.indexOf("refreshDrawableState()"))
    }
}
