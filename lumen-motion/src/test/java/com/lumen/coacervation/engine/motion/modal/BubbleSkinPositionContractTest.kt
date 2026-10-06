package com.lumen.coacervation.engine.motion.modal

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleSkinPositionContractTest {
    @Test fun bubblePresenterConnectsRowMotionAndRestorationToTheEngine() {
        val controller = MotionSource.file("BubbleMotionController")
        val frame = controller.after("private fun apply(").before("private fun finish(")
        assertTrue(frame.indexOf("layer.applyFrame(clamped, entryShape)") < frame.indexOf("onContentMoved()"))
        val settle = controller.after("private fun settleExpanded()").before("fun beginPredictiveBack(")
        assertTrue(settle.indexOf("layer.settleExpanded()") < settle.indexOf("onContentMoved()"))
        val presenter = MotionSource.file("LumenModalPresenter")
            .after("val bubbleController = if (bubbleLayer != null)").before("val titleMotion =")
        assertTrue(presenter.contains("onContentMoved = { lumen.notifyPositionChanged() }"))
    }
}
