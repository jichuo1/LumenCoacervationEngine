package com.lumen.coacervation.engine

import android.content.Context
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.liquid.LiquidRealtimeCaptureStore
import com.lumen.coacervation.engine.model.SkinId
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureHub
import com.lumen.coacervation.engine.runtime.SkinRepository

/**
 * 引擎的持久化命名。引擎只在宿主应用的私有目录里存三类东西：材质选择与健康状态、实时取样开关、
 * 自定义背景图。全部可以改名，便于从旧版本迁移（沿用旧文件名即可无损接管用户已有设置）。
 *
 * @property skinPreferences 材质选择、激活尝试与回退状态机（SharedPreferences 文件名）。
 * @property realtimeCapturePreferences 全屏实时取样开关（SharedPreferences 文件名）。
 * @property backgroundPreferences 自定义背景配置（SharedPreferences 文件名）。
 * @property backgroundAssetDirectory 自定义背景图副本所在的 `filesDir` 子目录名。
 */
public data class LumenStorageNames(
    val skinPreferences: String = "lumen_skin_preferences",
    val realtimeCapturePreferences: String = "lumen_realtime_capture_preferences",
    val backgroundPreferences: String = "lumen_background_preferences",
    val backgroundAssetDirectory: String = "lumen_background"
) {
    init {
        listOf(skinPreferences, realtimeCapturePreferences, backgroundPreferences, backgroundAssetDirectory).forEach {
            require(it.isNotBlank() && '/' !in it && '\\' !in it && it != "." && it != "..") { "Illegal storage name: $it" }
        }
        require(setOf(skinPreferences, realtimeCapturePreferences, backgroundPreferences).size == 3) {
            "Preference file names must be distinct"
        }
    }
}

/** 引擎全局配置。 */
public data class LumenEngineConfig(
    val storage: LumenStorageNames = LumenStorageNames()
)

/**
 * 凝光视效引擎入口。
 *
 * 可选地在 `Application.onCreate` 里调用一次 [configure]；不调用就使用默认配置。配置一旦被读取（第一次创建会话
 * 或读写任何引擎偏好）就冻结，之后再调用 [configure] 会抛出异常——避免同一进程里前后读写两套文件。
 */
public object LumenEngine {
    /** 引擎版本（语义化版本）。 */
    public const val VERSION: String = BuildConfig.LUMEN_VERSION

    /**
     * 引擎契约版本：[com.lumen.coacervation.engine.glow.GlowEngine] 接口或适配标准有不兼容变化时递增。
     * 宿主可以在接入层断言它，防止升级后静默改变行为。
     */
    public const val CONTRACT_VERSION: Int = 1

    @Volatile private var current: LumenEngineConfig = LumenEngineConfig()
    @Volatile private var frozen = false

    @MainThread
    @JvmStatic
    public fun configure(config: LumenEngineConfig) {
        synchronized(this) {
            check(!frozen || config == current) {
                "LumenEngine.configure must be called before the engine is first used"
            }
            current = config
        }
    }

    /** 当前配置；读取即冻结。 */
    @JvmStatic
    public val config: LumenEngineConfig
        get() = synchronized(this) {
            frozen = true
            current
        }

    // ---------------- 进程级能力（适配标准 §3） ----------------

    /** 当前持久化请求的材质。高级材质可能因设备或健康回退而实际未生效，以会话的诊断为准。 */
    @JvmStatic
    public fun requestedMaterial(context: Context): SkinId = SkinRepository.resolveRequestedSkin(context)

    /**
     * 切换材质。写入成功后宿主必须重建使用引擎的 Activity（例如 `recreate()`）才会生效。
     *
     * 切到 [SkinId.LIQUID] 进入"待确认"状态：新会话首个成功的可见绘制才确认健康；若进程在确认前死亡或渲染器
     * 失败，下次启动自动回退到 [SkinId.MATERIAL_YOU]，不会反复崩溃。
     *
     * @param realtimeCapture 同时写入全屏实时取样开关；null 表示不改。来源工程在打开高级材质时一并打开实时取样。
     * @return 是否已持久化。false 时宿主应把界面开关恢复原状并提示保存失败。
     */
    @MainThread
    @JvmStatic
    @JvmOverloads
    public fun selectMaterial(context: Context, material: SkinId, realtimeCapture: Boolean? = null): Boolean {
        if (SkinRepository.resolveRequestedSkin(context) != material) {
            val result = runCatching { SkinRepository.beginSelection(context, material) }.getOrNull()
            if (result?.persisted != true) return false
        }
        if (realtimeCapture != null) LiquidRealtimeCaptureStore.setEnabled(context, realtimeCapture)
        return true
    }

    /** 全屏实时取样（高级材质下用 PixelCopy 采样窗口内容）是否开启。只影响高级材质。 */
    @JvmStatic
    public fun isRealtimeCaptureEnabled(context: Context): Boolean = LiquidRealtimeCaptureStore.isEnabled(context)

    /** 写入全屏实时取样开关；需重建 Activity 生效。@return 是否已持久化。 */
    @JvmStatic
    public fun setRealtimeCaptureEnabled(context: Context, enabled: Boolean): Boolean =
        LiquidRealtimeCaptureStore.setEnabled(context, enabled)

    /**
     * 内存压力：释放全部会话的图形资源（实时取样缓冲、预缩放底图、内容节点显示列表）并把高级材质降到
     * 零额外资源的 TRANSLUCENT 后端；稳定底图保留（它就是用户可见的背景）。
     *
     * 宿主在进程级内存告急时调用，典型位置是 `Application.onTrimMemory` 收到 `TRIM_MEMORY_RUNNING_CRITICAL`（15）
     * 或 `TRIM_MEMORY_COMPLETE`（80）。**不要**按"数值越大越严重"判断：20（`UI_HIDDEN`）只是普通切后台，
     * 在那里释放会让用户每次切后台都永久丢掉高级材质。Activity 级的 onTrimMemory/onLowMemory 已由
     * [com.lumen.coacervation.engine.host.LumenActivityDelegate] 转发，不需要重复调用。
     */
    @MainThread
    @JvmStatic
    public fun releaseGraphics() {
        LumenMemoryPressureHub.releaseGraphics()
    }
}
