package com.lumen.coacervation.engine.morph

import com.lumen.coacervation.engine.motion.LumenSpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenMorphPolicyTest {
    @Test fun widthAndRadiusInterpolateFromCollapsedToExpanded() {
        assertEquals(.5f, LumenMorphPolicy.widthFraction(0f, .5f), 0f)
        assertEquals(.75f, LumenMorphPolicy.widthFraction(.5f, .5f), 0f)
        assertEquals(1f, LumenMorphPolicy.widthFraction(1f, .5f), 0f)
        assertEquals(1f, LumenMorphPolicy.widthFraction(2f, .5f), 0f)
        assertEquals(80f, LumenMorphPolicy.radiusPx(0f, 80f, 28f), 0f)
        assertEquals(54f, LumenMorphPolicy.radiusPx(.5f, 80f, 28f), 0f)
        assertEquals(28f, LumenMorphPolicy.radiusPx(1f, 80f, 28f), 0f)
        assertEquals(0f, LumenMorphPolicy.clamp01(Float.NaN), 0f)
    }

    @Test fun contentAlphaStartsAfterTheThresholdAndCanBeDisabled() {
        assertEquals(0f, LumenMorphPolicy.contentAlpha(0f, true), 0f)
        assertEquals(0f, LumenMorphPolicy.contentAlpha(.35f, true), 0f)
        assertEquals(.5f, LumenMorphPolicy.contentAlpha(.675f, true), 1e-6f)
        assertEquals(1f, LumenMorphPolicy.contentAlpha(1f, true), 0f)
        assertEquals(1f, LumenMorphPolicy.contentAlpha(0f, false), 0f)
    }

    @Test fun contentOffsetIsZeroWhenExpanded() {
        assertEquals(-50f, LumenMorphPolicy.contentOffset(0f, 80f, 130f), 0f)
        assertEquals(0f, LumenMorphPolicy.contentOffset(1f, 80f, 130f), 0f)
        assertEquals(0f, LumenMorphPolicy.contentOffset(3f, 80f, 130f), 0f)
    }

    @Test fun optionsValidateRangesAndDefaultsUseDistinctSprings() {
        val defaults = LumenMorphOptions.DEFAULT
        assertTrue(defaults.enabled)
        assertEquals(.5f, defaults.collapsedFraction, 0f)
        assertEquals(80f, defaults.collapsedRadiusDp, 0f)
        assertEquals(28f, defaults.expandedRadiusDp, 0f)
        assertTrue(defaults.widthSpring.dampingRatio != defaults.cornerSpring.dampingRatio)
        assertEquals(LumenSpringSpec.ofResponseBounce(.2f, 0f), defaults.contentSpring)
        assertThrows(IllegalArgumentException::class.java) {
            LumenMorphOptions(collapsedFraction = .1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LumenMorphOptions(cornerSmoothing = .7f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LumenMorphOptions(contactAlpha = .4f)
        }
        assertFalse(LumenMorphOptions(enabled = false).enabled)
    }
}
