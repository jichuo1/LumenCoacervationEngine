package com.lumen.coacervation.engine.interaction

import org.junit.Assert.assertEquals
import org.junit.Test

class ElasticClipPolicyTest {
    @Test fun `window and viewport remain hard boundaries even when also tagged as panels`() {
        assertEquals(ElasticClipAction.STOP, ElasticClipPolicy.action(true, false, false))
        assertEquals(ElasticClipAction.STOP, ElasticClipPolicy.action(false, true, true))
    }
    @Test fun `panel padding can carry motion without opening child or outer clipping`() {
        assertEquals(ElasticClipAction.PANEL_PADDING, ElasticClipPolicy.action(false, false, true))
        assertEquals(ElasticClipAction.RELIEVE, ElasticClipPolicy.action(false, false, false))
    }
}
