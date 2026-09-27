package com.lumen.coacervation.engine.liquid

import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Looper
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import androidx.core.graphics.ColorUtils
import com.lumen.coacervation.engine.background.AmbientBackdropScene
import com.lumen.coacervation.engine.model.LumenPalette

/**
 * Activity 的稳定 underlay，或高负载模式下由 PixelCopy 三缓冲持有的实时采样 source。
 *
 * 普通 create/custom 路径不捕获 Window 或 View 树；实时 source 只接管预先分配的可变 Bitmap，
 * 捕获调度与反馈抑制仍由 Activity renderer 负责。
 */
internal class LiquidBackdropSource private constructor(
    val bitmap: Bitmap,
    val customAssetId: String?,
    val isRealtime: Boolean,
    fullWidth: Int,
    fullHeight: Int,
    private val opticalBitmap: Bitmap = bitmap
) : AutoCloseable {
    var fullWidth: Int = fullWidth
        private set
    var fullHeight: Int = fullHeight
        private set

    // Root presentation and optical sampling share coordinates, not necessarily the same pixels.
    // The static custom source has one prefiltered copy; realtime buffers are never CPU-blurred.
    val bitmapShader = BitmapShader(opticalBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    private val rootPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 反馈抑制专用的独立 Shader 与 Matrix。
     *
     * 不能复用 [bitmapShader]：那一份已经作为 RuntimeShader 的 `content` 输入被后端持有，
     * 逐帧改写它的 local matrix 会污染折射采样。
     */
    private val maskShader by lazy(LazyThreadSafetyMode.NONE) {
        BitmapShader(opticalBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            // 与旧路径的 FILTER_BITMAP_FLAG 对齐：稳定底图是 0.25 倍采样，最近邻会在
            // 抑制区域露出明显色块。setFilterMode 是 API 33 才有的显式声明，31-32 仍依赖
            // maskPaint 的 FILTER_BITMAP_FLAG。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            }
        }
    }
    private val maskMatrix = Matrix()
    private val maskPaint by lazy(LazyThreadSafetyMode.NONE) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = maskShader }
    }
    private var closed = false
    private var published = false

    val isClosed: Boolean
        get() = closed

    /** 采样尺寸未变化时只更新窗口映射，不重新分配 Bitmap。 */
    fun updateFullSize(width: Int, height: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        require(width > 0 && height > 0) { "Backdrop dimensions must be positive" }
        fullWidth = width
        fullHeight = height
    }

    fun drawRoot(canvas: Canvas, bounds: Rect, alpha: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        rootPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, bounds, rootPaint)
    }

    /** The already-filtered static optical source, also used to remove captured glass feedback. */
    fun drawOpticalBackdrop(canvas: Canvas, bounds: Rect, alpha: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        rootPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(opticalBitmap, null, bounds, rootPaint)
    }

    /**
     * 在表面本地坐标中按根坐标取一块光学采样区，供外部窗口（Dialog）里的玻璃表面使用：
     * 那些表面折射不到自己窗口的内容，实时截屏里对应位置只有未压暗的锐利底页，
     * 改采已过滤副本才不会把底页文字透进面板。与 [drawRootMasked] 共用独立 Shader，
     * 不触碰折射后端持有的 [bitmapShader]。
     *
     * @param rootOffsetX/rootOffsetY 表面在 backdrop 全幅坐标中的位置（根视图像素）。
     */
    fun drawOpticalRegion(
        canvas: Canvas,
        localBounds: Rect,
        radiusPx: Float,
        rootOffsetX: Float,
        rootOffsetY: Float,
        alpha: Int
    ) {
        check(!closed) { "Liquid backdrop source is closed" }
        if (localBounds.isEmpty || opticalBitmap.width <= 0 || opticalBitmap.height <= 0 ||
            fullWidth <= 0 || fullHeight <= 0
        ) return
        val scaleX = fullWidth.toFloat() / opticalBitmap.width.toFloat()
        val scaleY = fullHeight.toFloat() / opticalBitmap.height.toFloat()
        maskMatrix.setScale(scaleX, scaleY)
        maskMatrix.postTranslate(-rootOffsetX, -rootOffsetY)
        maskShader.setLocalMatrix(maskMatrix)
        maskPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawRoundRect(
            localBounds.left.toFloat(), localBounds.top.toFloat(),
            localBounds.right.toFloat(), localBounds.bottom.toFloat(),
            radiusPx, radiusPx, maskPaint
        )
    }

    /**
     * 按根坐标把**可见根背景**（[bitmap]，含颗粒，与 [drawRoot] 同一张）画进 [bounds]，
     * 逐像素乘以 [alphaMask] 的 alpha 再乘 [alpha]。供滚动边缘溶解把内容"溶回"窗口底图：
     * 画的必须与根背景逐像素一致，否则溶解区会露出一块色差。
     *
     * 独立的 Shader/Matrix：[bitmapShader] 被折射后端持有，[maskShader] 属于光学副本。
     * 同一 Shader 在多个宿主间逐次改 local matrix 是安全的——HWUI 在录制那一刻快照原生实例。
     */
    fun drawPresentationRegion(
        canvas: Canvas,
        bounds: RectF,
        rootOffsetX: Float,
        rootOffsetY: Float,
        alphaMask: Shader?,
        alpha: Int
    ) {
        check(!closed) { "Liquid backdrop source is closed" }
        if (bounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0 || fullWidth <= 0 || fullHeight <= 0) return
        presentationMatrix.setScale(
            fullWidth.toFloat() / bitmap.width.toFloat(),
            fullHeight.toFloat() / bitmap.height.toFloat()
        )
        presentationMatrix.postTranslate(-rootOffsetX, -rootOffsetY)
        presentationShader.setLocalMatrix(presentationMatrix)
        presentationPaint.shader = if (alphaMask == null) presentationShader else {
            // ComposeShader 在子 Shader 的 local matrix 变化后会自行重建原生实例（API 26+），
            // 同一遮罩只需要组合一次。DST_IN：底图 × 遮罩 alpha。
            if (composedMask !== alphaMask) {
                composedShader = ComposeShader(presentationShader, alphaMask, PorterDuff.Mode.DST_IN)
                composedMask = alphaMask
            }
            composedShader
        }
        presentationPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawRect(bounds, presentationPaint)
    }

    private val presentationShader by lazy(LazyThreadSafetyMode.NONE) {
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
    }
    private val presentationMatrix = Matrix()
    private val presentationPaint by lazy(LazyThreadSafetyMode.NONE) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
    private var composedMask: Shader? = null
    private var composedShader: ComposeShader? = null

    /** Commit before binding this source to a renderer or exposing it to a window draw. */
    fun markPublished() {
        check(!closed) { "Liquid backdrop source is closed" }
        if (published) return
        published = true
        bitmap.prepareToDraw()
        if (opticalBitmap !== bitmap) opticalBitmap.prepareToDraw()
    }

    /** Worker results that never reached a display list can release both owned images immediately. */
    fun discardUnpublished() {
        if (closed) return
        check(!published && !isRealtime) { "Cannot recycle a published or realtime backdrop source" }
        closed = true
        if (opticalBitmap !== bitmap && !opticalBitmap.isRecycled) opticalBitmap.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    /**
     * 以光学采样副本填充给定路径，几何映射与根背景相同，根背景本身仍显示用户原图。
     *
     * 旧实现是 `clipPath` + 全图 `drawBitmap`：即使裁剪把光栅化限制在玻璃区域，Skia 仍要为
     * 整张目标位图建立一次抗锯齿裁剪掩码并做 save/restore。改成一次带 Shader 的路径填充后
     * 不再分配裁剪掩码，也不在反馈抑制的逐帧路径执行 CPU 模糊。
     */
    fun drawRootMasked(canvas: Canvas, path: Path, dstBounds: Rect, alpha: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        if (dstBounds.isEmpty || opticalBitmap.width <= 0 || opticalBitmap.height <= 0) return
        maskMatrix.setScale(
            dstBounds.width().toFloat() / opticalBitmap.width.toFloat(),
            dstBounds.height().toFloat() / opticalBitmap.height.toFloat()
        )
        maskMatrix.postTranslate(dstBounds.left.toFloat(), dstBounds.top.toFloat())
        maskShader.setLocalMatrix(maskMatrix)
        maskPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawPath(path, maskPaint)
    }

    override fun close() {
        if (closed) return
        closed = true
        // 已提交到硬件 display list 的 Bitmap 不能用 recycle() 充当 GPU fence。断开 renderer、
        // Shader 与 source 引用后交给运行时回收，旧 display list 的 native 引用可安全完成重放。
    }

    companion object {
        /**
         * 未设自定义图时的稳定 underlay：与标准磨砂皮肤共用 [AmbientBackdropScene] 配方，
         * 两种材质下用户看到的是同一个 Monet 氛围背景。
         *
         * 折射采样底图（[opticalBitmap]）保留颗粒加入前的干净副本——可见根背景带细颗粒纹理，
         * 玻璃采样源保持平滑，折射内容不会被噪点污染。实时截屏路径不受影响。
         */
        fun create(
            palette: LumenPalette,
            fullWidth: Int,
            fullHeight: Int
        ): LiquidBackdropSource {
            val size = LiquidBackdropSizingPolicy.resolve(fullWidth, fullHeight)
            val bitmap = createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            var optical: Bitmap? = null
            try {
                val canvas = Canvas(bitmap)
                val dark = ColorUtils.calculateLuminance(palette.background) < .5
                AmbientBackdropScene.paint(canvas, palette, size.width, size.height, dark)

                // 顶部与底部精确回到 background，避免状态栏/导航栏出现颜色接缝。
                val seamPaint = Paint(
                    Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
                )
                val transparentBackground = ColorUtils.setAlphaComponent(palette.background, 0)
                seamPaint.shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    size.height.toFloat(),
                    intArrayOf(
                        palette.background,
                        transparentBackground,
                        transparentBackground,
                        palette.background
                    ),
                    floatArrayOf(0f, 0.10f, 0.90f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(
                    0f,
                    0f,
                    size.width.toFloat(),
                    size.height.toFloat(),
                    seamPaint
                )

                val pixels = IntArray(size.width * size.height)
                bitmap.getPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
                optical = createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                optical.setPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
                AmbientBackdropScene.addGrain(pixels)
                bitmap.setPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
                bitmap.prepareToDraw()
                optical.prepareToDraw()
                return LiquidBackdropSource(
                    bitmap = bitmap,
                    customAssetId = null,
                    isRealtime = false,
                    fullWidth = fullWidth,
                    fullHeight = fullHeight,
                    opticalBitmap = optical
                )
            } catch (throwable: Throwable) {
                bitmap.recycle()
                optical?.recycle()
                throw throwable
            }
        }

        /**
         * Called on the existing background loader after bounded image decoding. One optical copy
         * is shared by every surface; the caller keeps ownership of [bitmap] if construction fails.
         */
        @WorkerThread
        fun fromCustomBitmap(
            bitmap: Bitmap,
            assetId: String,
            fullWidth: Int,
            fullHeight: Int,
            density: Float
        ): LiquidBackdropSource {
            check(Looper.myLooper() !== Looper.getMainLooper()) {
                "Custom optical backdrop must be prepared off the main thread"
            }
            require(!bitmap.isRecycled) { "Custom backdrop bitmap is recycled" }
            require(assetId.isNotBlank()) { "Custom backdrop asset id is blank" }
            val expected = LiquidBackdropSizingPolicy.resolve(fullWidth, fullHeight)
            require(bitmap.width == expected.width && bitmap.height == expected.height) {
                "Custom backdrop bitmap does not match the bounded sample size"
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val softened = LiquidOpticalSamplingPolicy.soften(pixels, bitmap.width, bitmap.height,
                fullWidth, density)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Backdrop replaced")
            val optical = createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            try {
                optical.setPixels(softened, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return LiquidBackdropSource(
                    bitmap = bitmap,
                    customAssetId = assetId,
                    isRealtime = false,
                    fullWidth = fullWidth,
                    fullHeight = fullHeight,
                    opticalBitmap = optical
                )
            } catch (failure: Throwable) {
                optical.recycle()
                throw failure
            }
        }

        /** 由 PixelCopy 三缓冲拥有的可变窗口截图；source 只建立长期复用的采样 Shader。 */
        fun fromRealtimeBitmap(
            bitmap: Bitmap,
            fullWidth: Int,
            fullHeight: Int
        ): LiquidBackdropSource {
            require(!bitmap.isRecycled && bitmap.isMutable) {
                "Realtime backdrop bitmap must be mutable and available"
            }
            require(fullWidth > 0 && fullHeight > 0) {
                "Realtime backdrop dimensions must be positive"
            }
            return LiquidBackdropSource(
                bitmap = bitmap,
                customAssetId = null,
                isRealtime = true,
                fullWidth = fullWidth,
                fullHeight = fullHeight
            )
        }
    }
}
