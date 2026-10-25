@file:Suppress("SetTextI18n")
package com.lumen.coacervation.sample

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.host.LumenMicaBackdrop
import com.lumen.coacervation.engine.host.LumenMicaKind
import com.lumen.coacervation.engine.host.LumenMicaLayer
import com.lumen.coacervation.engine.host.LumenMicaOptions
import com.lumen.coacervation.engine.host.LumenMicaPreparer
import com.lumen.coacervation.engine.interaction.LumenElasticInteraction
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.widget.LumenSlider
import com.lumen.coacervation.engine.widget.LumenSliderAppearance
import com.lumen.coacervation.engine.widget.LumenSliderOptions
import com.lumen.coacervation.engine.widget.LumenSliderRange
import com.lumen.coacervation.engine.widget.LumenSliderShape
import com.lumen.coacervation.engine.widget.LumenSliderSlideListener
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 滑块的五种材质外观：柔光 / Liquid（跟随窗口材质）、Fluent、Mica、Prism、Plain。
 *
 * - 页面接入完整的视效委托与长按弹性：滑块由引擎自动排除，不需要 `EXCLUDED_TAG`；
 * - 滑块的直接宿主容器放行两层裁剪（按压放大、拉伸、气泡与 Prism 阴影画在边界外，适配标准 §12.3）；
 * - 「窗口 HDR」只由宿主切换 `window.colorMode`，滑块从不改窗口设置；
 * - 页面是竖向 ScrollView：竖向拖动从滑块上开始也照常滚动。
 */
class SliderSandboxActivity : AppCompatActivity() {
    private val dark by lazy(LazyThreadSafetyMode.NONE) {
        intent.getBooleanExtra(EXTRA_DARK,
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }
    internal val lumen = LumenActivityDelegate(this, { SampleSettingsStore(this).accent.palette(dark) })
    private val elastic by lazy(LazyThreadSafetyMode.NONE) { LumenElasticInteraction(this, lumen) }
    private lateinit var micaPreparer: LumenMicaPreparer
    internal lateinit var mica: LumenMicaBackdrop
    internal lateinit var root: LinearLayout
    internal lateinit var scroll: ScrollView
    private lateinit var status: TextView
    internal val sliders = LinkedHashMap<String, LumenSlider>()
    /** 「从右到左」开关：测试经它切换方向，页面上的勾选状态与实际方向一致。 */
    internal lateinit var rtlToggle: CheckBox
    private val readouts = HashMap<String, TextView>()
    private var destroyed = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lumen.prepare()
        val palette = lumen.palette
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(32))
            clipChildren = false; clipToPadding = false
        }
        // 滚动容器保留自身裁剪：内容滚到状态栏下方时不能画到它上面。滑块的溢出只需要卡片放行。
        scroll = ScrollView(this).apply { addView(content) }
        status = text("", 12f, palette.textSecondary)
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(18); rightMargin = dp(18) })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        lumen.bindRoot(root) { if (!isFinishing && !isDestroyed) recreate() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        scroll.setOnScrollChangeListener { v, _, _, _, _ ->
            lumen.notifyScrollPositionChanged(v)
        }

        content.addView(text("滑块 · 五种材质", 22f, palette.textPrimary))
        content.addView(controls(palette))

        // 柔光 / Liquid：槽体是窗口材质表面，切换设置页的材质后外观随之变化。
        content.addView(section(palette, "柔光 / Liquid（跟随窗口材质）", lumen.cardBackground(palette.surface)) { card ->
            card.addView(slider("skin.brightness", palette, LumenSliderAppearance.Skin(lumen), LumenSliderRange(0f, 100f),
                value = 62f, icon = GlyphDrawable(GlyphDrawable.SUN), caption = "亮度（连续）"))
            card.addView(slider("skin.volume", palette, LumenSliderAppearance.Skin(lumen), LumenSliderRange(0f, 100f, 10f),
                value = 40f, icon = GlyphDrawable(GlyphDrawable.SPEAKER), caption = "音量（10 档，磁吸 + 越档触觉）"))
            card.addView(slider("skin.classic", palette, LumenSliderAppearance.Skin(lumen), LumenSliderRange(0f, 5f, 1f),
                value = 2f, options = LumenSliderOptions(shape = LumenSliderShape.CLASSIC), caption = "细轨（与开关同一套滑钮）"))
        })

        content.addView(section(palette, "Fluent（WinUI Slider）", fluentCard()) { card ->
            card.addView(slider("fluent.continuous", palette, LumenSliderAppearance.Fluent(), LumenSliderRange(0f, 100f),
                value = 30f, caption = "连续，拖动时显示数值气泡", dark = dark, fluentText = true))
            card.addView(slider("fluent.ticks", palette, LumenSliderAppearance.Fluent(), LumenSliderRange(0f, 100f, 25f),
                value = 50f, caption = "5 档外置刻度", dark = dark, fluentText = true))
        })

        val micaHost = FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        mica = LumenMicaBackdrop(micaHost, palette, LumenMicaOptions(kind = LumenMicaKind.BASE_ALT))
        micaPreparer = LumenMicaPreparer()
        loadMicaImage()
        val micaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            clipChildren = false; clipToPadding = false
            background = mica.layerBackground(LumenMicaLayer.CARD)
        }
        micaHost.addView(micaCard, FrameLayout.LayoutParams(-1, -2))
        micaCard.addView(text("Mica（云母层上的 Fluent 滑块）", 15f, palette.textPrimary))
        micaCard.addView(slider("mica", palette, LumenSliderAppearance.Mica(), LumenSliderRange(0f, 1f),
            value = .7f, caption = "平面、只留层级描边", dark = dark, fluentText = true))
        content.addView(micaHost, sectionParams())

        content.addView(section(palette, "Prism（按下变液态镜片）", prismStage()) { card ->
            card.addView(slider("prism.classic", palette, LumenSliderAppearance.Prism(), LumenSliderRange(0f, 100f),
                value = 35f, caption = "细轨：白胶囊钮，按住变成镜片"))
            card.addView(slider("prism.capsule", palette, LumenSliderAppearance.Prism(), LumenSliderRange(0f, 100f),
                value = 55f, options = LumenSliderOptions(shape = LumenSliderShape.CAPSULE), caption = "胶囊：镜片骑在填充末端"))
        })

        content.addView(section(palette, "Plain（只用调色板）", lumen.cardBackground(palette.surface)) { card ->
            card.addView(slider("plain", palette, LumenSliderAppearance.Plain, LumenSliderRange(0f, 10f, 1f),
                value = 6f, icon = GlyphDrawable(GlyphDrawable.SUN), caption = "无引擎表面"))
        })
        refreshStatus()
    }

    private fun controls(palette: LumenPalette) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        fun toggle(label: String, checked: Boolean, change: (Boolean) -> Unit): CheckBox = CheckBox(context).apply {
            text = label; isChecked = checked; setTextColor(palette.textPrimary)
            setOnCheckedChangeListener { _, value -> change(value) }
        }.also { addView(it) }
        toggle("深色（重建）", dark) {
            startActivity(intent.putExtra(EXTRA_DARK, it)); finish(); overridePendingTransition(0, 0)
        }
        rtlToggle = toggle("从右到左", false) { rtl -> root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
        toggle("禁用全部滑块", false) { off -> sliders.values.forEach { it.isEnabled = !off } }
        toggle("窗口 HDR（COLOR_MODE_HDR，由宿主开启）", false) { on ->
            window.colorMode = if (on) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
            refreshStatus()
        }
    }

    private fun section(palette: LumenPalette, title: String, background: Drawable, fill: (LinearLayout) -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            // 滑块按压放大、拉伸与气泡都画到自身边界外：直接宿主放行两层裁剪（§12.3）。
            clipChildren = false; clipToPadding = false
            this.background = background
        }
        card.addView(text(title, 15f, if (background is GradientDrawable && dark) 0xFFFFFFFF.toInt() else palette.textPrimary))
        fill(card)
        return card.also { it.layoutParams = sectionParams() }
    }

    private fun sectionParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }

    private fun fluentCard() = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(if (dark) 0xFF202020.toInt() else 0xFFF3F3F3.toInt())
    }

    /** 渐变舞台：Prism 镜片放大的是滑块自己的轨道，轨道以外透出这块背景。 */
    private fun prismStage() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        if (dark) intArrayOf(0xFF1D2433.toInt(), 0xFF3A2A4A.toInt()) else intArrayOf(0xFFF5EFE6.toInt(), 0xFFE6F1FB.toInt())).apply {
        cornerRadius = dp(15).toFloat()
    }

    private fun slider(
        key: String,
        palette: LumenPalette,
        appearance: LumenSliderAppearance,
        range: LumenSliderRange,
        value: Float,
        caption: String,
        options: LumenSliderOptions = LumenSliderOptions(),
        icon: Drawable? = null,
        dark: Boolean = false,
        fluentText: Boolean = false
    ): View {
        val captionColor = if (fluentText) (if (dark) 0xC5FFFFFF.toInt() else 0x9E000000.toInt()) else palette.textSecondary
        val readout = text(caption, 12f, captionColor)
        val view = LumenSlider(this).apply {
            contentDescription = caption
            configure(range, palette, appearance, options, value)
            setIcon(icon)
            setOnValueChangeListener { _, v, _ -> readout.text = "$caption · ${format(v, range)}" }
            setOnSlideListener(object : LumenSliderSlideListener {
                override fun onSlideFinished(slider: LumenSlider, value: Float) { refreshStatus() }
            })
        }
        readout.text = "$caption · ${format(value, range)}"
        sliders[key] = view
        readouts[key] = readout
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(10), 0, 0)
            addView(readout)
            addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }

    private fun format(value: Float, range: LumenSliderRange): String =
        if (range.to <= 1f) "${(value * 100).roundToInt()}%" else value.roundToInt().toString()

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
    }

    private fun refreshStatus() {
        val ratio = if (Build.VERSION.SDK_INT >= 34) {
            val display = window.decorView.display
            if (display != null && display.isHdrSdrRatioAvailable) "%.2f".format(display.hdrSdrRatio) else "不可用"
        } else "需 API 34"
        val mode = if (window.colorMode == ActivityInfo.COLOR_MODE_HDR) "HDR" else "默认"
        status.text = "窗口色彩模式：$mode · 显示器 HDR 余量：$ratio · 材质：${lumen.backendName ?: "柔光"}"
    }

    private fun loadMicaImage() {
        micaPreparer.prepare({
            val bitmap = Bitmap.createBitmap(192, 256, Bitmap.Config.ARGB_8888)
            try {
                val colors = intArrayOf(0xFF0067C0.toInt(), 0xFF8764B8.toInt(), 0xFFFF4343.toInt())
                val paint = Paint().apply { shader = LinearGradient(0f, 0f, 192f, 256f, colors, null, Shader.TileMode.CLAMP) }
                Canvas(bitmap).drawRect(0f, 0f, 192f, 256f, paint)
                bitmap
            } catch (failure: Throwable) {
                bitmap.recycle(); throw failure
            }
        }, { image, _ -> if (!destroyed) mica.setImage(image) })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lumen.onDispatchTouchEvent(event)
        return elastic.dispatch(event) { super.dispatchTouchEvent(it) }
    }

    override fun onStart() { super.onStart(); lumen.onStart() }
    override fun onPause() { elastic.clear(); super.onPause() }
    override fun onStop() { elastic.clear(); lumen.onStop(); super.onStop() }
    override fun onTrimMemory(level: Int) { lumen.onTrimMemory(level); super.onTrimMemory(level) }

    override fun onDestroy() {
        destroyed = true
        // 先关滑块（它们创建的 Fluent 控制器），再关云母。
        sliders.values.forEach(LumenSlider::dispose)
        micaPreparer.close()
        mica.close()
        elastic.dispose()
        lumen.onDestroy()
        super.onDestroy()
    }

    /** 原创的简单图标：太阳与扬声器，只用于演示图标在填充内外的反色。 */
    private class GlyphDrawable(private val glyph: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val path = android.graphics.Path()

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            path.rewind()
            val w = bounds.width().toFloat()
            if (glyph == SPEAKER) {
                val l = bounds.left.toFloat(); val t = bounds.top.toFloat()
                path.moveTo(l + w * .12f, t + w * .38f); path.lineTo(l + w * .32f, t + w * .38f)
                path.lineTo(l + w * .56f, t + w * .16f); path.lineTo(l + w * .56f, t + w * .84f)
                path.lineTo(l + w * .32f, t + w * .62f); path.lineTo(l + w * .12f, t + w * .62f); path.close()
            }
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val cx = b.exactCenterX(); val cy = b.exactCenterY()
            paint.strokeWidth = w * .09f
            if (glyph == SUN) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, w * .2f, paint)
                for (i in 0 until 8) {
                    val a = Math.PI * i / 4
                    canvas.drawLine(cx + (w * .32f * cos(a)).toFloat(), cy + (w * .32f * sin(a)).toFloat(),
                        cx + (w * .45f * cos(a)).toFloat(), cy + (w * .45f * sin(a)).toFloat(), paint)
                }
            } else {
                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                canvas.drawArc(cx - w * .1f, cy - w * .22f, cx + w * .34f, cy + w * .22f, -50f, 100f, false, paint)
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        companion object {
            const val SUN = 0
            const val SPEAKER = 1
        }
    }

    companion object {
        const val EXTRA_DARK = "dark"
    }
}
