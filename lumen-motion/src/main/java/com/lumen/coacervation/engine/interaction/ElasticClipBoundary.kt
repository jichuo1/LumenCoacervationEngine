package com.lumen.coacervation.engine.interaction

import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.view.ScrollingView
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport

/** A custom viewport whose clipping and ancestor boundaries elastic motion must retain. */
public interface ElasticClipBoundary

internal enum class ElasticClipAction { RELIEVE, PANEL_PADDING, STOP }

internal object ElasticClipPolicy {
    fun action(windowRoot: Boolean, viewport: Boolean, panel: Boolean): ElasticClipAction = when {
        windowRoot || viewport -> ElasticClipAction.STOP
        panel -> ElasticClipAction.PANEL_PADDING
        else -> ElasticClipAction.RELIEVE
    }

    fun isViewport(view: View): Boolean = view is ScrollView || view is HorizontalScrollView ||
        view is ScrollingView || view is LiquidStretchViewport || view is ElasticClipBoundary
}
