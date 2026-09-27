package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidCaptureRequestState
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before

class LiquidCaptureRequestStateTest {
    @Test fun oldSuccessAndFailureAfterStopResumeCannotPublishOrTouchFailureCounters() {
        for (success in listOf(true, false)) {
            val state = LiquidCaptureRequestState()
            val old = state.begin()!!
            state.invalidate() // stop, then resume before the old PixelCopy callback arrives
            assertNull("An in-flight bitmap cannot be reused", state.begin())
            var published = 0; var failures = 0; var throughputSamples = 0
            if (state.complete(old) == LiquidCaptureRequestState.Completion.CURRENT) {
                if (success) published++ else failures++
                throughputSamples++
            }
            assertEquals(0, published); assertEquals(0, failures); assertEquals(0, throughputSamples)
            val fresh = state.begin()!!
            assertEquals(LiquidCaptureRequestState.Completion.CURRENT, state.complete(fresh))
        }
    }

    @Test fun resizeReleaseAndCloseInvalidateButDoNotUnlockTheNativeWriteEarly() {
        val state = LiquidCaptureRequestState()
        val pending = state.begin()!!
        repeat(3) { state.invalidate(); assertNull(state.begin()) }
        assertEquals(LiquidCaptureRequestState.Completion.STALE, state.complete(pending))
        assertNotNull(state.begin())
    }

    @Test fun duplicateOldCallbackCannotClearANewerNativeRequest() {
        val state = LiquidCaptureRequestState()
        val first = state.begin()!!
        assertEquals(LiquidCaptureRequestState.Completion.CURRENT, state.complete(first))
        val next = state.begin()!!
        assertEquals(LiquidCaptureRequestState.Completion.FOREIGN, state.complete(first))
        assertNull(state.begin())
        assertEquals(LiquidCaptureRequestState.Completion.CURRENT, state.complete(next))
    }

    // 2026-09-20：回弹边界环移除后，请求不再携带边界 retirement 列表，
    // 断言从 MaskAndRetirements 收窄为 Mask。
    @Test fun productionCallbackOwnsItsSourceRootSizeAndMask() {
        val relative = "src/main/java/com/lumen/coacervation/engine/liquid/LiquidActivityRenderer.kt"
        val source = SourceContract.read(relative)
        val callback = source.after("private fun handleRealtimeCaptureResult(").before("private fun applyCaptureThroughputSample")
        assertTrue(callback.indexOf("realtimeCaptureInFlight !== request") < callback.indexOf("realtimeCaptureInFlight = null"))
        // 2026-09-23 截图后处理移到截图线程：主线程提交先验票，再采信后台结论。
        assertTrue(callback.indexOf("Completion.CURRENT") < callback.indexOf("request.outcome"))
        assertTrue(callback.indexOf("Completion.CURRENT") < callback.indexOf("val workStartedNanos"))
        assertTrue(callback.contains("root.width != request.width || root.height != request.height"))
        assertTrue(source.contains("handleRealtimeCaptureResult(request, result)"))
        // 2026-09-23 抑制器拆出（凝光视效引擎重构）：请求借出的遮罩原样交给抑制器应用。
        val postProcess = source.after("private fun postProcessRealtimeCapture(")
            .before("\n}")
        assertTrue(postProcess.contains("request.source, request.stableBackdrop, request.mask, request.maskReady"))
        val suppressorPath = relative.replace("LiquidActivityRenderer.kt", "LiquidFeedbackSuppressor.kt")
        val suppressor = SourceContract.read(suppressorPath)
        assertTrue(suppressor.contains("canvas.drawPath(requestMask, suppressionPaint)"))
        val close = source.after("override fun close()")
        assertTrue(close.contains("captureRequests.invalidate()"))
        assertFalse(close.contains("realtimeCaptureInFlight = null"))
    }
}
