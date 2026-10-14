package com.lumen.coacervation.sample

import android.animation.ValueAnimator
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.host.*
import com.lumen.coacervation.engine.interaction.LumenSurfaceInteraction
import com.lumen.coacervation.engine.motion.LumenFixedFrameClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Changes the public system setting and inspects the real binding's frame, without losing window focus. */
@RequiresApi(33)
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class SurfaceInteractionAnimationScaleRegressionTest {
    private val press = LumenLocalPressOptions(enabled = true, rippleEnabled = true)
    private val light = LumenSurfaceLightOptions(enabled = true, angleDegrees = 70f,
        altitude = .4f, gestureInfluence = 1f, smoothingTimeMs = 120)

    private fun read(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        fail(message)
    }

    private fun awaitScale(expected: Float) = await("Animator scale did not propagate to the application") {
        var actual = -1f
        InstrumentationRegistry.getInstrumentation().runOnMainSync { actual = ValueAnimator.getDurationScale() }
        actual == expected
    }

    private fun setScale(value: Float) {
        shell("settings put global animator_duration_scale $value")
        awaitScale(value)
    }

    private class Fixture(val view: View, val frame: Any, val input: LumenSurfaceInteraction,
        val clock: LumenFixedFrameClock) {
        fun event(action: Int) {
            val time = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(time, time, action, view.width * .9f, view.height * .15f, 0)
            try { input.observeTouch(event) } finally { event.recycle() }
        }
        fun advance() { clock.advanceMillis(100); input.advanceFrame() }
    }

    private fun withFixture(test: (ActivityScenario<SurfaceSandboxActivity>, Fixture) -> Unit) {
        val saved = shell("settings get global animator_duration_scale")
        require(saved == "null" || saved.toFloatOrNull()?.let { it.isFinite() && it >= 0f } == true)
        try {
            setScale(1f)
            ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
                lateinit var view: View
                lateinit var binding: LumenSurfaceBinding
                scenario.onActivity { activity ->
                    view = View(activity)
                    activity.preview.addView(view, FrameLayout.LayoutParams(120, 120))
                    binding = activity.session.bind(view, LumenSurfaceOptions(
                        sampling = LumenSurfaceSampling(backend = LumenSurfaceBackend.STATIC)))
                    binding.updateEnhancements(LumenSurfaceEnhancements(press = press, light = light))
                }
                await("The fixture must remain attached, visible and focused") {
                    var ready = false
                    scenario.onActivity { ready = view.width > 0 && view.isAttachedToWindow && view.isShown && view.hasWindowFocus() }
                    ready
                }
                lateinit var fixture: Fixture
                scenario.onActivity { activity ->
                    val entries = read(activity.session, "entries") as List<*>
                    val entry = entries.filterNotNull().single { read(it, "id") == binding.id }
                    val clock = LumenFixedFrameClock()
                    fixture = Fixture(view, requireNotNull(read(entry, "frame")),
                        LumenSurfaceInteraction(view, binding, press, light, clock), clock)
                }
                try { test(scenario, fixture) } finally {
                    scenario.onActivity { fixture.input.close(); binding.close(); it.preview.removeView(view) }
                }
            }
        } finally {
            if (saved == "null") shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale '" + saved.replace("'", "'\\''") + "'")
            awaitScale(if (saved == "null") 1f else saved.toFloat())
        }
    }

    private fun assertStatic(frame: Any) {
        assertEquals(0f, read(frame, "pressure") as Float, 0f)
        assertTrue((read(frame, "rippleBorn") as LongArray).all { it == Long.MIN_VALUE })
        assertEquals(true, read(frame, "lightOverride"))
        val horizontal = sqrt(1f - light.altitude * light.altitude)
        val angle = light.angleDegrees * Math.PI / 180.0
        assertEquals(cos(angle).toFloat() * horizontal, read(frame, "lightX") as Float, .00001f)
        assertEquals(sin(angle).toFloat() * horizontal, read(frame, "lightY") as Float, .00001f)
        assertEquals(light.altitude, read(frame, "lightZ") as Float, .00001f)
    }

    @Test fun disablingInTheSameFocusedWindowClearsUpAndCancelAndRequiresANewDown() {
        withFixture { scenario, f ->
            for (action in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                setScale(1f)
                scenario.onActivity {
                    f.event(MotionEvent.ACTION_DOWN); f.advance()
                    assertTrue((read(f.frame, "pressure") as Float) > .1f)
                }
                setScale(0f)
                scenario.onActivity {
                    assertTrue(f.view.hasWindowFocus())
                    f.event(action); assertStatic(f.frame)
                    assertEquals(false, read(f.input, "scheduled"))
                }
                setScale(1f)
                scenario.onActivity {
                    f.event(MotionEvent.ACTION_UP)
                    repeat(30) { f.advance() }
                    assertStatic(f.frame)
                    f.event(MotionEvent.ACTION_DOWN); f.advance()
                    assertTrue((read(f.frame, "pressure") as Float) > .1f)
                    f.input.cancel()
                }
            }
        }
    }

    @Test fun frameWithoutATerminalEventClearsTheGestureAndRetainsStaticLightAndManualTime() {
        withFixture { scenario, f ->
            scenario.onActivity { f.event(MotionEvent.ACTION_DOWN); f.advance() }
            setScale(0f)
            scenario.onActivity {
                assertTrue(f.view.hasWindowFocus())
                f.advance(); assertStatic(f.frame)
                assertEquals(f.clock.nowNanos(), read(f.frame, "frameNanos"))
                assertEquals(true, read(f.frame, "manualClock"))
                assertEquals(-1, read(f.input, "pointer"))
                repeat(5) { f.event(MotionEvent.ACTION_UP); f.event(MotionEvent.ACTION_CANCEL) }
                assertStatic(f.frame)
            }
        }
    }

    @Test fun anExistingRippleIsClearedAndReduceMotionUpdatesKeepConfiguredStaticLight() {
        withFixture { scenario, f ->
            scenario.onActivity {
                f.event(MotionEvent.ACTION_DOWN); f.advance(); f.event(MotionEvent.ACTION_UP)
                assertTrue((read(f.frame, "rippleBorn") as LongArray).any { it != Long.MIN_VALUE })
            }
            setScale(0f)
            scenario.onActivity { f.advance(); assertStatic(f.frame) }
            setScale(1f)
            scenario.onActivity {
                f.event(MotionEvent.ACTION_DOWN); f.advance()
                f.input.update(press, light, reduceMotion = true)
                assertStatic(f.frame)
                f.event(MotionEvent.ACTION_UP); f.advance(); assertStatic(f.frame)
                f.input.update(press, light, reduceMotion = false)
                f.event(MotionEvent.ACTION_DOWN); f.advance()
                assertTrue((read(f.frame, "pressure") as Float) > .1f)
            }
        }
    }
}
