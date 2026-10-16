package com.lumen.coacervation.engine.host

import android.graphics.Canvas
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import androidx.annotation.RequiresApi

/** Platform API isolation; this node is never replaced while cached View display lists reference it. */
@RequiresApi(29)
internal class DrawableSceneApi29(scale: Float) : RecordedDrawableScene(true, scale) {
    private val node = RenderNode("Lumen-drawable-scene").apply { setUseCompositingLayer(true, null) }
    private val inverseScale = 1f / scale

    override fun recordDrawable(drawable: Drawable, size: DrawableScenePolicy.Size) {
        try {
            node.setPosition(0, 0, size.width, size.height)
            val canvas = node.beginRecording(size.width, size.height)
            try {
                canvas.scale(size.scaleX, size.scaleY)
                drawable.draw(canvas)
            } finally { node.endRecording() }
        } catch (failure: Throwable) {
            // Cached View display lists also hold this node, so a partial record must be cleared.
            node.discardDisplayList()
            throw failure
        }
    }

    override fun drawRecorded(canvas: Canvas) {
        val saved = canvas.save()
        try {
            canvas.scale(inverseScale, inverseScale)
            canvas.drawRenderNode(node)
        } finally { canvas.restoreToCount(saved) }
    }

    override fun release() = node.discardDisplayList()
}
