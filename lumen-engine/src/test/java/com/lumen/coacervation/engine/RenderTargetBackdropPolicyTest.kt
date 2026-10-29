package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.host.RenderTargetBackdropPolicy
import org.junit.Assert.*
import org.junit.Test

class RenderTargetBackdropPolicyTest {
    @Test fun paddedSnapshotMustFitCompletelyWithinTheWindow() {
        assertTrue(RenderTargetBackdropPolicy.windowContains(20, 20, 60, 60, 100, 100, 20))
        for ((x, y) in listOf(19 to 20, 20 to 19, 21 to 20, 20 to 21))
            assertFalse(RenderTargetBackdropPolicy.windowContains(x, y, 60, 60, 100, 100, 20))
        assertFalse(RenderTargetBackdropPolicy.windowContains(20, 20, 0, 60, 100, 100, 20))
        assertFalse(RenderTargetBackdropPolicy.windowContains(Int.MAX_VALUE, 20, Int.MAX_VALUE, 60, 100, 100, 20))
    }

    @Test fun fullSdkVersionRejectsEarlierMajorAndMinorReleases() {
        for ((sdk, full) in listOf(27 to 0, 35 to 0, 36 to 3_600_000,
            37 to 3_700_000, 37 to 3_700_001)) {
            assertFalse("SDK $sdk / full $full must use the existing backend",
                RenderTargetBackdropPolicy.versionSupported(sdk, full))
        }
        assertEquals(3_700_002, RenderTargetBackdropPolicy.MIN_SDK_FULL)
        assertTrue(RenderTargetBackdropPolicy.versionSupported(37, 3_700_002))
        assertTrue(RenderTargetBackdropPolicy.versionSupported(38, 3_800_000))
    }

    @Test fun majorVersionIsCheckedEvenIfFullVersionIsMalformedOrUnexpected() {
        assertFalse(RenderTargetBackdropPolicy.versionSupported(27, Int.MAX_VALUE))
        assertFalse(RenderTargetBackdropPolicy.versionSupported(36, 3_700_002))
        assertFalse(RenderTargetBackdropPolicy.versionSupported(37, 0))
        assertFalse(RenderTargetBackdropPolicy.versionSupported(38, -1))
    }

    @Test fun nativeBackdropRequiresExplicitOptInAndAnActualHardwareCanvas() {
        val ordinary = Candidate()
        assertFalse(ordinary.eligible())
        val requested = ordinary.copy(requested = true)
        assertTrue(requested.eligible())
        assertFalse(requested.copy(hardwareCanvas = false).eligible())
        assertFalse(requested.copy(sdkSupported = false).eligible())
        assertFalse(requested.copy(gpuAllowed = false).eligible())
    }

    @Test fun everyUnsupportedMaterialOrDrawingContractKeepsTheExistingPath() {
        val supported = Candidate(requested = true)
        val unsupported = listOf(
            "draw-only target" to supported.copy(viewBackground = false),
            "other material" to supported.copy(frosted = false),
            "refraction" to supported.copy(refraction = true),
            "fade" to supported.copy(fade = true),
            "enhanced effects" to supported.copy(enhanced = true),
            "composite acrylic" to supported.copy(preserveColors = true),
            "prism" to supported.copy(prism = true),
            "partial opacity" to supported.copy(fullOpacity = false),
            "transformed target" to supported.copy(untransformed = false)
        )
        for ((reason, candidate) in unsupported) assertFalse(reason, candidate.eligible())
    }

    @Test fun failedBackendCannotResumeWhenOtherEligibilityInputsRecover() {
        val failed = Candidate(requested = true, failed = true)
        assertFalse(failed.eligible())
        assertFalse(failed.copy(hardwareCanvas = false).eligible())
        assertFalse(failed.copy(hardwareCanvas = true).eligible())
        assertFalse(failed.copy(requested = false).eligible())
        assertFalse(failed.copy(requested = true).eligible())
        assertFalse(failed.copy(fullOpacity = false).eligible())
        assertFalse(failed.copy(fullOpacity = true).eligible())
    }

    private data class Candidate(
        val requested: Boolean = false,
        val sdkSupported: Boolean = true,
        val hardwareCanvas: Boolean = true,
        val viewBackground: Boolean = true,
        val frosted: Boolean = true,
        val refraction: Boolean = false,
        val fade: Boolean = false,
        val enhanced: Boolean = false,
        val preserveColors: Boolean = false,
        val prism: Boolean = false,
        val fullOpacity: Boolean = true,
        val untransformed: Boolean = true,
        val gpuAllowed: Boolean = true,
        val failed: Boolean = false
    ) {
        fun eligible(): Boolean = RenderTargetBackdropPolicy.eligible(
            requested = requested,
            sdkSupported = sdkSupported,
            hardwareCanvas = hardwareCanvas,
            viewBackground = viewBackground,
            frosted = frosted,
            refraction = refraction,
            fade = fade,
            enhanced = enhanced,
            preserveColors = preserveColors,
            prism = prism,
            fullOpacity = fullOpacity,
            untransformed = untransformed,
            gpuAllowed = gpuAllowed,
            failed = failed
        )
    }
}
