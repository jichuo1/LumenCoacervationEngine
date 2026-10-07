package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostedCustomBackgroundContractTest {
    @Test fun ordinarySessionPassesApplicationContextAndWorkerUsesTheExistingAssetDecoder() {
        val session = SourceContract.read("runtime/ActivitySkinSession.kt")
        assertTrue(session.contains("backgroundContext = activity.applicationContext"))
        val renderer = SourceContract.read("material/FrostedMaterialRenderer.kt")
        val request = renderer.after("private fun requestBackdrop").before("private fun acceptBackdrop")
        val worker = request.after("work = worker.submit")
        assertTrue(worker.contains("LiquidBackgroundStore.decodeBackdrop(context, config"))
        assertFalse(worker.contains("SkinRepository"))
        val factory = renderer.after("private object ModernBackdropFactory").before("private class ModernSurfaceDrawable")
        assertTrue(factory.contains("ModernMaterialPolicy.sampleSize(width, height)"))
        assertTrue(factory.indexOf("customBackground?.invoke(w, h)") >= 0)
        assertTrue(factory.indexOf("customBackground?.invoke(w, h)") < factory.indexOf("AmbientBackdropScene.paint"))
        assertFalse(renderer.contains("PixelCopy"))
    }

    @Test fun foregroundMemoryReleaseKeepsTheDisplayUnderlayUntilStopOrClose() {
        val renderer = SourceContract.read("material/FrostedMaterialRenderer.kt")
        val root = renderer.after("fun bindRoot(view: View)").before("override fun surface")
        assertTrue(root.contains("val current = rootBackdrop"))
        val release = renderer.after("fun releaseMemory()").before("fun stop()")
        assertTrue(release.contains("if (!lifecycle.canWork) {\n            rootBackdrop = null"))
        assertTrue(release.contains("frame = null; sampleShader = null; samplePaint.shader = null"))
    }

}
