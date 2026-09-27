package com.lumen.coacervation.engine.liquid

/**
 * 悬浮胶囊清透档的配套参数，两者一起评估，不能只改变模糊而沿用旧的细节估计。
 * 节点合成不透明不等于下方细节不可见；这里的比例用于可读性估计，不是绘制 alpha。
 * 0.6 是当前设计估值，实际文字可读性与 GPU 开销仍须在设备上标定。
 */
internal object LiquidChromeGlassPolicy {
    const val BLUR_RADIUS_DP = 3f
    const val DETAIL_SEE_THROUGH = 0.6f
}
