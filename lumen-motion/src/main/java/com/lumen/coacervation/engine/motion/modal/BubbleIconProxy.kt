package com.lumen.coacervation.engine.motion.modal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.withSave
import com.lumen.coacervation.engine.widget.CoverableRippleDrawable
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 来源图标"未被动画改过"的 alpha，按 View 记账（仅主线程使用）。
 *
 * 不能让每个代理各自记一份 `source.alpha`。`presentSizedModalDialog` 开头的
 * `activeConfirmDialog?.dismiss()` 是硬关，而 `Dialog.dismiss` 只把收尾监听器 **post** 出去，
 * 真正的 [BubbleIconProxy.dispose] 要等下一轮消息循环；新代理在那之前就已构造完成，于是把
 * 停在动画中途的 alpha 当成了"原始值"。连续打断几次，每次"还原"都比上一次更暗，图标最终
 * 永久看不见——只有重建 Activity 才恢复，与现场报告一致。
 *
 * 首个持有者记下的值就是全体持有者共同的还原目标；最后一个持有者释放时把它写回，
 * 所以无论中途走了哪条异常路径，最后一个气泡消失后图标一定回到原样。
 */
private object SourceIconAlpha {
    private class Entry(val alpha: Float) {
        var holders = 0

        /** 是否有任一持有者真的改过这个 View 的 alpha。 */
        var touched = false
    }

    private val entries = WeakHashMap<ImageView, Entry>()

    fun acquire(view: ImageView): Float {
        val entry = entries.getOrPut(view) { Entry(view.alpha) }
        entry.holders++
        return entry.alpha
    }

    fun markTakenOver(view: ImageView) {
        entries[view]?.touched = true
    }

    fun release(view: ImageView) {
        val entry = entries[view] ?: return
        if (--entry.holders > 0) return
        entries.remove(view)
        // 只回收自己动过的：没人接管过就不碰，免得盖掉换肤之类的正当改动。
        // 非法值同样不回写，那不是这里改坏的，也不该由这里定义"原样"。
        if (entry.touched && entry.alpha.isFinite() && entry.alpha in 0f..1f) {
            view.alpha = entry.alpha
        }
    }
}

/**
 * 工具栏图标的短期图案代理：只捕获 ImageView 的 drawable，不包含 ripple、兄弟角标或背景。
 *
 * Bitmap 仅在准备阶段生成一次，最长边不超过 192px。颜色、tint、state、imageAlpha 均沿用
 * 已配置的 drawable；不更改其 bounds，也不改动真实控件的图片属性。动画期只改 alpha 和矩形。
 */
internal class BubbleIconProxy(private val source: ImageView) {
    private val originalAlpha = SourceIconAlpha.acquire(source)
    /**
     * 按钮原位的静止图标：面板从按钮处长出时，最初约 150ms 它的玻璃表面正好盖在按钮上，
     * 飞行副本又已飞走，按钮原位只剩一片浅色玻璃——深色图标先"消失"再"出现"，浅色下读作
     * 按钮深浅跳动（2026-09-24 真机：图标区与圆形底同时升到 242/243）。在面板窗口里、
     * 按钮原位、裁到面板当前形状内补画一张，不透明度 = 真实图标权重 × 表面不透明度，
     * 正好补回被玻璃盖掉的那部分。深色下面板与按钮同为深色，不画，保持原观感。
     */
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lightTheme = (source.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val sourceLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private val visibleBounds = Rect()
    private val sourceBounds = RectF()
    private val drawBounds = RectF()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    }
    private var root: View? = null
    private var bitmap: Bitmap? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var captureScale = 1f
    private var prepared = false
    private var disposed = false

    /**
     * 这个代理是否真的动过来源控件的 alpha。
     *
     * 捕获失败的代理是完全惰性的，[settleExpanded] 与 [dispose] 便不该替它"还原"——
     * 无条件写回会把上一轮留下的中途值当成真值盖到图标上。
     */
    private var tookOver = false

    val hasSnapshot: Boolean
        get() = bitmap != null && !disposed

    /** 失败不隐藏真实图标；同一个代理最多尝试捕获一次，不在布局或动画回调里反复采样。 */
    fun prepare(root: View): Boolean {
        if (disposed) return false
        if (prepared) return this.root === root && hasSnapshot
        prepared = true
        if (!source.isAttachedToWindow || !root.isAttachedToWindow || !source.isShown ||
            source.width <= 0 || source.height <= 0 || source.imageAlpha <= 0 ||
            !originalAlpha.isFinite() || originalAlpha <= 0f || originalAlpha > 1f ||
            source.scrollX != 0 || source.scrollY != 0 ||
            !stableTransform(source) || !stableTransform(root) ||
            source.display?.displayId != root.display?.displayId ||
            !source.getGlobalVisibleRect(visibleBounds) ||
            visibleBounds.width() < source.width || visibleBounds.height() < source.height
        ) return false
        val drawable = source.drawable ?: return false
        if (drawable.bounds.isEmpty) return false
        val matrix = source.imageMatrix
        val matrixValues = FloatArray(9)
        matrix.getValues(matrixValues)
        if (matrixValues.any { !it.isFinite() } ||
            matrixValues[Matrix.MPERSP_2] == 0f
        ) return false

        val width = source.width
        val height = source.height
        val scale = minOf(1f, MAX_CAPTURE_SIDE.toFloat() / maxOf(width, height))
        val bitmapWidth = ceil(width * scale.toDouble()).toInt().coerceIn(1, MAX_CAPTURE_SIDE)
        val bitmapHeight = ceil(height * scale.toDouble()).toInt().coerceIn(1, MAX_CAPTURE_SIDE)
        val captured = try {
            Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888).also { image ->
                // drawBitmap(Bitmap, x, y, Paint) 不额外按目标 Canvas density 缩放。
                image.density = Bitmap.DENSITY_NONE
                Canvas(image).withSave {
                    scale(scale, scale)
                    clipRect(0f, 0f, width.toFloat(), height.toFloat())
                    if (source.cropToPadding) {
                        clipRect(source.paddingLeft, source.paddingTop,
                            width - source.paddingRight, height - source.paddingBottom)
                    }
                    translate(source.paddingLeft.toFloat(), source.paddingTop.toFloat())
                    concat(matrix)
                    drawable.draw(this)
                }
                // 完全透明或非法图案不能接管真实图标；有界检查只发生在单次准备阶段。
                val pixels = IntArray(bitmapWidth * bitmapHeight)
                image.getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
                if (pixels.none { it ushr 24 != 0 }) return false
            }
        } catch (_: RuntimeException) {
            return false
        } catch (_: OutOfMemoryError) {
            // 小图案代理不是必要功能；资源压力时保留原图标与既有表面动画。
            return false
        }
        this.root = root
        sourceWidth = width
        sourceHeight = height
        captureScale = scale
        bitmap = captured
        iconPaint.alpha = 0
        if (!readSourceBounds(sourceBounds)) {
            dispose()
            return false
        }
        drawBounds.set(sourceBounds)
        return true
    }

    /** 来源 View 的完整边界：屏幕坐标减去当前 Dialog 根原点，不把 drawable 空白边缘裁掉。 */
    fun copySourceBounds(out: RectF) {
        if (!hasSnapshot) {
            out.setEmpty()
            return
        }
        if (!readSourceBounds(sourceBounds)) {
            dispose()
            out.setEmpty()
            return
        }
        out.set(sourceBounds)
    }

    fun updateFrame(surfaceBounds: RectF, progress: Float) {
        if (!hasSnapshot) return
        val p = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
        val travel = BubbleLayerMotionSpec.iconTravelFraction(p)
        // 零进度的表面可能是零尺寸；源图标原位交接并不依赖表面几何，不能因此废弃捕获。
        if (!readSourceBounds(sourceBounds) || (travel > 0f && !validBounds(surfaceBounds))) {
            dispose()
            return
        }
        if (travel == 0f) {
            // 与来源的原位交接段不发生移动、缩放或取整，避免收起末尾跳动。
            drawBounds.set(sourceBounds)
        } else {
            val fit = minOf(1.5f, surfaceBounds.width() / sourceWidth,
                surfaceBounds.height() / sourceHeight).coerceAtLeast(0f)
            val uniformScale = 1f + (fit - 1f) * travel
            val width = sourceWidth * uniformScale
            val height = sourceHeight * uniformScale
            val centerX = sourceBounds.centerX() +
                (surfaceBounds.centerX() - sourceBounds.centerX()) * travel
            val centerY = sourceBounds.centerY() +
                (surfaceBounds.centerY() - sourceBounds.centerY()) * travel
            drawBounds.set(centerX - width / 2f, centerY - height / 2f,
                centerX + width / 2f, centerY + height / 2f)
        }
        if (!tookOver) {
            tookOver = true
            SourceIconAlpha.markTakenOver(source)
        }
        // 真实图标与副本的实际不透明度互补，见 BubbleLayerMotionSpec.proxyIconOpacity。
        source.alpha = originalAlpha * BubbleLayerMotionSpec.sourceIconWeight(p, lightTheme)
        iconPaint.alpha = (255f * originalAlpha * BubbleLayerMotionSpec.proxyIconOpacity(p, lightTheme))
            .roundToInt().coerceIn(0, 255)
        slotPaint.alpha = if (!lightTheme) 0 else (255f * originalAlpha *
            BubbleLayerMotionSpec.sourceIconWeight(p, true) * BubbleLayerMotionSpec.surfaceOpacity(p))
            .roundToInt().coerceIn(0, 255)
        // 气泡盖住按钮的同时让按钮自己的涟漪同步退场，见 CoverableRippleDrawable。
        coverRipple(BubbleLayerMotionSpec.surfaceOpacity(p))
    }

    private fun coverRipple(opacity: Float) {
        (source.foreground as? CoverableRippleDrawable)?.coverOpacity = opacity
    }

    fun drawIcon(canvas: Canvas) {
        if (iconPaint.alpha == 0) return
        drawSnapshot(canvas, iconPaint)
    }

    /** 在按钮原位、面板形状内补画静止图标，见 [slotPaint]。 */
    fun drawSlotIcon(canvas: Canvas, surfaceShape: android.graphics.Path) {
        if (slotPaint.alpha == 0 || surfaceShape.isEmpty) return
        canvas.withSave {
            clipPath(surfaceShape)
            drawSnapshot(this, slotPaint, sourceBounds)
        }
    }

    /** 保留原图片透明轮廓；白色 SRC_IN 只替换 RGB，不把透明像素变成实心矩形。 */
    fun drawMask(canvas: Canvas) = drawSnapshot(canvas, maskPaint)

    fun copyDrawBounds(out: RectF) {
        if (hasSnapshot) out.set(drawBounds) else out.setEmpty()
    }

    fun settleExpanded() {
        restoreSource()
        iconPaint.alpha = 0
        slotPaint.alpha = 0
        // 打开态按钮被面板完全盖住；涟漪退场可能还没跑完，保持压住，收起时逐帧放开。
        if (tookOver) coverRipple(1f)
    }

    fun dispose() {
        // 幂等：updateFrame 的失败分支可能已经废弃过一次，之后图层还会再调一次。
        // 记账必须只减一次，否则最后一个持有者永远等不到写回。
        if (disposed) {
            restoreSource()
            return
        }
        disposed = true
        restoreSource()
        releaseRipple()
        iconPaint.alpha = 0
        slotPaint.alpha = 0
        bitmap = null
        root = null
        sourceBounds.setEmpty()
        drawBounds.setEmpty()
        SourceIconAlpha.release(source)
        // 不立即 recycle：RenderThread 可能仍持有前一帧 display list 中的 Bitmap 引用。
    }

    /** 只还原自己动过的东西；从未接管过来源的惰性代理不写 alpha。 */
    private fun restoreSource() {
        if (!tookOver) return
        source.alpha = originalAlpha
    }

    /** 废弃时把涟漪还给按钮；收起路径到这里时覆盖度已经归零，不会有可见变化。 */
    private fun releaseRipple() {
        if (tookOver) coverRipple(0f)
    }

    private fun drawSnapshot(canvas: Canvas, paint: Paint, bounds: RectF = drawBounds) {
        val image = bitmap ?: return
        if (disposed || !validBounds(bounds)) return
        canvas.withSave {
            translate(bounds.left, bounds.top)
            scale(bounds.width() / sourceWidth, bounds.height() / sourceHeight)
            clipRect(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
            // 捕获分辨率可降采样，但重放仍使用统一比例，不受 Bitmap 边长取整误差影响。
            scale(1f / captureScale, 1f / captureScale)
            drawBitmap(image, 0f, 0f, paint)
        }
    }

    private fun readSourceBounds(out: RectF): Boolean {
        val root = root ?: return false
        if (!source.isAttachedToWindow || !root.isAttachedToWindow || !source.isShown ||
            source.width != sourceWidth || source.height != sourceHeight ||
            !stableTransform(source) || !stableTransform(root)
        ) return false
        source.getLocationOnScreen(sourceLocation)
        root.getLocationOnScreen(rootLocation)
        val x = sourceLocation[0].toFloat() - rootLocation[0]
        val y = sourceLocation[1].toFloat() - rootLocation[1]
        out.set(x, y, x + sourceWidth, y + sourceHeight)
        return validBounds(out)
    }

    private fun validBounds(bounds: RectF): Boolean =
        bounds.left.isFinite() && bounds.top.isFinite() && bounds.right.isFinite() &&
            bounds.bottom.isFinite() && bounds.width().isFinite() && bounds.height().isFinite() &&
            bounds.centerX().isFinite() && bounds.centerY().isFinite() &&
            bounds.width() > 0f && bounds.height() > 0f

    private fun stableTransform(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.scaleX != 1f || current.scaleY != 1f || current.rotation != 0f ||
                current.rotationX != 0f || current.rotationY != 0f ||
                !current.translationX.isFinite() || !current.translationY.isFinite() ||
                (current !== view && current.alpha != 1f)
            ) return false
            current = current.parent as? View
        }
        return true
    }

    private companion object {
        const val MAX_CAPTURE_SIDE = 192
    }
}
