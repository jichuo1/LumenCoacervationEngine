package com.lumen.coacervation.engine.prism

import androidx.core.graphics.withSave
import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.animation.AnimationUtils
import com.lumen.coacervation.engine.fluent.AcrylicAvailability
import com.lumen.coacervation.engine.fluent.FluentTransition
import com.lumen.coacervation.engine.fluent.luminance
import com.lumen.coacervation.engine.host.LumenPrismFallbackReason
import com.lumen.coacervation.engine.host.LumenPrismOptions
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.roundToInt

/**
 * 棱镜玻璃背景，自下而上：外阴影（轮廓之外）→ 采样背景（会话 GPU 节点已做 模糊 → 饱和度 → 折射）→
 * 表面色 → 回退淡入淡出 → 按压平铺光与径向光（加色）→ 内阴影 → 边缘高光（加色）。
 *
 * 本 Drawable 直接作为 View 背景（不包任何包装层），`invalidateSelf` / `scheduleSelf` 因此总能到达 View。
 * 绘制路径不分配对象、不建 shader：渐变与模糊遮罩在配置或尺寸变化时重建，径向光只改 localMatrix。
 * 动画只在按压或回退过渡进行中才续帧。
 */
internal class PrismGlassDrawable(private val density: Float, private val host: Host) : Drawable() {
    internal interface Host {
        val backdrop: LumenSurfaceBinding?
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val additive = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; xfermode = additive }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = additive }
    private val glowShader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
        floatArrayOf(0f, PrismPolicy.GLOW_SOLID_FRACTION, 1f), Shader.TileMode.CLAMP).also { glow.shader = it }
    private val glowMatrix = Matrix()
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; xfermode = additive }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadowRadiusPx = -1f
    private var highlightShader: Any? = null
    private val light = FloatArray(2)
    private val rect = RectF()
    private val clip = Path()

    private var options = LumenPrismOptions()
    var dark = false
        private set
    private var tint = 0
    private var fallbackColor = 0
    private var radius = 0f
    private var alphaValue = 255

    private var policy = LumenPrismFallbackReason.NONE
    private val availability = AcrylicAvailability()
    private val crossfade = FluentTransition(0f)
    private val press = FluentTransition(0f)
    private var pressed = false
    private var hotspotX = Float.NaN
    private var hotspotY = Float.NaN
    private var lastWeight = 0f
    var lastBackend = LumenSurfaceBackend.STATIC
        private set
    var draws = 0L
        private set
    private var released = false
    private var recheckPending = false
    private val recheck = Runnable { recheckPending = false; invalidateSelf() }

    val glassVisible: Boolean get() = lastWeight > 0f
    val fallbackReason: LumenPrismFallbackReason
        get() = if (policy == LumenPrismFallbackReason.NONE && !availability.available)
            LumenPrismFallbackReason.BACKDROP_UNAVAILABLE else policy

    fun configure(value: LumenPrismOptions, palette: LumenPalette) {
        if (released) return
        options = value
        // Same shade rule as Fluent, Mica and the Activity delegate: the page background decides.
        dark = luminance(palette.background) < 0.5
        tint = PrismPolicy.tint(value, dark)
        fallbackColor = PrismPolicy.fallback(value, dark)
        highlight.strokeWidth = value.highlight.widthDp * density * 2f
        configureShadow(value)
        PrismPolicy.lightDirection(value.highlight.angleDegrees, light)
        if (Build.VERSION.SDK_INT >= 33 && value.highlight.enabled && value.highlight.falloff > 0f) {
            val shader = highlightShader as? PrismHighlightApi33 ?: PrismHighlightApi33().also { highlightShader = it }
            shader.install(highlight)
        } else {
            highlightShader = null
            highlight.shader = null
        }
        onBoundsChange(bounds)
        invalidateSelf()
    }

    private fun configureShadow(value: LumenPrismOptions) {
        val options = value.shadow
        shadow.color = withAlpha(options.color, (options.opacity * 255f).roundToInt())
        val radius = options.radiusDp * density
        // The blur mask is rebuilt only when the radius changes, never per frame (ENGINEERING_RULES §5).
        if (Build.VERSION.SDK_INT >= 28 && radius != shadowRadiusPx) {
            shadowRadiusPx = radius
            shadow.maskFilter = if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
        }
    }

    fun setPolicy(reason: LumenPrismFallbackReason) {
        if (released || reason == policy) return
        policy = reason
        if (reason != LumenPrismFallbackReason.NONE) {
            availability.reset()
            unscheduleSelf(recheck); recheckPending = false
        }
        retargetCrossfade(now())
        invalidateSelf()
    }

    fun release() {
        if (released) return
        released = true
        unscheduleSelf(recheck); recheckPending = false
        highlightShader = null
        highlight.shader = null
        inner.shader = null
    }

    override fun draw(canvas: Canvas) {
        if (released || bounds.isEmpty) return
        draws++
        val now = now()
        val binding = if (policy == LumenPrismFallbackReason.NONE) host.backdrop else null
        drawShadow(canvas)
        val save = canvas.save()
        try {
            canvas.clipPath(clip)
            if (binding != null && !binding.isRecordingContent) {
                binding.draw(canvas)
                lastBackend = binding.lastBackend()
                if (availability.report(lastBackend != LumenSurfaceBackend.STATIC, now)) retargetCrossfade(now)
                val pending = availability.pendingLossIn(now)
                if (pending >= 0L && !recheckPending) {
                    recheckPending = true
                    unscheduleSelf(recheck)
                    scheduleSelf(recheck, SystemClock.uptimeMillis() + pending + 1L)
                }
            } else if (binding == null) {
                lastBackend = LumenSurfaceBackend.STATIC
            }
            val weight = crossfade.value(now, eased = true)
            lastWeight = weight
            // The glass layer keeps its full tint; only the opaque fallback fades over it.
            if (weight > 0f && alpha(tint) > 0) {
                fill.color = withAlpha(tint, alpha(tint) * alphaValue / 255)
                canvas.drawRect(rect, fill)
            }
            if (weight < 1f) {
                fill.color = withAlpha(fallbackColor, ((1f - weight) * alphaValue).roundToInt())
                canvas.drawRect(rect, fill)
            }
            drawPress(canvas, now)
            if (options.innerShadow.enabled && inner.shader != null) {
                inner.alpha = alphaValue
                canvas.drawRect(rect, inner)
            }
            if (options.highlight.enabled && options.highlight.widthDp > 0f && options.highlight.opacity > 0f) {
                val highlightAlpha = (options.highlight.opacity * alphaValue).roundToInt()
                if (highlight.shader == null) highlight.color = withAlpha(options.highlight.color, highlightAlpha)
                else highlight.alpha = highlightAlpha
                canvas.drawRoundRect(rect, radius, radius, highlight)
            }
        } finally {
            canvas.restoreToCount(save)
        }
        if (crossfade.isRunning(now) || press.isRunning(now)) invalidateSelf()
    }

    private fun drawShadow(canvas: Canvas) {
        val options = options.shadow
        if (!options.enabled || options.opacity <= 0f || Build.VERSION.SDK_INT < 28) return
        val shadowAlpha = (options.opacity * alphaValue).roundToInt()
        if (shadowAlpha <= 0) return
        shadow.alpha = shadowAlpha
        // Outside the glass only: the shadow never darkens what shows through it.
        canvas.withSave {
            clipOutPath(clip)
            translate(0f, options.offsetYDp * density)
            drawRoundRect(rect, radius, radius, shadow)
        }
    }

    private fun drawPress(canvas: Canvas, now: Long) {
        if (!options.press.enabled) return
        val progress = press.value(now, eased = true)
        if (progress <= 0f) return
        val washAlpha = (options.press.washOpacity * progress * alphaValue).roundToInt()
        if (washAlpha > 0) {
            wash.alpha = washAlpha
            canvas.drawRect(rect, wash)
        }
        val glowAlpha = (options.press.glowOpacity * progress * alphaValue).roundToInt()
        if (glowAlpha > 0) {
            val cx = if (hotspotX.isNaN()) rect.centerX() else hotspotX
            val cy = if (hotspotY.isNaN()) rect.centerY() else hotspotY
            val r = minOf(rect.width(), rect.height()) * PrismPolicy.GLOW_RADIUS_FACTOR
            glowMatrix.setScale(r, r)
            glowMatrix.postTranslate(cx, cy)
            glowShader.setLocalMatrix(glowMatrix)
            glow.alpha = glowAlpha
            canvas.drawRect(rect, glow)
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        radius = PrismPolicy.radiusPx(options, density, rect.width(), rect.height())
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val innerShadow = options.innerShadow
        inner.shader = if (innerShadow.enabled && innerShadow.radiusDp > 0f && innerShadow.opacity > 0f && !rect.isEmpty) {
            val top = withAlpha(innerShadow.color, (innerShadow.opacity * 255f).roundToInt())
            LinearGradient(0f, rect.top, 0f, rect.top + innerShadow.radiusDp * density,
                top, withAlpha(innerShadow.color, 0), Shader.TileMode.CLAMP)
        } else null
        val shader = highlightShader
        if (Build.VERSION.SDK_INT >= 33 && shader is PrismHighlightApi33) {
            // Opaque colour: opacity is the paint alpha, as upstream passes color.copy(alpha = 1f).
            shader.configure(rect.left, rect.top, rect.width(), rect.height(), radius,
                options.highlight.color or (0xFF shl 24), light[0], light[1], options.highlight.falloff)
        }
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        // A background swap unschedules our recheck; let the next draw arm it again.
        recheckPending = false
        return super.setVisible(visible, restart)
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        var nextPressed = false
        var enabled = false
        for (value in state) when (value) {
            android.R.attr.state_pressed -> nextPressed = true
            android.R.attr.state_enabled -> enabled = true
        }
        val next = nextPressed && enabled
        if (next == pressed) return false
        pressed = next
        val now = now()
        press.retarget(if (next) 1f else 0f, now,
            transitionMs(if (next) PrismPolicy.PRESS_IN_MS else PrismPolicy.PRESS_OUT_MS), eased = true)
        invalidateSelf()
        return true
    }

    override fun setHotspot(x: Float, y: Float) {
        hotspotX = x
        hotspotY = y
        if (pressed) invalidateSelf()
    }

    private fun retargetCrossfade(now: Long) {
        val target = if (policy == LumenPrismFallbackReason.NONE && availability.available) 1f else 0f
        crossfade.retarget(target, now, transitionMs(PrismPolicy.FALLBACK_TRANSITION_MS), eased = true)
    }

    private fun transitionMs(full: Long): Long =
        if (options.reduceMotion || !ValueAnimator.areAnimatorsEnabled()) 0L else full

    private fun now(): Long = AnimationUtils.currentAnimationTimeMillis()

    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) return
        outline.setRoundRect(bounds, radius)
        outline.alpha = alphaValue / 255f
    }

    override fun setAlpha(alpha: Int) {
        val value = alpha.coerceIn(0, 255)
        if (value == alphaValue) return
        alphaValue = value
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue

    override fun setColorFilter(colorFilter: ColorFilter?) {
        for (paint in arrayOf(fill, wash, glow, inner, highlight, shadow)) paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun alpha(color: Int): Int = color ushr 24
    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0xFFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
