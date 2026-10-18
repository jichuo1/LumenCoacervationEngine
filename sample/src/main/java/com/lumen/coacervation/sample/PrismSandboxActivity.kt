@file:Suppress("SetTextI18n")
package com.lumen.coacervation.sample

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lumen.coacervation.engine.host.LumenPrismGlass
import com.lumen.coacervation.engine.host.LumenPrismOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.widget.LumenNavigationBar
import com.lumen.coacervation.engine.widget.LumenPrismSelectionOptions
import com.lumen.coacervation.engine.widget.NavigationBarColors
import com.lumen.coacervation.engine.widget.NavigationBarSelectionMask
import com.lumen.coacervation.engine.widget.NavigationBarSurface
import kotlin.math.roundToInt

/**
 * Prism (liquid glass) over a scrolling, colourful or pale source: a card, a button and a bottom bar whose
 * selected box uses the prism selection. Every layer has its own switch or slider; the shade switch updates
 * everything live, the pale switch checks the light-on-light case.
 */
class PrismSandboxActivity : Activity() {
    internal lateinit var card: LumenPrismGlass
    internal lateinit var button: LumenPrismGlass
    internal lateinit var bar: LumenPrismGlass
    internal lateinit var navigation: LumenNavigationBar
    internal lateinit var cardView: FrameLayout
    internal lateinit var buttonView: Button
    internal lateinit var scene: ScrollView
    private lateinit var sceneContent: LinearLayout
    private lateinit var stage: FrameLayout
    private lateinit var session: LumenSurfaceSession
    private lateinit var status: TextView
    private lateinit var cardLabel: TextView
    private var dark = false
    private var pale = false
    internal var glassOptions = LumenPrismOptions(cornerRadiusDp = 28f)
        private set
    internal var selectionOptions: LumenPrismSelectionOptions? = LumenPrismSelectionOptions()
        private set

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(236, 239, 245)) }
        stage = FrameLayout(this).apply { clipChildren = false }
        sceneContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scene = ScrollView(this).apply { addView(sceneContent) }
        fillScene()
        stage.addView(scene, FrameLayout.LayoutParams(-1, -1))
        // The glass views are siblings of the source and draw their shadow outside their bounds.
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24)); clipChildren = false; clipToPadding = false
        }
        cardView = FrameLayout(this)
        cardLabel = TextView(this).apply { text = "棱镜玻璃卡片"; textSize = 16f; gravity = Gravity.CENTER }
        cardView.addView(cardLabel, FrameLayout.LayoutParams(-1, -1))
        overlay.addView(cardView, LinearLayout.LayoutParams(-1, dp(96)))
        buttonView = Button(this).apply { text = "棱镜按钮（按住看光晕）"; isAllCaps = false; stateListAnimator = null }
        overlay.addView(buttonView, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(20) })
        stage.addView(overlay, FrameLayout.LayoutParams(-1, -2))
        navigation = buildNavigation()
        stage.addView(navigation, navigationParams())
        root.addView(stage, LinearLayout.LayoutParams(-1, dp(380)))
        status = TextView(this).apply { textSize = 11f; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        root.addView(status)
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(20)) }
        root.addView(ScrollView(this).apply { addView(options) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            overlay.setPadding(bars.left + dp(24), bars.top + dp(24), bars.right + dp(24), dp(24))
            stage.layoutParams = stage.layoutParams.apply { height = bars.top + dp(380) }
            status.setPadding(bars.left + dp(12), dp(6), bars.right + dp(12), dp(6))
            options.setPadding(bars.left + dp(12), 0, bars.right + dp(12), bars.bottom + dp(20))
            insets
        }

        session = LumenSurfaceSession(this, palette())
        card = LumenPrismGlass(cardView, palette(), glassOptions, session, scene)
        button = LumenPrismGlass(buttonView, palette(), glassOptions.copy(cornerRadiusDp = null), session, scene)
        // The bar keeps its own touch and selection; the prism glass only replaces its background.
        bar = LumenPrismGlass(navigation, palette(), glassOptions.copy(cornerRadiusDp = null), session, scene)
        navigation.setPrismSelection(selectionOptions)
        scene.setOnScrollChangeListener { v, _, _, _, _ -> session.notifyScrollPositionChanged(v) }
        buildOptions(options)
        applyShade()
        refreshStatus()
    }

    private fun palette() = LumenPalette.neutral(dark)
    private fun accent() = if (dark) ACCENT_DARK else ACCENT

    private fun navigationParams() = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
        leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(20) }

    private var page = 0
    private var progress = 0f
    private var pageAnimator: android.animation.ValueAnimator? = null

    /** Stands in for a pager: the selection travels to the tapped tab instead of jumping. */
    private fun selectPage(index: Int) {
        page = index
        navigation.setSelectedPage(index)
        pageAnimator?.cancel()
        pageAnimator = android.animation.ValueAnimator.ofFloat(progress, index.toFloat()).apply {
            duration = 320L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; navigation.setPageProgress(progress) }
            start()
        }
    }

    /** Navigation colours are fixed per bar: a shade switch rebuilds the bar and its glass in place. */
    private fun rebuildNavigation() {
        val index = stage.indexOfChild(navigation)
        bar.close(); navigation.dispose(); stage.removeView(navigation)
        navigation = buildNavigation()
        stage.addView(navigation, index, navigationParams())
        bar = LumenPrismGlass(navigation, palette(), glassOptions.copy(cornerRadiusDp = null), session, scene)
        navigation.setPrismSelection(selectionOptions)
        navigation.setSelectedPage(page); navigation.setPageProgress(page.toFloat())
    }

    private fun buildNavigation(): LumenNavigationBar {
        val p = palette()
        return LumenNavigationBar(
            context = this,
            titles = listOf("材质", "动效", "列表", "设置"),
            icons = intArrayOf(R.drawable.ic_sample_material, R.drawable.ic_sample_motion, R.drawable.ic_sample_list, R.drawable.ic_sample_settings),
            colors = NavigationBarColors(text = p.textSecondary, selectedText = accent(), highlight = accent()),
            backgroundFactory = { surface, _ ->
                when (surface) {
                    NavigationBarSurface.BAR -> ColorDrawable(Color.TRANSPARENT)
                    NavigationBarSurface.SELECTION -> GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE; cornerRadius = 1000f
                        setColor(NavigationBarSelectionMask.color(p, 0.1f))
                    }
                }
            },
            onSelect = { index -> selectPage(index) },
            onUserInteraction = {},
            onVisualMovement = { if (::bar.isInitialized) bar.notifyPositionChanged() }
        ).apply { tag = ElasticInteractionController.EXCLUDED_TAG }
    }

    private fun fillScene() {
        sceneContent.removeAllViews()
        val vivid = intArrayOf(0xFF0067C0.toInt(), 0xFFE3008C.toInt(), 0xFFFFB900.toInt(), 0xFF00B294.toInt(), 0xFF8764B8.toInt(), 0xFFFF4343.toInt())
        val soft = intArrayOf(0xFFF7F7FA.toInt(), 0xFFFFFFFF.toInt(), 0xFFF1F3F8.toInt(), 0xFFFAF6F0.toInt(), 0xFFF2F8F4.toInt(), 0xFFFFFFFF.toInt())
        val colors = if (pale) soft else vivid
        for (i in 0 until 14) sceneContent.addView(TextView(this).apply {
            text = "背景内容 ${i + 1} · Prism"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(if (pale) 0xFF30343A.toInt() else Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colors[i % colors.size], colors[(i + 2) % colors.size]))
        }, LinearLayout.LayoutParams(-1, dp(110)))
    }

    private fun buildOptions(parent: LinearLayout) {
        fun header(label: String) = parent.addView(TextView(this).apply {
            text = label; textSize = 14f; setPadding(0, dp(14), 0, dp(4)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        fun toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) =
            parent.addView(CheckBox(this).apply { text = label; isChecked = checked; setOnCheckedChangeListener { _, v -> change(v); refreshStatus() } })
        fun slider(label: String, min: Float, max: Float, value: Float, change: (Float) -> Unit) {
            val title = TextView(this).apply { textSize = 12f }
            fun show(v: Float) { title.text = "$label：${"%.2f".format(v)}" }
            show(value)
            parent.addView(title)
            parent.addView(SeekBar(this).apply {
                this.max = 1000
                progress = ((value - min) / (max - min) * 1000f).roundToInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = min + (max - min) * progress / 1000f
                        show(v); change(v); refreshStatus()
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            })
        }
        fun glass(change: (LumenPrismOptions) -> LumenPrismOptions) {
            glassOptions = change(glassOptions)
            card.update(glassOptions)
            button.update(glassOptions.copy(cornerRadiusDp = null))
            bar.update(glassOptions.copy(cornerRadiusDp = null))
        }
        fun selection(change: (LumenPrismSelectionOptions) -> LumenPrismSelectionOptions) {
            val next = change(selectionOptions ?: LumenPrismSelectionOptions())
            if (selectionOptions != null) { selectionOptions = next; navigation.setPrismSelection(next) }
            else selectionOptions = null
        }
        var lastSelection = selectionOptions ?: LumenPrismSelectionOptions()

        header("主题与内容")
        toggle("深色主题", dark) { setDark(it) }
        toggle("浅色背景内容（浅底测试）", pale) { pale = it; fillScene(); session.notifyContentChanged() }
        toggle("降低透明度", false) { v -> glass { it.copy(reduceTransparency = v) } }
        toggle("降低动态效果", false) { v -> glass { it.copy(reduceMotion = v) } }

        header("玻璃：模糊与饱和度")
        toggle("模糊", true) { v -> glass { it.copy(blur = it.blur.copy(enabled = v)) } }
        slider("模糊半径 dp", 0f, 48f, glassOptions.blur.radiusDp) { v -> glass { it.copy(blur = it.blur.copy(radiusDp = v)) } }
        slider("饱和度", 0f, 2f, glassOptions.blur.saturation) { v -> glass { it.copy(blur = it.blur.copy(saturation = v)) } }

        header("玻璃：边缘折射（API 33+）")
        toggle("折射", true) { v -> glass { it.copy(lens = it.lens.copy(enabled = v)) } }
        slider("折射带宽 dp", 0f, 64f, glassOptions.lens.refractionHeightDp) { v -> glass { it.copy(lens = it.lens.copy(refractionHeightDp = v)) } }
        slider("折射量 dp", 0f, 64f, glassOptions.lens.refractionAmountDp) { v -> glass { it.copy(lens = it.lens.copy(refractionAmountDp = v)) } }
        slider("纵深", 0f, 1f, glassOptions.lens.depthEffect) { v -> glass { it.copy(lens = it.lens.copy(depthEffect = v)) } }
        slider("色散", 0f, 1f, glassOptions.lens.dispersion) { v -> glass { it.copy(lens = it.lens.copy(dispersion = v)) } }

        header("玻璃：表面、高光与阴影")
        slider("表面色不透明度", 0f, 1f, 0.4f) { v -> glass { it.copy(surface = it.surface.copy(tintOpacity = v)) } }
        toggle("高光", true) { v -> glass { it.copy(highlight = it.highlight.copy(enabled = v)) } }
        slider("高光不透明度", 0f, 1f, glassOptions.highlight.opacity) { v -> glass { it.copy(highlight = it.highlight.copy(opacity = v)) } }
        slider("高光角度", -180f, 180f, glassOptions.highlight.angleDegrees) { v -> glass { it.copy(highlight = it.highlight.copy(angleDegrees = v)) } }
        toggle("内阴影", false) { v -> glass { it.copy(innerShadow = it.innerShadow.copy(enabled = v)) } }
        toggle("外阴影", true) { v -> glass { it.copy(shadow = it.shadow.copy(enabled = v)) } }
        slider("外阴影不透明度", 0f, 0.5f, glassOptions.shadow.opacity) { v -> glass { it.copy(shadow = it.shadow.copy(opacity = v)) } }
        toggle("按压光晕", true) { v -> glass { it.copy(press = it.press.copy(enabled = v)) } }

        header("底栏选中框")
        toggle("棱镜选中框（关闭 = 经典）", true) { v ->
            if (v) { selectionOptions = lastSelection; navigation.setPrismSelection(lastSelection) }
            else { lastSelection = selectionOptions ?: lastSelection; selectionOptions = null; navigation.setPrismSelection(null) }
        }
        slider("按下缩放", 1f, 1.8f, lastSelection.pressedScale) { v -> selection { it.copy(pressedScale = v) }; lastSelection = lastSelection.copy(pressedScale = v) }
        slider("内容放大", 1f, 1.6f, lastSelection.contentMagnification) { v -> selection { it.copy(contentMagnification = v) }; lastSelection = lastSelection.copy(contentMagnification = v) }
        slider("选中框折射带宽 dp", 0f, 32f, lastSelection.lensHeightDp) { v -> selection { it.copy(lensHeightDp = v) }; lastSelection = lastSelection.copy(lensHeightDp = v) }
        slider("选中框折射量 dp", 0f, 32f, lastSelection.lensAmountDp) { v -> selection { it.copy(lensAmountDp = v) }; lastSelection = lastSelection.copy(lensAmountDp = v) }
        slider("选中框色散", 0f, 1f, lastSelection.dispersion) { v -> selection { it.copy(dispersion = v) }; lastSelection = lastSelection.copy(dispersion = v) }
        slider("速度形变", 0f, 2f, lastSelection.velocityDeformation) { v -> selection { it.copy(velocityDeformation = v) }; lastSelection = lastSelection.copy(velocityDeformation = v) }
        Button(this).apply { text = "刷新诊断"; isAllCaps = false; setOnClickListener { refreshStatus() } }.also(parent::addView)
    }

    /** Live shade switch: palettes update in place, the bar (fixed colours) is rebuilt. */
    internal fun setDark(value: Boolean) {
        if (value == dark) return
        dark = value
        applyShade()
        rebuildNavigation()
    }

    /** Text colours stay host-owned: the glass follows the palette, labels follow it too. */
    private fun applyShade() {
        val p = palette()
        session.updatePalette(p)
        card.updatePalette(p); button.updatePalette(p); bar.updatePalette(p)
        cardLabel.setTextColor(p.textPrimary)
        buttonView.setTextColor(p.textPrimary)
        status.setTextColor(p.textPrimary)
        (window.decorView.findViewById<View>(android.R.id.content) as? FrameLayout)?.getChildAt(0)
            ?.setBackgroundColor(if (dark) Color.rgb(18, 18, 22) else Color.rgb(236, 239, 245))
    }

    internal fun refreshStatus() {
        val counters = session.diagnostics()
        status.text = "录制 ${counters.contentRecordings} · GPU ${counters.gpuDraws} · 软件 ${counters.softwareDraws} · 选中框 ${if (navigation.isPrismSelectionEnabled) "棱镜" else "经典"}\n" +
            "卡片 ${card.diagnostics()}\n底栏 ${bar.diagnostics()}"
    }

    override fun onStart() { super.onStart(); session.resume() }
    override fun onStop() { session.pause(); super.onStop() }
    override fun onDestroy() {
        pageAnimator?.cancel()
        // Close the glass before the session it samples from.
        card.close(); button.close(); bar.close()
        navigation.dispose()
        session.close()
        super.onDestroy()
    }

    internal companion object {
        /** The reference's accent per shade. */
        const val ACCENT = 0xFF0088FF.toInt()
        const val ACCENT_DARK = 0xFF0091FF.toInt()
    }
}
