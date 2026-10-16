package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.*
import org.junit.Test

class SurfaceSourceFrameClipContractTest {
    @Test fun gpuAndSoftwareUseOneFrameClipAndDeferredScrollCleanup() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val record = session.after("private fun recordSource(").before("private fun contentPixels()")
        val clip = record.indexOf("canvas.clipRect(0, 0, view.width, view.height)")
        val draw = record.indexOf("source.drawContent(canvas)")
        val restore = record.indexOf("canvas.restoreToCount(saved)")
        val finish = record.indexOf("recordingScrolls.finishRecord(!closed && !paused, applyDeferredScroll)")
        assertTrue(clip >= 0 && draw > clip && restore > draw && finish > restore)
        assertTrue(record.contains("recordingScrolls.beginRecord()"))
        assertTrue(record.contains("finally { canvas.restoreToCount(saved) }"))
        assertTrue(session.contains("override fun drawContent(canvas: Canvas) = recordSource(source, canvas)"))
        assertTrue(session.contains("recordContent = { recordSource(source, it) }"))
    }
}
