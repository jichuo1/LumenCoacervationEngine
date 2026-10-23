package com.lumen.coacervation.engine.runtime

import org.junit.Assert.*
import org.junit.Test

class PreparedBackdropCacheTest {
    @Test fun exactKeysSeparateThemesSizesAndBackgroundRevisions() {
        data class Key(val revision: String, val dark: Boolean, val width: Int)
        PreparedBackdropCache<Key, Any>().use { cache ->
            val pixels = Any()
            assertTrue(cache.put(Key("image-a", false, 1080), pixels, 1024))
            assertSame(pixels, cache.get(Key("image-a", false, 1080)))
            assertNull(cache.get(Key("image-a", true, 1080)))
            assertNull(cache.get(Key("image-a", false, 1440)))
            assertNull(cache.get(Key("image-b", false, 1080)))
        }
    }

    @Test fun pageCountCannotGrowTheCacheAndBothRendererKindsStayInBudget() {
        PreparedBackdropCache<Int, Any>().use { material ->
            PreparedBackdropCache<Int, Any>().use { liquid ->
                repeat(1000) { page ->
                    material.put(page, Any(), PreparedBackdropCache.MAX_BYTES_PER_RENDERER)
                    liquid.put(page, Any(), PreparedBackdropCache.MAX_BYTES_PER_RENDERER)
                    assertTrue(material.retainedBytes + liquid.retainedBytes <= PreparedBackdropCache.MAX_PROCESS_BYTES)
                    if (page > 0) assertNull(material.get(page - 1))
                }
            }
        }
    }

    @Test fun oversizedOrInvalidEntriesCannotExpandRetention() {
        PreparedBackdropCache<String, Any>().use { cache ->
            assertTrue(cache.put("a", Any(), 100))
            assertFalse(cache.put("b", Any(), PreparedBackdropCache.MAX_BYTES_PER_RENDERER + 1))
            assertEquals(0L, cache.retainedBytes)
            assertNull(cache.get("a"))
            assertFalse(cache.put("c", Any(), -1))
        }
    }

    @Test fun memoryReleaseDropsCacheOwnershipWithoutClosingPublishedPixels() {
        class Pixels : AutoCloseable { var closed = false; override fun close() { closed = true } }
        val published = Pixels()
        val cache = PreparedBackdropCache<String, Pixels>()
        cache.put("a", published, 100)
        LumenMemoryPressureHub.releaseGraphics()
        assertNull(cache.get("a"))
        assertEquals(0L, cache.retainedBytes)
        assertFalse(published.closed)
        assertTrue(cache.put("b", published, 100))
        cache.close()
        assertFalse(published.closed)
        assertFalse(cache.put("c", published, 100))
    }
}
