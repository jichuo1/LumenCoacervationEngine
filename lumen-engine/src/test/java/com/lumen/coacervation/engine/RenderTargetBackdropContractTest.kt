package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.*
import org.junit.Test

class RenderTargetBackdropContractTest {
    @Test fun minorSdkAccessIsIsolatedBehindAMajorVersionGuard() {
        val renderer = SourceContract.read("host/RenderTargetBackdropApi37.kt")
        val support = renderer.after("fun isSupported(): Boolean")
        assertTrue(support.indexOf("Build.VERSION.SDK_INT >= 36") < support.indexOf("Build.VERSION.SDK_INT_FULL"))
        assertTrue(support.contains("RenderTargetBackdropPolicy.versionSupported"))
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        assertFalse(session.contains("SDK_INT_FULL"))
        assertTrue(session.contains("Build.VERSION.SDK_INT >= 37 && RenderTargetBackdropApi37.isSupported()"))
        assertFalse(session.contains("import android.graphics.RenderNode"))
        assertFalse(session.contains("import android.graphics.RenderEffect"))
    }

    @Test fun captureRetirementRequiresAnActualSuccessfulNativeDraw() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val retire = session.after("// Wait for a successful draw").before("if (!wanted)")
        assertTrue(retire.contains("entry.nativeActive && entry.nativeEligible(host.isHardwareAccelerated)"))
        assertTrue(retire.contains("if (entry.nativeActive) entry.leaveNative()"))
        assertTrue(retire.indexOf("sampler.unregister(entry.target)") < retire.indexOf("continue"))
        val draw = session.after("if (native.draw(canvas, bounds,").before("if (backend != LumenSurfaceBackend.GPU && canvas.isHardwareAccelerated")
        assertTrue(draw.contains("entry.nativeActive = true"))
        assertTrue(draw.contains("group.sampler.unregister(entry.target)"))
        assertTrue(draw.contains("entry.nativeFailed = true; entry.leaveNative()"))
        // The sticky failure is initialized once and is not reset by rebuild, toggle or resume.
        assertEquals(1, Regex("nativeFailed = false").findAll(session).count())
    }

    @Test fun allGraphicsReleasePathsAlsoDropTheNativeNode() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val release = session.after("        fun releaseGpu() {\n            releaseNative()").before("fun enhanced()")
        assertTrue(release.contains("gpu = null; gpuPixels = 0L"))
        val nativeRelease = session.after("fun releaseNative()").before("fun leaveNative()")
        assertTrue(nativeRelease.contains("nativeBackdrop?.close()"))
        assertTrue(nativeRelease.contains("catch (_: Throwable) { nativeFailed = true }"))
        assertTrue(nativeRelease.contains("nativeBackdrop = null"))
        assertTrue(nativeRelease.contains("nativeActive = false"))
        val renderer = SourceContract.read("host/RenderTargetBackdropApi37.kt")
        val close = renderer.after("override fun close()").before("private fun checkMain")
        assertTrue(close.contains("setBackdropRenderEffect(null)"))
        assertTrue(close.contains("discardDisplayList()"))
        assertTrue(close.contains("if (closed) return"))
    }

    @Test fun semanticsAndExistingDataClassAbiRemainExplicit() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val eligibility = session.after("fun nativeEligible(").before("fun releaseNative()")
        assertTrue(eligibility.contains("installsBackground && group?.source is ViewContentSource"))
        assertTrue(eligibility.contains("options.gpuScale == 1f"))
        assertTrue(eligibility.contains("materialBackdrop == 1f && materialRadiusBackdrop == 1f && materialBlur == 1f"))
        assertTrue(eligibility.contains("drawable.alpha == 255"))
        val options = SourceContract.read("host/LumenSurfaceOptions.kt")
        assertFalse(options.contains("nativeBackdrop"))
        assertFalse(options.contains("renderTargetBackdrop"))
        val renderer = SourceContract.read("host/RenderTargetBackdropApi37.kt")
        assertFalse(renderer.contains("drawContent"))
        assertFalse(renderer.contains("saveLayer"))
        assertTrue(renderer.contains("canvas.isHardwareAccelerated"))
        assertTrue(renderer.contains("current.setPosition(bounds.left - padding, bounds.top - padding, bounds.right + padding, bounds.bottom + padding)"))
        assertTrue(renderer.contains("outline.setRoundRect(padding, padding, padding + nextWidth, padding + nextHeight, radius)"))
        assertTrue(eligibility.contains("nativeWithinWindow()"))
    }
}
