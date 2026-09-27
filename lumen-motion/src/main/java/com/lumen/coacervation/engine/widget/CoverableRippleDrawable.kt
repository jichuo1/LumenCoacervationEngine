package com.lumen.coacervation.engine.widget

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.core.graphics.ColorUtils
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.roundToInt

/**
 * 能被上层表面"盖住"的涟漪。
 *
 * 顶栏图标按钮的涟漪是 foreground，浅色主题下是 0x8C 白：点击后气泡面板在 ~250ms 内
 * 从按钮长出并盖住它，涟漪的退场动画却还要再跑几百毫秒，隔着半透明玻璃把图标区域
 * 提亮到比稳定打开态还亮（真机 176→219→199），读作"按钮深浅跳一下"。
 * [coverOpacity] 由气泡按表面覆盖度逐帧写入，涟漪颜色的 alpha 随之淡出；
 * RippleDrawable 在 API 31 以前没有取色接口，所以基色在构造时自己记下。
 */
public class CoverableRippleDrawable(
    private val baseColor: Int,
    content: Drawable?,
    mask: Drawable?
) : RippleDrawable(ColorStateList.valueOf(baseColor), content, mask) {

    var coverOpacity = 0f
        set(value) {
            val clamped = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            val alpha = (Color.alpha(baseColor) * (1f - clamped)).roundToInt()
            setColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(baseColor, alpha)))
            // API 31+ 的涟漪动画在 RenderThread 上跑，已经开始的那一次不再读新颜色；
            // 表面盖过一半后直接清掉热点（setVisible(false) 走 clearHotspots），
            // 此时按钮已在玻璃下，清掉不会被读成跳变。完全放开后再恢复可见。
            val shouldShow = clamped < COVERED_THRESHOLD
            if (shouldShow != isVisible) setVisible(shouldShow, false)
        }

    public companion object {
        private const val COVERED_THRESHOLD = 0.5f
        /** 浅色主题的白色涟漪不透明度：玻璃表面约 226–230，按下提亮到约 243。 */
        private const val LIGHT_THEME_RIPPLE_ALPHA = 0x8C
        private const val DARK_THEME_RIPPLE_ALPHA = 0x30

        /**
         * 自绘圆角点击涟漪（不解析主题属性 `selectableItemBackground*`：部分设备的全局主题模块会劫持主题属性，
         * 返回不透明实心色盖住内容）。适配标准 §12.2。
         *
         * **content 必须与 mask 同圆角**：`RippleDrawable.getOutline()` 只取第一个非 mask 层，透明 `ColorDrawable`
         * 报出的是直角矩形；长按弹性高光按 outline 裁剪，设计圆角全在 mask 上的话高光就成了方角、与涟漪边缘割裂。
         * content 仍然全透明，只是让轮廓说真话。
         *
         * 涟漪色随主题：深色用主文字色提亮；浅色用白色提亮（深灰涟漪在浅色下会先冒出一团灰黑光斑，读作"压了一层
         * 黑色遮罩"）。
         */
        @JvmStatic
        public fun rounded(palette: LumenPalette, cornerRadiusPx: Float): CoverableRippleDrawable {
            val content = GradientDrawable().apply {
                cornerRadius = cornerRadiusPx
                setColor(Color.TRANSPARENT)
            }
            val mask = GradientDrawable().apply {
                cornerRadius = cornerRadiusPx
                setColor(Color.WHITE)
            }
            val dark = ColorUtils.calculateLuminance(palette.surface) < 0.5
            val color = if (dark) ColorUtils.setAlphaComponent(palette.textPrimary, DARK_THEME_RIPPLE_ALPHA)
            else ColorUtils.setAlphaComponent(Color.WHITE, LIGHT_THEME_RIPPLE_ALPHA)
            return CoverableRippleDrawable(color, content, mask)
        }
    }
}
