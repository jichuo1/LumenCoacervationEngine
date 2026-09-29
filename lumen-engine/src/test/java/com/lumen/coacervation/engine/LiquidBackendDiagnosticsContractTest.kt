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

    /**
     * RenderNode.setPosition 返回"值是否变化"。把它当成功标志去 check，会让同尺寸的第二次绑定抛异常，
     * BLUR 后端在 API 31–32 上永远活不过第一次重绑。
     */
    @Test fun renderNodeSetPositionIsNeverTreatedAsASuccessFlag() {
        val sources = listOf("liquid/LiquidBlurBackendApi31.kt", "glow/GlowBackdropTarget.kt", "glow/GlowChromeGlassApi31.kt")
            .map { SourceContract.read(it) }
        sources.forEach { text ->
            assertTrue(!Regex("""(check|require)\s*\(\s*\w+\.setPosition\(""").containsMatchIn(text))
            assertTrue(!Regex("""if\s*\(\s*!?\s*\w+\.setPosition\(""").containsMatchIn(text))
        }
    }
}
