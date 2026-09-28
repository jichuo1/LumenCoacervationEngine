package com.lumen.coacervation.sample

import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenPalette
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 视效调参的宿主侧存储（适配标准 §2.5）。引擎不持久化调参；真实应用把它接进自己的设置。
 *
 * [current] 每次现读：长按拖动光晕在每次按下时取值，所以滑块改动即时生效；
 * 边缘高光在会话创建时读取，改动后需要重建 Activity。
 */
internal class SampleTuningStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("sample_effect_tuning", Context.MODE_PRIVATE)

    var current: LumenEffectTuning = read()
        private set

    private fun read() = LumenEffectTuning.clamped(
        edgeHighlightWidth = preferences.getFloat(KEY_EDGE_WIDTH, 1f),
        edgeHighlightIntensity = preferences.getFloat(KEY_EDGE_INTENSITY, 1f),
        dragGlowIntensity = preferences.getFloat(KEY_GLOW_INTENSITY, 1f),
        dragGlowRadius = preferences.getFloat(KEY_GLOW_RADIUS, 1f)
    )

    fun update(value: LumenEffectTuning) {
        current = value
        preferences.edit()
            .putFloat(KEY_EDGE_WIDTH, value.edgeHighlightWidth)
            .putFloat(KEY_EDGE_INTENSITY, value.edgeHighlightIntensity)
            .putFloat(KEY_GLOW_INTENSITY, value.dragGlowIntensity)
            .putFloat(KEY_GLOW_RADIUS, value.dragGlowRadius)
            .apply()
    }

    private companion object {
        const val KEY_EDGE_WIDTH = "edge_width"
        const val KEY_EDGE_INTENSITY = "edge_intensity"
        const val KEY_GLOW_INTENSITY = "glow_intensity"
        const val KEY_GLOW_RADIUS = "glow_radius"
    }
}

/**
 * 视效调参卡片：四个滑块。边缘高光两项松手后重建 Activity；长按光晕两项拖动时即时生效。
 * 滑块（`AbsSeekBar`）本身不参与长按弹性（§12.1）。
 */
internal fun SampleActivity.tuningCard(palette: LumenPalette) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(16), dp(18), dp(16))
    background = lumen.cardBackground(palette.surface)
    addView(TextView(context).apply {
        text = "视效调参"
        textSize = 16f
        setTextColor(palette.textPrimary)
    })
    addView(tuningSlider(palette, "边缘高光厚度", LumenEffectTuning.MIN_EDGE_WIDTH, LumenEffectTuning.MAX_EDGE_WIDTH,
        tuning.current.edgeHighlightWidth, recreateOnRelease = true) { tuning.current.copy(edgeHighlightWidth = it) })
    addView(tuningSlider(palette, "边缘高光亮度", 0f, LumenEffectTuning.MAX_EDGE_INTENSITY,
        tuning.current.edgeHighlightIntensity, recreateOnRelease = true) { tuning.current.copy(edgeHighlightIntensity = it) })
    addView(tuningSlider(palette, "长按光晕强度", 0f, LumenEffectTuning.MAX_DRAG_GLOW_INTENSITY,
        tuning.current.dragGlowIntensity, recreateOnRelease = false) { tuning.current.copy(dragGlowIntensity = it) })
    addView(tuningSlider(palette, "长按光晕半径", LumenEffectTuning.MIN_DRAG_GLOW_RADIUS,
        LumenEffectTuning.MAX_DRAG_GLOW_RADIUS, tuning.current.dragGlowRadius, recreateOnRelease = false) {
        tuning.current.copy(dragGlowRadius = it)
    })
}

private fun SampleActivity.tuningSlider(
    palette: LumenPalette,
    label: String,
    from: Float,
    to: Float,
    initial: Float,
    recreateOnRelease: Boolean,
    apply: (Float) -> LumenEffectTuning
) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(0, dp(12), 0, 0)
    val title = TextView(context).apply {
        textSize = 13f
        setTextColor(palette.textSecondary)
    }
    // 固定 0.05 一档：每个范围的端点与默认值 1 都落在整档上。用 progress / 20 而不是 0.05 × progress，
    // 默认档精确等于 1f（0.05f 不能精确表示），拖回原位就是引擎原样。
    val steps = ((to - from) * STEPS_PER_UNIT).roundToInt()
    fun valueOf(progress: Int) = (from + progress / STEPS_PER_UNIT).coerceIn(from, to)
    fun show(value: Float) {
        title.text = String.format(Locale.ROOT, "%s  ×%.2f", label, value)
    }
    show(initial)
    addView(title)
    addView(SeekBar(context).apply {
        max = steps
        progress = ((initial - from) * STEPS_PER_UNIT).roundToInt().coerceIn(0, steps)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = valueOf(progress)
                show(value)
                if (!recreateOnRelease) tuning.update(apply(value))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (!recreateOnRelease) return
                tuning.update(apply(valueOf(seekBar.progress)))
                // 边缘高光在会话创建时读取：与切换材质相同，重建 Activity 生效（§2.5）。
                recreate()
            }
        })
    }, LinearLayout.LayoutParams(-1, -2))
}

private const val STEPS_PER_UNIT = 20f
