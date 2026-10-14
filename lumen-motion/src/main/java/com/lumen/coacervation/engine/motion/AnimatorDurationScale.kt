package com.lumen.coacervation.engine.motion

import android.content.Context
import android.os.Build
import android.provider.Settings

internal object AnimatorDurationScale {
    fun current(context: Context): Float {
        val value = if (Build.VERSION.SDK_INT >= 33) {
            AnimatorDurationScaleApi33.current()
        } else {
            // Older public APIs expose the setting, not the process's asynchronously updated
            // animator cache. A settings-change notification can briefly leave these different.
            try {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            } catch (_: SecurityException) {
                1f
            }
        }
        return MotionTimeDomain.durationScale(value)
    }
}
