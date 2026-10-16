package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.*
import org.junit.Test

class AcrylicBackdropContractTest {
    @Test fun preservationIsInternalToTheCompositeBindingAndDoesNotChangePublicSamplingAbi() {
        val options = SourceContract.read("host/LumenSurfaceOptions.kt")
        assertFalse("Keep the released data-class constructors, copy and default descriptors", options.contains("preserveBackdropColors"))
        val control = SourceContract.read("host/LumenFluentControl.kt")
        assertTrue(control.contains("bindViewDrawOnly(view, surfaceOptions(), content, preserveBackdropColors = true)"))
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val binding = session.after("internal fun bindViewDrawOnly(").before("internal fun backendOf")
        assertTrue(binding.contains("bindViewDrawOnly(view, surface, source, false)"))
        assertTrue(binding.contains("preserveBackdropColors: Boolean)"))
        assertTrue(binding.contains("this.preserveBackdropColors = preserveBackdropColors"))
        assertTrue(session.contains("preserveBackdropColors = entry.preserveBackdropColors"))
    }

    @Test fun ordinaryGpuFrostedStillIlluminatesAndCompositeAcrylicUsesOnlyItsOwnRecipe() {
        val glass = SourceContract.read("host/SurfaceCaptureApi31.kt").after("class Glass(")
        assertTrue(glass.contains("preserveBackdropColors: Boolean = false"))
        val lens = glass.after("private val lens: Any?").before("val renderer =")
        assertTrue(lens.contains("!preserveBackdropColors && Build.VERSION.SDK_INT"))
        val renderer = glass.after("val renderer =").before("override fun close()")
        assertTrue(renderer.contains("options.material==LumenSurfaceMaterial.FROSTED&&lens==null&&!preserveBackdropColors"))
        assertTrue(renderer.contains("LensRefractionPolicy.LUMINANCE_GAIN"))
        assertTrue(renderer.contains("LensRefractionPolicy.LUMINANCE_BIAS"))
    }

    @Test fun softwareJobsSnapshotTheFlagAndInvalidateFramesWhenThePolicyChanges() {
        val sampler = SourceContract.read("material/LiveBackdropSampler.kt")
        val registration = sampler.after("private fun registerSample(").before("fun unregister(")
        assertTrue(registration.contains("entry.preserveBackdropColors != preserveBackdropColors"))
        assertTrue(registration.contains("entry.release()"))
        val job = sampler.after("private fun job(").before("private fun onBatchDone(")
        assertTrue(job.contains("preserveBackdropColors = entry.preserveBackdropColors"))
        val process = sampler.after("fun process(job: LensJob)").before("fun close()")
        assertTrue(process.contains("if (!job.preserveBackdropColors) LensRefractionPolicy.illuminate(job.out)"))
        assertTrue(process.indexOf("LensRefractionPolicy.illuminate") < process.indexOf("val profile = job.profile"))
    }
}
