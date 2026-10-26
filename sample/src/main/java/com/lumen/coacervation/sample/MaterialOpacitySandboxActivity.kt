@file:Suppress("SetTextI18n")
package com.lumen.coacervation.sample

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lumen.coacervation.engine.host.LumenFluentControl
import com.lumen.coacervation.engine.host.LumenMaterialLegibility
import com.lumen.coacervation.engine.host.LumenMaterialOpacityTarget
import com.lumen.coacervation.engine.host.LumenMicaBackdrop
import com.lumen.coacervation.engine.host.LumenMicaImage
import com.lumen.coacervation.engine.host.LumenPrismGlass
import com.lumen.coacervation.engine.host.LumenPrismOptions
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.motion.LumenMaterialOpacityScope
import com.lumen.coacervation.engine.widget.LumenSlider
import com.lumen.coacervation.engine.widget.LumenSliderAppearance
import com.lumen.coacervation.engine.widget.LumenSliderRange
import kotlin.math.roundToInt

/**
 * Material opacity (MATERIAL_OPACITY.md): five materials and a slider over one scrolling, colourful source, all
 * driven by one [LumenMaterialOpacityScope]. Labels keep full contrast at every opacity; the text-floor switch shows
 * the legibility floor against the decorative setting.
 */
class MaterialOpacitySandboxActivity : Activity() {
    private lateinit var session: LumenSurfaceSession
    private lateinit var scene: ScrollView
    private lateinit var status: TextView
    private lateinit var seek: SeekBar
    internal lateinit var scope: LumenMaterialOpacityScope
    internal lateinit var frosted: LumenSurfaceBinding
    internal lateinit var liquid: LumenSurfaceBinding
    internal lateinit var fluent: LumenFluentControl
    internal lateinit var prism: LumenPrismGlass
    internal lateinit var mica: LumenMicaBackdrop
    internal lateinit var slider: LumenSlider
    private var image: LumenMicaImage? = null
    private var destroyed = false
    private val palette = LumenPalette.neutral(false)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(236, 239, 245)) }
        val stage = FrameLayout(this).apply { clipChildren = false }
        val sceneContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scene = ScrollView(this).apply { addView(sceneContent) }
        fillScene(sceneContent)
        stage.addView(scene, FrameLayout.LayoutParams(-1, -1))
        // Tiles are siblings of the source (the same rule as every surface binding).
        val grid = GridLayout(this).apply {
            columnCount = 2; clipChildren = false; clipToPadding = false
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val frostedTile = tile(grid, "柔光")
        val liquidTile = tile(grid, "液态")
        val fluentTile = tile(grid, "亚克力")
        val prismTile = tile(grid, "棱镜")
        val micaTile = tile(grid, "云母")
        stage.addView(grid, FrameLayout.LayoutParams(-1, -2))
        root.addView(stage, LinearLayout.LayoutParams(-1, dp(420)))
        status = TextView(this).apply { textSize = 11f; setTextColor(palette.textPrimary); setPadding(dp(12), dp(6), dp(12), dp(6)) }
        root.addView(status)
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(20)) }
        root.addView(ScrollView(this).apply { addView(options) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            grid.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), dp(16))
            stage.layoutParams = stage.layoutParams.apply { height = bars.top + dp(420) }
            options.setPadding(bars.left + dp(12), 0, bars.right + dp(12), bars.bottom + dp(20))
            insets
        }

        session = LumenSurfaceSession(this, palette)
        frosted = session.bind(frostedTile, LumenSurfaceOptions(material = LumenSurfaceMaterial.FROSTED, role = SurfaceRole.CARD,
            radiusDp = 20f), scene)
        liquid = session.bind(liquidTile, LumenSurfaceOptions(material = LumenSurfaceMaterial.LIQUID, role = SurfaceRole.CARD,
            radiusDp = 20f), scene)
        fluent = LumenFluentControl(fluentTile, palette, session = session, source = scene)
        prism = LumenPrismGlass(prismTile, palette, LumenPrismOptions(cornerRadiusDp = 20f), session, scene)
        mica = LumenMicaBackdrop(micaTile, palette)
        prepareMicaImage()
        scene.setOnScrollChangeListener { v, _, _, _, _ -> session.notifyScrollPositionChanged(v) }

        slider = LumenSlider(this).apply {
            contentDescription = "Prism 滑块"
            configure(LumenSliderRange(0f, 100f), palette, LumenSliderAppearance.Prism(), value = 60f)
        }
        scope = LumenMaterialOpacityScope()
        for (target in targets()) scope.register(target)
        buildOptions(options)
        refreshStatus()
    }

    private fun targets(): List<LumenMaterialOpacityTarget> = listOf(frosted, liquid, fluent, prism, mica, slider)

    private fun tile(grid: GridLayout, label: String): FrameLayout {
        val tile = FrameLayout(this)
        tile.addView(TextView(this).apply {
            text = label; textSize = 16f; gravity = Gravity.CENTER; setTextColor(palette.textPrimary)
        }, FrameLayout.LayoutParams(-1, -1))
        grid.addView(tile, GridLayout.LayoutParams().apply {
            width = 0; height = dp(92)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        return tile
    }

    private fun fillScene(content: LinearLayout) {
        val vivid = intArrayOf(0xFFD85A30.toInt(), 0xFF378ADD.toInt(), 0xFF1D9E75.toInt(), 0xFFE3008C.toInt(), 0xFFFFB900.toInt(), 0xFF8764B8.toInt())
        for (i in 0 until 16) content.addView(TextView(this).apply {
            text = "背景正文 ${i + 1} · 清晰度与对比度测试"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(vivid[i % vivid.size], vivid[(i + 2) % vivid.size]))
        }, LinearLayout.LayoutParams(-1, dp(96)))
    }

    /** A small wallpaper-like gradient; the Mica snapshot is prepared off the main thread, as the API requires. */
    private fun prepareMicaImage() {
        Thread({
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(0f, 0f, 96f, 96f, Paint().apply {
                shader = LinearGradient(0f, 0f, 96f, 96f, intArrayOf(0xFFE3A189.toInt(), 0xFF8FD0B8.toInt(), 0xFF9CC3EC.toInt()),
                    null, Shader.TileMode.CLAMP)
            })
            val prepared = runCatching { LumenMicaImage.prepare(bitmap) }.getOrNull()
            bitmap.recycle()
            runOnUiThread {
                if (destroyed || prepared == null) return@runOnUiThread
                image = prepared
                mica.setImage(prepared)
            }
        }, "Sample-MicaImage").start()
    }

    private fun buildOptions(parent: LinearLayout) {
        val title = TextView(this).apply { textSize = 14f; setTextColor(palette.textPrimary) }
        fun show(value: Float) { title.text = "材质浓度 p：${"%.2f".format(value)}" }
        show(1f)
        parent.addView(title)
        seek = SeekBar(this).apply {
            max = 1000; progress = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val value = progress / 1000f
                    show(value); scope.set(value); refreshStatus()
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        parent.addView(seek)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun animate(label: String, target: Float) = row.addView(Button(this).apply {
            text = label; isAllCaps = false
            setOnClickListener {
                scope.animateTo(target)
                seek.progress = (target * 1000f).roundToInt(); show(target)
                postDelayed({ refreshStatus() }, 600L)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        animate("弹簧到 0", 0f)
        animate("弹簧到 0.35", 0.35f)
        animate("弹簧到 1", 1f)
        parent.addView(row)
        parent.addView(CheckBox(this).apply {
            text = "文字表面下限（关闭 = 装饰性，可降到 0）"; isChecked = true
            setOnCheckedChangeListener { _, text ->
                val legibility = if (text) LumenMaterialLegibility.AUTO else LumenMaterialLegibility.DECORATIVE
                frosted.setMaterialOpacity(frosted.materialOpacity(), legibility)
                liquid.setMaterialOpacity(liquid.materialOpacity(), legibility)
                fluent.setMaterialOpacity(fluent.materialOpacity(), legibility)
                prism.setMaterialOpacity(prism.materialOpacity(), legibility)
                refreshStatus()
            }
        })
        parent.addView(TextView(this).apply {
            text = "下方滑块同样挂在作用域上：槽体与滑钮变薄，数值填充与刻度不变。"
            textSize = 12f; setTextColor(palette.textSecondary); setPadding(0, dp(10), 0, dp(4))
        })
        parent.addView(slider, LinearLayout.LayoutParams(-1, -2))
        parent.addView(Button(this).apply { text = "刷新诊断"; isAllCaps = false; setOnClickListener { refreshStatus() } })
    }

    internal fun refreshStatus() {
        fun f(value: Float) = "%.2f".format(value)
        val counters = session.diagnostics()
        status.text = "作用域 ${f(scope.value)} · 录制 ${counters.contentRecordings} · GPU ${counters.gpuDraws} · 软件 ${counters.softwareDraws}\n" +
            "实际浓度：柔光 ${f(frosted.effectiveMaterialOpacity())} · 液态 ${f(liquid.effectiveMaterialOpacity())} · " +
            "亚克力 ${f(fluent.effectiveMaterialOpacity())} · 棱镜 ${f(prism.effectiveMaterialOpacity())} · 云母 ${f(mica.effectiveMaterialOpacity())}"
    }

    override fun onStart() { super.onStart(); session.resume() }
    override fun onStop() { session.pause(); super.onStop() }
    override fun onDestroy() {
        destroyed = true
        scope.close()
        // Close the glass before the session it samples from.
        fluent.close(); prism.close(); frosted.close(); liquid.close(); mica.close()
        slider.dispose()
        session.close()
        super.onDestroy()
    }
}
