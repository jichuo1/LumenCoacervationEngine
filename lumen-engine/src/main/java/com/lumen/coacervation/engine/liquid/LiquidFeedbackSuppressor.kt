package com.lumen.coacervation.engine.liquid

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
    /** 最近一次 [sanitizeRealtimeCapture] 是否走了预缩放抑制底图（而不是分配失败时的回退路径）。 */
    private var lastSanitizeUsedUnderlay = false

    /**
     * 遮罩输入签名（主线程）：[buildSuppressionMask] 把决定遮罩形状的全部数值按位记下，与**上一次真正
     * 发出的截图请求**所用的遮罩比较（[commitMaskSignature]）。构建后没发出请求的那次不算数。
     * 只用于判断"这次遮罩与上一次请求逐位相同"，不参与绘制。
     */
    private var signature = FloatArray(64)
    private var signatureSize = 0
    private var committedSignature = FloatArray(64)
    private var committedSignatureSize = -1

    /** 本次构建的遮罩与上一次发出请求的遮罩逐位相同（输入数值完全一致）。 */
    var maskMatchesPrevious = false
        private set

    /**
     * 静止探测复用（截图线程封闭）：上一次探测的原始截图与它的结论。连续两次探测原始截图逐字节相同、
     * 遮罩与稳定底图不变、基准仍是同一张时，这次抑制后的结果必然与上一次相同、也就与基准相同，
     * 可以跳过约 70% 截图线程开销的整窗抑制填充（2026-10-25 静止期实测）。任何其它截图都会清掉它。
     */
    private var probeRaw: Bitmap? = null
    private val probeCanvas = Canvas()
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private var probeValid = false
    /** 留下原始截图的那次探测的请求序号；只有紧接着它发出的下一次请求可以复用。 */
    private var probeSerial = Long.MIN_VALUE
    private var probeStable: LiquidBackdropSource? = null
    private var probeBaseline: LiquidBackdropSource? = null
    private var probeMaskReady = false
    private var probeUsedUnderlay = false
    private var probeOutcome = LiquidCaptureOutcome.FAILED

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
        forgetProbe()
        probeRaw = null
        regionA = ArrayList()
        regionB = ArrayList()
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
        beginSignature()
        if (captureSource.isClosed || root.width <= 0 || root.height <= 0) {
            signatureSize = -1
            return finishSignature(false)
        }
        val bitmap = captureSource.bitmap
        val scaleX = bitmap.width.toFloat() / root.width.toFloat()
        val scaleY = bitmap.height.toFloat() / root.height.toFloat()
        record(bitmap.width.toFloat()); record(bitmap.height.toFloat()); record(scaleX); record(scaleY)
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
                    record(1f); record(footprint.left.toFloat()); record(footprint.top.toFloat())
                    record(footprint.right.toFloat()); record(footprint.bottom.toFloat()); record(radiusPx)
                    record(paddingPx); record(rootOriginX.toFloat()); record(rootOriginY.toFloat())
                    for (value in footprint.screenTransform) record(value)
                } else {
                    mask.addRoundRect(geometry.left, geometry.top, geometry.right, geometry.bottom,
                        geometry.radiusX, geometry.radiusY, Path.Direction.CW)
                    record(2f); record(geometry.left); record(geometry.top); record(geometry.right)
                    record(geometry.bottom); record(geometry.radiusX); record(geometry.radiusY)
                }
                hasMask = true
            }
        }
        return finishSignature(hasMask)
    }

    private fun beginSignature() {
        signatureSize = 0
    }

    /** 主线程：本次构建的遮罩确实随一次截图请求发出后调用，它成为下一次比较的对象。 */
    @MainThread
    fun commitMaskSignature() {
        val swap = committedSignature
        committedSignature = signature
        committedSignatureSize = signatureSize
        signature = swap
        signatureSize = 0
    }

    private fun record(value: Float) {
        if (signatureSize < 0) return
        if (signatureSize == signature.size) signature = signature.copyOf(signature.size * 2)
        signature[signatureSize++] = value
    }

    /** 与上一次构建逐位比较（`toRawBits`：±0 与 NaN 也按位区分，宁可不复用）。 */
    private fun finishSignature(hasMask: Boolean): Boolean {
        record(if (hasMask) 1f else 0f)
        var same = signatureSize >= 0 && signatureSize == committedSignatureSize
        var index = 0
        while (same && index < signatureSize) {
            same = signature[index].toRawBits() == committedSignature[index].toRawBits()
            index++
        }
        maskMatchesPrevious = same
        return hasMask
    }

    /**
     * 截图线程：本次探测能否沿用上一次探测的结论。返回上一次的结论（此时本次截图**未做抑制**，
     * 调用方只能把它当作"与基准相同"的探测结果，不得绑定），不能复用返回 null。
     */
    @AnyThread
    fun reuseIdenticalProbe(
        serial: Long,
        captureSource: LiquidBackdropSource,
        stableBackdrop: LiquidBackdropSource,
        baseline: LiquidBackdropSource?,
        maskReady: Boolean,
        maskMatchesPrevious: Boolean
    ): LiquidCaptureOutcome? {
        val raw = probeRaw
        // 序号相邻保证"上一次请求"就是留下原始截图的那次探测，遮罩签名比较的也正是它的遮罩。
        if (!probeValid || raw == null || baseline == null || !maskMatchesPrevious || serial != probeSerial + 1) return null
        if (captureSource.isClosed || stableBackdrop.isClosed || baseline.isClosed) return null
        if (probeStable !== stableBackdrop || probeBaseline !== baseline || probeMaskReady != maskReady) return null
        val bitmap = captureSource.bitmap
        // 抑制路径必须与上一次相同：上一次用了预缩放底图，这次它也必须仍按同一源、同一尺寸缓存着。
        if (maskReady && (!probeUsedUnderlay || !underlayReadyFor(stableBackdrop, bitmap.width, bitmap.height))) return null
        if (!bitmap.sameAs(raw)) return null
        // 本次与上一次的原始截图、遮罩、底图、基准与结论都相同，它同样可以作为下一次的比较对象。
        probeSerial = serial
        return probeOutcome
    }

    /** 截图线程：完整路径之前留下本次探测的原始截图（抑制会就地改写它）。 */
    @AnyThread
    fun rememberProbeRaw(captureSource: LiquidBackdropSource) {
        probeValid = false
        val bitmap = captureSource.bitmap
        var raw = probeRaw
        if (raw == null || raw.width != bitmap.width || raw.height != bitmap.height) {
            raw = createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            probeRaw = raw
        }
        probeCanvas.setBitmap(raw)
        try {
            probeCanvas.drawBitmap(bitmap, 0f, 0f, copyPaint)
        } finally {
            probeCanvas.setBitmap(null)
        }
    }

    /** 截图线程：完整路径结束后登记结论；只有"抑制后与基准相同"的探测才能被下一次复用。 */
    @AnyThread
    fun commitProbe(
        serial: Long,
        stableBackdrop: LiquidBackdropSource,
        baseline: LiquidBackdropSource?,
        maskReady: Boolean,
        outcome: LiquidCaptureOutcome,
        sameAsBaseline: Boolean
    ) {
        probeValid = probeRaw != null && baseline != null && sameAsBaseline && outcome != LiquidCaptureOutcome.FAILED
        probeSerial = serial
        probeStable = stableBackdrop
        probeBaseline = baseline
        probeMaskReady = maskReady
        probeUsedUnderlay = lastSanitizeUsedUnderlay
        probeOutcome = outcome
    }

    /** 截图线程：非探测截图、失败与缓冲变化都让复用失效。 */
    @AnyThread
    fun forgetProbe() {
        probeValid = false
        probeStable = null
        probeBaseline = null
    }

    private val regionCanvas = Canvas()
    private val regionSource = Rect()
    private val regionTarget = Rect()
    private var regionA = ArrayList<Bitmap>()
    private var regionB = ArrayList<Bitmap>()

    /**
     * 截图线程：两张抑制后的截图在 [rects]（位图像素，left/top/right/bottom 依次排列，右下不含）内是否逐像素相同。
     * 只比较玻璃可能读到的区域（见 [LiquidGlassFootprintPolicy]）。区域为空表示没有玻璃读取截图，视为相同。
     * 每块区域以 SRC、无缩放原样复制进复用位图后用原生 `sameAs` 比较：逐行内存复制加 memcmp，
     * 不经逐像素颜色转换；复制不改变任何像素，结论与直接比较原始像素一致。
     */
    @AnyThread
    fun regionsEqual(capture: Bitmap, baseline: Bitmap, rects: IntArray): Boolean {
        if (capture.width != baseline.width || capture.height != baseline.height || capture.config != baseline.config) return false
        var index = 0
        var slot = 0
        while (index + 3 < rects.size) {
            val left = rects[index]; val top = rects[index + 1]
            val width = rects[index + 2] - left; val height = rects[index + 3] - top
            index += 4
            if (width <= 0 || height <= 0) continue
            val a = regionBitmap(regionA, slot, width, height, capture.config)
            val b = regionBitmap(regionB, slot, width, height, capture.config)
            slot++
            regionSource.set(left, top, left + width, top + height)
            regionTarget.set(0, 0, width, height)
            copyRegion(capture, a); copyRegion(baseline, b)
            if (!a.sameAs(b)) return false
        }
        return true
    }

    private fun regionBitmap(pool: ArrayList<Bitmap>, slot: Int, width: Int, height: Int, config: Bitmap.Config?): Bitmap {
        val existing = pool.getOrNull(slot)
        if (existing != null && existing.width == width && existing.height == height) return existing
        val created = createBitmap(width, height, config ?: Bitmap.Config.ARGB_8888)
        if (slot < pool.size) pool[slot] = created else pool += created
        return created
    }

    private fun copyRegion(from: Bitmap, to: Bitmap) {
        regionCanvas.setBitmap(to)
        try {
            regionCanvas.drawBitmap(from, regionSource, regionTarget, copyPaint)
        } finally {
            regionCanvas.setBitmap(null)
        }
    }

    private fun underlayReadyFor(stableBackdrop: LiquidBackdropSource, width: Int, height: Int): Boolean {
        val cached = suppressionUnderlay ?: return false
        return !cached.isRecycled && cached.width == width && cached.height == height &&
            suppressionUnderlaySource === stableBackdrop && !stableBackdrop.isClosed
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
        lastSanitizeUsedUnderlay = false
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
                lastSanitizeUsedUnderlay = true
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
        probeCanvas.setBitmap(null)
    }
}
