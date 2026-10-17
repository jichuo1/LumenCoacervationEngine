package com.lumen.coacervation.engine.motion

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenSpringSpecTest {

    @Test fun defaultMatchesTheEngineSpring() {
        assertEquals(310f, LumenSpringSpec.DEFAULT.stiffness, 0f)
        assertEquals(.7f, LumenSpringSpec.DEFAULT.dampingRatio, 0f)
    }

    @Test fun responseBounceMapsToStiffnessAndDampingLikeVendorSpringForces() {
        // ColorOS COUISpringForce: stiffness=(2π/response)²,dampingRatio=1−bounce
        val spec = LumenSpringSpec.ofResponseBounce(0.3f, 0.15f)
        assertEquals((2.0 * PI / 0.3).let { it * it }.toFloat(), spec.stiffness, 1e-3f)
        assertEquals(0.85f, spec.dampingRatio, 1e-6f)
        // bounce 0 → 近临界阻尼；厂商库大量使用的 response 0.15–0.4s 都落在合法范围
        assertEquals(0.95f, LumenSpringSpec.ofResponseBounce(0.4f, 0f).dampingRatio, 1e-6f)
        LumenSpringSpec.ofResponseBounce(0.15f, 0.2f)
    }

    @Test fun specStaysUnderdampedAndBounded() {
        assertThrows(IllegalArgumentException::class.java) { LumenSpringSpec(stiffness = 0f) }
        assertThrows(IllegalArgumentException::class.java) { LumenSpringSpec(stiffness = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { LumenSpringSpec(dampingRatio = 1f) }
        assertThrows(IllegalArgumentException::class.java) { LumenSpringSpec(dampingRatio = 0f) }
        assertThrows(IllegalArgumentException::class.java) { LumenSpringSpec.ofResponseBounce(0.01f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { LumenSpringSpec.ofResponseBounce(2f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { LumenSpringSpec.ofResponseBounce(0.3f, 1f) }
        // 极短周期换算出的刚度被收进上限，弹簧轴仍可解
        assertTrue(LumenSpringSpec.ofResponseBounce(0.08f, 0.5f).stiffness <= LumenSpringSpec.MAX_STIFFNESS)
    }

    @Test fun specDrivesTheExactSpringAxisToRest() {
        // 弹簧轴只接受欠阻尼：规格范围保证这一点，回弹应在有限步内收敛
        for (spec in listOf(LumenSpringSpec.DEFAULT, LumenSpringSpec.ofResponseBounce(0.3f, 0.15f),
            LumenSpringSpec.ofResponseBounce(0.4f, 0f))) {
            val axis = com.lumen.coacervation.engine.interaction.ElasticSpringAxis(12f)
            repeat(720) {
                axis.advance(1f / 120f, 0f, spec.stiffness, spec.dampingRatio)
                assertTrue(axis.value.isFinite())
            }
            assertTrue(axis.atRest(0f, .025f))
        }
    }
}
