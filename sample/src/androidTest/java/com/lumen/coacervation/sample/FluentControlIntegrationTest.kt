package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.LumenAcrylicBlend
import com.lumen.coacervation.engine.host.LumenAcrylicFallbackReason
import com.lumen.coacervation.engine.host.LumenAcrylicOptions
import com.lumen.coacervation.engine.host.LumenFluentControl
import com.lumen.coacervation.engine.host.LumenFluentControlOptions
import com.lumen.coacervation.engine.host.LumenFluentElevation
import com.lumen.coacervation.engine.host.LumenFluentElevationOptions
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Runs on every smoke API level, so API 27 also proves the API 29 blend class is never loaded there. */
@RunWith(AndroidJUnit4::class)
class FluentControlIntegrationTest {
    private val control = LumenFluentControlOptions(acrylic = LumenAcrylicOptions(enabled = false),
        elevation = LumenFluentElevationOptions(level = LumenFluentElevation.CONTROL), reduceMotion = true)

    private fun waitFor(scenario: ActivityScenario<FluentSandboxActivity>, condition: (FluentSandboxActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { ready = condition(it) }
            if (!ready) SystemClock.sleep(30)
        }
        assertTrue("condition not reached", ready)
    }

    private fun laidOut(scenario: ActivityScenario<FluentSandboxActivity>) =
        waitFor(scenario) { it.buttonViews[0].width > 0 && it.buttonViews[0].isAttachedToWindow && it.buttonViews[0].hasWindowFocus() }

    @Test fun installAndCloseRestoreOnlyOwnedState() {
        ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
            laidOut(scenario)
            scenario.onActivity { activity ->
                val view = activity.buttonViews[0]
                activity.buttons[0].close()
                val original = ColorDrawable(Color.RED)
                view.background = original
                view.setPadding(11, 12, 13, 14)
                view.elevation = 3f
                val fluent = LumenFluentControl(view, LumenPalette.neutral(false), control)
                assertNotSame(original, view.background)
                assertEquals(11, view.paddingLeft); assertEquals(14, view.paddingBottom)
                assertEquals(0.5f * view.resources.displayMetrics.density, view.elevation, 0.01f)
                fluent.close()
                assertSame(original, view.background)
                assertEquals(12, view.paddingTop); assertEquals(13, view.paddingRight)
                assertEquals(3f, view.elevation, 0f)
                fluent.close()   // idempotent
                assertEquals(LumenAcrylicFallbackReason.CLOSED, fluent.diagnostics().fallbackReason)

                // Changes the host made afterwards are not reverted.
                val second = LumenFluentControl(view, LumenPalette.neutral(false), control)
                val replacement = ColorDrawable(Color.BLUE)
                view.background = replacement
                view.elevation = 7f
                second.close()
                assertSame(replacement, view.background)
                assertEquals(7f, view.elevation, 0f)
            }
        }
    }

    @Test fun pressingLowersControlElevation() {
        ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
            laidOut(scenario)
            scenario.onActivity { activity ->
                val view = activity.buttonViews[0]
                val density = view.resources.displayMetrics.density
                assertEquals(0.5f * density, view.elevation, 0.01f)
                view.isPressed = true
                assertEquals(0.25f * density, view.elevation, 0.01f)
                view.isPressed = false
                assertEquals(0.5f * density, view.elevation, 0.01f)
            }
        }
    }

    @Test fun policyFallbacksAndBlendSelection() {
        ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
            laidOut(scenario)
            scenario.onActivity { activity ->
                val diagnostics = activity.flyout.diagnostics()
                assertEquals(if (Build.VERSION.SDK_INT >= 29) LumenAcrylicBlend.EXACT else LumenAcrylicBlend.APPROXIMATE, diagnostics.blend)
                assertEquals(16 * 1024, diagnostics.noiseTextureBytes)
                // Buttons were built without acrylic: solid fallback, no shared noise reference.
                assertEquals(LumenAcrylicFallbackReason.DISABLED, activity.buttons[0].diagnostics().fallbackReason)
                assertEquals(0, activity.buttons[0].diagnostics().noiseTextureBytes)
                activity.flyout.update(LumenFluentControlOptions(reduceTransparency = true, reduceMotion = true))
                assertEquals(LumenAcrylicFallbackReason.REDUCE_TRANSPARENCY, activity.flyout.diagnostics().fallbackReason)
                activity.flyout.update(LumenFluentControlOptions(reduceMotion = true, acrylic = LumenAcrylicOptions(exactBlend = false)))
                assertEquals(LumenAcrylicBlend.APPROXIMATE, activity.flyout.diagnostics().blend)
                val noSession = LumenFluentControl(activity.panel, LumenPalette.neutral(false))
                assertEquals(LumenAcrylicFallbackReason.NO_BACKDROP, noSession.diagnostics().fallbackReason)
                noSession.close()
            }
        }
    }

    @Test fun reducedTransparencyPaintsTheOpaqueFallbackColour() {
        ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
            laidOut(scenario)
            scenario.onActivity { it.flyout.update(LumenFluentControlOptions(reduceTransparency = true, reduceMotion = true)) }
            waitFor(scenario) { it.flyout.diagnostics().draws > 0 && !it.flyout.diagnostics().acrylicVisible }
            scenario.onActivity { activity ->
                val panel = activity.panel
                val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                panel.background.draw(Canvas(bitmap))
                // Light DEFAULT acrylic falls back to #F9F9F9; sample the centre, away from the stroke.
                val pixel = bitmap.getPixel(panel.width / 2, panel.height / 2)
                bitmap.recycle()
                for (shift in intArrayOf(16, 8, 0)) assertTrue(abs((pixel shr shift and 0xFF) - 0xF9) <= 2)
                assertEquals(0xFF, pixel ushr 24)
            }
        }
    }

    /** Regression: Paint.setAlpha once turned the 6% disabled stroke (#0F000000) into an opaque black border. */
    @Test fun solidStrokeKeepsItsTokenAlpha() {
        ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
            laidOut(scenario)
            scenario.onActivity { activity ->
                for (index in intArrayOf(0, 2)) {   // enabled gradient stroke, disabled solid stroke
                    val view = activity.buttonViews[index]
                    assertEquals(index != 2, view.isEnabled)
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.background.draw(Canvas(bitmap))
                    val pixel = bitmap.getPixel(view.width / 2, 1)
                    bitmap.recycle()
                    // #F9F9F9 under a 6% black stroke is about #EAEAEA; the old bug gave #000000.
                    for (shift in intArrayOf(16, 8, 0)) assertTrue("button $index stroke ${Integer.toHexString(pixel)}",
                        (pixel shr shift and 0xFF) > 0xC0)
                }
            }
        }
    }

    @Test fun backdropSamplingFadesAcrylicIn() {
        ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
            laidOut(scenario)
            // Device policy (low-RAM images, battery saver) is covered elsewhere; this test is about sampling.
            scenario.onActivity { it.flyout.update(LumenFluentControlOptions(acrylic = LumenAcrylicOptions(
                fallbackOnLowRamDevice = false, fallbackOnPowerSave = false))) }
            // The emulator draws the source through the software or GPU path; either is a sampled backdrop.
            waitFor(scenario) { it.flyout.diagnostics().acrylicVisible }
            scenario.onActivity { activity ->
                val diagnostics = activity.flyout.diagnostics()
                assertEquals(LumenAcrylicFallbackReason.NONE, diagnostics.fallbackReason)
                assertFalse(diagnostics.closed)
            }
        }
    }
}
