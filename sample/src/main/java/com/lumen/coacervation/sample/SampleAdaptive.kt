package com.lumen.coacervation.sample

import android.content.res.Configuration
import android.graphics.Typeface
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.motion.MicroMotion
import com.lumen.coacervation.engine.widget.CoverableRippleDrawable

/**
 * 第五页"自适应"（适配标准 §15.3）：页面按**当前窗口**的实际宽度排布，而不是按设备类型。
 *
 * - 窗口信息：宽高、方向、分屏、字体缩放、宽度档位（紧凑 / 中等 / 展开）。
 * - 自适应网格：列数 = 可用宽度 / 最小格宽，手机竖屏 2 列，横屏与平板更多。
 * - 列表-详情：宽度 ≥ 600dp 时并排双栏，点左侧直接换右侧；窄窗口里点条目形变出面板。
 *
 * 旋转、分屏、拖动自由窗口会重建 Activity；重建后停留在同一页、同一滚动位置（见 SampleActivity）。
 */
internal fun SampleActivity.buildAdaptivePage(content: LinearLayout, palette: LumenPalette) {
    val configuration = resources.configuration
    val widthDp = configuration.screenWidthDp
    val sizeClass = when {
        widthDp < 600 -> "紧凑（< 600dp）"
        widthDp < 840 -> "中等（600–840dp）"
        else -> "展开（≥ 840dp）"
    }
    content.addView(caption("旋转屏幕、进入分屏或拖动自由窗口，排布会按新的窗口宽度重新计算。"))

    // ---------------- 窗口信息 ----------------
    content.addView(settingsCard(palette, "当前窗口").apply {
        val lines = listOf(
            "宽 × 高" to "$widthDp × ${configuration.screenHeightDp} dp",
            "宽度档位" to sizeClass,
            "方向" to if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "横屏" else "竖屏",
            "最小宽度" to "${configuration.smallestScreenWidthDp} dp",
            "分屏 / 多窗口" to if (isInMultiWindowMode) "是" else "否",
            "字体缩放" to "${configuration.fontScale}×",
            "布局方向" to if (configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) "从右到左" else "从左到右"
        )
        lines.forEach { (key, value) -> addView(hint(palette, "$key：$value")) }
    }, cardParams())

    // ---------------- 自适应网格 ----------------
    val columns = adaptiveColumns(minCellDp = 140, min = 2, max = 6)
    content.addView(adaptiveLabel(palette, "自适应网格：$columns 列"))
    val grid = GridLayout(this).apply {
        columnCount = columns
        clipChildren = false
        clipToPadding = false
    }
    repeat(columns * 2) { index ->
        val title = TextView(this).apply {
            text = "格子 ${index + 1}"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.textPrimary)
        }
        grid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            minimumHeight = dp(if (index % 3 == 0) 128 else 96)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = lumen.cardBackground(palette.surface, 18f)
            foreground = CoverableRippleDrawable.rounded(palette, dp(18).toFloat())
            isClickable = true
            addView(title)
            setOnClickListener { showTilePanel(this, title.text.toString()) }
        }, galleryCell(index % columns, columns))
    }
    content.addView(grid, LinearLayout.LayoutParams(-1, -2))

    // ---------------- 列表-详情 ----------------
    val twoPane = widthDp >= 600
    content.addView(adaptiveLabel(palette, if (twoPane) "列表-详情：双栏" else "列表-详情：单栏（加宽窗口变双栏）"))
    val items = listOf(
        "玻璃材质" to "表面由引擎按角色绘制：柔光是静态磨砂加软件透镜，高级材质是 RuntimeShader 折射。",
        "长按弹性" to "按住约 160ms 后拖动：控件跟手、按压收缩、触点光晕流动，松手弹簧回弹。",
        "可打断动画" to "翻页、形变、手风琴都能在中途被新手势接住，从当前位置与速度续接。",
        "悬浮栏可读性" to "栏下方内容变化时自动补偿：滚动边缘溶解、加厚色罩、前景色加强。",
        "内存与回退" to "高级材质首帧成功才确认健康；失败或内存告急时单向降级，不会反复崩溃。"
    )
    if (twoPane) {
        val detailTitle = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.textPrimary)
        }
        val detailBody = TextView(this).apply {
            textSize = 14f
            setTextColor(palette.textSecondary)
            setPadding(0, dp(10), 0, 0)
        }
        detailTitle.text = items[0].first
        detailBody.text = items[0].second
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                items.forEach { (title, body) ->
                    addView(adaptiveRow(palette, title) {
                        MicroMotion.swapText(detailTitle, title)
                        MicroMotion.swapText(detailBody, body)
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
                background = lumen.surface(palette.surface, 22f, SurfaceRole.SELECTED_ITEM)
                addView(detailTitle)
                addView(detailBody)
            }, LinearLayout.LayoutParams(0, -2, 1.4f))
        }, cardParams())
    } else {
        items.forEach { (title, _) ->
            content.addView(adaptiveRow(palette, title) { row -> showTilePanel(row, title) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }
    content.addView(caption("所有卡片都是 WRAP_CONTENT：调大系统字体时卡片跟着长高，文字不会被截断。"))
}

private fun SampleActivity.adaptiveLabel(palette: LumenPalette, text: String) = TextView(this).apply {
    this.text = text
    textSize = 15f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(palette.textPrimary)
    setPadding(dp(6), dp(12), dp(6), dp(8))
}

private fun SampleActivity.adaptiveRow(palette: LumenPalette, title: String, onClick: (LinearLayout) -> Unit) =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = lumen.cardBackground(palette.surface)
        foreground = CoverableRippleDrawable.rounded(palette, dp(15).toFloat())
        isClickable = true
        addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(palette.textPrimary)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = "›"
            textSize = 20f
            setTextColor(palette.textSecondary)
        })
        setOnClickListener { onClick(this) }
    }
