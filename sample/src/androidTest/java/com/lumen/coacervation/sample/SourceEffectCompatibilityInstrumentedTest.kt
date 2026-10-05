package com.lumen.coacervation.sample

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.interaction.ElasticTravelPolicy
import com.lumen.coacervation.engine.motion.MorphCornerMode
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实窗口验证来源行程、单次 CANCEL 与策略在当前手势中的固定性。 */
@RunWith(AndroidJUnit4::class)
class SourceEffectCompatibilityInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun parentBoundsPreservesOverlapAndChangingPolicyDoesNotRetargetActiveDrag() {
        ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            val avoided = drag(scenario, ElasticTravelPolicy.AVOID_NEIGHBORS)
            val parent = drag(scenario, ElasticTravelPolicy.PARENT_BOUNDS)
            assertTrue("Parent travel $parent must exceed neighbor-limited $avoided", parent > avoided + 2f)
        }
    }

    private fun drag(scenario: ActivityScenario<SampleActivity>, policy: ElasticTravelPolicy): Float {
        val view = AtomicReference<TextView>()
        val dialog = AtomicReference<Dialog>()
        var release: (() -> Unit)? = null
        var clicks = 0
        var cancels = 0
        var density = 1f
        scenario.onActivity { activity ->
            activity.elastic.travelPolicy = policy
            density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                clipChildren = false
                clipToPadding = false
            }
            repeat(2) { index ->
                val row = TextView(activity).apply {
                    text = "测试条目 $index"
                    background = GradientDrawable().apply {
                        setColor(Color.LTGRAY)
                        cornerRadius = dp(15).toFloat()
                    }
                    setOnClickListener { clicks++ }
                }
                content.addView(row, LinearLayout.LayoutParams(dp(220), dp(60)).apply {
                    if (index > 0) topMargin = dp(4)
                })
                if (index == 0) view.set(row)
            }
            dialog.set(Dialog(activity).apply {
                setContentView(content)
                show()
            })
            val window = requireNotNull(dialog.get().window)
            val original = requireNotNull(window.callback)
            window.callback = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancels++
                    return original.dispatchTouchEvent(event)
                }
            }
            release = activity.elastic.installDialog(dialog.get())
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.get().getLocationOnScreen(location) }
        val x = location[0] + view.get().width / 2f
        val y = location[1] + view.get().height / 2f
        val down = SystemClock.uptimeMillis()
        var releasedPointer = false
        try {
            pointer(down, MotionEvent.ACTION_DOWN, x, y)
            SystemClock.sleep(320)
            pointer(down, MotionEvent.ACTION_MOVE, x, y + 60f * density)
            SystemClock.sleep(120)
            var first = 0f
            scenario.onActivity { activity ->
                first = view.get().translationY / density
                activity.elastic.travelPolicy = if (policy == ElasticTravelPolicy.PARENT_BOUNDS)
                    ElasticTravelPolicy.AVOID_NEIGHBORS else ElasticTravelPolicy.PARENT_BOUNDS
            }
            pointer(down, MotionEvent.ACTION_MOVE, x, y + 60f * density)
            var second = 0f
            instrumentation.runOnMainSync { second = view.get().translationY / density }
            assertEquals("Changing policy must not move the current drag boundary", first, second, .1f)
            pointer(down, MotionEvent.ACTION_UP, x, y + 60f * density)
            releasedPointer = true
            SystemClock.sleep(1000)
            instrumentation.runOnMainSync {
                assertEquals(0f, view.get().translationY, .15f * density)
                assertEquals("Captured drag must not invoke a click", 0, clicks)
                assertEquals("Captured drag must cancel the original dispatcher exactly once", 1, cancels)
            }
            return first
        } finally {
            if (!releasedPointer) pointer(down, MotionEvent.ACTION_CANCEL, x, y)
            scenario.onActivity {
                release?.invoke()
                dialog.get().dismiss()
            }
        }
    }

    @Test fun capsuleRuleCanBeChangedForTheNextModalWithoutInterruptingTheVisibleOne() {
        ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activity.modals.anchorCornerMode = MorphCornerMode.CAPSULE
                activity.showMorphPanel(anchor = activity.scrolls[0])
            }
            SystemClock.sleep(150)
            scenario.onActivity { it.modals.anchorCornerMode = MorphCornerMode.DECLARED }
            SystemClock.sleep(800)
            scenario.onActivity { activity ->
                val modal = requireNotNull(activity.modals.activeDialog)
                assertTrue(modal.isShowing)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            SystemClock.sleep(800)
            scenario.onActivity { activity ->
                assertTrue(activity.modals.activeDialog?.isShowing != true)
            }
        }
    }

    private fun pointer(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }
}
