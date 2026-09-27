package com.lumen.coacervation.engine.controls

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import androidx.annotation.MainThread
import androidx.appcompat.widget.SwitchCompat
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.liquid.LiquidChoiceDrawable
import com.lumen.coacervation.engine.model.SurfaceRole

/**
 * 把一棵 View 树里的原生控件换装成引擎材质（适配标准 §5.3）：
 *
 * - `SwitchCompat`：滑块与轨道换成 [LiquidChoiceDrawable]（纯渐变，不采样背景）。
 * - `CheckBox`：按钮换成紧凑的 [LiquidChoiceDrawable]，去掉框架默认的 40dp 按压涟漪背景。
 * - `EditText`：背景换成 SELECTED_ITEM 表面，前景加控件描边。
 *
 * 构造期一次性遍历，不挂层级监听、不轮询、不读偏好。之后动态添加的控件需要再调用一次。
 * 会话未准备（例如授权前）时不做任何事。
 */
public object LumenControls {

    @MainThread
    @JvmStatic
    public fun style(root: View, lumen: LumenActivityDelegate) {
        if (!lumen.isPrepared) return
        val palette = lumen.palette
        val density = root.resources.displayMetrics.density
        fun choice(width: Int, height: Int, checkbox: Boolean = false, thumb: Boolean = false) =
            LiquidChoiceDrawable(width, height, density, palette.surface, palette.primary,
                palette.onPrimary, palette.textPrimary, checkbox, thumb)

        fun visit(view: View) {
            when (view) {
                is SwitchCompat -> {
                    val width = (view.thumbDrawable?.intrinsicWidth ?: 0).coerceAtLeast((20 * density).toInt())
                    val height = (view.thumbDrawable?.intrinsicHeight ?: 0).coerceAtLeast((20 * density).toInt())
                    view.thumbTintList = null
                    view.trackTintList = null
                    view.thumbDrawable = choice(width, height, thumb = true)
                    view.trackDrawable = choice(width * 2, height)
                    view.splitTrack = false
                    // 新装的 Drawable 起始状态为空：setThumb/TrackDrawable 不会推送 View 当前状态。
                    // 在已获焦的窗口里 recreate 时 state_window_focused 不变、框架不会补推，
                    // 已开启的开关会停在未选中的配色（来源工程 2026-09-22 真机实证）。
                    view.refreshDrawableState()
                }
                is CheckBox -> {
                    // 框架默认按钮在各 ROM 上尺寸发散（常见 ~32dp），钳到紧凑区间。
                    val size = (view.buttonDrawable?.intrinsicWidth ?: 0)
                        .coerceIn((20 * density).toInt(), (26 * density).toInt())
                    view.buttonTintList = null
                    view.buttonDrawable = choice(size, size, checkbox = true)
                    view.refreshDrawableState()
                    // 框架默认背景是 40dp 按压涟漪，勾选时会在按钮周围开一块浅色遮罩。
                    view.background = null
                }
                is EditText -> {
                    view.backgroundTintList = null
                    val left = view.paddingLeft
                    val top = view.paddingTop
                    val right = view.paddingRight
                    val bottom = view.paddingBottom
                    view.background = lumen.surface(palette.surfaceVariant, 14f, SurfaceRole.SELECTED_ITEM)
                    view.setPadding(left, top, right, bottom)
                    view.foreground = lumen.controlOutline(14f)
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
    }

    /**
     * `SwitchCompat` 的（轨道, 滑块）Drawable，其他控件返回 null。接到 `LumenPagePager.switchParts`，翻页器才能
     * 区分"按在拨钮上"（交给开关）与"按在开关行的文字上横滑"（翻页）；框架 `Switch` 翻页器自己认得。
     *
     * ```kotlin
     * pager.switchParts = { LumenControls.switchParts(it) ?: LumenPagePager.frameworkSwitchParts(it) }
     * ```
     */
    @JvmStatic
    public fun switchParts(view: View): Pair<Drawable?, Drawable?>? =
        (view as? SwitchCompat)?.let { it.trackDrawable to it.thumbDrawable }
}
