package com.lumen.coacervation.sample

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.controls.LumenControls
import com.lumen.coacervation.engine.glow.GlowBackdropTarget
import com.lumen.coacervation.engine.glow.GlowFloatingChrome
import com.lumen.coacervation.engine.glow.GlowLegibilityPolicy
import com.lumen.coacervation.engine.glow.GlowScrollEdge
import com.lumen.coacervation.engine.glow.GlowScrollEdgePolicy
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.interaction.LumenElasticInteraction
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SkinId
import com.lumen.coacervation.engine.motion.modal.LumenModalPresenter
import com.lumen.coacervation.engine.motion.modal.LumenModalStyle
import com.lumen.coacervation.engine.motion.morph.ContainerMorphLauncher
import com.lumen.coacervation.engine.motion.pager.LumenPagePager
import com.lumen.coacervation.engine.motion.pager.LumenPageScrollView
import com.lumen.coacervation.engine.motion.pager.PageTextChain
import com.lumen.coacervation.engine.motion.reveal.LumenReveal
import com.lumen.coacervation.engine.widget.CoverableRippleDrawable
import com.lumen.coacervation.engine.widget.LumenNavigationBar
import com.lumen.coacervation.engine.widget.NavigationBarColors
import com.lumen.coacervation.engine.widget.NavigationBarSurface

/**
 * 最小接入示例（适配标准 §2～§13 的逐条对照）：`AppCompatActivity` + 组合式委托。
 *
 * 页面结构：
 * ```
 * root（bindRoot：根背景）
 *  ├ GlowBackdropTarget（悬浮栏下方的内容容器，只装翻页器）
 *  │   └ LumenPagePager（可打断翻页 + 文字链）→ 四页 LumenPageScrollView（各自包进回弹视口）
 *  ├ 顶栏胶囊（FLOATING；图标按钮打开锚定气泡）
 *  └ 胶囊底栏 LumenNavigationBar（点击/拖动选页、触点高光、弹簧回弹）
 * ```
 * 悬浮栏必须是内容容器的**兄弟**而不是子 View：探针与内容节点玻璃录的是"栏下面有什么"。
 */
class SampleActivity : AppCompatActivity() {

    // §2.5 视效调参由宿主存储；边缘高光随会话读取，长按形变与光晕每次按下现读。
    internal val tuning by lazy(LazyThreadSafetyMode.NONE) { SampleTuningStore(this) }
    // 宿主自己的外观设置：强调色、弹窗背景模糊（深浅色由 SampleApplication 在启动时应用）。
    internal val settings by lazy(LazyThreadSafetyMode.NONE) { SampleSettingsStore(this) }
    internal var backgroundPreviewLoader: SampleBackgroundPreviewLoader? = null
    // 自定义背景的图片选择：结果交给后台线程导入（LiquidBackgroundStore）。
    internal val backgroundPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBackgroundImage(uri)
    }
    // §2 组合式接入：一个委托，六个回调转发给它。
    internal val lumen = LumenActivityDelegate(this, ::resolvePalette) { tuning.current }
    // §12 全局长按弹性：Activity 与弹窗窗口共用一个实例。光晕调参现读，滑块改动即时生效。
    internal val elastic by lazy(LazyThreadSafetyMode.NONE) {
        LumenElasticInteraction(this, lumen, effectTuning = { tuning.current })
    }
    // §13 弹窗的打开与关闭；弹窗内容同样交给 lumen-controls 换装。
    internal val modals by lazy(LazyThreadSafetyMode.NONE) {
        LumenModalPresenter(this, lumen, style = LumenModalStyle(backdropBlur = settings.modalBackdropBlur),
            elastic = elastic, styleContent = { LumenControls.style(it, lumen) })
    }
    // §13.8 定位并高亮。
    internal val reveal by lazy(LazyThreadSafetyMode.NONE) { LumenReveal(lumen.palette.primary) }

    private var chrome: GlowFloatingChrome? = null
    private var textChain: PageTextChain? = null
    private var navigation: LumenNavigationBar? = null
    internal lateinit var pager: LumenPagePager
    internal val scrolls = ArrayList<LumenPageScrollView>()
    private val stretches = ArrayList<View?>()
    private var topInsetPx = 0f
    private var bottomInsetPx = 0f
    private var previousPage = 0
    private var fullscreenEntry: View? = null

    internal val density get() = resources.displayMetrics.density
    internal fun dp(value: Int) = (value * density).toInt()

    /** §2.2 配色由宿主提供：设置页选的强调色 + 引擎推荐的中性表面。真实应用接自己的主题系统。 */
    private fun resolvePalette(): LumenPalette {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return settings.accent.palette(dark)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // §2.3 宿主自己的授权门、早退检查在这里之前完成；通过后才 prepare()。
        lumen.prepare()

        val palette = lumen.palette
        val root = FrameLayout(this)
        val target = GlowBackdropTarget(this)
        pager = LumenPagePager(this)
        // §13.6 使用 AppCompat 开关的宿主把 SwitchCompat 的拨钮交给翻页器识别。
        pager.switchParts = { LumenControls.switchParts(it) ?: LumenPagePager.frameworkSwitchParts(it) }
        val headings = ArrayList<View>(4)
        val pages = listOf<Pair<String, (LinearLayout, LumenPalette) -> Unit>>(
            "材质与面板" to ::buildMaterialPage,
            "动效" to { content, colors -> buildMotionPage(content, colors) },
            "列表" to { content, colors -> buildListPage(content, colors) },
            "排布" to { content, colors -> buildLayoutPage(content, colors) },
            "自适应" to { content, colors -> buildAdaptivePage(content, colors) },
            "设置" to { content, colors -> buildSettingsPage(content, colors) }
        )
        pages.forEach { (title, build) ->
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                // 文字链与长按弹性都会让子 View 画出自身边界：内容层放行裁剪（§12.3）。
                clipChildren = false
                clipToPadding = false
            }
            val heading = TextView(this).apply {
                text = title
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(dp(6), dp(8), 0, dp(12))
            }
            content.addView(heading)
            headings += heading
            build(content, palette)
            val scroll = LumenPageScrollView(this, onUserScroll = { reveal.cancel() }, onContentTouch = {}).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                addView(content, ViewGroup.LayoutParams(-1, -2))
            }
            scrolls += scroll
            pager.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        target.addView(pager, FrameLayout.LayoutParams(-1, -1))
        root.addView(target, FrameLayout.LayoutParams(-1, -1))

        val topBar = buildTopBar(palette)
        root.addView(topBar, FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP).apply { setMargins(dp(12), dp(8), dp(12), 0) })
        val dock = buildNavigation(palette)
        // 胶囊底栏按压会放大、拖动会位移：直接宿主必须放行裁剪（§12.3）。
        root.clipChildren = false
        root.clipToPadding = false
        // 六项底栏最宽 360dp；窄窗口（分屏、小屏）里收到窗口宽度减两侧 12dp。
        val dockWidth = minOf(dp(360), dp(resources.configuration.screenWidthDp - 24))
        root.addView(dock, FrameLayout.LayoutParams(dockWidth, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { setMargins(0, 0, 0, dp(12)) })
        // 不贴滚动边缘的悬浮表面（§15.6）：悬浮按钮登记到悬浮栏时边传 null，照样做可读性补偿与内容节点玻璃。
        val fab = TextView(this).apply {
            text = "↑"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(palette.textPrimary)
            background = lumen.floatingBackground(palette.surface, 26f)
            foreground = CoverableRippleDrawable.rounded(palette, dp(26).toFloat())
            contentDescription = "回到顶部"
            isClickable = true
            setOnClickListener { scrolls.getOrNull(pager.selectedPage)?.smoothScrollTo(0, 0) }
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END)
            .apply { setMargins(0, 0, dp(20), dp(96)) })
        setContentView(root)
        // targetSdk 35+ 强制全屏绘制：根背景铺满窗口，悬浮栏与滚动内容按系统栏让位。
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (topBar.layoutParams as ViewGroup.MarginLayoutParams).topMargin = bars.top + dp(8)
            (dock.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = bars.bottom + dp(12)
            (fab.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = bars.bottom + dp(96)
            (fab.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = bars.right + dp(20)
            fab.requestLayout()
            topBar.requestLayout()
            dock.requestLayout()
            scrolls.forEach { it.setPadding(bars.left, bars.top + dp(84), bars.right, bars.bottom + dp(100)) }
            topInsetPx = (bars.top + dp(84)).toFloat()
            bottomInsetPx = (bars.bottom + dp(100)).toFloat()
            chrome?.onContentMoved()
            insets
        }

        // §2.4 setContentView 之后绑定根；整个高级材质失败时回调一次（总在当前调用栈之外）。
        lumen.bindRoot(root) {
            Toast.makeText(this, "高级材质不可用，已回退到柔光", Toast.LENGTH_SHORT).show()
            recreate()
        }
        // §6.1 柔光的软件透镜要采样悬浮栏下方的内容层。
        lumen.bindContentSource(pager)
        // §5.4 回弹：每页包进回弹视口，只在"当前页且翻页已停稳"时允许。
        scrolls.forEachIndexed { index, scroll ->
            stretches += lumen.installStretch(scroll) { pager.selectedPage == index && pager.isSettled }
        }
        // §5.3 原生控件换装（可选模块 lumen-controls）。
        LumenControls.style(root, lumen)

        // §13.6 翻页器接线：位置每变一帧，底栏指示、引擎采样、悬浮栏、文字链一起跟上。
        textChain = PageTextChain(pager, headings)
        pager.onPositionChanged = {
            navigation?.setPageProgress(pager.pagePosition, notifyPositionChanged = false)
            lumen.notifyPositionChanged()
            chrome?.onContentMoved()
            textChain?.onPositionChanged()
        }
        pager.onPageSelected = { index ->
            stretches.getOrNull(previousPage)?.let(lumen::finishStretch)
            previousPage = index
            navigation?.setSelectedPage(index)
        }
        pager.onMotionStarted = { stretches.forEach(lumen::finishStretch) }
        pager.onUserInteraction = { reveal.cancel() }

        // §6 悬浮栏可读性：滚动边缘溶解 + 按下方内容自适应 + 内容节点玻璃。
        chrome = GlowFloatingChrome(target, { lumen.engine }, ::edgeCoverage).apply {
            attach(topBar, GlowScrollEdge.TOP, palette.textPrimary, onForeground = foregroundFor(topBar, palette))
            attach(dock, GlowScrollEdge.BOTTOM, palette.textPrimary) { boost -> dock.setLegibility(boost, palette.surface) }
            attach(fab, null, palette.textPrimary) { boost -> fab.setTextColor(GlowLegibilityPolicy.foreground(palette.textPrimary, boost)) }
        }

        // 重建（改设置、切材质、旋转、分屏）后回到原来的页与滚动位置，而不是跳回第一页。
        val restoredPage = savedInstanceState?.getInt(STATE_PAGE, 0)?.takeIf { it in scrolls.indices } ?: 0
        if (restoredPage != 0) {
            pager.selectPage(restoredPage, animate = false)
            previousPage = restoredPage
            navigation?.setSelectedPage(restoredPage)
            navigation?.setPageProgress(restoredPage.toFloat(), notifyPositionChanged = false)
        }
        val restoredScroll = savedInstanceState?.getInt(STATE_SCROLL, 0) ?: 0
        if (restoredScroll > 0) {
            val scroll = scrolls[restoredPage]
            scroll.doOnLayout { scroll.scrollTo(0, restoredScroll) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::pager.isInitialized) return
        outState.putInt(STATE_PAGE, pager.selectedPage)
        outState.putInt(STATE_SCROLL, scrolls.getOrNull(pager.selectedPage)?.scrollY ?: 0)
    }

    private fun edgeCoverage(edge: GlowScrollEdge): Float =
        GlowScrollEdgePolicy.pagerCoverage(pager.pagePosition, scrolls.size) { index ->
            val scroll = scrolls[index]
            when (edge) {
                GlowScrollEdge.TOP -> GlowScrollEdgePolicy.topCoverage(scroll.scrollY, topInsetPx)
                GlowScrollEdge.BOTTOM -> {
                    val child = scroll.getChildAt(0)
                    val range = if (child == null) 0 else
                        (child.height - (scroll.height - scroll.paddingTop - scroll.paddingBottom)).coerceAtLeast(0)
                    GlowScrollEdgePolicy.bottomCoverage(scroll.scrollY, range, bottomInsetPx)
                }
            }
        }

    /** 可读性补偿的前景回调：0 = 原色，1 = 最大同向加强。 */
    private fun foregroundFor(bar: ViewGroup, palette: LumenPalette): (Float) -> Unit = { boost ->
        val color = GlowLegibilityPolicy.foreground(palette.textPrimary, boost)
        fun visit(view: View) {
            if (view is TextView && view !is SwitchCompat) view.setTextColor(color)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(bar)
    }

    private fun buildNavigation(palette: LumenPalette) = LumenNavigationBar(
        context = this,
        titles = listOf("材质", "动效", "列表", "排布", "自适应", "设置"),
        icons = intArrayOf(R.drawable.ic_sample_material, R.drawable.ic_sample_motion, R.drawable.ic_sample_list,
            R.drawable.ic_sample_layout, R.drawable.ic_sample_adaptive, R.drawable.ic_sample_settings),
        colors = NavigationBarColors(text = palette.textSecondary, selectedText = palette.primary, highlight = palette.primary),
        backgroundFactory = { surface, radius ->
            when (surface) {
                NavigationBarSurface.BAR -> lumen.floatingBackground(palette.surface, radius)
                NavigationBarSurface.SELECTION -> lumen.chromeOverlayBackground(palette.surface, radius, selected = true)
            }
        },
        onSelect = { pager.selectPage(it) },
        onUserInteraction = { reveal.cancel() },
        onVisualMovement = { lumen.notifyPositionChanged() }
    ).also {
        // 底栏自己处理按压/拖动/高光，不参与全局长按弹性（§12.1）。
        it.tag = ElasticInteractionController.EXCLUDED_TAG
        navigation = it
    }

    private fun buildTopBar(palette: LumenPalette) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(12), 0)
        background = lumen.floatingBackground(palette.surface, 30f)
        // 胶囊本身可点：长按拖动落在按钮以外时整条胶囊一起形变（§12.2）。
        isClickable = true
        clipChildren = false
        clipToPadding = false
        // §13.2 锚定气泡：工具栏小图标 → 贴在它旁边、伸出小角的气泡。来源必须是 ImageView（图案交接）。
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_sample_info)
            imageTintList = ColorStateList.valueOf(palette.textPrimary)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "关于"
            foreground = CoverableRippleDrawable.rounded(palette, dp(22).toFloat())
            isClickable = true
            setOnClickListener { showAboutBubble(this) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(TextView(context).apply {
            text = "Lumen"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.textPrimary)
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = "高级材质"
            textSize = 13f
            setTextColor(palette.textPrimary)
        })
        addView(SwitchCompat(context).apply {
            // §3.2 切换材质：写入选择 → 重建 Activity。写入失败时把开关恢复原状。
            isChecked = LumenEngine.requestedMaterial(context) == SkinId.LIQUID
            setOnCheckedChangeListener { button, checked ->
                val target = if (checked) SkinId.LIQUID else SkinId.MATERIAL_YOU
                if (LumenEngine.selectMaterial(context, target, realtimeCapture = checked)) {
                    recreate()
                } else {
                    button.isChecked = !checked
                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    // ---------------- 页面 1：材质与面板 ----------------

    private fun buildMaterialPage(content: LinearLayout, palette: LumenPalette) {
        content.addView(caption("当前材质：" + if (lumen.isLiquidEffective) "高级材质（${lumen.backendName}）" else "柔光"))
        content.addView(caption("长按任意卡片或按钮后拖动：卡片跟手形变、触点高光流动，松手弹簧回弹（§12）。"))
        content.addView(caption("边缘高光、长按形变与光晕等参数在「设置」页调节。"))
        // §13.1 条目 → 卡片形变：弹窗标题与条目标题**同一段文字**才会做标题迁移。
        content.addView(entryRow(palette, "形变面板", "条目长成屏幕中央的卡片；面板里还能再开覆盖式子面板") { row ->
            showMorphPanel(row)
        }, cardParams())
        // §13.5 条目 → 全屏 Activity 的容器形变（带预测式返回）。
        lateinit var fullscreenRow: LinearLayout
        fullscreenRow = entryRow(palette, "全屏页", "条目形变成整个页面；手势返回可拖动预览、松手续接") {
            val titleColumn = fullscreenRow.getChildAt(0) as LinearLayout
            DetailActivity.open(this, fullscreenRow, titleColumn.getChildAt(0) as TextView)
        }
        fullscreenEntry = fullscreenRow
        content.addView(fullscreenRow, cardParams())
        repeat(2) { index -> content.addView(settingCard(palette, index), cardParams()) }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = lumen.cardBackground(palette.surface)
            addView(EditText(context).apply {
                hint = "输入框：SELECTED_ITEM 表面 + 控件描边"
                setTextColor(palette.textPrimary)
                setHintTextColor(ColorUtils.setAlphaComponent(palette.textSecondary, 0x99))
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(CheckBox(context).apply {
                text = "复选框"
                setTextColor(palette.textPrimary)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            addView(TextView(context).apply {
                text = "文字按钮"
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                lumen.styleActionButton(this, filled = true)
                setOnClickListener { Toast.makeText(context, "按钮", Toast.LENGTH_SHORT).show() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }, cardParams())
        // §5 每种表面角色与控件样式各一份，可点开、可长按拖动。
        buildSurfaceGallery(content, palette)
    }

    // ---------------- 生命周期（§2.1）与弹性（§12） ----------------

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
        reveal.cancel()
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
        backgroundPreviewLoader?.close()
        backgroundPreviewLoader = null
        chrome?.dispose()
        chrome = null
        textChain?.dispose()
        navigation?.dispose()
        reveal.cancel()
        modals.onDestroy()
        // 来源页销毁：清掉形变来源登记（只清属于这个入口的那份）。
        ContainerMorphLauncher.clear(DetailActivity::class.java, fullscreenEntry)
        elastic.dispose()
        lumen.onDestroy()
        super.onDestroy()
    }

    // ---------------- 小工具 ----------------

    internal fun caption(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(lumen.palette.textSecondary)
        setPadding(dp(6), 0, dp(6), dp(12))
    }

    internal fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }

    /** 一行可点击的入口：标题 + 说明。行本身是弹性形变组（有表面、可点击）。 */
    internal fun entryRow(palette: LumenPalette, title: String, summary: String, onClick: (View) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = lumen.cardBackground(palette.surface)
            // 自绘圆角涟漪：content 与 mask 同圆角，长按高光才按设计圆角裁剪（§12.2）。
            foreground = CoverableRippleDrawable.rounded(palette, dp(15).toFloat())
            isClickable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 16f
                    setTextColor(palette.textPrimary)
                })
                addView(TextView(context).apply {
                    text = summary
                    textSize = 12f
                    setTextColor(palette.textSecondary)
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply {
                text = "›"
                textSize = 22f
                setTextColor(palette.textSecondary)
            })
            setOnClickListener { onClick(it) }
        }

    private fun settingCard(palette: LumenPalette, index: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(16), dp(12), dp(16))
        background = lumen.cardBackground(palette.surface)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "设置项 ${index + 1}"
                textSize = 16f
                setTextColor(palette.textPrimary)
            })
            addView(TextView(context).apply {
                text = "开关行不参与长按弹性，保留原生按压与拨动（§12.1）。"
                textSize = 12f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(SwitchCompat(context).apply { isChecked = index % 3 == 0 })
    }

    private companion object {
        private const val STATE_PAGE = "sample.page"
        private const val STATE_SCROLL = "sample.scroll"
    }
}
