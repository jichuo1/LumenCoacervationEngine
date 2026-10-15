package com.lumen.coacervation.sample

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.host.LumenMicaBackdrop
import com.lumen.coacervation.engine.host.LumenMicaFallbackReason as Reason
import com.lumen.coacervation.engine.host.LumenMicaImage
import com.lumen.coacervation.engine.host.LumenMicaKind
import com.lumen.coacervation.engine.host.LumenMicaLayer
import com.lumen.coacervation.engine.host.LumenMicaOptions
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs on the smoke API matrix. Bitmap/Canvas assertions verify actual rendered pixels and ownership. */
@RunWith(AndroidJUnit4::class)
class MicaIntegrationTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()
    private val active = LumenMicaOptions(reduceMotion = true, fallbackWhenWindowInactive = false,
        fallbackOnLowRamDevice = false, fallbackOnPowerSave = false)

    private fun ready(scenario: ActivityScenario<MicaSandboxActivity>) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { ready = it.currentImage != null && it.root.width > 0 && it.root.isAttachedToWindow }
            if (!ready) SystemClock.sleep(30)
        }
        assertTrue("Mica wallpaper not prepared", ready)
        scenario.onActivity { it.mica.update(active) }
    }

    private fun pixel(view: View): Int {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(32f / view.width, 32f / view.height)
        view.background.draw(canvas)
        val pixel = bitmap.getPixel(16, 16)
        bitmap.recycle()
        return pixel
    }

    @Test fun backgroundPreparationIsBoundedBorrowedAndMainThreadGuarded() {
        val source = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val image = LumenMicaImage.prepare(source) // Instrumentation test thread, not UI.
        assertTrue(image.byteCount <= 36 * 1024)
        assertEquals(Color.RED, source.getPixel(0, 0))
        source.recycle()
        ActivityScenario.launch(MicaSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                val input = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                try {
                    try { LumenMicaImage.prepare(input); fail("Main-thread preparation must fail") }
                    catch (_: IllegalStateException) { /* expected */ }
                } finally { input.recycle() }
                // The prepared copy survives recycling or mutation of the caller's bitmap.
                activity.mica.setImage(image)
                activity.mica.update(active.copy(tintOpacity = 0f, luminosityOpacity = 0f))
                assertEquals(Color.RED, pixel(activity.root))
            }
        }
    }

    @Test fun fallbackColoursAreOpaqueAndHighContrastHonoursHostPalette() {
        ActivityScenario.launch(MicaSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { a ->
                for (dark in listOf(false, true)) for (kind in LumenMicaKind.entries) {
                    a.mica.updatePalette(LumenPalette.neutral(dark))
                    a.mica.update(active.copy(kind = kind, reduceTransparency = true))
                    val expected = if (dark) {
                        if (kind == LumenMicaKind.BASE) 0xFF202020.toInt() else 0xFF0A0A0A.toInt()
                    } else if (kind == LumenMicaKind.BASE) 0xFFF3F3F3.toInt() else 0xFFDADADA.toInt()
                    assertEquals(expected, pixel(a.root))
                    assertEquals(Reason.REDUCE_TRANSPARENCY, a.mica.diagnostics().fallbackReason)
                }
                a.mica.updatePalette(LumenPalette.neutral(false).copy(background = 0xFF102030.toInt()))
                a.mica.update(active.copy(highContrast = true, fallbackColor = Color.RED))
                assertEquals(0xFF102030.toInt(), pixel(a.root))
                assertEquals(Reason.HIGH_CONTRAST, a.mica.diagnostics().fallbackReason)
            }
        }
    }

    @Test fun activeBaseAndAltRenderDifferentColoursAndRepeatedDrawsDoNotCreateSnapshots() {
        ActivityScenario.launch(MicaSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { a ->
                val base = pixel(a.root)
                val bytes = a.mica.diagnostics().snapshotBytes
                val transitions = a.mica.diagnostics().transitions
                repeat(20) { assertEquals(base, pixel(a.root)) }
                assertEquals(bytes, a.mica.diagnostics().snapshotBytes)
                assertEquals(transitions, a.mica.diagnostics().transitions)
                assertTrue(bytes in 1..(72 * 1024))
                assertTrue(a.mica.diagnostics().micaVisible)
                assertEquals(255, base ushr 24)
                a.mica.update(active.copy(kind = LumenMicaKind.BASE_ALT))
                assertNotEquals(base, pixel(a.root))
                assertEquals(bytes, a.mica.diagnostics().snapshotBytes)
            }
        }
    }

    @Test fun layerFillsUseWinUiTokensWithoutDuplicatingTheBackdrop() {
        ActivityScenario.launch(MicaSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { a ->
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                try {
                    val layer = a.mica.layerBackground(LumenMicaLayer.CONTENT, 0f)
                    layer.setBounds(0, 0, 32, 32)
                    layer.draw(Canvas(bitmap))
                    assertEquals(0x80FFFFFF.toInt(), bitmap.getPixel(16, 16))
                    a.mica.update(active.copy(kind = LumenMicaKind.BASE_ALT))
                    val command = a.mica.layerBackground(LumenMicaLayer.COMMAND, 0f)
                    bitmap.eraseColor(Color.TRANSPARENT)
                    command.setBounds(0, 0, 32, 32)
                    command.draw(Canvas(bitmap))
                    assertEquals(0xB3FFFFFF.toInt(), bitmap.getPixel(16, 16))
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun memoryPressureDropsSnapshotsUntilHostExplicitlyRestoresTheImage() {
        ActivityScenario.launch(MicaSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { a ->
                val image = a.currentImage!!
                LumenEngine.releaseGraphics()
                assertEquals(0, a.mica.diagnostics().snapshotBytes)
                assertEquals(Reason.MEMORY_PRESSURE, a.mica.diagnostics().fallbackReason)
                a.mica.updatePalette(LumenPalette.neutral(true))
                assertEquals(0, a.mica.diagnostics().snapshotBytes)
                a.mica.setImage(image)
                assertEquals(Reason.NONE, a.mica.diagnostics().fallbackReason)
                assertEquals(255, pixel(a.root) ushr 24)
                a.mica.setImage(null)
                assertEquals(Reason.NO_BACKGROUND, a.mica.diagnostics().fallbackReason)
            }
        }
    }

    @Test fun closeRestoresOnlyOwnedBackgroundAndPreservesPaddingAndLateCalls() {
        ActivityScenario.launch(MicaSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { a ->
                a.mica.close()
                val original = ColorDrawable(Color.RED)
                a.root.background = original
                a.root.setPadding(11, 12, 13, 14)
                val mica = LumenMicaBackdrop(a.root, LumenPalette.neutral(false), active)
                assertEquals(11, a.root.paddingLeft)
                assertEquals(14, a.root.paddingBottom)
                mica.close()
                assertSame(original, a.root.background)
                assertEquals(12, a.root.paddingTop)
                assertEquals(Reason.CLOSED, mica.diagnostics().fallbackReason)
                mica.update(active)
                mica.updatePalette(LumenPalette.neutral(true))
                mica.setImage(a.currentImage)
                mica.refreshPolicy()
                mica.close()
                val second = LumenMicaBackdrop(a.root, LumenPalette.neutral(false), active)
                val replacement = ColorDrawable(Color.BLUE)
                a.root.background = replacement
                second.close()
                assertSame(replacement, a.root.background)
            }
        }
    }

    @Test fun windowFocusPolicyCanBeRestoredWithoutPreparingAnotherImage() {
        ActivityScenario.launch(MicaSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            var dialog: Dialog? = null
            var bytes = 0
            scenario.onActivity { a ->
                bytes = a.mica.diagnostics().snapshotBytes
                a.mica.update(active.copy(fallbackWhenWindowInactive = true))
                dialog = Dialog(a).apply { setContentView(TextView(a).apply { text = "Focus policy test" }); show() }
            }
            try {
                waitForReason(scenario, Reason.WINDOW_INACTIVE)
                scenario.onActivity { a ->
                    assertEquals(0xFFF3F3F3.toInt(), pixel(a.root))
                    assertEquals(bytes, a.mica.diagnostics().snapshotBytes)
                    dialog!!.dismiss()
                }
                waitForReason(scenario, Reason.NONE)
                scenario.onActivity { assertEquals(bytes, it.mica.diagnostics().snapshotBytes) }
            } finally { scenario.onActivity { dialog?.dismiss() } }
        }
    }

    private fun waitForReason(scenario: ActivityScenario<MicaSandboxActivity>, reason: Reason) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var actual: Reason? = null
        do {
            scenario.onActivity { actual = it.mica.diagnostics().fallbackReason }
            if (actual == reason) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(reason, actual)
    }

    @Test fun gpuWindowOutputMatchesThePreparedMaterial() {
        ActivityScenario.launch(MicaSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            var expected = 0
            scenario.onActivity { a ->
                a.root.removeAllViews()
                expected = pixel(a.root)
            }
            try {
                val deadline = SystemClock.uptimeMillis() + 5_000
                var actual = 0
                do {
                    val latch = CountDownLatch(1)
                    var code = PixelCopy.ERROR_UNKNOWN
                    scenario.onActivity { a ->
                        val location = IntArray(2)
                        a.root.getLocationInWindow(location)
                        val x = location[0] + a.root.width / 2
                        val y = location[1] + a.root.height / 2
                        PixelCopy.request(a.window, android.graphics.Rect(x - 16, y - 16, x + 16, y + 16), image,
                            { result -> code = result; latch.countDown() }, Handler(Looper.getMainLooper()))
                    }
                    assertTrue(latch.await(5, TimeUnit.SECONDS))
                    if (code == PixelCopy.SUCCESS) actual = image.getPixel(16, 16)
                    if (closeColour(expected, actual)) break
                    SystemClock.sleep(50)
                } while (SystemClock.uptimeMillis() < deadline)
                assertTrue("GPU ${Integer.toHexString(actual)} != CPU ${Integer.toHexString(expected)}", closeColour(expected, actual))
            } finally { image.recycle() }
        }
    }

    private fun closeColour(expected: Int, actual: Int): Boolean {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            if (kotlin.math.abs((expected shr shift and 255) - (actual shr shift and 255)) > 3) return false
        }
        return true
    }
}
