package com.lumen.coacervation.engine.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.motion.SlidingSelectionHighlight
import com.lumen.coacervation.engine.motion.SlidingSelectionMotion
import kotlin.math.roundToInt

/**
 * 单选组的"选中框连贯动画"（适配标准 §13.11）：选中框是一块独立的表面，点新选项时它从旧位置连续滑到新位置，
 * 位置与尺寸一起插值；动画中途再点别的选项，从当前位置与速度续接。
 *
 * 移植自来源工程 JEV 灵敏度面板（`createSlidingChoice`），相对来源的变化：
 * - 选中框用 [View.layout] 直接摆放，**逐帧不 requestLayout**（来源每帧改 `layoutParams.height`，整棵树逐帧重新布局）；
 * - 中途改目标保留速度（来源从零速度重新起步），见 [SlidingSelectionMotion]；
 * - 选中框按整块矩形滑动：竖向列表、横向分段、换行都适用（来源只做竖向）；
 * - 每帧调用 [notifyPositionChanged]，高级材质的玻璃采样跟着选中框走（适配标准 §4）；
 * - 选中的行被长按拖动形变时，选中框同步跟随行的位移与缩放，不会留在原地；
 * - 标题高亮按"选中框盖住这一行多少"连续计算（[setOnHighlightListener]）：来源只渐变新旧两行，连点三个选项时
 *   第一行会停在半高亮，中途改向时旧行颜色也会跳一下；按覆盖比例算天然连续，长距离滑动时途经的行也会被扫亮。
 *
 * 用法：
 * ```
 * val choice = LumenSlidingSelection(context, lumen.selectionBackground(radiusDp = 14f),
 *     notifyPositionChanged = lumen::notifyPositionChanged)
 * labels.forEach { choice.addOption(createRow(it)) }
 * choice.onSelect = { index -> save(index) }
 * choice.setOnHighlightListener { index, weight -> titleOf(index).setTextColor(blend(normal, accent, weight)) }
 * choice.select(savedIndex, animate = false)
 * ```
 *
 * 选项由宿主构造；本控件接管选项的点击（选中后回调 [onSelect]），并维护 `isSelected` 供无障碍朗读。
 *
 * @param indicatorBackground 选中框的表面，通常是 `lumen.selectionBackground(...)`。圆角应与选项的涟漪一致。
 * @param orientation 选项排列方向：[LinearLayout.VERTICAL]（默认）或 [LinearLayout.HORIZONTAL]。
 */
@MainThread
public class LumenSlidingSelection @JvmOverloads constructor(
    context: Context,
    indicatorBackground: Drawable,
    orientation: Int = LinearLayout.VERTICAL,
    private val notifyPositionChanged: () -> Unit = {}
) : FrameLayout(context) {

    /** 选中框本身。只承载背景，不可点击；宿主不要改它的布局参数。 */
    public val indicator: View = View(context).apply {
        background = indicatorBackground
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 选项容器。用 [addOption] 添加选项。 */
    public val options: LinearLayout = LinearLayout(context).apply { this.orientation = orientation }

    /** 用户点选了一个**不同的**选项时回调（程序调用 [select] 不回调）。 */
    public var onSelect: ((index: Int) -> Unit)? = null

    /**
     * 各选项的高亮程度回调。用原始类型参数的接口而不是 `(Int, Float) -> Unit`：
     * 它在滑动的每一帧、每个选项上都会调用，函数类型会逐次装箱 Float，违反逐帧零分配（ENGINEERING_RULES §5）。
     */
    public fun interface OnHighlightListener {
        /** [weight] 是选中框此刻盖住第 [index] 个选项的比例（沿排列方向，0..1）。 */
        public fun onHighlight(index: Int, weight: Float)
    }

    private var highlightListener: OnHighlightListener? = null
    public fun interface OnGeometryListener {
        public fun onGeometry(left: Float,top: Float,right: Float,bottom: Float,targetLeft: Float,targetTop: Float,targetRight: Float,targetBottom: Float,moving: Boolean)
    }
    private var geometryListener: OnGeometryListener?=null
    public fun getOnGeometryListener(): OnGeometryListener? = geometryListener
    public fun setOnGeometryListener(listener: OnGeometryListener?) {geometryListener=listener;reportGeometry()}
    private fun reportGeometry(){
        if(!hasRendered||selectedIndex<0||geometryListener==null)return
        if(!readTarget(selectedIndex,scratch))return
        geometryListener?.onGeometry(rendered[0],rendered[1],rendered[2],rendered[3],scratch[0],scratch[1],scratch[2],scratch[3],animator?.isRunning==true)
    }

    /**
     * 设置高亮回调：只在某一项的值变化时回调，宿主用它渐变标题颜色等。设置时立即按当前状态回调一遍全部选项。
     */
    public fun setOnHighlightListener(listener: OnHighlightListener?) {
        highlightListener = listener
        weights.fill(Float.NaN)
        updateHighlights()
        reportGeometry()
    }

    /** 当前选中项；没有选项时为 -1。 */
    public var selectedIndex: Int = -1
        private set

    private val motion = SlidingSelectionMotion()
    private val rendered = FloatArray(4)
    private val velocity = FloatArray(4)
    private val scratch = FloatArray(4)
    private var hasRendered = false
    private var animator: ValueAnimator? = null
    private var fraction = 1f
    /** 各选项上次回调的高亮值；只在增删选项时重建。NaN 表示尚未回调。 */
    private var weights = FloatArray(0)
    private var observedTree: ViewTreeObserver? = null

    private val followSelectedRow = ViewTreeObserver.OnPreDrawListener {
        syncWithSelectedRowTransform()
        true
    }

    init {
        clipChildren = false
        clipToPadding = false
        addView(indicator, LayoutParams(0, 0))
        addView(options, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** 追加一个选项；它的点击由本控件接管。 */
    @JvmOverloads
    public fun addOption(
        option: View,
        params: LinearLayout.LayoutParams = defaultOptionParams()
    ) {
        val index = options.childCount
        option.isClickable = true
        option.setOnClickListener {
            if (index != selectedIndex) {
                select(index, animate = true)
                onSelect?.invoke(index)
            }
        }
        options.addView(option, params)
        weights = FloatArray(options.childCount) { Float.NaN }
        if (selectedIndex < 0) select(0, animate = false)
    }

    /**
     * 选中 [index]。`animate = true` 时选中框从当前位置滑过去（首帧布局前、系统关闭动画时直接跳到位）。
     * 越界的 [index] 会被收进范围。
     */
    @JvmOverloads
    public fun select(index: Int, animate: Boolean = true) {
        if (options.childCount == 0) return
        val next = index.coerceIn(0, options.childCount - 1)
        selectedIndex = next
        for (i in 0 until options.childCount) options.getChildAt(i).isSelected = i == next
        val canAnimate = animate && hasRendered && isLaidOut && isAttachedToWindow &&
            ValueAnimator.areAnimatorsEnabled() && readTarget(next, scratch)
        if (!canAnimate) {
            stopAnimation()
            // 布局前没有几何：先按选中状态给出 0/1，布局后按选中框的实际覆盖重算。
            if (!hasRendered) for (i in weights.indices) reportHighlight(i, if (i == next) 1f else 0f)
            requestLayout()
            return
        }
        // 续接：当前渲染矩形 + 当前速度（没有在动时速度为 0，走来源的强调减速曲线）。
        if (animator?.isRunning == true) motion.edgeVelocity(fraction, velocity) else velocity.fill(0f)
        stopAnimation()
        motion.start(rendered, velocity, scratch)
        fraction = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SlidingSelectionMotion.DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { renderFrame(it.animatedFraction) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                    reportGeometry()
                }
            })
            start()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 行的位置与尺寸会在首帧之后变化（长文字折行、面板形变展开时宽度还在变、增删选项）：
        // 静止时每次布局都重新对齐；滑动中由逐帧读取实时几何跟随，不打断。
        if (animator?.isRunning == true) {
            applyIndicator()
        } else if (selectedIndex >= 0 && readTarget(selectedIndex, rendered)) {
            hasRendered = true
            applyIndicator()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observeTree()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        stopObservingTree()
        super.onDetachedFromWindow()
    }

    private fun renderFrame(animatedFraction: Float) {
        fraction = animatedFraction
        // 目标每帧取行的实时几何：行在滑动途中变高、换位，选中框收尾时仍恰好落在行上。
        if (selectedIndex >= 0) readTarget(selectedIndex, motion.target)
        motion.frame(animatedFraction, rendered)
        applyIndicator()
        notifyPositionChanged()
    }

    /** 直接摆放选中框：只改它自己的边界，不 requestLayout。 */
    private fun applyIndicator() {
        indicator.layout(
            rendered[0].roundToInt(), rendered[1].roundToInt(),
            rendered[2].roundToInt(), rendered[3].roundToInt()
        )
        syncWithSelectedRowTransform()
        updateHighlights()
        reportGeometry()
    }

    /** 按选中框与各行沿排列方向的重叠比例回调高亮值。零分配。 */
    private fun updateHighlights() {
        if (!hasRendered || highlightListener == null) return
        val start = if (options.orientation == LinearLayout.VERTICAL) 1 else 0
        for (i in weights.indices) {
            if (!readTarget(i, scratch)) continue
            reportHighlight(i, SlidingSelectionHighlight.coverage(
                rendered[start], rendered[start + 2], scratch[start], scratch[start + 2]))
        }
    }

    private fun reportHighlight(index: Int, weight: Float) {
        // 原始类型比较：getOrNull 返回 Float? 会装箱。NaN 与任何值都不相等，首次必定回调。
        if (index >= weights.size) return
        if (weights[index] == weight) return
        weights[index] = weight
        highlightListener?.onHighlight(index, weight)
    }

    /** 选中行在本控件坐标系里的矩形（不含行自己的平移与缩放）。 */
    private fun readTarget(index: Int, out: FloatArray): Boolean {
        val row = options.getChildAt(index) ?: return false
        if (row.width <= 0 || row.height <= 0) return false
        val left = options.left + row.left
        val top = options.top + row.top
        out[0] = left.toFloat()
        out[1] = top.toFloat()
        out[2] = (left + row.width).toFloat()
        out[3] = (top + row.height).toFloat()
        return true
    }

    /**
     * 选中行被长按拖动形变（位移、缩放）时，选中框跟着同样变换；静止或滑动途中恢复为单位变换。
     * 只在值确实变化时写属性，避免重绘循环。
     */
    private fun syncWithSelectedRowTransform() {
        val row = if (animator?.isRunning == true) null else options.getChildAt(selectedIndex)
        val tx = row?.translationX ?: 0f
        val ty = row?.translationY ?: 0f
        val sx = row?.scaleX ?: 1f
        val sy = row?.scaleY ?: 1f
        if (indicator.translationX != tx) indicator.translationX = tx
        if (indicator.translationY != ty) indicator.translationY = ty
        if (indicator.scaleX != sx) indicator.scaleX = sx
        if (indicator.scaleY != sy) indicator.scaleY = sy
        if (row != null) {
            val px = row.pivotX
            val py = row.pivotY
            if (indicator.pivotX != px) indicator.pivotX = px
            if (indicator.pivotY != py) indicator.pivotY = py
        }
    }

    private fun stopAnimation() {
        val running = animator ?: return
        animator = null
        running.cancel()
    }

    private fun observeTree() {
        val tree = viewTreeObserver
        if (observedTree === tree && tree.isAlive) return
        stopObservingTree()
        tree.addOnPreDrawListener(followSelectedRow)
        observedTree = tree
    }

    private fun stopObservingTree() {
        observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(followSelectedRow)
        observedTree = null
    }

    private fun defaultOptionParams(): LinearLayout.LayoutParams {
        val gap = (4 * resources.displayMetrics.density).roundToInt()
        val vertical = options.orientation == LinearLayout.VERTICAL
        return if (vertical) {
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (options.childCount > 0) topMargin = gap
            }
        } else {
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                if (options.childCount > 0) marginStart = gap
            }
        }
    }
}
