package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.geometry.CaptureSamplingGeometry
import com.lumen.coacervation.engine.geometry.SamplingMatrixMath
import com.lumen.coacervation.engine.geometry.ScrollSamplingGeometry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollSamplingGeometryTest {
    private fun translation(x: Float, y: Float) = floatArrayOf(1f, 0f, x, 0f, 1f, y, 0f, 0f, 1f)

    private fun relative(source: FloatArray, target: FloatArray): FloatArray = FloatArray(9).also {
        assertTrue(SamplingMatrixMath.bitmapToTarget(source, target, 1f, 1f, it, FloatArray(9)))
    }

    @Test fun independentSourceAndMovingTargetRemainStaleUntilTheActualDrawRecords() {
        val footprint = ScrollSamplingGeometry()
        val source = translation(0f, 0f)
        val initial = relative(source, translation(20f, 500f))
        footprint.record(initial)
        val scrolled = relative(source, translation(20f, 360f))
        assertTrue(footprint.needsRefresh(scrolled))
        assertTrue("Notification itself must not claim the new geometry", footprint.needsRefresh(scrolled))
        footprint.record(scrolled)
        assertFalse(footprint.needsRefresh(scrolled))
        val reversed = relative(source, translation(20f, 410f))
        assertTrue(footprint.needsRefresh(reversed))
        assertFalse("The last draw remains the authoritative footprint", footprint.needsRefresh(scrolled))
    }

    @Test fun commonAncestorTranslationCancelsWithoutTargetRerecording() {
        val footprint = ScrollSamplingGeometry()
        val initial = relative(translation(15f, 80f), translation(35f, 420f))
        footprint.record(initial)
        for (shift in listOf(-2_000f, -1_000f, 160f, 0f)) {
            val moved = relative(translation(15f, 80f + shift), translation(35f, 420f + shift))
            assertFalse(footprint.needsRefresh(moved))
        }
    }

    @Test fun movingOnlySourceChangesTheTargetSamplingGeometry() {
        val footprint = ScrollSamplingGeometry()
        val target = translation(40f, 200f)
        footprint.record(relative(translation(0f, 0f), target))
        assertTrue(footprint.needsRefresh(relative(translation(0f, -120f), target)))
    }

    @Test fun invalidGeometryNeverPublishesOrRequestsAReplacementFootprint() {
        val footprint = ScrollSamplingGeometry()
        val initial = translation(2f, 3f)
        assertTrue(footprint.needsRefresh(initial))
        footprint.record(initial)
        val invalid = initial.copyOf().also { it[2] = Float.NaN }
        assertFalse(footprint.needsRefresh(invalid))
        footprint.record(invalid)
        assertFalse(footprint.needsRefresh(initial))
        assertFalse(footprint.needsRefresh(FloatArray(2)))
    }

    @Test fun unchangedContentRecordingDoesNotRefreshGpuConsumers() {
        val geometry = CaptureSamplingGeometry()
        assertTrue(geometry.update(1_000, 2_000, 500, 1_000, .5f, .5f))
        repeat(120) { assertFalse(geometry.update(1_000, 2_000, 500, 1_000, .5f, .5f)) }
    }

    @Test fun budgetScaleOrSourceResizeRequiresNewInverseScaleInTheProxy() {
        val geometry = CaptureSamplingGeometry()
        geometry.update(1_000, 2_000, 500, 1_000, .5f, .5f)
        assertTrue(geometry.update(1_000, 2_000, 250, 500, .25f, .25f))
        assertFalse(geometry.update(1_000, 2_000, 250, 500, .25f, .25f))
        assertTrue(geometry.update(1_200, 2_000, 300, 500, .25f, .25f))
        assertTrue(geometry.update(1_200, 2_000, 301, 500, 301f / 1_200, .25f))
    }

    @Test fun releaseAndCaptureReplacementInvalidateTheRecordingGeometry() {
        val geometry = CaptureSamplingGeometry()
        geometry.update(1_000, 2_000, 500, 1_000, .5f, .5f)
        geometry.clear()
        assertTrue(geometry.update(1_000, 2_000, 500, 1_000, .5f, .5f))
        assertFalse(geometry.update(1_000, 2_000, 500, 1_000, .5f, .5f))
    }

    @Test fun failedOrInvalidCaptureCannotOverwriteTheLastSuccessfulGeometry() {
        val geometry = CaptureSamplingGeometry()
        geometry.update(1_000, 2_000, 500, 1_000, .5f, .5f)
        assertFalse(geometry.update(0, 2_000, 500, 1_000, .5f, .5f))
        assertFalse(geometry.update(1_000, 2_000, 500, 1_000, Float.NaN, .5f))
        assertFalse(geometry.update(1_000, 2_000, 500, 1_000, .5f, .5f))
    }
}
