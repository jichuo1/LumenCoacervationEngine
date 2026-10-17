package com.lumen.coacervation.engine.interaction

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElasticRubberCurveTest {

    @Test fun defaultCurveRemainsTanh() {
        val implicit = ElasticVector()
        val explicit = ElasticVector()
        ElasticMotionPolicy.drag(30f, -40f, 8f, 12f, implicit)
        ElasticMotionPolicy.drag(30f, -40f, 8f, 12f, explicit, ElasticRubberCurve.TANH)
        assertEquals(implicit.x, explicit.x, 0f)
        assertEquals(implicit.y, explicit.y, 0f)
        ElasticMotionPolicy.dragFrom(3f, 2f, 30f, -40f, 8f, 12f, implicit)
        ElasticMotionPolicy.dragFrom(3f, 2f, 30f, -40f, 8f, 12f, explicit, ElasticRubberCurve.TANH)
        assertEquals(implicit.x, explicit.x, 0f)
        assertEquals(implicit.y, explicit.y, 0f)
    }

    @Test fun rationalCurveIsContinuousAtSlopMonotonicAndAsymptoticToTheLimit() {
        val out = ElasticVector()
        var previous = 0f
        for (step in 0..10000) {
            val x = step / 10f
            ElasticMotionPolicy.drag(x, 0f, 8f, 12f, out, ElasticRubberCurve.RATIONAL)
            assertTrue(out.x >= previous)
            assertTrue(out.x in 0f..12f)
            previous = out.x
        }
        ElasticMotionPolicy.drag(8f, 0f, 8f, 12f, out, ElasticRubberCurve.RATIONAL)
        assertEquals(0f, out.x, 0f)
        ElasticMotionPolicy.drag(8.0001f, 0f, 8f, 12f, out, ElasticRubberCurve.RATIONAL)
        assertTrue(out.x < .0001f)
        // 超大距离仍不超过渐近上限
        ElasticMotionPolicy.drag(1_000_000f, 0f, 8f, 12f, out, ElasticRubberCurve.RATIONAL)
        assertTrue(out.x <= 12.0001f)
    }

    @Test fun rationalCurveKeepsTheSingleRadialBudgetInEveryDirection() {
        val axial = ElasticVector()
        val rotated = ElasticVector()
        for (distance in listOf(8.0001f, 20f, 90f, 10000f)) {
            ElasticMotionPolicy.drag(distance, 0f, 8f, 12f, axial, ElasticRubberCurve.RATIONAL)
            for (degree in 0 until 360 step 15) {
                val angle = Math.toRadians(degree.toDouble())
                ElasticMotionPolicy.drag((distance * kotlin.math.cos(angle)).toFloat(),
                    (distance * kotlin.math.sin(angle)).toFloat(), 8f, 12f, rotated, ElasticRubberCurve.RATIONAL)
                assertEquals(axial.x, hypot(rotated.x, rotated.y), .0001f)
            }
        }
    }

    @Test fun rationalCurveFollowsTheFingerCloselyAtFirstThenResistsHarder() {
        val tanh = ElasticVector()
        val rational = ElasticVector()
        // 初段斜率：RATIONAL(0.55) > TANH(0.22)，小行程更跟手
        ElasticMotionPolicy.drag(12f, 0f, 8f, 12f, tanh, ElasticRubberCurve.TANH)
        ElasticMotionPolicy.drag(12f, 0f, 8f, 12f, rational, ElasticRubberCurve.RATIONAL)
        assertTrue(rational.x > tanh.x)
        // 远端两者都收敛到同一上限
        ElasticMotionPolicy.drag(100000f, 0f, 8f, 12f, tanh, ElasticRubberCurve.TANH)
        ElasticMotionPolicy.drag(100000f, 0f, 8f, 12f, rational, ElasticRubberCurve.RATIONAL)
        assertEquals(tanh.x, rational.x, .01f)
    }

    @Test fun invalidInputStillClearsTheSharedOutput() {
        val out = ElasticVector(10f, 12f)
        ElasticMotionPolicy.drag(Float.NaN, 0f, 8f, 12f, out, ElasticRubberCurve.RATIONAL)
        assertEquals(0f, out.x, 0f)
        assertEquals(0f, out.y, 0f)
        ElasticMotionPolicy.dragFrom(0f, 0f, 1f, 1f, 8f, Float.NaN, out, ElasticRubberCurve.RATIONAL)
        assertEquals(0f, out.x, 0f)
        assertEquals(0f, out.y, 0f)
    }
}
