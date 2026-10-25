package com.lumen.coacervation.engine.runtime

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.host.LumenPreparedBackdropCache
import com.lumen.coacervation.engine.liquid.LiquidBackdropSizingPolicy
import com.lumen.coacervation.engine.material.ModernMaterialPolicy
import org.junit.Assert.*
import org.junit.Test

class PreparedBackdropCacheTest {
    @Test fun anExplicitHostPresentationBudgetPreservesItsRecipeAndStillRejectsOversize() {
        val budget = 8L * 1024 * 1024 * 4 + 2L * 1024 * 1024
        LumenPreparedBackdropCache<Int, Any>(budget).use { cache ->
            val published = Any()
            assertTrue(cache.put(1, published, 18L * 1024 * 1024))
            assertSame(published, cache.get(1))
            assertTrue(cache.put(2, published, budget))
            assertNull(cache.get(1))
            assertEquals(budget, cache.retainedBytes)
            assertFalse(cache.put(3, published, budget + 1))
            assertEquals(0L, cache.retainedBytes)
        }
        for (invalid in listOf(0L, -1L, budget + 1)) {
            try {
                LumenPreparedBackdropCache<Int, Any>(invalid).close()
                fail("An unsupported host budget must fail before registering ownership")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun exactKeysSeparateThemesSizesAndBackgroundRevisions() {
        data class Key(val revision: String, val dark: Boolean, val width: Int)
        LumenPreparedBackdropCache<Key, Any>().use { cache ->
            val pixels = Any()
            assertTrue(cache.put(Key("image-a", false, 1080), pixels, 1024))
            assertSame(pixels, cache.get(Key("image-a", false, 1080)))
            assertNull(cache.get(Key("image-a", true, 1080)))
            assertNull(cache.get(Key("image-a", false, 1440)))
            assertNull(cache.get(Key("image-b", false, 1080)))
        }
    }

    @Test fun pageCountCannotGrowTheCacheAndBothRendererKindsStayInBudget() {
        LumenPreparedBackdropCache<Int, Any>().use { material ->
            LumenPreparedBackdropCache<Int, Any>().use { liquid ->
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
        LumenPreparedBackdropCache<String, Any>().use { cache ->
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
        val cache = LumenPreparedBackdropCache<String, Pixels>()
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

    @Test fun engineCachesKeepTheLeastRecentlyUsedEntriesWithinBothLimits() {
        PreparedBackdropCache<String, Any>(maxBytes = 1000, maxEntries = 3).use { cache ->
            val a = Any(); val b = Any(); val c = Any(); val d = Any()
            assertTrue(cache.put("a", a, 100)); assertTrue(cache.put("b", b, 100)); assertTrue(cache.put("c", c, 100))
            assertSame(a, cache.get("a"))          // a 变为最近使用
            assertTrue(cache.put("d", d, 100))     // 淘汰最久未用的 b
            assertNull(cache.get("b"))
            assertSame(a, cache.get("a")); assertSame(c, cache.get("c")); assertSame(d, cache.get("d"))
            assertEquals(300L, cache.retainedBytes)
            assertTrue(cache.put("e", Any(), 900))  // 字节上限：按最久未用淘汰 a、c，d + e 恰好 1000
            assertEquals(1000L, cache.retainedBytes)
            assertNull(cache.get("a")); assertNull(cache.get("c")); assertSame(d, cache.get("d"))
            assertTrue(cache.put("e", Any(), 400))  // 同键替换按新字节数计
            assertEquals(500L, cache.retainedBytes)
            cache.put("custom", Any(), 100)
            cache.removeIf { it == "custom" }
            assertNull(cache.get("custom"))
            assertEquals(500L, cache.retainedBytes)
        }
        for (invalid in listOf(0, PreparedBackdropCache.ENGINE_ENTRIES + 1)) {
            try {
                PreparedBackdropCache<Int, Any>(maxEntries = invalid).close()
                fail("entry count $invalid must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }

    /**
     * 真实工作集（ENGINEERING_RULES §5）：同一配色下竖屏、横屏、输入法压缩高度三种根尺寸的自动背景，
     * 柔光（原图 + 模糊副本）与 Liquid（显示与光学共用一张）都必须同时装进各自 4 MiB，
     * 否则 LRU 在旋转/键盘来回切换时退化为每次重建。
     */
    @Test fun automaticBackdropWorkingSetFitsTheRendererBudget() {
        for ((width, height) in listOf(720 to 1600, 1080 to 2400, 1440 to 3200, 1600 to 2560, 2560 to 1600, 1812 to 2176)) {
            val roots = listOf(width to height, height to width, width to (height * 0.55f).toInt())
            val frosted = roots.sumOf { (w, h) ->
                val (sw, sh) = ModernMaterialPolicy.sampleSize(w, h); sw.toLong() * sh * 4 * 2
            }
            val liquid = roots.sumOf { (w, h) ->
                val size = LiquidBackdropSizingPolicy.resolve(w, h); size.width.toLong() * size.height * 4
            }
            assertEquals(PreparedBackdropCache.ENGINE_ENTRIES, roots.size)
            assertTrue("frosted ${width}x$height = $frosted", frosted <= PreparedBackdropCache.MAX_BYTES_PER_RENDERER)
            assertTrue("liquid ${width}x$height = $liquid", liquid <= PreparedBackdropCache.MAX_BYTES_PER_RENDERER)
        }
    }

    /** 自动背景命中缓存时照常淡入；只有自定义背景沿用"命中即满幅"的旧行为（2026-10-25 用户确认）。 */
    @Test fun automaticFrostHitsKeepTheRevealFadeAndCustomStateIsUnchanged() {
        val request = SourceContract.read("material/FrostedMaterialRenderer.kt")
            .after("private fun requestBackdrop(").before("private fun acceptBackdrop(")
        val autoHit = request.after("val preparedAuto =").before("val isDark")
        assertTrue(autoHit.contains("acceptBackdrop(token, preparedAuto)"))
        assertFalse(autoHit.contains("animateReveal"))
        // pendingBackdropKey 仍只表示自定义背景：动效就绪判定依赖它。
        assertTrue(request.contains("pendingBackdropKey = key"))
        assertFalse(request.contains("pendingBackdropKey = autoKey"))
    }

    /** Liquid 自定义模式下解码前的自动占位不得入缓存，否则会以自定义背景的键被恢复成"图片"。 */
    @Test fun liquidCachesOnlyAutomaticSourcesUnderAutomaticMode() {
        val rebuild = SourceContract.read("liquid/LiquidActivityRenderer.kt")
            .after("private fun rebuildBackdrop(").before("private fun scheduleCustomBackdropIfNeeded(")
        assertTrue(rebuild.contains("val autoMode = backgroundConfig.mode != LiquidBackgroundMode.CUSTOM"))
        assertTrue(rebuild.contains("if (autoMode) preparedBackdrops.put("))
        assertTrue(rebuild.contains("val cached = if (autoMode) preparedBackdrops.get("))
    }

    /**
     * Liquid 自动背景预热（2026-10-25）：结果经原子槽交给主线程，不得改成 Handler 投递
     * （prepare 与 bindRoot 常在同一个 onCreate 消息里，投递的结果要到绑定之后才执行）；
     * 未取用的预热结果必须在 close() 里回收。
     */
    @Test fun liquidPrewarmHandsOffThroughTheAtomicSlotAndIsDrainedOnClose() {
        val renderer = SourceContract.read("liquid/LiquidActivityRenderer.kt")
        val prewarm = renderer.after("internal fun prewarmBackdrop()").before("private fun takePrewarmedBackdrop(")
        assertTrue(prewarm.contains("backgroundWorker.execute"))
        assertTrue(prewarm.contains("prewarmSlot.compareAndSet(PREWARM_PENDING, source)"))
        assertFalse(prewarm.contains("mainHandler.post"))
        val rebuild = renderer.after("private fun rebuildBackdrop(").before("private fun scheduleCustomBackdropIfNeeded(")
        assertTrue(rebuild.contains("takePrewarmedBackdrop(width, height)"))
        val close = renderer.after("    override fun close() {")
        assertTrue(close.before("val root = boundRoot").contains("prewarmSlot.getAndSet(PREWARM_TAKEN)"))
        // 只在构造成功后由会话触发，不得写进 init。
        assertFalse(renderer.contains("init {\n        prewarm"))
        assertTrue(SourceContract.read("runtime/ActivitySkinSession.kt").contains("?.also(LiquidActivityRenderer::prewarmBackdrop)"))
    }
}
