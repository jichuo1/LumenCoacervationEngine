package com.lumen.coacervation.engine.touch

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触点越出轮廓后的"贴边堆积"性质测试（纯 JVM）。
 *
 * 验收本体：手指拖出控件时高光**不消失**，而是钉在最近的轮廓边上，越往外拖越亮、越扁、
 * 越铺开；跨越轮廓的瞬间没有任何可见跳变；取向模型与流动模型共享同一套几何。
 */
class AdaptiveGlowEdgePileTest {
    private val density = 3f
    private val radiusPx = 192f
    private val barWidth = 960f
    private val barHeight = 192f
    private val cornerPx = barHeight / 2f
    private val oriented = GlowConfig.create(density, 12f, 1.5f, 720f, 84f)
    private val flowing = GlowConfig.create(
        density = density, maxTravelPx = 12f, travelEpsPx = 0f,
        velocityRefPxPerSec = 720f, edgeBandPx = 0f, axialBoost = 0f, oriented = false
    )

    private fun settle(cfg: GlowConfig, x: Float, y: Float, frames: Int = 120): GlowShape {
        val state = GlowState()
        val holder = GlowFrame()
        repeat(frames) {
            holder.press = 1f
            holder.centerX = x
            holder.centerY = y
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
            state.update(holder, 1f / 120f, radiusPx, 72, cfg)
        }
        return state.shape
    }

    @Test fun glowSurvivesLeavingTheOutline() {
        for (cfg in listOf(oriented, flowing)) {
            val outside = settle(cfg, barWidth / 2f, -200f)
            assertTrue("越界后仍可见", outside.visible)
            assertTrue(outside.alphaByte > 0)
            assertTrue("堆积量应饱和", outside.pileUnit > 0.95f)
        }
    }

    /**
     * 贴边收缩的下限必须等于堆积收敛目标：否则跨界瞬间等效半径先跌穿目标值、
     * 再由堆积拉回——"刚出界先缩小、再集中放大"的 V 形割裂（用户 2026-09-21 反馈）。
     * 这条不变式一旦破坏，下面的单调性用例会跟着失败。
     */
    @Test fun edgeFloorEqualsThePileTarget() {
        assertEquals(
            "EDGE_MIN_SCALE 必须等于 PILE_RECOVER，跨界尺寸才连续",
            GlowConfig.PILE_RECOVER, GlowConfig.EDGE_MIN_SCALE, 0f
        )
    }

    /**
     * 从轮廓内侧一路拖到堆积饱和：等效半径 iso = √rx·ry 必须**单调不增**——
     * 界内收缩到目标值后只能持平（取向模型）或继续收拢（流动模型），绝不允许回涨。
     */
    @Test fun sizeNeverRegrowsAcrossTheBoundary() {
        for (cfg in listOf(oriented, flowing)) {
            var lastIso = Float.MAX_VALUE
            for (step in 0..48) {
                val y = barHeight / 2f - step * (barHeight / 2f + cfg.pileRefPx * 1.5f) / 48f
                val shape = settle(cfg, barWidth / 2f, y)
                val iso = kotlin.math.sqrt(shape.radiusX * shape.radiusY)
                assertTrue(
                    "等效半径不得回涨（V 形割裂）@step=$step y=$y iso=$iso last=$lastIso",
                    iso <= lastIso + 0.5f
                )
                lastIso = iso
            }
        }
    }

    /**
     * 贴屏场景：可触达空间只剩 40px 时，满额堆积必须在 40px 内走完（而不是默认的
     * pileRefPx）——否则屏幕边缘的控件永远堆不出完整"集中"（用户 2026-09-21：
     * 贴屏拖拽时光效随可用空间忽强忽弱）。
     */
    @Test fun pileSaturatesWithinTheReachableRoom() {
        for (cfg in listOf(oriented, flowing)) {
            val nearEdge = GlowState()
            val holder = GlowFrame()
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
            holder.press = 1f
            holder.centerX = barWidth / 2f
            holder.centerY = -40f // 越出 40px
            holder.pileRoomPx = 40f // 屏幕边缘只剩 40px 可用
            repeat(120) { nearEdge.update(holder, 1f / 120f, radiusPx, 72, cfg) }
            assertTrue("可触达空间内必须满额堆积", nearEdge.shape.pileUnit > 0.95f)

            val unbounded = GlowState()
            val open = GlowFrame()
            open.boundsWidth = barWidth
            open.boundsHeight = barHeight
            open.cornerRadius = cornerPx
            open.press = 1f
            open.centerX = barWidth / 2f
            open.centerY = -40f // 同样越出 40px，但空间不受限（默认 +∞）
            repeat(120) { unbounded.update(open, 1f / 120f, radiusPx, 72, cfg) }
            assertTrue("不限空间时同样越界量只能堆出一小截", unbounded.shape.pileUnit < 0.35f)
        }
    }

    /**
     * 弧形路径上第二根轴越界/回界的瞬间，room（对已越界轴取 min）会硬跳——span 必须先过
     * 低通再进 smoothStep，否则 pileTarget 随之跳变，强度读作"瞬间减弱/增强"（用户
     * 2026-09-21 抓帧实证）。断言：room 300→40 硬切后 pile 不得立刻上窜，但最终仍收敛满额。
     */
    @Test fun pileSpanSnapIsAbsorbedByTheLowpass() {
        for (cfg in listOf(oriented, flowing)) {
            val state = GlowState()
            val holder = GlowFrame()
            holder.press = 1f
            holder.centerX = barWidth / 2f
            holder.centerY = -60f // 恒越出 60px
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
            holder.pileRoomPx = 300f // 初始只有一轴越界、空间充足
            repeat(120) { state.update(holder, 1f / 120f, radiusPx, 72, cfg) }
            assertTrue("room=300 时 60px 越界只能堆一小截", state.shape.pileUnit < 0.35f)

            holder.pileRoomPx = 40f // 第二轴越界 → room 硬跳 300→40
            repeat(8) { state.update(holder, 1f / 120f, radiusPx, 72, cfg) }
            assertTrue("span 硬跳必须被低通吸收（8 帧内不得越过中点）",
                state.shape.pileUnit < 0.5f)
            repeat(80) { state.update(holder, 1f / 120f, radiusPx, 72, cfg) }
            assertTrue("room 收缩完成后最终仍应饱和", state.shape.pileUnit > 0.9f)
        }
    }

    @Test fun reachableRoomFollowsTheExceededAxes() {
        val w = 200f; val h = 100f
        // 控件位于屏幕中（四周 500px 空间）：向上越界 → 可用空间 = 上边到屏幕顶
        assertEquals(500f, reachablePileRoomPx(500, 500, 700, 600, 1080, 2340, 100f, -10f, w, h), 1e-3f)
        // 控件右缘距屏幕右缘只剩 30px：向右越界 → 30
        assertEquals(30f, reachablePileRoomPx(850, 500, 1050, 600, 1080, 2340, 210f, 50f, w, h), 1e-3f)
        // 斜向越界：取两轴较小值（更近的屏幕缘先截断行程）
        assertEquals(30f, reachablePileRoomPx(850, 500, 1050, 600, 1080, 2340, 210f, -10f, w, h), 1e-3f)
        // 控件未越界 → 不限空间
        assertEquals(Float.POSITIVE_INFINITY,
            reachablePileRoomPx(500, 500, 700, 600, 1080, 2340, 100f, 50f, w, h))
        // 控件已在屏幕外（异常输入）→ 非负
        assertTrue(reachablePileRoomPx(1200, 500, 1400, 600, 1080, 2340, 210f, 50f, w, h) >= 0f)
    }

    @Test fun pileGrowsMonotonicallyWithOvershoot() {
        for (cfg in listOf(oriented, flowing)) {
            var lastAlpha = -1f
            var lastPile = -1f
            var lastRatio = Float.MAX_VALUE
            for (step in 0..40) {
                val overshoot = cfg.pileRefPx * 1.5f * step / 40f
                val shape = settle(cfg, barWidth / 2f, -overshoot)
                assertTrue("alpha 随越界量单调不减 @$overshoot", shape.alphaUnit >= lastAlpha - 1e-4f)
                assertTrue("堆积量随越界量单调不减 @$overshoot", shape.pileUnit >= lastPile - 1e-4f)
                val ratio = shape.radiusX / shape.radiusY
                assertTrue("越往外越扁（法向/切向半径比单调不增）@$overshoot", ratio <= lastRatio + 1e-4f)
                lastAlpha = shape.alphaUnit
                lastPile = shape.pileUnit
                lastRatio = ratio
            }
            val edge = settle(cfg, barWidth / 2f, 0f)
            val far = settle(cfg, barWidth / 2f, -cfg.pileRefPx * 2f)
            assertTrue("远离边缘必须明显比贴边亮", far.alphaUnit > edge.alphaUnit * 1.5f)
            assertEquals(0f, edge.pileUnit, 1e-3f)
            assertEquals(1f, far.pileUnit, 1e-3f)
        }
    }

    @Test fun pilePinsToTheNearestEdgeAndAlignsToItsNormal() {
        for (cfg in listOf(oriented, flowing)) {
            val above = settle(cfg, barWidth / 2f, -300f)
            assertEquals("钉在上边", 0f, above.centerY, 2f)
            assertEquals(barWidth / 2f, above.centerX, 2f)
            assertEquals("主轴转到轮廓法向（竖直）", 0f, abs(shortestAxisDeltaDeg(above.rotationDeg, 90f)), 2f)
            assertTrue("沿法向压扁、沿切向铺开", above.radiusX < above.radiusY)

            val below = settle(cfg, barWidth / 2f, barHeight + 300f)
            assertEquals("钉在下边", barHeight, below.centerY, 2f)

            val right = settle(cfg, barWidth + 300f, barHeight / 2f)
            assertEquals("钉在右端", barWidth, right.centerX, 2f)
            assertEquals(barHeight / 2f, right.centerY, 2f)
            assertEquals("法向水平", 0f, abs(shortestAxisDeltaDeg(right.rotationDeg, 0f)), 2f)

            // 斜向拖出全圆角端头：钉在圆弧上而不是矩形角
            val corner = settle(cfg, barWidth + 300f, -300f)
            val dx = corner.centerX - (barWidth - cornerPx)
            val dy = corner.centerY - cornerPx
            assertEquals("钉在端头圆弧上", cornerPx, kotlin.math.sqrt(dx * dx + dy * dy), 3f)
        }
    }

    @Test fun crossingTheOutlineIsContinuousFrameToFrame() {
        for (cfg in listOf(oriented, flowing)) {
            val state = GlowState()
            val holder = GlowFrame()
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
            var lastAlpha = -1
            var lastX = Float.NaN
            var lastY = Float.NaN
            var lastRx = Float.NaN
            var lastRy = Float.NaN
            var lastRot = Float.NaN
            // 从居中以 2px/帧（240px/s）匀速向上拖到轮廓外 300px，再原路拖回。
            val path = (0..200).map { barHeight / 2f - it * 2f } + (200 downTo 0).map { barHeight / 2f - it * 2f }
            for ((i, y) in path.withIndex()) {
                holder.press = 1f
                holder.velocityY = if (i <= 200) -240f else 240f
                holder.centerX = barWidth / 2f
                holder.centerY = y
                state.update(holder, 1f / 120f, radiusPx, 72, cfg)
                val s = state.shape
                assertTrue("第 $i 帧必须可见", s.visible)
                if (lastAlpha >= 0) {
                    assertTrue("第 $i 帧 alpha 跳变 ${abs(s.alphaByte - lastAlpha)}", abs(s.alphaByte - lastAlpha) <= 12)
                    assertTrue("第 $i 帧光心跳变", abs(s.centerX - lastX) <= 8f && abs(s.centerY - lastY) <= 8f)
                    assertTrue("第 $i 帧半径跳变", abs(s.radiusX - lastRx) / radiusPx <= 0.03f && abs(s.radiusY - lastRy) / radiusPx <= 0.03f)
                    assertTrue("第 $i 帧角度跳变 ${abs(shortestAxisDeltaDeg(lastRot, s.rotationDeg))}",
                        abs(shortestAxisDeltaDeg(lastRot, s.rotationDeg)) <= 14f)
                }
                lastAlpha = s.alphaByte
                lastX = s.centerX; lastY = s.centerY
                lastRx = s.radiusX; lastRy = s.radiusY
                lastRot = s.rotationDeg
            }
            assertTrue("拖回后堆积量归零", state.shape.pileUnit < 0.02f)
        }
    }

    @Test fun releaseOutsideStillFadesOut() {
        val state = GlowState()
        val holder = GlowFrame()
        holder.boundsWidth = barWidth; holder.boundsHeight = barHeight; holder.cornerRadius = cornerPx
        holder.centerX = barWidth / 2f; holder.centerY = -200f
        repeat(60) { holder.press = 1f; state.update(holder, 1f / 120f, radiusPx, 72, oriented) }
        assertTrue(state.shape.visible)
        repeat(60) { holder.press = 0f; state.update(holder, 1f / 120f, radiusPx, 72, oriented) }
        assertTrue("松手后即便在轮廓外也要熄灭", !state.shape.visible)
    }

    @Test fun extremeOvershootStaysFiniteAndBounded() {
        val wild = floatArrayOf(-1e9f, 1e9f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f)
        for (cfg in listOf(oriented, flowing)) for (x in wild) for (y in wild) {
            val shape = settle(cfg, x, y, frames = 8)
            for (v in floatArrayOf(shape.centerX, shape.centerY, shape.radiusX, shape.radiusY, shape.rotationDeg, shape.coreOffsetX, shape.alphaUnit, shape.pileUnit)) {
                assertTrue("有限 @($x,$y)", v.isFinite())
            }
            assertTrue(shape.centerX in 0f..barWidth && shape.centerY in 0f..barHeight)
            assertTrue(shape.pileUnit in 0f..1f)
            assertTrue(shape.alphaByte in 0..255)
        }
    }

    @Test fun outwardNormalMatchesHandComputedDirections() {
        val out = FloatArray(2)
        roundedRectOutwardNormal(barWidth / 2f, -50f, barWidth, barHeight, cornerPx, out)
        assertEquals(0f, out[0], 1e-4f); assertEquals(-1f, out[1], 1e-4f)
        roundedRectOutwardNormal(barWidth / 2f, barHeight + 50f, barWidth, barHeight, cornerPx, out)
        assertEquals(0f, out[0], 1e-4f); assertEquals(1f, out[1], 1e-4f)
        roundedRectOutwardNormal(barWidth + 50f, barHeight / 2f, barWidth, barHeight, cornerPx, out)
        assertEquals(1f, out[0], 1e-4f); assertEquals(0f, out[1], 1e-4f)
        roundedRectOutwardNormal(-50f, barHeight / 2f, barWidth, barHeight, cornerPx, out)
        assertEquals(-1f, out[0], 1e-4f); assertEquals(0f, out[1], 1e-4f)
        // 端头圆弧外 45°：法向指向圆弧圆心到触点的方向
        val cx = barWidth - cornerPx; val cy = cornerPx
        roundedRectOutwardNormal(cx + 100f, cy - 100f, barWidth, barHeight, cornerPx, out)
        assertEquals(0.7071f, out[0], 1e-3f); assertEquals(-0.7071f, out[1], 1e-3f)
        // 无效边界：退化为固定方向且不产生 NaN
        roundedRectOutwardNormal(1f, 1f, 0f, 0f, 0f, out)
        assertTrue(out[0].isFinite() && out[1].isFinite())
    }

    @Test fun axisDeltaIsModulo180() {
        assertEquals(0f, shortestAxisDeltaDeg(0f, 180f), 1e-4f)
        assertEquals(90f, shortestAxisDeltaDeg(0f, 90f), 1e-4f)
        assertEquals(-10f, shortestAxisDeltaDeg(0f, 170f), 1e-4f)
        assertEquals(10f, shortestAxisDeltaDeg(175f, 5f), 1e-4f)
        assertEquals(0f, shortestAxisDeltaDeg(Float.NaN, 30f), 1e-4f)
    }
}
