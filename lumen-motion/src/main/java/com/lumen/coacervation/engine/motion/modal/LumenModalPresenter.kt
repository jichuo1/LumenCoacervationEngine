package com.lumen.coacervation.engine.motion.modal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.interaction.LumenElasticInteraction
import com.lumen.coacervation.engine.motion.LumenEasing
import com.lumen.coacervation.engine.motion.MorphCornerPolicy
import com.lumen.coacervation.engine.motion.declaredCornerRadius
import com.lumen.coacervation.engine.motion.MotionRect
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 锚点弹窗的两种形态。
 *
 * [CONTAINER]：来源是整行条目（或一枚图标），弹窗表面从来源的位置与圆角长成屏幕中央的卡片。
 * [BUBBLE]：来源是工具栏上的小图标，弹窗贴在图标旁边并伸出指向它的小角；图标图案交给代理层、全程守恒。
 */
public enum class ModalAnchorStyle { CONTAINER, BUBBLE }

/** 弹窗外观参数。默认值即来源工程的取值（真机调过的）。 */
public data class LumenModalStyle(
    /** 卡片圆角；图标锚点形变的展开端半径必须与它一致，否则末帧会有一次圆角跳变。 */
    val cornerRadiusDp: Float = 28f,
    val minWidthDp: Int = 292,
    val paddingHorizontalDp: Int = 24,
    val paddingTopDp: Int = 26,
    val paddingBottomDp: Int = 18,
    /** 无锚点弹窗居中时的左右边距。 */
    val centeredMarginDp: Int = 32,
    /**
     * 卡片最大宽度（宿主没给确定宽度时生效；≤ 0 表示不限）。来源工程只跑在手机竖屏上，卡片宽度由内容与边距决定；
     * 平板、横屏、桌面窗口里长文字会把卡片撑满整个窗口宽（适配标准 §15.4）。
     */
    val maxWidthDp: Int = 560,
    /** 深色背板：40% 黑。比常见 scrim 略重，底页文字不会透过面板与内容混排。 */
    val darkScrimColor: Int = 0x66000000,
    /** 浅色背板：背景色压暗 8% 后 50% 不透明的浅色薄纱（深色背板在浅色底上会显成一块跟着形变伸缩的暗区）。 */
    val lightScrimDarken: Float = 0.08f,
    val lightScrimAlpha: Int = 0x80,
    /** 形变时正文起始位移上限（每轴）。够看出"从锚点方向飞来"，又不至于让长卡片整体晃动。 */
    val contentTravelCapDp: Float = 20f,
    val bubbleWidthDp: Float = 320f,
    val bubbleSideMarginDp: Float = 12f,
    val bubbleEdgeMarginDp: Float = 16f,
    val bubbleGapDp: Float = 6f,
    val bubbleTailHeightDp: Float = 9f,
    val bubbleTailHalfWidthDp: Float = 11f,
    /**
     * 背景浅毛玻璃（平台跨窗口模糊，API 31+，只在柔光材质下生效）。**默认关闭**：来源工程真机 A/B 实测
     * 开启后面板动画掉帧率 3.62% → 10.41%，代价在写 `LayoutParams` 触发的 `relayoutWindow`。
     */
    val backdropBlur: Boolean = false
)

/**
 * 覆盖式子面板的来源：在**父面板里的控件被点击那一刻**抓取（适配标准 §13.4）。
 *
 * @property anchorBounds 来源控件的屏幕矩形。子面板从这里长出来；父面板里的来源 View 事后可能已被形变改掉，
 *   所以必须当场取，不能事后查。
 * @property coverBounds 父面板**画出来的表面**（不是容器矩形：气泡面板的小角高度在容器 padding 里）。
 */
public class ModalSubPanelOrigin internal constructor(
    public val anchorBounds: MotionRect?,
    /** 来源控件声明的圆角（px），NaN 表示没有声明；折叠端据此长出来，见 [MorphCornerPolicy]。 */
    public val anchorCornerRadiusPx: Float,
    public val coverBounds: MotionRect?,
    internal val parentDialog: Dialog,
    internal val parentContainer: View
)

/**
 * 弹窗的打开与关闭（适配标准 §13）：图标/条目锚点形变、锚定气泡、覆盖式子面板、标题迁移、背板压暗、
 * 背景模糊、预测式返回与打断续接，全部收在这一个呈现器里。
 *
 * 从来源工程 `MainActivity.presentSizedModalDialog` / `dismissWithAnimation` 抽出，时序与各条真机教训原样保留：
 * - 所有退场一律走 [dismiss]：它先查锚点收起入口，锚点弹窗自动走对应的收起形变，不用改任何调用点；
 * - 弹窗的返回回调必须在 `dialog.show()` **之后**注册（API 33 没有缓存 attach 前注册的代理 dispatcher）；
 * - `setWindowAnimations(0)` 必须在 `setContentView` **之后**（`generateLayout()` 会从主题重读动画样式）；
 * - 收起动画末帧先上屏，下一帧再移窗（[dismissAfterFinalFrame]）。
 *
 * 一个 Activity 一个实例。线程：主线程。
 */
@MainThread
public class LumenModalPresenter @JvmOverloads constructor(
    private val activity: Activity,
    private val lumen: LumenActivityDelegate,
    private val style: LumenModalStyle = LumenModalStyle(),
    private val elastic: LumenElasticInteraction? = null,
    /** 弹窗内容换装（例如 `{ LumenControls.style(it, lumen) }`）；在呈现前对容器调用一次。 */
    private val styleContent: (View) -> Unit = {}
) {
    private val density get() = activity.resources.displayMetrics.density

    /** 当前最上层的弹窗。覆盖式子面板关闭后交还给被盖住的父面板。 */
    public var activeDialog: Dialog? = null
        private set

    /**
     * 弹窗 → 锚点收起入口。签名是 `(interactiveCommit, afterClose) -> handled`：`afterClose` 为空时用弹窗自己的
     * `onBackDismiss`，非空时用调用点传进来的那个（例如"关闭后打开链接"）。
     */
    private val anchoredClosers = WeakHashMap<Dialog, (Boolean, (() -> Unit)?) -> Boolean>()

    /** 弹窗窗口内的背板压暗层（位于卡片之下）；随各路径的动画进度同步 alpha。 */
    private val scrims = WeakHashMap<Dialog, View>()

    /**
     * 弹窗**卡片矩形**与**可见表面**之间的差。气泡面板的小角高度加在 container 的 padding 上，所以容器矩形比
     * 真正画出来的表面**大一条**（来源工程真机实测差 9dp＝小角高）。子面板要"严丝合缝盖住父面板"就必须按可见
     * 表面对齐。只有气泡面板非零。
     */
    private val surfaceInsets = WeakHashMap<Dialog, Rect>()

    private val emphasizedDecelerate = LumenEasing.emphasizedDecelerate()
    private val emphasizedAccelerate = LumenEasing.emphasizedAccelerate()

    // ---------------- 构造 ----------------

    /**
     * 弹窗内容容器：竖向、带弹窗表面背景、初始为缩放入场的起点（0.85、透明）。
     * **第一个子 View 应当是标题 TextView**：标题迁移只认它当目标端（§13.3）。
     */
    public fun createContainer(): LinearLayout = ModalContainer(activity, (style.maxWidthDp * density).toInt()).apply {
        orientation = LinearLayout.VERTICAL
        minimumWidth = (style.minWidthDp * density).toInt()
        setPadding(
            (style.paddingHorizontalDp * density).toInt(),
            (style.paddingTopDp * density).toInt(),
            (style.paddingHorizontalDp * density).toInt(),
            (style.paddingBottomDp * density).toInt()
        )
        background = lumen.modalBackground(lumen.palette.surface, style.cornerRadiusDp)
        // 两套材质的卡片都是半透明的，不能用系统 elevation 投影：系统按不透明物体画阴影，半影有一半落在卡片
        // 内侧，透过玻璃显成一圈灰带。与气泡面板一致不带投影，面板靠描边与背板区分。
        elevation = 0f
        scaleX = 0.85f
        scaleY = 0.85f
        alpha = 0f
    }

    // ---------------- 几何 ----------------

    /** 来源控件的屏幕矩形。只使用当前可见的条目，不能拿整个可滚动父分组作为来源；不可用时返回 null。 */
    public fun anchorBounds(anchor: View): MotionRect? {
        if (!anchor.isAttachedToWindow || !anchor.isShown) return null
        val visible = Rect()
        if (!anchor.getGlobalVisibleRect(visible) || visible.isEmpty) return null
        val sourceRoot = anchor.rootView
        if (sourceRoot.scaleX != 1f || sourceRoot.scaleY != 1f || sourceRoot.rotation != 0f ||
            sourceRoot.rotationX != 0f || sourceRoot.rotationY != 0f) return null
        // GlobalVisibleRect 属于 rootView 坐标；补上根窗口屏幕位置（含 adjustPan），
        // 后续才可以与 Dialog 的 getLocationOnScreen 相减。
        val rootLocation = IntArray(2)
        sourceRoot.getLocationOnScreen(rootLocation)
        visible.offset(rootLocation[0], rootLocation[1])
        return MotionRect(visible.left.toFloat(), visible.top.toFloat(),
            visible.right.toFloat(), visible.bottom.toFloat()).takeIf { it.isValid }
    }

    /** 弹窗画出来的那块表面在屏幕上的矩形。拿不到布局位置时返回 null。 */
    public fun surfaceBounds(dialog: Dialog, container: View): MotionRect? {
        val bounds = anchorBounds(container) ?: return null
        val insets = surfaceInsets[dialog] ?: return bounds
        return MotionRect(
            left = bounds.left + insets.left,
            top = bounds.top + insets.top,
            right = bounds.right - insets.right,
            bottom = bounds.bottom - insets.bottom
        ).takeIf { it.isValid }
    }

    /**
     * 覆盖式子面板：在父面板里的 [source] 被点击的**那一刻**调用，同时抓来源矩形与父面板可见表面。
     * 之后交给 [presentSubPanel]。父面板不关闭，子面板的展开端正好盖住它，收起时再露出它。
     */
    public fun captureSubPanel(parentDialog: Dialog, parentContainer: View, source: View): ModalSubPanelOrigin =
        ModalSubPanelOrigin(anchorBounds(source), source.declaredCornerRadius(),
            surfaceBounds(parentDialog, parentContainer), parentDialog, parentContainer)

    /**
     * 把卡片摆到 [origin] 的父面板矩形上并从来源控件长出来（第四条面板路径）。
     * 宽度与左上角严格对齐父面板；高度取"父面板高度"与"内容自然高度"的较大值——完全遮挡是目的，
     * 为了对齐把内容裁掉不是。内容比父面板矮时，空档应当由关闭行上方的一条 weight 占位吸收。
     */
    @JvmOverloads
    public fun presentSubPanel(
        origin: ModalSubPanelOrigin,
        dialog: Dialog,
        container: LinearLayout,
        onExpanded: () -> Unit = {},
        onBackDismiss: () -> Unit = {},
        titleView: TextView? = null
    ) {
        present(dialog, container, onExpanded = onExpanded, onBackDismiss = onBackDismiss,
            anchorBounds = origin.anchorBounds, coverBounds = origin.coverBounds,
            anchorCornerRadiusPx = origin.anchorCornerRadiusPx, titleView = titleView)
    }

    /**
     * 子面板里"关掉自己再去别处"的动作（例如打开第三张弹窗）：父面板留不住（新弹窗呈现时会硬关当前弹窗），
     * 所以与子面板的退场**并行**收起父面板，而不是等收完再收。
     */
    public fun dismissParent(origin: ModalSubPanelOrigin) {
        if (origin.parentDialog.isShowing) dismiss(origin.parentDialog, origin.parentContainer) {}
    }

    // ---------------- 关闭 ----------------

    /**
     * 关闭弹窗。锚点弹窗（形变/气泡）自动走对应的收起形变并在收拢后回调；普通弹窗走 180ms 缩放淡出。
     * **所有**关闭路径都应当走这里，不要自己写退场。
     */
    @JvmOverloads
    public fun dismiss(dialog: Dialog, container: View, onDismissed: () -> Unit = {}) {
        if (anchoredClosers[dialog]?.invoke(false, onDismissed) == true) return
        container.animate()
            .scaleX(0.92f).scaleY(0.92f).alpha(0f)
            .setDuration(180L)
            .setInterpolator(emphasizedAccelerate)
            // 背板压暗层随卡片淡出；缩放会移动卡片里的玻璃表面，逐帧通知引擎按新位置重新采样。
            .setUpdateListener {
                scrims[dialog]?.alpha = container.alpha
                lumen.notifyPositionChanged()
            }
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dialog.dismiss()
                    onDismissed()
                }
            })
            .start()
    }

    /** Activity 销毁前调用：硬关当前弹窗（不播动画）。 */
    public fun onDestroy() {
        activeDialog?.dismiss()
        activeDialog = null
    }

    /**
     * 收起动画的末帧先按时上屏，下一帧再移窗并执行后续回调。
     *
     * `dialog.dismiss()` 同步移窗，并在 RenderThread 上销毁这个窗口的硬件渲染资源。原来它跑在动画结束回调里，
     * 与收拢到底的最后一帧挤在同一帧（来源工程 atrace：关闭末帧 7–11ms，偶发紧接两帧 15–18ms）。推迟一帧后，
     * 这段开销落在画面静止的帧里；末帧已收拢到 0、窗口动画已关，推迟期间屏幕上没有可见内容。
     */
    private fun dismissAfterFinalFrame(dialog: Dialog, then: () -> Unit) {
        val decor = dialog.window?.decorView
        val finish = {
            if (dialog.isShowing) runCatching { dialog.dismiss() }
            then()
        }
        if (decor == null || !decor.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) {
            finish()
        } else {
            decor.postOnAnimation { finish() }
        }
    }

    private fun scrimColor(): Int {
        val palette = lumen.palette
        return if (ColorUtils.calculateLuminance(palette.surface) < 0.5) style.darkScrimColor
        else ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(palette.background, Color.BLACK, style.lightScrimDarken),
            style.lightScrimAlpha
        )
    }

    /**
     * 把来源（屏幕坐标）与已完成布局的卡片换算成承载层内的形变几何。卡片是承载层的直接 child，所以展开端直接用
     * `left/top/right/bottom`；折叠端要扣掉承载层自己的窗口原点——弹窗与页面是两个 Window，不能假设原点相同。
     */
    private fun resolveIconAnchoredGeometry(
        layer: IconAnchoredMotionLayer,
        card: View,
        anchorOnScreen: MotionRect,
        anchorDeclaredRadius: Float
    ): IconAnchoredMotionGeometry? {
        if (!layer.isAttachedToWindow || layer.width <= 0 || layer.height <= 0) return null
        if (card.width <= 0 || card.height <= 0) return null
        val layerLocation = IntArray(2)
        layer.getLocationOnScreen(layerLocation)
        val collapsed = MotionRect(
            left = anchorOnScreen.left - layerLocation[0],
            top = anchorOnScreen.top - layerLocation[1],
            right = anchorOnScreen.right - layerLocation[0],
            bottom = anchorOnScreen.bottom - layerLocation[1]
        )
        val expanded = MotionRect(card.left.toFloat(), card.top.toFloat(), card.right.toFloat(), card.bottom.toFloat())
        // 折叠端圆角优先取来源自己声明的圆角（网格方块、大卡片不会从一个圆长出来）；
        // 没有声明（无背景的图标）才取短边一半：图标收成正圆，不会出现"方角小块"。
        return IconAnchoredMotionGeometry(
            collapsedBounds = collapsed,
            expandedBounds = expanded,
            collapsedRadiusPx = MorphCornerPolicy.collapsedRadius(anchorDeclaredRadius, collapsed.width, collapsed.height),
            expandedRadiusPx = style.cornerRadiusDp * density,
            contentTravelCapPx = style.contentTravelCapDp * density
        ).takeIf { it.isUsable }
    }

    // ---------------- 呈现 ----------------

    /**
     * 呈现弹窗。
     *
     * @param preferredWidth 卡片宽度（px）；null 为 WRAP_CONTENT（含列表的弹窗必须给显式宽度，否则每行收缩成自己的内容宽）。
     * @param anchor 来源控件。传入即启用锚点形变：[ModalAnchorStyle.CONTAINER] 从它的位置与圆角长成卡片，
     *   [ModalAnchorStyle.BUBBLE] 贴在它旁边伸出小角（它必须是 ImageView 才有图案交接）。null 保持居中缩放入场。
     * @param anchorBounds 来源控件**在另一张弹窗里**时用它：那张弹窗会先收起，来源 View 随之从窗口上摘掉，事后只会
     *   拿到 null。调用点在**点击那一刻**用 [anchorBounds] 抓好传进来。仅在 `anchor == null` 且为 CONTAINER 时生效。
     * @param coverBounds **叠在父弹窗上**的子面板（更推荐用 [captureSubPanel] + [presentSubPanel]）：卡片摆到这张屏幕
     *   矩形上，父面板**不关闭**、留在下面；关闭时把"当前弹窗"还给它；不再叠第二层背板与模糊。
     * @param anchorCornerRadiusPx 与 [anchorBounds] 配套：来源控件在点击那一刻声明的圆角（`View` 不在时读不到）。
     *   传入实时 [anchor] 时忽略，直接读它自己的。
     * @param titleView 标题迁移的目标端。null 时取容器的第一个子 View（它是 TextView 时）；标题放在"图标 + 标题"
     *   的横排里、或前面还有别的控件时，显式传入（§15.4）。
     */
    // 气泡 margin 与锚点均为物理窗口坐标，LEFT 不可替换成会再镜像一次的 START。
    @Suppress("GestureBackNavigation", "RtlHardcoded")
    @JvmOverloads
    public fun present(
        dialog: Dialog,
        container: LinearLayout,
        preferredWidth: Int? = null,
        anchor: View? = null,
        anchorStyle: ModalAnchorStyle = ModalAnchorStyle.CONTAINER,
        onExpanded: () -> Unit = {},
        onBackDismiss: () -> Unit = {},
        anchorBounds: MotionRect? = null,
        coverBounds: MotionRect? = null,
        anchorCornerRadiusPx: Float = Float.NaN,
        titleView: TextView? = null
    ) {
        elastic?.clear()
        container.tag = ElasticInteractionController.CONTAINER_TAG
        // 子面板要盖在父面板上，父面板就不能被硬关；关闭时再把它还回 activeDialog。
        val cover = coverBounds?.takeIf { it.isValid && anchorStyle == ModalAnchorStyle.CONTAINER }
        val coveredParent = if (cover != null) activeDialog?.takeIf { it.isShowing } else null
        if (cover == null) activeDialog?.dismiss()
        styleContent(container)
        val density = density
        val cornerRadiusPx = style.cornerRadiusDp * density
        // 由 dismiss 传进来的一次性收尾回调；为空时用弹窗自己的 onBackDismiss。每次弹窗独立一份，不能提到字段上。
        val pendingAnchoredAfterClose = AtomicReference<(() -> Unit)?>(null)
        // 静态来源矩形只服务"来源在另一张弹窗里"：气泡要拿来源 ImageView 做图案交接，拿不到 View 就没有气泡可言。
        val capturedAnchorBounds = anchorBounds
            ?.takeIf { anchor == null && anchorStyle == ModalAnchorStyle.CONTAINER && it.isValid }
        // 实时 View 永远优先：退场和旋转必须每次重新解析，缓存来源会让旋转后用上陈旧矩形。
        fun resolveAnchorOnScreen(): MotionRect? = anchor?.let(::anchorBounds) ?: capturedAnchorBounds
        val resolvedAnchor = anchor?.takeIf {
            it.isAttachedToWindow && it.width > 0 && it.height > 0 &&
                (anchorStyle == ModalAnchorStyle.BUBBLE || ValueAnimator.areAnimatorsEnabled())
        }?.let(::anchorBounds)
            ?: capturedAnchorBounds?.takeIf { ValueAnimator.areAnimatorsEnabled() }
        fun placeBubble(target: MotionRect, width: Float, height: Float) =
            BubblePlacementSpec.place(
                anchor = target,
                windowWidth = width,
                windowHeight = height,
                desiredWidth = style.bubbleWidthDp * density,
                maxWidthPx = style.bubbleWidthDp * density,
                sideMarginPx = style.bubbleSideMarginDp * density,
                edgeMarginPx = style.bubbleEdgeMarginDp * density,
                gapPx = style.bubbleGapDp * density,
                tailHeightPx = style.bubbleTailHeightDp * density,
                tailHalfWidthPx = style.bubbleTailHalfWidthDp * density,
                cornerRadiusPx = cornerRadiusPx
            )
        val bubblePlacement = if (anchorStyle == ModalAnchorStyle.BUBBLE && resolvedAnchor != null) {
            // 用 Activity 窗口而不是整块屏幕：分屏、自由窗口、折叠屏里窗口比屏幕小，还可能不在屏幕原点（§15.3）。
            // 首次摆位之后，updateBubbleGeometry 会在首帧前按弹窗根的真实坐标再算一次。
            val decor = activity.window.decorView
            val decorOrigin = IntArray(2).also(decor::getLocationOnScreen)
            val windowWidth = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val windowHeight = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            placeBubble(
                MotionRect(resolvedAnchor.left - decorOrigin[0], resolvedAnchor.top - decorOrigin[1],
                    resolvedAnchor.right - decorOrigin[0], resolvedAnchor.bottom - decorOrigin[1]),
                windowWidth.toFloat(), windowHeight.toFloat()
            )
        } else {
            null
        }
        val originalPaddingTop = container.paddingTop
        val originalPaddingBottom = container.paddingBottom
        val modalBackground = container.background
        fun applyBubbleSurface(placement: BubblePlacement) {
            // 小角占掉整体高度的一条，内容要让开，否则文字会压在尖上。
            val tailPadding = (style.bubbleTailHeightDp * density).toInt()
            container.setPadding(
                container.paddingLeft,
                originalPaddingTop + if (placement.tailEdge == BubbleTailEdge.TOP) tailPadding else 0,
                container.paddingRight,
                originalPaddingBottom + if (placement.tailEdge == BubbleTailEdge.BOTTOM) tailPadding else 0
            )
            // 材质背景移交独立表面；正文保持原生尺寸。
            container.background = null
        }
        if (bubblePlacement != null) {
            applyBubbleSurface(bubblePlacement)
            // 小角那条只在 container 的 padding 里，不在画出来的表面里；记下来，子面板才能按可见表面对齐。
            val tailPadding = (style.bubbleTailHeightDp * density).toInt()
            surfaceInsets[dialog] = Rect(
                0,
                if (bubblePlacement.tailEdge == BubbleTailEdge.TOP) tailPadding else 0,
                0,
                if (bubblePlacement.tailEdge == BubbleTailEdge.BOTTOM) tailPadding else 0
            )
            container.scaleX = 1f
            container.scaleY = 1f
            container.elevation = 0f
        }
        val surfaceColor = lumen.palette.surface
        val bubbleLayer = if (bubblePlacement != null) {
            BubblePanelLayer(activity, container, anchor as? ImageView,
                modalBackground ?: lumen.modalBackground(surfaceColor, style.cornerRadiusDp),
                lumen.modalBackground(surfaceColor, 0f), surfaceColor,
                cornerRadiusPx, style.bubbleTailHeightDp * density,
                style.bubbleTailHalfWidthDp * density).apply { setPlacement(bubblePlacement) }
        } else null
        val morphLayer = resolvedAnchor?.takeIf { bubblePlacement == null }
            ?.let {
                IconAnchoredMotionLayer(
                    activity,
                    // 所有锚点弹窗都把卡片表面托管给持久表面 View：形变首尾与落定画的是同一个 Drawable，
                    // 落定瞬间不再有 drawable 交接。覆盖式面板沿用此路径。
                    surfaceBackground = modalBackground ?: lumen.modalBackground(surfaceColor, style.cornerRadiusDp),
                    fallbackColor = surfaceColor,
                    surfaceRadiusPx = cornerRadiusPx,
                    surfaceElevation = if (cover == null) container.elevation else 0f
                ).also { layer ->
                    if (layer.usesPersistentSurface) {
                        // 填充、描边与投影始终归承载表面；正文只负责内容动画。
                        container.background = null
                        container.elevation = 0f
                    }
                }
            }
        // 窗口内压暗层：盖在卡片之下、整窗铺开，随卡片/形变进度同步淡入淡出——平台 dim（FLAG_DIM_BEHIND）
        // 不可动画，且会硬切在自绘的形变/气泡入场之前。叠在父面板上的子面板不再加一层：父面板那层还在。
        val scrim = if (cover == null) View(activity).apply {
            setBackgroundColor(scrimColor())
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
        } else null
        val root = ModalCardRoot(activity).apply {
            val cardParams = if (bubblePlacement != null) {
                FrameLayout.LayoutParams(bubblePlacement.width.toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    // 几何来自物理屏幕坐标，不能在 RTL 下再次镜像。
                    gravity = Gravity.TOP or Gravity.LEFT
                    leftMargin = bubblePlacement.left.toInt()
                    topMargin = bubblePlacement.top.toInt()
                }
            } else if (cover != null) {
                // 真正的 margin 要等 root 拿到屏幕位置才算得准，见 applyCoverPlacement()。
                FrameLayout.LayoutParams(cover.width.toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { gravity = Gravity.TOP or Gravity.LEFT }
            } else {
                FrameLayout.LayoutParams(
                    preferredWidth ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    val margin = (style.centeredMarginDp * density).toInt()
                    setMargins(margin, 0, margin, 0)
                }
            }
            if (bubbleLayer != null) {
                bubbleLayer.setContentLayoutParams(cardParams)
                addView(bubbleLayer, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else if (morphLayer != null) {
                // 承载层必须全屏：outline 要从来源一路长到屏幕中央的卡片，折叠端矩形本来就落在卡片之外。
                morphLayer.addView(container, cardParams)
                addView(morphLayer, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                addView(container, cardParams)
            }
        }
        // 窗口铺满整屏，压暗层在最外层盖住状态栏与导航栏；root 按系统栏内缩，卡片、气泡、覆盖式面板仍以 root
        // 为坐标原点。来源工程原来窗口避开系统栏，打开面板后状态栏一条不被压暗。
        val windowFrame = FrameLayout(activity).apply {
            scrim?.let {
                addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(windowFrame) { _, insets ->
            // 只让开系统栏与刘海。弹窗是 adjustPan，输入法由系统平移窗口处理，不在这里内缩。
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val params = root.layoutParams as FrameLayout.LayoutParams
            if (params.leftMargin != safe.left || params.topMargin != safe.top ||
                params.rightMargin != safe.right || params.bottomMargin != safe.bottom
            ) {
                params.setMargins(safe.left, safe.top, safe.right, safe.bottom)
                root.layoutParams = params
            }
            insets
        }
        // 子面板贴到父面板矩形上。两张 Dialog 的窗口原点不保证相同（状态栏、adjustPan），每次都拿 root 的屏幕位置换算。
        val coverLocation = IntArray(2)
        fun applyCoverPlacement(): Boolean {
            val target = cover ?: return false
            if (root.width <= 0 || root.height <= 0) return false
            root.getLocationOnScreen(coverLocation)
            val params = container.layoutParams as? FrameLayout.LayoutParams ?: return false
            val left = (target.left - coverLocation[0]).toInt()
            val top = (target.top - coverLocation[1]).toInt()
            val width = target.width.toInt()
            // 高度必须**算出来写死**，不能用 WRAP_CONTENT + minimumHeight：后者在 FrameLayout 里会被剩余空间
            // 撑到窗口底部。先按目标宽度量一次自然高度，取"父面板高度"与"内容高度"的较大值。
            container.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val height = maxOf(target.height.toInt(), container.measuredHeight)
            var changed = false
            if (params.width != width) { params.width = width; changed = true }
            if (params.height != height) { params.height = height; changed = true }
            if (params.leftMargin != left) { params.leftMargin = left; changed = true }
            if (params.topMargin != top) { params.topMargin = top; changed = true }
            if (changed) container.requestLayout()
            return changed
        }
        var currentBubblePlacement = bubblePlacement
        var bubbleAnchor = resolvedAnchor
        fun notifyExpanded() {
            if (dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) onExpanded()
        }
        // 被盖住的父面板：形变末段让它的**卡片层**淡出，终态只剩子面板那一条描边（两条半透明描边重合会叠成更亮的
        // 一条）。绝不能淡整张 decorView——父面板的压暗层就在那张 decorView 里，而子面板按"父面板那层还在"
        // 的前提故意没有自己的压暗层，一起淡没就是全屏变亮。父面板窗口层的孩子是 [scrim, root]，取非 scrim 的那个。
        val coveredContent = if (cover != null) coveredParent?.let { parent ->
            val parentScrim = scrims[parent]
            val parentRoot = parentScrim?.parent as? ViewGroup
            val cardLayer = if (parentRoot == null) null else (0 until parentRoot.childCount)
                .map(parentRoot::getChildAt)
                .firstOrNull { it !== parentScrim }
            cardLayer ?: parent.window?.decorView
        } else null
        // 背景浅毛玻璃：跟着弹窗自己的进度渐进。子面板不再叠一层（父面板那层还在）。
        val backdropBlur = if (cover != null) null else ModalBackdropBlur.createOrNull(
            window = dialog.window,
            userEnabled = style.backdropBlur,
            materialYouSkin = !lumen.isLiquidEffective,
            density = density
        )
        scrim?.let { scrims[dialog] = it }
        val bubbleController = if (bubbleLayer != null) {
            BubbleMotionController(
                layer = bubbleLayer,
                onFrame = { progress ->
                    backdropBlur?.apply(progress)
                    scrim?.alpha = progress
                    coveredContent?.alpha = IconAnchoredMotionSpec.coveredParentAlpha(progress)
                },
                onExpanded = ::notifyExpanded,
                onClosed = {
                    dismissAfterFinalFrame(dialog) {
                        (pendingAnchoredAfterClose.getAndSet(null) ?: onBackDismiss).invoke()
                    }
                }
            )
        } else {
            null
        }
        val titleMotion = if (morphLayer != null) {
            ModalTitleMotion.create(anchor, titleView ?: (container.getChildAt(0) as? TextView), root)
                ?.also { title ->
                    // 承载层带 elevation 后 Z>0，会把 Z=0 的兄弟盖到表面之下；飞行标题只抬 Z 序（空 outline，
                    // 自身不投影），保持在面板之上。
                    title.elevation = morphLayer.elevation + 1f
                    root.addView(title, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
        } else null
        val morphController = if (morphLayer != null && resolvedAnchor != null) {
            // 形变不缩放卡片（缩放会把文字压扁），只驱动 outline + alpha，因此先抹掉缩放入场准备的 0.85。
            container.scaleX = 1f
            container.scaleY = 1f
            IconAnchoredMotionController(
                layer = morphLayer,
                content = container,
                // 必须和卡片用**同一种**背景（MODAL），不能用 MOTION_SURFACE：后者是为全屏容器形变准备的半透明
                // "运动表面"，用在这里会让飞入过程变成一团能透看底页的鬼影。
                surfaceDrawable = lumen.modalBackground(surfaceColor, style.cornerRadiusDp),
                resolveGeometry = {
                    resolveAnchorOnScreen()?.let { currentAnchor ->
                        val liveAnchor = anchor?.takeIf { it.isAttachedToWindow }
                        resolveIconAnchoredGeometry(morphLayer, container, currentAnchor,
                            liveAnchor?.declaredCornerRadius() ?: anchorCornerRadiusPx)
                    }
                },
                titleMotion = titleMotion,
                onFrame = { progress ->
                    backdropBlur?.apply(progress)
                    scrim?.alpha = progress
                    coveredContent?.alpha = IconAnchoredMotionSpec.coveredParentAlpha(progress)
                    // 父面板在子面板当前覆盖的区域里按子面板不透明度让位：外轮廓不回缩、开头不露底、结尾不叠亮。
                    (coveredContent as? ModalCardRoot)?.excludeMotionSurface(morphLayer, morphLayer.alpha)
                },
                onExpanded = ::notifyExpanded,
                onContentMoved = { lumen.notifyPositionChanged() },
                onClosed = {
                    dismissAfterFinalFrame(dialog) {
                        (pendingAnchoredAfterClose.getAndSet(null) ?: onBackDismiss).invoke()
                    }
                }
            )
        } else {
            null
        }
        // 登记收起入口：所有 dismiss() 由此自动走对应的收起动画。
        val anchoredCloser: ((Boolean, (() -> Unit)?) -> Boolean)? = when {
            bubbleController != null -> { interactive, after ->
                // 关闭按钮后紧接返回键不能覆盖第一次关闭的业务回调。
                if (!bubbleController.isClosing) pendingAnchoredAfterClose.set(after)
                bubbleController.requestClose(interactive)
            }
            morphController != null -> { interactive, after ->
                if (!morphController.isClosing) pendingAnchoredAfterClose.set(after)
                morphController.requestClose(interactive)
            }
            else -> null
        }
        if (anchoredCloser != null) anchoredClosers[dialog] = anchoredCloser
        val bubbleRootLocation = IntArray(2)
        val bubbleSourceLocation = IntArray(2)
        var bubbleLaidOut = false
        var previousBubbleHeight = 0
        var bubbleGeometryPending = false
        var previousBubbleLeft = Int.MIN_VALUE
        var previousBubbleTop = Int.MIN_VALUE
        var previousBubbleRight = Int.MIN_VALUE
        var previousBubbleBottom = Int.MIN_VALUE
        fun updateBubbleGeometry(): Boolean {
            if (bubbleController == null || bubbleController.isClosing || root.width <= 0 || root.height <= 0) return false
            val source = anchor ?: return false
            if (!source.isAttachedToWindow) return false
            root.getLocationOnScreen(bubbleRootLocation)
            source.getLocationOnScreen(bubbleSourceLocation)
            // Dialog 的内容原点可能位于状态栏下面；统一转换后才计算小角与收回目标。
            val x = (bubbleSourceLocation[0] - bubbleRootLocation[0]).toFloat()
            val y = (bubbleSourceLocation[1] - bubbleRootLocation[1]).toFloat()
            val localAnchor = MotionRect(x, y, x + source.width, y + source.height)
            val placement = placeBubble(localAnchor, root.width.toFloat(), root.height.toFloat()) ?: return false
            val params = container.layoutParams as FrameLayout.LayoutParams
            val top = BubblePlacementSpec.resolveTop(placement, localAnchor, container.height.toFloat(),
                style.bubbleGapDp * density, style.bubbleEdgeMarginDp * density).toInt()
            val layoutChanged = params.width != placement.width.toInt() ||
                params.leftMargin != placement.left.toInt() || params.topMargin != top
            // 键盘只改变可用高度、但卡片本身仍放得下时，不打断仍在进行的展开动画。
            val shapeChanged = placement.tailEdge != currentBubblePlacement?.tailEdge ||
                placement.tailCenterX != currentBubblePlacement?.tailCenterX ||
                placement.tailBaseCenterX != currentBubblePlacement?.tailBaseCenterX
            val geometryChanged = layoutChanged || shapeChanged || localAnchor != bubbleAnchor ||
                previousBubbleHeight != container.height
            val actualBoundsChanged = container.left != previousBubbleLeft ||
                container.top != previousBubbleTop || container.right != previousBubbleRight ||
                container.bottom != previousBubbleBottom
            bubbleGeometryPending = bubbleGeometryPending || geometryChanged || actualBoundsChanged
            bubbleAnchor = localAnchor
            if (shapeChanged) applyBubbleSurface(placement)
            bubbleLayer?.setPlacement(placement)
            bubbleLayer?.setAnchor(localAnchor)
            currentBubblePlacement = placement
            previousBubbleHeight = container.height
            if (layoutChanged) {
                params.width = placement.width.toInt()
                params.leftMargin = placement.left.toInt()
                params.topMargin = top
                container.layoutParams = params
            }
            // 参数写入不是布局完成；等实际四边就绪后再刷新独立背景与正文裁剪。
            if (!layoutChanged && !container.isLayoutRequested) {
                if (bubbleLaidOut && bubbleGeometryPending) bubbleController.handleWindowSizeChange()
                bubbleGeometryPending = false
                previousBubbleLeft = container.left
                previousBubbleTop = container.top
                previousBubbleRight = container.right
                previousBubbleBottom = container.bottom
            }
            return layoutChanged
        }
        val bubbleLayoutListener = if (bubbleController != null) {
            ViewTreeObserver.OnGlobalLayoutListener { updateBubbleGeometry() }
                .also { root.viewTreeObserver.addOnGlobalLayoutListener(it) }
        } else null
        morphLayer?.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val hadLayout = (oldRight - oldLeft) > 0 && (oldBottom - oldTop) > 0
            val resized = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
            // 旋转/分屏/输入法改变窗口后旧矩形失效，直接落到稳定端而不是继续按旧几何插值。
            if (hadLayout && resized && morphController?.isExpanded == false) {
                morphController.handleWindowSizeChange()
            }
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            // 铺到系统栏下面：浮动窗口默认按系统栏缩框，压暗层盖不到状态栏。内容区的内缩由 windowFrame 自己做。
            WindowCompat.setDecorFitsSystemWindows(this, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                attributes = attributes.apply {
                    fitInsetsTypes = 0
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            } else {
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    attributes = attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
            // 弹窗窗口不带 DRAWS_SYSTEM_BAR_BACKGROUNDS 时，系统在它盖住的状态栏上画一条不透明黑底、图标强制变白。
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
            // 窗口盖住状态栏后由它决定图标明暗；沿用页面的设置，打开面板时图标不跳色。
            val pageBars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = pageBars.isAppearanceLightStatusBars
                isAppearanceLightNavigationBars = pageBars.isAppearanceLightNavigationBars
            }
        }
        dialog.setContentView(windowFrame)
        // 平台弹窗布局里有 fitsSystemWindows="true" 的容器，会把整个内容区连同压暗层按状态栏高度下推，空出的
        // 那条由 DecorView 的兜底背景填成黑色。祖先链一律不吃 insets，内缩只由 windowFrame 的监听做。
        var fitAncestor = windowFrame.parent as? View
        while (fitAncestor != null && fitAncestor !== dialog.window?.decorView) {
            fitAncestor.fitsSystemWindows = false
            fitAncestor.setPadding(0, 0, 0, 0)
            fitAncestor = fitAncestor.parent as? View
        }
        val releaseElasticInteraction = elastic?.installDialog(dialog) ?: {}
        // **必须在 setContentView 之后**：`PhoneWindow.generateLayout()` 会从主题里重新读 `windowAnimationStyle`
        // 覆盖掉之前写的 0。本呈现器的动画都是手绘的，窗口动画只会叠在上面捣乱：`dismiss()` 同步移窗，
        // 窗口退出动画搬的是**最后一次真正绘制**的那一帧，末帧的飞行标题会被拖着"往上飘走并淡出"。
        dialog.window?.setWindowAnimations(0)
        // 系统返回（手势与三键）收敛到同一退场。API 34+ 注册带进度的回调：手势拖动时预览缩小、取消回弹、
        // 松手从预览态无缝续接退场；API 33 的普通回调没有进度，松手才动画；三键导航走 KEYCODE_BACK。
        // dismissing 防重入：退场一旦启动，后续手势/按键回调一律忽略——四个回调都要判，`onBackStarted` 尤其
        // 不能漏，它才是 cancel 在途 ViewPropertyAnimator 的那处，cancel 会立刻派发收尾监听器。
        var dismissing = false
        fun requestDismiss(interactiveCommit: Boolean = false) {
            if (dismissing) return
            dismissing = true
            // 锚点动画接管失败（几何不可用）时回落到缩放退场，不留半截形状。
            if (anchoredCloser?.invoke(interactiveCommit, null) == true) return
            dismiss(dialog, container, onBackDismiss)
        }
        // 类型必须是 `Any?`：写成 API 33 的回调类型，这个被 lambda 捕获的 var 就会把类型引用带进 dismiss 回调的
        // 字节码，API 32 及以下进入那个方法时直接 NoClassDefFoundError（ENGINEERING_RULES §2）。
        var predictiveBackCallback: Any? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            predictiveBackCallback = PredictiveBackApi33.animationCallback(
                onStarted = {
                    if (!dismissing) {
                        // 形变路径由 controller 自己接管在途动画并按当前速度续接，不能 cancel container 的动画。
                        if (bubbleController != null) {
                            bubbleController.beginPredictiveBack()
                        } else if (morphController != null) {
                            morphController.beginPredictiveBack()
                        } else {
                            container.animate().cancel()
                        }
                    }
                },
                onProgressed = { progress ->
                    if (!dismissing) {
                        if (bubbleController != null) {
                            bubbleController.progressPredictiveBack(progress)
                        } else if (morphController != null) {
                            morphController.progressPredictiveBack(progress)
                        } else {
                            container.scaleX = 1f - 0.05f * progress
                            container.scaleY = 1f - 0.05f * progress
                            container.alpha = 1f - 0.15f * progress
                            scrim?.alpha = container.alpha
                            lumen.notifyPositionChanged()
                        }
                    }
                },
                onCancelled = {
                    if (!dismissing) {
                        if (bubbleController != null) {
                            bubbleController.cancelPredictiveBack()
                        } else if (morphController != null) {
                            morphController.cancelPredictiveBack()
                        } else {
                            container.animate()
                                .scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(260L)
                                .setInterpolator(emphasizedDecelerate)
                                // ViewPropertyAnimator 的监听器是黏性的：显式设置时要把模糊与压暗带上，再加逐帧位置通知。
                                .setUpdateListener {
                                    backdropBlur?.apply(container.alpha)
                                    scrim?.alpha = container.alpha
                                    lumen.notifyPositionChanged()
                                }
                                .withEndAction { lumen.notifyPositionChanged() }
                                .start()
                            scrim?.animate()?.alpha(1f)?.setDuration(260L)?.setInterpolator(emphasizedDecelerate)?.start()
                        }
                    }
                },
                onInvoked = { requestDismiss(interactiveCommit = true) }
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            predictiveBackCallback = PredictiveBackApi33.plainCallback { requestDismiss() }
        }
        val callbackToRegister = predictiveBackCallback
        // 类型同样必须是 `Any?`（来源工程 2026-09-11 崩溃里被捕获的正是这个 var）。
        var registeredBackDispatcher: Any? = null
        /**
         * 把系统返回接到本呈现器的退场上。**必须在 `dialog.show()` 之后**：之前 decor 还没挂 `ViewRootImpl`，
         * API 33 没有缓存 attach 前注册的代理 dispatcher，注册会丢失，`Dialog.show()` 自己的 system 级默认回调
         * （瞬间消失、没有动画）就赢下手势派发，两条返回路径行为不一致。失败要留日志。
         */
        fun registerBackCallback() {
            if (callbackToRegister == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            if (registeredBackDispatcher != null) return
            runCatching {
                registeredBackDispatcher = PredictiveBackApi33.register(dialog, callbackToRegister)
            }.onFailure {
                Log.e(TAG, "register OnBackInvokedCallback failed; back gesture loses its animation", it)
            }
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) requestDismiss()
                true
            } else {
                false
            }
        }
        dialog.setOnDismissListener {
            releaseElasticInteraction()
            // 其他入口硬关（呈现新弹窗时的 activeDialog?.dismiss()）也要收掉在途 animator。
            morphController?.cancelMotion()
            bubbleController?.cancelMotion()
            backdropBlur?.clear()
            if (bubbleLayoutListener != null && root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(bubbleLayoutListener)
            }
            anchoredClosers.remove(dialog)
            surfaceInsets.remove(dialog)
            scrims.remove(dialog)
            // 硬关会把形变停在半路，父面板不能留着半透明的 alpha：它马上就要重新露出来。
            coveredContent?.alpha = 1f
            (coveredContent as? ModalCardRoot)?.clearExclusion()
            // 子面板收起后父面板重新露出来，它必须变回"当前弹窗"。
            if (activeDialog === dialog) {
                activeDialog = coveredParent?.takeIf { it.isShowing }
            }
            val callback = predictiveBackCallback
            // 注销要冲着**当时注册成功的那个** dispatcher；SDK 判断决定 PredictiveBackApi33 会不会被加载。
            val dispatcher = registeredBackDispatcher
            if (callback != null && dispatcher != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registeredBackDispatcher = null
                runCatching { PredictiveBackApi33.unregister(dispatcher, callback) }
            }
        }
        activeDialog = dialog
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        registerBackCallback()

        if (bubbleController != null) {
            // 在实际 Dialog 坐标和最终测量尺寸就绪后才开始，避免先闪一帧或从错误位置展开。
            root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (updateBubbleGeometry() || container.isLayoutRequested) return false
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!dialog.isShowing || bubbleController.isClosing) return true
                    bubbleLaidOut = true
                    bubbleController.prepareFirstFrame()
                    bubbleController.startEntry()
                    return true
                }
            })
            return
        }
        if (morphController != null && morphLayer != null) {
            // 必须在首帧绘制**之前**压到来源端，否则会先闪一帧完整卡片再跳回来源。
            morphLayer.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    // 先把卡片摆到父面板矩形上再压首帧：位置还没定就 prepareFirstFrame，展开端会按旧矩形算。
                    if (applyCoverPlacement() || (cover != null && container.isLayoutRequested)) return false
                    morphLayer.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!dialog.isShowing || morphController.isClosing) return true
                    if (!morphController.prepareFirstFrame()) {
                        // 尺寸未就绪或几何非法：直接落到展开端，不留半截形状。
                        morphController.snapToExpanded()
                        return true
                    }
                    morphLayer.post {
                        if (dialog.isShowing && !morphController.isClosing) morphController.startEntry()
                    }
                    return true
                }
            })
            return
        }
        container.post {
            container.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(260L)
                .setInterpolator(emphasizedDecelerate)
                // 无锚点弹窗借它自己的入场进度推模糊与压暗。缩放入场会移动卡片里玻璃表面的屏幕位置，而属性动画既不
                // 触发滚动回调、也不重录显示列表：不逐帧通知的话，按钮光影会在打开后整体跳一下。
                .setUpdateListener {
                    backdropBlur?.apply(container.alpha)
                    scrim?.alpha = container.alpha
                    lumen.notifyPositionChanged()
                }
                .withEndAction {
                    backdropBlur?.apply(1f)
                    scrim?.alpha = 1f
                    lumen.notifyPositionChanged()
                    notifyExpanded()
                }
                .start()
        }
    }

    private companion object {
        const val TAG = "Lumen-Modal"
    }
}

/**
 * 弹窗内容容器：宿主没给确定宽度（WRAP_CONTENT / 无约束）时，宽度不超过 [maxWidthPx]。
 * 确定宽度（`preferredWidth`、覆盖式子面板对齐父面板、气泡宽度）一律照办，不在这里改。
 */
private class ModalContainer(context: android.content.Context, private val maxWidthPx: Int) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = View.MeasureSpec.getMode(widthMeasureSpec)
        val size = View.MeasureSpec.getSize(widthMeasureSpec)
        val capped = if (maxWidthPx > 0 && mode != View.MeasureSpec.EXACTLY &&
            (mode == View.MeasureSpec.UNSPECIFIED || size > maxWidthPx)
        ) View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.AT_MOST) else widthMeasureSpec
        super.onMeasure(capped, heightMeasureSpec)
    }
}
