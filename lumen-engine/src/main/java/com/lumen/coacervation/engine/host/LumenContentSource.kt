package com.lumen.coacervation.engine.host

import android.graphics.Canvas
import android.view.View
import androidx.annotation.MainThread

/**
 * Existing content in its coordinate View's local space. Recording never reparents or hides Views.
 * A custom source may declare [excludesSurfaces] only if it genuinely omits the injected surfaces.
 */
public interface LumenContentSource {
    public val coordinateView: View
    public val excludesSurfaces: Boolean get() = false
    @MainThread public fun drawContent(canvas: Canvas)
}

internal class ViewContentSource(override val coordinateView: View) : LumenContentSource {
    override fun drawContent(canvas: Canvas) = coordinateView.draw(canvas)
}
