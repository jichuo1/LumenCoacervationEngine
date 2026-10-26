package com.lumen.coacervation.engine.host

import android.graphics.drawable.Drawable
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.material.ControlShadowDrawable

/**
 * 控件软阴影的独立入口：不经过 Activity 委托也能给任意控件加阴影（适配标准 §5.6）。
 *
 * 典型用法是给引擎表面背景加一层：`view.background = LumenControlShadow.wrap(lumen.cardBackground(), 15f, density, options)`；
 * 也可以只要阴影层（`shadowOnly`），叠在宿主自己的背景之下。阴影画在控件边界之外，
 * 祖先需要 `clipChildren=false` 或按 [outsetPx] 预留间距。
 *
 * 全部主线程调用；配置见 [LumenControlShadowOptions]。
 */
@MainThread
public object LumenControlShadow {

    /**
     * 给任意背景 Drawable 包一层软阴影；`options.enabled` 为 false 时原样返回 [content]。
     * 包裹层转发回调、状态、热点与轮廓，对 View 而言与裸背景等价。
     */
    @JvmStatic
    public fun wrap(
        content: Drawable,
        cornerRadiusDp: Float,
        density: Float,
        options: LumenControlShadowOptions,
        dark: Boolean
    ): Drawable {
        if (!options.enabled) return content
        require(density.isFinite() && density > 0f) { "density must be positive, was $density" }
        return ControlShadowDrawable(content, cornerRadiusDp.coerceAtLeast(0f) * density,
            density, options, dark)
    }

    /** 只要阴影层本身，用于叠在宿主自有背景之下（例如 `FrameLayout` 里后加的底层 View）。 */
    @JvmStatic
    public fun shadowOnly(
        cornerRadiusDp: Float,
        density: Float,
        options: LumenControlShadowOptions,
        dark: Boolean
    ): Drawable {
        require(density.isFinite() && density > 0f) { "density must be positive, was $density" }
        return ControlShadowDrawable(null, cornerRadiusDp.coerceAtLeast(0f) * density,
            density, options, dark)
    }

    /**
     * 阴影在控件边界外的最大外扩像素：水平方向各需要这么多空间，下方再叠加 offsetY。
     * 用于宿主规划间距/关闭祖先裁剪；`options.enabled` 为 false 时返回 0。
     */
    @JvmStatic
    public fun outsetPx(options: LumenControlShadowOptions, density: Float): Int {
        if (!options.enabled) return 0
        require(density.isFinite() && density > 0f) { "density must be positive, was $density" }
        return com.lumen.coacervation.engine.material.ControlShadowPolicy.outsetPx(
            options.blurRadiusDp * density, options.spreadDp * density, options.offsetYDp * density)
    }
}
