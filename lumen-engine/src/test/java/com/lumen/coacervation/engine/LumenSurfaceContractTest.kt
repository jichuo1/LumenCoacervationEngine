package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.*
import org.junit.Test

class LumenSurfaceContractTest {
    @Test fun allFadeBackendsUseTheConfiguredCurve() {
        assertTrue(session().contains("LumenSurfaceFadeDirection.BOTTOM_TO_TOP, c.sampling.fadeCurve"))
        assertTrue(SourceContract.read("host/SurfaceCaptureApi31.kt").contains("LumenSurfaceFadeDirection.BOTTOM_TO_TOP, sampling.fadeCurve"))
        assertTrue(SourceContract.read("material/LiveBackdropSampler.kt").contains("profile.fadeEnd, reverse, profile.fadeCurve"))
    }
    private fun session()=SourceContract.read("host/LumenSurfaceSession.kt")
    @Test fun localSessionDoesNotTakeOverTheApplication() {
        val s=session()
        listOf("getSharedPreferences(","recreate(","setContentView(","setPreferredRefreshRate(","setOnTouchListener(","addView(").forEach {
            assertFalse("Local session must not $it",s.contains(it))
        }
    }
    @Test fun backgroundsAreRestoredOnlyWhileStillOwned() {
        val s=session()
        assertTrue(s.contains("view.background === entry.drawable"))
        assertTrue(s.contains("replaceBackground(view, entry.original)"))
        assertTrue(s.contains("removeOnAttachStateChangeListener(entry)"))
        assertTrue(s.contains("removeCallbacks(entry.dispatch)"))
    }
    @Test fun windowsAndFeedbackAreCheckedBeforeSampling() {
        val s=session()
        assertTrue(s.contains("host.windowToken != view.windowToken"))
        assertTrue(s.contains("parent === view"))
        assertTrue(s.contains("LumenSurfaceFailure.SELF_FEEDBACK"))
        assertTrue(s.contains("if (failure(host, entry) != LumenSurfaceFailure.NONE) { sampler.unregister(host); entry.releaseGpu(); continue }"))
    }
    @Test fun lateFramesAndMemoryHaveAnExplicitReleasePath() {
        val s=session()
        assertTrue(s.contains("LumenMemoryPressureHub.removeListener(memoryListener)"))
        assertTrue(s.contains("unregisterComponentCallbacks(trim)"))
        assertTrue(s.contains("removeOnPreDrawListener(this)"))
        assertTrue(s.contains("removeOnWindowFocusChangeListener(this)"))
        assertTrue(s.contains("sampler.suspend()"))
        assertTrue(s.contains("main.post(dispatch)"))
    }
    @Test fun softwareUpdatesDropStaleProfilesAndPreserveDisplayListBitmaps() {
        val s=SourceContract.read("material/LiveBackdropSampler.kt")
        assertTrue(s.contains("entry.profile != profile"))
        assertTrue(s.contains("entry.generation != job.generation"))
        assertTrue(s.contains("sharedBudget?.reserve(bytes)"))
        assertFalse(s.contains("texture?.recycle()"))
    }
    @Test fun softwareCaptureFiltersEachSurfaceBeforePreparingOrRecordingIt() {
        val s=SourceContract.read("material/LiveBackdropSampler.kt")
        val collect=s.after("private fun collectJobs(").before("private fun schedulePendingSample(")
        val throttle=collect.indexOf("entry.cadence.remainingMs(")
        assertTrue(throttle >= 0 && collect.indexOf("prepare(view, entry,") > throttle)
        assertTrue(collect.contains("plans.forEach { entry -> entry.cadence.sampled(now) }"))
        val trailing=s.after("private val trailingSample = Runnable").before("private var inFlight")
        assertFalse(trailing.contains("dirty = true"))
        val done=s.after("private fun onBatchDone(").before("private fun isInside(")
        assertTrue(done.contains("if (isActive) schedulePendingSample(System.nanoTime())"))
    }
}
