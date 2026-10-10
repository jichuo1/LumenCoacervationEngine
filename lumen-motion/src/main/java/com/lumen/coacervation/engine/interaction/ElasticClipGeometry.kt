package com.lumen.coacervation.engine.interaction

/** Fits the final drawn edges, including pivot stretch, inside retained clips. */
internal object ElasticClipGeometry {
    fun roundedContains(left: Float, top: Float, right: Float, bottom: Float,
                        clipLeft: Float, clipTop: Float, clipRight: Float, clipBottom: Float,
                        radius: Float): Boolean {
        if (left < clipLeft || top < clipTop || right > clipRight || bottom > clipBottom) return false
        val r = radius.coerceIn(0f, minOf(clipRight - clipLeft, clipBottom - clipTop) / 2).toDouble()
        fun inside(x: Float, y: Float): Boolean {
            val centerX = x.toDouble().coerceIn(clipLeft + r, clipRight - r)
            val centerY = y.toDouble().coerceIn(clipTop + r, clipBottom - r)
            val dx = x - centerX
            val dy = y - centerY
            return dx * dx + dy * dy <= r * r
        }
        return inside(left, top) && inside(right, top) && inside(left, bottom) && inside(right, bottom)
    }

    /** Convex rounded clips allow a continuous blend from the baseline to the requested frame. */
    fun roundedFraction(tx: Float, ty: Float, sx: Float, sy: Float, width: Float, height: Float,
                        pivotX: Float, pivotY: Float, clips: FloatArray, count: Int): Float {
        fun contains(fraction: Float): Boolean {
            val scaleX = 1f + (sx - 1f) * fraction
            val scaleY = 1f + (sy - 1f) * fraction
            val left = tx * fraction + pivotX * (1f - scaleX)
            val top = ty * fraction + pivotY * (1f - scaleY)
            val right = left + width * scaleX
            val bottom = top + height * scaleY
            for (index in 0 until count) {
                val offset = index * 5
                if (!roundedContains(left, top, right, bottom, clips[offset], clips[offset + 1],
                        clips[offset + 2], clips[offset + 3], clips[offset + 4])) return false
            }
            return true
        }
        if (count == 0 || contains(1f)) return 1f
        if (!contains(0f)) return 0f // preserve a pre-existing crop rather than moving it into view
        var low = 0f
        var high = 1f
        repeat(12) {
            val middle = (low + high) / 2
            if (contains(middle)) low = middle else high = middle
        }
        return low
    }

    /** [out.x] is translation relative to the baseline; [out.y] is scale. */
    fun fitAxis(translation: Float, scale: Float, size: Float, pivot: Float,
                leadingGap: Float, trailingGap: Float, out: ElasticVector) {
        out.x = 0f
        out.y = 1f
        if (!translation.isFinite() || !scale.isFinite() || scale <= 0f ||
            !size.isFinite() || size <= 0f || !pivot.isFinite() ||
            leadingGap.isNaN() || trailingGap.isNaN() ||
            leadingGap == Float.NEGATIVE_INFINITY || trailingGap == Float.NEGATIVE_INFINITY) return
        // A partially visible row keeps its original scroll clipping. Do not
        // translate it into view or create additional overflow beyond baseline.
        val leading = leadingGap.coerceAtLeast(0f)
        val trailing = trailingGap.coerceAtLeast(0f)
        val maximumScale = 1.0 + (leading.toDouble() + trailing) / size
        var fittedScale = minOf(scale.toDouble(), maximumScale).toFloat()
        // Float rounding must not put the two permissible edges in reverse order.
        if (fittedScale.toDouble() > maximumScale) fittedScale = Math.nextDown(fittedScale)
        val minimum = (-leading.toDouble() - pivot * (1.0 - fittedScale)).toFloat()
        val maximum = (trailing.toDouble() - (size.toDouble() - pivot) * (fittedScale - 1.0)).toFloat()
        if (minimum.isNaN() || maximum.isNaN() || minimum > maximum) return
        val fittedTranslation = translation.coerceIn(minimum, maximum)
        if (!fittedTranslation.isFinite()) return
        out.x = fittedTranslation
        out.y = fittedScale
    }
}
