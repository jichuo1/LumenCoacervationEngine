package com.lumen.coacervation.engine.motion.reveal

import org.junit.Assert.assertEquals
import org.junit.Test

/** A geometry loop must converge, including targets whose full width cannot fit the viewport. */
class RevealHorizontalPolicyTest {
    @Test fun immutableGeometryHasAStableDestinationAcrossTargetWidthsAndStartingPositions() {
        for (width in listOf(1, 100, 176, 177, 199, 200, 201, 500)) {
            for (left in listOf(-100, 0, 10, 12, 300)) {
                val delta = RevealPolicy.horizontalDelta(left, left + width, 0, 200, 12)
                val arrived = left - delta
                assertEquals("width=$width left=$left", 0,
                    RevealPolicy.horizontalDelta(arrived, arrived + width, 0, 200, 12))
            }
        }
    }

    @Test fun oversizedTargetAlignsToStartEvenThoughItsFarEdgeRemainsOutside() {
        assertEquals(300, RevealPolicy.horizontalDelta(300, 800, 0, 200, 12))
        assertEquals(0, RevealPolicy.horizontalDelta(0, 500, 0, 200, 12))
    }

    @Test fun viewportPaddingAndLimitedMarginHaveTheSameFixedPoint() {
        val delta = RevealPolicy.horizontalDelta(300, 490, 8, 208, 12)
        assertEquals(287, delta)
        assertEquals(0, RevealPolicy.horizontalDelta(300 - delta, 490 - delta, 8, 208, 12))
    }
}
