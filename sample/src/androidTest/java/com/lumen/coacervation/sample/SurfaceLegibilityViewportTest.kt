package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Picture
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.LumenSurfaceLegibilityController
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenViewportContentSource
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceLegibilityViewportTest {
    @Test fun controllerMapsTheProbeIntoAnOffsetScaledOwnerAndKeepsPendingEntriesAligned() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.session.close()
                val root = FrameLayout(activity)
                val owner = FrameLayout(activity).apply {
                    setBackgroundColor(Color.GREEN)
                    pivotX = 0f; pivotY = 0f
                    scaleX = .5f; scaleY = .5f
                }
                val surface = View(activity)
                val invalidMatrix = Matrix().apply {
                    setValues(floatArrayOf(Float.NaN, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
                }
                val invalid = object : View(activity) {
                    override fun getMatrix(): Matrix = invalidMatrix
                }
                root.addView(owner, FrameLayout.LayoutParams(160, 120))
                root.addView(surface, FrameLayout.LayoutParams(40, 20))
                root.addView(invalid, FrameLayout.LayoutParams(20, 20))
                activity.setContentView(root)
                root.layout(0, 0, 300, 300)
                owner.layout(80, 140, 240, 260)
                surface.layout(90, 160, 130, 180)
                invalid.layout(0, 0, 20, 20)
                val source = LumenViewportContentSource(owner) { use -> use(surface, 0f, 0f, 0f) }
                val session = LumenSurfaceSession(activity, LumenPalette.neutral(true))
                val controller = LumenSurfaceLegibilityController(source.coordinateView, Color.BLACK, Color.GRAY)
                try {
                    val options = LumenSurfaceOptions(material = LumenSurfaceMaterial.STATIC, color = Color.BLACK)
                    controller.bind(surface, session.bindSource(surface, options, source), options)
                    controller.bind(invalid, session.bindSource(invalid, options, source), options)
                    // Invoke the controller's actual frame, including its region collection and probe.
                    controller.javaClass.getDeclaredMethod("frame").apply { isAccessible = true }.invoke(controller)
                    val entries = field(controller, "entries") as Map<*, *>
                    val pending = field(controller, "pending") as List<*>
                    assertEquals("An invalid transform must not consume a sample index", 1, pending.size)
                    assertSame(entries[surface], pending.single())
                    val probe = requireNotNull(field(controller, "probe"))
                    val slots = field(probe, "slots") as List<*>
                    val slot = requireNotNull(slots.single())
                    // The 40x20 screen surface is 80x40 in the scaled owner's local coordinates.
                    assertEquals(20, field(slot, "width"))
                    assertEquals(10, field(slot, "height"))
                    val image = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(image).drawPicture(field(slot, "picture") as Picture)
                        assertEquals("The probe must read the owner rather than an out-of-crop fallback", Color.GREEN,
                            image.getPixel(10, 5))
                    } finally { image.recycle() }
                } finally {
                    controller.close(); session.close(); source.close()
                }
            }
        }
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}
