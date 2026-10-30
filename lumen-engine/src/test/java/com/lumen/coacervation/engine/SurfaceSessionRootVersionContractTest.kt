package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 根来源 contentVersion 在一次 onPreDraw 里只读一次：onPreDraw 读到的值直接交给可用性汇总，Group 构造时同理。
 * 汇总自己不得再读 `current.contentVersion`，观察 helper 不得持有跨回调状态。
 */
class SurfaceSessionRootVersionContractTest {
    private val session = SourceContract.read("host/LumenSurfaceSession.kt")
    private val observation = SourceContract.read("runtime/LumenSourceObservation.kt")

    @Test fun preDrawHandsTheVersionItJustReadToTheAvailabilityAggregation() {
        val preDraw = session.after("override fun onPreDraw(): Boolean {").before("if(dependenciesChanged())")
        assertTrue(preDraw.contains("var rootVersion=LumenSourceObservation.NO_VERSION"))
        assertTrue(preDraw.contains("val epoch=versioned.sourceEpoch;val version=versioned.contentVersion"))
        // 赋值要在 require 之前：负值也要原样交给汇总，保持「负版本 = 暂时不可用」的语义。
        assertTrue(preDraw.after("val version=versioned.contentVersion").before("require(epoch>=0L&&version>=0L)").contains("rootVersion=version"))
        assertEquals("the watcher reads the root version exactly once", 1, Regex("""versioned\.contentVersion""").findAll(preDraw).count())
        val afterDependencies = session.after("override fun onPreDraw(): Boolean {").after("if(dependenciesChanged())")
        assertTrue(afterDependencies.before("if(graphResult!=LumenSourceGraph.Result.VALID").contains("availability=readAvailability(versioned,rootVersion)"))
    }

    @Test fun groupConstructionReusesTheVersionItDeclaredFrom() {
        val declaration = session.after("var declaredEpoch=").before("private val sampleEvents=")
        assertTrue(declaration.contains("contentVersion?.also {initialRootVersion=it}"))
        assertTrue(declaration.contains("var availability=readAvailability(source as? LumenVersionedContentSource,initialRootVersion)"))
        assertTrue(declaration.contains("LumenSourceObservation.availability(trackedSources,root,rootVersion)"))
    }

    @Test fun aggregationNoLongerReadsContentVersionItself() {
        assertFalse(session.contains("current.contentVersion"))
        assertFalse(session.contains("trackedSources[i].contentVersion"))
    }

    @Test fun observationHelperHoldsNoCrossCallbackState() {
        // 只有 const 哨兵；没有 var / 缓存集合 / 时间戳，版本只在一次回调内传递。
        assertFalse(Regex("""\bvar\s""").containsMatchIn(observation.after("internal object LumenSourceObservation")))
        assertFalse(observation.contains("Map<"))
        assertFalse(observation.contains("nanoTime"))
    }
}
