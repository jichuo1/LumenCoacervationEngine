package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.LumenBackdropReadiness
import com.lumen.coacervation.engine.motion.MotionRect
import com.lumen.coacervation.engine.motion.morph.ContainerMorphController
import com.lumen.coacervation.engine.motion.morph.ContainerMorphGeometry
import com.lumen.coacervation.engine.motion.morph.ContainerMorphHost
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScreenCornerMorphTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()

    @Test fun optInUsesEachWindowCornerAndDisablingItRestoresLegacyGeometry() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = ContainerMorphHost(activity, Color.WHITE, Color.WHITE, Color.BLACK, "")
                host.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 240, 400); host.showExpandedImmediately()
                fun pixel(x: Int, y: Int): Int {
                    val bitmap = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888)
                    return try { host.draw(Canvas(bitmap)); bitmap.getPixel(x, y) } finally { bitmap.recycle() }
                }
                assertFalse(host.followScreenCorners)
                assertEquals(Color.WHITE, pixel(10, 10))
                host.followScreenCorners = true
                if (Build.VERSION.SDK_INT >= 31) {
                    val insets = CornerInsetsTestApi31.insets(240, 400)
                    host.onApplyWindowInsets(insets)
                    assertEquals("The larger top corner must be clipped: ${CornerInsetsTestApi31.describe(host, insets)}",
                        0, Color.alpha(pixel(10, 10)))
                    assertEquals("The smaller bottom corner must retain this pixel", Color.WHITE, pixel(10, 390))
                    assertEquals(Color.WHITE, pixel(120, 200))
                } else assertEquals("Missing platform data must retain the old shape", Color.WHITE, pixel(10, 10))
                host.followScreenCorners = false
                assertEquals(Color.WHITE, pixel(10, 10))
            }
        }
    }

    @Test fun coldBackdropDefersEntryWithoutAnExpandedThemeFrameAndThenCompletes() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var controller: ContainerMorphController
            val expanded = CountDownLatch(1)
            var started = 0
            var ready = false
            var preparationRequested = false
            try {
                scenario.onActivity { activity ->
                    activity.binding.close(); activity.session.close()
                    val parent = activity.findViewById<ViewGroup>(android.R.id.content)
                    val width = parent.width.coerceAtLeast(240)
                    val height = parent.height.coerceAtLeast(400)
                    val host = ContainerMorphHost(activity, Color.WHITE, Color.WHITE, Color.BLACK, "")
                    activity.setContentView(host)
                    host.replacePage(View(activity), TextView(activity))
                    host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    host.layout(0, 0, width, height)
                    host.liquidBackdropRoot().background = object : ColorDrawable(Color.CYAN), LumenBackdropReadiness {
                        override fun prepareForMotion() { preparationRequested = true }
                        override val isReadyForMotion: Boolean get() = ready
                    }
                    val source = MotionRect(20f, height * .5f, 220f, height * .5f + 80f)
                    val title = MotionRect(30f, height * .5f + 20f, 180f, height * .5f + 40f)
                    controller = ContainerMorphController(activity, host, {}, DetailActivity::class.java,
                        null, false, { null }, onMotionStarted = { started++ },
                        onExpanded = { expanded.countDown() }, hasLaunchOrigin = { true },
                        resolveGeometry = { ContainerMorphGeometry(source, MotionRect(0f, 0f, width.toFloat(), height.toFloat()),
                            title, MotionRect(20f, 20f, 180f, 40f), 18f, 18f, 40f, 20f, false) })
                    controller.start(true)
                    assertTrue(preparationRequested)
                    host.viewTreeObserver.dispatchOnPreDraw()
                    assertEquals("Cold custom pixels must not start the coloured surface", 0, started)
                    assertEquals(0f, host.expansion, 0f)
                    val image = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(image)
                        canvas.scale(24f / width, 24f / height)
                        host.draw(canvas)
                        assertEquals(0, Color.alpha(image.getPixel(12, 12)))
                    } finally { image.recycle() }
                    ready = true; host.invalidate()
                }
                assertTrue("Entry did not resume after the background became ready", expanded.await(5, TimeUnit.SECONDS))
                scenario.onActivity { assertEquals(1, started); assertTrue(controller.isSettledExpanded) }
            } finally { scenario.onActivity { controller.onDestroy() } }
        }
    }
}

@RequiresApi(31)
private object CornerInsetsTestApi31 {
    fun describe(host: View, insets: WindowInsets): String {
        val location = IntArray(2); host.getLocationInWindow(location)
        return "location=${location.contentToString()} bounds=${host.width}x${host.height} " +
            (0..3).joinToString { insets.getRoundedCorner(it).toString() }
    }

    fun insets(width: Int, height: Int): WindowInsets {
        return WindowInsets.Builder()
            .setRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT,
                android.view.RoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT, 60, 60, 60))
            .setRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT,
                android.view.RoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT, 60, width - 60, 60))
            .setRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT,
                android.view.RoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT, 20, width - 20, height - 20))
            .setRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT,
                android.view.RoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT, 20, 20, height - 20))
            .build()
    }
}
