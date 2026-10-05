package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.interaction.ElasticMotionGroupPolicy
import com.lumen.coacervation.engine.interaction.ElasticTravelPolicy
import com.lumen.coacervation.engine.interaction.ElasticVector
import com.lumen.coacervation.engine.motion.MorphCornerMode
import com.lumen.coacervation.engine.motion.MorphCornerPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEffectCompatibilityTest {
    @Test fun parentBoundsMatchesOriginalClampWithoutChangingDefaultNeighborAvoidance() {
        val original = ElasticVector()
        val adapted = ElasticVector()
        for (gap in listOf(-10f, 0f, 4f, 12f, 100f)) {
            for (limit in listOf(0f, 18f)) {
                val bound = ElasticMotionGroupPolicy.travelBound(gap, 3f, limit, 2.5f,
                    ElasticTravelPolicy.PARENT_BOUNDS)
                for (distance in listOf(-100f, -18f, -4f, 0f, 4f, 18f, 100f)) {
                    ElasticMotionGroupPolicy.clampToParent(distance, distance, gap, gap, gap, gap,
                        original, minTravel = limit)
                    ElasticMotionGroupPolicy.clampToParent(distance, distance, bound, bound, bound, bound, adapted)
                    assertEquals(original.x, adapted.x, 0f)
                    assertEquals(original.y, adapted.y, 0f)
                }
            }
        }
        assertEquals(.5f, ElasticMotionGroupPolicy.travelBound(0f, 3f, 18f, 2.5f), 0f)
        assertEquals(18f, ElasticMotionGroupPolicy.travelBound(0f, 3f, 18f, 2.5f,
            ElasticTravelPolicy.PARENT_BOUNDS), 0f)
    }

    @Test fun capsuleModeKeepsOriginalShortSideRadiusForAllDeclaredCorners() {
        for (declared in listOf(Float.NaN, -1f, 0f, 12f, 1000f)) {
            assertEquals(27f, MorphCornerPolicy.collapsedRadius(declared, 300f, 54f,
                MorphCornerMode.CAPSULE), 0f)
            assertEquals(27f, MorphCornerPolicy.collapsedRadius(declared, 54f, 300f,
                MorphCornerMode.CAPSULE), 0f)
        }
        assertEquals(12f, MorphCornerPolicy.collapsedRadius(12f, 300f, 54f), 0f)
        assertEquals(0f, MorphCornerPolicy.collapsedRadius(0f, 300f, 54f), 0f)
    }

    @Test fun invalidMorphDimensionsRemainSafeInBothModes() {
        for (mode in MorphCornerMode.entries) {
            for (size in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
                assertEquals(0f, MorphCornerPolicy.collapsedRadius(12f, size, 100f, mode), 0f)
                assertEquals(0f, MorphCornerPolicy.collapsedRadius(12f, 100f, size, mode), 0f)
            }
        }
    }

    @Test fun travelPolicyIsReadOnlyAtPressPreparationAndSkipsNeighborAllocation() {
        val controller = MotionSource.file("ElasticInteractionController")
        val capture = controller.after("private fun captureGroupGaps(group: View) {").before("private fun validGeometry()")
        val parent = capture.after("if (policy == ElasticTravelPolicy.PARENT_BOUNDS) {")
            .before("val count = parent.childCount")
        assertTrue(parent.contains("return"))
        assertFalse(parent.contains("FloatArray("))
        val drag = controller.after("private fun dragTo(").before("private fun animateFrame(")
        assertFalse(drag.contains("travelPolicy"))
        val frame = controller.after("private fun animateFrame(").before("private fun ")
        assertFalse(frame.contains("travelPolicy"))
    }

    @Test fun activityAndDialogControllersUseTheSamePolicyWithoutChangingConstructorSignatures() {
        val wrapper = MotionSource.file("LumenElasticInteraction")
        assertTrue(wrapper.contains("windowController?.travelPolicy = value"))
        assertTrue(wrapper.contains("dialogInteractions.values.forEach { it.controller.travelPolicy = value }"))
        assertTrue(wrapper.contains("it.travelPolicy = travelPolicy"))
        val signature = wrapper.after("public class LumenElasticInteraction(").before(") {")
        assertFalse(signature.contains("travelPolicy"))
    }

    @Test fun presentedModalKeepsItsCapturedCornerModeThroughoutTheMotion() {
        val presenter = MotionSource.file("LumenModalPresenter")
        assertTrue(presenter.contains("public var anchorCornerMode: MorphCornerMode = MorphCornerMode.DECLARED"))
        val present = MotionSource.function("present")
        assertTrue(present.contains("val cornerMode = anchorCornerMode"))
        assertTrue(present.contains("liveAnchor?.declaredCornerRadius() ?: anchorCornerRadiusPx, cornerMode)"))
    }
}
