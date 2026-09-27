package com.lumen.coacervation.engine.liquid

import android.graphics.Canvas
import android.graphics.Rect
import com.lumen.coacervation.engine.model.LiquidRenderBackend

/** 高 API 图形类型不得出现在该公共接口中，保证 API 27 可以安全加载通用 renderer。 */
internal interface LiquidBackendDriver : AutoCloseable {
    val backend: LiquidRenderBackend
    val requiresBackdrop: Boolean

    /** 只在布局/尺寸变化路径调用，不在 draw 中创建或绑定图形资源。 */
    fun bindBackdrop(source: LiquidBackdropSource)

    /**
     * 调用方保证在主线程绘制；实现不得在此创建 Bitmap、Shader 或 RenderEffect。
     *
     * [stretchDirY]：系统超出回弹的方向，`-1` 顶部下拉（高光投到表面**上**边缘）、
     * `+1` 底部上拉、`0` 无回弹。只在 `opticalIntensity > 1` 时有视觉意义；
     * 不支持方向光效的后端直接忽略。
     * [contentAlpha]：玻璃层输出不透明度。< 1 时真实下层内容参与合成（浮动表面
     * 借此透出下方滚动内容）；1 为全不透明，行为与旧版一致。
     * [motionLite]：位移抑制期置 true——此时绑定的本就是平滑稳定底图，多次散射
     * 取样没有收益；后端只保留单次取样与边缘光项，外观与静止态一致但逐像素
     * 纹理成本降到约 1/5。不支持的分级后端直接忽略。
     */
    fun drawBackdrop(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        viewX: Int,
        viewY: Int,
        opticalIntensity: Float,
        stretchDirY: Float,
        contentAlpha: Float,
        motionLite: Boolean
    )
}

/** API 27 通用的零资源后端；实际半透明表面由通用 renderer 绘制。 */
internal class LiquidTranslucentBackend : LiquidBackendDriver {
    override val backend = LiquidRenderBackend.TRANSLUCENT
    override val requiresBackdrop = false

    override fun bindBackdrop(source: LiquidBackdropSource) = Unit

    override fun drawBackdrop(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        viewX: Int,
        viewY: Int,
        opticalIntensity: Float,
        stretchDirY: Float,
        contentAlpha: Float,
        motionLite: Boolean
    ) = Unit

    override fun close() = Unit
}
