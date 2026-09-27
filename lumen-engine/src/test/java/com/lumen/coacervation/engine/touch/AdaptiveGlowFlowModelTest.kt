package com.lumen.coacervation.engine.touch

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按高亮的**流动模型**（`GlowConfig.oriented = false`）性质测试。
 *
 * 模型要解决的事：这个场景里手指会连续改变方向，任何"朝向手势方向的长条"都会被反复转轴。
 * 用户 2026-09-21 指定的替代方向是——**形状随"手势把控件拉离原位的程度"无极变化，
 * 让光效流动起来，而不是一个形状不断变化方向**。
 *
 * 因此这里钉住四件事：
 * ① 没有任何取向量（旋转恒 0、亮核前移恒 0、两个半轴恒相等）；
 * ② 形状只由行程量连续决定（胀缩单调、无极）；
 * ③ 运动由光心流动滞后表达（静止精确落在触点，一动泻在后方）；
 * ④ **方向快速反转不引入任何额外跳变**——这是用户投诉的本体。
 */
class AdaptiveGlowFlowModelTest {

    private val density = 3f
    private val limitPx = 54f                     // 18dp：典型卡片行程上限
    private val velocityRefPx = limitPx * 20f     // 与 TouchHighlight.VELOCITY_REF_FACTOR 一致
    private val radiusPx = 420f                   // 0.7 × max(宽,高)
    private val viewWidth = 1320f
    private val viewHeight = 368f
    private val cornerPx = 45f                    // 15dp 卡圆角
    private val flowing = GlowConfig.create(
        density = density,
        maxTravelPx = limitPx,
        travelEpsPx = 0f,
        velocityRefPxPerSec = velocityRefPx,
        edgeBandPx = 0f,
        axialBoost = 0f,
        oriented = false
    )
    private val oriented = GlowConfig.create(
        density = density,
        maxTravelPx = limitPx,
        travelEpsPx = 0f,
        velocityRefPxPerSec = velocityRefPx,
        edgeBandPx = 0f,
        axialBoost = 0f,
        oriented = true
    )

    private class Sample(
        val alpha: Int, val rot: Float, val rx: Float, val ry: Float,
        val core: Float, val cx: Float, val cy: Float
    )

    /**
     * 逐帧喂入。[drive] 每帧给出 (位移x, 位移y, 速度x, 速度y, 触点x, 触点y)。
     * dt 混 120/90/60Hz——与真实帧节奏一致。
     */
    private fun collect(
        frames: Int,
        cfg: GlowConfig,
        drive: (Int) -> FloatArray
    ): List<Sample> {
        val state = GlowState()
        val holder = GlowFrame()
        holder.boundsWidth = viewWidth
        holder.boundsHeight = viewHeight
        holder.cornerRadius = cornerPx
        val dts = floatArrayOf(1f / 120f, 1f / 90f, 1f / 60f)
        val out = ArrayList<Sample>()
        repeat(frames) { index ->
            val v = drive(index)
            holder.press = 1f
            holder.offsetX = v[0]; holder.offsetY = v[1]
            holder.velocityX = v[2]; holder.velocityY = v[3]
            holder.centerX = v[4]; holder.centerY = v[5]
            state.update(holder, dts[index % 3], radiusPx, 32, cfg)
            val s = state.shape
            if (s.visible) out += Sample(s.alphaByte, s.rotationDeg, s.radiusX, s.radiusY,
                s.coreOffsetX, s.centerX, s.centerY)
        }
        return out
    }

    private fun List<Sample>.maxStep(pick: (Sample) -> Float): Float {
        var m = 0f
        for (i in 1 until size) m = maxOf(m, abs(pick(this[i]) - pick(this[i - 1])))
        return m
    }

    /** 触点固定在中点；位移/速度按给定符号与 ramp 走。 */
    private fun dragDrive(sign: Float, ramp: (Int) -> Float): (Int) -> FloatArray = { index ->
        val r = ramp(index)
        floatArrayOf(limitPx * sign * r, 0f, velocityRefPx * sign * r, 0f,
            viewWidth / 2f, viewHeight / 2f)
    }

    // ---------------- ① 没有取向量 ----------------

    @Test fun flowingModelHasNoOrientationAtAll() {
        val samples = collect(240, flowing) { index ->
            // 剧烈的多方向甩动：右 → 左 → 右上 → 左下
            val phase = index / 40
            when (phase % 4) {
                0 -> floatArrayOf(limitPx, 0f, velocityRefPx, 0f, viewWidth / 2f, viewHeight / 2f)
                1 -> floatArrayOf(-limitPx, 0f, -velocityRefPx, 0f, viewWidth / 2f, viewHeight / 2f)
                2 -> floatArrayOf(limitPx * .7f, limitPx * .7f, velocityRefPx * .7f, velocityRefPx * .7f,
                    viewWidth / 2f, viewHeight / 2f)
                else -> floatArrayOf(-limitPx * .7f, -limitPx * .7f, -velocityRefPx * .7f, -velocityRefPx * .7f,
                    viewWidth / 2f, viewHeight / 2f)
            }
        }
        assertTrue("必须有可见帧", samples.size > 100)
        for (s in samples) {
            assertEquals("旋转恒为 0", 0f, s.rot, 0f)
            assertEquals("亮核前移恒为 0", 0f, s.core, 0f)
            assertEquals("两个半轴恒相等", s.rx, s.ry, 1e-3f)
        }
    }

    @Test fun orientedModelStillOrients() {
        // 反向对照：取向模型仍然产出朝向（底栏/scrub 条不受影响）
        val samples = collect(120, oriented, dragDrive(1f) { (it / 40f).coerceAtMost(1f) })
        assertTrue(samples.any { abs(it.rot) < 1f })
        assertTrue(samples.any { it.core > 1f })
        assertTrue(samples.any { it.rx > it.ry * 1.2f })
    }

    // ---------------- ② 形状只由行程量决定 ----------------

    @Test fun shapeSwellsContinuouslyWithDragMagnitude() {
        var previous = -1f
        for (step in 0..40) {
            val magnitude = step / 40f
            val samples = collect(60, flowing, dragDrive(1f) { magnitude })
            val rx = samples.last().rx
            assertTrue("半径必须随行程量单调不减", rx >= previous - 1e-3f)
            assertTrue("半径不得超过胀缩上限", rx <= radiusPx * (1f + GlowConfig.SWELL_MAX) + 1f)
            previous = rx
        }
        assertTrue("满额行程必须明显胀大", previous > radiusPx * 1.3f)
        // 静止（无位移、无速度）回到基准圆
        val rest = collect(3, flowing) { floatArrayOf(0f, 0f, 0f, 0f, viewWidth / 2f, viewHeight / 2f) }
        assertEquals(radiusPx, rest.last().rx, radiusPx * 0.02f)
    }

    @Test fun stillHoldIsThePlainCircle() {
        val samples = collect(30, flowing) { floatArrayOf(0f, 0f, 0f, 0f, viewWidth / 2f, viewHeight / 2f) }
        val last = samples.last()
        assertEquals("静止 alpha 与基础值一致", 32, last.alpha)
        assertEquals(radiusPx, last.rx, radiusPx * 0.02f)
        assertEquals(radiusPx, last.ry, radiusPx * 0.02f)
        assertEquals("光心落在触点", viewWidth / 2f, last.cx, 1f)
        assertEquals(viewHeight / 2f, last.cy, 1f)
    }

    // ---------------- ③ 运动由流动表达 ----------------

    @Test fun centerFlowsBehindTheFingerWhileMoving() {
        // 触点持续向右移动；光心应滞后于触点（泻在后方），但滞后量有界。
        val samples = collect(90, flowing) { index ->
            val x = viewWidth / 2f + index * 4f
            floatArrayOf(limitPx, 0f, velocityRefPx, 0f, x, viewHeight / 2f)
        }
        var maxLag = 0f
        for (s in samples) maxLag = maxOf(maxLag, abs(s.cx - (viewWidth / 2f +
            (samples.indexOf(s).takeIf { it >= 0 } ?: 0) * 4f)))
        assertTrue("流动滞后必须有界（≤ 底栏尾迹上限量级）", maxLag <= GlowConfig.TAIL_MAX_DP * density + 2f)
        assertTrue("运动中必须读得出滞后", maxLag > 1f)
    }

    // ---------------- ④ 反向不引入额外跳变 ----------------

    /** 先跑 [settle] 帧同向满额拖动让状态稳定，再进入正题；只返回稳定后的样本。 */
    private fun settled(
        settle: Int = 60,
        frames: Int,
        drive: (Int) -> FloatArray
    ): List<Sample> = collect(settle + frames, flowing) { index ->
        if (index < settle) dragDrive(1f) { 1f }(index) else drive(index - settle)
    }.drop(settle)

    @Test fun rapidReversalAddsNoJerkOverMonotonicDrag() {
        // 两条曲线都先稳定到满额拖动，再分道：一条继续同向，一条剧烈来回甩。
        // 这样比出来的差异只来自"方向改变"本身，与起步 ramp 无关。
        val monotonic = settled(frames = 240, drive = dragDrive(1f) { 1f })
        val baseAlpha = monotonic.maxStep { it.alpha.toFloat() }
        val baseRx = monotonic.maxStep { it.rx }
        val baseCx = monotonic.maxStep { it.cx }

        val reversal = settled(frames = 240) { index ->
            val sign = if (index / 40 % 2 == 0) 1f else -1f
            dragDrive(sign) { 1f }(index)
        }
        val revAlpha = reversal.maxStep { it.alpha.toFloat() }
        val revRx = reversal.maxStep { it.rx }
        val revCx = reversal.maxStep { it.cx }

        // 反向不得比同向更跳。裕量只吸收相位差，不吸收量级差。
        assertTrue("alpha 反向跳变 $revAlpha vs 基线 $baseAlpha", revAlpha <= baseAlpha + 1f)
        assertTrue("半径反向跳变 $revRx vs 基线 $baseRx", revRx <= baseRx + 1f)
        assertTrue("光心反向跳变 $revCx vs 基线 $baseCx", revCx <= baseCx + 1f)
    }

    @Test fun reversalKeepsEveryOutputFrameContinuous() {
        val samples = settled(frames = 240) { index ->
            val sign = if (index / 40 % 2 == 0) 1f else -1f
            dragDrive(sign) { 1f }(index)
        }
        // 逐帧硬上界：alpha 12 bytes、半径 2% 基准、光心 1.5dp+0.5px
        // （与取向模型 frameToFrameOutputsHaveNoVisibleJumps 同口径）。
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            assertTrue("第 $i 帧 alpha 跳变 ${abs(b.alpha - a.alpha)}",
                abs(b.alpha - a.alpha) <= 12)
            assertTrue("第 $i 帧半径跳变 ${abs(b.rx - a.rx) / radiusPx}",
                abs(b.rx - a.rx) / radiusPx <= 0.02f)
            val head = sqrt((b.cx - a.cx) * (b.cx - a.cx) + (b.cy - a.cy) * (b.cy - a.cy))
            assertTrue("第 $i 帧光心跳变 $head", head <= 1.5f * density + 0.5f)
        }
    }

    @Test fun cornerClampOutsideCapsuleMustNotKillTheHighlight() {
        // 2026-09-21 真机：顶部胶囊（full-round，r = h/2）向四个斜向拖动时高光瞬间消失。
        // 机制：moveTo 把触点钳进**矩形**边界，而矩形四角本就在胶囊轮廓外（SDF > 0）；
        // edgeBandPx = 0 让 smoothStep 退化成 0 处硬阶跃，insideDistance < 0 即整团熄灭。
        // 轮廓裁剪由 TouchHighlight 的 clipPath 负责，这里不许再有一道边缘门。
        val state = GlowState()
        val holder = GlowFrame()
        holder.boundsWidth = viewWidth
        holder.boundsHeight = viewHeight
        holder.cornerRadius = viewHeight * .5f       // 胶囊：全圆角
        holder.press = 1f
        repeat(60) {
            holder.offsetX = limitPx * .7f
            holder.offsetY = limitPx * .7f           // 45° 斜向满额拖动
            holder.velocityX = velocityRefPx * .7f
            holder.velocityY = velocityRefPx * .7f
            holder.centerX = viewWidth               // 钳在矩形右上角：胶囊轮廓外
            holder.centerY = 0f
            state.update(holder, 1f / 120f, radiusPx, 32, flowing)
        }
        assertTrue("触点钳在胶囊外的矩形角时高光不许熄灭", state.shape.visible)
        assertTrue(state.shape.alphaByte > 0)
        // 对照：band > 0 的表面保留边缘衰减语义（底栏/scrub 条贴边变暗），但同样不许熄灭。
        val banded = GlowConfig.create(
            density = density, maxTravelPx = limitPx, travelEpsPx = 0f,
            velocityRefPxPerSec = velocityRefPx, edgeBandPx = 84f,
            axialBoost = 0f, oriented = false
        )
        val bandedState = GlowState()
        holder.cornerRadius = viewHeight * .5f
        holder.centerX = viewWidth; holder.centerY = 0f
        bandedState.update(holder, 1f / 120f, radiusPx, 32, banded)
        assertTrue("band > 0 时轮廓外只变暗、不熄灭", bandedState.shape.visible)
        assertTrue(bandedState.shape.alphaUnit < state.shape.alphaUnit)
    }

    @Test fun wildInputsStayFiniteInTheFlowingModel() {
        val wild = floatArrayOf(
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            -1e30f, 1e30f, -1f, 0f, 1f, 1e-30f
        )
        val random = kotlin.random.Random(20260921)
        repeat(3000) {
            val state = GlowState()
            val holder = GlowFrame()
            holder.boundsWidth = if (it % 11 == 0) wild[random.nextInt(wild.size)] else viewWidth
            holder.boundsHeight = if (it % 13 == 0) wild[random.nextInt(wild.size)] else viewHeight
            holder.cornerRadius = wild[random.nextInt(wild.size)]
            holder.press = if (it % 3 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * 2f
            holder.offsetX = wild[random.nextInt(wild.size)]
            holder.offsetY = wild[random.nextInt(wild.size)]
            holder.velocityX = wild[random.nextInt(wild.size)]
            holder.velocityY = wild[random.nextInt(wild.size)]
            holder.centerX = if (it % 5 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * viewWidth
            holder.centerY = if (it % 7 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * viewHeight
            val dt = if (it % 4 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * 0.2f
            val radius = if (it % 9 == 0) wild[random.nextInt(wild.size)] else radiusPx
            state.update(holder, dt, radius, 32, flowing)
            val s = state.shape
            assertTrue("centerX", s.centerX.isFinite())
            assertTrue("centerY", s.centerY.isFinite())
            assertTrue("radiusX", s.radiusX.isFinite() && s.radiusX >= 0f)
            assertTrue("radiusY", s.radiusY.isFinite() && s.radiusY >= 0f)
            assertTrue("alphaByte", s.alphaByte in 0..255)
            assertTrue("rotationDeg", s.rotationDeg.isFinite())
            if (s.pileUnit == 0f) assertEquals("流动模型轮廓内无取向", 0f, s.rotationDeg, 0f)
            assertEquals("流动模型无亮核前移", 0f, s.coreOffsetX, 0f)
        }
    }

    @Test fun resetReturnsToThePlainCircle() {
        val state = GlowState()
        val holder = GlowFrame()
        holder.boundsWidth = viewWidth; holder.boundsHeight = viewHeight; holder.cornerRadius = cornerPx
        repeat(40) {
            holder.press = 1f
            holder.offsetX = limitPx; holder.velocityX = velocityRefPx
            holder.centerX = viewWidth / 2f; holder.centerY = viewHeight / 2f
            state.update(holder, 1f / 120f, radiusPx, 32, flowing)
        }
        assertTrue(state.shape.radiusX > radiusPx * 1.2f)
        state.reset()
        assertFalse(state.shape.visible)
        holder.offsetX = 0f; holder.velocityX = 0f
        state.update(holder, 1f / 120f, radiusPx, 32, flowing)
        assertEquals(32, state.shape.alphaByte)
        assertEquals(radiusPx, state.shape.radiusX, radiusPx * 0.02f)
        assertEquals(0f, state.shape.rotationDeg, 0f)
    }
}
