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
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.lumen.coacervation.engine.host.LumenControlShadowOptions
import com.lumen.coacervation.engine.material.ControlShadowPolicy.Layer
import kotlin.math.abs
import kotlin.math.min

/**
 * 控件软阴影绘制器：在内容背景之下绘制环境层（宽幅）与贴边接触带（窄幅）两张连续剖面纹理。
 *
 * 剖面按距离场烘焙，只画在控件边界之外：内侧恒为 0，控件边缘保留自己的干净轮廓，
 * 多个控件叠加时不会互相压出暗边。绘制路径稳态零分配（位图与度量都在构造/缓存阶段准备好）。
 */
internal class ControlShadowDrawable(
    private val content: Drawable?,
    private val cornerRadiusPx: Float,
    private val density: Float,
    private val options: LumenControlShadowOptions,
    private val dark: Boolean
) : Drawable() {
    private val castPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val contactPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val castFrame = RectF()
    private val contactFrame = RectF()
    private val alphas = ControlShadowPolicy.adaptAlphas(
        options.opacity, options.contactOpacity, dark, options.adaptToTheme)
    private val spreadPx = options.spreadDp * density
    private val offsetYPx = options.offsetYDp * density
    // 度量在构造时算一次：绘制路径不再创建 TextureMetrics、换算纹理坐标或分配停靠点数组。
    private val castMetrics = ControlShadowPolicy.textureMetrics(
        Layer.CAST, cornerRadiusPx + spreadPx.coerceAtLeast(0f),
        options.blurRadiusDp * density, density, offsetYPx)
    private val contactMetrics = ControlShadowPolicy.textureMetrics(
        Layer.CONTACT, cornerRadiusPx + spreadPx.coerceAtLeast(0f), 0f, density)
    private val xStops = FloatArray(4)
    private val yStops = FloatArray(4)
    private val srcStops = IntArray(4)

    init {
        // 颜色只保留 RGB，强度由各层的 Paint.alpha 控制；SRC_IN 保留纹理的 alpha 剖面。
        val filter = PorterDuffColorFilter(
            ColorUtils.setAlphaComponent(options.color, 255), PorterDuff.Mode.SRC_IN)
        castPaint.colorFilter = filter
        contactPaint.colorFilter = filter
        castPaint.alpha = (alphas[0] * 255f).toInt().coerceIn(0, 255)
        contactPaint.alpha = (alphas[1] * 255f).toInt().coerceIn(0, 255)
    }

    private companion object {
        /** 检测到同级阴影会交叉时的投射强度倍率；保留一半以上，避免卡顿感变成\"无阴影\"。 */
        const val OVERLAP_SOFTEN_GAIN = 0.55f
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

        // 重叠处的强度：同级控件落在外扩区域内时降低本控件强度，避免交叉处叠加过黑。
        // 只在宿主显式开启时做一次同级 bounds 检测（零分配），默认仍是直接叠加。
        val gain = if (options.softenOverlap) {
            val view = callback as? View
            if (view != null && siblingShadowOverlaps(view)) OVERLAP_SOFTEN_GAIN else 1f
        } else 1f
        castPaint.alpha = (alphas[0] * 255f * gain).toInt().coerceIn(0, 255)
        contactPaint.alpha = (alphas[1] * 255f * gain).toInt().coerceIn(0, 255)

        // 阴影画在内容之下、只在边界之外；控件边缘保持自己的干净轮廓。
        val cast = if (castPaint.alpha > 0) ControlShadowTextureCache.obtain(castMetrics) else null
        if (cast != null) {
            val outset = castMetrics.padPx + spreadPx
            castFrame.set(bounds.left - outset, bounds.top - outset,
                bounds.right + outset, bounds.bottom + outset)
            // 不再整体下移纹理：偏移已烘焙成"底部强、顶部弱"的竖向增益，边界不会露出亮缝。
            drawNineSlice(canvas, cast, castMetrics, castFrame, castPaint)
        }

        val contact = if (contactPaint.alpha > 0) ControlShadowTextureCache.obtain(contactMetrics) else null
        if (contact != null) {
            val outset = contactMetrics.padPx + spreadPx
            contactFrame.set(bounds.left - outset, bounds.top - outset,
                bounds.right + outset, bounds.bottom + outset)
            // 接触带不随 offsetY 移动，始终贴着控件轮廓，避免顶部出现亮缝。
            drawNineSlice(canvas, contact, contactMetrics, contactFrame, contactPaint)
        }

        content?.draw(canvas)
    }

    /**
     * 同级近似重叠检测：任一可见兄弟控件与本控件的外扩阴影矩形相交，就认为阴影会叠加。
     * 只读 bounds、不分配；RecyclerView 可见行数量有限，单次绘制成本可忽略。
     */
    private fun siblingShadowOverlaps(view: View): Boolean {
        val parent = view.parent as? ViewGroup ?: return false
        val outset = castMetrics.padPx + spreadPx + abs(offsetYPx)
        val left = view.left - outset
        val top = view.top - outset
        val right = view.right + outset
        val bottom = view.bottom + outset
        for (i in 0 until parent.childCount) {
            val sibling = parent.getChildAt(i)
            if (sibling === view || sibling.visibility != View.VISIBLE) continue
            if (sibling.right > left && sibling.left < right &&
                sibling.bottom > top && sibling.top < bottom) return true
        }
        return false
    }

    /**
     * 九宫格：src 停靠点用纹理 px，dst 停靠点用逻辑 px。
     * 单元格内边界是圆角本身；内侧不画，不需要额外的内侧留白。
     */
    private fun drawNineSlice(
        canvas: Canvas,
        texture: android.graphics.Bitmap,
        metrics: ControlShadowPolicy.TextureMetrics,
        frame: RectF,
        paint: Paint
    ) {
        val srcCorner = metrics.texturePadPx + metrics.textureCornerPx
        val side = metrics.textureSidePx
        var dstCorner = (metrics.padPx + metrics.cornerPx + spreadPx).coerceAtLeast(1f)
        val maxCorner = min(frame.width(), frame.height()) * .5f
        if (dstCorner > maxCorner) dstCorner = maxCorner
        xStops[0] = frame.left; xStops[1] = frame.left + dstCorner
        xStops[2] = frame.right - dstCorner; xStops[3] = frame.right
        yStops[0] = frame.top; yStops[1] = frame.top + dstCorner
        yStops[2] = frame.bottom - dstCorner; yStops[3] = frame.bottom
        srcStops[0] = 0; srcStops[1] = srcCorner; srcStops[2] = side - srcCorner; srcStops[3] = side
        for (row in 0..2) for (col in 0..2) {
            if (xStops[col + 1] <= xStops[col] || yStops[row + 1] <= yStops[row]) continue
            srcRect.set(srcStops[col], srcStops[row], srcStops[col + 1], srcStops[row + 1])
            dstRect.set(xStops[col].toInt(), yStops[row].toInt(), xStops[col + 1].toInt(), yStops[row + 1].toInt())
            canvas.drawBitmap(texture, srcRect, dstRect, paint)
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
