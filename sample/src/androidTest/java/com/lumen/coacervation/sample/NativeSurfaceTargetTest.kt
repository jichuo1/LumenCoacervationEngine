package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.*
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises draw targets sharing one real Window anchor, without allocating a View per surface. */
@RunWith(AndroidJUnit4::class)
class NativeSurfaceTargetTest {
    private class Target(override val coordinateView: View, var x: Float, var y: Float) : LumenSurfaceTarget {
        override val width = 64
        override val height = 64
        var attached = true
        var invalidations = 0
        private val location = IntArray(2)
        override val isAttachedToWindow get() = attached && coordinateView.isAttachedToWindow
        override val isShown get() = attached && coordinateView.isShown
        override val alpha = 1f
        override val layoutDirection = View.LAYOUT_DIRECTION_LTR
        override fun localToScreen(out: FloatArray): Boolean {
            if (!isAttachedToWindow) return false
            coordinateView.getLocationOnScreen(location)
            out.fill(0f)
            out[0] = 1f; out[4] = 1f; out[8] = 1f
            out[2] = location[0] + x; out[5] = location[1] + y
            return true
        }
        override fun invalidate() { invalidations++; coordinateView.invalidate() }
        // Registration must use identity even for callers with value equality.
        override fun equals(other: Any?) = other is Target
        override fun hashCode() = 1
    }

    private class Source(override val coordinateView: View) : LumenVersionedContentSource {
        private val paint = Paint()
        override val excludesSurfaces = true
        override val observeCoordinateViewInvalidations = false
        override val sourceEpoch = 0L
        override val contentVersion = 0L
        override val availability = LumenSourceAvailability.READY
        override fun drawContent(canvas: Canvas) {
            paint.color = Color.RED
            canvas.drawRect(0f, 0f, coordinateView.width / 2f, coordinateView.height.toFloat(), paint)
            paint.color = Color.BLUE
            canvas.drawRect(coordinateView.width / 2f, 0f, coordinateView.width.toFloat(), coordinateView.height.toFloat(), paint)
        }
    }

    private fun ready(scenario: ActivityScenario<SurfaceSandboxActivity>) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { ready = it.preview.width > 256 && it.preview.height > 128 && it.preview.hasWindowFocus() }
            if (!ready) SystemClock.sleep(20)
        }
        assertTrue("Window anchor did not become visible", ready)
    }

    @Test fun independentTargetsPreserveTheHostAndClosingOneKeepsTheOtherBound() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                val background = activity.preview.background
                val count = activity.preview.childCount
                LumenSurfaceSession(activity, LumenPalette.neutral(false)).use { session ->
                    val first = Target(activity.preview, 0f, 0f)
                    val a = session.bindTarget(first)
                    val b = session.bindTarget(Target(activity.preview, 100f, 0f))
                    assertNotEquals(a.id, b.id)
                    assertEquals(2, session.diagnostics().attachedSurfaces)
                    assertSame(background, activity.preview.background)
                    assertEquals(count, activity.preview.childCount)
                    val beforeClose = first.invalidations
                    a.close()
                    assertEquals("Closing a draw target must remove its cached frame", beforeClose + 1, first.invalidations)
                    a.close()
                    assertEquals("Repeated close must not keep invalidating", beforeClose + 1, first.invalidations)
                    assertFalse(a.isBound)
                    assertTrue(b.isBound)
                    assertEquals(1, session.diagnostics().attachedSurfaces)
                    b.close()
                    assertEquals(0, session.diagnostics().attachedSurfaces)
                }
                assertSame(background, activity.preview.background)
            }
        }
    }

    @Test fun softwareSamplingUsesEachTargetsPositionAndStopsWhenStatic() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            lateinit var session: LumenSurfaceSession
            lateinit var left: Target
            lateinit var right: Target
            lateinit var a: LumenSurfaceBinding
            lateinit var b: LumenSurfaceBinding
            val first = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val second = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val firstCanvas = Canvas(first)
            val secondCanvas = Canvas(second)
            scenario.onActivity { activity ->
                session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
                val source = Source(activity.preview)
                val surface = LumenSurfaceOptions(radiusDp = 0f, tintEnabled = false, edgeEnabled = false,
                    sampling = LumenSurfaceSampling(backend = LumenSurfaceBackend.SOFTWARE, minIntervalMs = 0,
                        refractionEnabled = false, blurEnabled = false))
                left = Target(activity.preview, 20f, 20f)
                right = Target(activity.preview, activity.preview.width - 84f, 20f)
                a = session.bindTarget(left, surface, source)
                b = session.bindTarget(right, surface, source)
                a.draw(firstCanvas); b.draw(secondCanvas)
            }
            try {
                val deadline = SystemClock.uptimeMillis() + 5000
                var sampled = false
                while (!sampled && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity {
                        a.draw(firstCanvas); b.draw(secondCanvas)
                        sampled = a.diagnostics()?.backend == LumenSurfaceBackend.SOFTWARE &&
                            b.diagnostics()?.backend == LumenSurfaceBackend.SOFTWARE
                    }
                    if (!sampled) SystemClock.sleep(20)
                }
                assertTrue("Independent targets never acquired their software inputs", sampled)
                assertTrue(Color.red(first.getPixel(32, 32)) > 220)
                assertTrue(Color.blue(first.getPixel(32, 32)) < 40)
                assertTrue(Color.blue(second.getPixel(32, 32)) > 220)
                assertTrue(Color.red(second.getPixel(32, 32)) < 40)
                var recordings = 0L
                SystemClock.sleep(100)
                scenario.onActivity { recordings = session.diagnostics().contentRecordings }
                SystemClock.sleep(300)
                scenario.onActivity { assertEquals("Idle targets kept sampling", recordings, session.diagnostics().contentRecordings) }
                scenario.onActivity {
                    a.close(); left.attached = false
                    val invalidations = left.invalidations
                    session.notifyContentChanged()
                    b.draw(secondCanvas)
                    assertEquals(invalidations, left.invalidations)
                    assertTrue(b.isBound)
                }
            } finally { scenario.onActivity { session.close() } }
        }
    }
}
