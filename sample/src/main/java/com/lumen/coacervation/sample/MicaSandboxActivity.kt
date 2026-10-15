@file:Suppress("SetTextI18n")
package com.lumen.coacervation.sample

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.lumen.coacervation.engine.host.LumenMicaBackdrop
import com.lumen.coacervation.engine.host.LumenMicaImage
import com.lumen.coacervation.engine.host.LumenMicaKind
import com.lumen.coacervation.engine.host.LumenMicaLayer
import com.lumen.coacervation.engine.host.LumenMicaOptions
import com.lumen.coacervation.engine.model.LumenPalette
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One static window foundation, a commanding layer and segmented content. Never captures this UI. */
class MicaSandboxActivity : Activity() {
    internal lateinit var mica: LumenMicaBackdrop
    internal lateinit var root: LinearLayout
    internal var currentImage: LumenMicaImage? = null
        private set
    private lateinit var command: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var status: TextView
    private val labels = ArrayList<TextView>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Lumen-MicaDemo").apply { isDaemon = true } }
    private var pending: Future<*>? = null
    private var options = LumenMicaOptions()
    private var dark = false
    private var alternateImage = false
    private var generation = 0
    private var destroyed = false
    private val statusRefresh = Runnable { if (!destroyed) refreshStatus() }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        command = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)) }
        command.addView(label("云母 · Mica", 26f))
        command.addView(label("静态背景色彩融入窗口，内容滚动时保持安静。", 14f))
        root.addView(command, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(20)) }
        card.addView(label("专注当前内容", 21f))
        card.addView(label("Mica 是窗口的基础层。命令区、内容区和卡片仅叠加低透明度填充，不重复模糊背景。", 15f))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        status = label("准备背景…", 12f)
        content.addView(status)
        fun toggle(text: String, checked: Boolean = false, action: (Boolean) -> Unit) {
            content.addView(CheckBox(this).apply {
                this.text = text; isChecked = checked; labels += this
                setOnCheckedChangeListener { _, value -> action(value); applyOptions() }
            })
        }
        toggle("启用云母", true) { options = options.copy(enabled = it) }
        toggle("Mica Alt · 更鲜明的背景与命令层") { options = options.copy(kind = if (it) LumenMicaKind.BASE_ALT else LumenMicaKind.BASE) }
        toggle("深色主题") { dark = it }
        toggle("降低透明度") { options = options.copy(reduceTransparency = it) }
        toggle("高对比度（使用宿主背景色）") { options = options.copy(highContrast = it) }
        toggle("降低动态效果") { options = options.copy(reduceMotion = it) }
        toggle("窗口失焦时回退", true) { options = options.copy(fallbackWhenWindowInactive = it) }
        content.addView(Button(this).apply { text = "切换背景配色"; isAllCaps = false; labels += this
            setOnClickListener { alternateImage = !alternateImage; loadImage() } })
        content.addView(Button(this).apply { text = "刷新诊断"; isAllCaps = false; labels += this; setOnClickListener { refreshStatus() } })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        mica = LumenMicaBackdrop(root, LumenPalette.neutral(dark), options)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        applyOptions()
        loadImage()
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setPadding(0, dp(4), 0, dp(8)); labels += this
    }

    private fun applyOptions() {
        val palette = LumenPalette.neutral(dark)
        mica.update(options)
        mica.updatePalette(palette)
        command.background = mica.layerBackground(LumenMicaLayer.COMMAND, 0f)
        content.background = mica.layerBackground(LumenMicaLayer.CONTENT, 0f)
        card.background = mica.layerBackground(LumenMicaLayer.CARD)
        labels.forEach {
            it.setTextColor(palette.textPrimary)
            when (it) {
                is CheckBox -> it.buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(if (dark) 0xFF9CDCFE.toInt() else 0xFF0067C0.toInt(),
                        if (dark) 0xFFAAAAAA.toInt() else 0xFF626262.toInt()))
                is Button -> it.backgroundTintList = ColorStateList.valueOf(
                    if (dark) 0xFF343434.toInt() else 0xFFE9E9E9.toInt())
            }
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        scheduleStatus()
    }

    private fun loadImage() {
        val request = ++generation
        val alternate = alternateImage
        pending?.cancel(true)
        pending = worker.submit {
            try {
                // Original synthetic wallpaper: bounded demo input, no external image assets or permissions.
                val bitmap = Bitmap.createBitmap(192, 256, Bitmap.Config.ARGB_8888)
                val image = try {
                    val colors = if (alternate) intArrayOf(0xFF00B294.toInt(), 0xFFFFB900.toInt(), 0xFFE3008C.toInt())
                        else intArrayOf(0xFF0067C0.toInt(), 0xFF8764B8.toInt(), 0xFFFF4343.toInt())
                    val paint = Paint().apply { shader = LinearGradient(0f, 0f, 192f, 256f, colors, null, Shader.TileMode.CLAMP) }
                    Canvas(bitmap).drawRect(0f, 0f, 192f, 256f, paint)
                    LumenMicaImage.prepare(bitmap)
                } finally { bitmap.recycle() }
                root.post {
                    if (!destroyed && request == generation) {
                        currentImage = image
                        mica.setImage(image)
                        scheduleStatus()
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: OutOfMemoryError) {
                publishImageFailure(request)
            } catch (_: RuntimeException) {
                publishImageFailure(request)
            }
        }
    }

    private fun publishImageFailure(request: Int) {
        root.post {
            if (!destroyed && request == generation) {
                currentImage = null
                mica.setImage(null)
                scheduleStatus()
            }
        }
    }

    private fun refreshStatus() { status.text = "${mica.diagnostics()}" }
    private fun scheduleStatus() {
        refreshStatus()
        // One settling update per user/background change, never a recurring diagnostics poll.
        root.removeCallbacks(statusRefresh)
        root.postDelayed(statusRefresh, 200L)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::mica.isInitialized) root.post { if (!destroyed) scheduleStatus() }
    }
    override fun onDestroy() {
        destroyed = true
        generation++
        root.removeCallbacks(statusRefresh)
        pending?.cancel(true)
        pending = null
        worker.shutdownNow()
        currentImage = null
        mica.close()
        super.onDestroy()
    }
}
