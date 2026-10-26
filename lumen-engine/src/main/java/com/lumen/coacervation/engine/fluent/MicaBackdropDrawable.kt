package com.lumen.coacervation.engine.fluent

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.AnimationUtils
import com.lumen.coacervation.engine.host.LumenMicaFallbackReason
import com.lumen.coacervation.engine.host.LumenMicaImage
import com.lumen.coacervation.engine.host.LumenMicaOptions
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.max
import kotlin.math.roundToInt

/** One opaque window foundation. No source capture, blur, bitmap allocation or shader creation in draw. */
internal class MicaBackdropDrawable : Drawable() {
    private val fill = Paint()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val rect = RectF()
    private val transition = FluentTransition(0f)
    private var options = LumenMicaOptions()
    private var recipe = MicaPolicy.resolve(options, false, 0)
    private var image: LumenMicaImage? = null
    private var material: Bitmap? = null
    private var shader: BitmapShader? = null
    private var released = false
    private var alphaValue = 255
    /** Material opacity: weight of the wallpaper material over the opaque base (MATERIAL_OPACITY.md §4.4). */
    var imageWeight = 1f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (next == field) return
            field = next
            invalidateSelf()
        }
    var draws = 0L
        private set
    var micaVisible = false
        private set
    val transitions: Long get() = transition.retargets
    val snapshotBytes: Int get() = (image?.byteCount ?: 0) + (material?.byteCount ?: 0)
    val hasMaterial: Boolean get() = shader != null

    fun configure(value: LumenMicaOptions, palette: LumenPalette, source: LumenMicaImage?) {
        if (released) return
        val next = MicaPolicy.resolve(value, luminance(palette.background) < 0.5, palette.background)
        val changed = image !== source || next.tint != recipe.tint ||
            next.luminosityTarget != recipe.luminosityTarget || next.luminosity != recipe.luminosity
        options = value
        recipe = next
        image = source
        if (changed) {
            // A decorative graphics failure must leave the window's opaque fallback usable.
            try {
                rebuildMaterial(source)
            } catch (_: OutOfMemoryError) {
                clearMaterial()
            } catch (_: RuntimeException) {
                clearMaterial()
            }
            onBoundsChange(bounds)
        }
        if (motionDisabled()) transition.snap(transition.target)
        invalidateSelf()
    }

    private fun rebuildMaterial(source: LumenMicaImage?) {
        material = source?.let {
            val bitmap = it.bitmap
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (i in pixels.indices) pixels[i] = MicaPolicy.materialPixel(pixels[i], recipe)
            Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        }
        shader = material?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        imagePaint.shader = shader
    }

    private fun clearMaterial() {
        material = null
        shader = null
        imagePaint.shader = null
    }

    fun setPolicy(value: LumenMicaFallbackReason) {
        if (released) return
        val target = if (value == LumenMicaFallbackReason.NONE && material != null) 1f else 0f
        if (motionDisabled() || options.highContrast || options.reduceTransparency) transition.snap(target)
        else transition.retarget(target, now(), MicaPolicy.TRANSITION_MS, eased = true)
        invalidateSelf()
    }

    fun releaseImage() {
        image = null
        material = null
        shader = null
        imagePaint.shader = null
        transition.snap(0f)
        micaVisible = false
        invalidateSelf()
    }

    fun release() {
        releaseImage()
        released = true
    }

    override fun draw(canvas: Canvas) {
        if (released || bounds.isEmpty) return
        draws++
        // Opaque at normal Drawable alpha, including the entire fallback transition.
        fill.color = withAlpha(recipe.fallback, alphaValue)
        canvas.drawRect(rect, fill)
        if (motionDisabled()) transition.snap(transition.target)
        val now = now()
        val weight = transition.value(now, eased = true)
        // The base stays opaque at any material opacity; only the wallpaper material thins out over it.
        val imageAlpha = (weight * alphaValue * imageWeight).roundToInt()
        micaVisible = weight > 0f && shader != null && imageAlpha > 0
        if (micaVisible) {
            imagePaint.alpha = imageAlpha
            canvas.drawRect(rect, imagePaint)
        }
        if (transition.isRunning(now)) invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        val bitmap = material ?: return
        if (bounds.isEmpty) return
        // Center-crop the single static snapshot; resizing never regenerates or recaptures it.
        val scale = max(rect.width() / bitmap.width, rect.height() / bitmap.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(rect.left + (rect.width() - bitmap.width * scale) / 2f,
            rect.top + (rect.height() - bitmap.height * scale) / 2f)
        shader?.setLocalMatrix(matrix)
    }

    private fun motionDisabled(): Boolean = options.reduceMotion || !ValueAnimator.areAnimatorsEnabled()
    private fun now(): Long = AnimationUtils.currentAnimationTimeMillis()

    override fun setAlpha(alpha: Int) { alphaValue = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha(): Int = alphaValue
    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        imagePaint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (alphaValue == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
}
