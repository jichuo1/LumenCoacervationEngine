package com.lumen.coacervation.sample

import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.motion.expansion.SectionExpansionController
import com.lumen.coacervation.engine.widget.CoverableRippleDrawable

/**
 * 第四页"排布"（适配标准 §14）：同一套交互与动效放进不同尺寸的卡片与不同排布里。
 *
 * - 两列网格：长按拖动不钻到邻居底下（§14.1）；点开从方块自己的圆角长出来（§14.2）。
 * - 大小混排：大卡片点开形变成全屏页，起点圆角就是它自己的（§14.2）。
 * - 横向轮播：左右是邻居；文字链不画出轮播视口（§14.4）；定位会先横向滚到目标（§14.6）。
 * - 卡中卡：外层卡片打 CONTAINER_TAG，里面的小卡片各自弹（§14.1）。
 * - 网格里的手风琴：同一行有更高的格子时，下面的行不动（§14.3）。
 */
internal fun SampleActivity.buildLayoutPage(content: LinearLayout, palette: LumenPalette) {
    content.addView(sampleAction("局部视效调节实验室") {
        startActivity(android.content.Intent(this, SurfaceSandboxActivity::class.java))
    })
    content.addView(caption("同一套形变、弹性与链式动画，在不同尺寸与排布下各自适配。"))

    // ---------------- 两列网格 ----------------
    content.addView(sectionLabel("两列网格"))
    val grid = GridLayout(this).apply {
        columnCount = 2
        // 卡片按压放大、拖动位移会画出自身边界：直接宿主放行两层裁剪（§12.3）。
        clipChildren = false
        clipToPadding = false
    }
    listOf("方块 A", "方块 B", "方块 C", "方块 D").forEachIndexed { index, title ->
        grid.addView(tile(palette, title, "长按拖动，点开形变", heightDp = 120, radiusDp = 18f) { tileView, titleView ->
            showTilePanel(tileView, titleView.text.toString())
        }, gridCell(index % 2))
    }
    content.addView(grid, fullWidth())

    // ---------------- 大小混排 ----------------
    content.addView(sectionLabel("大小混排"))
    val bento = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }
    lateinit var hero: LinearLayout
    hero = tile(palette, "大卡片", "点开形变成全屏页，起点就是这张卡的圆角", heightDp = 252, radiusDp = 24f) { heroView, titleView ->
        DetailActivity.open(this, heroView, titleView)
    }
    bento.addView(hero, LinearLayout.LayoutParams(0, dp(252), 1.4f).apply { marginEnd = dp(12) })
    bento.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(tile(palette, "小卡片 1", "", heightDp = 120, radiusDp = 14f) { v, t -> showTilePanel(v, t.text.toString()) },
            LinearLayout.LayoutParams(-1, dp(120)).apply { bottomMargin = dp(12) })
        addView(tile(palette, "小卡片 2", "", heightDp = 120, radiusDp = 14f) { v, t -> showTilePanel(v, t.text.toString()) },
            LinearLayout.LayoutParams(-1, dp(120)))
    }, LinearLayout.LayoutParams(0, -2, 1f))
    content.addView(bento, fullWidth())

    // ---------------- 横向轮播 ----------------
    content.addView(sectionLabel("横向轮播"))
    val carouselRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    val carouselCards = (1..8).map { index ->
        tile(palette, "轮播 $index", "左右是邻居", heightDp = 168, radiusDp = 20f) { v, t -> showTilePanel(v, t.text.toString()) }
    }
    carouselCards.forEachIndexed { index, card ->
        carouselRow.addView(card, LinearLayout.LayoutParams(dp(150), dp(168)).apply {
            if (index < carouselCards.lastIndex) marginEnd = dp(12)
        })
    }
    val carousel = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(carouselRow)
    }
    content.addView(carousel, fullWidth())
    content.addView(sampleAction("定位到轮播第 7 张（先竖向，再横向）") {
        reveal.reveal(scrolls[pager.selectedPage], carouselCards[6], topOffsetPx = dp(84) + dp(28))
    }, fullWidth())

    // ---------------- 卡中卡 ----------------
    content.addView(sectionLabel("卡中卡"))
    val outer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = lumen.cardBackground(palette.surface, 22f)
        clipChildren = false
        clipToPadding = false
        // 外层卡片只承载，不作为形变组：里面的小卡片各自是弹性单位（§14.1）。
        tag = ElasticInteractionController.CONTAINER_TAG
        addView(TextView(context).apply {
            text = "外层卡片（CONTAINER_TAG）：长按里面的小卡片，只有它自己动"
            textSize = 13f
            setTextColor(palette.textSecondary)
            setPadding(dp(4), 0, dp(4), dp(10))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            listOf("子卡 1", "子卡 2", "子卡 3").forEachIndexed { index, title ->
                addView(TextView(context).apply {
                    text = title
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTextColor(palette.textPrimary)
                    background = lumen.selectionBackground(palette.surface, 14f)
                    foreground = CoverableRippleDrawable.rounded(palette, dp(14).toFloat())
                    isClickable = true
                    setOnClickListener { showTilePanel(this, title) }
                }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { if (index < 2) marginEnd = dp(10) })
            }
        })
    }
    content.addView(outer, fullWidth())

    // ---------------- 网格里的手风琴 ----------------
    content.addView(sectionLabel("网格里的手风琴"))
    val accordionGrid = GridLayout(this).apply {
        columnCount = 2
        clipChildren = false
        clipToPadding = false
    }
    val folding = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(14), 0, dp(14), dp(14))
        repeat(5) {
            addView(TextView(context).apply {
                text = "折叠行 ${it + 1}"
                textSize = 13f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(6), 0, 0)
            })
        }
    }
    val chevron = TextView(this).apply {
        text = "⌄"
        textSize = 18f
        setTextColor(palette.textSecondary)
    }
    val accordionCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = lumen.cardBackground(palette.surface, 18f)
    }
    lateinit var expansion: SectionExpansionController
    accordionCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        foreground = CoverableRippleDrawable.rounded(palette, dp(18).toFloat())
        isClickable = true
        addView(TextView(context).apply {
            text = "点我展开"
            textSize = 15f
            setTextColor(palette.textPrimary)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(chevron)
        setOnClickListener { expansion.setExpanded(!expansion.expanded) }
    })
    accordionCard.addView(folding, LinearLayout.LayoutParams(-1, -2))
    accordionGrid.addView(accordionCard, gridCell(0, alignTop = true))
    // 同行一张 140dp 的格子：展开高度不超过它时，下面那行纹丝不动；超过的部分才推下去。
    accordionGrid.addView(tile(palette, "同行格子", "140dp 高", heightDp = 140, radiusDp = 18f) { v, t ->
        showTilePanel(v, t.text.toString())
    }, gridCell(1))
    accordionGrid.addView(tile(palette, "下一行 A", "跟着行高滑", heightDp = 96, radiusDp = 18f) { v, t ->
        showTilePanel(v, t.text.toString())
    }, gridCell(0))
    accordionGrid.addView(tile(palette, "下一行 B", "跟着行高滑", heightDp = 96, radiusDp = 18f) { v, t ->
        showTilePanel(v, t.text.toString())
    }, gridCell(1))
    content.addView(accordionGrid, fullWidth())
    expansion = SectionExpansionController(accordionCard, folding, chevron, density,
        cornerRadiusDp = 18f, notifyPositionChanged = { lumen.notifyPositionChanged() })
    content.addView(caption("最后一张卡片撑出页底，让定位与回弹都有空间。"))
}

/** 网格方块：卡片表面 + 自绘圆角涟漪（与卡片同圆角，长按高光与形变起点都按它）。 */
private fun SampleActivity.tile(
    palette: LumenPalette,
    title: String,
    summary: String,
    heightDp: Int,
    radiusDp: Float,
    onClick: (LinearLayout, TextView) -> Unit
): LinearLayout {
    val titleView = TextView(this).apply {
        text = title
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(palette.textPrimary)
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        minimumHeight = dp(heightDp)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = lumen.cardBackground(palette.surface, radiusDp)
        foreground = CoverableRippleDrawable.rounded(palette, dp(radiusDp.toInt()).toFloat())
        isClickable = true
        addView(titleView)
        if (summary.isNotEmpty()) addView(TextView(context).apply {
            text = summary
            textSize = 12f
            setTextColor(palette.textSecondary)
            setPadding(0, dp(4), 0, 0)
        })
        setOnClickListener { onClick(this, titleView) }
    }
}

/** 从任意卡片形变出来的面板：标题与卡片标题同文字，做标题迁移（§13.3）。 */
internal fun SampleActivity.showTilePanel(anchor: View, title: String) {
    val dialog = Dialog(this)
    val container = modals.createContainer()
    container.addView(TextView(this).apply {
        text = title
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(lumen.palette.textPrimary)
    })
    container.addView(TextView(this).apply {
        text = "形变的起点是这张卡片自己的位置与圆角：方块从圆角方块长出来，图标从圆长出来，大卡片会缩成面板。"
        textSize = 14f
        setTextColor(lumen.palette.textSecondary)
        setPadding(0, dp(10), 0, 0)
    })
    container.addView(sampleAction("关闭") { modals.dismiss(dialog, container) },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    modals.present(dialog, container, anchor = anchor)
}

private fun SampleActivity.sectionLabel(text: String) = TextView(this).apply {
    this.text = text
    textSize = 15f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(lumen.palette.textPrimary)
    setPadding(dp(6), dp(16), dp(6), dp(8))
}

private fun SampleActivity.sampleAction(text: String, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(12), dp(16), dp(12))
    lumen.styleActionButton(this, filled = false)
    setOnClickListener { onClick() }
}

private fun SampleActivity.fullWidth() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }

/** 两列等宽的网格格子，格子之间 12dp。 */
private fun SampleActivity.gridCell(column: Int, alignTop: Boolean = false) = GridLayout.LayoutParams(
    GridLayout.spec(GridLayout.UNDEFINED),
    GridLayout.spec(GridLayout.UNDEFINED, 1f)
).apply {
    width = 0
    height = ViewGroup.LayoutParams.WRAP_CONTENT
    setGravity(if (alignTop) Gravity.TOP or Gravity.FILL_HORIZONTAL else Gravity.FILL_HORIZONTAL)
    if (column == 0) marginEnd = dp(6) else marginStart = dp(6)
    bottomMargin = dp(12)
}
