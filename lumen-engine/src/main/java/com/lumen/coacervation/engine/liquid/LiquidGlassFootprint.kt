package com.lumen.coacervation.engine.liquid

import android.graphics.Matrix
import android.graphics.RectF
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.model.LiquidParameters
import kotlin.math.ceil
import kotlin.math.floor

/**
 * REFRACTION 窗口玻璃会读到的实时截图区域（纯策略，JVM 单测）。
 *
 * 推导（canvas 坐标，coord 为正在着色的像素；见 `ROUNDED_RECT_REFRACTION_SHADER`）：
 * - 深内部早退取样 coord 本身；内部透镜 `interiorOffset` ≤ [INTERIOR_LENS_PEAK]·ID·oi；
 * - 折射位移 `edgeWeight·RA·edgeBoost·|grad|·edgeReach` ≤ RA·oi；
 * - 其上再叠色散（≤ CS·oi）或散射（≤ SR·oi）之一；oi 在后端夹到 ≤ [MAX_OPTICAL_INTENSITY]。
 * 取样点经平移或射影映射到根坐标，再乘 backdropScale 到位图；抗锯齿边缘像素中心至多在 bounds 外 0.5px，
 * LINEAR 读 floor(p−0.5) 及其后一格，CLAMP 只读位图内像素。
 *
 * 设备证明：`LiquidGlassFootprintInstrumentedTest`——区域外的截图像素怎么变，GPU 渲染出的玻璃都逐位不变。
 */
internal object LiquidGlassFootprintPolicy {
    /** 与 `LiquidRefractionBackendApi33.drawBackdrop` 对 opticalIntensity 的上限一致（源码契约守住）。 */
    const val MAX_OPTICAL_INTENSITY = 1.85f
    /** r(1−r²) 在 [0,1] 上的最大值 2/(3√3) ≈ 0.38490，向上取整。 */
    const val INTERIOR_LENS_PEAK = 0.385f
    /** 抗锯齿边缘的像素中心在 bounds 外至多 0.5px，取 1px。 */
    const val EDGE_COVERAGE_PX = 1f
    /** LINEAR 过滤读到的相邻像素，两侧各留的位图像素。 */
    const val FILTER_TEXELS = 2

    /** 单个着色点到它任一取样点的最大距离（canvas 像素）。 */
    fun maxOffsetPx(parameters: LiquidParameters, density: Float): Float = MAX_OPTICAL_INTENSITY * density * (
        parameters.refractionAmountDp + INTERIOR_LENS_PEAK * parameters.interiorDistortionDp +
            maxOf(parameters.chromaticShiftDp, parameters.scatteringRadiusDp))

    /** 根坐标下（已外扩）的矩形 → 位图像素矩形，写入 [out] 的 [index]..[index]+3（left, top, right, bottom，右下不含）。 */
    fun toBitmapRect(
        left: Float, top: Float, right: Float, bottom: Float,
        scaleX: Float, scaleY: Float, bitmapWidth: Int, bitmapHeight: Int,
        out: IntArray, index: Int
    ) {
        out[index] = (floor(left * scaleX).toInt() - FILTER_TEXELS).coerceIn(0, bitmapWidth)
        out[index + 1] = (floor(top * scaleY).toInt() - FILTER_TEXELS).coerceIn(0, bitmapHeight)
        out[index + 2] = (ceil(right * scaleX).toInt() + FILTER_TEXELS).coerceIn(0, bitmapWidth)
        out[index + 3] = (ceil(bottom * scaleY).toInt() + FILTER_TEXELS).coerceIn(0, bitmapHeight)
    }
}

/**
 * 主线程：把一个玻璃表面的本地 bounds 按与 `drawBackdrop` 相同的映射（平移或 `localToBackdrop`）换到根坐标，
 * 再交给 [LiquidGlassFootprintPolicy] 得到位图区域。不处理回弹拉伸采样——调用方遇到拉伸就整体退回整图比较。
 */
@MainThread
internal class LiquidGlassFootprint {
    private val rect = RectF()

    fun bitmapRect(
        left: Int, top: Int, right: Int, bottom: Int,
        viewX: Float, viewY: Float, localToBackdrop: Matrix?, deltaPx: Float,
        scaleX: Float, scaleY: Float, bitmapWidth: Int, bitmapHeight: Int,
        out: IntArray, index: Int
    ) {
        val expand = deltaPx + LiquidGlassFootprintPolicy.EDGE_COVERAGE_PX
        rect.set(left - expand, top - expand, right + expand, bottom + expand)
        // 射影映射 w>0 时凸区域的像落在角点像的包围盒内；mapRect 取的正是四个角点的包围盒。
        if (localToBackdrop != null) localToBackdrop.mapRect(rect) else rect.offset(viewX, viewY)
        LiquidGlassFootprintPolicy.toBitmapRect(rect.left, rect.top, rect.right, rect.bottom,
            scaleX, scaleY, bitmapWidth, bitmapHeight, out, index)
    }
}
