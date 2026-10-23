package com.lumen.coacervation.sample

import android.app.Dialog
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.appcompat.app.AppCompatDelegate
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.background.LiquidBackgroundImportResult
import com.lumen.coacervation.engine.background.LiquidBackgroundStore
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.host.LumenBackdropReadiness
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SkinId
import com.lumen.coacervation.engine.motion.modal.LumenModalPresenter
import com.lumen.coacervation.engine.motion.modal.ModalAnchorStyle
import com.lumen.coacervation.engine.motion.morph.ContainerMorphHost
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Same custom pixels across lifecycle and window boundaries; no dependence on a user's wallpaper. */
@RunWith(AndroidJUnit4::class)
class CustomSkinBackdropContinuityTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val skins get() = if (Build.VERSION.SDK_INT >= 31) listOf(SkinId.MATERIAL_YOU, SkinId.LIQUID)
        else listOf(SkinId.MATERIAL_YOU)

    private fun importFixture() {
        val file = File(context.cacheDir, "motion-continuity-original.png")
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.rgb(18, 205, 230))
                drawCircle(90f, 80f, 55f, Paint().apply { color = Color.rgb(250, 180, 30) })
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        assertTrue(LiquidBackgroundStore.importFromUri(context, Uri.fromFile(file)) is LiquidBackgroundImportResult.Success)
    }

    private fun pixels(root: View): IntArray {
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(image)
            canvas.scale(20f / root.width, 20f / root.height)
            root.background.setBounds(0, 0, root.width, root.height)
            root.background.draw(canvas)
            intArrayOf(image.getPixel(5, 8), image.getPixel(10, 10), image.getPixel(15, 15))
        } finally { image.recycle() }
    }

    private fun warm(scenario: ActivityScenario<SurfaceSandboxActivity>, root: View) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity {
                if (root.width > 0 && root.height > 0) {
                    val color = pixels(root)[1]
                    ready = kotlin.math.abs(Color.green(color) - Color.red(color)) > 60
                }
            }
            if (!ready) SystemClock.sleep(16)
        }
        assertTrue("Custom backdrop never became visible", ready)
        SystemClock.sleep(220) // Let the intentional first-load reveal settle before taking the reference.
    }

    private fun capture(activity: Activity, name: String) {
        val bitmap = Bitmap.createBitmap(360, 792, Bitmap.Config.ARGB_8888)
        val complete = CountDownLatch(1)
        var status = -1
        try {
            instrumentation.runOnMainSync {
                PixelCopy.request(activity.window, bitmap, { status = it; complete.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue(complete.await(3, TimeUnit.SECONDS))
            assertEquals("Window capture failed: $name", PixelCopy.SUCCESS, status)
            val directory = File(context.getExternalFilesDir(null), "motion-review").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (InstrumentationRegistry.getArguments().getString("lumen.captureMotionReview") != "true") return
            // Include the source Activity under a translucent target; Window PixelCopy alone omits it.
            val display = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull("Display capture failed: $name", display)
            try {
                File(directory, "${name}_display.png").outputStream().use {
                    display!!.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { display?.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun realSecondaryActivityKeepsCustomSkinInLightAndDarkAndUsesScreenCorners() {
        val previous = LumenEngine.requestedMaterial(context)
        val night = AppCompatDelegate.getDefaultNightMode()
        importFixture()
        try {
            for (skin in skins) for (dark in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
                    LumenEngine.selectMaterial(context, skin, realtimeCapture = false)
                }
                ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
                    SystemClock.sleep(900)
                    lateinit var title: TextView
                    var originalTitleColor = 0
                    scenario.onActivity { activity ->
                        activity.pager.selectPage(0, animate = false)
                        val found = ArrayList<View>()
                        activity.scrolls[0].findViewsWithText(found, "全屏页", View.FIND_VIEWS_WITH_TEXT)
                        title = found.filterIsInstance<TextView>().single { it.text.toString() == "全屏页" }
                        originalTitleColor = title.currentTextColor
                        title.requestRectangleOnScreen(android.graphics.Rect(0, 0, title.width, title.height), true)
                    }
                    SystemClock.sleep(200)
                    val monitor = instrumentation.addMonitor(DetailActivity::class.java.name, null, false)
                    try {
                        scenario.onActivity {
                            var entry: View = title
                            while (!entry.isClickable && entry.parent is View) entry = entry.parent as View
                            assertTrue(entry.isShown && entry.isClickable)
                            assertTrue(entry.performClick())
                        }
                        val detail = monitor.waitForActivityWithTimeout(5_000) as? DetailActivity
                        assertNotNull("The real detail Activity did not start", detail)
                        val target = detail!!
                        val label = "${skin.name}_${if (dark) "dark" else "light"}"
                        SystemClock.sleep(120)
                        capture(target, "${label}_enter")
                        SystemClock.sleep(650)
                        instrumentation.runOnMainSync {
                            val content = target.findViewById<ViewGroup>(android.R.id.content)
                            val host = content.getChildAt(0) as ContainerMorphHost
                            assertTrue(host.followScreenCorners)
                            assertEquals(1f, host.expansion, .001f)
                            assertTrue(target.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                                if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
                        }
                        capture(target, "${label}_expanded")
                        instrumentation.runOnMainSync { target.onBackPressedDispatcher.onBackPressed() }
                        SystemClock.sleep(80)
                        if (!target.isDestroyed) capture(target, "${label}_exit")
                        val deadline = SystemClock.uptimeMillis() + 5_000
                        while (!target.isDestroyed && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(16)
                        assertTrue("The real detail Activity did not close", target.isDestroyed)
                        scenario.onActivity { assertEquals(originalTitleColor, title.currentTextColor) }
                    } finally { instrumentation.removeMonitor(monitor) }
                }
            }
        } finally {
            LiquidBackgroundStore.restoreAutomatic(context)
            instrumentation.runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(night)
                LumenEngine.selectMaterial(context, previous)
            }
        }
    }

    @Test fun motionReadinessNeverExposesAPaletteOnlyReadyFrame() {
        val previous = LumenEngine.requestedMaterial(context)
        importFixture()
        try {
            for (skin in skins) for (dark in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    LumenEngine.releaseGraphics()
                    LumenEngine.selectMaterial(context, skin, realtimeCapture = false)
                }
                ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
                    lateinit var material: LumenActivityDelegate
                    lateinit var root: FrameLayout
                    scenario.onActivity { activity ->
                        activity.binding.close(); activity.session.close()
                        LumenEngine.releaseGraphics()
                        material = LumenActivityDelegate(activity, { LumenPalette.neutral(dark) })
                        material.prepare()
                        root = FrameLayout(activity)
                        activity.setContentView(root)
                        assertTrue(material.bindRoot(root)); material.onStart()
                        (root.background as LumenBackdropReadiness).prepareForMotion()
                    }
                    try {
                        val deadline = SystemClock.uptimeMillis() + 5_000
                        var ready = false
                        while (!ready && SystemClock.uptimeMillis() < deadline) {
                            scenario.onActivity {
                                if (root.width > 0 && root.height > 0 &&
                                    (root.background as LumenBackdropReadiness).isReadyForMotion) {
                                    val color = pixels(root)[1]
                                    assertTrue("$skin dark=$dark reported ready with only the theme visible",
                                        kotlin.math.abs(Color.green(color) - Color.red(color)) > 60)
                                    ready = true
                                }
                            }
                            if (!ready) SystemClock.sleep(16)
                        }
                        assertTrue("Cold motion preparation did not complete", ready)
                    } finally { scenario.onActivity { material.onDestroy() } }
                }
            }
        } finally {
            LiquidBackgroundStore.restoreAutomatic(context)
            instrumentation.runOnMainSync { LumenEngine.selectMaterial(context, previous) }
        }
    }

    @Test fun customBackdropOwnersKeepTheirPixelsOnResumeAndFirstDrawInBothThemes() {
        val previous = LumenEngine.requestedMaterial(context)
        importFixture()
        try {
            for (skin in skins) for (dark in listOf(false, true)) {
                instrumentation.runOnMainSync { assertTrue(LumenEngine.selectMaterial(context, skin, realtimeCapture = false)) }
                ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
                    lateinit var material: LumenActivityDelegate
                    lateinit var root: FrameLayout
                    scenario.onActivity { activity ->
                        activity.binding.close(); activity.session.close()
                        material = LumenActivityDelegate(activity, { LumenPalette.neutral(dark) })
                        material.prepare()
                        root = FrameLayout(activity)
                        activity.setContentView(root)
                        assertTrue(material.bindRoot(root)); material.onStart()
                    }
                    try {
                        warm(scenario, root)
                        scenario.onActivity { activity ->
                            val expected = pixels(root)
                            material.onStop(); material.onStart()
                            assertArrayEquals("$skin dark=$dark resumed with a palette-only frame", expected, pixels(root))
                            val width = root.width; val height = root.height
                            material.onDestroy()
                            material = LumenActivityDelegate(activity, { LumenPalette.neutral(dark) })
                            material.prepare()
                            root = FrameLayout(activity)
                            activity.setContentView(root)
                            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                            root.layout(0, 0, width, height)
                            assertTrue(material.bindRoot(root)); material.onStart()
                            assertArrayEquals("$skin dark=$dark new owner exposed an unskinned first frame", expected, pixels(root))
                        }
                    } finally { scenario.onActivity { material.onDestroy() } }
                }
            }
        } finally {
            LiquidBackgroundStore.restoreAutomatic(context)
            instrumentation.runOnMainSync { LumenEngine.selectMaterial(context, previous) }
        }
    }

    @Test fun customSkinModalFamiliesKeepTheirBackdropAndRestoreSourceText() {
        val previous = LumenEngine.requestedMaterial(context)
        importFixture()
        try {
            for (skin in skins) for (dark in listOf(false, true)) {
                instrumentation.runOnMainSync { LumenEngine.selectMaterial(context, skin, realtimeCapture = false) }
                ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
                    lateinit var material: LumenActivityDelegate
                    lateinit var root: FrameLayout
                    lateinit var source: TextView
                    lateinit var presenter: LumenModalPresenter
                    var dialog: Dialog? = null
                    var child: Dialog? = null
                    scenario.onActivity { activity ->
                        activity.binding.close(); activity.session.close()
                        material = LumenActivityDelegate(activity, { LumenPalette.neutral(dark) }); material.prepare()
                        root = FrameLayout(activity)
                        source = TextView(activity).apply {
                            text = "Motion"; setTextColor(material.palette.textPrimary)
                            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18f)
                            background = material.cardBackground(); isClickable = true
                        }
                        root.addView(source, FrameLayout.LayoutParams(240, 80).apply { leftMargin = 40; topMargin = 180 })
                        activity.setContentView(root); material.bindRoot(root); material.onStart()
                        presenter = LumenModalPresenter(activity, material)
                    }
                    try {
                        warm(scenario, root)
                        for (family in 0..3) {
                            lateinit var body: LinearLayout
                            lateinit var nested: TextView
                            var expected = intArrayOf()
                            val original = source.textColors
                            scenario.onActivity { activity ->
                                expected = pixels(root)
                                body = presenter.createContainer()
                                body.addView(TextView(activity).apply { text = "Motion"; setTextColor(material.palette.textPrimary) })
                                nested = TextView(activity).apply { text = "Nested"; setTextColor(material.palette.textPrimary) }
                                body.addView(nested, LinearLayout.LayoutParams(-1, 80))
                                dialog = Dialog(activity)
                                presenter.present(dialog!!, body, anchor = if (family == 0) null else source,
                                    anchorStyle = if (family == 2) ModalAnchorStyle.BUBBLE else ModalAnchorStyle.CONTAINER)
                            }
                            SystemClock.sleep(600)
                            if (family == 3) {
                                lateinit var sub: LinearLayout
                                scenario.onActivity { activity ->
                                    val origin = presenter.captureSubPanel(dialog!!, body, nested)
                                    sub = presenter.createContainer().apply {
                                        addView(TextView(activity).apply { text = "Nested"; setTextColor(material.palette.textPrimary) })
                                    }
                                    child = Dialog(activity)
                                    presenter.presentSubPanel(origin, child!!, sub)
                                }
                                SystemClock.sleep(550)
                                scenario.onActivity { presenter.dismiss(child!!, sub) }
                                val deadline = SystemClock.uptimeMillis() + 3_000
                                var closed = false
                                while (!closed && SystemClock.uptimeMillis() < deadline) {
                                    scenario.onActivity { closed = child?.isShowing != true }
                                    if (!closed) SystemClock.sleep(16)
                                }
                                assertTrue("Covered child did not close", closed)
                            }
                            scenario.onActivity { presenter.dismiss(dialog!!, body) }
                            val deadline = SystemClock.uptimeMillis() + 3_000
                            var closed = false
                            while (!closed && SystemClock.uptimeMillis() < deadline) {
                                scenario.onActivity { closed = dialog?.isShowing != true }
                                if (!closed) SystemClock.sleep(16)
                            }
                            assertTrue("Modal family $family did not close", closed)
                            scenario.onActivity {
                                assertSame("$skin dark=$dark family=$family source state colours changed", original, source.textColors)
                                assertEquals(1f, source.alpha, 0f)
                                assertArrayEquals("$skin dark=$dark family=$family changed the stable backdrop", expected, pixels(root))
                            }
                        }
                    } finally { scenario.onActivity { child?.dismiss(); dialog?.dismiss(); material.onDestroy() } }
                }
            }
        } finally {
            LiquidBackgroundStore.restoreAutomatic(context)
            instrumentation.runOnMainSync { LumenEngine.selectMaterial(context, previous) }
        }
    }
}
