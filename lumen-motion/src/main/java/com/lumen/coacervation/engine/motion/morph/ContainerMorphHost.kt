@file:Suppress("ReplaceWithViewOutlineProviderExtension")

package com.lumen.coacervation.engine.motion.morph

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lumen.coacervation.engine.liquid.LiquidMotionSurfaceFrameProvider
import com.lumen.coacervation.engine.motion.InterruptibleMotionPolicy
import com.lumen.coacervation.engine.motion.MotionRect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

public data class ContainerMorphGeometry(
    val collapsedBounds: MotionRect,
    val expandedBounds: MotionRect,
    val collapsedTitleBounds: MotionRect,
    val expandedTitleBounds: MotionRect,
    val collapsedTitleTextSizePx: Float,
    val expandedTitleTextSizePx: Float,
    val collapsedCornerRadiusPx: Float,
    val contentTravelPx: Float,
    val titleMotionEnabled: Boolean
)

public enum class ContainerMorphTitleMode {
    SOURCE_TITLE,
    CROSSFADE_FROM_PAGE_TITLE,
    HIDDEN
}

/**
 * 设置备份页整个 Activity 生命周期内唯一的动画宿主。
 *
 * 页面切换只替换 [pageClip] 的 child；形变 surface、圆角裁剪、标题副本和输入拦截层均保持，
 * 让预测式返回可以连续 seek，而不依赖不可 seek 的 framework/shared-element transition。
 */
@SuppressLint("ViewConstructor")
public class ContainerMorphHost(
    context: Context,
    private val collapsedSurfaceColor: Int,
    private val expandedSurfaceColor: Int,
    titleColor: Int,
    sourceTitle: CharSequence,
    private val surfaceHandoffExpansion: Float = 0.1f,
    private val collapsedStrokeColor: Int = Color.TRANSPARENT,
    private val collapsedStrokeWidthPx: Float = 0f
) : FrameLayout(context) {

    private val backdropClip = MotionClipFrameLayout(context)
    private val backdropRoot = View(context)
    private val surface = MorphSurfaceView(context)
    private val pageClip = MotionClipFrameLayout(context)
    private val transitionTitle = TextView(context).apply {
        text = sourceTitle
        textSize = 17f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(titleColor)
        setTypeface(typeface, Typeface.BOLD)
        isSingleLine = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.INVISIBLE
        pivotX = 0f
        pivotY = 0f
    }
    private var navigationBackTarget: View? = null
    private var pressedBackTarget: View? = null
    private var interactionBlocked = false
    private val backLocation = IntArray(2)
    private var shapedMotion = false
    private val inputBlocker = object : View(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    clearBackPress()
                    if (hitsNavigationBack(event)) {
                        pressedBackTarget = navigationBackTarget
                        pressedBackTarget?.isPressed = true
                    }
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount != 1 || !hitsNavigationBack(event)) clearBackPress()
                MotionEvent.ACTION_UP -> {
                    if (pressedBackTarget != null && pressedBackTarget === navigationBackTarget && hitsNavigationBack(event)) performClick()
                    clearBackPress()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clearBackPress()
            }
            return true // Never forward touches to moving business controls.
        }
        override fun performClick(): Boolean {
            super.performClick()
            pressedBackTarget?.takeIf { it === navigationBackTarget }?.performClick()
            return true
        }
    }.apply {
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    private var currentPage: View? = null
    private var currentToolbarTitle: TextView? = null
    private val motionFrame = ContainerMorphFrameBuffer()

    fun registerNavigationBack(view: View) {
        clearBackPress()
        navigationBackTarget = view
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) clearBackPress()
    }

    private fun clearBackPress() {
        pressedBackTarget?.isPressed = false
        pressedBackTarget = null
        if (!interactionBlocked) inputBlocker.visibility = View.GONE
    }

    private fun hitsNavigationBack(event: MotionEvent): Boolean {
        val target = navigationBackTarget ?: return false
        if (!target.isAttachedToWindow || !target.isShown || !target.isEnabled ||
            (currentPage?.alpha ?: 0f) <= 0.01f) return false
        if (shapedMotion && (event.x < motionFrame.left || event.x > motionFrame.right ||
            event.y < motionFrame.top || event.y > motionFrame.bottom)) return false
        target.getLocationOnScreen(backLocation)
        return event.rawX >= backLocation[0] && event.rawX < backLocation[0] + target.width &&
            event.rawY >= backLocation[1] && event.rawY < backLocation[1] + target.height
    }

    var expansion: Float = 1f
        private set

    public var onWindowSizeChangedDuringMotion: (() -> Unit)? = null

    /**
     * 正文被平移/缩放后回调。父层 translation 不会让子 View 重录，玻璃卡片会一直按录制时的
     * 屏幕位置采样背景；动画结束后等抑制解除整组重录才对齐，卡片内部颜色一跳（2026-09-24
     * 真机逐帧：两张卡片反向变色 4～5 级，背景不变）。宿主 Activity 接到皮肤的位移通知上。
     */
    public var onContentMoved: (() -> Unit)? = null

    private fun View.notifyIfMoved(beforeY: Float, beforeScaleX: Float, beforeScaleY: Float) {
        if (translationY != beforeY || scaleX != beforeScaleX || scaleY != beforeScaleY) {
            onContentMoved?.invoke()
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        backdropClip.addView(
            backdropRoot,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(backdropClip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(pageClip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(transitionTitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(inputBlocker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        if (oldWidth > 0 && oldHeight > 0 && expansion < 0.999f) {
            onWindowSizeChangedDuringMotion?.invoke()
            return
        }
        surface.setFrame(
            left = 0f,
            top = 0f,
            right = width.toFloat(),
            bottom = height.toFloat(),
            cornerRadiusPx = 0f,
            color = expandedSurfaceColor
        )
        if (expansion >= 0.999f) backdropClip.clearMotionOutline()
    }

    /** 皮肤背景绑定到该层；其父容器始终跟随形变 surface 裁剪。 */
    fun liquidBackdropRoot(): View = backdropRoot

    /** 在 setContentView 后接管默认的根 padding，让系统栏区域也由同一背景绘制。 */
    fun installContentInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // 只缩进内容；背景、形变轮廓和标题代理保持全窗口坐标。
            setPadding(0, 0, 0, 0)
            pageClip.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    /** Both skin renderers read this View's live motion bounds, radius and fallback color. */
    fun setMotionSurfaceBackground(background: Drawable?) {
        surface.background = background
    }

    fun replacePage(page: View, toolbarTitle: TextView) {
        currentToolbarTitle?.alpha = 1f
        pageClip.removeAllViews()
        pageClip.addView(
            page,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        currentPage = page
        currentToolbarTitle = toolbarTitle
        // 飞行标题的字形跟随目标页标题（来源工程写死粗体，目标页换字体、字距时两端对不上，§15.4）；
        // 字号在每帧按两端插值，颜色保持来源条目的颜色。
        transitionTitle.typeface = toolbarTitle.typeface
        transitionTitle.letterSpacing = toolbarTitle.letterSpacing
        transitionTitle.fontFeatureSettings = toolbarTitle.fontFeatureSettings
        // 字体内边距决定文字在 View 里的竖向位置；两端按文字左上角对齐，这一项也要一致。
        transitionTitle.includeFontPadding = toolbarTitle.includeFontPadding
        showExpandedImmediately()
    }

    public fun prepareFirstFrameForEntry() {
        backdropClip.visibility = View.INVISIBLE
        surface.visibility = View.INVISIBLE
        currentPage?.alpha = 0f
        currentToolbarTitle?.alpha = 0f
        transitionTitle.visibility = View.INVISIBLE
        blockInteraction(true)
    }

    public fun beginMotion() {
        backdropClip.visibility = View.VISIBLE
        transitionTitle.visibility = View.VISIBLE
        blockInteraction(true)
    }

    public fun applyExpansion(
        geometry: ContainerMorphGeometry,
        value: Float,
        titleMode: ContainerMorphTitleMode,
        contentTiming: ContainerMorphContentTiming
    ) {
        shapedMotion = true
        val clamped = value.coerceIn(0f, 1f)
        expansion = clamped
        ContainerMorphSpec.fillFrame(
            out = motionFrame,
            expansion = clamped,
            collapsedBounds = geometry.collapsedBounds,
            expandedBounds = geometry.expandedBounds,
            collapsedTitleBounds = geometry.collapsedTitleBounds,
            expandedTitleBounds = geometry.expandedTitleBounds,
            collapsedTitleTextSizePx = geometry.collapsedTitleTextSizePx,
            expandedTitleTextSizePx = geometry.expandedTitleTextSizePx,
            collapsedCornerRadiusPx = geometry.collapsedCornerRadiusPx,
            contentTravelPx = geometry.contentTravelPx,
            contentTiming = contentTiming
        )

        if (surface.visibility != View.VISIBLE) surface.visibility = View.VISIBLE
        if (backdropClip.visibility != View.VISIBLE) backdropClip.visibility = View.VISIBLE
        backdropClip.alpha = motionFrame.surfaceAlpha
        val collapsedChromeFraction = ContainerMorphSpec.collapsedChromeFraction(clamped)
        surface.setFrame(
            left = motionFrame.left,
            top = motionFrame.top,
            right = motionFrame.right,
            bottom = motionFrame.bottom,
            cornerRadiusPx = motionFrame.cornerRadiusPx,
            color = ColorUtils.blendARGB(
                collapsedSurfaceColor,
                expandedSurfaceColor,
                clamped
            ),
            strokeColor = collapsedStrokeColor.withMultipliedAlpha(
                collapsedChromeFraction
            ),
            strokeWidthPx = collapsedStrokeWidthPx * collapsedChromeFraction
        )
        surface.alpha = ContainerMorphSpec.transitionSurfaceAlpha(
            expansion = clamped,
            handoffExpansion = surfaceHandoffExpansion
        )
        backdropClip.setMotionOutline(
            motionFrame.left,
            motionFrame.top,
            motionFrame.right,
            motionFrame.bottom,
            motionFrame.cornerRadiusPx
        )
        pageClip.setMotionOutline(
            motionFrame.left,
            motionFrame.top,
            motionFrame.right,
            motionFrame.bottom,
            motionFrame.cornerRadiusPx
        )

        currentPage?.apply {
            val beforeY = translationY
            alpha = motionFrame.contentAlpha
            // 正文钉在形变框顶部随框移动：框里始终是页面顶部的连续画面，而不是被缩小的
            // 轮廓从页面中段裁出的一截（2026-09-24 用户报告"中间两个画面割断"）。只平移不缩放，
            // 玻璃卡片按屏幕位置采样背景，平移后仍与背景对齐。
            translationY = motionFrame.contentTranslationYPx +
                (motionFrame.top - geometry.expandedBounds.top)
            notifyIfMoved(beforeY, scaleX, scaleY)
        }

        val sourceTakeoverAlpha = ContainerMorphSpec.smoothStep(0.02f, 0.14f, clamped)
        val titleAlpha = when (titleMode) {
            ContainerMorphTitleMode.SOURCE_TITLE -> sourceTakeoverAlpha
            ContainerMorphTitleMode.CROSSFADE_FROM_PAGE_TITLE -> {
                val exitProgress = 1f - clamped
                sourceTakeoverAlpha * ContainerMorphSpec.smoothStep(0f, 0.35f, exitProgress)
            }
            ContainerMorphTitleMode.HIDDEN -> 0f
        }
        val toolbarAlpha = when (titleMode) {
            ContainerMorphTitleMode.SOURCE_TITLE -> 0f
            ContainerMorphTitleMode.CROSSFADE_FROM_PAGE_TITLE -> 1f - titleAlpha
            ContainerMorphTitleMode.HIDDEN -> 1f
        }
        currentToolbarTitle?.alpha = toolbarAlpha

        transitionTitle.apply {
            val targetVisibility = if (titleMode == ContainerMorphTitleMode.HIDDEN) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
            if (visibility != targetVisibility) visibility = targetVisibility
            x = motionFrame.titleX
            y = motionFrame.titleY
            val baseTextSize = geometry.expandedTitleTextSizePx.coerceAtLeast(1f)
            if (abs(textSize - baseTextSize) > 0.5f) {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseTextSize)
            }
            val titleScale = motionFrame.titleTextSizePx / baseTextSize
            scaleX = titleScale
            scaleY = titleScale
            alpha = titleAlpha
        }
    }

    /** 来源坐标不再可靠时的无方向退化动画，不使用过期矩形。 */
    public fun applyFallbackExpansion(
        value: Float,
        contentTravelPx: Float,
        contentTiming: ContainerMorphContentTiming
    ) {
        shapedMotion = false
        val clamped = value.coerceIn(0f, 1f)
        val contentFraction = if (contentTiming == ContainerMorphContentTiming.PREDICTIVE) {
            ContainerMorphSpec.contentFraction(clamped, contentTiming)
        } else {
            clamped
        }
        expansion = clamped
        if (surface.visibility != View.VISIBLE) surface.visibility = View.VISIBLE
        if (backdropClip.visibility != View.VISIBLE) backdropClip.visibility = View.VISIBLE
        backdropClip.alpha = clamped
        backdropClip.setMotionOutline(0f, 0f, width.toFloat(), height.toFloat(), 0f)
        surface.setFrame(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            0f,
            expandedSurfaceColor
        )
        surface.alpha = clamped
        pageClip.clearMotionOutline()
        currentPage?.apply {
            val beforeY = translationY
            val beforeScaleX = scaleX
            val beforeScaleY = scaleY
            alpha = contentFraction
            translationY = (1f - contentFraction) * contentTravelPx
            pivotX = width / 2f
            pivotY = height / 2f
            scaleX = 0.985f + 0.015f * clamped
            scaleY = 0.985f + 0.015f * clamped
            notifyIfMoved(beforeY, beforeScaleX, beforeScaleY)
        }
        currentToolbarTitle?.alpha = 1f
        transitionTitle.visibility = View.INVISIBLE
    }

    public fun showExpandedImmediately() {
        shapedMotion = false
        expansion = 1f
        backdropClip.visibility = View.VISIBLE
        backdropClip.alpha = 1f
        backdropClip.clearMotionOutline()
        surface.visibility = View.VISIBLE
        if (width > 0 && height > 0) {
            surface.setFrame(
                left = 0f,
                top = 0f,
                right = width.toFloat(),
                bottom = height.toFloat(),
                cornerRadiusPx = 0f,
                color = expandedSurfaceColor
            )
        }
        surface.alpha = 1f
        pageClip.clearMotionOutline()
        currentPage?.apply {
            val beforeY = translationY
            val beforeScaleX = scaleX
            val beforeScaleY = scaleY
            alpha = 1f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            notifyIfMoved(beforeY, beforeScaleX, beforeScaleY)
        }
        currentToolbarTitle?.alpha = 1f
        transitionTitle.apply {
            alpha = 0f
            scaleX = 1f
            scaleY = 1f
            visibility = View.INVISIBLE
        }
        blockInteraction(false)
    }

    public fun blockInteraction(blocked: Boolean) {
        interactionBlocked = blocked
        // Keep ownership of an existing back-button press across the final expansion frame.
        inputBlocker.visibility = if (InterruptibleMotionPolicy.keepInputBlocked(blocked, pressedBackTarget != null))
            View.VISIBLE else View.GONE
        pageClip.importantForAccessibility = if (blocked) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    private fun Int.withMultipliedAlpha(fraction: Float): Int = ColorUtils.setAlphaComponent(
        this,
        (Color.alpha(this) * fraction.coerceIn(0f, 1f)).roundToInt()
    )

    private class MorphSurfaceView(context: Context) :
        View(context),
        LiquidMotionSurfaceFrameProvider {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val bounds = RectF()
        private val strokeBounds = RectF()
        private var cornerRadiusPx = 0f

        fun setFrame(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            cornerRadiusPx: Float,
            color: Int,
            strokeColor: Int = Color.TRANSPARENT,
            strokeWidthPx: Float = 0f
        ) {
            val normalizedRadius = cornerRadiusPx.coerceAtLeast(0f)
            val normalizedStrokeWidth = strokeWidthPx.coerceAtLeast(0f)
            if (bounds.left == left && bounds.top == top &&
                bounds.right == right && bounds.bottom == bottom &&
                this.cornerRadiusPx == normalizedRadius && paint.color == color &&
                strokePaint.color == strokeColor &&
                strokePaint.strokeWidth == normalizedStrokeWidth
            ) {
                return
            }
            this.bounds.set(left, top, right, bottom)
            this.cornerRadiusPx = normalizedRadius
            paint.color = color
            strokePaint.color = strokeColor
            strokePaint.strokeWidth = normalizedStrokeWidth
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (background == null) {
                canvas.drawRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, paint)
            }
            if (strokePaint.strokeWidth > 0f && Color.alpha(strokePaint.color) > 0) {
                val halfStroke = strokePaint.strokeWidth / 2f
                strokeBounds.set(bounds)
                strokeBounds.inset(halfStroke, halfStroke)
                val strokeRadius = (cornerRadiusPx - halfStroke).coerceAtLeast(0f)
                canvas.drawRoundRect(strokeBounds, strokeRadius, strokeRadius, strokePaint)
            }
        }

        override fun copyLiquidMotionBounds(outBounds: RectF) {
            outBounds.set(bounds)
        }

        override fun liquidMotionCornerRadiusPx(): Float = cornerRadiusPx

        override fun liquidMotionFallbackColor(): Int = paint.color
    }

    private class MotionClipFrameLayout(context: Context) : FrameLayout(context) {
        private val motionBounds = RectF()
        private var motionRadius = 0f

        init {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (motionBounds.isEmpty) {
                        outline.setRect(0, 0, view.width, view.height)
                    } else {
                        outline.setRoundRect(
                            floor(motionBounds.left).toInt(),
                            floor(motionBounds.top).toInt(),
                            ceil(motionBounds.right).toInt(),
                            ceil(motionBounds.bottom).toInt(),
                            motionRadius
                        )
                    }
                }
            }
        }

        fun setMotionOutline(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusPx: Float
        ) {
            val normalizedRadius = radiusPx.coerceAtLeast(0f)
            if (clipToOutline && motionBounds.left == left && motionBounds.top == top &&
                motionBounds.right == right && motionBounds.bottom == bottom &&
                motionRadius == normalizedRadius
            ) {
                return
            }
            motionBounds.set(left, top, right, bottom)
            motionRadius = normalizedRadius
            clipToOutline = true
            invalidateOutline()
        }

        fun clearMotionOutline() {
            clipToOutline = false
            motionBounds.setEmpty()
            motionRadius = 0f
        }
    }
}
