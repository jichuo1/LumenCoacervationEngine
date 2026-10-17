package com.lumen.coacervation.engine.morph

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.lumen.coacervation.engine.interaction.ElasticSpringAxis
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.max
import kotlin.math.min

/**
 * 液态形变磁贴：在外层固定 2 格宽的宿主里，让表面在 1 格 ↔ 2 格之间平滑形变。
 *
 * - 表面宽度、圆角、内容三条弹簧独立推进（[LumenMorphOptions]），宽度/圆角的响应与回弹错开时
 *   会出现“先挤出、圆角再追上”的液滴感；内容使用更快弹簧，形成领先/滞后。
 * - 圆角用连续曲率近似（[LumenMorphOptions.cornerSmoothing]），不是普通圆角矩形。
 * - 只负责自身几何与内容；不包裹 `LumenControlShadow` / 液态折射表面：
 *   动态半径会让预烘焙阴影纹理每帧失效，因此本控件不建阴影、不采样背景，只提供贴边高光与接触线。
 * - 点击切换展开/收起；`setProgress` 可供手势直接操纵；全局长按弹性应通过 `EXCLUDED_TAG` 排除本控件。
 *
 * 绘制/动画路径零分配：Path/Paint/渐变与三条弹簧轴均在构造或尺寸变化时准备。
 */
public class LumenMorphTile @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val contactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
    }
    private val path = Path()

    private val iconView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val labelView = TextView(context).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var palette: LumenPalette = LumenPalette.neutral(false)
    private var tuning: LumenEffectTuning = LumenEffectTuning.DEFAULT
    private var options: LumenMorphOptions = LumenMorphOptions.DEFAULT
    private var fillColor: Int = palette.primary
    private var contentColor: Int = palette.onPrimary

    private val widthAxis = ElasticSpringAxis(0f)
    private val cornerAxis = ElasticSpringAxis(0f)
    private val contentAxis = ElasticSpringAxis(0f)
    private var targetProgress = 0f
    private var expanded = false
    private var framePosted = false
    private var lastFrameNanos = 0L

    private var iconSize = 0f
    private var iconExpandedCenterX = 0f
    private var iconCollapsedCenterX = 0f
    private var shapeWidth = 0f
    private var edgeGradient: LinearGradient? = null
    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL

    private val frameCallback = Choreographer.FrameCallback { animateFrame(it) }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clipChildren = true
        addView(iconView, LayoutParams(dp(32f).toInt(), dp(32f).toInt()))
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener { toggle() }
    }

    /** 配置调色板、视效调参（贴边高光厚度/亮度）与形变参数；可反复调用。 */
    public fun configure(palette: LumenPalette, tuning: LumenEffectTuning, options: LumenMorphOptions) {
        this.palette = palette
        this.tuning = tuning
        this.options = options
        fillColor = palette.primary
        contentColor = palette.onPrimary
        applyColors()
        if (!options.enabled) snapTo(targetProgress)
        invalidate()
    }

    /** 覆盖表面填充色与内容色（默认取 palette.primary/onPrimary）。 */
    public fun setColors(fill: Int, content: Int) {
        fillColor = fill
        contentColor = content
        applyColors()
        invalidate()
    }

    /** 设置图标与文字；文字同时作为无障碍描述。 */
    public fun setContent(icon: Drawable?, label: CharSequence) {
        iconView.setImageDrawable(icon)
        iconView.imageTintList = ColorStateList.valueOf(contentColor)
        labelView.text = label
        contentDescription = label
        requestLayout()
    }

    /** 展开/收起；`animate=false` 或关闭形变时直接落到目标。 */
    public fun setExpanded(expanded: Boolean, animate: Boolean = true) {
        if (this.expanded == expanded) return
        this.expanded = expanded
        targetProgress = if (expanded) 1f else 0f
        if (!animate || !options.enabled || !ValueAnimator.areAnimatorsEnabled()) {
            snapTo(targetProgress)
            return
        }
        startFrames()
    }

    public fun toggle() {
        setExpanded(!expanded)
    }

    public val isExpanded: Boolean get() = expanded

    /** 手势直接操纵：0 = 收起，1 = 展开。会立即停止弹簧。 */
    public fun setProgress(progress: Float) {
        stopFrames()
        val p = LumenMorphPolicy.clamp01(progress)
        widthAxis.reset(p)
        cornerAxis.reset(p)
        contentAxis.reset(p)
        targetProgress = p
        expanded = p >= .5f
        applyProgress()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        iconSize = min(dp(36f), h - dp(24f)).coerceAtLeast(dp(20f))
        iconCollapsedCenterX = if (rtl) w - w * options.collapsedFraction / 2f
        else w * options.collapsedFraction / 2f
        iconExpandedCenterX = if (rtl) w - dp(24f) - iconSize / 2f
        else dp(24f) + iconSize / 2f
        iconView.pivotX = iconSize / 2f
        iconView.pivotY = iconSize / 2f
        edgeGradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(), Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        edgePaint.shader = edgeGradient
        applyProgress()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        iconCollapsedCenterX = if (rtl) width - width * options.collapsedFraction / 2f
        else width * options.collapsedFraction / 2f
        iconExpandedCenterX = if (rtl) width - dp(24f) - iconSize / 2f
        else dp(24f) + iconSize / 2f
        val iconLeft = if (rtl) (width - dp(24f) - iconSize).toInt() else dp(24f).toInt()
        val iconTop = ((height - iconSize) / 2f).toInt()
        iconView.layout(iconLeft, iconTop, iconLeft + iconSize.toInt(), iconTop + iconSize.toInt())
        val gap = dp(12f)
        val textLeft = if (rtl) (iconLeft - gap - labelView.measuredWidth).toInt()
        else (iconLeft + iconSize + gap).toInt()
        val textTop = ((height - labelView.measuredHeight) / 2f).toInt()
        labelView.layout(textLeft, textTop, textLeft + labelView.measuredWidth, textTop + labelView.measuredHeight)
        applyContent(contentAxis.value)
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (shapeWidth <= 0f) return
        val radius = LumenMorphPolicy.radiusPx(
            cornerAxis.value, options.collapsedRadiusDp * density, options.expandedRadiusDp * density)
        val clampedRadius = radius.coerceIn(0f, min(shapeWidth, height.toFloat()) / 2f)
        val shapeLeft = if (rtl) width - shapeWidth else 0f
        buildSmoothRect(path, shapeLeft, 0f, shapeLeft + shapeWidth, height.toFloat(),
            clampedRadius, options.cornerSmoothing)

        fillPaint.color = fillColor
        canvas.drawPath(path, fillPaint)

        if (options.edgeHighlight && tuning.edgeHighlightIntensity > 0f) {
            edgePaint.alpha = (255f * .26f * tuning.edgeHighlightIntensity)
                .toInt().coerceIn(0, 255)
            edgePaint.strokeWidth = max(1f, .65f * density * tuning.edgeHighlightWidth)
            canvas.drawPath(path, edgePaint)
        }
        if (options.contactLine && options.contactAlpha > 0f) {
            contactPaint.alpha = (options.contactAlpha * 255f).toInt().coerceIn(0, 255)
            contactPaint.strokeWidth = max(1f, .75f * density)
            canvas.drawPath(path, contactPaint)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 收起态右侧是空的宿主格：只把可见宽度内、纵向范围内的按下交给本控件。
        if (event.actionMasked == MotionEvent.ACTION_DOWN && shapeWidth > 0f) {
            val shapeLeft = if (rtl) width - shapeWidth else 0f
            if (event.x !in shapeLeft..(shapeLeft + shapeWidth) ||
                event.y !in 0f..height.toFloat()) return false
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(path)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (options.enabled && !atRest()) startFrames()
    }

    override fun onDetachedFromWindow() {
        stopFrames()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow && options.enabled && !atRest()) {
            startFrames()
        } else if (visibility != VISIBLE) {
            stopFrames()
        }
    }

    private fun applyColors() {
        val tint = ColorStateList.valueOf(contentColor)
        iconView.imageTintList = tint
        labelView.setTextColor(contentColor)
    }

    private fun startFrames() {
        if (framePosted) return
        framePosted = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrames() {
        if (!framePosted) return
        framePosted = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lastFrameNanos = 0L
    }

    private fun animateFrame(timeNanos: Long) {
        framePosted = false
        if (!isAttachedToWindow || visibility != VISIBLE) return
        val seconds = if (lastFrameNanos == 0L) 1f / 120f else
            ((timeNanos - lastFrameNanos).coerceAtLeast(0L) / 1_000_000_000.0).toFloat().coerceAtMost(.05f)
        lastFrameNanos = timeNanos
        widthAxis.advance(seconds, targetProgress,
            options.widthSpring.stiffness, options.widthSpring.dampingRatio)
        cornerAxis.advance(seconds, targetProgress,
            options.cornerSpring.stiffness, options.cornerSpring.dampingRatio)
        contentAxis.advance(seconds, targetProgress,
            options.contentSpring.stiffness, options.contentSpring.dampingRatio)
        applyProgress()
        invalidate()
        if (atRest()) {
            lastFrameNanos = 0L
        } else {
            framePosted = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun atRest(): Boolean =
        widthAxis.atRest(targetProgress, .002f) &&
            cornerAxis.atRest(targetProgress, .002f) &&
            contentAxis.atRest(targetProgress, .002f)

    private fun snapTo(target: Float) {
        stopFrames()
        widthAxis.reset(target)
        cornerAxis.reset(target)
        contentAxis.reset(target)
        targetProgress = target
        expanded = target >= .5f
        applyProgress()
        invalidate()
    }

    private fun applyProgress() {
        // 触摸命中也要用到当前可见宽度：在进度更新时同步，而不是等下一次 onDraw。
        shapeWidth = width * LumenMorphPolicy.widthFraction(
            widthAxis.value, options.collapsedFraction)
        applyContent(contentAxis.value)
        invalidate()
    }

    private fun applyContent(progress: Float) {
        val p = LumenMorphPolicy.clamp01(progress)
        val offset = LumenMorphPolicy.contentOffset(p, iconCollapsedCenterX, iconExpandedCenterX)
        iconView.translationX = offset
        labelView.translationX = offset
        val scale = 1f + (options.contentScale - 1f) * p
        iconView.scaleX = scale
        iconView.scaleY = scale
        labelView.alpha = LumenMorphPolicy.contentAlpha(p, options.fadeLabel)
    }

    /**
     * 连续曲率圆角：直边提前 `smoothing·r` 结束，三次贝塞尔控制点仍落在原圆角处，
     * 形成比普通圆角更柔和的过渡。smoothing 会被收进“短边一半 − r”，保证圆形时仍是圆。
     */
    private fun buildSmoothRect(
        path: Path, left: Float, top: Float, right: Float, bottom: Float,
        radius: Float, smoothing: Float
    ) {
        path.reset()
        val maxRadius = min(right - left, bottom - top) / 2f
        val r = radius.coerceIn(0f, maxRadius)
        if (r <= .01f) {
            path.addRect(left, top, right, bottom, Path.Direction.CW)
            return
        }
        val s = (r * smoothing.coerceIn(0f, .6f)).coerceAtMost(maxRadius - r)
        val k = .5522848f
        path.moveTo(left + r + s, top)
        path.lineTo(right - r - s, top)
        // top-right：水平切线起步、垂直切线收尾
        path.cubicTo(
            right - r - s + k * (r + s), top,
            right, top + r + s - k * (r + s),
            right, top + r + s)
        path.lineTo(right, bottom - r - s)
        // bottom-right
        path.cubicTo(
            right, bottom - r - s + k * (r + s),
            right - r - s + k * (r + s), bottom,
            right - r - s, bottom)
        path.lineTo(left + r + s, bottom)
        // bottom-left
        path.cubicTo(
            left + r + s - k * (r + s), bottom,
            left, bottom - r - s + k * (r + s),
            left, bottom - r - s)
        path.lineTo(left, top + r + s)
        // top-left
        path.cubicTo(
            left, top + r + s - k * (r + s),
            left + r + s - k * (r + s), top,
            left + r + s, top)
        path.close()
    }
}
