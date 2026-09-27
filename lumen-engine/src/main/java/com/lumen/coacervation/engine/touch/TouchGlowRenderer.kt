package com.lumen.coacervation.engine.touch

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import kotlin.math.hypot
import kotlin.math.roundToInt
import com.lumen.coacervation.engine.touch.GlowShape

/**
 * 自适应光晕的薄绘制层。
 *
 * 几何全部由 `AdaptiveGlowPolicy` 算出，这里只做四件事：把椭圆形状与亮核前移写进 shader 的
 * localMatrix、写 paint.alpha、画一个**包住渐变支持域**的圆。**shader 只建一次**；每帧的
 * localMatrix 是 uniform 级更新，不重建 program、不离屏、不分配（Matrix/Paint 均复用）。
 *
 * 旋转语义：localMatrix 在几何局部空间生效、画布 translate 负责位移，两者叠加即
 * "任意朝向椭圆"。`setScale` 之后 `postTranslate` 再 `postRotate`，得到
 * M = R(θ)·T(coreOffsetX,coreOffsetY)·S(sx,sy)——亮核沿局部主轴（形变方向）前移，尾侧衰减更长。
 * 屏幕坐标 y 向下，`atan2(vy,vx)` 与 `postRotate` 同坐标系，正方向一致。
 *
 * **几何圆必须包住前移后的渐变支持域**（长半轴 + 前移量）。早期实现画的是基准半径 R 的圆，
 * 而拉长后的渐变长半轴 rx > R：几何在长轴方向把 alpha 尚有约 0.26 的渐变齐平切断，留下一道
 * 硬边——现场就是"光晕边缘过渡断裂、混色生硬"。改为 max(radiusX, radiusY) + length(coreOffset) 后，
 * 渐变在自身椭圆边界处恰好衰减到 0，边缘连续。
 */
public class TouchGlowRenderer(
    color: Int,
    radiusPx: Float,
    private val coreStopFraction: Float = CORE_STOP_FRACTION,
    private val coreStopAlpha: Float = CORE_STOP_ALPHA,
    private val midStopFraction: Float = MID_STOP_FRACTION,
    private val midStopAlpha: Float = MID_STOP_ALPHA
) {
    private val radius = radiusPx.coerceAtLeast(1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var shader = buildShader(color)

    /**
     * 仅配置期调用（构造期颜色未知的表面，如 scrub 条的 configure）。
     * 每帧调用它是性能红线：那等于每帧重建 shader。
     */
    fun recolor(color: Int) {
        shader = buildShader(color)
    }

    private fun buildShader(color: Int): RadialGradient = RadialGradient(
        0f, 0f, radius,
        intArrayOf(
            ColorUtils.setAlphaComponent(color, 0xFF),
            ColorUtils.setAlphaComponent(color, (coreStopAlpha * 255f).roundToInt()),
            ColorUtils.setAlphaComponent(color, (midStopAlpha * 255f).roundToInt()),
            ColorUtils.setAlphaComponent(color, 0)
        ),
        floatArrayOf(0f, coreStopFraction, midStopFraction, 1f),
        Shader.TileMode.CLAMP
    ).also { paint.shader = it }

    fun draw(canvas: Canvas, shape: GlowShape) {
        if (!shape.visible) return
        matrix.setScale(shape.radiusX / radius, shape.radiusY / radius)
        matrix.postTranslate(shape.coreOffsetX, shape.coreOffsetY)
        matrix.postRotate(shape.rotationDeg)
        shader.setLocalMatrix(matrix)
        paint.alpha = shape.alphaByte
        val extent = maxOf(shape.radiusX, shape.radiusY) + hypot(shape.coreOffsetX, shape.coreOffsetY)
        canvas.withTranslation(shape.centerX, shape.centerY) {
            drawCircle(0f, 0f, extent, paint)
        }
    }

    companion object {
        /** 亮核收在 22% 半径内——形变越大，亮核相对越小、越"聚集"。 */
        const val CORE_STOP_FRACTION = 0.22f
        const val CORE_STOP_ALPHA = 0.74f
        /** 中段肩：把衰减铺开，外缘留出长尾，边缘因此连续、混色柔和。 */
        const val MID_STOP_FRACTION = 0.52f
        const val MID_STOP_ALPHA = 0.30f
    }
}
