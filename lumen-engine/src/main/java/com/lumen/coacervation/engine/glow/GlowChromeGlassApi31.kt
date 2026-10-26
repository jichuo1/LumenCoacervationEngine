package com.lumen.coacervation.engine.glow

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import androidx.annotation.RequiresApi
import com.lumen.coacervation.engine.runtime.LumenGraphicsCounters
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮栏的**内容节点玻璃**骨架（2026-09-23 可读性改造 B/C 期，API 31+），两套材质共用。
 *
 * 原路径要么折射 PixelCopy 截屏（高级材质：至少滞后一帧，栏自己的位置在截图里被抑制遮罩换成
 * 稳定底图），要么在主线程录 Picture、后台软件模糊（柔光）。这里改成 BlurView/Haze 的
 * RenderNode 技法：
 *
 * - 内容容器（[GlowBackdropTarget]）把子 View 录进一个内容节点，屏幕上照常只画一次；
 * - 本类的背景节点在自己的 display list 里**引用**同一个内容节点（可选垫一层窗口底图），
 *   套上材质各自的 RenderEffect（[Effects]）。
 *
 * 内容滚动只改子节点，背景节点不重录也能拿到当帧内容：UI 线程零额外工作，没有截图滞后。
 * HWUI 对一帧里被第二个父节点访问到的节点按最大损伤处理（`RenderNode::prepareTreeImpl`），
 * 所以窗口任意一帧重绘都会让栏的效果层重算——[Effects] 必须按这个频率控制 GPU 开销。
 *
 * 节点比表面四周多出 [paddingPx]：折射会从表面外侧取样，外沿必须有真实内容。输出按表面圆角
 * 用 outline 裁剪。
 */
@RequiresApi(31)
internal class GlowChromeGlassApi31(
    val paddingPx: Int,
    private val effects: Effects
) : AutoCloseable {
    var counters: LumenGraphicsCounters? = null
    /** 按表面几何与光学强度生成效果；参数（量化后）没变时不会被调用。 */
    fun interface Effects {
        fun create(
            width: Int,
            height: Int,
            padding: Int,
            radiusPx: Float,
            opticalIntensity: Float,
            stretchDirY: Float
        ): RenderEffect

        /**
         * One of up to [MAX_VARIANTS] variants of the same chain (material-opacity blur levels, MATERIAL_OPACITY.md §13);
         * [DEFAULT_VARIANT] is the configured chain. Factories without variants ignore it.
         */
        fun create(
            width: Int,
            height: Int,
            padding: Int,
            radiusPx: Float,
            opticalIntensity: Float,
            stretchDirY: Float,
            variant: Int
        ): RenderEffect = create(width, height, padding, radiusPx, opticalIntensity, stretchDirY)
    }

    private val node = RenderNode("Lumen-GlowChromeGlass")
    private val outline = Outline()
    private val underlayRect = RectF()
    private var effectWidth = -1
    private var effectHeight = -1
    private var effectRadius = Float.NaN
    private var effectIntensity = Float.NaN
    private var effectDirection = Float.NaN
    private var effectVariant = DEFAULT_VARIANT
    /**
     * Chains (and their API 31 aliases) per variant for the current geometry. A material-opacity drag only switches
     * between them, so after one sweep it allocates nothing; a geometry change drops them. Null until a variant is used.
     */
    private var variantEffects: Array<RenderEffect?>? = null
    private var variantAliases: Array<RenderEffect?>? = null
    /** Tiny node that draws one variant at 1/255 alpha so its blur program is compiled before it is needed. */
    private var warmNode: RenderNode? = null
    /** 仅 API 31：当前效果与一个像素等价的另一个滤镜对象（[FilteredLayerRefresh]）。 */
    private var effect: RenderEffect? = null
    private var alias: RenderEffect? = null
    private var aliasActive = false
    private var closed = false

    /**
     * 在宿主画布上画一次玻璃。[bounds] 与 [radiusPx] 是表面几何（宿主局部坐标）；
     * [content] 是内容容器已录制的内容节点；[contentOffsetX]/[contentOffsetY] 是宿主原点在内容节点坐标中的位置。
     * [underlay] 按宿主局部坐标画在内容之下（高级材质的窗口底图）；null 表示内容透明处保持透明。
     */
    fun draw(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        content: GlowContentCaptureApi31,
        contentOffsetX: Float,
        contentOffsetY: Float,
        alpha: Float,
        opticalIntensity: Float,
        stretchDirY: Float,
        underlay: ((Canvas, RectF) -> Unit)?
    ) = draw(canvas, bounds, radiusPx, content, contentOffsetX, contentOffsetY,
        alpha, opticalIntensity, stretchDirY, underlay, null)

    /** 完整矩阵入口；保留原入口的尾随 underlay lambda 调用形式。 */
    fun draw(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        content: GlowContentCaptureApi31,
        contentOffsetX: Float,
        contentOffsetY: Float,
        alpha: Float,
        opticalIntensity: Float,
        stretchDirY: Float,
        underlay: ((Canvas, RectF) -> Unit)?,
        contentToHost: Matrix?
    ) = draw(canvas, bounds, radiusPx, content, contentOffsetX, contentOffsetY, alpha, opticalIntensity, stretchDirY,
        underlay, contentToHost, DEFAULT_VARIANT)

    /** As above, selecting effect [variant] (0 until [MAX_VARIANTS], or [DEFAULT_VARIANT]). */
    fun draw(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        content: GlowContentCaptureApi31,
        contentOffsetX: Float,
        contentOffsetY: Float,
        alpha: Float,
        opticalIntensity: Float,
        stretchDirY: Float,
        underlay: ((Canvas, RectF) -> Unit)?,
        contentToHost: Matrix?,
        variant: Int
    ) {
        check(!closed) { "Chrome glass is closed" }
        require(variant == DEFAULT_VARIANT || variant in 0 until MAX_VARIANTS) { "variant $variant" }
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return
        val pad = paddingPx
        node.setPosition(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad)
        val recording = node.beginRecording(width + 2 * pad, height + 2 * pad)
        try {
            // 节点局部坐标 → 宿主局部坐标。
            recording.translate((pad - bounds.left).toFloat(), (pad - bounds.top).toFloat())
            if (underlay != null) {
                underlayRect.set(
                    (bounds.left - pad).toFloat(), (bounds.top - pad).toFloat(),
                    (bounds.right + pad).toFloat(), (bounds.bottom + pad).toFloat()
                )
                underlay(recording, underlayRect)
            }
            if (contentToHost != null) recording.concat(contentToHost)
            else recording.translate(-contentOffsetX, -contentOffsetY)
            recording.drawRenderNode(content.node)
        } finally {
            node.endRecording()
        }
        counters?.proxyRecorded()
        // 每次重录都可能换了内容（新的内容节点、新的取样矩阵），API 31 必须换一个没缓存过的滤镜。
        if (!updateEffect(width, height, radiusPx, opticalIntensity, stretchDirY, variant)) swapEquivalentEffect()
        outline.setRoundRect(pad, pad, pad + width, pad + height, radiusPx)
        node.setOutline(outline)
        node.setClipToOutline(true)
        node.setAlpha(alpha.coerceIn(0f, 1f))
        canvas.drawRenderNode(node)
    }

    /**
     * RenderEffect 在创建时快照 shader uniform，参数变了只能换新对象。回弹强度按
     * [INTENSITY_STEP] 量化后比对，回弹拖动中不会逐帧新建。返回是否换了新对象。
     */
    private fun updateEffect(width: Int, height: Int, radiusPx: Float, opticalIntensity: Float, stretchDirY: Float,
        variant: Int): Boolean {
        val intensity = (opticalIntensity / INTENSITY_STEP).roundToInt() * INTENSITY_STEP
        val direction = stretchDirY.coerceIn(-1f, 1f)
        val sameGeometry = width == effectWidth && height == effectHeight && radiusPx == effectRadius &&
            abs(intensity - effectIntensity) < 1e-4f && direction == effectDirection
        if (sameGeometry && variant == effectVariant) return false
        if (!sameGeometry) {
            effectWidth = width
            effectHeight = height
            effectRadius = radiusPx
            effectIntensity = intensity
            effectDirection = direction
            // Cached variants were built for the previous geometry.
            variantEffects?.fill(null)
            variantAliases?.fill(null)
        }
        effectVariant = variant
        val slot = variant + 1
        val cached = variantEffects?.get(slot)
        val built = cached ?: effects.create(width, height, paddingPx, radiusPx, intensity, direction, variant)
        if (cached == null) counters?.effectBuilt()
        val refresh = FilteredLayerRefresh.required()
        effect = if (refresh) built else null
        // 整数零偏移是直通：输出与 built 逐像素相同，只是另一个滤镜对象。
        alias = if (refresh) variantAliases?.get(slot) ?: RenderEffect.createOffsetEffect(0f, 0f, built) else null
        if (variant != DEFAULT_VARIANT || variantEffects != null) remember(slot, built, alias)
        aliasActive = false
        node.setRenderEffect(built)
        return true
    }

    private fun remember(slot: Int, built: RenderEffect, alias: RenderEffect?) {
        val effects = variantEffects ?: arrayOfNulls<RenderEffect>(MAX_VARIANTS + 1).also { variantEffects = it }
        effects[slot] = built
        if (alias != null) {
            val aliases = variantAliases ?: arrayOfNulls<RenderEffect>(MAX_VARIANTS + 1).also { variantAliases = it }
            aliases[slot] = alias
        }
    }

    /** API 31 以外 [alias] 为 null，什么也不做。切换不计入效果构建次数。 */
    private fun swapEquivalentEffect() {
        val next = (if (aliasActive) effect else alias) ?: return
        aliasActive = !aliasActive
        node.setRenderEffect(next)
    }

    /**
     * Pre-compiles [variant]'s blur program (MATERIAL_OPACITY.md §14): records [content] into a [WARM_PX] node at
     * the centre of [bounds] with that variant's chain and draws it at 1/255 alpha — invisible, but rendered, so the
     * first real use of the level does not stall the RenderThread. The chain is not cached; nothing else changes.
     */
    fun drawWarm(canvas: Canvas, bounds: Rect, radiusPx: Float, content: GlowContentCaptureApi31, contentToHost: Matrix?,
        variant: Int) {
        if (closed || variant !in 0 until MAX_VARIANTS || bounds.width() < WARM_PX || bounds.height() < WARM_PX) return
        val node = warmNode ?: RenderNode("Lumen-GlowChromeWarm").also { warmNode = it }
        val left = bounds.centerX() - WARM_PX / 2
        val top = bounds.centerY() - WARM_PX / 2
        node.setPosition(left, top, left + WARM_PX, top + WARM_PX)
        val recording = node.beginRecording(WARM_PX, WARM_PX)
        try {
            recording.translate(-left.toFloat(), -top.toFloat())
            if (contentToHost != null) recording.concat(contentToHost)
            recording.drawRenderNode(content.node)
        } finally {
            node.endRecording()
        }
        node.setRenderEffect(effects.create(WARM_PX, WARM_PX, 0, minOf(radiusPx, WARM_PX / 2f), 1f, 0f, variant))
        node.setAlpha(WARM_ALPHA)
        canvas.drawRenderNode(node)
    }

    /** Warming finished: drop the warm node's display list and effect. */
    fun endWarm() {
        warmNode?.let { it.setRenderEffect(null); it.discardDisplayList() }
        warmNode = null
    }

    /** 内存压力：丢掉 display list（下次绘制重录），效果对象留着——它们只是参数快照。 */
    fun releaseDisplayList() {
        if (!closed) node.discardDisplayList()
    }

    override fun close() {
        if (closed) return
        closed = true
        node.setRenderEffect(null)
        effect = null
        alias = null
        variantEffects = null
        variantAliases = null
        endWarm()
        node.discardDisplayList()
    }

    companion object {
        private const val INTENSITY_STEP = 0.05f
        /** The configured chain, with no material-opacity variant. */
        const val DEFAULT_VARIANT = -1
        /** Upper bound on cached variants per glass (`MaterialOpacityPolicy.BLUR_LEVELS + 1` fits). */
        const val MAX_VARIANTS = 32
        private const val WARM_PX = 24
        private const val WARM_ALPHA = 1f / 255f
    }
}
