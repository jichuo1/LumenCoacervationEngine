package com.lumen.coacervation.sample

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 表面沙盒有两种启动方式：
 * - 直接启动（仪器化测试、`am start`）：静止页面，参数与 1.5.6 相同，测试依赖「静止页面停止采样」「内容可被替换」；
 * - 演示入口（"局部视效调节实验室"）带 [SurfaceSandboxActivity.EXTRA_NATIVE_BACKDROP_DEMO]：请求原生 backdrop、关闭折射并持续动画。
 * 1.6.0 起把演示行为无条件放进 onCreate，导致整套设备测试在 API 27 上崩溃、在 API 31–34 上长时间停滞。
 */
@RunWith(AndroidJUnit4::class)
class SurfaceSandboxLaunchModeTest {
    private fun demoIntent() = Intent(ApplicationProvider.getApplicationContext(), SurfaceSandboxActivity::class.java)
        .putExtra(SurfaceSandboxActivity.EXTRA_NATIVE_BACKDROP_DEMO, true)

    private fun leftMargin(activity: SurfaceSandboxActivity) = (activity.glass.layoutParams as FrameLayout.LayoutParams).leftMargin
    private fun dp(activity: SurfaceSandboxActivity, value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()

    @Test fun directLaunchKeepsTheStaticDefaultPageAndDoesNotRequestTheNativeBackdrop() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val state = checkNotNull(activity.binding.renderTargetBackdropState())
                assertFalse("the native request is opt-in", state.requested)
                assertEquals("the glass keeps its 1.5.6 margins", dp(activity, 16), leftMargin(activity))
                assertFalse("a directly launched sandbox must not animate its content", activity.contentAnimating)
            }
            // 演示动画的周期是 1600 ms：等过一个完整周期，页面仍然静止（没有任何东西悄悄把动画开起来）。
            SystemClock.sleep(1800)
            scenario.onActivity { assertFalse("still static after a full animation period", it.contentAnimating) }
        }
    }

    @Test fun demoLaunchRequestsTheNativeBackdropAndASwappedContentViewCannotCrashTheAnimation() {
        ActivityScenario.launch<SurfaceSandboxActivity>(demoIntent()).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the demo entry asks for the native backdrop", checkNotNull(activity.binding.renderTargetBackdropState()).requested)
                assertTrue("the demo entry animates its content", activity.contentAnimating)
                assertEquals("the demo keeps its wider margins", dp(activity, 32), leftMargin(activity))
                // 测试（和宿主）可以把内容换成自己的视图；动画的下一帧不能再把它当成沙盒自己的图案视图。
                activity.preview.removeView(activity.content)
                activity.content = View(activity)
                activity.preview.addView(activity.content, 0, FrameLayout.LayoutParams(-1, -1))
            }
            SystemClock.sleep(1800) // 超过一个完整的动画周期；若监听器强转失败，进程会在这里崩溃。
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }
}
