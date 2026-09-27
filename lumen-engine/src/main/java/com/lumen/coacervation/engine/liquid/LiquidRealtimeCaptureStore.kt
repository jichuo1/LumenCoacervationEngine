package com.lumen.coacervation.engine.liquid

import com.lumen.coacervation.engine.LumenEngine
import android.annotation.SuppressLint
import android.content.Context

/** 全屏实时取样是本机界面效果（取决于这台设备的 GPU 与散热），宿主不应把它放进跨设备同步或备份的设置。 */
internal object LiquidRealtimeCaptureStore {
    // 来源工程为 ui_liquid_effect_preferences；见 LumenStorageNames。
    private val PREF_FILE: String get() = LumenEngine.config.storage.realtimeCapturePreferences
    private const val KEY_ENABLED = "fullscreen_realtime_capture_enabled"

    fun isEnabled(context: Context): Boolean = runCatching {
        preferences(context).getBoolean(KEY_ENABLED, false)
    }.getOrDefault(false)

    @SuppressLint("UseKtx") // 必须检查同步落盘与读回结果，切换后 Activity 会立即重建。
    fun setEnabled(context: Context, enabled: Boolean): Boolean = runCatching {
        val preferences = preferences(context)
        preferences.edit().putBoolean(KEY_ENABLED, enabled).commit() &&
            preferences.getBoolean(KEY_ENABLED, !enabled) == enabled
    }.getOrDefault(false)

    private fun preferences(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(
            PREF_FILE,
            Context.MODE_PRIVATE
        )
}
