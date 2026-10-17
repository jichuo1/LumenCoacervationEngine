package com.lumen.coacervation.sample

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.lumen.coacervation.engine.host.LumenControlShadowOptions
import com.lumen.coacervation.engine.interaction.ElasticRubberCurve
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.morph.LumenMorphOptions
import com.lumen.coacervation.engine.motion.LumenSpringSpec
import kotlin.math.PI
import kotlin.math.sqrt
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 视效、控件阴影与长按动效调参的宿主侧存储（适配标准 §2.5、§5.6）。引擎不持久化调参；
 * 真实应用把它接进自己的设置。
 *
 * [current] 每次现读：长按拖动的形变与光晕在每次按下时取值，所以滑块改动即时生效；
 * 边缘高光、控件阴影、回弹弹簧与橡皮筋曲线在会话/交互创建时读取，改动后需要重建 Activity。
 */
internal class SampleTuningStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("sample_effect_tuning", Context.MODE_PRIVATE)

    var current: LumenEffectTuning = read()
        private set

    /** 控件软阴影配置（§5.6）：委托首次读取后缓存。 */
    var controlShadow: LumenControlShadowOptions = readShadow()
        private set

    /** 液态形变磁贴配置：Demo 动效页的专用测试控件与设置卡共用。 */
    var morph: LumenMorphOptions = readMorph()
        private set

    /** 自定义弹簧开关：关闭时 [springSpec] 恒为引擎原样，与持久化的周期/弹跳无关。 */
    var springCustomized: Boolean = preferences.getBoolean(KEY_SPRING_CUSTOM, false)
        private set
    var springResponseSeconds: Float =
        preferences.getFloat(KEY_SPRING_RESPONSE, DEFAULT_SPRING_RESPONSE).coerceIn(0.08f, 1.5f)
        private set
    var springBounce: Float = preferences.getFloat(KEY_SPRING_BOUNCE, DEFAULT_SPRING_BOUNCE).coerceIn(0f, 0.9f)
        private set

    /** 长按回弹弹簧规格：自定义开时按「周期 + 弹跳」换算，关时为 [LumenSpringSpec.DEFAULT]。 */
    val springSpec: LumenSpringSpec
        get() = if (springCustomized)
            LumenSpringSpec.ofResponseBounce(springResponseSeconds, springBounce)
        else LumenSpringSpec.DEFAULT

    /** 跟手位移的橡皮筋曲线；默认 [ElasticRubberCurve.TANH]（引擎原样）。 */
    var rubberCurve: ElasticRubberCurve = readCurve()
        private set

    private fun read() = LumenEffectTuning.clamped(
        edgeHighlightWidth = preferences.getFloat(KEY_EDGE_WIDTH, 1f),
        edgeHighlightIntensity = preferences.getFloat(KEY_EDGE_INTENSITY, 1f),
        dragGlowIntensity = preferences.getFloat(KEY_GLOW_INTENSITY, 1f),
        dragGlowRadius = preferences.getFloat(KEY_GLOW_RADIUS, 1f),
        dragDeformation = preferences.getFloat(KEY_DEFORMATION, 1f)
    )

    fun update(value: LumenEffectTuning) {
        current = value
        preferences.edit()
            .putFloat(KEY_EDGE_WIDTH, value.edgeHighlightWidth)
            .putFloat(KEY_EDGE_INTENSITY, value.edgeHighlightIntensity)
            .putFloat(KEY_GLOW_INTENSITY, value.dragGlowIntensity)
            .putFloat(KEY_GLOW_RADIUS, value.dragGlowRadius)
            .putFloat(KEY_DEFORMATION, value.dragDeformation)
            .apply()
    }

    fun updateShadow(value: LumenControlShadowOptions) {
        controlShadow = value
        preferences.edit()
            .putBoolean(KEY_SHADOW_ENABLED, value.enabled)
            .putFloat(KEY_SHADOW_BLUR, value.blurRadiusDp)
            .putFloat(KEY_SHADOW_OFFSET_Y, value.offsetYDp)
            .putFloat(KEY_SHADOW_SPREAD, value.spreadDp)
            .putFloat(KEY_SHADOW_OPACITY, value.opacity)
            .putFloat(KEY_SHADOW_CONTACT, value.contactOpacity)
            .putBoolean(KEY_SHADOW_ADAPT, value.adaptToTheme)
            .putBoolean(KEY_SHADOW_SOFTEN, value.softenOverlap)
            .putFloat(KEY_SHADOW_ATTENUATION, value.overlapAttenuation)
            .apply()
    }

    fun updateMorph(value: LumenMorphOptions) {
        morph = value
        preferences.edit()
            .putBoolean(KEY_MORPH_ENABLED, value.enabled)
            .putFloat(KEY_MORPH_FRACTION, value.collapsedFraction)
            .putFloat(KEY_MORPH_COLLAPSED_RADIUS, value.collapsedRadiusDp)
            .putFloat(KEY_MORPH_EXPANDED_RADIUS, value.expandedRadiusDp)
            .putFloat(KEY_MORPH_SMOOTHING, value.cornerSmoothing)
            .putFloat(KEY_MORPH_CONTENT_SCALE, value.contentScale)
            .putBoolean(KEY_MORPH_FADE, value.fadeLabel)
            .putBoolean(KEY_MORPH_EDGE, value.edgeHighlight)
            .putBoolean(KEY_MORPH_CONTACT, value.contactLine)
            .putFloat(KEY_MORPH_CONTACT_ALPHA, value.contactAlpha)
            .putFloat(KEY_MORPH_WIDTH_RESPONSE, morphResponse(value.widthSpring))
            .putFloat(KEY_MORPH_WIDTH_BOUNCE, morphBounce(value.widthSpring))
            .putFloat(KEY_MORPH_CORNER_RESPONSE, morphResponse(value.cornerSpring))
            .putFloat(KEY_MORPH_CORNER_BOUNCE, morphBounce(value.cornerSpring))
            .putFloat(KEY_MORPH_CONTENT_RESPONSE, morphResponse(value.contentSpring))
            .putFloat(KEY_MORPH_CONTENT_BOUNCE, morphBounce(value.contentSpring))
            .apply()
    }

    fun updateSpring(customized: Boolean, responseSeconds: Float, bounce: Float) {
        springCustomized = customized
        springResponseSeconds = responseSeconds.coerceIn(0.08f, 1.5f)
        springBounce = bounce.coerceIn(0f, 0.9f)
        preferences.edit()
            .putBoolean(KEY_SPRING_CUSTOM, customized)
            .putFloat(KEY_SPRING_RESPONSE, springResponseSeconds)
            .putFloat(KEY_SPRING_BOUNCE, springBounce)
            .apply()
    }

    fun updateCurve(curve: ElasticRubberCurve) {
        rubberCurve = curve
        preferences.edit().putString(KEY_RUBBER_CURVE, curve.name).apply()
    }

    private fun readShadow() = LumenControlShadowOptions(
        enabled = preferences.getBoolean(KEY_SHADOW_ENABLED, false),
        blurRadiusDp = preferences.getFloat(KEY_SHADOW_BLUR, 10f).coerceIn(0f, 32f),
        offsetYDp = preferences.getFloat(KEY_SHADOW_OFFSET_Y, 3f).coerceIn(-8f, 16f),
        spreadDp = preferences.getFloat(KEY_SHADOW_SPREAD, 0f).coerceIn(-4f, 8f),
        opacity = preferences.getFloat(KEY_SHADOW_OPACITY, 0.14f).coerceIn(0f, 0.6f),
        contactOpacity = preferences.getFloat(KEY_SHADOW_CONTACT, 0.06f).coerceIn(0f, 0.4f),
        adaptToTheme = preferences.getBoolean(KEY_SHADOW_ADAPT, true),
        softenOverlap = preferences.getBoolean(KEY_SHADOW_SOFTEN, false),
        overlapAttenuation = preferences.getFloat(KEY_SHADOW_ATTENUATION, 0.55f).coerceIn(0.1f, 1f)
    )

    private fun readMorph() = LumenMorphOptions(
        enabled = preferences.getBoolean(KEY_MORPH_ENABLED, true),
        collapsedFraction = preferences.getFloat(KEY_MORPH_FRACTION, .5f).coerceIn(.2f, 1f),
        collapsedRadiusDp = preferences.getFloat(KEY_MORPH_COLLAPSED_RADIUS, 80f).coerceIn(0f, 200f),
        expandedRadiusDp = preferences.getFloat(KEY_MORPH_EXPANDED_RADIUS, 28f).coerceIn(0f, 200f),
        cornerSmoothing = preferences.getFloat(KEY_MORPH_SMOOTHING, .25f).coerceIn(0f, .6f),
        contentScale = preferences.getFloat(KEY_MORPH_CONTENT_SCALE, 1f).coerceIn(.5f, 2f),
        fadeLabel = preferences.getBoolean(KEY_MORPH_FADE, true),
        edgeHighlight = preferences.getBoolean(KEY_MORPH_EDGE, true),
        contactLine = preferences.getBoolean(KEY_MORPH_CONTACT, true),
        contactAlpha = preferences.getFloat(KEY_MORPH_CONTACT_ALPHA, .08f).coerceIn(0f, .3f),
        widthSpring = LumenSpringSpec.ofResponseBounce(
            preferences.getFloat(KEY_MORPH_WIDTH_RESPONSE, .36f).coerceIn(.08f, 1.5f),
            preferences.getFloat(KEY_MORPH_WIDTH_BOUNCE, .17f).coerceIn(0f, .9f)),
        cornerSpring = LumenSpringSpec.ofResponseBounce(
            preferences.getFloat(KEY_MORPH_CORNER_RESPONSE, .36f).coerceIn(.08f, 1.5f),
            preferences.getFloat(KEY_MORPH_CORNER_BOUNCE, .16f).coerceIn(0f, .9f)),
        contentSpring = LumenSpringSpec.ofResponseBounce(
            preferences.getFloat(KEY_MORPH_CONTENT_RESPONSE, .2f).coerceIn(.08f, 1.5f),
            preferences.getFloat(KEY_MORPH_CONTENT_BOUNCE, 0f).coerceIn(0f, .9f))
    )

    private fun morphResponse(spec: LumenSpringSpec): Float =
        (2.0 * PI / sqrt(spec.stiffness.toDouble())).toFloat()

    private fun morphBounce(spec: LumenSpringSpec): Float =
        (1f - spec.dampingRatio).coerceIn(0f, .9f)

    private fun readCurve() =
        ElasticRubberCurve.entries.firstOrNull { it.name == preferences.getString(KEY_RUBBER_CURVE, null) }
            ?: ElasticRubberCurve.TANH

    private companion object {
        const val KEY_EDGE_WIDTH = "edge_width"
        const val KEY_EDGE_INTENSITY = "edge_intensity"
        const val KEY_GLOW_INTENSITY = "glow_intensity"
        const val KEY_GLOW_RADIUS = "glow_radius"
        const val KEY_DEFORMATION = "drag_deformation"
        const val KEY_SHADOW_ENABLED = "control_shadow_enabled"
        const val KEY_SHADOW_BLUR = "control_shadow_blur"
        const val KEY_SHADOW_OFFSET_Y = "control_shadow_offset_y"
        const val KEY_SHADOW_SPREAD = "control_shadow_spread"
        const val KEY_SHADOW_OPACITY = "control_shadow_opacity"
        const val KEY_SHADOW_CONTACT = "control_shadow_contact"
        const val KEY_SHADOW_ADAPT = "control_shadow_adapt"
        const val KEY_SHADOW_SOFTEN = "control_shadow_soften"
        const val KEY_SHADOW_ATTENUATION = "control_shadow_attenuation"
        const val KEY_MORPH_ENABLED = "morph_enabled"
        const val KEY_MORPH_FRACTION = "morph_fraction"
        const val KEY_MORPH_COLLAPSED_RADIUS = "morph_collapsed_radius"
        const val KEY_MORPH_EXPANDED_RADIUS = "morph_expanded_radius"
        const val KEY_MORPH_SMOOTHING = "morph_smoothing"
        const val KEY_MORPH_CONTENT_SCALE = "morph_content_scale"
        const val KEY_MORPH_FADE = "morph_fade"
        const val KEY_MORPH_EDGE = "morph_edge"
        const val KEY_MORPH_CONTACT = "morph_contact"
        const val KEY_MORPH_CONTACT_ALPHA = "morph_contact_alpha"
        const val KEY_MORPH_WIDTH_RESPONSE = "morph_width_response"
        const val KEY_MORPH_WIDTH_BOUNCE = "morph_width_bounce"
        const val KEY_MORPH_CORNER_RESPONSE = "morph_corner_response"
        const val KEY_MORPH_CORNER_BOUNCE = "morph_corner_bounce"
        const val KEY_MORPH_CONTENT_RESPONSE = "morph_content_response"
        const val KEY_MORPH_CONTENT_BOUNCE = "morph_content_bounce"
        const val KEY_SPRING_CUSTOM = "spring_custom"
        const val KEY_SPRING_RESPONSE = "spring_response"
        const val KEY_SPRING_BOUNCE = "spring_bounce"
        const val KEY_RUBBER_CURVE = "rubber_curve"
        /** 引擎默认弹簧（刚度 310、阻尼比 0.7）换算回设计口语的周期与弹跳。 */
        const val DEFAULT_SPRING_RESPONSE = 0.36f
        const val DEFAULT_SPRING_BOUNCE = 0.3f
    }
}

/**
 * 视效调参卡片：表面立体边缘开关、边缘高光两项与长按三项滑块，以及「恢复默认」。
 * 边缘高光在会话创建时读取，改动后重建 Activity；长按形变与光晕三项拖动时即时生效。
 * 滑块（`AbsSeekBar`）本身不参与长按弹性（§12.1）。
 */
internal fun SampleActivity.tuningCard(palette: LumenPalette) = settingsCard(palette, "视效调参").apply {
    val current = tuning.current
    val edgeOn = current.edgeHighlightIntensity > 0f
    addView(switchRow(palette, "表面立体边缘",
        "贴边受光与底部立体边；默认关闭，避免拟物化边缘干扰内容", edgeOn) { _, checked ->
        tuning.update(tuning.current.copy(edgeHighlightIntensity = if (checked) 1f else 0f))
        recreate()
    })
    if (edgeOn) {
        addView(tuningSlider(palette, "边缘高光厚度", LumenEffectTuning.MIN_EDGE_WIDTH, LumenEffectTuning.MAX_EDGE_WIDTH,
            current.edgeHighlightWidth, recreateOnRelease = true) { tuning.current.copy(edgeHighlightWidth = it) })
        addView(tuningSlider(palette, "边缘高光亮度", 0f, LumenEffectTuning.MAX_EDGE_INTENSITY,
            current.edgeHighlightIntensity, recreateOnRelease = true) { tuning.current.copy(edgeHighlightIntensity = it) })
    } else {
        addView(hint(palette, "边缘高光已关闭；开启后可调厚度与亮度。长按形变与光晕始终即时生效。"))
    }
    addView(tuningSlider(palette, "长按形变程度", 0f, LumenEffectTuning.MAX_DRAG_DEFORMATION,
        tuning.current.dragDeformation, recreateOnRelease = false) { tuning.current.copy(dragDeformation = it) })
    addView(tuningSlider(palette, "长按光晕强度", 0f, LumenEffectTuning.MAX_DRAG_GLOW_INTENSITY,
        tuning.current.dragGlowIntensity, recreateOnRelease = false) { tuning.current.copy(dragGlowIntensity = it) })
    addView(tuningSlider(palette, "长按光晕半径", LumenEffectTuning.MIN_DRAG_GLOW_RADIUS,
        LumenEffectTuning.MAX_DRAG_GLOW_RADIUS, tuning.current.dragGlowRadius, recreateOnRelease = false) {
        tuning.current.copy(dragGlowRadius = it)
    })
    addView(TextView(context).apply {
        text = "恢复默认"
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        lumen.styleActionButton(this, filled = false)
        isEnabled = tuning.current != LumenEffectTuning.DEFAULT
        setOnClickListener {
            tuning.update(LumenEffectTuning.DEFAULT)
            recreate()
        }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
}

private fun SampleActivity.tuningSlider(
    palette: LumenPalette,
    label: String,
    from: Float,
    to: Float,
    initial: Float,
    recreateOnRelease: Boolean,
    apply: (Float) -> LumenEffectTuning
) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(0, dp(12), 0, 0)
    val title = TextView(context).apply {
        textSize = 13f
        setTextColor(palette.textSecondary)
    }
    // 固定 0.05 一档：每个范围的端点与默认值 1 都落在整档上。用 progress / 20 而不是 0.05 × progress，
    // 默认档精确等于 1f（0.05f 不能精确表示），拖回原位就是引擎原样。
    val steps = ((to - from) * STEPS_PER_UNIT).roundToInt()
    fun valueOf(progress: Int) = (from + progress / STEPS_PER_UNIT).coerceIn(from, to)
    fun show(value: Float) {
        title.text = String.format(Locale.ROOT, "%s  ×%.2f", label, value)
    }
    show(initial)
    addView(title)
    addView(SeekBar(context).apply {
        max = steps
        progress = ((initial - from) * STEPS_PER_UNIT).roundToInt().coerceIn(0, steps)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = valueOf(progress)
                show(value)
                if (!recreateOnRelease) tuning.update(apply(value))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (!recreateOnRelease) return
                tuning.update(apply(valueOf(seekBar.progress)))
                // 边缘高光在会话创建时读取：与切换材质相同，重建 Activity 生效（§2.5）。
                recreate()
            }
        })
    }, LinearLayout.LayoutParams(-1, -2))
}

private const val STEPS_PER_UNIT = 20f

/**
 * 控件软阴影卡（§5.6）：开关、延展/强度/重叠策略与深色自适应。配置在委托首次读取后缓存，
 * 改动一律「松手重建」。滑块（`AbsSeekBar`）本身不参与长按弹性（§12.1）。
 */
internal fun SampleActivity.controlShadowCard(palette: LumenPalette) = settingsCard(palette, "控件阴影（重建）").apply {
    val shadow = tuning.controlShadow
    addView(switchRow(palette, "启用软阴影", "浅色表面叠在白底内容上也能读出分界；只在控件外侧宽幅衰减，边缘保持干净",
        shadow.enabled) { _, checked ->
        tuning.updateShadow(shadow.copy(enabled = checked))
        recreate()
    })
    if (!shadow.enabled) {
        addView(hint(palette, "开启后 surface() 一族背景外包一层软阴影：环境影给浮起感，贴边接触带保证纯白底上也有分界。"))
        return@apply
    }
    addView(releaseSlider(palette, "延展（模糊 dp）", 0f, 32f, 0.1f, shadow.blurRadiusDp) { value ->
        tuning.updateShadow(tuning.controlShadow.copy(blurRadiusDp = value))
        recreate()
    })
    addView(releaseSlider(palette, "向下偏移（dp）", -8f, 16f, 0.1f, shadow.offsetYDp) { value ->
        tuning.updateShadow(tuning.controlShadow.copy(offsetYDp = value))
        recreate()
    })
    addView(releaseSlider(palette, "轮廓扩张（dp）", -4f, 8f, 0.1f, shadow.spreadDp) { value ->
        tuning.updateShadow(tuning.controlShadow.copy(spreadDp = value))
        recreate()
    })
    addView(releaseSlider(palette, "投射强度", 0f, 0.6f, 0.005f, shadow.opacity) { value ->
        tuning.updateShadow(tuning.controlShadow.copy(opacity = value))
        recreate()
    })
    addView(releaseSlider(palette, "贴边接触强度", 0f, 0.4f, 0.005f, shadow.contactOpacity) { value ->
        tuning.updateShadow(tuning.controlShadow.copy(contactOpacity = value))
        recreate()
    })
    addView(switchRow(palette, "重叠处减弱阴影",
        "相邻控件落在外扩阴影内时降低本控件强度，避免交叉处叠加过黑；关闭则直接叠加", shadow.softenOverlap) { _, checked ->
        tuning.updateShadow(tuning.controlShadow.copy(softenOverlap = checked))
        recreate()
    })
    if (shadow.softenOverlap) {
        addView(releaseSlider(palette, "重叠保留比例", 0.1f, 1f, 0.01f, shadow.overlapAttenuation) { value ->
            tuning.updateShadow(tuning.controlShadow.copy(overlapAttenuation = value))
            recreate()
        })
    }
    addView(switchRow(palette, "深色主题自适应", "深色下自动加强环境影、减弱接触带", shadow.adaptToTheme) { _, checked ->
        tuning.updateShadow(tuning.controlShadow.copy(adaptToTheme = checked))
        recreate()
    })
    addView(hint(palette, "本 Demo 卡片间距已够阴影外扩；密集列表里相邻项会把它裁掉，通常正是想要的效果。同一控件不要再叠平台 elevation，避免双影。"))
}

/**
 * 液态形变卡：专用测试件在「动效」页；这里控制三条弹簧、几何与贴边显示。
 * 改动重建 Activity，便于和「表面立体边缘」等会话级参数一起生效。
 */
internal fun SampleActivity.morphCard(palette: LumenPalette) = settingsCard(palette, "液态形变（重建）").apply {
    val morph = tuning.morph
    addView(hint(palette, "专用测试件在「动效」页。宽度/圆角/内容三条弹簧独立；圆角与内容错峰时出现液滴挤出感。"))
    fun update(block: (LumenMorphOptions) -> LumenMorphOptions) {
        tuning.updateMorph(block(morph))
        recreate()
    }
    addView(switchRow(palette, "启用弹簧动画", "关闭后状态切换直接落位，仍保留几何/内容变化", morph.enabled) { _, checked ->
        update { it.copy(enabled = checked) }
    })
    addView(switchRow(palette, "贴边高光", "跟随「表面立体边缘」的厚度/亮度", morph.edgeHighlight) { _, checked ->
        update { it.copy(edgeHighlight = checked) }
    })
    addView(switchRow(palette, "贴边接触线", "沿当前形变路径画一圈低 alpha 接触线", morph.contactLine) { _, checked ->
        update { it.copy(contactLine = checked) }
    })
    addView(switchRow(palette, "文字淡入", "关闭后文字恒定 alpha，只被形状裁切", morph.fadeLabel) { _, checked ->
        update { it.copy(fadeLabel = checked) }
    })
    addView(releaseSlider(palette, "收起宽度比例", .3f, .9f, .01f, morph.collapsedFraction) { value ->
        update { it.copy(collapsedFraction = value) }
    })
    addView(releaseSlider(palette, "收起圆角（dp）", 0f, 80f, 1f, morph.collapsedRadiusDp) { value ->
        update { it.copy(collapsedRadiusDp = value) }
    })
    addView(releaseSlider(palette, "展开圆角（dp）", 0f, 60f, 1f, morph.expandedRadiusDp) { value ->
        update { it.copy(expandedRadiusDp = value) }
    })
    addView(releaseSlider(palette, "圆角平滑", 0f, .6f, .01f, morph.cornerSmoothing) { value ->
        update { it.copy(cornerSmoothing = value) }
    })
    addView(releaseSlider(palette, "内容缩放", .8f, 1.2f, .01f, morph.contentScale) { value ->
        update { it.copy(contentScale = value) }
    })
    addView(releaseSlider(palette, "接触线强度", 0f, .3f, .01f, morph.contactAlpha) { value ->
        update { it.copy(contactAlpha = value) }
    })
    addView(releaseSlider(palette, "宽度 response", .1f, .8f, .01f, springResponse(morph.widthSpring)) { value ->
        update { it.copy(widthSpring = LumenSpringSpec.ofResponseBounce(value, springBounce(morph.widthSpring))) }
    })
    addView(releaseSlider(palette, "宽度 bounce", 0f, .4f, .01f, springBounce(morph.widthSpring)) { value ->
        update { it.copy(widthSpring = LumenSpringSpec.ofResponseBounce(springResponse(morph.widthSpring), value)) }
    })
    addView(releaseSlider(palette, "圆角 response", .1f, .8f, .01f, springResponse(morph.cornerSpring)) { value ->
        update { it.copy(cornerSpring = LumenSpringSpec.ofResponseBounce(value, springBounce(morph.cornerSpring))) }
    })
    addView(releaseSlider(palette, "圆角 bounce", 0f, .3f, .01f, springBounce(morph.cornerSpring)) { value ->
        update { it.copy(cornerSpring = LumenSpringSpec.ofResponseBounce(springResponse(morph.cornerSpring), value)) }
    })
    addView(releaseSlider(palette, "内容 response", .1f, .5f, .01f, springResponse(morph.contentSpring)) { value ->
        update { it.copy(contentSpring = LumenSpringSpec.ofResponseBounce(value, springBounce(morph.contentSpring))) }
    })
    addView(releaseSlider(palette, "内容 bounce", 0f, .2f, .01f, springBounce(morph.contentSpring)) { value ->
        update { it.copy(contentSpring = LumenSpringSpec.ofResponseBounce(springResponse(morph.contentSpring), value)) }
    })
}

private fun springResponse(spec: LumenSpringSpec): Float =
    (2.0 * PI / sqrt(spec.stiffness.toDouble())).toFloat()

private fun springBounce(spec: LumenSpringSpec): Float =
    (1f - spec.dampingRatio).coerceIn(0f, .9f)

/**
 * 长按动效卡：跟手位移的橡皮筋曲线与回弹弹簧（§12，`ofResponseBounce` 设计口语换算）。
 * elastic 在 Activity 创建时构造，改动重建生效。
 */
internal fun SampleActivity.motionCard(palette: LumenPalette) = settingsCard(palette, "长按动效（重建）").apply {
    addView(label(palette, "跟手位移曲线"))
    addView(choiceRow(palette,
        listOf("默认 TANH" to ElasticRubberCurve.TANH, "跟手优先 RATIONAL" to ElasticRubberCurve.RATIONAL),
        tuning.rubberCurve) { curve ->
        if (curve == tuning.rubberCurve) return@choiceRow
        tuning.updateCurve(curve)
        recreate()
    })
    addView(hint(palette, "RATIONAL 初段更跟手、接近行程上限更韧；两者行程上限与 slop 归零一致。"))
    addView(switchRow(palette, "自定义回弹弹簧",
        "以「周期 + 弹跳」换算刚度与阻尼比；关闭即引擎原样（310 / 0.7）",
        tuning.springCustomized) { _, checked ->
        tuning.updateSpring(checked, tuning.springResponseSeconds, tuning.springBounce)
        recreate()
    })
    if (tuning.springCustomized) {
        addView(releaseSlider(palette, "周期（秒）", 0.08f, 1.5f, 0.01f, tuning.springResponseSeconds) { value ->
            tuning.updateSpring(true, value, tuning.springBounce)
            recreate()
        })
        addView(releaseSlider(palette, "弹跳", 0f, 0.9f, 0.05f, tuning.springBounce) { value ->
            tuning.updateSpring(true, tuning.springResponseSeconds, value)
            recreate()
        })
        addView(hint(palette, "周期越短回弹越快；弹跳越大过冲越明显，0 为近临界阻尼。"))
    }
    addView(hint(palette, "改完去别的页长按卡片拖动再松手对比。"))
}

/**
 * 松手后应用并重建的参数滑块：标题实时显示当前值，写入发生在 `onStopTrackingTouch`——
 * 需要重建 Activity 生效的参数不能在拖动中途落盘，否则一次滑动触发几十次重建。
 */
internal fun SampleActivity.releaseSlider(
    palette: LumenPalette,
    label: String,
    from: Float,
    to: Float,
    step: Float,
    initial: Float,
    apply: (Float) -> Unit
) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(0, dp(12), 0, 0)
    val title = TextView(context).apply {
        textSize = 13f
        setTextColor(palette.textSecondary)
    }
    val steps = ((to - from) / step).roundToInt()
    fun valueOf(progress: Int) = (from + progress * step).coerceIn(from, to)
    fun show(value: Float) {
        title.text = String.format(Locale.ROOT, "%s  %.2f", label, value)
    }
    show(initial)
    addView(title)
    addView(SeekBar(context).apply {
        max = steps
        progress = ((initial - from) / step).roundToInt().coerceIn(0, steps)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) show(valueOf(progress))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = apply(valueOf(seekBar.progress))
        })
    }, LinearLayout.LayoutParams(-1, -2))
}
