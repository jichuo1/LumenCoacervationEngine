package com.lumen.coacervation.engine.liquid

/**
 * 反馈遮罩的复用几何缓冲。先在根坐标里外扩原圆角矩形，再映射到采样图。
 *
 * 不把矩形夹回 viewport：圆角矩形与屏幕的交集不等于“先截短矩形，再画同样的圆角”。
 * 全屏直角表面外扩后，四个圆心仍在屏幕角上；若先夹矩形，圆心会向内挪一个 padding，
 * 留下未抑制的角落，让上一帧的高光递归进入下一帧。实际裁切交给目标 Bitmap 的 Canvas。
 */
internal class LiquidSuppressionMaskGeometry {
    var left = 0f
        private set
    var top = 0f
        private set
    var right = 0f
        private set
    var bottom = 0f
        private set
    var radiusX = 0f
        private set
    var radiusY = 0f
        private set

    fun set(
        surfaceLeft: Float,
        surfaceTop: Float,
        surfaceRight: Float,
        surfaceBottom: Float,
        radiusPx: Float,
        paddingPx: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        scaleX: Float,
        scaleY: Float
    ): Boolean {
        if (surfaceRight <= surfaceLeft || surfaceBottom <= surfaceTop ||
            viewportWidth <= 0 || viewportHeight <= 0 || scaleX <= 0f || scaleY <= 0f) return false
        val padding = paddingPx.coerceAtLeast(0f)
        val expandedLeft = surfaceLeft - padding
        val expandedTop = surfaceTop - padding
        val expandedRight = surfaceRight + padding
        val expandedBottom = surfaceBottom + padding
        if (expandedRight <= 0f || expandedBottom <= 0f ||
            expandedLeft >= viewportWidth || expandedTop >= viewportHeight) return false
        val radius = radiusPx.coerceIn(
            0f, minOf(surfaceRight - surfaceLeft, surfaceBottom - surfaceTop) * 0.5f
        ) + padding
        left = expandedLeft * scaleX
        top = expandedTop * scaleY
        right = expandedRight * scaleX
        bottom = expandedBottom * scaleY
        radiusX = radius * scaleX
        radiusY = radius * scaleY
        return true
    }
}
