package com.lumen.coacervation.engine.liquid

import android.graphics.Matrix
import android.graphics.Rect
import android.view.View
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.geometry.ViewSamplingMatrix

/** Window-relative native stretch coordinates shared by engine and host renderer adapters. */
@MainThread
public class LiquidStretchSampling {
    private val coordinates = ViewSamplingMatrix()
    private val viewportToBackdrop = Matrix()
    private val values = FloatArray(9)
    private val opticalSegment = FloatArray(2)

    public fun resolve(host: View?, backdropRoot: View?, out: FloatArray) {
        require(out.size >= 3)
        out.fill(0f)
        var ancestor = host?.parent as? View
        while (ancestor != null && ancestor !is LiquidStretchViewport) ancestor = ancestor.parent as? View
        if (backdropRoot == null || ancestor !is LiquidStretchViewport || ancestor.samplingOverscroll == 0f ||
            !coordinates.sourceToTarget(ancestor, backdropRoot, viewportToBackdrop)) return
        viewportToBackdrop.getValues(values)
        if (values[1] != 0f || values[3] != 0f || values[6] != 0f || values[7] != 0f || values[4] <= 0f) return
        out[0] = values[5]
        out[1] = ancestor.height * values[4]
        out[2] = ancestor.samplingOverscroll
    }

    /** Bitmap backends use the secant over this surface; AGSL uses the exact per-pixel mapping. */
    public fun opticalTransform(bounds: Rect, localToBackdrop: Matrix?, rootX: Float, rootY: Float,
                                sampling: FloatArray?, out: Matrix) {
        if (localToBackdrop != null) out.set(localToBackdrop) else out.setTranslate(rootX, rootY)
        if (sampling == null || sampling.size < 3 || sampling[1] <= 0f || sampling[2] == 0f) return
        out.getValues(values)
        if (values[6] != 0f || values[7] != 0f || values[8] != 1f) return
        val x1 = values[3] * bounds.left
        val x2 = values[3] * bounds.right
        val y1 = values[4] * bounds.top
        val y2 = values[4] * bounds.bottom
        val top = minOf(x1, x2) + minOf(y1, y2) + values[5]
        val bottom = maxOf(x1, x2) + maxOf(y1, y2) + values[5]
        if (!LiquidStretchSamplingPolicy.opticalSegment(top, bottom, sampling[0], sampling[1], sampling[2], opticalSegment)) return
        out.postScale(1f, opticalSegment[0])
        out.postTranslate(0f, opticalSegment[1])
    }
}
