package com.lumen.coacervation.sample

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.lumen.coacervation.engine.controls.LumenControls
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.interaction.LumenElasticInteraction
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.motion.morph.ContainerMorphController
import com.lumen.coacervation.engine.motion.morph.ContainerMorphHost
import com.lumen.coacervation.engine.motion.morph.ContainerMorphLauncher
import com.lumen.coacervation.engine.motion.morph.ContainerMorphOrigin
import com.lumen.coacervation.engine.widget.CoverableRippleDrawable

/**
 * 条目形变成全屏的目标页（适配标准 §13.5）：透明窗口主题，内容根由 [ContainerMorphHost] 承载、
 * [ContainerMorphController] 驱动打开、关闭与预测式返回。
 */
class DetailActivity : AppCompatActivity() {

    private val tuning by lazy(LazyThreadSafetyMode.NONE) { SampleTuningStore(this) }
    private val lumen = LumenActivityDelegate(this, ::resolvePalette) { tuning.current }
    private val elastic by lazy(LazyThreadSafetyMode.NONE) { LumenElasticInteraction(this, lumen) }
    private lateinit var host: ContainerMorphHost
    private var morph: ContainerMorphController? = null
    private var toolbarTitle: TextView? = null
    private var scroll: NestedScrollView? = null
    private var stretch: View? = null

    /** 与入口标题同文字才做标题迁移：入口把自己的标题带过来。 */
    private val title: String get() = intent.getStringExtra(EXTRA_TITLE) ?: TITLE
    private val density get() = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    private fun resolvePalette(): LumenPalette {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // 与来源页同一个强调色：形变两端的表面与标题颜色才接得上。
        return SampleSettingsStore(this).accent.palette(dark)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 关掉系统开关转场：打开与关闭都是代码绘制的可 seek 形变。
        ContainerMorphController.suppressSystemTransitions(this)
        lumen.prepare()
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        val palette = lumen.palette
        // 折叠端表面与来源条目的卡片同色同圆角，两层交接才不跳。
        host = ContainerMorphHost(
            context = this,
            collapsedSurfaceColor = if (lumen.isLiquidEffective) ColorUtils.setAlphaComponent(palette.surface, 0x74)
                else palette.surfaceVariant,
            expandedSurfaceColor = if (lumen.isLiquidEffective) ColorUtils.setAlphaComponent(palette.surface, 0x28)
                else palette.background,
            titleColor = palette.textPrimary,
            sourceTitle = title
        )
        host.setMotionSurfaceBackground(lumen.motionSurfaceBackground(palette.surfaceVariant, SOURCE_CORNER_RADIUS_DP))
        val page = buildPage(palette)
        setContentView(host)
        host.installContentInsets()
        host.replacePage(page, requireNotNull(toolbarTitle))
        lumen.bindRoot(host.liquidBackdropRoot()) { if (!isFinishing && !isDestroyed) recreate() }
        LumenControls.style(page, lumen)
        val controller = ContainerMorphController(
            activity = this,
            host = host,
            lumen = lumen,
            destination = DetailActivity::class.java,
            launchOrigin = ContainerMorphOrigin.from(intent),
            allowLaunchOriginForExit = savedInstanceState == null,
            destinationTitle = { toolbarTitle },
            collapsedCornerRadiusDp = SOURCE_CORNER_RADIUS_DP,
            onMotionStarted = {
                lumen.finishStretch(stretch)
                stretch = null
            },
            onExpanded = ::installStretch
        )
        morph = controller
        // 预测式返回：拖动预览、取消回弹、松手从当前值续接收缩（§13.5）。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) = controller.beginPredictiveBack()
            override fun handleOnBackProgressed(backEvent: BackEventCompat) = controller.progressPredictiveBack(backEvent.progress)
            override fun handleOnBackCancelled() = controller.cancelPredictiveBack()
            override fun handleOnBackPressed() = controller.commitBack()
        })
        controller.start(isFreshLaunch = savedInstanceState == null)
    }

    private fun installStretch() {
        if (stretch != null) return
        val target = scroll ?: return
        stretch = lumen.installStretch(target) { morph?.isSettledExpanded == true }
    }

    private fun buildPage(palette: LumenPalette): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
            addView(TextView(context).apply {
                text = "‹"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(palette.textPrimary)
                contentDescription = "返回"
                foreground = CoverableRippleDrawable.rounded(palette, dp(24).toFloat())
                isClickable = true
                setOnClickListener { onBackPressedDispatcher.onBackPressed() }
                // 形变期间整页拦截输入，只放行这枚登记过的返回按钮。
                host.registerNavigationBack(this)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            // 与来源条目标题同文字：形变时标题从条目位置迁移到这里。
            addView(TextView(context).apply {
                text = title
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(dp(8), 0, 0, 0)
                toolbarTitle = this
            })
        })
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
            clipChildren = false
            clipToPadding = false
            repeat(10) { index ->
                addView(TextView(context).apply {
                    text = "全屏页内容 ${index + 1}：这一整页是从来源条目形变出来的。"
                    textSize = 15f
                    setTextColor(palette.textPrimary)
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    background = lumen.cardBackground(palette.surface)
                    foreground = CoverableRippleDrawable.rounded(palette, dp(15).toFloat())
                    isClickable = true
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            }
        }
        scroll = NestedScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lumen.onDispatchTouchEvent(event)
        return elastic.dispatch(event) { super.dispatchTouchEvent(it) }
    }

    override fun onStart() {
        super.onStart()
        lumen.onStart()
    }

    override fun onPause() {
        elastic.clear()
        super.onPause()
    }

    override fun onStop() {
        elastic.clear()
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
        morph?.onDestroy()
        elastic.dispose()
        lumen.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val TITLE = "全屏页"
        private const val EXTRA_TITLE = "sample.detail.title"
        /** 入口卡片报不出圆角时的兜底（CARD 默认 15dp）；报得出时以入口自己的圆角为准（§14.2）。 */
        private const val SOURCE_CORNER_RADIUS_DP = 15f

        /** 来源页调用：登记入口、把几何写进 Intent、启动并关掉系统转场。 */
        fun open(source: Activity, entry: View, title: TextView) {
            ContainerMorphLauncher.launch(source, DetailActivity::class.java, entry, title,
                configure = { it.putExtra(EXTRA_TITLE, title.text.toString()) })
        }
    }
}
