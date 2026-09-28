package com.lumen.coacervation.engine.host

import android.app.Activity
import android.content.res.Configuration
import android.view.MotionEvent
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenPalette

/**
 * 继承式接入：已经把 [LumenActivityDelegate] 的生命周期与输入转发全部接好。
 *
 * 只适合直接继承 `android.app.Activity` 的宿主。基类是 `AppCompatActivity`、`ComponentActivity` 或
 * BetterAndroid `AppViewsActivity` 的宿主请持有一个 [LumenActivityDelegate] 并按其 KDoc 的表格转发
 * （见 docs/INTEGRATION_STANDARD.md §2 与 docs/BETTERANDROID_INITIATIVE.md）。
 *
 * 子类仍需自己在授权/早退检查之后调用 `lumen.prepare()`，并在 `setContentView` 之后 `lumen.bindRoot(root)`。
 */
public abstract class LumenActivity : Activity() {

    /** 本 Activity 的视效委托。 */
    public val lumen: LumenActivityDelegate by lazy(LazyThreadSafetyMode.NONE) {
        LumenActivityDelegate(this, ::resolvePalette, ::resolveEffectTuning)
    }

    /**
     * 本 Activity 的配色。默认按系统深浅色给出中性调色板；接入宿主主题时覆盖它。
     * 在第一次需要配色时调用一次。
     */
    protected open fun resolvePalette(): LumenPalette = LumenPalette.neutral(
        dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    )

    /**
     * 本 Activity 的视效调参（边缘高光厚度与亮度、长按拖动光晕）。默认引擎原样；在第一次需要时调用一次，
     * 改动后重建 Activity 生效。
     */
    protected open fun resolveEffectTuning(): LumenEffectTuning = LumenEffectTuning.DEFAULT

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lumen.onDispatchTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onStart() {
        super.onStart()
        lumen.onStart()
    }

    override fun onStop() {
        lumen.onStop()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        lumen.onTrimMemory(level)
        super.onTrimMemory(level)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        lumen.onLowMemory()
        @Suppress("DEPRECATION")
        super.onLowMemory()
    }

    override fun onDestroy() {
        try {
            lumen.onDestroy()
        } finally {
            super.onDestroy()
        }
    }
}
