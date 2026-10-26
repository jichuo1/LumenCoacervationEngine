package com.lumen.coacervation.engine.host

import androidx.annotation.MainThread
import com.lumen.coacervation.engine.model.SurfaceRole

/**
 * 材质浓度（MATERIAL_OPACITY.md）：0 接近清玻璃（只剩轮廓），1 为默认外观。
 *
 * 只改变材质本身（模糊、色调、亮度、散射、阴影），不改变内容与整体可见度（`View.alpha` / `Drawable.setAlpha`）。
 * 逐帧可调：实现只写原始数值并请求重绘，不分配、不重建着色器或模糊效果。
 */
public interface LumenMaterialOpacityTarget {
    /** 主线程。超出 0..1 截断，NaN 忽略。 */
    @MainThread public fun setMaterialOpacity(value: Float)

    /** 最近一次请求的浓度。 */
    @MainThread public fun materialOpacity(): Float

    /** 可读性下限与“降低透明度”生效后实际使用的浓度。 */
    @MainThread public fun effectiveMaterialOpacity(): Float
}

/** 表面是否承载文字，决定低浓度时是否启用可读性下限（MATERIAL_OPACITY.md §6）。 */
public enum class LumenMaterialLegibility {
    /** 按表面角色推断：卡片、弹窗、顶栏、悬浮栏、标签与按钮视为承载文字。 */
    AUTO,
    /** 承载文字：浓度不低于该材质的下限。 */
    TEXT,
    /** 纯装饰：允许降到 0。 */
    DECORATIVE;

    internal fun carriesText(role: SurfaceRole?): Boolean = when (this) {
        TEXT -> true
        DECORATIVE -> false
        AUTO -> role == null || when (role) {
            SurfaceRole.WINDOW, SurfaceRole.SELECTED_ITEM, SurfaceRole.MOTION_SURFACE -> false
            else -> true
        }
    }
}
