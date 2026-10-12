package com.lumen.coacervation.engine.host

import android.view.View
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.geometry.ViewSamplingMatrix
import java.lang.ref.WeakReference

/**
 * A surface's geometry and invalidation boundary. Several surfaces may share [coordinateView],
 * which identifies their window only; their size and complete transform remain independent.
 */
@MainThread
interface LumenSurfaceTarget {
    val coordinateView: View
    val width: Int
    val height: Int
    val isAttachedToWindow: Boolean
    val isShown: Boolean
    val alpha: Float
    val layoutDirection: Int

    /** Writes a finite local-to-screen 3x3 matrix in Android Matrix order into a caller-owned buffer. */
    fun localToScreen(out: FloatArray): Boolean
    fun invalidate()
}

/** A retained registration must not keep an otherwise released View alive. */
internal class ViewSurfaceTarget(
    view: View,
    private val matrices: ViewSamplingMatrix
) : LumenSurfaceTarget {
    private val reference = WeakReference(view)
    override val coordinateView: View
        get() = reference.get() ?: error("Surface View has been released")
    override val width: Int get() = reference.get()?.width ?: 0
    override val height: Int get() = reference.get()?.height ?: 0
    override val isAttachedToWindow: Boolean get() = reference.get()?.isAttachedToWindow == true
    override val isShown: Boolean get() = reference.get()?.isShown == true
    override val alpha: Float get() = reference.get()?.alpha ?: 0f
    override val layoutDirection: Int get() = reference.get()?.layoutDirection ?: View.LAYOUT_DIRECTION_LTR

    override fun localToScreen(out: FloatArray): Boolean {
        val view = reference.get() ?: return false
        return matrices.localToScreen(view, out)
    }

    override fun invalidate() {
        val view = reference.get() ?: return
        view.background?.invalidateSelf()
        view.invalidate()
    }
}
