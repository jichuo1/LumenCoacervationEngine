package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.runtime.LumenMemoryPressureHub
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureListener
import org.junit.Assert.assertEquals
import org.junit.Test

class LumenMemoryPressureHubTest {

    @Test
    fun `release walks current listeners and unregister stops further calls`() {
        val counts = mutableListOf(0, 0)
        val first = LumenMemoryPressureListener { counts[0] += 1 }
        val second = LumenMemoryPressureListener { counts[1] += 1 }
        LumenMemoryPressureHub.addListener(first)
        LumenMemoryPressureHub.addListener(second)
        try {
            LumenMemoryPressureHub.releaseGraphics()
            assertEquals(listOf(1, 1), counts)
            LumenMemoryPressureHub.removeListener(second)
            LumenMemoryPressureHub.releaseGraphics()
            assertEquals(listOf(2, 1), counts)
        } finally {
            LumenMemoryPressureHub.removeListener(first)
            LumenMemoryPressureHub.removeListener(second)
        }
    }
}
