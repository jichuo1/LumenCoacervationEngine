package com.lumen.coacervation.engine.liquid

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.core.graphics.createBitmap

/**
 * 实时截图的**反馈抑制**：模块自己画出的玻璃（及其周围 effect padding）在截图里一律换成干净的
 * 显示底图，下一帧的光学输入永远不含上一帧的光学输出——否则文字与玻璃会被递归折射成残影。
 *
 * 遮罩在**发起截图那一刻**按当帧已绘制的足迹构建（[buildSuppressionMask]），回调里只负责应用
 * （[sanitizeRealtimeCapture]）：PixelCopy 读的是最近一次已合成的帧，回调时再取位置会与截图内容
 * 错开几十像素。
 *
 * 2026-09-23 凝光视效引擎重构时从 [LiquidActivityRenderer] 拆出，逻辑逐行不变。
 *
 * **线程分工**（2026-09-23 截图后处理移出 UI 线程）：[buildSuppressionMask] 在主线程发起截图时
 * 执行；[sanitizeRealtimeCapture]、抑制底图缓存与 [close] 是线程封闭的——截图线程
 * （`Lumen-LiquidCapture`）启动后只在它上面执行，主线程的释放请求由渲染器投递过去；线程启动失败或
 * 尚未启动时回退到主线程，此时不存在并发方。[mask] 在两者之间按单飞交接，不会被同时读写。
 */
internal class LiquidFeedbackSuppressor(private val paddingPx: Float) {
    private val canvas = Canvas()
    private val captureBounds = Rect()
    private val scaleBounds = Rect()
    private val geometry = LiquidSuppressionMaskGeometry()
    private val surfacePath = Path()
    private val surfaceToCapture = Matrix()

    /**
     * 发起截图时构建的遮罩。在对应请求完成前被它**独占借用**：单飞保证下一次请求不会中途
     * rewind 它。
     */
    val mask = Path()

    /**
     * 预缩放到截图尺寸的稳定底图，供反馈抑制按 1:1 填充。
     *
     * 抑制原本用 0.25 倍的稳定底图逐帧**双线性放大**填进截图（1440p 上是 360×800 → 671×1490，
     * 约 2.9 倍面积），这是主线程上的软件光栅化，夹在 GPU→CPU 回读与纹理上传之间。预缩放一次后
     * 逐帧只剩 1:1 的 alpha 混合，输出内容不变（同一双线性滤波、同一源，只是重采样从每帧一次变成
     * 尺寸变化时一次）。代价是一张截图尺寸的位图（1,000,000 px 约 3.81 MiB），内存压力下释放。
     */
    private var suppressionUnderlay: Bitmap? = null
    private var suppressionUnderlayShader: BitmapShader? = null
    private var suppressionUnderlaySource: LiquidBackdropSource? = null
    private val suppressionPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 准备与当前截图尺寸 1:1 的抑制底图；尺寸或稳定底图变化时重建。
     *
     * @return 可用时返回 true；分配失败按"本次不做抑制"处理，由调用方回退。
     */
    @AnyThread
    private fun ensureSuppressionUnderlay(
        stableBackdrop: LiquidBackdropSource,
        width: Int,
        height: Int
    ): Boolean {
        val cached = suppressionUnderlay
        if (cached != null && !cached.isRecycled &&
            cached.width == width && cached.height == height &&
            suppressionUnderlaySource === stableBackdrop && !stableBackdrop.isClosed
        ) {
            return true
        }
        releaseSuppressionUnderlay()
        if (width <= 0 || height <= 0 || stableBackdrop.isClosed) return false
        return runCatching {
            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val scaleCanvas = Canvas(bitmap)
            scaleBounds.set(0, 0, width, height)
            // 这一次放大与原逐帧填充使用同一滤波与同一源，输出内容一致。
            stableBackdrop.drawSuppressionBackdrop(scaleCanvas, scaleBounds, 255)
            bitmap.prepareToDraw()
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            suppressionUnderlay = bitmap
            suppressionUnderlayShader = shader
            suppressionUnderlaySource = stableBackdrop
            suppressionPaint.shader = shader
            true
        }.getOrElse {
            releaseSuppressionUnderlay()
            false
        }
    }

    @AnyThread
    fun releaseSuppressionUnderlay() {
        suppressionPaint.shader = null
        suppressionUnderlayShader = null
        suppressionUnderlaySource = null
        suppressionUnderlay = null
    }

    /**
     * 按**发起截图那一刻**的已绘制几何构建抑制遮罩。
     *
     * 位置一律取 footprint 在最近一次 draw 时记录的屏幕原点，而不是实时
     * `getLocationOnScreen`：`PixelCopy` 读的是最近一次已合成的帧，用当前坐标会在快速滑动时
     * 与截图内容错开几十像素。工作量与放在回调里构建完全相同。
     */
    @MainThread
    fun buildSuppressionMask(
        root: View,
        captureSource: LiquidBackdropSource,
        rootOriginX: Int,
        rootOriginY: Int,
        surfaces: MutableMap<View, LiquidSurfaceFootprint>
    ): Boolean {
        if (captureSource.isClosed || root.width <= 0 || root.height <= 0) return false
        val bitmap = captureSource.bitmap
        val scaleX = bitmap.width.toFloat() / root.width.toFloat()
        val scaleY = bitmap.height.toFloat() / root.height.toFloat()
        mask.rewind()
        mask.fillType = Path.FillType.WINDING
        var hasMask = false
                val iterator = surfaces.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val surface = entry.key
            val footprint = entry.value
            if (!surface.isAttachedToWindow) {
                iterator.remove()
                continue
            }
            if (!surface.isShown || surface.alpha <= 0f || surface.rootView !== root.rootView) continue
            if (!footprint.hasOrigin) continue
            if ((footprint.hasTransform && footprint.right > footprint.left && footprint.bottom > footprint.top) || geometry.set(
                    (footprint.originX - rootOriginX + footprint.left).toFloat(),
                    (footprint.originY - rootOriginY + footprint.top).toFloat(),
                    (footprint.originX - rootOriginX + footprint.right).toFloat(),
                    (footprint.originY - rootOriginY + footprint.bottom).toFloat(),
                    footprint.radiusPx, paddingPx, root.width, root.height, scaleX, scaleY
                )) {
                // 保留屏幕外的外扩轮廓，让 Canvas 裁切；先夹坐标会在贴边处造出圆角缺口。
                if (footprint.hasTransform) {
                    // 与 display list 同一帧的完整变换，包括中心缩放、祖先平移和跨窗口偏移。
                    surfacePath.rewind()
                    val radiusPx = footprint.radiusPx + paddingPx
                    surfacePath.addRoundRect(footprint.left - paddingPx, footprint.top - paddingPx,
                        footprint.right + paddingPx, footprint.bottom + paddingPx,
                        radiusPx, radiusPx, Path.Direction.CW)
                    surfaceToCapture.setValues(footprint.screenTransform)
                    surfaceToCapture.postTranslate(-rootOriginX.toFloat(), -rootOriginY.toFloat())
                    surfaceToCapture.postScale(scaleX, scaleY)
                    surfacePath.transform(surfaceToCapture)
                    mask.addPath(surfacePath)
                } else {
                    mask.addRoundRect(geometry.left, geometry.top, geometry.right, geometry.bottom,
                        geometry.radiusX, geometry.radiusY, Path.Direction.CW)
                }
                hasMask = true
            }
        }
        return hasMask
    }

    /**
     * Replace owned optical output with the clean underlay. Leaving any composite fraction would
     * recursively feed the module's own text and previous glass back into the next optical input.
     * Pixels outside the owned-output mask keep the live PixelCopy content.
     *
     * 遮罩几何在发起截图时就已按当帧绘制位置构建（[buildSuppressionMask]），这里只负责应用。
     * 返回值区分"没有可见玻璃"与"真的失败"，调用方只对后者累计熔断计数。
     */
    @AnyThread
    fun sanitizeRealtimeCapture(
        captureSource: LiquidBackdropSource,
        stableBackdrop: LiquidBackdropSource,
        requestMask: Path,
        maskReady: Boolean
    ): LiquidCaptureOutcome {
        if (captureSource.isClosed || stableBackdrop.isClosed) return LiquidCaptureOutcome.FAILED
        if (!maskReady) return LiquidCaptureOutcome.NO_GLASS_VISIBLE

        val bitmap = captureSource.bitmap
        captureBounds.set(0, 0, bitmap.width, bitmap.height)
        canvas.setBitmap(bitmap)
        return try {
            if (ensureSuppressionUnderlay(stableBackdrop, bitmap.width, bitmap.height)) {
                // Cached opaque underlay is copied 1:1; no recursive composite fraction or per-frame resampling.
                suppressionPaint.alpha = LiquidRealtimeCapturePolicy.BASE_SUPPRESSION_ALPHA
                canvas.drawPath(requestMask, suppressionPaint)
            } else {
                // 预缩放位图分配失败时回退到原路径，抑制强度与几何完全一致。
                stableBackdrop.drawSuppressionBackdropMasked(
                    canvas,
                    requestMask,
                    captureBounds,
                    LiquidRealtimeCapturePolicy.BASE_SUPPRESSION_ALPHA
                )
            }
            LiquidCaptureOutcome.SUPPRESSED
        } finally {
            canvas.setBitmap(null)
        }
    }

    @AnyThread
    fun close() {
        releaseSuppressionUnderlay()
        canvas.setBitmap(null)
    }
}
