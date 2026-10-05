package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidMotionMappingContractTest {
    @Test fun mappingAndMaskShareTheRecordedFullTransform() {
        val renderer = SourceContract.read("liquid/LiquidActivityRenderer.kt")
        assertTrue(renderer.contains("surfaceCoordinates.sourceToTarget(screenTransform, root, surfaceToBackdrop)"))
        assertTrue(renderer.contains("surfaceCoordinates.localToScreen(view, footprint.screenTransform)"))
        assertTrue(renderer.contains("SamplingMatrixMath.equal(entry.value.screenTransform, refreshSurfaceTransform)"))
        val feedback = SourceContract.read("liquid/LiquidFeedbackSuppressor.kt")
        val mask = feedback.after("fun buildSuppressionMask(").before("fun sanitizeRealtimeCapture(")
        assertTrue(mask.contains("surfaceToCapture.setValues(footprint.screenTransform)"))
        assertFalse(mask.contains("Matrix()"))
        assertFalse(mask.contains("Path()"))
    }

    @Test fun edgePullWithoutScrollSuppressesCaptureUntilTheExistingQuietRelease() {
        val renderer = SourceContract.read("liquid/LiquidActivityRenderer.kt")
        val stretch = renderer.after("private fun onStretchDistanceChanged(").before("override fun onTrimMemory(")
        assertTrue(stretch.contains("if (distance > 0f) suppressRealtimeSamplingWhileScrolling()"))
        val settle = renderer.after("private fun onScrollSettleCheck(").before("private fun clearScrollSuppression(")
        assertTrue(settle.contains("stretchOpticalIntensity > 1f"))
        assertTrue(settle.contains("LiquidRealtimeCapturePolicy.WAKE_SETTLE_FRAMES"))
    }
}
