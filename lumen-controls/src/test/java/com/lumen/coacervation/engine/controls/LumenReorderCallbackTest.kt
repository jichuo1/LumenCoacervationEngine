package com.lumen.coacervation.engine.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenReorderCallbackTest {
    /** 落位滑行随距离走：短拖不拖沓、长拖不"撞回去"，有上下限。 */
    @Test fun dropSettleDurationGrowsWithDistanceAndIsBounded() {
        assertEquals(LumenReorderCallback.DROP_SETTLE_MIN_MS, LumenReorderCallback.dropSettleDurationMs(0f))
        assertEquals(LumenReorderCallback.DROP_SETTLE_MIN_MS, LumenReorderCallback.dropSettleDurationMs(-50f))
        assertEquals(236L, LumenReorderCallback.dropSettleDurationMs(100f))
        assertEquals(LumenReorderCallback.DROP_SETTLE_MAX_MS, LumenReorderCallback.dropSettleDurationMs(10_000f))
        var previous = 0L
        for (px in 0..3000 step 10) {
            val duration = LumenReorderCallback.dropSettleDurationMs(px.toFloat())
            assertTrue(duration >= previous)
            assertTrue(duration in LumenReorderCallback.DROP_SETTLE_MIN_MS..LumenReorderCallback.DROP_SETTLE_MAX_MS)
            previous = duration
        }
    }

    /** 拾起放大按卡片尺寸封顶（适配标准 §14.5）：一般行 1.03，大卡片最多长大 8dp。 */
    @Test fun liftScaleIsCappedForLargeCards() {
        val density = 3f
        assertEquals(LumenReorderCallback.LIFT_SCALE, LumenReorderCallback.liftScale((200 * density).toInt(), density), 0f)
        val large = LumenReorderCallback.liftScale((360 * density).toInt(), density)
        assertEquals(1f + 8f * density / (360 * density), large, 1e-6f)
        assertTrue(large < LumenReorderCallback.LIFT_SCALE)
        assertEquals(LumenReorderCallback.LIFT_SCALE, LumenReorderCallback.liftScale(0, density), 0f)
        assertEquals(LumenReorderCallback.LIFT_SCALE, LumenReorderCallback.liftScale(1000, Float.NaN), 0f)
    }
}
