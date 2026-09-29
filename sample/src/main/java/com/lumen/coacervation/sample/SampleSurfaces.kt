package com.lumen.coacervation.sample

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.widget.CoverableRippleDrawable

/**
 * 表面角色一览（适配标准 §5）：宿主只声明"这块表面是什么"，引擎决定怎样画。
 * 每个色块都是可点的卡片：点开从色块自己的圆角形变出面板，长按可拖动。
 */
internal fun SampleActivity.buildSurfaceGallery(content: LinearLayout, palette: LumenPalette) {
    content.addView(galleryLabel(palette, "表面角色"))
    val roles = listOf(
        Triple(SurfaceRole.CARD, "卡片", "列表与内容卡片"),
        Triple(SurfaceRole.FLOATING, "悬浮", "底栏、悬浮按钮；透出下方内容"),
        Triple(SurfaceRole.TOP_BAR, "顶栏", "贴顶的栏；随滚动溶解"),
        Triple(SurfaceRole.MODAL, "弹窗", "面板与气泡的卡片"),
        Triple(SurfaceRole.SELECTED_ITEM, "选中项", "输入框、选中态"),
        Triple(SurfaceRole.CHIP, "标签", "小尺寸的可选项"),
        Triple(SurfaceRole.FILLED_BUTTON, "实心按钮", "主要操作"),
        Triple(SurfaceRole.TEXT_BUTTON, "文字按钮", "次要操作"),
        Triple(SurfaceRole.MOTION_SURFACE, "形变表面", "入口与全屏形变共享")
    )
    val columns = adaptiveColumns(minCellDp = 150, min = 2, max = 4)
    val grid = GridLayout(this).apply {
        columnCount = columns
        clipChildren = false
        clipToPadding = false
    }
    roles.forEachIndexed { index, (role, title, summary) ->
        val radius = if (role == SurfaceRole.CHIP) 12f else 18f
        val titleView = TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.textPrimary)
        }
        val swatch = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            minimumHeight = dp(96)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = lumen.surface(palette.surface, radius, role)
            foreground = CoverableRippleDrawable.rounded(palette, dp(radius.toInt()).toFloat())
            isClickable = true
            contentDescription = "$title 表面"
            addView(titleView)
            addView(TextView(context).apply {
                text = "${role.name}\n$summary"
                textSize = 11f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener { showTilePanel(this, titleView.text.toString()) }
        }
        grid.addView(swatch, galleryCell(index % columns, columns))
    }
    content.addView(grid, LinearLayout.LayoutParams(-1, -2))

    // §5.3 控件：状态标签、可选中条目、悬浮栏里的叠层按钮、控件描边。
    content.addView(galleryLabel(palette, "控件样式"))
    content.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = lumen.cardBackground(palette.surface)
        clipChildren = false
        clipToPadding = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("已同步" to palette.primary, "处理中" to palette.secondary, "提醒" to palette.tertiary)
                .forEachIndexed { index, (text, accent) ->
                    addView(TextView(context).apply {
                        this.text = text
                        textSize = 12f
                        setTextColor(accent)
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        lumen.styleStatusChip(this, accent)
                    }, LinearLayout.LayoutParams(-2, -2).apply { if (index < 2) marginEnd = dp(8) })
                }
        })
        addView(TextView(context).apply {
            text = "状态标签：保留可读的实色字形，不做逐个光学采样。"
            textSize = 12f
            setTextColor(palette.textSecondary)
            setPadding(0, dp(8), 0, dp(4))
        })
        // 可选中条目：点按在 CARD / SELECTED_ITEM 之间切换，描边随之加深。
        repeat(3) { index ->
            var selected = index == 0
            addView(TextView(context).apply {
                textSize = 14f
                setTextColor(palette.textPrimary)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                fun render() {
                    text = if (selected) "选项 ${index + 1}  ✓" else "选项 ${index + 1}"
                    lumen.styleSelectionControl(this, 16f, selected)
                    contentDescription = if (selected) "选项 ${index + 1}，已选中" else "选项 ${index + 1}"
                }
                render()
                isClickable = true
                setOnClickListener {
                    selected = !selected
                    render()
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        // 悬浮栏里的轻量叠层（未选中 / 选中）。
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = lumen.floatingBackground(palette.surface, 28f)
            listOf(false, true, false).forEachIndexed { index, selected ->
                addView(TextView(context).apply {
                    text = if (selected) "选中" else "叠层"
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setTextColor(if (selected) palette.primary else palette.textPrimary)
                    background = lumen.chromeOverlayBackground(palette.surface, 20f, selected)
                    foreground = CoverableRippleDrawable.rounded(palette, dp(20).toFloat())
                    isClickable = true
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (index < 2) marginEnd = dp(8) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addView(TextView(context).apply {
            text = "悬浮栏里的按钮用 chromeOverlayBackground：继承胶囊的光学图，不另采背景。"
            textSize = 12f
            setTextColor(palette.textSecondary)
            setPadding(0, dp(8), 0, 0)
        })
    }, cardParams())
}

private fun SampleActivity.galleryLabel(palette: LumenPalette, text: String) = TextView(this).apply {
    this.text = text
    textSize = 15f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(palette.textPrimary)
    setPadding(dp(6), dp(12), dp(6), dp(8))
}

/** 等宽网格格子：列间距 12dp，末列不留右边距。 */
internal fun SampleActivity.galleryCell(column: Int, columns: Int) = GridLayout.LayoutParams(
    GridLayout.spec(GridLayout.UNDEFINED),
    GridLayout.spec(GridLayout.UNDEFINED, 1f)
).apply {
    width = 0
    height = ViewGroup.LayoutParams.WRAP_CONTENT
    setGravity(Gravity.FILL_HORIZONTAL)
    marginStart = if (column == 0) 0 else dp(6)
    marginEnd = if (column == columns - 1) 0 else dp(6)
    bottomMargin = dp(12)
}

/**
 * 按当前窗口宽度决定列数（分屏、自由窗口、横屏、平板都按实际可用宽度，§15.3）。
 * 窗口尺寸变化会重建 Activity，重建时重新计算。
 */
internal fun SampleActivity.adaptiveColumns(minCellDp: Int, min: Int, max: Int): Int {
    val available = resources.configuration.screenWidthDp - 32
    return (available / minCellDp).coerceIn(min, max)
}
