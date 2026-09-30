package com.lumen.coacervation.sample

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.background.LiquidBackgroundImportResult
import com.lumen.coacervation.engine.background.LiquidBackgroundMode
import com.lumen.coacervation.engine.background.LiquidBackgroundStore
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SkinId
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.widget.CoverableRippleDrawable
import com.lumen.coacervation.engine.widget.LumenSlidingSelection
import java.util.concurrent.Executors

/**
 * 宿主侧的外观设置：深浅色、强调色、弹窗背景模糊。引擎不持久化这些；真实应用接进自己的设置系统。
 * 除深浅色由 AppCompat 自动重建外，其余改动都要重建 Activity 生效（配色与会话在创建时读取，§2.2）。
 */
internal class SampleSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("sample_settings", Context.MODE_PRIVATE)

    var nightMode: Int
        get() = preferences.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            .takeIf { it in NIGHT_MODES } ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        set(value) { preferences.edit().putInt(KEY_NIGHT_MODE, value).apply() }

    var accent: SampleAccent
        get() = SampleAccent.entries.firstOrNull { it.name == preferences.getString(KEY_ACCENT, null) } ?: SampleAccent.BLUE
        set(value) { preferences.edit().putString(KEY_ACCENT, value.name).apply() }

    var modalBackdropBlur: Boolean
        get() = preferences.getBoolean(KEY_MODAL_BLUR, false)
        set(value) { preferences.edit().putBoolean(KEY_MODAL_BLUR, value).apply() }

    companion object {
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_ACCENT = "accent"
        private const val KEY_MODAL_BLUR = "modal_backdrop_blur"
        val NIGHT_MODES = listOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES
        )
    }
}

/** 强调色预设：浅色与深色各一档，表面与文字仍用引擎推荐的中性色（`LumenPalette.modern`）。 */
internal enum class SampleAccent(val label: String, val light: Long, val dark: Long, val onLight: Long, val onDark: Long) {
    BLUE("蓝", 0xFF2A5EA8, 0xFF9ECAFF, 0xFFFFFFFF, 0xFF003258),
    VIOLET("紫", 0xFF6750A4, 0xFFD0BCFF, 0xFFFFFFFF, 0xFF381E72),
    GREEN("绿", 0xFF2E6B3F, 0xFF95D5A2, 0xFFFFFFFF, 0xFF003919),
    ORANGE("橙", 0xFF9A4600, 0xFFFFB787, 0xFFFFFFFF, 0xFF522300),
    NEUTRAL("中性", 0xFF5D5E66, 0xFFC6C6CC, 0xFFFFFFFF, 0xFF2F3036);

    fun palette(dark: Boolean): LumenPalette = LumenPalette.modern(
        primary = (if (dark) this.dark else light).toInt(),
        onPrimary = (if (dark) onDark else onLight).toInt(),
        secondary = if (dark) 0xFFBBC7DB.toInt() else 0xFF535F70.toInt(),
        tertiary = if (dark) 0xFFD6BEE4.toInt() else 0xFF6B5778.toInt(),
        dark = dark
    )
}

// ---------------- 页面 6：设置（全部可调参数） ----------------

internal fun SampleActivity.buildSettingsPage(content: LinearLayout, palette: LumenPalette) {
    content.addView(caption("所有可调参数集中在这一页。标注「重建」的改动会重建页面，并停留在这一页。"))
    content.addView(materialCard(palette), cardParams())
    content.addView(appearanceCard(palette), cardParams())
    content.addView(backgroundCard(palette), cardParams())
    content.addView(tuningCard(palette), cardParams())
    content.addView(diagnosticsCard(palette), cardParams())
}

/** §3.2 材质与实时取样：写入成功后重建；写入失败把开关恢复原状。 */
private fun SampleActivity.materialCard(palette: LumenPalette) = settingsCard(palette, "材质（重建）").apply {
    val requested = LumenEngine.requestedMaterial(this@materialCard)
    addView(choiceRow(palette, listOf("柔光" to SkinId.MATERIAL_YOU, "高级材质" to SkinId.LIQUID), requested) { skin ->
        if (skin == requested) return@choiceRow
        if (LumenEngine.selectMaterial(this@materialCard, skin)) recreate()
        else Toast.makeText(this@materialCard, "保存失败", Toast.LENGTH_SHORT).show()
    })
    addView(switchRow(palette, "全屏实时取样", "高级材质下用窗口截图做玻璃采样；更通透，耗电更高",
        LumenEngine.isRealtimeCaptureEnabled(this@materialCard)) { button, checked ->
        if (LumenEngine.setRealtimeCaptureEnabled(this@materialCard, checked)) recreate() else button.isChecked = !checked
    })
    addView(hint(palette, "高级材质需要 API 31+（折射 API 33+）；设备不支持或渲染失败时自动回到柔光，下方「诊断」给出原因。"))
}

/** 深浅色、强调色（§2.2 配色由宿主提供）与弹窗背景模糊（LumenModalStyle.backdropBlur）。 */
private fun SampleActivity.appearanceCard(palette: LumenPalette) = settingsCard(palette, "外观").apply {
    addView(label(palette, "深浅色"))
    val modes = listOf("跟随系统" to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        "浅色" to AppCompatDelegate.MODE_NIGHT_NO, "深色" to AppCompatDelegate.MODE_NIGHT_YES)
    addView(choiceRow(palette, modes, settings.nightMode) { mode ->
        if (mode == settings.nightMode) return@choiceRow
        settings.nightMode = mode
        // AppCompat 在深浅色确实变化时自动重建；配色随之按新的 uiMode 重新解析。
        AppCompatDelegate.setDefaultNightMode(mode)
    })
    addView(label(palette, "强调色（重建）"))
    addView(choiceRow(palette, SampleAccent.entries.map { it.label to it }, settings.accent) { accent ->
        if (accent == settings.accent) return@choiceRow
        settings.accent = accent
        recreate()
    })
    addView(switchRow(palette, "弹窗背景模糊（重建）",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "柔光材质下弹窗背后做跨窗口模糊；代价是动画掉帧率上升"
        else "需要 API 31+，本机不生效",
        settings.modalBackdropBlur) { _, checked ->
        settings.modalBackdropBlur = checked
        recreate()
    })
}

/** 自定义背景（LiquidBackgroundStore）：导入在后台线程，完成后回主线程重建。 */
private fun SampleActivity.backgroundCard(palette: LumenPalette) = settingsCard(palette, "自定义背景（重建）").apply {
    val result = LiquidBackgroundStore.read(this@backgroundCard)
    val custom = result.config.mode == LiquidBackgroundMode.CUSTOM && result.assetPresent
    addView(hint(palette, if (custom) "当前：自定义图片${result.config.displayName?.let { "（$it）" } ?: ""}" else "当前：自动生成的环境底图"))
    addView(hint(palette, "只在高级材质下生效：玻璃背后显示这张图。图片会被缩放并复制进应用私有目录。"))
    addView(actionRow(palette, "选择图片") { pickBackgroundImage() })
    if (custom) addView(actionRow(palette, "恢复自动底图") { restoreAutomaticBackground() })
}

/** 只读诊断（§3.2）：请求/生效材质、后端与降级原因。 */
private fun SampleActivity.diagnosticsCard(palette: LumenPalette) = settingsCard(palette, "诊断").apply {
    val diagnostics = lumen.diagnostics()
    val metrics = resources.displayMetrics
    val lines = listOf(
        "引擎版本" to LumenEngine.VERSION,
        "契约版本" to LumenEngine.CONTRACT_VERSION.toString(),
        "系统" to "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        "设备" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "屏幕" to "${(metrics.widthPixels / metrics.density).toInt()} × ${(metrics.heightPixels / metrics.density).toInt()} dp，密度 ${metrics.density}",
        "请求材质" to (diagnostics?.requestedSkin?.let(::skinLabel) ?: "未准备"),
        "生效材质" to (diagnostics?.effectiveSkin?.let(::skinLabel) ?: "未准备"),
        "高级材质后端" to (diagnostics?.liquidBackendName ?: "—"),
        "回退原因" to (diagnostics?.fallbackReason ?: "—"),
        "后端降级原因" to (diagnostics?.liquidBackendDegradeReason ?: "—")
    )
    lines.forEach { (key, value) -> addView(hint(palette, "$key：$value")) }
}

private fun skinLabel(skin: SkinId) = when (skin) {
    SkinId.MATERIAL_YOU -> "柔光"
    SkinId.LIQUID -> "高级材质"
}

internal fun SampleActivity.pickBackgroundImage() {
    // 部分精简系统没有文档选择器：launch 抛 ActivityNotFoundException 时提示而不是崩溃。
    runCatching { backgroundPicker.launch(arrayOf("image/*")) }
        .onFailure { Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_SHORT).show() }
}

/** 导入与恢复都可能读写磁盘：放后台线程，结果回主线程时先确认页面还活着。 */
private val backgroundWorker = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "Sample-Background").apply { isDaemon = true }
}

internal fun SampleActivity.importBackgroundImage(uri: Uri) {
    val appContext = applicationContext
    backgroundWorker.execute {
        val result = runCatching { LiquidBackgroundStore.importFromUri(appContext, uri) }.getOrNull()
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val message = when (result) {
                is LiquidBackgroundImportResult.Success -> null
                is LiquidBackgroundImportResult.Failure -> "导入失败：${result.reason}"
                null -> "导入失败"
            }
            if (message == null) recreate() else Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}

private fun SampleActivity.restoreAutomaticBackground() {
    val appContext = applicationContext
    backgroundWorker.execute {
        val restored = runCatching { LiquidBackgroundStore.restoreAutomatic(appContext) }.getOrDefault(false)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (restored) recreate() else Toast.makeText(this, "恢复失败", Toast.LENGTH_SHORT).show()
        }
    }
}

// ---------------- 设置页的小部件 ----------------

internal fun SampleActivity.settingsCard(palette: LumenPalette, title: String) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(16), dp(18), dp(16))
    background = lumen.cardBackground(palette.surface)
    addView(TextView(context).apply {
        text = title
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(palette.textPrimary)
    })
}

private fun SampleActivity.label(palette: LumenPalette, text: String) = TextView(this).apply {
    this.text = text
    textSize = 13f
    setTextColor(palette.textSecondary)
    setPadding(0, dp(12), 0, dp(6))
}

internal fun SampleActivity.hint(palette: LumenPalette, text: String) = TextView(this).apply {
    this.text = text
    textSize = 12f
    setTextColor(palette.textSecondary)
    setPadding(0, dp(6), 0, 0)
}

/**
 * 一排互斥选项，做成分段控件：轨道是 CHIP 表面，选中框（SELECTED_ITEM 表面）在选项下面连贯滑动，
 * 标题颜色按选中框的覆盖比例渐变（§13.11 `LumenSlidingSelection`）。
 *
 * 这几项设置都要重建页面才生效：等选中框滑到位再应用，连点只保留最后一次。
 */
internal fun <T> SampleActivity.choiceRow(
    palette: LumenPalette,
    options: List<Pair<String, T>>,
    selected: T,
    onChoose: (T) -> Unit
): LumenSlidingSelection {
    val choice = LumenSlidingSelection(
        context = this,
        indicatorBackground = lumen.surface(palette.surface, 12f, SurfaceRole.SELECTED_ITEM),
        orientation = LinearLayout.HORIZONTAL,
        notifyPositionChanged = { lumen.notifyPositionChanged() }
    )
    choice.background = lumen.surface(palette.surface, 16f, SurfaceRole.CHIP)
    choice.setPadding(dp(4), dp(4), dp(4), dp(4))
    val titles = options.map { (text, _) ->
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 13f
            maxLines = 1
            setTextColor(palette.textPrimary)
            setPadding(dp(4), dp(10), dp(4), dp(10))
            foreground = CoverableRippleDrawable.rounded(palette, dp(12).toFloat())
        }
    }
    titles.forEach { choice.addOption(it) }
    choice.setOnHighlightListener { index, weight ->
        titles[index].setTextColor(ColorUtils.blendARGB(palette.textPrimary, palette.primary, weight))
    }
    choice.select(options.indexOfFirst { it.second == selected }.coerceAtLeast(0), animate = false)
    var pending: Runnable? = null
    choice.onSelect = { index ->
        pending?.let(choice::removeCallbacks)
        val apply = Runnable { onChoose(options[index].second) }
        pending = apply
        choice.postDelayed(apply, CHOICE_APPLY_DELAY_MS)
    }
    choice.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    return choice
}

/** 选中框滑动 260ms，再留一帧余量后应用会重建页面的设置。 */
private const val CHOICE_APPLY_DELAY_MS = 280L

private fun SampleActivity.switchRow(
    palette: LumenPalette,
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (SwitchCompat, Boolean) -> Unit
) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, dp(12), 0, 0)
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(palette.textPrimary)
        })
        addView(hint(palette, summary))
    }, LinearLayout.LayoutParams(0, -2, 1f))
    addView(SwitchCompat(context).apply {
        isChecked = checked
        contentDescription = title
        setOnCheckedChangeListener { button, value -> onChange(button as SwitchCompat, value) }
    })
}

private fun SampleActivity.actionRow(palette: LumenPalette, text: String, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    gravity = Gravity.CENTER
    setTextColor(palette.textSecondary)
    setPadding(dp(16), dp(12), dp(16), dp(12))
    lumen.styleActionButton(this, filled = false)
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
}
