package com.lumen.coacervation.engine.model

/**
 * 与具体皮肤无关的表面语义。
 *
 * 业务代码只能声明“它是什么”，不能直接选择 Liquid 或 Material renderer。
 */
public enum class SurfaceRole {
    WINDOW,
    CARD,
    MODAL,
    TOP_BAR,
    CHIP,
    FILLED_BUTTON,
    TEXT_BUTTON,
    SELECTED_ITEM,
    FLOATING,
    MOTION_SURFACE
}
