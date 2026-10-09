package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScrollPositionContractTest {
    @Test fun localScrollKeepsPauseMemoryRecordingAndWindowOwnershipGates() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val scroll = session.after("public fun notifyScrollPositionChanged(scrollHost: View)").before("public fun pause()")
        assertTrue(scroll.contains("if (closed) return"))
        assertTrue(scroll.contains("checkMain()"))
        assertTrue(scroll.contains("paused || memoryReleased || !options.enabled"))
        assertTrue(scroll.contains("if (recordingDepth > 0)"))
        assertTrue(scroll.contains("recordingScrolls.defer(scrollHost)"))
        assertTrue(scroll.contains("!scrollHost.isAttachedToWindow || !scrollHost.isShown"))
        assertTrue(scroll.contains("content.rootView !== windowRoot"))
        assertTrue(scroll.contains("!content.hasWindowFocus()"))
        assertTrue(scroll.contains("host.background !== entry.drawable"))
        assertTrue(scroll.contains("failure(host, entry) != LumenSurfaceFailure.NONE"))
        assertFalse(scroll.contains("memoryReleased=false"))
        assertFalse(scroll.contains("recordSource("))
        assertFalse(scroll.contains("createBitmap("))
    }

    @Test fun sourceRecordingReplaysDeferredActualScrollOnlyAfterTheOutermostFinally() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val recording = session.after("private fun recordSource(").before("private fun registerTrim()")
        assertTrue(recording.contains("recordingScrolls.beginRecord()"))
        assertTrue(recording.contains("try { source.drawContent(canvas) } finally"))
        assertTrue(recording.contains("recordingScrolls.finishRecord(!closed && !paused, applyDeferredScroll)"))
        val callback = session.after("private val applyDeferredScroll:").before("private var listener:")
        assertTrue(callback.contains("runCatching { notifyScrollPositionChanged(host) }"))
        assertFalse(callback.contains("drawContent("))
        val close = session.after("override fun close()").before("private inner class Group(")
        assertTrue(close.contains("recordingScrolls.clear()"))
        val pause = session.after("public fun pause()").before("public fun resume()")
        assertTrue(pause.contains("recordingScrolls.clear()"))
    }

    @Test fun localScrollComparesAffectedRelativeMapsAndOnlyMarksSourceContentDirty() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val scroll = session.after("public fun notifyScrollPositionChanged(scrollHost: View)").before("public fun pause()")
        assertTrue(scroll.contains("ScrollSurfaceScope.contains(content, scrollHost)"))
        assertTrue(scroll.contains("content === scrollHost"))
        assertTrue(scroll.contains("ScrollSurfaceScope.contains(scrollHost, content)"))
        assertTrue(scroll.contains("if (contentChanged) group.invalidateScrollContent()"))
        assertTrue(scroll.contains("ScrollSurfaceScope.contains(host, scrollHost)"))
        val scopeFilter = scroll.indexOf("if (!sourceMoved && !ScrollSurfaceScope.contains(host, scrollHost)")
        val feedbackCheck = scroll.indexOf("if (failure(host, entry) != LumenSurfaceFailure.NONE)")
        assertTrue(scopeFilter >= 0 && scopeFilter < feedbackCheck)
        assertTrue(scroll.contains("matrices.sourceToTarget(content, host, scrollSampleMatrix)"))
        assertTrue(scroll.contains("if (entry.scrollGeometry.needsRefresh(scrollMatrixValues))"))
        assertTrue(scroll.contains("entry.invalidateFrame()"))
        assertFalse(scroll.contains("scrollGeometry.record("))
        val dirty = session.after("fun invalidateScrollContent()").before("override fun onGlobalLayout()")
        assertTrue(dirty.contains("if (!dirty)"))
        assertTrue(dirty.contains("source.coordinateView.postInvalidateOnAnimation()"))
        assertTrue(dirty.contains("timeline.contentChanged()"))
        assertTrue(dirty.contains("sampler.invalidate()"))
        assertFalse(dirty.contains("recordSource("))
        assertFalse(dirty.contains("record("))
    }

    @Test fun onlyVisibleDynamicHardwareDrawPublishesPureRelativeGeometry() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val draw = session.after("override fun draw(canvas: Canvas)").before("private fun configureEnhancedPath(")
        assertTrue(draw.contains("if (recordingDepth > 0 || closed"))
        val saveRelative = draw.indexOf("sampleMatrix.getValues(scrollGeometryValues)")
        val gpuDraw = draw.indexOf("group.capture!!.draw(")
        val publish = draw.indexOf("entry.scrollGeometry.record(scrollGeometryValues)")
        assertTrue(saveRelative >= 0 && saveRelative < gpuDraw && gpuDraw < publish)
        assertTrue(draw.contains("canvas.isHardwareAccelerated && backend != LumenSurfaceBackend.STATIC && visibleEffect"))
        assertTrue(draw.contains("if (!sampleGeometryReady) sampleMatrix.getValues(scrollGeometryValues)"))
        val animation = session.after("public fun notifyPositionChanged()").before("public fun notifyScrollPositionChanged(")
        assertTrue(animation.contains("entries.forEach { it.invalidateFrame() }"))
        assertFalse(animation.contains("scrollGeometry"))
    }

    @Test fun successfulCaptureGeometryChangesRefreshOnlyExistingGpuProxies() {
        val session = SourceContract.read("host/LumenSurfaceSession.kt")
        val capture = session.after("if (current.record(recorder, options, available))").before("else { budgetRejected = true")
        assertTrue(capture.contains("captureGeometry.update(content.width, content.height, current.recordedWidth,"))
        assertTrue(capture.contains("current.recordedHeight, current.scaleX, current.scaleY"))
        assertTrue(capture.contains("entry.group === this && entry.gpu != null"))
        assertTrue(capture.contains("entry.stateBackend == LumenSurfaceBackend.GPU"))
        assertTrue(capture.contains("entry.invalidateFrame()"))
        val release = session.after("fun releaseGpu() {").before("fun release() {")
        assertTrue(release.contains("captureGeometry.clear()"))
        val recorder = SourceContract.read("host/SurfaceCaptureApi31.kt")
            .after("fun record(").before("fun draw(")
        assertTrue(recorder.indexOf("source.drawContent(canvas)") < recorder.indexOf("recordedWidth = width"))
        assertTrue(recorder.indexOf("recordedWidth = width") < recorder.indexOf("return true"))
    }
}
