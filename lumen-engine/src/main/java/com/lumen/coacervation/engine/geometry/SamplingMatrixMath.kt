package com.lumen.coacervation.engine.geometry

import kotlin.math.abs
import kotlin.math.roundToInt

/** Row-major 3x3 matrices in Android Matrix order, using column vectors. Outputs may alias inputs. */
internal object SamplingMatrixMath {
    fun identity(out: FloatArray) {
        out.fill(0f, 0, 9)
        out[0] = 1f; out[4] = 1f; out[8] = 1f
    }

    fun isFinite(matrix: FloatArray): Boolean {
        for (index in 0..8) if (!matrix[index].isFinite()) return false
        return true
    }

    fun equal(first: FloatArray, second: FloatArray): Boolean {
        for (index in 0..8) if (first[index] != second[index]) return false
        return true
    }

    fun multiply(left: FloatArray, right: FloatArray, out: FloatArray) {
        val a = left[0] * right[0] + left[1] * right[3] + left[2] * right[6]
        val b = left[0] * right[1] + left[1] * right[4] + left[2] * right[7]
        val c = left[0] * right[2] + left[1] * right[5] + left[2] * right[8]
        val d = left[3] * right[0] + left[4] * right[3] + left[5] * right[6]
        val e = left[3] * right[1] + left[4] * right[4] + left[5] * right[7]
        val f = left[3] * right[2] + left[4] * right[5] + left[5] * right[8]
        val g = left[6] * right[0] + left[7] * right[3] + left[8] * right[6]
        val h = left[6] * right[1] + left[7] * right[4] + left[8] * right[7]
        val i = left[6] * right[2] + left[7] * right[5] + left[8] * right[8]
        out[0] = a; out[1] = b; out[2] = c
        out[3] = d; out[4] = e; out[5] = f
        out[6] = g; out[7] = h; out[8] = i
    }

    /** T(dx,dy) * matrix: translate the output space, including perspective matrices. */
    fun translateAfter(matrix: FloatArray, dx: Float, dy: Float) {
        matrix[0] += dx * matrix[6]; matrix[1] += dx * matrix[7]; matrix[2] += dx * matrix[8]
        matrix[3] += dy * matrix[6]; matrix[4] += dy * matrix[7]; matrix[5] += dy * matrix[8]
    }

    fun invert(matrix: FloatArray, out: FloatArray): Boolean {
        if (!isFinite(matrix)) return false
        val a = matrix[0].toDouble(); val b = matrix[1].toDouble(); val c = matrix[2].toDouble()
        val d = matrix[3].toDouble(); val e = matrix[4].toDouble(); val f = matrix[5].toDouble()
        val g = matrix[6].toDouble(); val h = matrix[7].toDouble(); val i = matrix[8].toDouble()
        val aa = e * i - f * h; val bb = c * h - b * i; val cc = b * f - c * e
        val dd = f * g - d * i; val ee = a * i - c * g; val ff = c * d - a * f
        val gg = d * h - e * g; val hh = b * g - a * h; val ii = a * e - b * d
        val determinant = a * aa + b * dd + c * gg
        if (!determinant.isFinite() || abs(determinant) < 1e-12) return false
        val factor = 1.0 / determinant
        val r0 = (aa * factor).toFloat(); val r1 = (bb * factor).toFloat(); val r2 = (cc * factor).toFloat()
        val r3 = (dd * factor).toFloat(); val r4 = (ee * factor).toFloat(); val r5 = (ff * factor).toFloat()
        val r6 = (gg * factor).toFloat(); val r7 = (hh * factor).toFloat(); val r8 = (ii * factor).toFloat()
        if (!r0.isFinite() || !r1.isFinite() || !r2.isFinite() || !r3.isFinite() || !r4.isFinite() ||
            !r5.isFinite() || !r6.isFinite() || !r7.isFinite() || !r8.isFinite()) return false
        out[0] = r0; out[1] = r1; out[2] = r2
        out[3] = r3; out[4] = r4; out[5] = r5
        out[6] = r6; out[7] = r7; out[8] = r8
        return true
    }

    /** inverse(targetGlobal) * sourceGlobal * bitmapToRoot, the BitmapShader local matrix. */
    fun bitmapToTarget(
        sourceGlobal: FloatArray, targetGlobal: FloatArray,
        bitmapScaleX: Float, bitmapScaleY: Float,
        out: FloatArray, inverseScratch: FloatArray
    ): Boolean {
        if (!isFinite(sourceGlobal) || !bitmapScaleX.isFinite() || !bitmapScaleY.isFinite() ||
            bitmapScaleX <= 0f || bitmapScaleY <= 0f || !invert(targetGlobal, inverseScratch)) return false
        multiply(inverseScratch, sourceGlobal, out)
        out[0] *= bitmapScaleX; out[3] *= bitmapScaleX; out[6] *= bitmapScaleX
        out[1] *= bitmapScaleY; out[4] *= bitmapScaleY; out[7] *= bitmapScaleY
        return isFinite(out)
    }

    /**
     * getLocationOnScreen rounds the root's transformed origin, then adds integer window offsets.
     * Subtract that same rounded origin, rather than the fractional origin, so pivots retain subpixels.
     * This also includes ViewRootImpl's integer pan/scroll without accessing hidden APIs.
     */
    fun alignRootToScreen(
        localToRootParent: FloatArray, rootToRootParent: FloatArray, screenX: Int, screenY: Int
    ): Boolean {
        val w = rootToRootParent[8]
        if (!isFinite(rootToRootParent) || abs(w) < 1e-12f) return false
        val rootX = rootToRootParent[2] / w
        val rootY = rootToRootParent[5] / w
        if (!rootX.isFinite() || !rootY.isFinite()) return false
        translateAfter(localToRootParent,
            (screenX.toLong() - rootX.roundToInt()).toFloat(),
            (screenY.toLong() - rootY.roundToInt()).toFloat())
        return isFinite(localToRootParent)
    }
}
