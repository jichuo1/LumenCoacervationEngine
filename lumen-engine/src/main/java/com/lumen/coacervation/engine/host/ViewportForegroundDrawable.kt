package com.lumen.coacervation.engine.host

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.view.View

/** View compensates backgrounds for content scroll, but foreground decoration needs its own offset. */
internal class ViewportForegroundDrawable(drawable: Drawable) : DrawableWrapper(drawable) {
    override fun draw(canvas: Canvas) {
        var outer: Drawable = this
        var owner = callback
        var depth = 0
        while (owner is Drawable && depth++ < 16) {
            outer = owner
            owner = owner.callback
        }
        val view = owner as? View
        // A background is already compensated by View; only the actual foreground owns this space.
        val x = if (view?.foreground === outer) view.scrollX else 0
        val y = if (view?.foreground === outer) view.scrollY else 0
        if (x == 0 && y == 0) { super.draw(canvas); return }
        val saved = canvas.save()
        try {
            canvas.translate(x.toFloat(), y.toFloat())
            super.draw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }
}
