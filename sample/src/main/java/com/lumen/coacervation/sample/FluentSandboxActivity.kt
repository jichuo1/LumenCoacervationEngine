@file:Suppress("SetTextI18n")
package com.lumen.coacervation.sample

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lumen.coacervation.engine.host.LumenAcrylicKind
import com.lumen.coacervation.engine.host.LumenAcrylicOptions
import com.lumen.coacervation.engine.host.LumenFluentControl
import com.lumen.coacervation.engine.host.LumenFluentControlOptions
import com.lumen.coacervation.engine.host.LumenFluentElevation
import com.lumen.coacervation.engine.host.LumenFluentElevationOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette

/** Fluent acrylic + elevation over a scrolling, colourful source. The panel and buttons are siblings of the source. */
class FluentSandboxActivity : Activity() {
    internal lateinit var flyout: LumenFluentControl
    internal val buttons = ArrayList<LumenFluentControl>()
    internal lateinit var panel: LinearLayout
    internal val buttonViews = ArrayList<Button>()
    private lateinit var session: LumenSurfaceSession
    private lateinit var status: TextView
    private var dark = false
    private var flyoutOptions = LumenFluentControlOptions()
    private var buttonOptions = LumenFluentControlOptions(
        acrylic = LumenAcrylicOptions(enabled = false),
        elevation = LumenFluentElevationOptions(level = LumenFluentElevation.CONTROL))

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(236, 239, 245)) }
        val stage = FrameLayout(this).apply { clipChildren = false }
        val scene = ScrollView(this).apply { addView(sceneContent()) }
        stage.addView(scene, FrameLayout.LayoutParams(-1, -1))
        val overlay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)); clipChildren = false; clipToPadding = false }
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        for (label in listOf("剪切", "复制", "粘贴", "重命名")) panel.addView(TextView(this).apply {
            text = label; textSize = 15f; setPadding(dp(8), dp(10), dp(8), dp(10)); setTextColor(Color.BLACK) })
        overlay.addView(panel, LinearLayout.LayoutParams(dp(200), -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(20), 0, 0); clipChildren = false }
        val controls = (1..3).map { index ->
            Button(this).apply { text = "按钮 $index"; isAllCaps = false; stateListAnimator = null; setTextColor(Color.BLACK) }
                .also { row.addView(it, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }) }
        }
        controls.last().isEnabled = false
        buttonViews += controls
        overlay.addView(row, LinearLayout.LayoutParams(-1, -2))
        stage.addView(overlay, FrameLayout.LayoutParams(-1, -2))
        root.addView(stage, LinearLayout.LayoutParams(-1, dp(320)))
        status = TextView(this).apply { textSize = 11f; setTextColor(Color.BLACK); setPadding(dp(12), dp(6), dp(12), dp(6)) }
        root.addView(status)
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(20)) }
        root.addView(ScrollView(this).apply { addView(options) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        // targetSdk 35+ draws edge to edge: the scene stays behind the status bar, the controls move clear of the bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            overlay.setPadding(bars.left + dp(24), bars.top + dp(24), bars.right + dp(24), dp(24))
            stage.layoutParams = stage.layoutParams.apply { height = bars.top + dp(320) }
            status.setPadding(bars.left + dp(12), dp(6), bars.right + dp(12), dp(6))
            options.setPadding(bars.left + dp(12), 0, bars.right + dp(12), bars.bottom + dp(20))
            insets
        }

        session = LumenSurfaceSession(this, palette())
        flyout = LumenFluentControl(panel, palette(), flyoutOptions, session, scene)
        controls.forEach { buttons += LumenFluentControl(it, palette(), buttonOptions) }
        scene.setOnScrollChangeListener { v, _, _, _, _ -> session.notifyScrollPositionChanged(v) }
        buildOptions(options)
        refreshStatus()
    }

    private fun palette() = LumenPalette.neutral(dark)

    private fun sceneContent(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val colors = intArrayOf(0xFF0067C0.toInt(), 0xFFE3008C.toInt(), 0xFFFFB900.toInt(), 0xFF00B294.toInt(), 0xFF8764B8.toInt(), 0xFFFF4343.toInt())
        for (i in 0 until 12) addView(TextView(context).apply {
            text = "背景内容 ${i + 1}"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colors[i % colors.size], colors[(i + 2) % colors.size]))
        }, LinearLayout.LayoutParams(-1, dp(120)))
    }

    private fun buildOptions(parent: LinearLayout) {
        fun toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) =
            parent.addView(CheckBox(this).apply { text = label; isChecked = checked; setTextColor(Color.BLACK); setOnCheckedChangeListener { _, v -> change(v); refreshStatus() } })
        fun button(label: String, action: () -> Unit) =
            parent.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action(); refreshStatus() } })
        fun flyout(change: (LumenFluentControlOptions) -> LumenFluentControlOptions) { flyoutOptions = change(flyoutOptions); flyout.update(flyoutOptions) }
        toggle("深色主题", dark) { dark = it; session.updatePalette(palette()); flyout.updatePalette(palette()); buttons.forEach { b -> b.updatePalette(palette()) }; applyTextColors() }
        toggle("启用亚克力", true) { v -> flyout { it.copy(acrylic = it.acrylic.copy(enabled = v)) } }
        toggle("降低透明度", false) { v -> flyout { it.copy(reduceTransparency = v) } }
        toggle("精确混合（API 29+）", true) { v -> flyout { it.copy(acrylic = it.acrylic.copy(exactBlend = v)) } }
        toggle("窗口失焦时回退（背景亚克力语义）", false) { v -> flyout { it.copy(acrylic = it.acrylic.copy(fallbackWhenWindowInactive = v)) } }
        toggle("降低动态效果", false) { v -> flyout { it.copy(reduceMotion = v) }; buttonOptions = buttonOptions.copy(reduceMotion = v); buttons.forEach { b -> b.update(buttonOptions) } }
        button("切换亚克力类型") { flyout { it.copy(acrylic = it.acrylic.copy(kind = LumenAcrylicKind.entries[(it.acrylic.kind.ordinal + 1) % LumenAcrylicKind.entries.size])) } }
        button("切换浮层高度") { flyout { it.copy(elevation = it.elevation.copy(level = LumenFluentElevation.entries[(it.elevation.level.ordinal + 1) % LumenFluentElevation.entries.size])) } }
        button("刷新诊断") {}
    }

    /** Text colour stays host-owned: the material changes, the labels follow the palette. */
    private fun applyTextColors() {
        val color = palette().textPrimary
        for (i in 0 until panel.childCount) (panel.getChildAt(i) as? TextView)?.setTextColor(color)
        buttonViews.forEach { it.setTextColor(color) }
    }

    private fun refreshStatus() {
        val counters = session.diagnostics()
        status.text = "类型 ${flyoutOptions.acrylic.kind} · 高度 ${flyoutOptions.elevation.level} · 录制 ${counters.contentRecordings} · GPU ${counters.gpuDraws} · 软件 ${counters.softwareDraws}\n${flyout.diagnostics()}"
    }

    override fun onStart() { super.onStart(); session.resume() }
    override fun onStop() { session.pause(); super.onStop() }
    override fun onDestroy() {
        // Close the controls before the session they sample from.
        buttons.forEach { it.close() }
        flyout.close()
        session.close()
        super.onDestroy()
    }
}
