package com.lumen.coacervation.sample

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.background.LiquidBackgroundImportResult
import com.lumen.coacervation.engine.background.LiquidBackgroundStore
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.SkinId
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 演示包的冒烟测试：在模拟器上把每一页、每类面板与形变、长按拖动、所有设置项都走一遍。
 *
 * 断言以"不崩溃、状态可恢复"为主：任何主线程异常都会让进程崩溃，整次运行随之失败。
 * 视觉效果（折射是否好看）不在这里验证。
 */
@RunWith(AndroidJUnit4::class)
class DemoSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetState() {
        clearPreferences()
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            LumenEngine.selectMaterial(context, SkinId.MATERIAL_YOU, realtimeCapture = false)
        }
    }

    @After
    fun restoreState() {
        instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
        runCatching { LiquidBackgroundStore.restoreAutomatic(context) }
        clearPreferences()
    }

    @Test
    fun everyPageRendersAndScrollsInBothMaterials() {
        for (material in SkinId.entries) {
            selectMaterial(material)
            ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
                settle(800)
                val pages = onUi(scenario) { it.scrolls.size }
                assertEquals(6, pages)
                for (page in 0 until pages) {
                    onUi(scenario) { it.pager.selectPage(page) }
                    settle(700)
                    onUi(scenario) { it.scrolls[page].fullScroll(View.FOCUS_DOWN) }
                    settle(500)
                    onUi(scenario) { it.scrolls[page].fullScroll(View.FOCUS_UP) }
                    settle(400)
                }
                // 重建后停留在同一页（改设置、切材质都靠这一点）。
                scenario.recreate()
                settle(800)
                assertEquals(pages - 1, onUi(scenario) { it.pager.selectedPage })
            }
        }
    }

    @Test
    fun panelsBubblesAndFullscreenMorphOpenAndClose() {
        ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
            settle(800)
            // 锚定气泡（顶栏"关于"图标）。
            clickOnUi(scenario) { findByDescription(it.window.decorView, "关于") }
            settle(900)
            assertNotNull(onUi(scenario) { it.modals.activeDialog })
            pressBack()
            settle(900)

            // 条目 → 卡片形变，再从面板里打开覆盖式子面板，然后逐层返回。
            clickOnUi(scenario) { clickableAncestor(findByText(it.scrolls[0], "形变面板")) }
            settle(900)
            clickOnUi(scenario) { findByText(requireNotNull(it.modals.activeDialog).window!!.decorView, "ⓘ") }
            settle(900)
            pressBack()
            settle(900)
            pressBack()
            settle(900)

            // 表面角色色块与排布页的卡片都能形变出面板。
            clickOnUi(scenario) { clickableAncestor(findByText(it.scrolls[0], "弹窗")) }
            settle(900)
            pressBack()
            settle(900)
            onUi(scenario) { it.pager.selectPage(3, animate = false) }
            settle(500)
            clickOnUi(scenario) { clickableAncestor(findByText(it.scrolls[3], "方块 A")) }
            settle(900)
            pressBack()
            settle(900)

            // 条目 → 全屏页的容器形变，返回时收回来源条目。
            onUi(scenario) { it.pager.selectPage(0, animate = false) }
            settle(500)
            clickOnUi(scenario) { clickableAncestor(findByText(it.scrolls[0], "全屏页")) }
            settle(1500)
            pressBack()
            settle(1500)
        }
    }

    @Test
    fun motionPageAccordionScrubAndRevealRun() {
        ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
            settle(800)
            onUi(scenario) { it.pager.selectPage(1, animate = false) }
            settle(500)
            val header = onUi(scenario) { clickableAncestor(findByText(it.scrolls[1], "手风琴（点按展开/收起，可随时打断）")) }
            // 展开到一半立刻收起：从中间态倒带。
            onUi(scenario) { header.performClick() }
            settle(120)
            onUi(scenario) { header.performClick() }
            settle(700)
            onUi(scenario) { header.performClick() }
            settle(700)
            clickOnUi(scenario) { findByText(it.scrolls[1], "显示/隐藏角标") }
            settle(400)
            clickOnUi(scenario) { findByText(it.scrolls[1], "切换文字") }
            settle(400)
            clickOnUi(scenario) { findByText(it.scrolls[1], "定位到页底的目标") }
            settle(1500)
            onUi(scenario) { it.pager.selectPage(3, animate = false) }
            settle(500)
            clickOnUi(scenario) { findByText(it.scrolls[3], "定位到轮播第 7 张（先竖向，再横向）") }
            settle(1800)
        }
    }

    @Test
    fun longPressDragWorksAtEveryTuningExtreme() {
        val extremes = listOf(
            LumenEffectTuning.DEFAULT,
            LumenEffectTuning(dragDeformation = 0f, dragGlowIntensity = 0f, dragGlowRadius = LumenEffectTuning.MIN_DRAG_GLOW_RADIUS),
            LumenEffectTuning(
                edgeHighlightWidth = LumenEffectTuning.MAX_EDGE_WIDTH,
                edgeHighlightIntensity = LumenEffectTuning.MAX_EDGE_INTENSITY,
                dragDeformation = LumenEffectTuning.MAX_DRAG_DEFORMATION,
                dragGlowIntensity = LumenEffectTuning.MAX_DRAG_GLOW_INTENSITY,
                dragGlowRadius = LumenEffectTuning.MAX_DRAG_GLOW_RADIUS
            ),
            LumenEffectTuning(edgeHighlightWidth = LumenEffectTuning.MIN_EDGE_WIDTH, edgeHighlightIntensity = 0f)
        )
        for (material in SkinId.entries) {
            for (tuning in extremes) {
                Log.i(TAG, "drag round: $material $tuning")
                selectMaterial(material)
                SampleTuningStore(context).update(tuning)
                ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
                    settle(800)
                    val row = onUi(scenario) { clickableAncestor(findByText(it.scrolls[0], "形变面板")) }
                    longPressDrag(row, dxDp = 60f, dyDp = 40f)
                    settle(900)
                    closePanel(scenario)
                    // 排布页：网格方块朝邻居拖、轮播卡片横向拖。
                    onUi(scenario) { it.pager.selectPage(3, animate = false) }
                    settle(500)
                    longPressDrag(onUi(scenario) { clickableAncestor(findByText(it.scrolls[3], "方块 A")) }, 120f, 0f)
                    settle(900)
                    closePanel(scenario)
                    longPressDrag(onUi(scenario) { clickableAncestor(findByText(it.scrolls[3], "方块 D")) }, -120f, -120f)
                    settle(900)
                    closePanel(scenario)
                    // 底栏自带的按压拖动与光晕。
                    val dock = onUi(scenario) { findByDescriptionPrefix(it.window.decorView, "材质") }
                    longPressDrag(dock, dxDp = 180f, dyDp = 0f)
                    settle(1000)
                    closePanel(scenario)
                    assertTrue(onUi(scenario) { !it.isFinishing })
                }
            }
        }
    }

    @Test
    fun everySettingRecreatesCleanly() {
        ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
            settle(800)
            onUi(scenario) { it.pager.selectPage(5, animate = false) }
            settle(400)
            // 强调色逐个切换（每次重建，重建后仍在设置页）。
            for (accent in SampleAccent.entries) {
                SampleSettingsStore(context).accent = accent
                scenario.recreate()
                settle(700)
                assertEquals(5, onUi(scenario) { it.pager.selectedPage })
            }
            // 弹窗背景模糊：开着打开一次弹窗。
            SampleSettingsStore(context).modalBackdropBlur = true
            scenario.recreate()
            settle(700)
            onUi(scenario) { it.pager.selectPage(0, animate = false) }
            settle(400)
            clickOnUi(scenario) { clickableAncestor(findByText(it.scrolls[0], "形变面板")) }
            settle(900)
            pressBack()
            settle(900)
            // 深浅色：AppCompat 自动重建。
            for (mode in listOf(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.MODE_NIGHT_NO)) {
                instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(mode) }
                settle(1200)
                assertEquals(6, onUi(scenario) { it.scrolls.size })
            }
            // 实时取样开关（只影响高级材质）。
            instrumentation.runOnMainSync { LumenEngine.selectMaterial(context, SkinId.LIQUID, realtimeCapture = true) }
            scenario.recreate()
            settle(1200)
            onUi(scenario) { it.pager.selectPage(0, animate = false) }
            settle(600)
            onUi(scenario) { it.scrolls[0].fullScroll(View.FOCUS_DOWN) }
            settle(800)
        }
    }

    @Test
    fun customBackgroundImportsAndRestores() {
        val file = File(context.cacheDir, "smoke-background.png")
        val bitmap = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(40, 90, 160))
            drawCircle(320f, 480f, 260f, android.graphics.Paint().apply { color = Color.rgb(240, 180, 90) })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val result = LiquidBackgroundStore.importFromUri(context, Uri.fromFile(file))
        assertTrue("import failed: $result", result is LiquidBackgroundImportResult.Success)
        selectMaterial(SkinId.LIQUID)
        ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
            settle(1200)
            onUi(scenario) { it.pager.selectPage(5, animate = false) }
            settle(600)
        }
        assertTrue(LiquidBackgroundStore.restoreAutomatic(context))
        file.delete()
    }

    @Test
    fun rotationKeepsPageAndAdaptsLayout() {
        ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
            settle(800)
            onUi(scenario) { it.pager.selectPage(4, animate = false) }
            settle(400)
            onUi(scenario) { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            settle(2000)
            assertEquals(4, onUi(scenario) { it.pager.selectedPage })
            // 横屏里自适应页的双栏（若宽度够）或单栏都能点。
            onUi(scenario) { it.scrolls[4].fullScroll(View.FOCUS_DOWN) }
            settle(600)
            onUi(scenario) { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            settle(2000)
            assertEquals(4, onUi(scenario) { it.pager.selectedPage })
            onUi(scenario) { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            settle(800)
        }
    }

    // ---------------- 工具 ----------------

    private fun selectMaterial(material: SkinId) {
        instrumentation.runOnMainSync {
            LumenEngine.selectMaterial(context, material, realtimeCapture = material == SkinId.LIQUID)
        }
    }

    private fun clearPreferences() {
        listOf("sample_effect_tuning", "sample_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private companion object {
        const val TAG = "DemoSmokeTest"
    }

    private fun settle(millis: Long) {
        SystemClock.sleep(millis)
        instrumentation.waitForIdleSync()
    }

    private fun <T> onUi(scenario: ActivityScenario<SampleActivity>, block: (SampleActivity) -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { result = runCatching { block(it) } }
        return requireNotNull(result).getOrThrow()
    }

    private fun clickOnUi(scenario: ActivityScenario<SampleActivity>, find: (SampleActivity) -> View) {
        onUi(scenario) { activity -> assertTrue(find(activity).performClick()) }
    }

    /** 拖动若没被判定为长按拖动，会以点击结束并打开面板；关掉它，免得挡住后续注入的触摸。 */
    private fun closePanel(scenario: ActivityScenario<SampleActivity>) {
        if (onUi(scenario) { it.modals.activeDialog } != null) {
            pressBack()
            settle(900)
        }
    }

    private fun pressBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    /** 真实的触摸流：按下、停留过长按阈值、分步拖动、抬起。坐标取 View 当前在屏幕上的中心。 */
    private fun longPressDrag(view: View, dxDp: Float, dyDp: Float) {
        Log.i(TAG, "longPressDrag ${view.javaClass.simpleName} (${dxDp}dp, ${dyDp}dp)")
        val location = IntArray(2)
        var density = 1f
        instrumentation.runOnMainSync {
            view.getLocationOnScreen(location)
            density = view.resources.displayMetrics.density
        }
        val startX = location[0] + view.width / 2f
        val startY = location[1] + view.height / 2f
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY)
        SystemClock.sleep(320)
        val steps = 16
        for (step in 1..steps) {
            val fraction = step / steps.toFloat()
            inject(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                startX + dxDp * density * fraction, startY + dyDp * density * fraction)
            SystemClock.sleep(16)
        }
        // 反向拖回一半：长按光晕的"反向不转轴"路径。
        for (step in 1..8) {
            val fraction = 1f - step / 16f
            inject(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                startX + dxDp * density * fraction, startY + dyDp * density * fraction)
            SystemClock.sleep(16)
        }
        inject(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
            startX + dxDp * density * .5f, startY + dyDp * density * .5f)
    }

    private fun inject(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private fun findByText(root: View, text: String): View =
        requireNotNull(find(root) { it is TextView && it.text?.toString() == text && it.isShown }) { "text not found: $text" }

    private fun findByDescription(root: View, description: String): View =
        requireNotNull(find(root) { it.contentDescription?.toString() == description && it.isShown }) {
            "description not found: $description"
        }

    /** 底栏按钮的无障碍描述以标题开头；取不到时退回底栏整体。 */
    private fun findByDescriptionPrefix(root: View, prefix: String): View =
        find(root) { it.contentDescription?.toString()?.startsWith(prefix) == true && it.isShown && it.isClickable }
            ?: requireNotNull(find(root) { it.javaClass.simpleName == "LumenNavigationBar" }) { "navigation bar not found" }

    private fun clickableAncestor(view: View): View {
        var current: View? = view
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent as? View
        }
        error("no clickable ancestor for $view")
    }

    private fun find(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                find(root.getChildAt(index), predicate)?.let { return it }
            }
        }
        return null
    }
}
