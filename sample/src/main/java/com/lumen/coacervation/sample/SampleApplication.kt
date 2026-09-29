package com.lumen.coacervation.sample

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.appcompat.app.AppCompatDelegate
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.LumenEngineConfig
import com.lumen.coacervation.engine.LumenStorageNames

/** 适配标准 §3：进程级接线。 */
class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 可选：改存储文件名（例如从旧版本迁移时沿用旧名）。必须在第一次使用引擎之前调用。
        LumenEngine.configure(LumenEngineConfig(storage = LumenStorageNames()))
        // 设置页选的深浅色：进程启动时应用一次，之后由设置页切换（AppCompat 自动重建）。
        AppCompatDelegate.setDefaultNightMode(SampleSettingsStore(this).nightMode)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 只在前台运行告急或进程即将被回收时释放；UI_HIDDEN（20）只是普通切后台，不释放。
        @Suppress("DEPRECATION")
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            LumenEngine.releaseGraphics()
        }
    }
}
