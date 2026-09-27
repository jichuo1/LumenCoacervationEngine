package com.lumen.coacervation.engine.motion.expansion

import android.animation.ValueAnimator
import android.graphics.Outline
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 手风琴分节的展开/收起驱动器（「兼容」「外观」「进阶/分类」共用）。
 *
 * 模型见 [ExpansionMotionPolicy]：动画期间布局始终保持展开态，单一进度 p 统一驱动
 * **整条祖先链**的圆角 outline 裁剪、内容各行向 header 下沿差分折叠（牌堆）、兄弟控件
 * translationY 与箭头转角；p→0 收尾时 `GONE` 的重布局要等布局生效帧再复位
 * offsets——同帧复位会让兄弟控件先回弹再被顶回，就是用户看到的跳变。
 *
 * **"会变矮的层"必须全部跟着动**：兄弟控件靠位移补偿，但每一层 wrap_content 祖先
 * 自己的背景画在布局矩形上，只裁分节卡的话它们会全程停在展开尺寸、落定一帧收掉。
 * 见 [ClipLayer] 的实证数据。
 *
 * 打断语义：反转只是翻转 [ExpansionMotionPolicy.Spring.target]，当前 p 与速度原样
 * 续跑——展开到一半收起、收起到一半展开都从中间态连续倒带，不存在 cancel 链。
 */
public class SectionExpansionController(
    private val card: ViewGroup,
    private val content: ViewGroup,
    private val chevron: View,
    private val density: Float,
    cornerRadiusDp: Float = CARD_CORNER_DP,
    private val notifyPositionChanged: () -> Unit
) {
    private val spring = ExpansionMotionPolicy.Spring()
    private val featherPx = ExpansionMotionPolicy.FEATHER_DP * density
    private val risePx = ExpansionMotionPolicy.CONTENT_RISE_DP * density
    private val stackPeekPx = ExpansionMotionPolicy.ROW_STACK_PEEK_DP * density
    private val cornerRadiusPx = cornerRadiusDp * density

    /**
     * 一层"会跟着内容一起变矮"的可见层：卡片自身，以及它上面每一层 wrap_content 祖先。
     *
     * 揭示沿用 clipToOutline + 逐帧圆角 outline。矩形 clipBounds 会把圆角卡片切成直边
     * "截断"（2026-09-22 真机截图实证）；outline 让下边缘全程保持本层自己的圆角，
     * 读作一张实体薄片在收卷，而不是被刀裁。
     *
     * **祖先必须一起裁**（2026-09-22 真机逐帧实证）：兄弟控件靠 translationY 滑行，
     * 但祖先卡片自己的背景/描边画在**布局矩形**上——只裁 card 的话，外层卡片全程停在
     * 展开尺寸，直到收尾那一帧被布局一次性收掉。实测「净化进阶设置」收起：动画第 1064 帧
     * 已静止，第 1069 帧单帧全屏差分 **0.52**（与动画中段同量级），热区 y2666–3168 ——
     * 就是外层「净化」大卡下边缘从屏幕外跳到 y≈2750，用户读作"外面一圈突然跳变"。
     */
    private class ClipLayer(val view: View, val takeover: ClipTakeover, val rowLevels: Int)

    /** 跟随滑行的兄弟；[rowLevels] 是它下方经过了几层"行"换算（竖向列表里恒为 0）。 */
    private class Follower(val view: View, val rowLevels: Int)

    /** 一层"行"容器（网格、横排）：卡片在这一层的展开高度 E 与同行其他格的最高高度 m（§14.3）。 */
    private class RowLevel(val expandedHeight: Float, val othersMax: Float, val chainIndex: Int)

    /** 被接管的 outline 原值。 */
    private class OutlineState(val provider: ViewOutlineProvider?, val clipToOutline: Boolean)

    /**
     * 一层的裁剪接管权，按 View 记账、可被多个分节共用。
     *
     * **同一层会同时属于多个分节的裁剪链**：一张「进阶设置」大卡既是它自己那个分节的
     * `card`，又是它内部每个二级分节的 wrap 祖先；两个相邻的二级分节也共用这张大卡和
     * 页面大卡。原先每个控制器各存一份"接管前的原值"，后起步的那个就会把**前一个控制器
     * 还挂着的 ClipLayer provider** 当成原值记下来，收尾时原样装回去——那个 provider 的
     * 可见下沿早已冻结在旧高度，于是本层被永久切短（2026-09-23 真机 logcat 实证：
     * 「增强进阶设置」卡真实高 4806，却被还原成 `vis=2447` 的 provider 裁住；页面大卡
     * 6517 被裁到 4158）。更糟的是它**自锁**：下一轮 capture 读到的"原值"仍是这个假
     * provider，此后每次展开都重演，直到进程重启。
     *
     * 所以原值必须**按 View 只记一次**（谁先接管谁记真值），各控制器只登记自己的收缩量，
     * 可见下沿取当前布局高减去各家之和，最后一个离开的才还原。
     * 登记的是扣除后代贡献后的 ownShrink，不能直接把父子完整行程相加。
     *
     * 所有 press 统一发生在布局完成后的 MotionFrame.onPreDraw；此时已移除收尾的
     * 控制器，并重新读取其余控制器的几何，因此可以同步更新 fullHeight，不会在
     * GONE 布局前后混用旧收缩量与新高度。
     */
    private class ClipTakeover(view: View, private val radiusPx: Float) {
        private val original = OutlineState(view.outlineProvider, view.clipToOutline)
        private val shrinkByOwner = LinkedHashMap<Any, Float>()
        private var fullHeight = view.height
        private var visibleBottom = view.height.toFloat()

        private val provider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val h = visibleBottom.roundToInt()
                if (view.width <= 0 || h <= 0) {
                    outline.setEmpty()
                    return
                }
                outline.setRoundRect(
                    0, 0, view.width, h,
                    minOf(radiusPx, h * 0.5f)
                )
            }
        }

        fun reserve(owner: Any) { shrinkByOwner.putIfAbsent(owner, 0f) }

        fun press(view: View, owner: Any, shrinkPx: Float) {
            shrinkByOwner[owner] = shrinkPx
            if (view.outlineProvider !== provider) {
                view.outlineProvider = provider
                view.clipToOutline = true
            }
            fullHeight = view.height
            apply(view)
        }

        /** @return true 表示本层已无人占用，调用方可以把它从登记表里摘掉。 */
        fun release(view: View, owner: Any): Boolean {
            if (shrinkByOwner.remove(owner) == null) return false
            if (shrinkByOwner.isEmpty()) {
                view.clipToOutline = original.clipToOutline
                view.outlineProvider = original.provider ?: ViewOutlineProvider.BACKGROUND
                // 定界交还给布局矩形，理由见 [SectionExpansionController.applyClip]。
                view.background?.setBounds(0, 0, view.width, view.height)
                view.invalidateOutline()
                return true
            }
            // 还有人在收缩本层：离开的那位若是收起收尾，布局刚刚变矮，封顶高要跟着更新。
            fullHeight = view.height
            apply(view)
            return false
        }

        private fun apply(view: View) {
            var shrink = 0f
            for (value in shrinkByOwner.values) shrink += value
            visibleBottom = (fullHeight - shrink).coerceAtLeast(0f)
            view.background?.setBounds(0, 0, view.width, visibleBottom.roundToInt())
            view.invalidateOutline()
        }
    }

    private var clipLayers: List<ClipLayer> = emptyList()
    private var clipActive = false

    private var scroller: ViewGroup? = null
    private var frame: MotionFrame? = null
    private var retiring = false
    private var ancestors: List<ViewGroup> = emptyList()
    private var ownShrink = 0f
    private var targetShrink = 0f
    private var revealedHeight = 0f

    private var siblings: List<Follower> = emptyList()
    private var rowLevels: List<RowLevel> = emptyList()

    /** 在祖先链上位于 [ancestor] 之下的行换算层数（嵌套分节：父级内容看到的是子级换算后的收缩量）。 */
    private fun rowLevelsBelow(ancestor: View): Int {
        val index = ancestors.indexOf(ancestor)
        if (index < 0) return rowLevels.size
        return rowLevels.count { it.chainIndex < index }
    }

    /** 本控制器的收缩量经过前 [levels] 层行换算后的值：该层及以上看到的"变矮了多少"。 */
    private fun shrinkThrough(shrink: Float, levels: Int): Float {
        var value = shrink
        for (i in 0 until minOf(levels, rowLevels.size)) {
            value = ExpansionFollowPolicy.rowShrink(rowLevels[i].expandedHeight, rowLevels[i].othersMax, value)
        }
        return value
    }
    private var rows: List<View> = emptyList()
    /** 收缩量的唯一来源：所有裁剪层与兄弟位移都按它换算，保证各层同速。 */
    private var contentHeightPx = 0

    private var pendingSetup: ViewTreeObserver.OnPreDrawListener? = null
    private var ticking = false
    private var lastFrameNanos = 0L

    /** 当前逻辑目标态；与宿主侧的布尔标志互为冗余但独立可信。 */
    var expanded = false
        private set

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // teardown 之后已投递的回调仍会进这里一次，必须拦掉。
            if (!ticking) return
            val last = lastFrameNanos
            lastFrameNanos = frameTimeNanos
            val dt = if (last == 0L) 1f / 60f else (frameTimeNanos - last) / 1e9f
            if (!card.isAttachedToWindow || !content.isAttachedToWindow) {
                ticking = false
                settleNow(spring.target >= 0.5f)
                return
            }
            val atRest = spring.step(dt)
            card.invalidate()
            if (atRest) finish(spring.target >= 0.5f)
            else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 切换到目标态。[animate] 为 false 或布局尚未就绪时直接落位；
     * 其余路径都走进度弹簧，任意时刻可再反向。
     */
    fun setExpanded(target: Boolean, animate: Boolean = true) {
        if (animate && target == expanded && pendingSetup == null && !ticking) return
        // preDraw 尚未交还时反转，撤回退休标志，由同一个共享帧继续接管。
        retiring = false
        if (!animate || !card.isLaidOut || !ValueAnimator.areAnimatorsEnabled()) {
            teardown()
            snapTo(target)
            return
        }
        expanded = target
        spring.target = if (target) 1f else 0f
        if (target) {
            if (pendingSetup != null) return // 展开布点已排队，等首帧
            if (content.visibility == View.VISIBLE && contentHeightPx > 0) {
                // 收起中途反转：几何仍有效，直接续跑
                if (frame == null) captureGeometry()
                startTicker()
            } else {
                beginExpandSetup()
            }
        } else {
            if (pendingSetup != null) {
                // 展开尚未起步即被打断：撤销布点，回到干净的收起态
                teardown()
                snapTo(false)
                return
            }
            captureGeometry()
            startTicker()
        }
    }

    /** 展开：VISIBLE 后等一次布局拿到展开几何（各层高度与内容高度）再起步。 */
    private fun beginExpandSetup() {
        content.visibility = View.VISIBLE
        val listener = ViewTreeObserver.OnPreDrawListener {
            pendingSetup?.let { pending ->
                content.viewTreeObserver.takeIf { it.isAlive }
                    ?.removeOnPreDrawListener(pending)
            }
            pendingSetup = null
            if (!content.isAttachedToWindow) return@OnPreDrawListener true
            contentHeightPx = content.height
            captureActors()
            // 本轮新增的 preDraw listener 不一定进入当前分发快照，首帧在这里补画一次。
            frame?.onPreDraw()
            startTicker()
            true
        }
        pendingSetup = listener
        content.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /** 收起路径（含展开动画中途反转）：内容仍占展开位，直接量取即可。 */
    private fun captureGeometry() {
        contentHeightPx = content.height
        captureActors()
    }

    /**
     * 跟随者收集：内容收缩 h 会让卡片的**每一层 wrap_content 祖先**同步变矮，
     * 因此要被顶起/放下的不只是卡片的同级兄弟——要沿祖先链向上走，
     * 收集每一层垂直 LinearLayout 中位于该祖先之后的全部子项。
     * 上溯终止于两类边界：可纵向滚动的容器（内部高度变化不影响其外兄弟），
     * 或高度非 wrap_content 的层（自身尺寸不变，收缩不再向上传导）。
     * 中间夹的非线性 wrap 层（FrameLayout 嵌套）不构成兄弟但会继续穿透。
     */
    private fun captureActors() {
        // 旧一轮的占用先交还：中途反转会走到这里，层集合可能已经变了，不交还就会漏掉
        // 不再属于本轮的层。本函数结尾按当前进度立刻重压，所以不会露出未裁剪的展开态。
        clearClip()
        scroller = null
        val chain = ArrayList<ViewGroup>()
        val followers = ArrayList<Follower>()
        val layers = ArrayList<ClipLayer>()
        val rowModels = ArrayList<RowLevel>()
        // 从 content 起步而不是 card：卡片内部位于 content **之后**的控件（同一张卡里
        // 还挂着别的分节时）同样要跟着滑行；card 自身则作为第一层裁剪层。
        var node: View? = content
        while (true) {
            val parent = node?.parent as? ViewGroup ?: break
            if (parent.canScrollVertically(-1) || parent.canScrollVertically(1)) {
                scroller = parent
                break
            }
            chain += parent
            val current = node ?: break
            // 跟随者按排布分三类（适配标准 §14.3）：
            // 竖向 LinearLayout：后面的兄弟平移整段收缩量（来源工程的唯一情形）；
            // 网格 / 横排（一行高度 = 最高那格）：先把收缩量换算成行的收缩量，下面的行按它平移；
            // 其他容器（约束布局、相对布局、瀑布流的列……）：只推正下方、同一列的兄弟。
            val levelsBelow = rowModels.size
            when {
                parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL -> {
                    val index = parent.indexOfChild(current)
                    if (index >= 0) {
                        for (i in index + 1 until parent.childCount) followers += Follower(parent.getChildAt(i), levelsBelow)
                    }
                }
                isRowContainer(parent) -> {
                    var othersMax = 0f
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChildAt(i)
                        if (child === current || child.visibility == View.GONE) continue
                        if (parent is LinearLayout ||
                            ExpansionFollowPolicy.sharesRow(child.top.toFloat(), child.bottom.toFloat(),
                                current.top.toFloat(), current.bottom.toFloat())
                        ) othersMax = maxOf(othersMax, child.height.toFloat())
                    }
                    rowModels += RowLevel(current.height.toFloat(), othersMax, chain.lastIndex)
                    if (parent !is LinearLayout) {
                        for (i in 0 until parent.childCount) {
                            val child = parent.getChildAt(i)
                            if (child !== current && ExpansionFollowPolicy.isBelow(child.top.toFloat(), current.bottom.toFloat())) {
                                followers += Follower(child, rowModels.size)
                            }
                        }
                    }
                }
                else -> {
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChildAt(i)
                        if (child === current) continue
                        if (ExpansionFollowPolicy.isBelow(child.top.toFloat(), current.bottom.toFloat()) &&
                            ExpansionFollowPolicy.sharesColumn(child.left.toFloat(), child.right.toFloat(),
                                current.left.toFloat(), current.right.toFloat())
                        ) followers += Follower(child, levelsBelow)
                    }
                }
            }
            // 卡片本体永远裁——它的下边缘就是揭示沿。
            if (parent === card) layers += clipLayer(parent, levelsBelow)
            // 高度不是 wrap 的层自己不会变矮，收缩到此为止；裁它会凭空切掉内容。
            if (parent.layoutParams?.height != ViewGroup.LayoutParams.WRAP_CONTENT) break
            // 祖先只有"自己画东西"的才需要裁（背景/描边画在布局矩形上）；纯布局容器不画，
            // 它的孩子已经由 translationY 补偿，裁了只是白付一次 invalidateOutline。
            if (parent !== card && parent.background != null) layers += clipLayer(parent, rowModels.size)
            node = parent
        }
        ancestors = chain
        siblings = followers
        rowLevels = rowModels
        clipLayers = layers
        clipActive = layers.isNotEmpty()
        rows = (0 until content.childCount).map(content::getChildAt)
        spring.adoptTravel(contentHeightPx.toFloat())
        if (frame == null) {
            val root = card.rootView
            frame = frames.getOrPut(root) { MotionFrame(root) }.also { it.add(this) }
        }
        card.invalidate()
    }

    /**
     * 每层的圆角取**它自己背景报告的 outline 半径**，不能统一用卡片那个常量：
     * 外层页面卡是 `skinCardBackground(...)` 的默认 15dp，分节卡是 12dp，混用会让
     * 收卷中的下边缘圆角与静止态对不上。两套皮肤的表面 Drawable 都实现了 `getOutline`
     * 并报真实半径；报不出（非圆角矩形/无背景）时退回构造参数。
     */
    private fun clipLayer(view: View, rowLevels: Int): ClipLayer {
        val takeover = takeovers.getOrPut(view) { ClipTakeover(view, outlineRadiusOf(view)) }
        // 采集时就占位，避免原占用者在本轮首次绘制之前退休并摘掉共享登记项。
        takeover.reserve(this)
        return ClipLayer(view, takeover, rowLevels)
    }

    /** "行"容器：一行的高度由这一行最高的那格决定（网格、表格、横排 LinearLayout）。 */
    private fun isRowContainer(parent: ViewGroup): Boolean =
        parent is android.widget.GridLayout || parent is android.widget.TableLayout || parent is android.widget.TableRow ||
            (parent is LinearLayout && parent.orientation == LinearLayout.HORIZONTAL)

    private fun outlineRadiusOf(view: View): Float {
        val background = view.background ?: return cornerRadiusPx
        val outline = Outline()
        val radius = runCatching {
            background.getOutline(outline)
            outline.radius
        }.getOrDefault(Float.NaN)
        return if (radius.isFinite() && radius >= 0f) radius else cornerRadiusPx
    }

    private fun startTicker() {
        if (ticking || frame == null) return
        ticking = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * 单帧映射：p 是唯一的驱动源。
     * 卡片下边缘扫动 → 内容揭示沿 → 行级联显影 → 兄弟滑行 → 箭头 → Liquid 位移通知。
     */
    private fun apply(offsets: MotionOffsets) {
        val spatial = ExpansionMotionPolicy.spatial(spring.p).coerceAtLeast(0f)
        applyClip()
        offsets.add(content, -risePx * (1f - spatial))
        val clipY = revealedHeight
        for (i in rows.indices) {
            val row = rows[i]
            if (row.visibility == View.GONE) continue
            val precedingOffset = offsets.preceding(row)
            val drawnTop = NestedExpansionPolicy.rowTop(
                i, row.top.toFloat(), precedingOffset, spatial, stackPeekPx
            )
            offsets.add(row, drawnTop - row.top - precedingOffset)
            offsets.fade(row, ExpansionMotionPolicy.rowReveal(clipY, drawnTop, featherPx))
        }
        chevron.rotation = ExpansionMotionPolicy.CHEVRON_DEGREES * spatial
    }

    /**
     * 逐帧把本轮的收缩量登记到每一层上，可见下边缘由 [ClipTakeover] 合计后落笔。
     *
     * 收缩量对所有层是**同一个**值（内容高度的未完成部分）——内容变矮多少，卡片和
     * 它上面每一层 wrap 祖先就一起变矮多少。兄弟控件的 translationY 也取同一个量，
     * 所以任何一层里位于收缩点之后的孩子都恰好落在自己那层的裁剪线之内，不会被切。
     */
    private fun applyClip() {
        if (clipLayers.isEmpty()) return
        val shrink = ownShrink
        // 表面 Drawable 必须知道"正在被画的矩形"：只裁不定界的话，玻璃表面仍按
        // 展开态矩形算边缘——下缘描边、菲涅尔/镜面 rim 全在裁剪线之外，整段动画
        // 没有下缘光，落定重新定界时一次性出现（2026-09-22 真机像素实测：x=700
        // 底缘由平坦 26 变成 28→30→32 的描边梯度，左缘 44→40、内侧 36→33）。
        // 与 2026-09-21（十一）承载层那条同源：provider / setBounds 两条通道
        // 喂的都是同一个"当前矩形"。View 只在**尺寸变化**时重置背景 bounds
        // （mBackgroundSizeChanged），动画期布局冻结，所以这里写的值能留住。
        // 定界与 outline 都由 [ClipTakeover] 按本层所有占用者的收缩量合计后落笔。
        for (layer in clipLayers) layer.takeover.press(layer.view, this, shrinkThrough(shrink, layer.rowLevels))
        clipActive = true
    }

    /**
     * 交还本控制器对各层的裁剪占用；本层最后一个占用者离开时才还原接管前的原值。
     *
     * 本函数在布局生效后的 preDraw（收起）或动画收尾帧（展开）里跑，还原时读到的
     * width/height 已是收尾态；尺寸真变过时 View 自己也会在下一次 draw 重置背景
     * bounds，这里只是保证"尺寸没变"那条路径上不留下被裁短的 bounds。
     */
    private fun clearClip() {
        if (clipActive) {
            for (layer in clipLayers) {
                if (layer.takeover.release(layer.view, this)) takeovers.remove(layer.view)
            }
            clipActive = false
        }
        card.clipBounds = null
    }

    /** GONE 先触发布局；退休、几何重读与所有共享属性的写回统一在下一次 preDraw。 */
    private fun finish(toExpanded: Boolean) {
        ticking = false
        retiring = true
        content.visibility = if (toExpanded) View.VISIBLE else View.GONE
        chevron.rotation = if (toExpanded) ExpansionMotionPolicy.CHEVRON_DEGREES else 0f
        card.invalidate()
        notifyPositionChanged()
    }

    /** 不直接清零 View 属性：共享帧只移除本控制器的贡献，保留其他控制器的位移。 */
    private fun resetOffsets() {
        frame?.remove(this)
        frame = null
        siblings = emptyList()
        rowLevels = emptyList()
        clipLayers = emptyList()
        ancestors = emptyList()
        scroller = null
        retiring = false
    }

    /** 无动画直达目标态；同时清掉动画残留的所有视觉补偿。 */
    private fun snapTo(target: Boolean) {
        expanded = target
        spring.p = if (target) 1f else 0f
        spring.v = 0f
        spring.target = spring.p
        ticking = false
        content.visibility = if (target) View.VISIBLE else View.GONE
        clearClip()
        resetOffsets()
        chevron.rotation = if (target) ExpansionMotionPolicy.CHEVRON_DEGREES else 0f
    }

    /** 视图树重建/节区卸载时调用：停帧、撤掉排队中的 preDraw 布点、清残留补偿。 */
    fun cancel() {
        teardown()
        clearClip()
        resetOffsets()
        rows = emptyList()
    }

    private fun teardown() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        pendingSetup?.let { pending ->
            content.viewTreeObserver.takeIf { it.isAlive }
                ?.removeOnPreDrawListener(pending)
        }
        pendingSetup = null
    }

    /** 视图脱离窗口时的安全落位：直接吸附到弹簧目标态。 */
    private fun settleNow(toExpanded: Boolean) {
        pendingSetup = null
        snapTo(toExpanded)
    }

    /** 同一窗口只在 preDraw 写一次共享几何，避免不同 Choreographer 回调混用前后两帧。 */
    private class MotionFrame(private val root: View) :
        ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val controllers = ArrayList<SectionExpansionController>()
        private val retired = ArrayList<SectionExpansionController>()
        private val positionNotifications = LinkedHashSet<() -> Unit>()
        private val offsets = MotionOffsets()
        private val scrolls = LinkedHashMap<ViewGroup, ScrollFollow>()
        private val observer = root.viewTreeObserver
        private var revision = 0
        private var rendering = false
        private var closed = false

        init {
            observer.addOnPreDrawListener(this)
            root.addOnAttachStateChangeListener(this)
        }

        fun add(controller: SectionExpansionController) {
            controllers.add(controller)
            // 后代先算：祖先的 ownShrink 只收缩后代尚未收掉的可见区域。
            controllers.sortByDescending { it.ancestors.size }
            revision++
        }

        fun remove(controller: SectionExpansionController) {
            if (!controllers.remove(controller)) return
            revision++
            if (!rendering && controllers.isEmpty()) close()
            else root.invalidate()
        }

        override fun onPreDraw(): Boolean {
            if (closed) return true
            rendering = true
            // 子级 GONE 已完成布局：先撤贡献，再读取父级的新高度与行顶。
            for (i in controllers.lastIndex downTo 0) {
                val controller = controllers[i]
                if (controller.ancestors.any { it.visibility == View.GONE }) {
                    retired.add(controller)
                    controller.teardown()
                    controller.snapTo(controller.expanded)
                } else if (controller.retiring) {
                    retired.add(controller)
                    controller.clearClip()
                    controller.resetOffsets()
                }
            }
            offsets.begin()
            for (controller in controllers) {
                val measuredHeight = controller.content.height
                if (controller.contentHeightPx != measuredHeight) {
                    controller.contentHeightPx = measuredHeight
                    controller.spring.adoptTravel(measuredHeight.toFloat())
                }
                var nested = 0f
                var targetNested = 0f
                for (child in controllers) {
                    if (child.ancestors.contains(controller.content)) {
                        val levels = child.rowLevelsBelow(controller.content)
                        nested += child.shrinkThrough(child.ownShrink, levels)
                        targetNested += child.shrinkThrough(child.targetShrink, levels)
                    }
                }
                val height = controller.contentHeightPx.toFloat()
                controller.ownShrink = NestedExpansionPolicy.ownShrink(height, nested, controller.spring.p)
                controller.targetShrink = NestedExpansionPolicy.ownShrink(height, targetNested, controller.spring.target)
                controller.revealedHeight = (height - nested - controller.ownShrink).coerceAtLeast(0f)
                for (sibling in controller.siblings) {
                    offsets.follow(sibling.view, -controller.shrinkThrough(controller.ownShrink, sibling.rowLevels))
                }
            }
            for (controller in controllers) controller.apply(offsets)
            offsets.commit()
            applyScrollFollow()
            // 表面位置必须先写回再通知，避免皮肤采到上一帧的坐标。
            // 同一 Activity 的绑定方法引用相等；父子分节同帧只刷新一次皮肤。
            // 不合并不同 owner 的回调，也必须保留本帧退休分节的最后一次通知。
            for (controller in controllers) positionNotifications.add(controller.notifyPositionChanged)
            for (controller in retired) positionNotifications.add(controller.notifyPositionChanged)
            for (notify in positionNotifications) notify()
            positionNotifications.clear()
            retired.clear()
            rendering = false
            if (controllers.isEmpty()) close()
            return true
        }

        /** 同一个滚动容器只有一个写入者；用户滚动后整组让位。 */
        private fun applyScrollFollow() {
            for (state in scrolls.values) state.used = false
            for (controller in controllers) {
                val host = controller.scroller ?: continue
                scrolls.getOrPut(host) { ScrollFollow(host.scrollY) }.used = true
            }
            val iterator = scrolls.iterator()
            while (iterator.hasNext()) {
                val (host, state) = iterator.next()
                if (!state.used) {
                    iterator.remove()
                    continue
                }
                var shrink = 0f
                var target = 0f
                for (controller in controllers) {
                    if (controller.scroller === host) {
                        shrink += controller.shrinkThrough(controller.ownShrink, Int.MAX_VALUE)
                        target += controller.shrinkThrough(controller.targetShrink, Int.MAX_VALUE)
                    }
                }
                val child = host.getChildAt(0) ?: continue
                val maximum = (child.height - host.height + host.paddingTop + host.paddingBottom).coerceAtLeast(0)
                state.apply(host, maximum, shrink, target, revision)
            }
        }

        private fun close() {
            if (closed) return
            closed = true
            offsets.restoreAll()
            retired.clear()
            scrolls.clear()
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            root.removeOnAttachStateChangeListener(this)
            if (frames[root] === this) frames.remove(root)
        }

        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            rendering = true
            while (controllers.isNotEmpty()) {
                val controller = controllers.last()
                controller.teardown()
                controller.snapTo(controller.expanded)
            }
            rendering = false
            close()
        }
    }

    /** 先累加所有兄弟贡献，再叠加行折叠；最后一个使用者退出才还原真实原值。 */
    private class MotionOffsets {
        private class State(view: View) {
            val originalY = view.translationY
            val originalAlpha = view.alpha
            var preceding = 0f
            var translation = 0f
            var alpha = 1f
            var used = false
        }

        private val states = LinkedHashMap<View, State>()

        fun begin() {
            for (state in states.values) {
                state.preceding = 0f
                state.translation = 0f
                state.alpha = 1f
                state.used = false
            }
        }

        private fun state(view: View): State = states.getOrPut(view) { State(view) }.also { it.used = true }

        fun follow(view: View, offset: Float) {
            val state = state(view)
            state.preceding += offset
            state.translation += offset
        }

        fun preceding(view: View): Float = states[view]?.preceding ?: 0f

        fun add(view: View, offset: Float) { state(view).translation += offset }

        fun fade(view: View, alpha: Float) { state(view).alpha *= alpha }

        fun commit() {
            val iterator = states.iterator()
            while (iterator.hasNext()) {
                val (view, state) = iterator.next()
                view.translationY = state.originalY + if (state.used) state.translation else 0f
                view.alpha = state.originalAlpha * if (state.used) state.alpha else 1f
                if (!state.used) iterator.remove()
            }
        }

        fun restoreAll() {
            for ((view, state) in states) {
                view.translationY = state.originalY
                view.alpha = state.originalAlpha
            }
            states.clear()
        }
    }

    private class ScrollFollow(private val expandedScroll: Int) {
        var used = false
        private var yielded = false
        private var lastWrittenScroll = -1
        private var layoutMaximum = -1
        private var revision = -1
        private var startShrink = 0f
        private var endShrink = Float.NaN
        private var startScroll = expandedScroll
        private var endScroll = expandedScroll

        fun apply(host: ViewGroup, maximum: Int, shrink: Float, target: Float, currentRevision: Int) {
            if (yielded) return
            // 正常布局钳制不算用户滚动，其他外部位移才让位。
            if (lastWrittenScroll >= 0 && abs(host.scrollY - lastWrittenScroll.coerceAtMost(maximum)) > SCROLL_YIELD_PX) {
                yielded = true
                return
            }
            if (revision != currentRevision || layoutMaximum != maximum || endShrink != target) {
                startShrink = shrink
                endShrink = target
                startScroll = host.scrollY
                endScroll = minOf(expandedScroll, (maximum - target).roundToInt().coerceAtLeast(0))
                revision = currentRevision
                layoutMaximum = maximum
            }
            val scroll = NestedExpansionPolicy.scrollPosition(shrink, startShrink, endShrink, startScroll, endScroll)
                .roundToInt().coerceIn(0, (maximum - shrink).roundToInt().coerceAtLeast(0))
            if (scroll != host.scrollY) host.scrollTo(host.scrollX, scroll)
            lastWrittenScroll = host.scrollY
        }
    }

    private companion object {
        /**
         * 裁剪接管权登记表，按 View 共用于全部分节控制器。
         *
         * 值不持有 View（`ClipTakeover` 的构造参数不是 `val`），所以弱键能正常回收；
         * 正常路径上每次 press 都有配对的 release，落表项不会积压。
         */
        val takeovers = java.util.WeakHashMap<View, ClipTakeover>()
        val frames = java.util.WeakHashMap<View, MotionFrame>()

        /** 与四个分节卡片 `skinCardBackground(surface, 12f)` 的圆角一致。 */
        const val CARD_CORNER_DP = 12f

        /** scrollY 与上一帧写入值的偏差超过它就判定"用户自己在滚"，让位。 */
        const val SCROLL_YIELD_PX = 2
    }
}
