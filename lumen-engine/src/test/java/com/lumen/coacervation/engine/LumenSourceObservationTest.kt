package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.host.LumenSourceAvailability
import com.lumen.coacervation.engine.host.LumenVersionedContentSource
import com.lumen.coacervation.engine.runtime.LumenSourceObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Random

/**
 * 单次预绘制内根来源 contentVersion 只读一次。
 *
 * 来源接口带 android.view.View 属性，JVM 测试里不构造 View，用动态代理只实现被读取的三个属性并记录读取次数；
 * 一旦实现读了别的成员（coordinateView 等）测试直接失败，说明汇总的读取面变宽了。
 */
class LumenSourceObservationTest {
    private class Spec(
        var epoch: Long = 1L,
        var version: Long = 1L,
        var availability: LumenSourceAvailability = LumenSourceAvailability.READY,
        var throwOnEpoch: Boolean = false,
        var throwOnVersion: Boolean = false,
        var throwOnAvailability: Boolean = false
    ) {
        var epochReads = 0
        var versionReads = 0
        var availabilityReads = 0
        val order = ArrayList<String>()

        fun source(): LumenVersionedContentSource = Proxy.newProxyInstance(
            LumenVersionedContentSource::class.java.classLoader,
            arrayOf(LumenVersionedContentSource::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getSourceEpoch" -> { epochReads++; order += "e"; if (throwOnEpoch) error("epoch"); epoch }
                    "getContentVersion" -> { versionReads++; order += "v"; if (throwOnVersion) error("version"); version }
                    "getAvailability" -> { availabilityReads++; order += "a"; if (throwOnAvailability) error("availability"); availability }
                    "toString" -> "FakeSource"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> false
                    else -> throw AssertionError("observation must only read epoch/version/availability, not ${method.name}")
                }
            }
        ) as LumenVersionedContentSource

        fun reset() { epochReads = 0; versionReads = 0; availabilityReads = 0; order.clear() }
    }

    /** 改动前 LumenSurfaceSession.Group.readAvailability 的逐字拷贝，作差分基准。 */
    private fun legacyAvailability(tracked: List<LumenVersionedContentSource>): LumenSourceAvailability {
        return try {
            for (i in 0 until tracked.size) {
                val current = tracked[i]
                if (current.sourceEpoch < 0L || current.contentVersion < 0L) return LumenSourceAvailability.TEMPORARILY_UNAVAILABLE
                if (current.availability != LumenSourceAvailability.READY) return current.availability
            }
            LumenSourceAvailability.READY
        } catch (_: Throwable) {
            LumenSourceAvailability.TEMPORARILY_UNAVAILABLE
        }
    }

    /** onPreDraw 的根观察：先读 epoch 和 version，再汇总。返回根 contentVersion 的总读取次数。 */
    private fun preDrawWithLegacy(root: Spec, tracked: List<LumenVersionedContentSource>, rootSource: LumenVersionedContentSource): LumenSourceAvailability {
        runCatching { rootSource.sourceEpoch; rootSource.contentVersion }
        return legacyAvailability(tracked)
    }

    private fun preDrawWithObservation(root: Spec, tracked: List<LumenVersionedContentSource>, rootSource: LumenVersionedContentSource): LumenSourceAvailability {
        var rootVersion = LumenSourceObservation.NO_VERSION
        runCatching { rootSource.sourceEpoch; rootVersion = rootSource.contentVersion }
        return LumenSourceObservation.availability(tracked, rootSource, rootVersion)
    }

    @Test fun rootContentVersionIsReadOncePerPreDrawInsteadOfTwice() {
        for (trackedCount in listOf(1, 2, 5, 12)) {
            val specs = List(trackedCount) { Spec() }
            val sources = specs.map { it.source() }
            val root = specs[0]
            val rootSource = sources[0]

            preDrawWithLegacy(root, sources, rootSource)
            val legacyRootReads = root.versionReads
            val legacyOtherReads = specs.drop(1).map { it.versionReads }
            specs.forEach { it.reset() }

            val result = preDrawWithObservation(root, sources, rootSource)
            assertEquals(LumenSourceAvailability.READY, result)
            assertEquals("legacy path reads the root version in the watcher and again in the aggregation", 2, legacyRootReads)
            assertEquals("the aggregation reuses the version the watcher just read", 1, root.versionReads)
            assertEquals("every other tracked source is still read exactly once", legacyOtherReads, specs.drop(1).map { it.versionReads })
            assertEquals("epoch and availability reads are untouched", trackedCount, specs.sumOf { it.epochReads } - 1)
            assertEquals(trackedCount, specs.sumOf { it.availabilityReads })
        }
    }

    @Test fun withoutAReusableValueTheRootIsReadLikeBefore() {
        val root = Spec()
        val source = root.source()
        assertEquals(LumenSourceAvailability.READY, LumenSourceObservation.availability(listOf(source)))
        assertEquals(1, root.versionReads)
        root.reset()
        // 传了 root 但没有读到版本（NO_VERSION）：不能把哨兵当版本，必须重新读。
        assertEquals(LumenSourceAvailability.READY,
            LumenSourceObservation.availability(listOf(source), source, LumenSourceObservation.NO_VERSION))
        assertEquals(1, root.versionReads)
    }

    @Test fun reusedVersionIsOnlyAppliedToTheRootInstanceNotToOtherSources() {
        val a = Spec(version = 7L)
        val b = Spec(version = -1L)
        val tracked = listOf(a.source(), b.source())
        // 根是 a：b 仍要读自己的版本，为负就整体不可用。
        assertEquals(LumenSourceAvailability.TEMPORARILY_UNAVAILABLE, LumenSourceObservation.availability(tracked, tracked[0], 7L))
        assertEquals(0, a.versionReads)
        assertEquals(1, b.versionReads)
    }

    @Test fun aRootNotInTheTrackedListIsNeverConsulted() {
        val tracked = Spec()
        val outsider = Spec(version = -5L).source()
        assertEquals(LumenSourceAvailability.READY, LumenSourceObservation.availability(listOf(tracked.source()), outsider, -5L))
        assertEquals(1, tracked.versionReads)
    }

    @Test fun aNegativeReusedRootVersionStillMeansTemporarilyUnavailable() {
        val root = Spec(version = -3L)
        val source = root.source()
        assertEquals(LumenSourceAvailability.TEMPORARILY_UNAVAILABLE, LumenSourceObservation.availability(listOf(source), source, -3L))
        assertEquals(0, root.versionReads)
    }

    @Test fun readOrderAndShortCircuitMatchTheLegacyAggregation() {
        // epoch 为负时短路，不再读版本；版本之后才读 availability。
        val negativeEpoch = Spec(epoch = -1L)
        val s1 = negativeEpoch.source()
        assertEquals(LumenSourceAvailability.TEMPORARILY_UNAVAILABLE, LumenSourceObservation.availability(listOf(s1)))
        assertEquals(listOf("e"), negativeEpoch.order)

        val ready = Spec()
        val s2 = ready.source()
        LumenSourceObservation.availability(listOf(s2))
        assertEquals(listOf("e", "v", "a"), ready.order)

        val reused = Spec()
        val s3 = reused.source()
        LumenSourceObservation.availability(listOf(s3), s3, 1L)
        assertEquals(listOf("e", "a"), reused.order)
    }

    @Test fun firstNonReadySourceDecidesAndLaterSourcesAreNotTouched() {
        val first = Spec(availability = LumenSourceAvailability.TEMPORARILY_UNAVAILABLE)
        val second = Spec()
        val tracked = listOf(first.source(), second.source())
        assertEquals(LumenSourceAvailability.TEMPORARILY_UNAVAILABLE, LumenSourceObservation.availability(tracked, tracked[0], 1L))
        assertEquals(0, second.epochReads + second.versionReads + second.availabilityReads)
    }

    @Test fun differentialAgainstTheLegacyAggregationOverRandomStates() {
        val random = Random(20261029L)
        val availabilities = LumenSourceAvailability.values()
        repeat(4000) { round ->
            val count = 1 + random.nextInt(6)
            fun newSpec() = Spec(
                epoch = if (random.nextInt(10) == 0) -1L else random.nextInt(5).toLong(),
                version = if (random.nextInt(10) == 0) -1L else random.nextInt(5).toLong(),
                availability = if (random.nextInt(4) == 0) availabilities[random.nextInt(availabilities.size)] else LumenSourceAvailability.READY,
                throwOnEpoch = random.nextInt(30) == 0,
                throwOnVersion = random.nextInt(30) == 0,
                throwOnAvailability = random.nextInt(30) == 0
            )
            val legacySpecs = List(count) { newSpec() }
            // 同一组状态再造一份给新路径，读取计数各自独立。
            val newSpecs = legacySpecs.map { it.copy2() }
            val legacySources = legacySpecs.map { it.source() }
            val newSources = newSpecs.map { it.source() }
            val rootIndex = random.nextInt(count)
            val expected = preDrawWithLegacy(legacySpecs[rootIndex], legacySources, legacySources[rootIndex])
            val actual = preDrawWithObservation(newSpecs[rootIndex], newSources, newSources[rootIndex])
            assertEquals("round $round count=$count root=$rootIndex", expected, actual)
            for (i in 0 until count) {
                assertTrue(
                    "round $round source $i: new path must never read a version more often than legacy (${newSpecs[i].versionReads} vs ${legacySpecs[i].versionReads})",
                    newSpecs[i].versionReads <= legacySpecs[i].versionReads
                )
                assertEquals("round $round source $i epoch reads", legacySpecs[i].epochReads, newSpecs[i].epochReads)
                assertEquals("round $round source $i availability reads", legacySpecs[i].availabilityReads, newSpecs[i].availabilityReads)
            }
        }
    }

    private fun Spec.copy2() = Spec(epoch, version, availability, throwOnEpoch, throwOnVersion, throwOnAvailability)

    @Test fun observationKeepsNoStateAcrossCallbacks() {
        // 同一个来源版本在两次回调之间变化，第二次必须看到新值，而不是上一次回调的旧值。
        val root = Spec(version = 3L)
        val source = root.source()
        val tracked = listOf(source)
        assertEquals(LumenSourceAvailability.READY, LumenSourceObservation.availability(tracked, source, 3L))
        root.version = -1L
        assertEquals(LumenSourceAvailability.TEMPORARILY_UNAVAILABLE, LumenSourceObservation.availability(tracked, source))
        root.version = 4L
        assertEquals(LumenSourceAvailability.READY, LumenSourceObservation.availability(tracked))
        assertEquals(2, root.versionReads)
    }
}
