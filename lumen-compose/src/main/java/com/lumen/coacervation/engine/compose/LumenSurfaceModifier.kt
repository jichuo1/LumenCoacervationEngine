package com.lumen.coacervation.engine.compose

import android.graphics.Picture
import android.view.View
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import com.lumen.coacervation.engine.host.*

/** Put after lumenElastic so material and content use the same complete transform. */
public fun Modifier.lumenSurface(state: LumenComposeState, options: LumenSurfaceOptions = LumenSurfaceOptions(),
    enhancements: LumenSurfaceEnhancements = LumenSurfaceEnhancements.DEFAULT, key: Any? = null
): Modifier = then(SurfaceElement(state, options, enhancements, key))

private data class SurfaceElement(val state: LumenComposeState, val options: LumenSurfaceOptions,
    val enhancements: LumenSurfaceEnhancements, val key: Any?
) : ModifierNodeElement<LumenSurfaceNode>() {
    override fun create() = LumenSurfaceNode(state, options, enhancements, key)
    override fun update(node: LumenSurfaceNode) = node.update(state, options, enhancements, key)
    override fun InspectorInfo.inspectableProperties() { name = "lumenSurface"; properties["key"] = key }
}

internal class LumenSurfaceNode(private var owner: LumenComposeState, private var options: LumenSurfaceOptions,
    private var enhancements: LumenSurfaceEnhancements, private var key: Any?
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode, GlobalPositionAwareModifierNode, UnplacedAwareModifierNode, CompositionLocalConsumerModifierNode, LumenSurfaceTarget {
    private var binding: LumenSurfaceBinding? = null
    private var measured = IntSize.Zero
    private val matrix = androidx.compose.ui.graphics.Matrix()
    private val current = FloatArray(9)
    private val last = FloatArray(9)
    private var hasGeometry = false
    private var placed = false
    private var anchor: View? = null
    override val coordinateView get() = anchor ?: owner.view
    override val width get() = measured.width
    override val height get() = measured.height
    override val isAttachedToWindow get() = isAttached && coordinateView.isAttachedToWindow
    override val isShown get() = placed && isAttachedToWindow && coordinateView.isShown && width > 0 && height > 0
    override val alpha = 1f
    override val layoutDirection get() = if (isAttached && requireLayoutDirection() == LayoutDirection.Rtl)
        View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

    override fun onDetach() { onUnplaced(); measured = IntSize.Zero; anchor = null }
    override fun onReset() = onUnplaced()
    override fun onUnplaced() { owner.unregister(this); binding = null; hasGeometry = false; placed = false }
    private fun bind() {
        binding = owner.session.bindTarget(this, options, owner.source, enhancements)
        owner.register(this, key, binding!!)
    }
    fun update(state: LumenComposeState, value: LumenSurfaceOptions, effects: LumenSurfaceEnhancements, newKey: Any?) {
        val rebind = owner !== state || key != newKey
        if (rebind) { owner.unregister(this); binding = null; hasGeometry = false }
        owner = state; options = value; enhancements = effects; key = newKey
        if (!isAttached || !placed) return
        if (binding?.isBound == true) { binding?.update(value); binding?.updateEnhancements(effects) }
        invalidateDraw()
    }
    override fun onRemeasured(size: IntSize) {
        if (measured != size) { measured = size; hasGeometry = false; binding?.notifyPositionChanged() }
    }
    override fun onPlaced(coordinates: LayoutCoordinates) {
        val currentView = currentValueOf(LocalView)
        if (anchor != null && anchor !== currentView) { owner.unregister(this); binding = null; hasGeometry = false }
        anchor = currentView
        placed = true
        refreshGeometry()
    }
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) = refreshGeometry()
    override fun localToScreen(out: FloatArray): Boolean {
        if (!placed || !isAttachedToWindow || width <= 0 || height <= 0) return false
        val coordinates = requireLayoutCoordinates()
        if (!coordinates.isAttached) return false
        matrix.reset(); coordinates.transformToScreen(matrix); matrix.toAndroidValues(out)
        for (i in 0..8) if (!out[i].isFinite()) return false
        return true
    }
    fun refreshGeometry() {
        if (!localToScreen(current)) return
        var changed = !hasGeometry
        for (i in 0..8) if (current[i] != last[i]) changed = true
        if (changed) { current.copyInto(last); hasGeometry = true; binding?.notifyPositionChanged() }
    }
    override fun invalidate() { if (isAttached) invalidateDraw() }
    override fun ContentDrawScope.draw() {
        if (owner.source.recording) {
            owner.unregister(this@LumenSurfaceNode); binding = null
            drawContent(); return
        }
        // Lazy placement can insert new rows before unplacing old ones. Claim budgets after layout.
        if (owner.isOpen && placed && binding?.isBound != true) bind()
        binding?.draw(drawContext.canvas.nativeCanvas)
        drawContent()
    }
}

internal data class BackdropElement(val source: ComposeBackdropSource) : ModifierNodeElement<BackdropNode>() {
    override fun create() = BackdropNode(source)
    override fun update(node: BackdropNode) = node.update(source)
    override fun InspectorInfo.inspectableProperties() { name = "lumenBackdrop" }
}

internal class BackdropNode(private var source: ComposeBackdropSource) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {
    private val picture = Picture()
    private var coordinates: LayoutCoordinates? = null
    fun update(value: ComposeBackdropSource) {
        if (source !== value) {
            if (isAttached) source.detach(this)
            source = value
            if (isAttached) source.attach(this)
            invalidateDraw()
        }
    }
    override fun onAttach() { source.attach(this) }
    override fun onPlaced(coordinates: LayoutCoordinates) { this.coordinates = coordinates; source.positionChanged(coordinates) }
    override fun onDetach() { coordinates = null; source.detach(this) }
    override fun ContentDrawScope.draw() {
        val coordinates = coordinates ?: return
        val original = drawContext.canvas
        val wasRecording = source.recording
        val recording = picture.beginRecording(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
        try {
            source.recording = true
            drawContext.canvas = Canvas(recording)
            drawContent()
        } finally { drawContext.canvas = original; source.recording = wasRecording; picture.endRecording() }
        source.publish(picture, coordinates)
        original.nativeCanvas.drawPicture(picture)
    }
}
