package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.PixelCopy
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.motion.modal.ModalCardRoot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PanelEmbeddedViewportTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()
    @Test fun limitedPanelScrollKeepsNaturalHeightAndWrapsOnce() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var scroll: com.lumen.coacervation.engine.widget.LumenMaxHeightScrollView
            lateinit var body: View
            var installations = 0
            scenario.onActivity { activity ->
                body = View(activity).apply { minimumHeight = 40 }
                scroll = com.lumen.coacervation.engine.widget.LumenMaxHeightScrollView(activity, 180).apply {
                    addView(body, FrameLayout.LayoutParams(-1, -2))
                }
                installPanel(activity, scroll)
                scroll.layoutParams.height = -2
                val root = (activity.findViewById<View>(android.R.id.content) as FrameLayout).getChildAt(0)
                fun prepare() = com.lumen.coacervation.engine.motion.modal.LumenPanelScroll.prepare(root) {
                    installations++
                    com.lumen.coacervation.engine.liquid.LiquidStretchViewport.installAround(it, { true }) { _, _ -> }
                }
                prepare()
                prepare()
                assertEquals(1, installations)
            }
            awaitFrame(scenario)
            scenario.onActivity {
                assertEquals("Short contents must not be stretched to the cap", 40, scroll.height)
                assertEquals(40, (scroll.parent as View).height)
                body.minimumHeight = 700
                body.requestLayout()
            }
            awaitFrame(scenario)
            scenario.onActivity {
                assertEquals(180, scroll.height)
                assertEquals(180, (scroll.parent as View).height)
                scroll.scrollTo(0, 300)
                assertTrue(scroll.scrollY > 0)
            }
        }
    }

    @Test fun inlineScrollMasksAllCornersAndTracksRelayout() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var scroll: ScrollView
            scenario.onActivity { activity ->
                scroll = ScrollView(activity).apply {
                    setBackgroundColor(Color.RED)
                    addView(View(activity).apply { setBackgroundColor(Color.RED); minimumHeight = 700 }, FrameLayout.LayoutParams(192, 700))
                }
                installPanel(activity, scroll)
            }
            checkCorners(scenario, "inline-scroll")
            scenario.onActivity { scroll.layoutParams.height = 220; scroll.requestLayout() }
            checkCorners(scenario, "inline-scroll-relayout", height = 220)
            scenario.onActivity {
                scroll.scrollTo(0, 400)
                assertTrue("Engine clipping must preserve ordinary scrolling", scroll.scrollY > 0)
            }
        }
    }

    @Test fun decoratedEmbeddedPageMasksItsBackgroundAndKeepsInnerListSquare() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var list: RecyclerView
            lateinit var page: FrameLayout
            scenario.onActivity { activity ->
                list = RecyclerView(activity)
                page = FrameLayout(activity).apply {
                    setBackgroundColor(Color.RED)
                    addView(list, FrameLayout.LayoutParams(192, 180))
                }
                installPanel(activity, page)
            }
            checkCorners(scenario, "embedded-page")
            scenario.onActivity {
                assertTrue(page.clipToOutline)
                assertFalse("The embedded page owns the outline; its inner list keeps a square content viewport", list.clipToOutline)
            }
        }
    }

    @Test fun explicitChildOutlineRemainsAuthoritativeAcrossLayouts() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var scroll: ScrollView
            val provider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 12f)
                }
            }
            scenario.onActivity { activity ->
                scroll = ScrollView(activity).apply {
                    outlineProvider = provider
                    clipToOutline = true
                    addView(View(activity), FrameLayout.LayoutParams(192, 700))
                }
                installPanel(activity, scroll)
            }
            awaitFrame(scenario)
            scenario.onActivity { assertSame(provider, scroll.outlineProvider); scroll.requestLayout() }
            awaitFrame(scenario)
            scenario.onActivity { assertSame(provider, scroll.outlineProvider) }
        }
    }

    private fun installPanel(activity: SurfaceSandboxActivity, page: View) {
        activity.session.close()
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = ElasticInteractionController.CONTAINER_TAG
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 40f }
            setPadding(24, 24, 24, 24)
            addView(page, LinearLayout.LayoutParams(192, 180))
        }
        activity.setContentView(ModalCardRoot(activity).apply {
            setBackgroundColor(Color.WHITE)
            addView(panel, FrameLayout.LayoutParams(240, -2).apply { leftMargin = 16; topMargin = 80 })
        })
    }

    private fun awaitFrame(scenario: ActivityScenario<SurfaceSandboxActivity>) {
        val frame = CountDownLatch(1)
        val committed = Runnable { frame.countDown() }
        var root: View? = null
        var observer: ViewTreeObserver? = null
        var sawDraw = false
        val drawn = ViewTreeObserver.OnDrawListener {
            if (!sawDraw) { sawDraw = true; root?.post(committed) }
        }
        try {
            scenario.onActivity { activity ->
                root = activity.window.decorView
                assertTrue("The fixture must use the actual hardware window", root!!.isHardwareAccelerated)
                observer = root!!.viewTreeObserver
                if (Build.VERSION.SDK_INT >= 29) observer!!.registerFrameCommitCallback(committed)
                else observer!!.addOnDrawListener(drawn)
                root!!.postInvalidateOnAnimation()
            }
            assertTrue("A committed hardware frame must arrive", frame.await(5, TimeUnit.SECONDS))
        } finally {
            scenario.onActivity {
                root?.removeCallbacks(committed)
                observer?.takeIf { it.isAlive }?.let {
                    if (Build.VERSION.SDK_INT >= 29) it.unregisterFrameCommitCallback(committed)
                    else it.removeOnDrawListener(drawn)
                }
            }
        }
    }

    private fun checkCorners(scenario: ActivityScenario<SurfaceSandboxActivity>, name: String, height: Int = 180) {
        repeat(3) { attempt ->
            awaitFrame(scenario)
            val ready = CountDownLatch(1)
            val bitmap = Bitmap.createBitmap(240, height + 48, Bitmap.Config.ARGB_8888)
            var result = -1
            var abandoned = false
            var inFlight = false
            val ownership = Any()
            try {
                scenario.onActivity { activity ->
                    val root = (activity.findViewById<View>(android.R.id.content) as FrameLayout).getChildAt(0) as ModalCardRoot
                    val panel = root.getChildAt(0)
                    val origin = IntArray(2)
                    panel.getLocationInWindow(origin)
                    assertEquals(bitmap.width, panel.width)
                    assertEquals(bitmap.height, panel.height)
                    assertTrue("The entire source must fit in the 320px emulator window",
                        origin[0] >= 0 && origin[0] + panel.width <= activity.window.decorView.width)
                    synchronized(ownership) { inFlight = true }
                    PixelCopy.request(activity.window, Rect(origin[0], origin[1], origin[0] + panel.width, origin[1] + panel.height), bitmap,
                        { code ->
                            synchronized(ownership) {
                                result = code
                                inFlight = false
                                if (abandoned) bitmap.recycle()
                            }
                            ready.countDown()
                        }, Handler(Looper.getMainLooper()))
                }
                assertTrue("PixelCopy must complete", ready.await(5, TimeUnit.SECONDS))
                if (result == PixelCopy.ERROR_SOURCE_NO_DATA && attempt < 2) return@repeat
                assertEquals(PixelCopy.SUCCESS, result)
                scenario.onActivity { activity ->
                    java.io.File(activity.getExternalFilesDir(null), "$name.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                assertEquals("Embedded interior remains visible", Color.RED, bitmap.getPixel(100, 100))
                for ((x, y) in arrayOf(25 to 25, 214 to 25, 25 to height + 22, 214 to height + 22)) {
                    assertEquals("$name corner $x,$y follows the panel's round outline", Color.WHITE, bitmap.getPixel(x, y))
                }
                return
            } finally {
                synchronized(ownership) { abandoned = true; if (!inFlight && !bitmap.isRecycled) bitmap.recycle() }
            }
        }
        fail("No committed hardware frame")
    }
}
