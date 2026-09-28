package com.lumen.coacervation.engine.interaction

import com.lumen.coacervation.engine.contract.MotionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElasticGlowTuningTest {
    @Test fun unitMultipliersReturnTheOriginalValuesBitForBit() {
        for (alpha in 0..255) assertEquals(alpha, ElasticGlowTuning.baseAlpha(alpha, 1f))
        for (radius in listOf(0f, 33.6f, 252f, 1e6f)) assertEquals(radius, ElasticGlowTuning.radius(radius, 1f), 0f)
    }

    @Test fun intensityScalesTheBaseAlphaWithinAByte() {
        assertEquals(0, ElasticGlowTuning.baseAlpha(32, 0f))
        assertEquals(16, ElasticGlowTuning.baseAlpha(32, .5f))
        assertEquals(128, ElasticGlowTuning.baseAlpha(32, 4f))
        assertEquals(255, ElasticGlowTuning.baseAlpha(200, 4f))
        assertEquals(84, ElasticGlowTuning.baseAlpha(56, 1.5f))
    }

    @Test fun unitDeformationReturnsTheOriginalValuesBitForBit() {
        for (limit in listOf(0f, 4.2f, 47.25f)) assertEquals(limit, ElasticDeformationTuning.travelLimit(limit, 1f), 0f)
        for (scale in listOf(.984f, 1f, 1.035f, 1.0123f)) assertEquals(scale, ElasticDeformationTuning.scale(scale, 1f), 0f)
        // 与改造前 `((1 - ratio) / PRESS_DEPTH).coerceIn(0, 1)` 逐位一致。
        for (ratio in listOf(1f, .999f, .992f, .984f, .9f, 1.02f)) {
            assertEquals(((1f - ratio) / ElasticMotionPolicy.PRESS_DEPTH).coerceIn(0f, 1f),
                ElasticDeformationTuning.pressFromScale(ratio, 1f), 0f)
        }
    }

    @Test fun deformationScalesTravelAndTheOffsetFromUnitScale() {
        assertEquals(36f, ElasticDeformationTuning.travelLimit(18f, 2f), 0f)
        assertEquals(0f, ElasticDeformationTuning.travelLimit(18f, 0f), 0f)
        assertEquals(1.07f, ElasticDeformationTuning.scale(1.035f, 2f), 1e-6f)
        assertEquals(.992f, ElasticDeformationTuning.scale(.984f, .5f), 1e-6f)
        for (scale in listOf(.984f, 1.035f)) assertEquals(1f, ElasticDeformationTuning.scale(scale, 0f), 0f)
        // 零行程时既有策略输出零位移，形变关闭后也不会有速度与回弹。
        val out = ElasticVector(1f, 1f)
        ElasticMotionPolicy.drag(40f, 30f, 8f, 0f, out)
        assertEquals(0f, out.x, 0f)
        assertEquals(0f, out.y, 0f)
        assertEquals(0f, ElasticMotionPolicy.releaseVelocity(0f, 5f, 16L, 0f), 0f)
    }

    @Test fun regrabbingMidBounceKeepsTheRenderedScaleContinuous() {
        for (deformation in listOf(.25f, .5f, 1f, 1.5f, 2f)) {
            for (press in listOf(0f, .3f, .7f, 1f)) {
                val rendered = ElasticDeformationTuning.scale(1f - ElasticMotionPolicy.PRESS_DEPTH * press, deformation)
                assertEquals(press, ElasticDeformationTuning.pressFromScale(rendered, deformation), 1e-4f)
            }
        }
        assertEquals(0f, ElasticDeformationTuning.pressFromScale(.984f, 0f), 0f)
        assertEquals(0f, ElasticDeformationTuning.pressFromScale(Float.NaN, 1f), 0f)
    }

    @Test fun radiusScalesLinearly() {
        assertEquals(50f, ElasticGlowTuning.radius(100f, .5f), 0f)
        assertEquals(200f, ElasticGlowTuning.radius(100f, 2f), 0f)
    }

    /**
     * 倍率每次按下时读取（设置页滑块可即时预览），并真正进入光晕的半径与基准 alpha；
     * 两个窗口入口（Activity、弹窗）都把同一个来源传给控制器。空白折叠后比对。
     */
    @Test fun tuningIsReadPerPressAndReachesTheGlow() {
        fun flat(text: String) = text.replace(Regex("\\s+"), " ")
        val controller = flat(MotionSource.file("ElasticInteractionController"))
        assertTrue(controller.contains("val tuning = effectTuning() removeVisual(restore = true, releaseLease = true)"))
        assertTrue(controller.contains("highlight = TouchHighlight(view, density, highlightColor, tuning.dragGlowIntensity, tuning.dragGlowRadius)"))
        assertTrue(controller.contains("ElasticGlowTuning.radius(maxOf(width, height) * .7f, glowRadiusScale)"))
        assertTrue(controller.contains("ElasticGlowTuning.baseAlpha(HIGHLIGHT_BASE_ALPHA, glowIntensity)"))
        assertTrue(controller.contains("state.update(frame, dt, radius, baseAlpha, config)"))
        // 形变倍率进入行程上限（邻居钳制在其后）、按压/拉伸缩放（绝对拉伸上限在其后）与接手时的按压反推。
        assertTrue(controller.contains("deformation = tuning.dragDeformation"))
        assertTrue(controller.contains("limit = ElasticDeformationTuning.travelLimit( ElasticMotionPolicy.positionLimit(group.width, group.height, density), deformation) groupWidth"))
        assertTrue(controller.contains("cappedScale( ElasticDeformationTuning.scale(scale.x, deformation), groupWidth, capPx)"))
        assertTrue(controller.contains("cappedScale( ElasticDeformationTuning.scale(scale.y, deformation), groupHeight, capPx)"))
        assertTrue(controller.contains("ElasticDeformationTuning.pressFromScale( observed.scaleX / owned.original.scaleX, deformation)"))
        // 光晕自己的行程参考不随形变倍率缩放：倍率为 0 时 smoothStep(0, 0, x) 会退化。
        assertTrue(controller.contains("private val limitPx = ElasticMotionPolicy.positionLimit(view.width, view.height, density)"))
        val interaction = flat(MotionSource.file("LumenElasticInteraction"))
        assertTrue(interaction.contains("private val effectTuning: () -> LumenEffectTuning = { lumen.effectTuning }"))
        assertEquals(2, Regex("effectTuning = effectTuning").findAll(interaction).count())
    }
}
