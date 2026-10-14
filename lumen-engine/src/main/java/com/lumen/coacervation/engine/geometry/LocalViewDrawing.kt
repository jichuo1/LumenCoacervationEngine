package com.lumen.coacervation.engine.geometry

import android.graphics.Canvas
import android.view.View

/**
 * Records a View in its own local space, the way its parent's display list does.
 *
 * View.draw(Canvas) expects the canvas to be translated by the view's own scroll already: the platform
 * applies `translate(-scrollX, -scrollY)` in updateDisplayListIfDirty before calling draw(). A bare draw()
 * of a scrolled container therefore records its content at scroll position 0, while every sampling matrix
 * ([ViewSamplingMatrix.localToScreen]) maps the view's local space. Unscrolled views keep the plain draw().
 * Allocation-free; safe on per-sample recording paths.
 */
internal object LocalViewDrawing {
    fun draw(view: View, canvas: Canvas) {
        val x = view.scrollX
        val y = view.scrollY
        if (x == 0 && y == 0) {
            view.draw(canvas)
            return
        }
        val saved = canvas.save()
        try {
            canvas.translate(-x.toFloat(), -y.toFloat())
            view.draw(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
    }
}
