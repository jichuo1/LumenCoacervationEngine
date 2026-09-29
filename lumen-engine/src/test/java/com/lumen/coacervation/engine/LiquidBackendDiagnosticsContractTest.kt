package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每一条降级路径都要留下原因（设置页的"降级原因"读它）。
 *
 * 演示包在 API 31 模拟器上观察到：高级材质生效后端是 TRANSLUCENT，降级原因却为空——
 * 绑定失败与内存压力这两条路径淘汰后端时没有记录，用户侧只看到"效果变朴素了"。
 */
class LiquidBackendDiagnosticsContractTest {
    private val source = SourceContract.read("liquid/LiquidBackendSet.kt")

    @Test fun bindFailureRecordsItsCause() {
        val bind = source.after("private fun ensureCurrentBound(").before("fun advanceAfterFailure(")
        assertTrue(bind.contains("bound.exceptionOrNull()?.let { recordFailure(driver.backend, it) }"))
    }

    @Test fun memoryPressureRecordsItsCause() {
        val translucent = source.after("fun advanceToTranslucent()").before("fun foreignRefractionDriver(")
        assertTrue(translucent.contains("failures.getOrPut(failed) { \"memory-pressure\" }"))
    }

    @Test fun runtimeDrawFailureStillRecordsItsCause() {
        val runtime = source.after("fun advanceAfterFailure(").before("fun advanceToTranslucent()")
        assertTrue(runtime.contains("failures.getOrPut(failed) { \"runtime-draw-failed\" }"))
    }
}
