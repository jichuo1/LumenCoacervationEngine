package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.*
import org.junit.Test

/** Platform pixels and lifecycle are exercised by sample's ViewportContentSourceTest. */
class ViewportContentSourceContractTest {
    private val source = SourceContract.read("host/LumenViewportContentSource.kt")

    @Test fun proxyVisibilityAndProbesUseTheOwnerWithoutHardwareReentry() {
        assertTrue(source.contains("override fun isShown(): Boolean = !closed && owner.isShown"))
        val probe = source.after("override fun onDraw(canvas: Canvas)").before("}.apply")
        assertTrue(probe.contains("if (!canvas.isHardwareAccelerated) draw(canvas, foregroundOnly = true)"))
        assertTrue(source.contains("if (foregroundOnly) LocalViewDrawing.draw(content, canvas) else source.drawContent(canvas)"))
        assertTrue(source.contains("private val mapping = ViewSamplingMatrix()"))
        val proxyMatrix = source.after("override fun getMatrix(): Matrix").before("override fun onDraw")
        assertTrue(proxyMatrix.contains("proxyMapping.sourceToTarget(owner, overlay, proxyMatrix)"))
        assertTrue(proxyMatrix.contains("proxyMatrix.preTranslate(left.toFloat(), top.toFloat())"))
        assertTrue(proxyMatrix.contains("proxyMatrix.postTranslate(-left.toFloat(), -top.toFloat())"))
    }

    @Test fun sourceGraphAndInvalidationPolicyRemainExplicit() {
        assertTrue(source.contains("override val dependencies: List<LumenContentSource> = listOf(source)"))
        assertTrue(source.contains("override val sourceEpoch: Long get() = versioned?.sourceEpoch ?: 0L"))
        assertTrue(source.contains("override val observeCoordinateViewInvalidations: Boolean get() = false"))
        assertTrue(source.contains("if (!source.excludesSurfaces && isWithin(view, content)) unsafe = true"))
        assertTrue(source.contains("if (versioned == null || !source.excludesSurfaces)"))
        assertFalse(source.contains("postInvalidateOnAnimation"))
        assertFalse(source.contains("OnPreDrawListener"))
    }

    @Test fun closeCannotReattachAndDrawingAlwaysRestoresCanvasAndGuard() {
        assertTrue(source.after("public fun sync(): Boolean").before("bounds.clear()").contains("if (closed) return false"))
        val close = source.after("override fun close()").before("private fun isWithin")
        assertTrue(close.contains("if (closed) return"))
        assertTrue(close.indexOf("closed = true") < close.indexOf(".overlay.remove(it)"))
        val draw = source.after("private fun draw(canvas: Canvas, foregroundOnly: Boolean)").before("override fun close()")
        assertTrue(draw.after("} finally {").contains("canvas.restoreToCount(saved)"))
        assertTrue(draw.after("} finally {").contains("drawing = false"))
    }
}
