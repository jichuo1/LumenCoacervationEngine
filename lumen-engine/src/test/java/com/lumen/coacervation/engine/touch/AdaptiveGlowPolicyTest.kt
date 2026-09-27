package com.lumen.coacervation.engine.touch

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自适应光晕几何的性质测试。全部为纯 JVM 标量断言——android.graphics 类型一个都不碰，
 * 因此这里的结论就是 draw 路径真实会算出的东西。
 *
 * 覆盖两族：**几何正确性**（形变/方向/边缘/拖尾/有界性）与**无极性质**
 * （利普希茨有界、帧间连续、单调、临界穿越连续、帧率等价）。后者是"无极变化"的
 * 验收本体——分档、折点、模式切换都会在其中现形。
 */
class AdaptiveGlowPolicyTest {
    private val density = 3f
    private val travelEpsPx = 1.5f             // 0.5dp：渲染位移的"有方向"闸门（不是 touchSlop）
    private val maxTravelPx = 12f              // 4dp
    private val velocityRefPx = 720f           // 240dp/s
    private val edgeBandPx = 84f               // 28dp
    private val radiusPx = 192f                // 64dp
    private val barWidth = 960f
    private val barHeight = 192f
    private val cornerPx = barHeight / 2f      // 胶囊圆角，与底栏 outline 一致
    private val config = GlowConfig.create(density, maxTravelPx, travelEpsPx, velocityRefPx, edgeBandPx)

    private fun frame(block: GlowFrame.() -> Unit) = GlowFrame().apply(block)

    /** 逐帧喂入；每帧先用零值清空 holder，再交给 block 填。 */
    private fun run(
        state: GlowState,
        frames: Int,
        dt: Float,
        baseAlpha: Int = 72,
        cfg: GlowConfig = config,
        radius: Float = radiusPx,
        block: (GlowFrame) -> Unit
    ) {
        val holder = GlowFrame()
        repeat(frames) {
            holder.press = 0f
            holder.offsetX = 0f
            holder.offsetY = 0f
            holder.velocityX = 0f
            holder.velocityY = 0f
            holder.centerX = 0f
            holder.centerY = 0f
            holder.boundsWidth = 0f
            holder.boundsHeight = 0f
            holder.cornerRadius = 0f
            block(holder)
            state.update(holder, dt, radius, baseAlpha, cfg)
        }
    }

    private fun centered(block: GlowFrame.() -> Unit) = frame {
        centerX = barWidth / 2f
        centerY = barHeight / 2f
        boundsWidth = barWidth
        boundsHeight = barHeight
        cornerRadius = cornerPx
        block()
    }

    private fun restingFrame() = centered { press = 1f }

    @Test fun heldStillIsPixelIdenticalToTheLegacyCircle() {
        val state = GlowState()
        state.update(restingFrame(), 1f / 120f, radiusPx, 72, config)
        val shape = state.shape
        assertTrue(shape.visible)
        assertEquals("静止 alpha 必须与改造前逐字一致", 72, shape.alphaByte)
        assertEquals(radiusPx, shape.radiusX, 1e-3f)
        assertEquals(radiusPx, shape.radiusY, 1e-3f)
        assertEquals(0f, shape.rotationDeg, 0f)
        // 高亮表面的基础 alpha 32 同样逐字保持
        val highlight = GlowState()
        highlight.update(restingFrame(), 1f / 120f, radiusPx, 32, config)
        assertEquals(32, highlight.shape.alphaByte)
    }

    @Test fun horizontalDragStretchesAlongXAndConservesArea() {
        val state = GlowState()
        run(state, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.offsetX = maxTravelPx
            holder.velocityX = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        val shape = state.shape
        assertTrue(shape.radiusX > radiusPx * 1.4f)
        assertTrue(shape.radiusY < radiusPx * 0.72f)
        assertEquals("面积守恒", radiusPx * radiusPx, shape.radiusX * shape.radiusY, radiusPx * radiusPx * 0.001f)
        assertEquals(0f, shape.rotationDeg, 1f)
        // 1.30 × 1.15 × 1.18 × 72 = 127.0
        assertEquals(127, shape.alphaByte)
    }

    @Test fun verticalDragStretchesAlongYAndGetsNoAxialBoost() {
        val state = GlowState()
        run(state, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.offsetY = maxTravelPx
            holder.velocityY = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        val shape = state.shape
        assertEquals(90f, shape.rotationDeg, 1f)
        assertTrue(shape.radiusX > radiusPx * 1.4f)
        // 纵向没有横向增亮：1.30 × 1.15 × 72 = 107.6 ⇒ 108
        assertEquals(108, shape.alphaByte)
    }

    @Test fun diagonalDirectionIsTheVelocityAngle() {
        val state = GlowState()
        val v = velocityRefPx / sqrt(2f)
        run(state, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = v; holder.velocityY = v
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertEquals(45f, state.shape.rotationDeg, 1f)
    }

    @Test fun coreOffsetConcentratesTheGlowAlongTheDeformation() {
        // 静止：无方向 ⇒ 不前移（正圆光晕不能无端偏心头）
        val rest = GlowState()
        rest.update(restingFrame(), 1f / 120f, radiusPx, 72, config)
        assertEquals(0f, rest.shape.coreOffsetX, 0f)

        // 满额横向形变：前移达到上限；rotation 仍为 0（局部 +X 就是运动方向）
        val horizontal = GlowState()
        run(horizontal, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.offsetX = maxTravelPx
            holder.velocityX = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertEquals(config.coreShiftMaxPx, horizontal.shape.coreOffsetX, config.coreShiftMaxPx * 0.02f)

        // 垂直形变：前移同样满额，靠 rotation=90° 转到垂直方向
        val vertical = GlowState()
        run(vertical, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.offsetY = maxTravelPx
            holder.velocityY = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertEquals(90f, vertical.shape.rotationDeg, 1f)
        assertEquals(config.coreShiftMaxPx, vertical.shape.coreOffsetX, config.coreShiftMaxPx * 0.02f)
    }

    @Test fun coreOffsetGrowsMonotonicallyWithDeformation() {
        var previous = -1f
        for (index in 0..40) {
            val magnitude = index / 40f
            val state = GlowState()
            run(state, frames = 30, dt = 1f / 120f) { holder ->
                holder.press = 1f
                holder.offsetX = maxTravelPx * magnitude
                holder.velocityX = velocityRefPx * magnitude
                holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
                holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
                holder.cornerRadius = cornerPx
            }
            val offset = state.shape.coreOffsetX
            assertTrue("前移量必须随形变单调不减", offset >= previous - 1e-4f)
            assertTrue("前移量不得超上限", offset <= config.coreShiftMaxPx + 1e-3f)
            previous = offset
        }
        assertTrue("满额形变必须有明显前移", previous > config.coreShiftMaxPx * 0.95f)
    }

    @Test fun coreOffsetScalesDownNearTheEdge() {
        // 贴边仍可见（alpha 只降到地板），但前移与半径一起收到 EDGE_MIN_SCALE
        val atEdge = GlowState()
        atEdge.update(centered {
            press = 1f
            velocityX = velocityRefPx
            offsetX = maxTravelPx
            centerY = 0f
        }, 1f / 120f, radiusPx, 72, config)
        assertTrue(atEdge.shape.visible)
        assertTrue("贴边前移必须随尺寸收缩", atEdge.shape.coreOffsetX <= config.coreShiftMaxPx * GlowConfig.EDGE_MIN_SCALE + 1e-3f)

        // 半 band 处稳定形变：前移与半径同乘 edgeScale（≈0.85，下限已与堆积目标对齐），不到满额
        val nearEdge = GlowState()
        run(nearEdge, frames = 30, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.offsetX = maxTravelPx
            holder.centerX = barWidth / 2f
            holder.centerY = edgeBandPx * 0.5f
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertTrue(nearEdge.shape.visible)
        assertTrue("贴边衰减必须同时收前移", nearEdge.shape.coreOffsetX < config.coreShiftMaxPx * 0.95f)
        assertTrue(nearEdge.shape.coreOffsetX > config.coreShiftMaxPx * 0.72f)
    }

    @Test fun edgeProximityFadesAlphaContinuously() {
        val samples = ArrayList<Pair<Float, Float>>()
        for (index in 0..60) {
            val y = cornerPx * index / 60f // 0（贴上边）→ 96（居中）
            val state = GlowState()
            state.update(centered {
                press = 1f
                centerY = y
            }, 1f / 120f, radiusPx, 72, config)
            samples += y to state.shape.alphaUnit
        }
        for (index in 1 until samples.size) {
            assertTrue("alpha 必须随边距单调不减", samples[index].second >= samples[index - 1].second - 1e-4f)
        }
        assertEquals("贴边 alpha 落在地板而不是 0", GlowConfig.EDGE_ALPHA_FLOOR, samples.first().second, 1e-4f)
        assertEquals("居中无衰减", 1f, samples.last().second, 1e-4f)
        val atEdge = GlowState()
        atEdge.update(centered {
            press = 1f
            centerY = 0f
        }, 1f / 120f, radiusPx, 72, config)
        assertTrue("贴边仍可见", atEdge.shape.visible)
    }

    @Test fun stretchGrowsFromZeroWhenTheDragStarts() {
        // 起拖第一帧：形变必须接近 0（latent 拉伸会在这时一步到位）
        val state = GlowState()
        state.update(centered {
            press = 1f
            velocityX = 60f // 刚过静止闸门的微小速度
            offsetX = 1f
        }, 1f / 120f, radiusPx, 72, config)
        assertTrue(state.shape.radiusX < radiusPx * 1.05f)
    }

    @Test fun tailLagsBehindTheMotionAndIsBounded() {
        val state = GlowState()
        run(state, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.offsetX = maxTravelPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        val shape = state.shape
        // 向右拖：光心滞后到触点左侧，且不超过 TAIL_MAX(10dp=30px)
        assertTrue(shape.centerX < barWidth / 2f - 1f)
        assertTrue(shape.centerX >= barWidth / 2f - 30f - 1f)
    }

    @Test fun releaseFadesOutAndTailVanishes() {
        val state = GlowState()
        run(state, frames = 30, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.offsetX = maxTravelPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        val stretched = state.shape.radiusX
        run(state, frames = 60, dt = 1f / 120f) { holder ->
            holder.press = 0f
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertFalse("松手后不可见", state.shape.visible)
        assertTrue(state.shape.radiusX < stretched)
    }

    @Test fun everyInputCombinationStaysFiniteAndInRange() {
        val random = Random(20260920)
        val wild = floatArrayOf(
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            -1e30f, 1e30f, -1f, 0f, 1f, 1e-30f
        )
        repeat(4000) {
            val state = GlowState()
            val holder = GlowFrame()
            holder.press = if (it % 3 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * 2f
            holder.offsetX = wild[random.nextInt(wild.size)]
            holder.offsetY = wild[random.nextInt(wild.size)]
            holder.velocityX = wild[random.nextInt(wild.size)]
            holder.velocityY = wild[random.nextInt(wild.size)]
            holder.centerX = if (it % 5 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * barWidth
            holder.centerY = if (it % 7 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * barHeight
            holder.boundsWidth = if (it % 11 == 0) wild[random.nextInt(wild.size)] else barWidth
            holder.boundsHeight = if (it % 13 == 0) wild[random.nextInt(wild.size)] else barHeight
            holder.cornerRadius = wild[random.nextInt(wild.size)]
            val dt = if (it % 4 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * 0.2f
            val radius = if (it % 9 == 0) wild[random.nextInt(wild.size)] else radiusPx
            val base = intArrayOf(0, 32, 72, 255)[random.nextInt(4)]
            state.update(holder, dt, radius, base, config)
            val shape = state.shape
            // 面积守恒要与**实际生效的半径**比（policy 内部会把半径钳到 MAX_RADIUS_PX）
            val effectiveRadius = if (radius.isFinite()) radius.coerceIn(0f, GlowState.MAX_RADIUS_PX) else 0f
            assertTrue("centerX", shape.centerX.isFinite())
            assertTrue("centerY", shape.centerY.isFinite())
            assertTrue("radiusX", shape.radiusX.isFinite() && shape.radiusX >= 0f)
            assertTrue("radiusY", shape.radiusY.isFinite() && shape.radiusY >= 0f)
            assertTrue("rotationDeg", shape.rotationDeg.isFinite())
            assertTrue("alphaUnit", shape.alphaUnit.isFinite() && shape.alphaUnit >= 0f)
            assertTrue("alphaByte", shape.alphaByte in 0..255)
            assertTrue("rx*ry 恒不超基准面积",
                shape.radiusX * shape.radiusY <= effectiveRadius * effectiveRadius * 1.001f + 1f)
            if (shape.visible) assertTrue("可见即 alpha 为正", shape.alphaByte > 0)
        }
    }

    @Test fun sdfMatchesHandComputedDistances() {
        // 胶囊中心到边界的距离 = 半高
        assertEquals(96f, -roundedRectSignedDistance(480f, 96f, 960f, 192f, 96f), 1e-3f)
        // 距上边 4px
        assertEquals(4f, -roundedRectSignedDistance(480f, 4f, 960f, 192f, 96f), 1e-3f)
        // 上方外 10px 为负
        assertEquals(-10f, -roundedRectSignedDistance(480f, -10f, 960f, 192f, 96f), 1e-3f)
        // 矩形退化：(5,5) 距最近边 5px
        assertEquals(5f, -roundedRectSignedDistance(5f, 5f, 100f, 100f, 0f), 1e-3f)
        // 圆角外侧：(-10,-10) 在 100×100 r=20 的左上角外
        assertEquals(-(sqrt(2f) * 30f - 20f), -roundedRectSignedDistance(-10f, -10f, 100f, 100f, 20f), 1e-2f)
        // 边界本身为 0
        assertEquals(0f, -roundedRectSignedDistance(480f, 0f, 960f, 192f, 96f), 1e-3f)
    }

    @Test fun noisyVelocityKeepsTheAngleStable() {
        val random = Random(7)
        var drift = 0.0
        val state = GlowState()
        repeat(180) {
            val jitter = (random.nextFloat() - 0.5f) * 0.42f // ±12°
            val radians = drift + jitter
            val v = velocityRefPx
            state.update(centered {
                press = 1f
                velocityX = (cos(radians).toDouble() * v).toFloat()
                velocityY = (sin(radians).toDouble() * v).toFloat()
            }, 1f / 120f, radiusPx, 72, config)
            val degrees = Math.toDegrees(drift)
            assertTrue("第 $it 帧方向跳变 ${state.shape.rotationDeg} vs $degrees",
                abs(shortestAngleDeltaDeg(degrees.toFloat(), state.shape.rotationDeg)) < 15f)
            drift += (random.nextFloat() - 0.5f) * 0.05f
        }
        // 反向时走短弧，不翻烧饼（±180 等价）。转速护栏 540°/s ⇒ 180° 需约 333ms，
        // 这里给 60 帧（500ms）让它收敛到位。
        val reversal = GlowState()
        run(reversal, frames = 30, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        run(reversal, frames = 60, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = -velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertTrue(abs(abs(reversal.shape.rotationDeg) - 180f) < 3f)
    }

    @Test fun nanFramesKeepThePreviousDirection() {
        val state = GlowState()
        run(state, frames = 20, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        val settled = state.shape.rotationDeg
        run(state, frames = 5, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = Float.NaN
            holder.velocityY = Float.NaN
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertEquals(settled, state.shape.rotationDeg, 1e-4f)
    }

    // ---------------- 无极性质（连续域） ----------------

    @Test fun alphaIsContinuousAcrossTheAxialThreshold() {
        // 斜向拖过 |vx|/|vy| = 1.15 的临界方向：亮度必须连续爬升，不许"咯噔"
        val outputs = ArrayList<Float>()
        for (index in 0..120) {
            val ratio = 0.9f + 0.6f * index / 120f
            val state = GlowState()
            run(state, frames = 24, dt = 1f / 120f) { holder ->
                holder.press = 1f
                holder.velocityX = velocityRefPx * ratio
                holder.velocityY = velocityRefPx
                holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
                holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
                holder.cornerRadius = cornerPx
            }
            outputs += state.shape.alphaUnit
        }
        var maxStep = 0f
        for (index in 1 until outputs.size) {
            maxStep = maxOf(maxStep, abs(outputs[index] - outputs[index - 1]))
        }
        assertTrue("临界穿越单步跳变 $maxStep", maxStep < 0.03f)
    }

    @Test fun alphaIsLipschitzInSpeedAndTravel() {
        for (channel in 0..1) {
            var previous = -1f
            var maxStep = 0f
            for (index in 0..600) {
                // 通道 0 扫速度，通道 1 扫行程；步长都远小于各自饱和区宽度
                val magnitude = if (channel == 0) velocityRefPx * 2f * index / 600f
                else maxTravelPx * 2f * index / 600f
                val state = GlowState()
                run(state, frames = 30, dt = 1f / 120f) { holder ->
                    holder.press = 1f
                    if (channel == 0) holder.velocityX = magnitude else holder.offsetX = magnitude
                    holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
                    holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
                    holder.cornerRadius = cornerPx
                }
                val alpha = state.shape.alphaUnit
                if (previous >= 0f) maxStep = maxOf(maxStep, abs(alpha - previous))
                previous = alpha
            }
            assertTrue("通道 $channel 单步跳变 $maxStep", maxStep < 0.02f)
        }
    }

    @Test fun frameToFrameOutputsHaveNoVisibleJumps() {
        // 一次完整手势帧序列：按下（真实 press 弹簧）、60 帧拖动、松手回弹。
        // dt 混 8.3/11.1/16.7ms。上界按真实弹簧的最大单帧增量给：press 弹簧从静止
        // 到 1 的峰值速率约 8/s，60Hz 单帧 Δpress≈0.13 ⇒ Δalpha≈9.6 bytes。
        // 分档/折点/模式切换会一次跳几十，仍会被抓住。
        val state = GlowState()
        val pressSpring = PressSpring(0f, 1f)
        var lastAlpha = -1
        var lastRotation = 0f
        var lastRadiusX = radiusPx
        var lastTailOffset = 0f
        var frameIndex = 0
        fun step(holder: GlowFrame) {
            val dts = floatArrayOf(1f / 120f, 1f / 90f, 1f / 60f)
            val dt = dts[frameIndex % 3]
            state.update(holder, dt, radiusPx, 72, config)
            val shape = state.shape
            if (shape.visible) {
                if (lastAlpha >= 0) {
                    assertTrue("第 $frameIndex 帧 alpha 跳变 ${abs(shape.alphaByte - lastAlpha)}",
                        abs(shape.alphaByte - lastAlpha) <= 12)
                    // 角度上界 = 转速护栏 × dt（dt 归一化；没护栏时反转一帧可搬 51°）
                    val angleBound = GlowConfig.MAX_ANGULAR_SPEED_DEG_PER_SEC * dt + 0.5f
                    assertTrue("第 $frameIndex 帧角度跳变 ${abs(shortestAngleDeltaDeg(lastRotation, shape.rotationDeg))}",
                        abs(shortestAngleDeltaDeg(lastRotation, shape.rotationDeg)) <= angleBound)
                    assertTrue("第 $frameIndex 帧半径跳变 ${abs(shape.radiusX - lastRadiusX) / radiusPx}",
                        abs(shape.radiusX - lastRadiusX) / radiusPx <= 0.02f)
                    val tailOffset = abs(shape.centerX - holder.centerX)
                    assertTrue("第 $frameIndex 帧拖尾跳变 ${abs(tailOffset - lastTailOffset)}",
                        abs(tailOffset - lastTailOffset) <= 1.5f * density + 0.5f)
                }
                lastAlpha = shape.alphaByte
                lastRotation = shape.rotationDeg
                lastRadiusX = shape.radiusX
                lastTailOffset = abs(shape.centerX - holder.centerX)
            }
            frameIndex++
        }
        val holder = GlowFrame()
        holder.boundsWidth = barWidth; holder.boundsHeight = barHeight; holder.cornerRadius = cornerPx
        for (index in 0 until 24) {
            val seconds = index / 120f
            holder.press = pressSpring.value(seconds).coerceIn(0f, 1f)
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            step(holder)
        }
        for (index in 0 until 60) {
            val progress = index / 60f
            holder.press = 1f
            holder.offsetX = maxTravelPx * progress
            holder.velocityX = velocityRefPx * progress
            holder.centerX = barWidth / 2f + progress * 40f
            holder.centerY = barHeight / 2f
            step(holder)
        }
        val releaseSpring = PressSpring(1f, 0f, 0f)
        for (index in 0 until 40) {
            val seconds = index / 120f
            val progress = index / 40f
            holder.press = releaseSpring.value(seconds).coerceIn(0f, 1f)
            // 回弹按物理一致建模：位移单调归零，差分速度在转向点连续过零
            // （早期草案让位移钉在上限、速度却从 +708 突翻 -216，真实信号不可能如此）。
            holder.offsetX = maxTravelPx * (1f - progress)
            holder.velocityX = velocityRefPx * (1f - 2f * progress)
            holder.centerX = barWidth / 2f + 40f
            holder.centerY = barHeight / 2f
            step(holder)
        }
        assertFalse(state.shape.visible)
    }

    @Test fun frameRateDoesNotChangeTheResponse() {
        // 同一次手势分别以 120Hz / 60Hz 推进：matched-time 输出必须一致（dt 归一化的直接证据）
        fun simulate(dt: Float, steps: Int): GlowShape {
            val state = GlowState()
            val holder = GlowFrame()
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight; holder.cornerRadius = cornerPx
            repeat(steps) { index ->
                val progress = ((index + 1) / 60f).coerceIn(0f, 1f)
                holder.press = 1f
                holder.offsetX = maxTravelPx * progress
                holder.velocityX = velocityRefPx * progress
                holder.centerX = barWidth / 2f
                holder.centerY = barHeight / 2f
                state.update(holder, dt, radiusPx, 72, config)
            }
            return state.shape
        }
        val fast = simulate(1f / 120f, 120) // 1 秒
        val slow = simulate(1f / 60f, 60)   // 1 秒
        assertTrue(abs(fast.radiusX - slow.radiusX) / radiusPx < 0.03f)
        assertTrue(abs(fast.rotationDeg - slow.rotationDeg) < 5f)
        assertTrue(abs(fast.alphaByte - slow.alphaByte) <= 5)
    }

    @Test fun resetOnlyMarksTheInvisibleBoundary() {
        val state = GlowState()
        run(state, frames = 20, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        state.reset()
        assertFalse(state.shape.visible)
        assertEquals(0f, state.shape.alphaUnit, 0f)
        // reset 之后重新按下：从正圆开始
        state.update(restingFrame(), 1f / 120f, radiusPx, 72, config)
        assertEquals(72, state.shape.alphaByte)
        assertEquals(radiusPx, state.shape.radiusX, 1e-3f)
    }
}

/**
 * 真实按压信号的发生器：来源工程底栏按压弹簧（ModernNavigationSpring）的同一条欠阻尼解析解，参数原样保留。
 * 只用来给光晕状态机喂"物理上可能出现"的 press 序列，本身不属于引擎。
 */
private class PressSpring(start: Float, target: Float, velocity: Float = 0f) {
    private val target = if (target.isFinite()) target else 0f
    private val displacement = (if (start.isFinite()) start else this.target) - this.target
    private val decay = 15.6f
    private val frequency = 12.51559f
    private val sine = ((if (velocity.isFinite()) velocity else 0f) + decay * displacement) / frequency

    fun value(seconds: Float): Float {
        val t = seconds.coerceAtLeast(0f)
        return target + kotlin.math.exp(-decay * t) *
            (displacement * kotlin.math.cos(frequency * t) + sine * kotlin.math.sin(frequency * t))
    }
}
