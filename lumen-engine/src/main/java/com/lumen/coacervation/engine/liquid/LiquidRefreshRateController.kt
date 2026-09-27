package com.lumen.coacervation.engine.liquid

import android.app.Activity
import android.view.View
import androidx.annotation.MainThread
import kotlin.math.abs

/**
 * 高级材质实时档的窗口刷新率：面板支持档位 → 热降档上限 → 吞吐降档上限，取最严格的一方申请给
 * 窗口，并同步 ADPF 目标周期；停止/关闭时只还原**自己申请过**的值。
 *
 * 2026-09-23 凝光视效引擎重构时从 [LiquidActivityRenderer] 拆出，逻辑逐行不变。
 *
 * 吞吐降档的判据见 [LiquidCaptureThroughputPolicy]；它只该在"画面持续变化、采集真跟不上"时
 * 生效——静止门控丢弃的相同截图必须 [resetThroughput]，否则采集管线自身的节奏（天然只有刷新率
 * 的 1/3–1/4）会被误判为跟不上，把整个窗口压到 60Hz。
 */
@MainThread
internal class LiquidRefreshRateController(
    private val activity: Activity,
    private val performance: LiquidPerformanceController?
) {
    /** 当前目标刷新率对应的帧周期；实时采集的节奏按它排。 */
    var frameIntervalNanos: Long = LiquidRealtimeCapturePolicy.frameIntervalNanos(60f)
        private set
    private var targetRefreshRate = 60f

    /** 实测采集吞吐；只统计连续成功完成之间的间隔，失败/熔断/重建/相同截图都会重置。 */
    private val throughput = LiquidCaptureThroughputTracker()

    /** 吞吐自适应给出的刷新率上限；`null` 表示尚未降档。会话内只降不升。 */
    private var throughputCap: Float? = null

    private var originalPreferredRefreshRate: Float? = null
    private var appliedPreferredRefreshRate: Float? = null
    private var originalPreferredDisplayModeId: Int? = null
    private var appliedPreferredDisplayModeId: Int? = null

    /** 根据 display mode 与热状态请求窗口刷新率，并同步 ADPF 目标周期。 */
    @Suppress("DEPRECATION")
    fun configure(
        root: View,
        thermalStatus: Int = performance?.currentThermalStatus
            ?: LiquidPerformancePolicy.THERMAL_STATUS_NONE
    ) {
        val display = root.display ?: return
        val currentMode = display.mode
        val matchingModes = display.supportedModes.asSequence()
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .toList()
        val supportedRates = matchingModes.asSequence()
            .map { it.refreshRate }
            .toList()
        val requestedRefreshRate = LiquidRealtimeCapturePolicy.targetRefreshRate(
            currentRefreshRate = display.refreshRate,
            supportedRefreshRates = supportedRates
        )
        val thermalLimited = LiquidPerformancePolicy.targetRefreshRate(
            requestedRefreshRate = requestedRefreshRate,
            thermalStatus = thermalStatus
        )
        // 吞吐上限与热上限取更严格的一方；两者都只收紧、不放宽设备原始能力。
        targetRefreshRate = throughputCap
            ?.let { minOf(thermalLimited, it) }
            ?: thermalLimited
        frameIntervalNanos = LiquidRealtimeCapturePolicy.frameIntervalNanos(targetRefreshRate)
        performance?.updateTargetWorkDuration(frameIntervalNanos)
        val attributes = activity.window.attributes
        if (originalPreferredRefreshRate == null) {
            originalPreferredRefreshRate = attributes.preferredRefreshRate
            originalPreferredDisplayModeId = attributes.preferredDisplayModeId
        }
        val targetMode = matchingModes
            .filter { abs(it.refreshRate - targetRefreshRate) <= 0.5f }
            .maxByOrNull { it.refreshRate }
        val targetModeId = targetMode?.modeId ?: 0
        if (abs(attributes.preferredRefreshRate - targetRefreshRate) >= 0.01f ||
            attributes.preferredDisplayModeId != targetModeId
        ) {
            attributes.preferredRefreshRate = targetRefreshRate
            attributes.preferredDisplayModeId = targetModeId
            activity.window.attributes = attributes
        }
        appliedPreferredRefreshRate = targetRefreshRate
        appliedPreferredDisplayModeId = targetModeId
    }

    /** 失败、相同截图、缓冲重建、热状态变化：旧样本不再代表当前的稳态吞吐。 */
    fun resetThroughput() = throughput.reset()

    /** 新前台会话重新从设备最高档开始探测；只降不升的策略靠会话边界自愈。 */
    fun resetSession() {
        throughput.reset()
        throughputCap = null
    }

    /**
     * 记录一次成功完成，必要时按实测吞吐降一档刷新率。
     *
     * 只降不升：升档需要先请求更高刷新率才能观察可行性，"试探→失败→降回"会在相邻档位之间反复
     * 切换且肉眼可见。会话重建、热状态变化与缓冲重建都会重置统计，届时重新从设备最高档开始。
     */
    fun onCaptureCompleted(completionNanos: Long, root: View?) {
        val shouldStepDown = throughput.onCaptureCompleted(
            nowNanos = completionNanos,
            currentTargetFps = targetRefreshRate
        )
        if (!shouldStepDown) return
        val boundRoot = root ?: return
        val display = boundRoot.display ?: return
        val currentMode = display.mode
        val supported = display.supportedModes.asSequence()
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .map { it.refreshRate }
            .toList()
        val next = LiquidCaptureThroughputPolicy.stepDownTarget(
            currentTargetFps = targetRefreshRate,
            measuredFps = throughput.measuredFramesPerSecond,
            supportedRefreshRates = supported
        )
        throughput.reset()
        if (next >= targetRefreshRate - 0.5f) return
        throughputCap = next
        configure(boundRoot)
    }

    /** 只还原自己申请过、且此刻仍是自己那个值的窗口属性。 */
    @Suppress("DEPRECATION")
    fun restore() {
        val applied = appliedPreferredRefreshRate ?: return
        val original = originalPreferredRefreshRate ?: return
        val attributes = activity.window.attributes
        val appliedModeId = appliedPreferredDisplayModeId
        val originalModeId = originalPreferredDisplayModeId
        var changed = false
        if (abs(attributes.preferredRefreshRate - applied) < 0.01f) {
            attributes.preferredRefreshRate = original
            changed = true
        }
        if (appliedModeId != null && originalModeId != null &&
            attributes.preferredDisplayModeId == appliedModeId
        ) {
            attributes.preferredDisplayModeId = originalModeId
            changed = true
        }
        if (changed) {
            activity.window.attributes = attributes
        }
        appliedPreferredRefreshRate = null
        originalPreferredRefreshRate = null
        appliedPreferredDisplayModeId = null
        originalPreferredDisplayModeId = null
    }
}
