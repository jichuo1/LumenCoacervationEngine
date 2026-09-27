package com.lumen.coacervation.engine.motion

/** 不依赖 Android UI 的矩形，供各类形变（弹窗、气泡、全屏容器）计算和 JVM 单测共用。 */
public data class MotionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    val isValid: Boolean
        get() = left.isFinite() &&
            top.isFinite() &&
            right.isFinite() &&
            bottom.isFinite() &&
            width > 0f &&
            height > 0f
}
