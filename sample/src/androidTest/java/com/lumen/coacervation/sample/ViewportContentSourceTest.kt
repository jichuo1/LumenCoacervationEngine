package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.LumenSourceAvailability
import com.lumen.coacervation.engine.host.LumenVersionedContentSource
import com.lumen.coacervation.engine.host.LumenViewportContentSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewportContentSourceTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    private data class Stage(val root: FrameLayout, val owner: FrameLayout, val surface: View)

    private fun install(activity: SurfaceSandboxActivity): Stage {
        activity.session.close()
        val root = FrameLayout(activity)
        val owner = FrameLayout(activity).apply {
            addView(object : View(activity) {
                private val paint = Paint()
                override fun onDraw(canvas: Canvas) {
                    val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
                    for (index in colors.indices) {
                        paint.color = colors[index]
                        canvas.drawRect(0f, index * 40f, 160f, (index + 1) * 40f, paint)
                    }
                }
            }, FrameLayout.LayoutParams(160, 160))
        }
        val surface = View(activity)
        root.addView(owner, FrameLayout.LayoutParams(160, 120))
        root.addView(surface, FrameLayout.LayoutParams(80, 24).apply { leftMargin = 20; topMargin = 16 })
        activity.setContentView(root)
        // Explicit pixels keep the fixture independent of emulator density and display width.
        root.layout(0, 0, 200, 180)
        owner.layout(0, 0, 160, 120)
        owner.getChildAt(0).layout(0, 0, 160, 160)
        surface.layout(20, 16, 100, 40)
        return Stage(root, owner, surface)
    }

    private fun bitmap(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }

    private fun assertSamePixels(expected: Bitmap, actual: Bitmap) {
        assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
        val first = IntArray(expected.width * expected.height)
        val second = IntArray(first.size)
        expected.getPixels(first, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(second, 0, actual.width, 0, 0, actual.width, actual.height)
        assertArrayEquals(first, second)
    }

    @Test fun overlayVisibilityFollowsOwnerAndCloseCannotReviveIt() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val stage = install(activity)
                val children = stage.owner.childCount
                val wrapper = LumenViewportContentSource(stage.owner) { use -> use(stage.surface, 0f, 0f, 0f) }
                val proxy = wrapper.coordinateView
                try {
                    assertNotSame(stage.owner, proxy)
                    assertEquals(0f, proxy.alpha, 0f)
                    assertTrue(stage.owner.isShown)
                    assertTrue("OverlayViewGroup lacks a normal parent chain; proxy must use owner visibility", proxy.isShown)
                    assertEquals(children, stage.owner.childCount)
                    stage.owner.visibility = View.INVISIBLE
                    assertFalse(proxy.isShown)
                    stage.owner.visibility = View.VISIBLE
                    assertTrue(proxy.isShown)
                    wrapper.close()
                    assertNull(proxy.parent)
                    assertFalse(proxy.isShown)
                    assertFalse(wrapper.sync())
                    assertEquals(LumenSourceAvailability.PROHIBITED, wrapper.availability)
                    val image = bitmap(80, 24, wrapper::drawContent)
                    try { assertEquals(Color.TRANSPARENT, image.getPixel(20, 12)) } finally { image.recycle() }
                    assertEquals(children, stage.owner.childCount)
                    assertEquals(View.VISIBLE, stage.owner.visibility)
                    assertEquals(1f, stage.owner.alpha, 0f)
                } finally { wrapper.close() }
            }
        }
    }

    @Test fun croppedSourceAndForegroundProbeMatchTheOwnerIncludingItsOwnScroll() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val stage = install(activity)
                stage.owner.layout(12, 28, 172, 148)
                stage.owner.translationX = 3.5f
                stage.owner.scaleX = .85f
                stage.owner.scaleY = .9f
                stage.owner.rotation = 3f
                stage.owner.pivotX = 17f
                stage.owner.pivotY = 21f
                val wrapper = LumenViewportContentSource(stage.owner) { use -> use(stage.surface, 0f, 0f, 4f) }
                try {
                    for (scroll in intArrayOf(0, 40, 80)) {
                        stage.owner.scrollTo(0, scroll)
                        wrapper.sync()
                        val proxy = wrapper.coordinateView
                        val full = bitmap(160, 120) { canvas ->
                            canvas.translate(0f, -scroll.toFloat())
                            stage.owner.draw(canvas)
                        }
                        val expected = Bitmap.createBitmap(full, proxy.left, proxy.top, proxy.width, proxy.height)
                        val source = bitmap(proxy.width, proxy.height, wrapper::drawContent)
                        val probe = bitmap(proxy.width, proxy.height, proxy::draw)
                        // Exercise the engine's own matrix bridge, not just the proxy's layout.
                        val toOwner = sourceToTarget(proxy, stage.owner)
                        val restored = bitmap(160, 120) { canvas ->
                            canvas.concat(toOwner)
                            canvas.drawBitmap(source, 0f, 0f, null)
                        }
                        val clippedOwner = bitmap(160, 120) { canvas ->
                            canvas.clipRect(proxy.left, proxy.top, proxy.right, proxy.bottom)
                            canvas.drawBitmap(full, 0f, 0f, null)
                        }
                        try {
                            assertSamePixels(expected, source); assertSamePixels(expected, probe)
                            assertSamePixels(clippedOwner, restored)
                        } finally {
                            expected.recycle(); full.recycle(); source.recycle(); probe.recycle()
                            restored.recycle(); clippedOwner.recycle()
                        }
                    }
                } finally { wrapper.close() }
            }
        }
    }

    /** The sample is a separate module; reflection exposes only the engine's internal geometry to the regression. */
    private fun sourceToTarget(source: View, target: View): Matrix {
        val type = Class.forName("com.lumen.coacervation.engine.geometry.ViewSamplingMatrix")
        val mapping = type.getDeclaredConstructor().newInstance()
        val matrix = Matrix()
        val method = type.getMethod("sourceToTarget", View::class.java, View::class.java, Matrix::class.java)
        assertEquals(true, method.invoke(mapping, source, target, matrix))
        return matrix
    }

    private class BackgroundSource(override val coordinateView: View) : LumenVersionedContentSource, AutoCloseable {
        override var sourceEpoch = 2L
        override var contentVersion = 3L
        override var availability = LumenSourceAvailability.READY
        override val excludesSurfaces = true
        var draws = 0
        var closed = false
        var failDrawing = false
        override fun drawContent(canvas: Canvas) {
            if (failDrawing) throw IllegalStateException("fixture drawing")
            draws++; canvas.drawColor(Color.MAGENTA)
        }
        override fun close() { closed = true }
    }

    @Test fun customPixelsAndNativeForegroundRemainSeparateAndVersionsStayPure() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val stage = install(activity)
                val original = BackgroundSource(stage.owner)
                val wrapper = LumenViewportContentSource(original) { use -> use(stage.surface, 0f, 0f, 0f) }
                try {
                    assertSame(original, wrapper.dependencies.single())
                    assertEquals(original.sourceEpoch, wrapper.sourceEpoch)
                    val initial = wrapper.contentVersion
                    val source = bitmap(80, 24, wrapper::drawContent)
                    val probe = bitmap(80, 24, wrapper.coordinateView::draw)
                    try {
                        assertEquals(Color.MAGENTA, source.getPixel(20, 12))
                        assertEquals(Color.RED, probe.getPixel(20, 12))
                    } finally { source.recycle(); probe.recycle() }
                    val recovered = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(recovered)
                        val saveCount = canvas.saveCount
                        original.failDrawing = true
                        try { wrapper.drawContent(canvas); fail("Fixture source must throw") }
                        catch (_: IllegalStateException) { assertEquals(saveCount, canvas.saveCount) }
                        original.failDrawing = false
                        wrapper.drawContent(canvas)
                        assertEquals("The drawing guard must recover after a source exception", Color.MAGENTA, recovered.getPixel(20, 12))
                    } finally { original.failDrawing = false; recovered.recycle() }
                    stage.surface.invalidate(); stage.owner.invalidate()
                    repeat(5) { assertFalse(wrapper.sync()); assertEquals(initial, wrapper.contentVersion) }
                    repeat(5) {
                        val before = wrapper.contentVersion
                        original.contentVersion++
                        assertNotEquals(before, wrapper.contentVersion)
                    }
                    original.sourceEpoch++
                    assertEquals(original.sourceEpoch, wrapper.sourceEpoch)
                    original.availability = LumenSourceAvailability.TEMPORARILY_UNAVAILABLE
                    assertEquals(original.availability, wrapper.availability)
                    original.availability = LumenSourceAvailability.READY
                    if (Build.VERSION.SDK_INT >= 29) {
                        val draws = original.draws
                        HardwareProbe.draw(wrapper.coordinateView)
                        assertEquals("Hardware overlay recording must not sample the source", draws, original.draws)
                    }
                } finally { wrapper.close() }
                assertFalse("Closing the wrapper must not close its caller-owned dependency", original.closed)
            }
        }
    }

    @Test fun continuousSurfaceMovementUpdatesTheCropAndStaticGeometryConverges() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val stage = install(activity)
                val original = BackgroundSource(stage.owner)
                val wrapper = LumenViewportContentSource(original) { use -> use(stage.surface, 2f, 3f, 1f) }
                try {
                    var previous = wrapper.contentVersion
                    repeat(30) { frame ->
                        stage.surface.translationY = frame + .25f
                        assertTrue(wrapper.sync())
                        assertEquals(18 + frame, wrapper.coordinateView.top)
                        assertEquals(39 + frame, wrapper.coordinateView.bottom)
                        assertNotEquals(previous, wrapper.contentVersion)
                        previous = wrapper.contentVersion
                        assertFalse(wrapper.sync())
                        assertEquals(previous, wrapper.contentVersion)
                    }
                } finally { wrapper.close() }
            }
        }
    }

    @Test fun hiddenDescendantSurfaceIsStillRejectedAndProviderFailureRecovers() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val stage = install(activity)
                val nested = View(activity).apply { visibility = View.INVISIBLE }
                stage.owner.addView(nested, FrameLayout.LayoutParams(20, 20))
                var current = nested
                var fail = false
                val wrapper = LumenViewportContentSource(stage.owner) { use ->
                    if (fail) throw IllegalStateException("fixture")
                    use(current, 0f, 0f, 0f)
                }
                try {
                    assertEquals(LumenSourceAvailability.PROHIBITED, wrapper.availability)
                    val image = bitmap(160, 120, wrapper::drawContent)
                    try { assertEquals(Color.TRANSPARENT, image.getPixel(20, 12)) } finally { image.recycle() }
                    current = stage.surface
                    wrapper.sync()
                    assertEquals(LumenSourceAvailability.READY, wrapper.availability)
                    fail = true; wrapper.sync()
                    assertEquals(LumenSourceAvailability.TEMPORARILY_UNAVAILABLE, wrapper.availability)
                    fail = false; wrapper.sync()
                    assertEquals(LumenSourceAvailability.READY, wrapper.availability)
                } finally { wrapper.close() }
            }
        }
    }

    @Test fun nonContainerSourceRetainsItsAnchorAndClipsInSourceCoordinates() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val stage = install(activity)
                val leaf = View(activity).apply { setBackgroundColor(Color.CYAN) }
                stage.root.addView(leaf, FrameLayout.LayoutParams(160, 120))
                leaf.layout(0, 0, 160, 120)
                val wrapper = LumenViewportContentSource(leaf) { use -> use(stage.surface, 0f, 0f, 0f) }
                try {
                    assertSame(leaf, wrapper.coordinateView)
                    val image = bitmap(160, 120, wrapper::drawContent)
                    try {
                        assertEquals(Color.CYAN, image.getPixel(30, 20))
                        assertEquals(Color.TRANSPARENT, image.getPixel(0, 0))
                        assertEquals(Color.TRANSPARENT, image.getPixel(120, 60))
                    } finally { image.recycle() }
                } finally { wrapper.close() }
                assertSame(stage.root, leaf.parent)
            }
        }
    }

    @RequiresApi(29)
    private object HardwareProbe {
        fun draw(view: View) {
            val node = android.graphics.RenderNode("viewport-probe-test")
            node.setPosition(0, 0, view.width, view.height)
            val recording = node.beginRecording(view.width, view.height)
            try { view.draw(recording) } finally { node.endRecording(); node.discardDisplayList() }
        }
    }
}
