package com.lumen.coacervation.sample

import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.lumen.coacervation.engine.controls.LumenReorderCallback
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.motion.MicroMotion
import com.lumen.coacervation.engine.motion.expansion.SectionExpansionController
import com.lumen.coacervation.engine.motion.modal.ModalAnchorStyle
import com.lumen.coacervation.engine.motion.modal.ModalSubPanelOrigin
import com.lumen.coacervation.engine.widget.CoverableRippleDrawable
import com.lumen.coacervation.engine.widget.LumenSegmentScrubBar
import com.lumen.coacervation.engine.widget.LumenSlidingSelection
import com.lumen.coacervation.engine.widget.NavigationBarColors

// ---------------- §13 弹窗的打开与关闭 ----------------

/** 面板标题：**必须是容器的第一个子 View**，与来源条目标题同文字时做标题迁移（§13.3）。 */
private fun SampleActivity.panelTitle(text: String) = TextView(this).apply {
    this.text = text
    textSize = 18f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    setTextColor(lumen.palette.textPrimary)
}

private fun SampleActivity.panelText(text: String) = TextView(this).apply {
    this.text = text
    textSize = 14f
    setTextColor(lumen.palette.textSecondary)
    setPadding(0, dp(10), 0, 0)
}

private fun SampleActivity.panelAction(text: String, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(12), dp(16), dp(12))
    lumen.styleActionButton(this, filled = false)
    setOnClickListener { onClick() }
}

private fun SampleActivity.actionParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }

/** §13.1 条目 → 卡片形变。关闭一律走 modals.dismiss（锚点弹窗自动走收起形变）。 */
internal fun SampleActivity.showMorphPanel(anchor: View) {
    val dialog = Dialog(this)
    val container = modals.createContainer()
    container.addView(panelTitle("形变面板"))
    container.addView(panelText("这张卡片从你点的那一行长出来：outline 从条目的位置与圆角长到这里，标题文字跟着平移。"))
    // §13.4 覆盖式子面板：在父面板里的控件被点击的那一刻抓取两张矩形。
    container.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, 0)
        addView(TextView(context).apply {
            text = "子面板从 ⓘ 长出来，盖住这张卡片"
            textSize = 14f
            setTextColor(lumen.palette.textPrimary)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = "ⓘ"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(lumen.palette.primary)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            foreground = CoverableRippleDrawable.rounded(lumen.palette, dp(20).toFloat())
            isClickable = true
            setOnClickListener { source -> showSubPanel(modals.captureSubPanel(dialog, container, source)) }
        })
    })
    container.addView(panelAction("关闭") { modals.dismiss(dialog, container) }, actionParams())
    modals.present(dialog, container, anchor = anchor)
}

/** §13.4 覆盖式子面板：父面板不关；子面板里"去别处"的动作与自己的退场并行收起父面板。 */
private fun SampleActivity.showSubPanel(origin: ModalSubPanelOrigin) {
    val dialog = Dialog(this)
    val container = modals.createContainer()
    container.addView(panelTitle("子面板"))
    container.addView(panelText("展开端正好盖住父面板；收起时父面板重新露出来，背板压暗保持不变。"))
    container.addView(panelAction("打开第三张面板") {
        // 这一步会开新弹窗，父面板留不住（新弹窗呈现时会硬关当前弹窗）：两张一起收。
        modals.dismissParent(origin)
        modals.dismiss(dialog, container) { showPlainPanel() }
    }, actionParams())
    // 盖住父面板时卡片被抬到父面板的高度：用一条 weight 占位把空档收到关闭行上方，关闭行贴着卡片底边。
    container.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
    container.addView(panelAction("返回") { modals.dismiss(dialog, container) }, actionParams())
    modals.presentSubPanel(origin, dialog, container)
}

/** 没有来源 View 的弹窗：居中缩放入场、缩放淡出退场。 */
private fun SampleActivity.showPlainPanel() {
    val dialog = Dialog(this)
    val container = modals.createContainer()
    container.addView(panelTitle("第三张面板"))
    container.addView(panelText("由流程触发、没有来源控件的面板保持居中缩放入场。系统返回（手势与三键）都会走同一退场。"))
    container.addView(panelAction("好") { modals.dismiss(dialog, container) }, actionParams())
    modals.present(dialog, container)
}

/** §13.2 锚定气泡：正文逐行链式浮现（相邻行恒定重叠 50%），来源图标图案全程守恒。 */
internal fun SampleActivity.showAboutBubble(icon: View) {
    val dialog = Dialog(this)
    val container = modals.createContainer()
    container.addView(panelTitle("关于凝光视效引擎"))
    listOf("柔光与高级材质两套表面", "长按拖动形变与触点高光", "可打断的翻页与形变", "弹窗与全屏页的打开和关闭")
        .forEach { container.addView(panelText("· $it")) }
    container.addView(panelAction("关闭") { modals.dismiss(dialog, container) }, actionParams())
    modals.present(dialog, container, anchor = icon, anchorStyle = ModalAnchorStyle.BUBBLE)
}

// ---------------- 页面 2：动效 ----------------

internal fun SampleActivity.buildMotionPage(content: LinearLayout, palette: LumenPalette) {
    content.addView(caption("左右滑动或点底栏切页：翻页可随时被新手势接住，标题与文字按离手指远近链式跟随（§13.6）。"))

    // §13.7 手风琴：单一进度弹簧驱动卡片裁剪、行级联与兄弟滑行；展开到一半收起会从中间态倒带。
    val accordionContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(18), 0, dp(18), dp(16))
        repeat(4) { addView(panelText("折叠内容第 ${it + 1} 行")) }
    }
    val chevron = TextView(this).apply {
        text = "⌄"
        textSize = 20f
        setTextColor(palette.textSecondary)
    }
    val accordion = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = lumen.cardBackground(palette.surface)
    }
    lateinit var expansion: SectionExpansionController
    accordion.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        foreground = CoverableRippleDrawable.rounded(palette, dp(15).toFloat())
        isClickable = true
        // 可展开标题同样参与长按弹性，不要打 EXCLUDED_TAG（§12.2）。
        addView(TextView(context).apply {
            text = "手风琴（点按展开/收起，可随时打断）"
            textSize = 16f
            setTextColor(palette.textPrimary)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(chevron)
        setOnClickListener { expansion.setExpanded(!expansion.expanded) }
    })
    accordion.addView(accordionContent, LinearLayout.LayoutParams(-1, -2))
    content.addView(accordion, cardParams())
    expansion = SectionExpansionController(accordion, accordionContent, chevron, density,
        notifyPositionChanged = { lumen.notifyPositionChanged() })

    // §12.4 分段档位条：轻点切换、按住拖动滑块跟手、松手弹簧吸附；自管触摸，不参与全局弹性。
    val scrubDescription = panelText("当前档位：标准")
    val scrub = LumenSegmentScrubBar(this, null).apply {
        tag = ElasticInteractionController.EXCLUDED_TAG
        // 5 段：来源工程的档位条会把第 5 段静默丢掉（§15.1）。
        val labels = listOf("关", "精简", "标准", "详细", "全部")
        configure(
            labels = labels,
            colors = NavigationBarColors(text = palette.textSecondary, selectedText = palette.primary, highlight = palette.primary),
            thumbBackground = lumen.chromeOverlayBackground(palette.surface, 18f, selected = true),
            trackBackground = lumen.surface(palette.surface, 20f, com.lumen.coacervation.engine.model.SurfaceRole.SELECTED_ITEM),
            selectedIndex = 2
        ) { index -> MicroMotion.swapText(scrubDescription, "当前档位：${labels[index]}") }
    }
    content.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = lumen.cardBackground(palette.surface)
        // 档位条按压会放大、拖动会位移：直接宿主放行两层裁剪（§12.3）。
        clipChildren = false
        clipToPadding = false
        addView(scrub, LinearLayout.LayoutParams(-1, -2))
        addView(scrubDescription)
    }, cardParams())

    // §13.9 微动效：文字切换、角标、显隐过渡、提示条。
    val swapTarget = panelText("文字切换：点下面的按钮")
    val badge = TextView(this).apply {
        text = "NEW"
        textSize = 10f
        setTextColor(palette.onPrimary)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(palette.primary)
        }
        visibility = View.INVISIBLE
    }
    val collapsible = panelText("这一行由显隐过渡控制：父容器用 ChangeBounds + Fade 让位。")
    val micro = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = lumen.cardBackground(palette.surface)
        addView(swapTarget)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(panelText("角标："))
            addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })
        addView(collapsible)
    }
    var swapIndex = 0
    micro.addView(panelAction("切换文字") {
        swapIndex++
        MicroMotion.swapText(swapTarget, if (swapIndex % 2 == 1) "文字切换：淡出、换字、再淡入" else "文字切换：连续点只保留最新文案")
    }, actionParams())
    micro.addView(panelAction("显示/隐藏角标") {
        if (badge.visibility == View.VISIBLE) MicroMotion.hideBadge(badge) else MicroMotion.showBadge(badge)
    }, actionParams())
    micro.addView(panelAction("显示/隐藏上面那一行") {
        MicroMotion.setVisible(micro, collapsible, collapsible.visibility != View.VISIBLE)
    }, actionParams())
    content.addView(micro, cardParams())

    // §13.11 选中框连贯滑动（来源工程 JEV 灵敏度面板）：点新选项时选中框从旧行滑到新行，位置与高度一起插值；
    // 滑动途中再点别的行，从当前位置与速度续接。行高不一：长说明会折行，选中框按行的实际高度伸缩。
    content.addView(buildSlidingChoiceCard(palette), cardParams())

    // §13.8 定位并高亮：滚到本页最后一张卡片，到位后闪一次高亮。
    val farTarget = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = lumen.cardBackground(palette.surface)
        addView(panelText("我是定位目标：滚到这里后闪一次高亮。"))
    }
    content.addView(panelAction("定位到页底的目标") {
        val scroll = scrolls[pager.selectedPage]
        reveal.reveal(scroll, farTarget, topOffsetPx = dp(84) + dp(28))
    }, cardParams())
    repeat(6) { content.addView(panelText("占位内容 ${it + 1}：让页面足够长，定位才需要滚动。"), cardParams()) }
    content.addView(farTarget, cardParams())
}

// ---------------- 页面 3：长按拖拽排序（lumen-controls） ----------------

internal fun SampleActivity.buildListPage(content: LinearLayout, palette: LumenPalette) {
    content.addView(caption("长按一行拾起，上下拖动排序；松手按拖动距离滑回落位（§12.5）。"))
    val items = MutableList(8) { "条目 ${it + 1}" }
    val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            TextView(parent.context).apply {
                textSize = 16f
                setTextColor(palette.textPrimary)
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = lumen.cardBackground(palette.surface)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                // 列表行由 ItemTouchHelper 接管长按，不参与全局弹性。
                tag = ElasticInteractionController.EXCLUDED_TAG
            }
        ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = "≡  ${items[position]}"
        }

        override fun getItemCount() = items.size
    }
    val list = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(context)
        this.adapter = adapter
        isNestedScrollingEnabled = false
        // 行内状态自己渐变，不整行交叉淡化一下。
        (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }
    val status = panelText("顺序：${items.joinToString("、")}")
    ItemTouchHelper(LumenReorderCallback(
        canDrag = { true },
        onMove = { from, to ->
            items.add(to, items.removeAt(from))
            adapter.notifyItemMoved(from, to)
            true
        },
        // 拖动中途不提交数据，松手后一次性落库。
        onDrop = { MicroMotion.swapText(status, "顺序：${items.joinToString("、")}") }
    )).attachToRecyclerView(list)
    content.addView(list, LinearLayout.LayoutParams(-1, -2))
    content.addView(status, cardParams())

    // §14.5 网格排序：GridLayoutManager 三列，方向自动放开到四向；大方块拾起时的放大量按尺寸封顶。
    content.addView(caption("三列网格：长按拾起后可上下左右换位。"))
    val tiles = MutableList(9) { "${it + 1}" }
    val gridAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            TextView(parent.context).apply {
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(palette.textPrimary)
                background = lumen.cardBackground(palette.surface, 16f)
                layoutParams = RecyclerView.LayoutParams(-1, dp(96)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                tag = ElasticInteractionController.EXCLUDED_TAG
            }
        ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = tiles[position]
        }

        override fun getItemCount() = tiles.size
    }
    val gridList = RecyclerView(this).apply {
        layoutManager = GridLayoutManager(context, 3)
        this.adapter = gridAdapter
        isNestedScrollingEnabled = false
        (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }
    ItemTouchHelper(LumenReorderCallback(
        canDrag = { true },
        onMove = { from, to ->
            tiles.add(to, tiles.removeAt(from))
            gridAdapter.notifyItemMoved(from, to)
            true
        },
        onDrop = {}
    )).attachToRecyclerView(gridList)
    content.addView(gridList, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    content.addView(TextView(this).apply {
        text = "列表行的卡片表面同样是引擎材质。"
        textSize = 12f
        setTextColor(ColorUtils.setAlphaComponent(palette.textSecondary, 0xB0))
        setPadding(dp(6), 0, dp(6), dp(12))
    })
}

// ---------------- §13.11 选中框连贯滑动 ----------------

private fun SampleActivity.buildSlidingChoiceCard(palette: LumenPalette) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(12), dp(14), dp(12), dp(12))
    background = lumen.cardBackground(palette.surface)
    clipChildren = false
    clipToPadding = false
    addView(TextView(context).apply {
        text = "选中框连贯滑动"
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(palette.textPrimary)
        setPadding(dp(6), 0, dp(6), dp(10))
    })
    val levels = listOf(
        "宽松" to "只拦截明确命中的内容。",
        "标准" to "推荐。明确命中与高度疑似都拦截。",
        "严格" to "疑似内容也拦截；可能误伤少量正常内容，说明文字更长，这一行会折成两行，选中框按实际高度伸缩。",
        "仅标记" to "不拦截，只在内容旁标注判定结果。"
    )
    val status = panelText("当前：标准")
    val titles = ArrayList<TextView>(levels.size)
    val choice = LumenSlidingSelection(
        context = context,
        indicatorBackground = lumen.selectionBackground(palette.surface, 14f),
        notifyPositionChanged = { lumen.notifyPositionChanged() }
    )
    levels.forEach { (title, summary) ->
        val titleView = TextView(context).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.textSecondary)
        }
        titles += titleView
        choice.addOption(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            // 行只有涟漪：选中框在行下面滑动；长按拖动选中行时选中框跟着行形变。
            background = CoverableRippleDrawable.rounded(palette, dp(14).toFloat())
            addView(titleView)
            addView(TextView(context).apply {
                text = summary
                textSize = 12f
                setTextColor(palette.textSecondary)
                alpha = 0.72f
                setPadding(0, dp(4), 0, 0)
            })
        })
    }
    choice.setOnHighlightListener { index, weight ->
        titles[index].setTextColor(ColorUtils.blendARGB(palette.textSecondary, palette.primary, weight))
    }
    choice.select(1, animate = false)
    choice.onSelect = { index -> MicroMotion.swapText(status, "当前：${levels[index].first}") }
    addView(choice, LinearLayout.LayoutParams(-1, -2))
    addView(status)
}
