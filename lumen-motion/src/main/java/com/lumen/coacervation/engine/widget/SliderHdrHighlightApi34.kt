package com.lumen.coacervation.engine.widget

import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Display
import androidx.annotation.RequiresApi

/**
 * 滑块 HDR 高光的 API 34 隔离类（`ENGINEERING_RULES.md` §2）：读显示器当前 HDR/SDR 余量，
 * 并用 EXTENDED_SRGB 的 long 颜色构建超过 SDR 白的径向渐变。只在按下时、增益或半径变化时调用一次，
 * 不在逐帧路径上新建 Shader（§5）。窗口未处于 `COLOR_MODE_HDR` 时平台按 SDR 钳制，结果与 SDR 回退一致。
 */
@RequiresApi(34)
internal object SliderHdrHighlightApi34 {
    fun headroom(display: Display?): Float =
        if (display != null && display.isHdrSdrRatioAvailable) display.hdrSdrRatio else 1f

    fun shader(radius: Float, gain: Float): Shader {
        val space = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)
        return RadialGradient(
            0f, 0f, radius,
            longArrayOf(
                Color.pack(gain, gain, gain, 1f, space),
                Color.pack(gain, gain, gain, .55f, space),
                Color.pack(gain, gain, gain, 0f, space)
            ),
            floatArrayOf(0f, .3f, 1f),
            Shader.TileMode.CLAMP
        )
    }
}
