package com.lumen.coacervation.engine.compose

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Picture
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalView
import com.lumen.coacervation.engine.host.*
import com.lumen.coacervation.engine.model.LumenPalette
import java.util.IdentityHashMap
import androidx.annotation.MainThread

/** One owner per Compose host. It never creates a View for an individual surface. */
@MainThread
public class LumenComposeState internal constructor(internal val view: View, palette: LumenPalette,
    options: LumenSurfaceSessionOptions
) : AutoCloseable, ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    public val session = LumenSurfaceSession(view.context, palette, options)
    internal val source = ComposeBackdropSource(view) { session.notifyContentChanged() }
    private class Registration(val key: Any?, val binding: LumenSurfaceBinding)
    private val targets = IdentityHashMap<LumenSurfaceNode, Registration>()
    private var observer: ViewTreeObserver? = null
    private var closed = false
    internal val isOpen: Boolean get() = !closed && !session.isClosed
    private var bootstrapPosted = false
    private val bootstrap = Runnable {
        bootstrapPosted = false
        if (!closed) for (node in targets.keys) node.invalidate()
    }

    init { view.addOnAttachStateChangeListener(this); observe() }

    /** Diagnostic lookup; keys do not determine the identity of the render target. */
    public fun bindingFor(key: Any): LumenSurfaceBinding? = targets.values.firstOrNull { it.key == key }?.binding

    internal fun register(node: LumenSurfaceNode, key: Any?, binding: LumenSurfaceBinding) {
        if (!isOpen) { binding.close(); return }
        targets.put(node, Registration(key, binding))?.binding?.close()
        // A binding first created in draw missed pre-draw and cannot invalidate its in-flight layer.
        if (!bootstrapPosted) { bootstrapPosted = true; view.postOnAnimation(bootstrap) }
    }
    internal fun unregister(node: LumenSurfaceNode) { targets.remove(node)?.binding?.close() }

    private fun observe() {
        val current = view.viewTreeObserver
        if (observer === current) return
        unobserve(); observer = current
        current.addOnPreDrawListener(this)
    }
    private fun unobserve() { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null }
    override fun onPreDraw(): Boolean {
        if (isOpen && view.isAttachedToWindow && view.isShown) {
            // Layer transforms can change without a layout callback. Refresh before recording/drawing.
            source.refreshGeometry()
            for (node in targets.keys) node.refreshGeometry()
        }
        return true
    }
    override fun onViewAttachedToWindow(v: View) { if (!closed) { observe(); session.resume() } }
    override fun onViewDetachedFromWindow(v: View) { unobserve(); session.pause() }
    override fun close() {
        if (closed) return
        closed = true
        view.removeCallbacks(bootstrap); bootstrapPosted = false
        unobserve(); view.removeOnAttachStateChangeListener(this)
        for (node in targets.keys) node.invalidate()
        targets.clear(); session.close(); source.release()
    }
}

@Composable
public fun rememberLumenComposeState(palette: LumenPalette,
    options: LumenSurfaceSessionOptions = LumenSurfaceSessionOptions()
): LumenComposeState {
    val view = LocalView.current
    val state = remember(view) { LumenComposeState(view, palette, options) }
    SideEffect { state.session.updatePalette(palette); state.session.updateOptions(options) }
    DisposableEffect(state) { onDispose { state.close() } }
    return state
}

/** Background and foreground remain siblings in the caller's Composition, including in a Dialog. */
@Composable
public fun LumenComposeHost(state: LumenComposeState, modifier: Modifier = Modifier,
    backdrop: @Composable BoxScope.() -> Unit, content: @Composable BoxScope.() -> Unit
) {
    require(state.view === LocalView.current) { "Create LumenComposeState inside this host's Composition" }
    Box(modifier) {
        Box(Modifier.matchParentSize().graphicsLayer().then(BackdropElement(state.source)), content = backdrop)
        content()
    }
}

internal class ComposeBackdropSource(override val coordinateView: View, private val changed: () -> Unit) : LumenVersionedContentSource {
    var recording = false
    private var owner: Any? = null
    fun attach(token: Any) {
        check(owner == null || owner === token) { "Each LumenComposeHost needs its own LumenComposeState" }
        owner = token
    }
    fun detach(token: Any) { if (owner === token) { owner = null; release() } }
    override val excludesSurfaces = true
    override val observeCoordinateViewInvalidations = false
    override var sourceEpoch = 0L
        private set
    override var contentVersion = 0L
        private set
    private var picture: Picture? = null
    private var coordinates: LayoutCoordinates? = null
    private val composeMatrix = androidx.compose.ui.graphics.Matrix()
    private val androidMatrix = Matrix()
    private val values = FloatArray(9)
    private val last = FloatArray(9)
    private var hasGeometry = false
    override val availability: LumenSourceAvailability
        get() = if (picture != null && coordinates?.isAttached == true && coordinateView.isAttachedToWindow)
            LumenSourceAvailability.READY else LumenSourceAvailability.TEMPORARILY_UNAVAILABLE

    fun publish(value: Picture, location: LayoutCoordinates) {
        picture = value; coordinates = location; contentVersion++
        changed()
    }
    fun positionChanged(location: LayoutCoordinates) { coordinates = location; contentVersion++; changed() }
    fun refreshGeometry() {
        val current = coordinates ?: return
        if (!current.isAttached || picture == null) return
        composeMatrix.reset()
        current.findRootCoordinates().transformFrom(current, composeMatrix)
        composeMatrix.toAndroidValues(values)
        var moved = !hasGeometry
        for (i in 0..8) if (last[i] != values[i]) moved = true
        if (moved) { values.copyInto(last); hasGeometry = true; contentVersion++; changed() }
    }
    override fun drawContent(canvas: Canvas) {
        val current = coordinates ?: return
        val recorded = picture ?: return
        if (!current.isAttached) return
        composeMatrix.reset()
        current.findRootCoordinates().transformFrom(current, composeMatrix)
        composeMatrix.toAndroidValues(values)
        androidMatrix.setValues(values)
        val save = canvas.save()
        try { canvas.concat(androidMatrix); canvas.drawPicture(recorded) }
        finally { canvas.restoreToCount(save) }
    }
    fun release() { picture = null; coordinates = null; hasGeometry = false; sourceEpoch++; contentVersion++ }
}

internal fun androidx.compose.ui.graphics.Matrix.toAndroidValues(out: FloatArray) {
    val v = values
    out[0] = v[0]; out[1] = v[4]; out[2] = v[12]
    out[3] = v[1]; out[4] = v[5]; out[5] = v[13]
    out[6] = v[3]; out[7] = v[7]; out[8] = v[15]
}
