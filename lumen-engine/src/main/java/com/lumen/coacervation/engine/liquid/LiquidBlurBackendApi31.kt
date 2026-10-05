package com.lumen.coacervation.engine.liquid

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import androidx.annotation.RequiresApi
import com.lumen.coacervation.engine.model.LiquidRenderBackend

/** API 31+ 独立 backdrop 模糊；RenderEffect 只作用于共享背景 RenderNode。 */
@RequiresApi(31)
internal class LiquidBlurBackendApi31(
    private val blurRadiusPx: Float
) : LiquidBackendDriver {
    override val backend = LiquidRenderBackend.BLUR
    override val requiresBackdrop = true

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backdropToLocal = Matrix()
    private val clipBounds = RectF()
    private val clipPath = Path()
    private var renderNode: RenderNode? = null
    private var blurEffect: RenderEffect? = null
    private var backdropScaleX = 1f
    private var backdropScaleY = 1f
    private var sampledBlurRadius = Float.NaN

    override fun bindBackdrop(source: LiquidBackdropSource) {
        check(!source.isClosed) { "Cannot bind a closed Liquid backdrop" }
        val node = renderNode ?: RenderNode("Lumen-LiquidBackdrop")
        node.discardDisplayList()
        // setPosition 的返回值是"位置是否变化"，不是"是否成功"：同尺寸重绑（实时取样每次换缓冲）
        // 返回 false。曾经 check 它，第二次绑定就抛异常，BLUR 被永久淘汰，API 31–32 设备
        // 一律退到 TRANSLUCENT（演示包冒烟测试在 API 31 模拟器上发现）。
        node.setPosition(0, 0, source.bitmap.width, source.bitmap.height)
        val recordingCanvas = node.beginRecording(source.bitmap.width, source.bitmap.height)
        try {
            recordingCanvas.drawBitmap(source.bitmap, 0f, 0f, bitmapPaint)
        } finally {
            node.endRecording()
        }
        backdropScaleX = source.fullWidth.toFloat() / source.bitmap.width.toFloat()
        backdropScaleY = source.fullHeight.toFloat() / source.bitmap.height.toFloat()
        val nextBlurRadius = (
            blurRadiusPx / maxOf(backdropScaleX, backdropScaleY)
            ).coerceAtLeast(0.1f)
        if (blurEffect == null || sampledBlurRadius != nextBlurRadius) {
            val effect = RenderEffect.createBlurEffect(
                nextBlurRadius,
                nextBlurRadius,
                Shader.TileMode.CLAMP
            )
            node.setRenderEffect(effect)
            blurEffect = effect
            sampledBlurRadius = nextBlurRadius
        }
        renderNode = node
    }

    override fun drawBackdrop(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        viewX: Int,
        viewY: Int,
        opticalIntensity: Float,
        stretchDirY: Float,
        contentAlpha: Float,
        motionLite: Boolean,
        localToBackdrop: Matrix?
    ) {
        val node = checkNotNull(renderNode) { "Liquid blur backdrop is not bound" }
        // 模糊后端没有边缘光学项，方向不参与；contentAlpha 直接让真实下层内容透入。
        node.alpha = contentAlpha.coerceIn(0f, 1f)
        clipBounds.set(bounds)
        clipPath.reset()
        clipPath.addRoundRect(clipBounds, radiusPx, radiusPx, Path.Direction.CW)
        val saveCount = canvas.save()
        try {
            canvas.clipPath(clipPath)
            if (localToBackdrop != null && localToBackdrop.invert(backdropToLocal)) {
                canvas.concat(backdropToLocal)
            } else {
                canvas.translate(-viewX.toFloat(), -viewY.toFloat())
            }
            canvas.scale(backdropScaleX, backdropScaleY)
            canvas.drawRenderNode(node)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    override fun close() {
        renderNode?.setRenderEffect(null)
        renderNode?.discardDisplayList()
        renderNode = null
        blurEffect = null
        sampledBlurRadius = Float.NaN
    }
}
