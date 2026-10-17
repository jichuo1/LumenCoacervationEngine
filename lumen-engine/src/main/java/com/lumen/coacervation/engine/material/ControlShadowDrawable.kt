package com.lumen.coacervation.engine.material

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.lumen.coacervation.engine.host.LumenControlShadowOptions
import kotlin.math.min

/**
 * 控件软阴影绘制器：投射层（缓存模糊位图，九宫格拉伸）+ 接触环（两道同心描边，全在控件轮廓之外，
 * 不会透过半透明表面读成脏边），再绘制被包裹的内容背景。
 *
 * 阴影画在 bounds 之外：宿主需保证祖先不裁剪子 View（`clipChildren=false`）或按
 * `LumenControlShadow.outsetPx` 预留间距。绘制路径稳态零分配（位图缓存在 [ControlShadowTextureCache]）。
 */
internal class ControlShadowDrawable(
    private val content: Drawable?,
    private val cornerRadiusPx: Float,
    private val density: Float,
    private val options: LumenControlShadowOptions,
    private val dark: Boolean
) : Drawable() {
    private val castPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val contactInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val contactOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val castFrame = RectF()
    private val ringRect = RectF()
    private val alphas = ControlShadowPolicy.adaptAlphas(
        options.opacity, options.contactOpacity, dark, options.adaptToTheme)
    private val spreadPx = options.spreadDp * density
    private val offsetYPx = options.offsetYDp * density

    init {
        val castColor = ColorUtils.setAlphaComponent(options.color, (alphas[0] * 255).toInt().coerceIn(0, 255))
        @Suppress("DEPRECATION")
        castPaint.colorFilter = PorterDuffColorFilter(castColor, PorterDuff.Mode.SRC_IN)
        val widths = ControlShadowPolicy.contactWidths(density)
        val ringAlphas = ControlShadowPolicy.contactAlphas(alphas[1])
        contactInner.strokeWidth = widths[0]
        contactInner.color = ColorUtils.setAlphaComponent(options.color, (ringAlphas[0] * 255).toInt().coerceIn(0, 255))
        contactOuter.strokeWidth = widths[1]
        contactOuter.color = ColorUtils.setAlphaComponent(options.color, (ringAlphas[1] * 255).toInt().coerceIn(0, 255))
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) {
            content?.draw(canvas)
            return
        }
        // setCallback 是 final：内容背景的回调在首帧惰性转发（恒等检查，稳态无写）。
        // ModernSurfaceDrawable 靠 `callback as? View` 注册采样，断了它柔光透镜会静默停采。
        val content = content
        if (content != null && content.callback !== callback) content.callback = callback
        val metrics = ControlShadowTextureCache.obtain(cornerRadiusPx + spreadPx.coerceAtLeast(0f),
            options.blurRadiusDp * density)
        if (metrics != null) {
            val texture = ControlShadowTextureCache.texture(metrics)
            if (texture != null) {
                val outset = metrics.padPx + spreadPx
                castFrame.set(bounds.left - outset, bounds.top - outset,
                    bounds.right + outset, bounds.bottom + outset)
                castFrame.offset(0f, offsetYPx)
                drawNineSlice(canvas, texture, metrics, castFrame)
            }
        }
        if (alphas[1] > 0f) {
            drawContactRing(canvas, contactOuter, contactInner.strokeWidth)
            drawContactRing(canvas, contactInner, 0f)
        }
        content?.draw(canvas)
    }

    /** 两道环各自整体外移，使描边内缘贴着上一道的外缘，整条环都在控件轮廓之外。 */
    private fun drawContactRing(canvas: Canvas, paint: Paint, innerOffset: Float) {
        val outset = (spreadPx * .5f + innerOffset + paint.strokeWidth * .5f)
            .coerceAtLeast(paint.strokeWidth * .5f)
        ringRect.set(bounds.left - outset, bounds.top - outset, bounds.right + outset, bounds.bottom + outset)
        val radius = (cornerRadiusPx + outset).coerceAtLeast(0f)
        canvas.drawRoundRect(ringRect, radius, radius, paint)
    }

    private fun drawNineSlice(canvas: Canvas, texture: android.graphics.Bitmap,
                              metrics: ControlShadowPolicy.TextureMetrics, frame: RectF) {
        val srcCorner = metrics.padPx + metrics.cornerPx
        val side = metrics.sidePx
        var dstCorner = (metrics.padPx + metrics.cornerPx + spreadPx).coerceAtLeast(1f)
        val maxCorner = min(frame.width(), frame.height()) * .5f
        if (dstCorner > maxCorner) dstCorner = maxCorner
        val xStops = floatArrayOf(frame.left, frame.left + dstCorner, frame.right - dstCorner, frame.right)
        val yStops = floatArrayOf(frame.top, frame.top + dstCorner, frame.bottom - dstCorner, frame.bottom)
        val srcStops = intArrayOf(0, srcCorner, side - srcCorner, side)
        for (row in 0..2) for (col in 0..2) {
            if (xStops[col + 1] <= xStops[col] || yStops[row + 1] <= yStops[row]) continue
            srcRect.set(srcStops[col], srcStops[row], srcStops[col + 1], srcStops[row + 1])
            dstRect.set(xStops[col].toInt(), yStops[row].toInt(), xStops[col + 1].toInt(), yStops[row + 1].toInt())
            canvas.drawBitmap(texture, srcRect, dstRect, castPaint)
        }
    }

    // ---------------- 内容转发：包裹层对 View 而言必须与裸背景等价 ----------------
    // （setCallback 是 final，回调转发在 draw 首帧完成，见上。）

    override fun onBoundsChange(bounds: Rect) {
        content?.bounds = bounds
    }

    override fun isStateful(): Boolean = content?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean {
        val content = content ?: return false
        return if (content.isStateful) content.setState(state) else false
    }

    override fun onLevelChange(level: Int): Boolean = content?.setLevel(level) == true

    override fun setHotspot(x: Float, y: Float) {
        content?.setHotspot(x, y)
    }

    override fun jumpToCurrentState() {
        content?.jumpToCurrentState()
    }

    override fun getOutline(outline: Outline) {
        val content = content
        if (content != null) content.getOutline(outline) else super.getOutline(outline)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        content?.setVisible(visible, restart)
        return super.setVisible(visible, restart)
    }

    override fun setAlpha(alpha: Int) {
        content?.alpha = alpha
    }

    override fun getAlpha(): Int = content?.alpha ?: 255

    override fun setColorFilter(colorFilter: ColorFilter?) {
        content?.colorFilter = colorFilter
    }

    override fun setTintList(tint: ColorStateList?) {
        content?.setTintList(tint)
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        content?.setTintMode(tintMode)
    }

    override fun getChangingConfigurations(): Int = content?.changingConfigurations ?: 0

    override fun getIntrinsicWidth(): Int = content?.intrinsicWidth ?: -1
    override fun getIntrinsicHeight(): Int = content?.intrinsicHeight ?: -1

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
