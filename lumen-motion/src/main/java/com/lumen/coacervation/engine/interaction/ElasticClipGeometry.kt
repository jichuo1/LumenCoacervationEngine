package com.lumen.coacervation.engine.interaction

/** Fits the final drawn edges, including pivot stretch, inside retained clips. */
internal object ElasticClipGeometry {
    const val CROP_LEFT = 1
    const val CROP_TOP = 2
    const val CROP_RIGHT = 4
    const val CROP_BOTTOM = 8

    /** Both rectangles are in page coordinates, before rounding the owned source crop. */
    fun pageCropEdges(left: Float, top: Float, right: Float, bottom: Float,
                      visibleLeft: Float, visibleTop: Float, visibleRight: Float, visibleBottom: Float,
                      pageWidth: Float, pageHeight: Float): Int {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
            !visibleLeft.isFinite() || !visibleTop.isFinite() || !visibleRight.isFinite() ||
            !visibleBottom.isFinite() || !pageWidth.isFinite() || !pageHeight.isFinite() ||
            right <= left || bottom <= top || pageWidth <= 0f || pageHeight <= 0f ||
            visibleRight <= visibleLeft || visibleBottom <= visibleTop ||
            visibleLeft < maxOf(left, 0f) || visibleTop < maxOf(top, 0f) ||
            visibleRight > minOf(right, pageWidth) || visibleBottom > minOf(bottom, pageHeight)) return 0
        var edges = 0
        if (left < 0f && visibleLeft == 0f) edges = edges or CROP_LEFT
        if (top < 0f && visibleTop == 0f) edges = edges or CROP_TOP
        if (right > pageWidth && visibleRight == pageWidth) edges = edges or CROP_RIGHT
        if (bottom > pageHeight && visibleBottom == pageHeight) edges = edges or CROP_BOTTOM
        return edges
    }

    /** The fixed page already clips this side. A tighter outer crop remains authoritative. */
    fun retainedGap(gap: Float, pageCropped: Boolean): Float =
        if (pageCropped && gap >= 0f) Float.POSITIVE_INFINITY else gap

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
        var minimum = (-leading.toDouble() - pivot * (1.0 - fittedScale)).toFloat()
        var maximum = (trailing.toDouble() - (size.toDouble() - pivot) * (fittedScale - 1.0)).toFloat()
        if (translation >= 0f && maximum < 0f || translation <= 0f && minimum > 0f) {
            // Stretch cannot force the control against the finger, or move it with zero input.
            // Only the offending extra scale yields; roomy frames keep their original response.
            val delta = fittedScale.toDouble() - 1.0
            var fraction = 1.0
            val leadingGrowth = pivot * delta
            val trailingGrowth = (size.toDouble() - pivot) * delta
            if (translation <= 0f && leadingGrowth > 0.0) fraction = minOf(fraction, leading / leadingGrowth)
            if (translation >= 0f && trailingGrowth > 0.0) fraction = minOf(fraction, trailing / trailingGrowth)
            fittedScale = (1.0 + delta * fraction).toFloat()
            minimum = (-leading.toDouble() - pivot * (1.0 - fittedScale)).toFloat()
            maximum = (trailing.toDouble() - (size.toDouble() - pivot) * (fittedScale - 1.0)).toFloat()
            if (translation >= 0f && maximum < 0f || translation <= 0f && minimum > 0f) {
                fittedScale = Math.nextAfter(fittedScale, 1.0)
                minimum = (-leading.toDouble() - pivot * (1.0 - fittedScale)).toFloat()
                maximum = (trailing.toDouble() - (size.toDouble() - pivot) * (fittedScale - 1.0)).toFloat()
            }
        }
        if (minimum.isNaN() || maximum.isNaN() || minimum > maximum) return
        val fittedTranslation = translation.coerceIn(minimum, maximum)
        if (!fittedTranslation.isFinite()) return
        out.x = fittedTranslation
        out.y = fittedScale
    }
}
