package com.lumen.coacervation.engine.liquid

import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Liquid 表面使用的视觉档位；标准档保持既有观感，实时档提高折射与高光强度。 */
internal enum class LiquidEffectProfile {
    STANDARD,
    REALTIME_CAPTURE
}

internal data class LiquidRealtimeCaptureSize(
    val width: Int,
    val height: Int
) {
    val pixels: Long
        get() = width.toLong() * height.toLong()
}

/**
 * 一次采样结果的处理结论。
 *
 * "本帧没有可见玻璃"必须与"采样失败"分开：前者是完全正常的状态（长列表把所有卡片滚出可视区、
 * 转场中 alpha 归零），若按失败计数，连续 4 帧就会永久熔断整个 Activity 的实时效果。
 */
internal enum class LiquidCaptureOutcome {
    /** 抑制遮罩已应用，可直接作为新 backdrop。 */
    SUPPRESSED,

    /** 本帧没有任何可见玻璃表面，截图里也就不含自身反馈，可原样采用且不计失败。 */
    NO_GLASS_VISIBLE,

    /** 真实失败：source 已关闭、root 尺寸非法或稳定底图缺失。 */
    FAILED
}

/**
 * 全屏实时取样的纯策略边界。
 *
 * 源区域始终覆盖完整 Activity 根层；目标位图按"像素预算"而不是固定倍率采样。
 * 三缓冲让 PixelCopy 不写入当前或上一帧仍可能被 RenderThread 引用的 Bitmap。
 */
internal object LiquidRealtimeCapturePolicy {
    const val MAX_TARGET_FRAMES_PER_SECOND = 120f
    const val RETRY_DELAY_MS = 120L
    const val INITIAL_DELAY_MS = 100L
    const val BUFFER_COUNT = 3
    const val MAX_CONSECUTIVE_FAILURES = 4
    // Owned optical output is replaced, never recursively mixed back into the next input frame.
    const val BASE_SUPPRESSION_ALPHA = 0xFF

    /**
     * 内容位移静默窗口：PixelCopy 单飞回读至少滞后一帧，滚动/形变中展示实时截屏会把
     * 旧位置的文字折射进玻璃表面，形成沿滑动方向偏移的残影。位移活跃期间玻璃改采稳定
     * 底图；最后一次位移回调后超过该时长未再位移，才放行下一次实时采集。
     *
     * 该窗口同时是防抖：手势中途短暂停顿（指尖滞留、惯性换段）若达到阈值就会解除抑制、
     * 纹理重绑并整组表面重录，随后下一次位移又抑制——一次手势里多次乒乓，边缘高光随之
     * 明暗闪动。96ms 内的停顿在人手滑动里很常见，放宽到 160ms 把这类中段停顿吸收进
     * 同一次抑制周期，只在手势真正结束后才切回实时采样。
     */
    const val SCROLL_QUIET_MS = 160L

    /**
     * 采样像素预算。
     *
     * 旧实现用固定 `0.72` 倍率，在 1440×3200 面板上会产出 2,389,248 像素——正好顶到当时的
     * 240 万上限，单缓冲 9.11 MiB、三缓冲 27.3 MiB，120Hz 下仅 PixelCopy 回读就约 1.07 GiB/s。
     * 倍率固定意味着成本随面板分辨率平方增长，而这张图最终要经过 34dp 折射带、8.5dp 内透镜和
     * 5.5dp 散射的主动扭曲，高频细节本来就会被软化；旧实时档的 1.8dp 色散已在后续校准中关闭。
     *
     * 改为预算制后缓冲尺寸与面板分辨率解耦。为给 120Hz 滑动留出稳定余量，1080×2400
     * 与 1440×3200 都收敛到约 671×1490：单缓冲约 3.81 MiB、三缓冲约 11.44 MiB。
     * [MAX_TARGET_SCALE] 保证低分屏不会被反向放大到超过原有清晰度。
     */
    const val TARGET_SAMPLE_PIXELS = 1_000_000L
    const val MIN_SAMPLE_PIXELS = 240_000L

    private const val MAX_TARGET_SCALE = 0.72f
    private const val DEFAULT_REFRESH_RATE = 60f

    /**
     * 超过该面积的玻璃表面改用 2 抽样散射。
     *
     * 全屏模态在 1440p 上是 4.6 MP，按每像素 7 次 `content.eval` 计算单帧就是 3200 万次纹理
     * 取样；卡片级表面通常不到 1 MP，继续用 4 抽样。阈值取在两者之间。
     */
    private const val REDUCED_SCATTER_AREA_PX = 1_800_000L
    private const val MAX_STRETCH_DISTANCE = 0.18f
    private const val MAX_STRETCH_OPTICAL_BOOST = 0.85f

    fun isSupported(sdkInt: Int, hardwareAccelerated: Boolean): Boolean =
        sdkInt >= 31 && hardwareAccelerated

    /**
     * @param pixelBudget 允许调用方按热状态收紧预算；始终不超过 [MAX_TARGET_SCALE] 对应的尺寸。
     */
    fun resolveSize(
        fullWidth: Int,
        fullHeight: Int,
        pixelBudget: Long = TARGET_SAMPLE_PIXELS
    ): LiquidRealtimeCaptureSize {
        require(fullWidth > 0 && fullHeight > 0) { "Capture dimensions must be positive" }
        val budget = pixelBudget.coerceAtLeast(MIN_SAMPLE_PIXELS)
        var width = (fullWidth * MAX_TARGET_SCALE).roundToInt().coerceAtLeast(1)
        var height = (fullHeight * MAX_TARGET_SCALE).roundToInt().coerceAtLeast(1)
        val pixels = width.toLong() * height.toLong()
        if (pixels > budget) {
            val scale = sqrt(budget.toDouble() / pixels.toDouble())
            width = (width * scale).roundToInt().coerceAtLeast(1)
            height = (height * scale).roundToInt().coerceAtLeast(1)
            while (width.toLong() * height.toLong() > budget) {
                if (width >= height) width -= 1 else height -= 1
            }
        }
        return LiquidRealtimeCaptureSize(width, height)
    }

    /** 大面积表面降低散射抽样数；成本按表面像素面积判定，与后端和档位无关。 */
    fun useReducedScatterTaps(widthPx: Int, heightPx: Int): Boolean =
        widthPx.toLong() * heightPx.toLong() > REDUCED_SCATTER_AREA_PX

    /** 优先采用面板真实支持且不超过 120Hz 的最高档，异常 display 信息安全回退到 60Hz。 */
    fun targetRefreshRate(
        currentRefreshRate: Float,
        supportedRefreshRates: Iterable<Float>
    ): Float {
        val supported = supportedRefreshRates
            .filter { it.isFinite() && it > 0f && it <= MAX_TARGET_FRAMES_PER_SECOND + 0.5f }
            .maxOrNull()
        if (supported != null) return supported.coerceAtMost(MAX_TARGET_FRAMES_PER_SECOND)
        return currentRefreshRate
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceAtMost(MAX_TARGET_FRAMES_PER_SECOND)
            ?: DEFAULT_REFRESH_RATE
    }

    fun frameIntervalNanos(refreshRate: Float): Long {
        val safeRate = refreshRate
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceAtMost(MAX_TARGET_FRAMES_PER_SECOND)
            ?: DEFAULT_REFRESH_RATE
        return (1_000_000_000.0 / safeRate.toDouble()).roundToLong().coerceAtLeast(1L)
    }

    fun isFrameDue(frameTimeNanos: Long, nextCaptureNanos: Long): Boolean =
        frameTimeNanos >= nextCaptureNanos

    /**
     * 回弹强度的量化步长。
     *
     * 强度每变一次，渲染层就要把**整组可见玻璃表面**重录一遍（uniform 要跟着更新）。
     * 而系统 stretch 距离是连续衰减的：不量化的话回弹期间几乎每一帧都越过发布门，
     * 真机 102 秒滑动实测——坏帧的 draw 录制中位 **3.43ms**，是全局中位 1.19ms 的近 3 倍，
     * 4% 的帧越过系统 deadline，表现为"滑动偶发不跟手"。
     *
     * 0.03 在 0.85 的总行程上约 28 级。它只是 `edgeBoost` 的乘数，而边缘光本身的量级是
     * 菲涅尔 0.025 / 镜面 0.06——一级的最终像素亮度变化在千分之一量级，肉眼不可辨。
     * 与 `ModalBackdropBlur.RADIUS_STEP_PX` 同一套路：**per-frame 写进渲染状态的量必须量化**。
     */
    const val STRETCH_INTENSITY_STEP = 0.03f

    /**
     * 低端死区：增益小于它就按"没有回弹"发布。
     *
     * 弹簧的**尾段占了整条回弹的绝大多数帧**，而那时增益已经小到看不见——0.06 只有
     * 总行程 0.85 的 7%，对应的最终像素变化在千分之一量级。不设死区的话，尾段每跨过
     * 一个量化级就要把整组可见玻璃表面重录一遍；短页面里所有表面都在屏幕上，一次都省不掉。
     */
    const val STRETCH_INTENSITY_DEAD_ZONE = 0.06f

    /** 系统 stretch 距离本身连续；smoothstep 只放大强度，不引入新的回弹时长或振荡。 */
    fun stretchOpticalIntensity(distance: Float): Float {
        val normalized = (distance.coerceAtLeast(0f) / MAX_STRETCH_DISTANCE).coerceIn(0f, 1f)
        val eased = normalized * normalized * (3f - 2f * normalized)
        val boost = MAX_STRETCH_OPTICAL_BOOST * eased
        if (boost < STRETCH_INTENSITY_DEAD_ZONE) return 1f
        // 量化到整数级：距离为 0 时 boost 也是 0，终态恒为精确的 1f，不会卡在半亮。
        val steps = (boost / STRETCH_INTENSITY_STEP).roundToLong()
        return 1f + steps * STRETCH_INTENSITY_STEP
    }

    fun shouldSuspend(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= MAX_CONSECUTIVE_FAILURES

    /**
     * 静止门控：连续多少张截图与**当前绑定的那张**逐像素相同，才判定画面静止、停止采集。
     *
     * 旧实现没有这道门：只要 Activity 可见就按刷新率不停 PixelCopy，每张完成后把**全部**玻璃
     * 表面失效重画——真机静止 5 秒渲染 154 帧、每帧 GPU 10ms（柔光同条件 0 帧）。静止时截图
     * 内容不变、shader 的抖动只依赖像素坐标，重画出来是同一帧，所以跳过它视觉上无损。
     *
     * 取 2 而不是 1：窗口刚画完的那一帧可能还没被合成，第一张"相同"可能只是截到了旧帧。
     */
    const val IDLE_CONFIRMATIONS = 2

    /**
     * 窗口有新绘制后等几帧再截：让触发唤醒的那一帧先完成合成。
     */
    const val WAKE_SETTLE_FRAMES = 2

    /**
     * 静止期的兜底探测间隔。窗口没有任何绘制就不会被唤醒，而纯 RenderThread 动画（硬件涟漪等）
     * 改变像素却不经过 UI 线程绘制；每秒探测一次，把这类变化的滞后上限钉在 1 秒。
     */
    const val IDLE_PROBE_MS = 1000L

    /**
     * 本张截图是否可以当作"画面没变"丢弃。
     *
     * 只有当比较基准正是**当前绑定给后端**的那张实时截图时才成立：抑制期/缓冲重建后后端绑的是
     * 稳定底图，此时哪怕内容恰好相同也必须绑回实时截图，否则玻璃会一直停在磨砂观感。
     */
    fun isUnchanged(
        comparedAgainstBoundSource: Boolean,
        samplingSuppressed: Boolean,
        pixelsIdentical: () -> Boolean
    ): Boolean = comparedAgainstBoundSource && !samplingSuppressed && pixelsIdentical()

    /** 连续相同的张数达到阈值即进入静止。 */
    fun shouldEnterIdle(identicalStreak: Int): Boolean = identicalStreak >= IDLE_CONFIRMATIONS
}
