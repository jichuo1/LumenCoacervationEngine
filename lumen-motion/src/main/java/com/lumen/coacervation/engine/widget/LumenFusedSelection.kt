package com.lumen.coacervation.engine.widget

import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.host.*
import com.lumen.coacervation.engine.model.LumenPalette

/** Decoration for the real sliding selector. Hit targets/selection semantics remain on the original options. */
@MainThread
public class LumenFusedSelection(
    private val selection: LumenSlidingSelection,palette: LumenPalette,
    surface: LumenSurfaceOptions=LumenSurfaceOptions(),
    enhancements: LumenSurfaceEnhancements=LumenSurfaceEnhancements(geometry=LumenSurfaceGeometryOptions(fusionEnabled=true)),
    sharedNodeBaseline: Boolean=false
) : AutoCloseable,View.OnLayoutChangeListener,LumenSlidingSelection.OnGeometryListener {
    private val original=selection.indicator.background
    private val originalListener=selection.getOnGeometryListener()
    private val transparent=ColorDrawable(0)
    private val overlay=View(selection.context).apply {isClickable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private val session=LumenSurfaceSession(selection.context,palette)
    private val source=object:LumenContentSource {
        override val coordinateView: View get()=selection
        override val excludesSurfaces: Boolean get()=true
        override fun drawContent(canvas: Canvas) {
            // The options layer is independent of both the legacy indicator and our overlay.
            val saved=canvas.save();canvas.translate(selection.options.left.toFloat(),selection.options.top.toFloat())
            try {selection.options.draw(canvas)}finally {canvas.restoreToCount(saved)}
        }
    }
    private var surface=surface
    private var enhancements=enhancements
    private var baseline=sharedNodeBaseline
    private var binding: LumenSurfaceBinding
    private var closed=false
    private var halo=FusionDecorationBounds.haloDp(enhancements.geometry)*selection.resources.displayMetrics.density
    init {
        selection.addView(overlay,0,FrameLayout.LayoutParams(0,0))
        selection.indicator.background=transparent
        binding=bindEffect()
        selection.addOnLayoutChangeListener(this);selection.setOnGeometryListener(this);layoutOverlay()
    }
    public fun update(surface: LumenSurfaceOptions,enhancements: LumenSurfaceEnhancements) {
        if(closed)return
        this.surface=surface;this.enhancements=enhancements
        binding.update(surface);if(!baseline)binding.updateEnhancements(enhancements)
        halo=FusionDecorationBounds.haloDp(enhancements.geometry)*selection.resources.displayMetrics.density
        layoutOverlay()
        // Rebase the original geometry into the new overlay origin without taking back a host-owned listener.
        if(selection.getOnGeometryListener()===this)selection.setOnGeometryListener(this)
    }
    /** Explicit A/B: the original moving indicator uses the existing shared content-node/software path. */
    public fun setSharedNodeBaseline(enabled:Boolean){
        if(closed||baseline==enabled)return
        binding.close();baseline=enabled;binding=bindEffect();layoutOverlay()
        if(selection.getOnGeometryListener()===this)selection.setOnGeometryListener(this)
    }
    private fun bindEffect(): LumenSurfaceBinding {
        overlay.visibility=if(baseline)View.INVISIBLE else View.VISIBLE
        return if(baseline)session.bindSource(selection.indicator,surface,source)else session.bindSource(overlay,surface,source,enhancements)
    }
    public fun updatePalette(palette: LumenPalette){if(!closed)session.updatePalette(palette)}
    public fun diagnostics(): LumenSurfaceState?=if(closed)null else binding.diagnostics()
    public fun pause(){if(!closed)session.pause()}
    public fun resume(){if(!closed)session.resume()}
    private fun layoutOverlay(){
        val margin=kotlin.math.ceil(halo).toInt()
        overlay.layout(-margin,-margin,selection.width+margin,selection.height+margin)
        session.notifyPositionChanged()
    }
    override fun onLayoutChange(v: View,l: Int,t: Int,r: Int,b: Int,oldL: Int,oldT: Int,oldR: Int,oldB: Int){if(!closed)layoutOverlay()}
    override fun onGeometry(left: Float,top: Float,right: Float,bottom: Float,targetLeft: Float,targetTop: Float,targetRight: Float,targetBottom: Float,moving: Boolean) {
        if(closed||right<=left||bottom<=top)return
        if(baseline){session.notifyPositionChanged();originalListener?.onGeometry(left,top,right,bottom,targetLeft,targetTop,targetRight,targetBottom,moving);return}
        val margin=kotlin.math.ceil(halo).toFloat()
        // The same original motion provides both current and destination geometry; no second animator.
        binding.setShapesPixels(left+margin,top+margin,right-left,bottom-top,targetLeft+margin,targetTop+margin,
            (targetRight-targetLeft).coerceAtLeast(0f),(targetBottom-targetTop).coerceAtLeast(0f),moving)
        originalListener?.onGeometry(left,top,right,bottom,targetLeft,targetTop,targetRight,targetBottom,moving)
    }
    override fun close(){
        if(closed)return
        closed=true;selection.removeOnLayoutChangeListener(this)
        if(selection.getOnGeometryListener()===this)selection.setOnGeometryListener(originalListener)
        binding.close();session.close();selection.removeView(overlay)
        if(selection.indicator.background===transparent)selection.indicator.background=original
    }
}
