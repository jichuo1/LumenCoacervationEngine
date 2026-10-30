package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.LumenMaterialLegibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Material opacity on real window pixels (MATERIAL_OPACITY.md §9.3), over the sandbox's eight materials on one scope:
 * returning to 1 restores the default pixels exactly, a settled opacity leaves the window idle (settle timers and
 * blur-level warm-up end), and clear decorative surfaces stop recording their source.
 */
@RunWith(AndroidJUnit4::class)
class MaterialOpacityRegressionTest {
    @get:Rule val hardware = HardwareOutputRule()

    private fun stage(activity: MaterialOpacitySandboxActivity): View =
        (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0)

    private fun capture(activity: MaterialOpacitySandboxActivity): Bitmap {
        var copy: Bitmap? = null
        WindowRegressionCapture.copy(activity, stage(activity)) { copy = it.copy(Bitmap.Config.ARGB_8888, false) }
        return requireNotNull(copy)
    }

    /** Waits past the settle delay, re-blurs and warm-up, then until two captures in a row are identical. */
    private fun settled(activity: MaterialOpacitySandboxActivity): Bitmap {
        SystemClock.sleep(1200)
        var previous = capture(activity)
        repeat(12) {
            SystemClock.sleep(250)
            val next = capture(activity)
            if (next.sameAs(previous)) { previous.recycle(); return next }
            previous.recycle(); previous = next
        }
        throw AssertionError("The stage never settled")
    }

    private fun launch(block: (MaterialOpacitySandboxActivity) -> Unit) =
        ActivityScenario.launch(MaterialOpacitySandboxActivity::class.java).use { scenario ->
            var activity: MaterialOpacitySandboxActivity? = null
            scenario.onActivity { activity = it }
            block(requireNotNull(activity))
        }

    @Test fun returningToFullOpacityRestoresTheDefaultPixels() = launch { activity ->
        val untouched = settled(activity)
        WindowRegressionCapture.main { activity.scope.set(0.35f) }
        val thin = settled(activity)
        assertFalse("0.35 must change the materials", thin.sameAs(untouched))
        WindowRegressionCapture.main { activity.scope.set(0f) }
        settled(activity).recycle()
        WindowRegressionCapture.main { activity.scope.set(1f) }
        val restored = settled(activity)
        assertTrue("Back at 1 every material must draw its default pixels again", restored.sameAs(untouched))
        listOf(untouched, thin, restored).forEach(Bitmap::recycle)
    }

    @Test fun aSettledOpacityLeavesTheWindowIdle() = launch { activity ->
        settled(activity).recycle()
        fun drawsDuring(millis: Long): Int {
            var draws = 0
            val listener = ViewTreeObserver.OnDrawListener { draws++ }
            WindowRegressionCapture.main { activity.window.decorView.viewTreeObserver.addOnDrawListener(listener) }
            SystemClock.sleep(millis)
            WindowRegressionCapture.main { activity.window.decorView.viewTreeObserver.removeOnDrawListener(listener) }
            return draws
        }
        assertEquals("The untouched sandbox must be idle", 0, drawsDuring(1500))
        WindowRegressionCapture.main { activity.scope.set(0.35f) }
        settled(activity).recycle()
        assertEquals("Settle timers and blur warm-up must end: no frames once settled", 0, drawsDuring(1500))
        WindowRegressionCapture.main { activity.scope.set(1f) }
        settled(activity).recycle()
        assertEquals("Back at 1 the window must be idle again", 0, drawsDuring(1500))
    }

    @Test fun clearDecorativeSurfacesStopRecordingTheirSource() = launch { activity ->
        settled(activity).recycle()
        fun recordingsWhileScrolling(): Long {
            var before = 0L
            WindowRegressionCapture.main { before = activity.session.diagnostics().contentRecordings }
            repeat(6) {
                WindowRegressionCapture.main { activity.scene.scrollBy(0, (24 * activity.resources.displayMetrics.density).toInt()) }
                WindowRegressionCapture.frame(activity.scene)
            }
            settled(activity).recycle()
            var after = 0L
            WindowRegressionCapture.main { after = activity.session.diagnostics().contentRecordings }
            return after - before
        }
        assertTrue("At full opacity scrolling the source must resample it", recordingsWhileScrolling() > 0)
        WindowRegressionCapture.main {
            // Decorative: no legibility floor, so every sampled material can reach zero backdrop weight.
            for (binding in listOf(activity.frosted, activity.liquid, activity.direct, activity.software))
                binding.setMaterialOpacity(1f, LumenMaterialLegibility.DECORATIVE)
            activity.fluent.setMaterialOpacity(1f, LumenMaterialLegibility.DECORATIVE)
            activity.prism.setMaterialOpacity(1f, LumenMaterialLegibility.DECORATIVE)
            // The scope drives them through the one-argument setter; the declared legibility must survive it.
            activity.scope.set(0f)
        }
        WindowRegressionCapture.main {
            for (target in listOf(activity.frosted, activity.liquid, activity.direct, activity.software, activity.fluent, activity.prism))
                assertEquals("A decorative surface reaches 0 through the scope", 0f, target.effectiveMaterialOpacity(), 0f)
        }
        settled(activity).recycle()
        assertEquals("Clear glass needs no backdrop: the source must not be recorded", 0L, recordingsWhileScrolling())
        WindowRegressionCapture.main { activity.scope.set(1f) }
        settled(activity).recycle()
        assertTrue("Raising the opacity again resumes sampling", recordingsWhileScrolling() > 0)
    }
}
