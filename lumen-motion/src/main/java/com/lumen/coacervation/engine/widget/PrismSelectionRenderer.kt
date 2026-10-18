package com.lumen.coacervation.engine.widget

import androidx.core.graphics.withTranslation
import androidx.core.graphics.withScale
import androidx.core.graphics.withSave
import androidx.core.graphics.createBitmap
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 底栏棱镜选中框的绘制与运动状态（由 [LumenNavigationBar] 持有，宿主不直接使用）。
 *
 * - **选中色副本**：把每一项的图标与标签按当前位置画进一张 ALPHA_8 遮罩（只在几何或内容变化时重建），
 *   选中框内用选中色画这张遮罩（API 33 时放大 + 边缘折射 + 色散），框外的真实图标被裁掉——
 *   与参考库"用强调色副本叠在选中胶囊里"的做法等价，且不重画栏本身的材质，与任何栏背景都兼容。
 *   遮罩用不透明、无阴影的画笔绘制：标签的可读性光晕与常规色的透明度不进入副本。
 * - **外阴影**：一张预先模糊好的胶囊 ALPHA_8 纹理，按选中框拉伸绘制，逐帧不做模糊。
 * - **运动**：X/Y 两根果冻弹簧在按压目标变化时重定目标；只有用户拖动或回弹时的横向速度参与挤压拉伸。
 *
 * 绘制路径不分配对象：遮罩、纹理、shader、渐变都在重建或配置时创建，逐帧只改矩阵与 uniform。
 * API 33 的 shader 在配置时编译，失败只关掉折射（退到放大 + 平移的位图副本），不影响页面。
 */
internal class PrismSelectionRenderer(private val density: Float) {
    var options: LumenPrismSelectionOptions = LumenPrismSelectionOptions.DEFAULT
        private set
    private var color = Color.BLACK

    // Selected-colour copy.
    private var mask: Bitmap? = null
    private val maskCanvas = Canvas()
    private var maskDirty = true
    private val barToMask = Matrix()
    private var shaderApi33: Any? = null
    private var shaderFailed = false
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var shaderBitmap: Bitmap? = null
    private val maskMatrix = Matrix()
    private var iconCopies: Array<Drawable?> = emptyArray()
    /** Geometry the mask was built from: item, icon and label bounds plus label scroll, per item. */
    private var signature = IntArray(0)

    // Pre-blurred shadow at the selection box's rest size.
    private var shadow: Bitmap? = null
    private var shadowPad = 0f
    private var shadowScale = 1f
    private val shadowDst = RectF()

    private val copyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerShader = LinearGradient(0f, 0f, 0f, 1f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        .also { innerPaint.shader = it }
    private val innerMatrix = Matrix()
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = NavigationBarPrismPolicy.HIGHLIGHT_WIDTH_DP * density
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.BLACK }

    /** Pill geometry in bar coordinates, written by [layoutPill]. */
    val pill = RectF()
    val pillPath = Path()
    private val rimRect = RectF()
    private var radius = 0f
    var progress = 0f
        private set

    // Motion state.
    private var springX = LumenSpring(1f, 1f, 0f, options.scaleXSpring.stiffness, options.scaleXSpring.dampingRatio)
    private var springY = LumenSpring(1f, 1f, 0f, options.scaleYSpring.stiffness, options.scaleYSpring.dampingRatio)
    private var springStart = 0L
    private var targetScale = 1f
    private var atRest = true
    var jellyX = 1f
        private set
    var jellyY = 1f
        private set
    private var jellyVelocityX = 0f
    private var jellyVelocityY = 0f
    var velocity = 0f
        private set
    private var lastPosition = Float.NaN
    private var lastTime = 0L

    val ready: Boolean get() = mask != null && !maskDirty

    fun configure(value: LumenPrismSelectionOptions, selectedColor: Int, pressTarget: Float) {
        val springsChanged = value.scaleXSpring != options.scaleXSpring || value.scaleYSpring != options.scaleYSpring
        options = value
        // Compile once, outside draw. A driver failure only switches refraction off (ENGINEERING_RULES §6).
        if (Build.VERSION.SDK_INT >= 33 && shaderApi33 == null && !shaderFailed) {
            shaderApi33 = try { PrismSelectionShaderApi33() } catch (error: RuntimeException) {
                shaderFailed = true
                Log.w(TAG, "prism selection shader unavailable", error)
                null
            }
            shaderBitmap = null
            maskDirty = true
        }
        setColor(selectedColor)
        retarget(NavigationBarPrismPolicy.lerp(1f, value.pressedScale, pressTarget.coerceIn(0f, 1f)), force = springsChanged)
    }

    fun setColor(selectedColor: Int) {
        color = selectedColor
        // The API 33 shader applies the colour (and its alpha) itself; the bitmap path uses the paint colour.
        copyPaint.color = if (shaderApi33 != null) Color.WHITE else selectedColor
        maskPaint.color = selectedColor
    }

    fun invalidateMask() { maskDirty = true }

    /**
     * Rebuilds the copy only when the geometry it was drawn from changed (positions, sizes, label scroll).
     * Returns true when it rebuilt. Called from the bar's draw pass, so a steady state allocates nothing.
     */
    fun prepare(width: Int, height: Int, items: List<View>, icons: List<ImageView>, labels: List<TextView>,
        restWidth: Int, restHeight: Int): Boolean {
        if (!maskDirty && mask != null && signatureMatches(width, height, items, icons, labels)) return false
        rebuildMask(width, height, items, icons, labels)
        rebuildShadow(restWidth, restHeight)
        return true
    }

    private fun signatureMatches(width: Int, height: Int, items: List<View>, icons: List<ImageView>, labels: List<TextView>): Boolean {
        if (signature.size != 2 + items.size * 8) return false
        if (signature[0] != width || signature[1] != height) return false
        var i = 2
        for (index in items.indices) {
            val item = items[index]
            val icon = icons.getOrNull(index)
            val label = labels.getOrNull(index)
            if (signature[i++] != item.left || signature[i++] != item.top) return false
            if (signature[i++] != (icon?.left ?: 0) || signature[i++] != (icon?.top ?: 0)) return false
            if (signature[i++] != (label?.left ?: 0) || signature[i++] != (label?.top ?: 0)) return false
            if (signature[i++] != (label?.scrollX ?: 0) || signature[i++] != (label?.width ?: 0)) return false
        }
        return true
    }

    private fun recordSignature(width: Int, height: Int, items: List<View>, icons: List<ImageView>, labels: List<TextView>) {
        val needed = 2 + items.size * 8
        if (signature.size != needed) signature = IntArray(needed)
        signature[0] = width; signature[1] = height
        var i = 2
        for (index in items.indices) {
            val item = items[index]
            val icon = icons.getOrNull(index)
            val label = labels.getOrNull(index)
            signature[i++] = item.left; signature[i++] = item.top
            signature[i++] = icon?.left ?: 0; signature[i++] = icon?.top ?: 0
            signature[i++] = label?.left ?: 0; signature[i++] = label?.top ?: 0
            signature[i++] = label?.scrollX ?: 0; signature[i++] = label?.width ?: 0
        }
    }

    /**
     * Draws drawable copies and text layouts only: the item Views themselves are never drawn here, so their
     * display lists and dirty flags are untouched. Paints are forced opaque and shadow-free for the copy and
     * restored exactly afterwards.
     */
    private fun rebuildMask(width: Int, height: Int, items: List<View>, icons: List<ImageView>, labels: List<TextView>) {
        maskDirty = false
        recordSignature(width, height, items, icons, labels)
        val scale = NavigationBarPrismPolicy.maskScale(width, height)
        if (scale <= 0f) { mask = null; return }
        val w = NavigationBarPrismPolicy.maskSize(width, scale)
        val h = NavigationBarPrismPolicy.maskSize(height, scale)
        val bitmap = mask?.takeIf { it.width == w && it.height == h }?.also { it.eraseColor(Color.TRANSPARENT) }
            // A display list recorded last frame may still reference the old mask: drop it, never recycle it.
            ?: createBitmap(w, h, Bitmap.Config.ALPHA_8).also { mask = it }
        if (iconCopies.size != items.size) iconCopies = arrayOfNulls(items.size)
        val canvas = maskCanvas
        canvas.setBitmap(bitmap)
        canvas.withScale(scale, scale) { drawCopies(this, items, icons, labels) }
        canvas.setBitmap(null)
        barToMask.setScale(1f / scale, 1f / scale)
        val shader = shaderApi33
        if (Build.VERSION.SDK_INT >= 33 && shader is PrismSelectionShaderApi33) {
            if (shaderBitmap !== bitmap) { shader.setMask(bitmap, barToMask); shaderBitmap = bitmap }
            shader.install(copyPaint)
        }
    }

    private fun drawCopies(canvas: Canvas, items: List<View>, icons: List<ImageView>, labels: List<TextView>) {
        for (i in items.indices) {
            val item = items[i]
            val icon = icons.getOrNull(i)
            val source = icon?.drawable
            if (icon != null && source != null) {
                val copy = iconCopies[i] ?: source.constantState?.newDrawable(icon.resources)?.mutate()?.also { iconCopies[i] = it }
                if (copy != null) {
                    // Coverage only: no tint, full alpha, the real icon's state and level.
                    copy.setTintList(null)
                    copy.alpha = 255
                    copy.state = source.state
                    copy.level = source.level
                    copy.bounds = source.bounds
                    canvas.withTranslation((item.left + icon.left + icon.paddingLeft).toFloat(), (item.top + icon.top + icon.paddingTop).toFloat()) {
                        concat(icon.imageMatrix)
                        copy.draw(this)
                    }
                }
            }
            val label = labels.getOrNull(i)
            val layout = label?.layout
            if (label != null && layout != null) {
                val paint = layout.paint
                val savedColor = paint.color
                val shadowRadius = label.shadowRadius
                paint.color = Color.BLACK
                if (shadowRadius > 0f) paint.clearShadowLayer()
                // Single-line centred labels place their text through their own scroll (TextView.bringTextIntoView).
                canvas.withTranslation((item.left + label.left + label.compoundPaddingLeft - label.scrollX).toFloat(),
                    (item.top + label.top + label.extendedPaddingTop - label.scrollY).toFloat()) { layout.draw(this) }
                paint.color = savedColor
                if (shadowRadius > 0f) paint.setShadowLayer(shadowRadius, label.shadowDx, label.shadowDy, label.shadowColor)
            }
        }
    }

    /** Pre-blurred capsule at the rest size; stretched to the pill each frame instead of blurring per frame. */
    private fun rebuildShadow(restWidth: Int, restHeight: Int) {
        if (restWidth <= 0 || restHeight <= 0) { shadow = null; return }
        val blur = NavigationBarPrismPolicy.SHADOW_RADIUS_DP * density
        val pad = ceil(NavigationBarPrismPolicy.shadowExtent(blur)).toInt()
        val fullW = restWidth + pad * 2
        val fullH = restHeight + pad * 2
        val scale = NavigationBarPrismPolicy.shadowScale(fullW, fullH)
        val w = NavigationBarPrismPolicy.maskSize(fullW, scale)
        val h = NavigationBarPrismPolicy.maskSize(fullH, scale)
        val bitmap = shadow?.takeIf { it.width == w && it.height == h }?.also { it.eraseColor(Color.TRANSPARENT) }
            ?: createBitmap(w, h, Bitmap.Config.ALPHA_8).also { shadow = it }
        val canvas = maskCanvas
        canvas.setBitmap(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            // Software canvas: blur masks work on every API level here.
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        val restRadius = minOf(restWidth, restHeight) / 2f
        canvas.withScale(scale, scale) {
            drawRoundRect(pad.toFloat(), pad.toFloat(), (pad + restWidth).toFloat(), (pad + restHeight).toFloat(),
                restRadius, restRadius, paint)
        }
        canvas.setBitmap(null)
        shadowPad = pad.toFloat()
        shadowScale = scale
    }

    /** Called when the bar's press target changes (down 1, tap 0.8, release 0). */
    fun setPressTarget(target: Float) {
        retarget(NavigationBarPrismPolicy.lerp(1f, options.pressedScale, target.coerceIn(0f, 1f)), force = false)
    }

    private fun retarget(scale: Float, force: Boolean) {
        if (!force && scale == targetScale) return
        val now = now()
        advanceSprings(now)
        targetScale = scale
        springX = LumenSpring(jellyX, scale, jellyVelocityX, options.scaleXSpring.stiffness, options.scaleXSpring.dampingRatio)
        springY = LumenSpring(jellyY, scale, jellyVelocityY, options.scaleYSpring.stiffness, options.scaleYSpring.dampingRatio)
        springStart = now
        atRest = false
    }

    private fun advanceSprings(now: Long) {
        if (atRest) return
        val seconds = (now - springStart).coerceAtLeast(0L) / 1000f
        jellyX = springX.value(seconds)
        jellyY = springY.value(seconds)
        jellyVelocityX = springX.velocity(seconds)
        jellyVelocityY = springY.velocity(seconds)
    }

    /**
     * Advances motion for this frame and lays out the pill over the selection View's rest box ([left], [top],
     * [width], [height] in bar coordinates, before scale). [logicalPosition] feeds the velocity only while
     * [trackVelocity] (the user owns the indicator: scrub or rebound); [still] snaps everything (animations off).
     */
    fun layoutPill(left: Float, top: Float, width: Float, height: Float, logicalPosition: Float, press: Float,
        count: Int, trackVelocity: Boolean, still: Boolean) {
        val now = now()
        if (still) snapToTarget() else advanceSprings(now)
        if (still) {
            velocity = 0f; lastPosition = logicalPosition; lastTime = now
        } else if (lastPosition.isNaN() || lastTime == 0L) {
            lastPosition = logicalPosition; lastTime = now
        } else if (now > lastTime) {
            val dt = (now - lastTime) / 1000f
            val target = if (trackVelocity) NavigationBarPrismPolicy.rangeVelocity((logicalPosition - lastPosition) / dt, count) else 0f
            velocity = NavigationBarPrismPolicy.smooth(velocity, target, dt)
            if (abs(velocity) < NavigationBarPrismPolicy.SETTLED_VELOCITY * 0.1f) velocity = 0f
            lastPosition = logicalPosition; lastTime = now
        }
        // Settled springs land exactly on target: no residual 1.0009 scale or sub-pixel offset.
        if (!atRest && NavigationBarPrismPolicy.settled(jellyX, jellyY, targetScale, 0f, jellyVelocityX, jellyVelocityY)) snapToTarget()
        progress = press.coerceIn(0f, 1f)
        val cx = left + width / 2f
        val cy = top + height / 2f
        val halfW = width * scaleX / 2f
        val halfH = height * scaleY / 2f
        pill.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        radius = minOf(halfW, halfH)
        pillPath.rewind()
        pillPath.addRoundRect(pill, radius, radius, Path.Direction.CW)
    }

    /** Scale the host's selection View shows, so its rest fill matches the pill. */
    val scaleX: Float get() = jellyX * NavigationBarPrismPolicy.squashX(velocity, options.velocityDeformation)
    val scaleY: Float get() = jellyY * NavigationBarPrismPolicy.squashY(velocity, options.velocityDeformation)

    /** True while springs or velocity still move; the bar keeps a frame source running until then. */
    fun needsFrames(): Boolean = !atRest || velocity != 0f

    fun draw(canvas: Canvas) {
        if (mask == null || pill.isEmpty) return
        val p = progress
        val options = options
        val shadowBitmap = shadow
        if (p > 0f && options.shadowOpacity > 0f && shadowBitmap != null) {
            shadowPaint.alpha = (options.shadowOpacity * p * 255f).roundToInt()
            val sx = pill.width() / (shadowBitmap.width / shadowScale - shadowPad * 2f)
            val sy = pill.height() / (shadowBitmap.height / shadowScale - shadowPad * 2f)
            val offset = NavigationBarPrismPolicy.SHADOW_OFFSET_DP * density
            shadowDst.set(pill.left - shadowPad * sx, pill.top - shadowPad * sy + offset,
                pill.right + shadowPad * sx, pill.bottom + shadowPad * sy + offset)
            // Outside the pill only: the shadow never darkens the pill's content.
            canvas.withSave {
                clipOutPath(pillPath)
                drawBitmap(shadowBitmap, null, shadowDst, shadowPaint)
            }
        }
        if (p > 0f && options.pressedWashOpacity > 0f) {
            washPaint.alpha = (options.pressedWashOpacity * p * 255f).roundToInt()
            canvas.drawRoundRect(pill, radius, radius, washPaint)
        }
        val magnification = NavigationBarPrismPolicy.lerp(1f, options.contentMagnification, p)
        val shader = shaderApi33
        if (Build.VERSION.SDK_INT >= 33 && shader is PrismSelectionShaderApi33) {
            shader.configure(pill.centerX(), pill.centerY(), pill.width() / 2f, pill.height() / 2f, radius,
                options.lensHeightDp * density * p, options.lensAmountDp * density * p, options.dispersion, magnification, color)
            canvas.drawRoundRect(pill, radius, radius, copyPaint)
        } else {
            // Below API 33: the alpha mask drawn with the paint colour, clipped to the pill. A drawBitmap of an
            // ALPHA_8 bitmap takes the paint colour on every pipeline; as a BitmapShader it does not on the
            // API 27 OpenGL renderer (CI smoke: the selected copy stayed uncoloured).
            val bitmap = mask ?: return
            maskMatrix.set(barToMask)
            maskMatrix.postScale(magnification, magnification, pill.centerX(), pill.centerY())
            canvas.withSave {
                clipPath(pillPath)
                concat(maskMatrix)
                drawBitmap(bitmap, 0f, 0f, maskPaint)
            }
        }
        if (p > 0f && options.innerShadowOpacity > 0f) {
            innerPaint.alpha = (options.innerShadowOpacity * p * 255f).roundToInt()
            innerMatrix.setScale(1f, (NavigationBarPrismPolicy.INNER_SHADOW_RADIUS_DP * density * p).coerceAtLeast(1f))
            innerMatrix.postTranslate(0f, pill.top)
            innerShader.setLocalMatrix(innerMatrix)
            canvas.drawRoundRect(pill, radius, radius, innerPaint)
        }
        if (p > 0f && options.highlightOpacity > 0f) {
            rimPaint.alpha = (options.highlightOpacity * p * 255f).roundToInt()
            val half = rimPaint.strokeWidth / 2f
            rimRect.set(pill.left + half, pill.top + half, pill.right - half, pill.bottom - half)
            val r = (radius - half).coerceAtLeast(0f)
            canvas.drawRoundRect(rimRect, r, r, rimPaint)
        }
    }

    /** Springs at their target, no wobble; allocation-free. */
    fun snapToTarget() {
        jellyX = targetScale; jellyY = targetScale; jellyVelocityX = 0f; jellyVelocityY = 0f
        atRest = true
    }

    fun resetMotion() {
        targetScale = 1f
        jellyX = 1f; jellyY = 1f; jellyVelocityX = 0f; jellyVelocityY = 0f
        atRest = true
        velocity = 0f; lastPosition = Float.NaN; lastTime = 0L
    }

    fun release() {
        mask = null
        shadow = null
        maskDirty = true
        shaderApi33 = null
        shaderBitmap = null
        copyPaint.shader = null
        iconCopies = emptyArray()
        signature = IntArray(0)
    }

    private fun now(): Long = AnimationUtils.currentAnimationTimeMillis()

    private companion object {
        const val TAG = "LumenNavigationBar"
    }
}
