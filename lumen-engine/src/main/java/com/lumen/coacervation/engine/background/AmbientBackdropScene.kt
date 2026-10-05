package com.lumen.coacervation.engine.background

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import com.lumen.coacervation.engine.model.LumenPalette

/**
 * 自动氛围底图场景：标准磨砂皮肤与 Liquid 未设自定义图时的稳定 underlay 共用同一配方，
 * 两条皮肤下用户看到的是同一个背景。
 *
 * 分层（全部取自 Monet palette，不引入新色源）：
 * ① 纵向色阶——顶部向 surface 轻抬、底部向深端渐沉，底色自带方向性明度场；
 * ② 四团环境光晕——primary 左上 / secondary 右中 / tertiary 下缘 / surface 顶部中性提升，
 *    每团用"亮核→肩部→归零"三段衰减，避免线性径向渐变的色团感；
 * ③ 四缘暗角——中心约一半透明，边缘轻压，视线收向中部。
 * 场景按窗口的 0.2～0.25 倍尺寸生成；逐像素颗粒放大后会成为斑块，因此只保留低频渐变，
 * 色阶仍由 DITHER_FLAG 抖动处理。两条皮肤的显示与采样都使用这份干净背景。
 */
internal object AmbientBackdropScene {

    fun paint(canvas: Canvas, palette: LumenPalette, width: Int, height: Int, dark: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(palette.background)
        val wash = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        wash.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(
                ColorUtils.blendARGB(palette.background, palette.surface, if (dark) .16f else .34f),
                palette.background,
                palette.background,
                ColorUtils.blendARGB(palette.background,
                    if (dark) Color.BLACK else palette.surfaceVariant, if (dark) .28f else .3f)
            ),
            floatArrayOf(0f, .42f, .78f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, wash)

        ambient(canvas, wash, w, h, x = .16f, y = .20f, radius = 1.05f,
            color = palette.primary, alpha = if (dark) 0x2E else 0x3A)
        ambient(canvas, wash, w, h, x = .95f, y = .58f, radius = .92f,
            color = palette.secondary, alpha = if (dark) 0x24 else 0x2E)
        ambient(canvas, wash, w, h, x = .28f, y = 1.05f, radius = .95f,
            color = palette.tertiary, alpha = if (dark) 0x1C else 0x24)
        ambient(canvas, wash, w, h, x = .5f, y = -.04f, radius = 1.15f,
            color = palette.surface, alpha = if (dark) 0x20 else 0x34)

        wash.shader = RadialGradient(w * .5f, h * .42f, maxOf(w, h) * .74f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(Color.BLACK,
                    ((if (dark) 0x38 else 0x14) * 0.45f).toInt()),
                ColorUtils.setAlphaComponent(Color.BLACK, if (dark) 0x38 else 0x14)),
            floatArrayOf(0f, .5f, .8f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, wash)
    }

    /**
     * 三段衰减的径向光晕：亮核 → 55% 处约三分之一强度 → 边缘归零。
     * 肩部让光晕体积更大但峰值更柔，比单段线性渐变更"散"。
     */
    private fun ambient(
        canvas: Canvas,
        wash: Paint,
        w: Float,
        h: Float,
        x: Float,
        y: Float,
        radius: Float,
        color: Int,
        alpha: Int
    ) {
        wash.shader = RadialGradient(w * x, h * y, (w * radius).coerceAtLeast(1f),
            intArrayOf(
                ColorUtils.setAlphaComponent(color, alpha),
                ColorUtils.setAlphaComponent(color, (alpha * .34f).toInt()),
                ColorUtils.setAlphaComponent(color, 0)
            ),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, wash)
    }

}
