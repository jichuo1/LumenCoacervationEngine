package com.lumen.coacervation.engine

import java.io.File
import com.lumen.coacervation.engine.liquid.LiquidControlStyle
import com.lumen.coacervation.engine.liquid.LiquidSurfaceAlphaPolicy
import com.lumen.coacervation.engine.liquid.LiquidTokenResolver
import com.lumen.coacervation.engine.liquid.LiquidVisualTuningPolicy
import com.lumen.coacervation.engine.model.SurfaceRole
import org.junit.Assert.*
import org.junit.Test
import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.contract.after

/** Pure state tests plus source wiring guards; not an Android visual/interaction acceptance test. */
class LiquidControlStyleTest {
    @Test fun `all native state combinations preserve selection and disable interaction emphasis`() {
        for (enabled in listOf(false, true)) for (checked in listOf(false, true)) {
            for (pressed in listOf(false, true)) for (focused in listOf(false, true)) {
                val state = LiquidControlStyle.resolve(enabled, checked, pressed, focused)
                assertEquals(checked, state.selected)
                assertEquals(enabled && (pressed || focused), state.emphasized)
                assertEquals(if (enabled) 255 else 100, LiquidControlStyle.opacity(state))
                assertTrue(LiquidControlStyle.fillAlpha(state) in 1..254)
            }
        }
    }

    @Test fun `selected controls remain distinguishable without focus or press`() {
        val idle = LiquidControlStyle.resolve(true, false, false, false)
        val checked = LiquidControlStyle.resolve(true, true, false, false)
        val focused = LiquidControlStyle.resolve(true, false, false, true)
        assertTrue(LiquidControlStyle.fillAlpha(checked) > LiquidControlStyle.fillAlpha(idle))
        assertTrue(LiquidControlStyle.fillAlpha(focused) > LiquidControlStyle.fillAlpha(idle))
        assertTrue(LiquidControlStyle.opacity(idle) > LiquidControlStyle.opacity(idle.copy(enabled = false)))
    }

    @Test fun `new semantic surfaces retain fallback readability in both themes`() {
        for (dark in listOf(false, true)) {
            val params = LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(dark))
            for (role in listOf(SurfaceRole.FILLED_BUTTON, SurfaceRole.TEXT_BUTTON, SurfaceRole.SELECTED_ITEM)) {
                val optical = LiquidSurfaceAlphaPolicy.resolve(role, false, params)
                val fallback = LiquidSurfaceAlphaPolicy.resolve(role, true, params)
                assertTrue(optical in 0f..1f)
                assertTrue(fallback in optical..1f)
            }
        }
    }

    private fun source(relative: String): String {
        val path = "src/main/java/com/lumen/coacervation/engine/$relative"
        return SourceContract.read(path)
    }

    /** 控件换装（可选模块 lumen-controls）与按钮换装（委托）只换视觉：未准备会话不动，绝不改状态、监听器或偏好。 */
    @Test fun `control styling is gated and cannot change preferences or listeners`() {
        val controlsFile = listOf("../lumen-controls", "lumen-controls")
            .map { File("$it/src/main/java/com/lumen/coacervation/engine/controls/LumenControls.kt") }
            .first(File::isFile)
        val controls = SourceContract.normalize(controlsFile.readText()).after("public fun style(")
        assertTrue(controls.contains("if (!lumen.isPrepared) return"))
        val delegate = source("host/LumenActivityDelegate.kt")
        val button = delegate.after("public fun styleActionButton(").before("/** 可选中的条目")
        assertTrue(button.contains("if (!isPrepared) return"))
        assertTrue(button.contains("view.foreground = RippleDrawable"))
        for (body in listOf(controls, button)) {
            listOf("isChecked =", "setOnCheckedChangeListener", "setOnClickListener", "getSharedPreferences",
                "performClick(", "addOnGlobalLayoutListener", "PixelCopy", "RuntimeShader").forEach {
                assertFalse(it, body.contains(it))
            }
        }
        assertTrue(controls.contains("is SwitchCompat"))
        assertTrue(controls.contains("is CheckBox"))
        assertTrue(controls.contains("is EditText"))
        assertTrue(controls.contains("view.setPadding(left, top, right, bottom)"))
        // 新 Drawable 必须立刻拿到当前状态（recreate 后已开启的开关会停在未选中配色）。
        assertEquals(2, Regex("""view\.refreshDrawableState\(\)""").findAll(controls).count())
    }

    @Test fun `choice drawing caches geometry and has no animator or capture loop`() {
        val drawable = source("liquid/LiquidChoiceDrawable.kt")
        val draw = drawable.after("override fun draw(canvas: Canvas)").before("override fun setAlpha")
        listOf("Path()", "RectF()", "LinearGradient(", "post", "invalidateSelf()").forEach {
            assertFalse(it, draw.contains(it))
        }
        assertFalse(drawable.contains("ValueAnimator"))
        assertTrue(drawable.contains("override fun isStateful() = true"))
        assertTrue(drawable.contains("if (checkbox && visualState.selected)"))
    }

    /** 2026-09-24 用户报告浅色下开关颜色较浅：浅色主题加深轨道，深色配比不变。 */
    @Test fun `light theme switches keep a contrasting track`() {
        val drawable = source("liquid/LiquidChoiceDrawable.kt")
        assertTrue(drawable.contains("private val lightTheme = ColorUtils.calculateLuminance(surface) > 0.5"))
        fun constant(name: String) = Regex("const val $name = ([0-9.]+)f?").find(drawable)!!.groupValues[1].toFloat()
        assertTrue(constant("CHECKED_TRACK_WASH_LIGHT") > constant("CHECKED_TRACK_WASH"))
        assertEquals(0.42f, constant("CHECKED_TRACK_WASH"), 0f)
        assertTrue(constant("UNCHECKED_TRACK_SHADE_LIGHT") > 0f)
        // 只给开关轨道铺灰；复选框没有滑块，铺灰只会得到深灰方块（2026-09-24 用户报告）。
        assertTrue(drawable.contains("lightTheme && !thumb && !checkbox -> ColorUtils.blendARGB(surface, outline, UNCHECKED_TRACK_SHADE_LIGHT)"))
        assertTrue(drawable.contains("lightTheme && !thumb && !(checkbox && !checked)"))
        assertTrue(constant("UNCHECKED_EDGE_ALPHA_LIGHT") > 46f)
    }
}
