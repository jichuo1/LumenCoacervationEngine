package com.lumen.coacervation.engine.host

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.graphics.ColorUtils
import com.lumen.coacervation.engine.glow.GlowEngine
import com.lumen.coacervation.engine.material.ModernMaterialDrawables
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SkinId
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.runtime.ActivitySkinSession
import com.lumen.coacervation.engine.runtime.SkinSessionDiagnostics

/**
 * 一个 Activity 接入凝光视效引擎的全部入口（适配标准 §2）。
 *
 * 用组合而不是继承：任何 Activity 基类（`Activity`、`AppCompatActivity`、`ComponentActivity`、BetterAndroid 的
 * `AppViewsActivity`……）都能持有一个委托，并把下列生命周期与输入事件转发给它：
 *
 * | Activity 回调 | 委托方法 |
 * |---|---|
 * | `dispatchTouchEvent` | [onDispatchTouchEvent]（在 `super` 之前） |
 * | `onStart` / `onStop` | [onStart] / [onStop] |
 * | `onTrimMemory` / `onLowMemory` | [onTrimMemory] / [onLowMemory] |
 * | `onDestroy` | [onDestroy]（在 `super` 之前） |
 *
 * 不想手写转发可以直接继承 [LumenActivity]。
 *
 * 委托**不接管** `onCreate`、Window、contentView、系统栏、语言或重建时机；会话只在宿主显式调用 [prepare] 后创建，
 * 因此宿主的隐私授权门、早退分支都保持原样（未授权分支不得调用 [prepare]）。
 *
 * 线程：全部在主线程调用。
 *
 * @param paletteProvider 首次需要配色时调用一次，结果在本 Activity 生命周期内缓存。
 * @param effectTuningProvider 首次需要视效调参时调用一次，结果在本 Activity 生命周期内缓存；改动后重建 Activity 生效。
 *   默认 [LumenEffectTuning.DEFAULT]（引擎原样）。
 */
@MainThread
public class LumenActivityDelegate @JvmOverloads constructor(
    private val activity: Activity,
    private val paletteProvider: () -> LumenPalette,
    private val effectTuningProvider: () -> LumenEffectTuning = { LumenEffectTuning.DEFAULT }
) {
    private var session: ActivitySkinSession? = null
    private var paletteOrNull: LumenPalette? = null
    private var effectTuningOrNull: LumenEffectTuning? = null
    private var lifecycleEnded = false
    private val density: Float get() = activity.resources.displayMetrics.density

    /** 本 Activity 使用的配色；未准备会话时也可用（授权前的界面同样需要中性配色）。 */
    public val palette: LumenPalette
        get() = paletteOrNull ?: paletteProvider().also { paletteOrNull = it }

    /**
     * 本 Activity 使用的视效调参（边缘高光、长按拖动光晕）。与 [palette] 一样首次读取后缓存，
     * 会话、未准备时的静态表面、`LumenElasticInteraction` 的默认值都读它。
     */
    public val effectTuning: LumenEffectTuning
        get() = effectTuningOrNull ?: effectTuningProvider().also { effectTuningOrNull = it }

    /** 是否已准备会话。 */
    public val isPrepared: Boolean get() = session != null && !lifecycleEnded

    // ---------------- 会话 ----------------

    /**
     * 创建本 Activity 的视效会话：读取材质选择，申请高级材质渲染权，失败时准备柔光回退。幂等。
     * 只能在宿主自己的授权门、早退检查都通过之后调用。
     */
    public fun prepare() {
        if (lifecycleEnded || session != null) return
        session = ActivitySkinSession.create(activity, palette, effectTuning)
    }

    /**
     * 把会话绑定到可见的内容根 View（通常在 `setContentView` 之后）。
     *
     * @param onFailure 只在**整个**高级材质渲染器失败并已持久化回退时回调一次，且总在当前调用栈之外；
     *   宿主通常在这里提示并 `recreate()`。后端的正常降级（REFRACTION → BLUR → TRANSLUCENT）不会回调。
     * @return 未准备或 Activity 已结束返回 false。
     */
    public fun bindRoot(root: View, onFailure: (() -> Unit)? = null): Boolean {
        if (lifecycleEnded) return false
        return session?.bindRoot(root, onFailure) ?: false
    }

    /**
     * 当前在画的引擎，供 [com.lumen.coacervation.engine.glow.GlowFloatingChrome] 等组件现取现用。
     * 生命周期结束或未准备时为 null。**不得缓存**：高级材质中途失败后会换成柔光。
     */
    public val engine: GlowEngine?
        get() = if (lifecycleEnded) null else session?.engine

    // ---------------- 通知（适配标准 §4） ----------------

    /**
     * 在 Activity 的 `dispatchTouchEvent` 里、调用 `super` **之前**转发。
     * 高级材质据此把"抑制解除"这类重同步挪出手指按住的时段，避免新手势头几帧迟滞。
     */
    public fun onDispatchTouchEvent(event: MotionEvent) {
        if (lifecycleEnded) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> session?.notifyGestureActive(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> session?.notifyGestureActive(false)
        }
    }

    /**
     * 显式位移通知：用 `translationX/Y`、`scale`、属性动画移动了带引擎表面的 View 时，**每一帧**都要调用。
     * 属性动画不触发滚动回调、也不重录子 View 的显示列表；不通知的话玻璃采样会停在动画中途的位置。
     * 滚动位置的同步通知使用 [notifyScrollPositionChanged]，不提前消费本方法合并的动画批次。
     */
    public fun notifyPositionChanged() {
        if (!lifecycleEnded) session?.notifyPositionChanged()
    }

    /**
     * 在滚动容器实际更新 scrollX/Y 后调用，包含拖动、惯性和程序滚动。
     * 只同步刷新该容器后代中采样原点已过期的可见表面；零位移时不调用。
     * ViewTreeObserver 的窗口滚动监听仍作保底，调用方负责解绑自己的滚动回调。
     */
    public fun notifyScrollPositionChanged(scrollHost: View) {
        if (!lifecycleEnded) session?.notifyScrollPositionChanged(scrollHost)
    }

    /** 悬浮表面下方的内容层（必须是悬浮表面的兄弟而不是祖先），供柔光的软件透镜采样。 */
    public fun bindContentSource(view: View) {
        if (!lifecycleEnded) session?.bindContentSource(view)
    }

    // ---------------- 表面（适配标准 §5） ----------------

    /** 按语义角色取表面背景。未准备会话时返回等价的静态材质，调用方无需判断。 */
    public fun surface(color: Int, radiusDp: Float, role: SurfaceRole): Drawable =
        session?.surfaceBackground(color, radiusDp, role)
            ?: ModernMaterialDrawables.fallback(color, radiusDp.coerceAtLeast(0f) * density, density, role, isDark,
                effectTuning)

    public fun cardBackground(color: Int = palette.surface, radiusDp: Float = 15f): Drawable =
        surface(color, radiusDp, SurfaceRole.CARD)

    public fun floatingBackground(color: Int = palette.surface, radiusDp: Float = 28f): Drawable =
        surface(color, radiusDp, SurfaceRole.FLOATING)

    public fun topBarBackground(color: Int = palette.surface, radiusDp: Float = 0f): Drawable =
        surface(color, radiusDp, SurfaceRole.TOP_BAR)

    public fun selectionBackground(color: Int = palette.surface, radiusDp: Float = 22f): Drawable =
        surface(color, radiusDp, SurfaceRole.SELECTED_ITEM)

    public fun modalBackground(color: Int = palette.surface, radiusDp: Float = 28f): Drawable =
        surface(color, radiusDp, SurfaceRole.MODAL)

    /** 入口与全屏形变共享的表面；未生效高级材质时返回等价的静态背景。 */
    public fun motionSurfaceBackground(color: Int = palette.surface, radiusDp: Float): Drawable =
        surface(color, radiusDp, SurfaceRole.MOTION_SURFACE)

    /** 动态形变层只在高级材质已生效时接管；否则返回 null，由宿主保留自绘边界。 */
    public fun liquidMotionSurfaceBackgroundOrNull(color: Int = palette.surface, radiusDp: Float): Drawable? =
        if (isLiquidEffective) session?.surfaceBackground(color, radiusDp, SurfaceRole.MOTION_SURFACE) else null

    /** 悬浮栏里的圆形按钮、选中指示：轻量叠层，不做逐个光学采样。 */
    public fun chromeOverlayBackground(color: Int = palette.surface, radiusDp: Float, selected: Boolean = false): Drawable =
        ModernMaterialDrawables.chromeOverlay(color, radiusDp * density, density,
            if (selected) SurfaceRole.SELECTED_ITEM else SurfaceRole.FLOATING, isDark, effectTuning)

    /** 授权前也可用的窗口背景：只用配色，不读材质偏好、不做位图工作。 */
    public fun neutralWindowBackground(): Drawable = ModernMaterialDrawables.neutralWindow(palette)

    // ---------------- 控件（适配标准 §5.3） ----------------

    /**
     * 文字按钮换装：玻璃作为直接背景，涟漪放在前景（前景涟漪不会切断玻璃 Drawable 的 View 回调）。
     * 未准备会话时不做任何事。
     */
    public fun styleActionButton(view: TextView, filled: Boolean, radiusDp: Float = 20f) {
        if (!isPrepared) return
        val text = palette.textSecondary
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(text, 0x66), text)))
        view.backgroundTintList = null
        replaceBackgroundKeepingPadding(view,
            surface(palette.surface, radiusDp, if (filled) SurfaceRole.FILLED_BUTTON else SurfaceRole.TEXT_BUTTON))
        val mask = GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setColor(Color.WHITE)
        }
        view.foreground = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 0x33)),
            controlOutline(radiusDp, filled), mask)
    }

    /** 可选中的条目：选中时用 SELECTED_ITEM，否则 CARD。 */
    public fun styleSelectionControl(view: View, radiusDp: Float, selected: Boolean) {
        if (!isPrepared) return
        replaceBackgroundKeepingPadding(view,
            surface(palette.surface, radiusDp, if (selected) SurfaceRole.SELECTED_ITEM else SurfaceRole.CARD))
        view.foreground = controlOutline(radiusDp, selected)
    }

    /** 小语义标签：保留可读的实色字形，不做逐个光学采样。 */
    public fun styleStatusChip(view: TextView, accent: Int, radiusDp: Float = 9f) {
        if (!isPrepared) return
        replaceBackgroundKeepingPadding(view, GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ColorUtils.setAlphaComponent(palette.surface, 210),
                ColorUtils.setAlphaComponent(palette.surface, 160))).apply {
            cornerRadius = radiusDp * density
            setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(accent, 180))
        })
    }

    /** 控件描边：聚焦/按下时加粗加深。供可选模块 `lumen-controls` 与宿主自定义控件复用。 */
    public fun controlOutline(radiusDp: Float, emphasized: Boolean = false): Drawable {
        fun border(active: Boolean) = GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setColor(Color.TRANSPARENT)
            setStroke(((if (active) 2f else 1f) * density).toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(palette.primary, if (active || emphasized) 0x72 else 0x22))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), border(true))
            addState(intArrayOf(android.R.attr.state_pressed), border(true))
            addState(intArrayOf(), border(false))
        }
    }

    // ---------------- 回弹（适配标准 §5.4） ----------------

    /**
     * 让一个已在层级中的滚动 View 以前景内容参与系统 stretch（包进回弹视口，距离接到引擎的光学增益）。
     * 失败时保持原层级并返回 null——回弹只是修饰，不能拖垮页面。
     */
    public fun installStretch(scrollTarget: View, isStretchAllowed: () -> Boolean = { true }): View? =
        if (lifecycleEnded) null else session?.installStretchViewport(scrollTarget, isStretchAllowed)

    /** 结束一次回弹（例如翻页离开该页时），让视口立即归位。 */
    public fun finishStretch(view: View?) {
        if (!lifecycleEnded) session?.finishStretchViewport(view)
    }

    // ---------------- 状态与诊断 ----------------

    /** 持久化选择是否请求高级材质；未准备时为 false。 */
    public val isLiquidRequested: Boolean get() = session?.requestedSkin == SkinId.LIQUID

    /** 高级材质是否已在本 Activity 生效。 */
    public val isLiquidEffective: Boolean get() = session?.effectiveSkin == SkinId.LIQUID

    /** 当前实际渲染后端名称（REFRACTION / BLUR / TRANSLUCENT）；柔光或未准备时为 null。 */
    public val backendName: String? get() = session?.liquidBackendName

    /** 只读诊断摘要，不暴露渲染器或 View。 */
    public fun diagnostics(): SkinSessionDiagnostics? = session?.diagnostics

    // ---------------- 生命周期转发 ----------------

    public fun onStart() {
        session?.onActivityStarted()
    }

    public fun onStop() {
        session?.onActivityStopped()
    }

    public fun onTrimMemory(level: Int) {
        session?.onTrimMemory(level)
    }

    public fun onLowMemory() {
        session?.onLowMemory()
    }

    /** 在 Activity `onDestroy` 调用 `super` 之前调用。幂等。 */
    public fun onDestroy() {
        lifecycleEnded = true
        val current = session
        session = null
        current?.close()
    }

    private val isDark: Boolean get() = ColorUtils.calculateLuminance(palette.background) < .5

    private fun replaceBackgroundKeepingPadding(view: View, drawable: Drawable) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.background = drawable
        view.setPadding(left, top, right, bottom)
    }
}

