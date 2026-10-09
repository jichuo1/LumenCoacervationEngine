package com.lumen.coacervation.engine.glow

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.liquid.LiquidStretchEdge
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.model.SkinId
import com.lumen.coacervation.engine.model.SurfaceRole

/**
 * 凝光视效引擎：一个 Activity 全部视效能力的统一契约。
 *
 * 两套材质各自实现它——柔光（`FrostedMaterialRenderer`：静态环境磨砂 + 软件透镜采样）与
 * 高级材质（`LiquidActivityRenderer`：RuntimeShader 折射 + PixelCopy 实时取样）。
 * `ActivitySkinSession` 只和这个接口打交道，不再逐个方法按皮肤分支。
 *
 * 能力差异用**默认空实现**表达，而不是让调用方判断皮肤：
 * - [bindContentSource]：只有柔光需要（它的透镜要软件重绘下方内容层；高级材质直接截窗口）。
 * - [onStretchDistance] / [notifyGestureActive]：只有高级材质有光学响应与重同步时机。
 * - [backendName] / [backendDegradeReason]：只有高级材质有可降级的渲染后端。
 * - [wantsScrollEdgeDissolve] / [drawWindowBackdrop] / [floatingSurfaceOptics] /
 *   [setSurfaceLegibility] / [supportsSurfaceBackdrop] / [setSurfaceBackdrop]：悬浮栏可读性
 *   （滚动边缘溶解、按下方内容自适应、内容节点玻璃），由 [GlowFloatingChrome] 统一驱动，
 *   见 2026-09-23 悬浮栏可读性改造。
 *
 * 线程：全部在主线程调用。
 */
@MainThread
public interface GlowEngine : AutoCloseable {
    /** 引擎对应的皮肤身份。 */
    val skin: SkinId

    /**
     * 绑定窗口根：装上根背景、监听与首帧健康确认。
     *
     * 不会失败的引擎（柔光）忽略 [callbacks]；会失败的引擎在首个成功可见绘制后回调
     * [GlowEngineCallbacks.onFirstVisibleDraw]，在不可恢复时回调 [GlowEngineCallbacks.onFatalFailure]。
     */
    fun bindRoot(root: View, callbacks: GlowEngineCallbacks): Boolean

    /** 某一角色表面的背景 Drawable：同一角色在两套材质下各自的实现。 */
    fun surface(fallbackColor: Int, radiusDp: Float, role: SurfaceRole): Drawable

    /** 悬浮表面下方的内容层。只有需要自己重绘下方内容的引擎关心。 */
    fun bindContentSource(view: View) = Unit

    /** 显式动画/形变的位移通知（滚动由引擎自己监听）。 */
    fun notifyPositionChanged()

    /** Actual scroll offset already changed: refresh stale descendant sampling origins synchronously. */
    fun notifyScrollPositionChanged(scrollHost: View) = Unit

    /** 手指按下/抬起。 */
    fun notifyGestureActive(active: Boolean) = Unit

    /**
     * 本引擎是否需要回弹视口。视口本身与皮肤无关（视觉拉伸由平台 EdgeEffect 完成），
     * 但高级材质只在 API 31+ 硬件加速下才有意义，旧设备上保持原层级不包裹。
     */
    val wantsStretchViewport: Boolean get() = true

    /** 回弹距离，供光学增益使用。 */
    fun onStretchDistance(distance: Float, edge: LiquidStretchEdge) = Unit

    fun onStart()
    fun onStop()
    fun onTrimMemory(level: Int)
    fun onLowMemory()

    /** 诊断：当前渲染后端名；没有后端概念的引擎为 null。 */
    val backendName: String? get() = null

    /** 诊断：比当前后端更优先的候选为什么不可用；未降级为 null。 */
    val backendDegradeReason: String? get() = null

    /**
     * 是否在悬浮栏两端做**滚动边缘溶解**：内容滚进栏下方时渐隐进窗口底图（Apple 称
     * scroll edge effect）。需要 [drawWindowBackdrop] 能原样画出底图。
     */
    val wantsScrollEdgeDissolve: Boolean get() = false

    /** 窗口底图的换代计数：底图被替换（尺寸变化、自定义图片加载完成）时递增，依赖它的绘制需要重录。 */
    val windowBackdropGeneration: Long get() = 0L

    /**
     * 把窗口底图（根背景）按屏幕位置原样画进 [canvas] 的 [bounds]，逐像素乘以 [alphaMask] 的 alpha
     * （null 表示不透明）再乘 [alpha]。[host] 是 [canvas] 局部坐标所属的 View，用来换算到根坐标。
     *
     * 在已合成画面上叠一层透明度为 a 的底图，与"把内容透明度乘 (1-a)"在预乘合成下完全等价，
     * 却只是一次带着色器的绘制、不需要离屏图层。
     *
     * @return false 表示当前没有可原样绘制的底图，调用方应跳过效果。
     */
    fun drawWindowBackdrop(
        canvas: Canvas,
        host: View,
        bounds: RectF,
        alphaMask: Shader?,
        alpha: Float
    ): Boolean = false

    /**
     * 悬浮表面的光学参数，供 [GlowLegibilityPolicy] 计算可读性补偿；null 表示本引擎不做自适应
     * （调用方据此连探针都不启动）。
     */
    fun floatingSurfaceOptics(): GlowSurfaceOptics? = null

    /** 某个悬浮表面下方内容的可读性补偿；null 或 [GlowLegibility.NEUTRAL] 表示撤销。 */
    fun setSurfaceLegibility(host: View, legibility: GlowLegibility?) = Unit

    /**
     * 悬浮栏能否改用内容节点实时取样（B 期，API 31+ 硬件加速）。为 true 时调用方打开
     * [GlowBackdropTarget.contentCaptureEnabled] 并用 [setSurfaceBackdrop] 登记栏。
     * 节点路径出错后会永久变回 false，调用方应随之关掉录制。
     */
    val supportsSurfaceBackdrop: Boolean get() = false

    /** 是否还支持栏内按钮的完整相对矩阵；默认不增加额外的 GPU 效果层。 */
    val supportsDescendantSurfaceBackdrop: Boolean get() = false

    /** 登记（或以 null 撤销）悬浮栏 [host] 的内容节点取样源。 */
    fun setSurfaceBackdrop(host: View, target: GlowBackdropTarget?) = Unit
}

/** [GlowEngine.bindRoot] 的健康回调；只在主线程、且只在绘制调用栈之外触发。 */
public class GlowEngineCallbacks(
    val onFirstVisibleDraw: () -> Unit,
    val onFatalFailure: () -> Unit
)

/**
 * 回弹视口的统一安装：把滚动容器包进 [LiquidStretchViewport]，距离回调接到引擎。
 *
 * 失败时保持原层级、返回 null，不上报皮肤失败——回弹只是修饰，不能拖垮页面。
 */
@MainThread
internal fun GlowEngine.installStretchViewport(
    scrollTarget: View,
    isStretchAllowed: () -> Boolean
): View? {
    if (!wantsStretchViewport) return null
    return runCatching {
        LiquidStretchViewport.installAround(
            scrollTarget = scrollTarget,
            isStretchAllowed = isStretchAllowed,
            onStretchDistance = ::onStretchDistance
        )
    }.getOrNull()
}

/** 回弹视口收尾（页面切换、弹窗接管等）：立即归零形变。 */
@MainThread
internal fun finishStretchViewport(view: View?) {
    (view as? LiquidStretchViewport)?.finishStretch()
}
