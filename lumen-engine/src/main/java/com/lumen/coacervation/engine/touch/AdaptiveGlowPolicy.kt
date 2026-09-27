package com.lumen.coacervation.engine.touch

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * 一帧的光晕形状。单位 px，角度 deg（屏幕坐标，y 向下）。
 *
 * [radiusX]/[radiusY] 是椭圆半轴：渲染层按它们对基准半径做非均匀缩放（shader 只建一次），
 * 因此这里只输出标量，不碰任何 android.graphics 类型——整个文件可在 JVM 单测里直接跑。
 *
 * [coreOffsetX] 是亮核沿椭圆局部主轴（旋转前 +X，即形变方向）的前移量：形变越大光核越朝
 * 前进方向聚集，尾侧衰减更长——"彗星"效果。无方向（正圆）时恒为 0，避免无端偏心头。
 * **仅取向模型（`GlowConfig.oriented = true`）会写它**；流动模型下恒为 0。
 *
 * [rotationDeg] 同理：流动模型在轮廓内恒为 0（没有长轴就没有取向），形变完全由
 * [radiusX]/[radiusY] 的**等向**胀缩表达，运动由 [centerX]/[centerY] 的流动滞后表达。
 * 唯一例外是堆积（[pileUnit] > 0）：那时主轴转到轮廓法向——这是边的几何，不是手势方向。
 *
 * [pileUnit] 是"堆积量"：触点越出控件轮廓后，光晕不消失，而是钉在轮廓最近点、贴边
 * 压扁并增亮——越往外拖越聚集。两支模型共用，见 [GlowState.update] 的堆积段。
 */
public class GlowShape {
    var centerX = 0f
    var centerY = 0f
    var radiusX = 0f
    var radiusY = 0f
    var rotationDeg = 0f
    var coreOffsetX = 0f
    /** 局部坐标换基后的 Y 分量，防止形状过渡时把亮核跟着旋转。 */
    var coreOffsetY = 0f
    /** 0..1：触点越出轮廓的堆积程度（低通后）。0 = 在轮廓内。 */
    var pileUnit = 0f
    /** 0..约 3.4。乘各表面基础 alpha；不设 clamp——上界由构造保证（见 [GlowState.update]）。 */
    var alphaUnit = 0f
    var alphaByte = 0
    /** 唯一渲染门：alphaByte <= 0、半径为 0 或边界非法时不画。中间量归零全部连续，不另设开关。 */
    var visible = false

    fun reset() {
        centerX = 0f
        centerY = 0f
        radiusX = 0f
        radiusY = 0f
        rotationDeg = 0f
        coreOffsetX = 0f
        coreOffsetY = 0f
        pileUnit = 0f
        alphaUnit = 0f
        alphaByte = 0
        visible = false
    }
}

/** 每帧输入。全部允许 NaN/越界，输出必须有界。 */
public class GlowFrame {
    var press = 0f
    var offsetX = 0f
    var offsetY = 0f
    var velocityX = 0f
    var velocityY = 0f
    var centerX = 0f
    var centerY = 0f
    var boundsWidth = 0f
    var boundsHeight = 0f
    var cornerRadius = 0f
    /**
     * 越界方向上"控件边缘 → 屏幕边缘"的可触达空间（px），由 [reachablePileRoomPx] 计算。
     * +∞（默认）= 不限空间，堆积满额行程就是 pileRefPx；控件贴屏幕边缘时调用方喂入
     * 实际剩余空间，满额行程随之压缩——剩余空间内也能堆出完整"集中"。
     */
    var pileRoomPx = Float.POSITIVE_INFINITY
}

/**
 * 触点已越界的各轴上"控件边缘 → 屏幕边缘"的可触达距离（px），取最小值；
 * 触点仍在轮廓内时返回 +∞。斜向越界取两轴较小值（更近的屏幕缘先截断行程）。
 * 仅做几何运算，不依赖 android 类型——可在 JVM 单测里直接跑。
 */
public fun reachablePileRoomPx(
    viewScreenLeft: Int, viewScreenTop: Int, viewScreenRight: Int, viewScreenBottom: Int,
    screenWidth: Int, screenHeight: Int,
    touchLocalX: Float, touchLocalY: Float,
    boundsWidth: Float, boundsHeight: Float
): Float {
    var room = Float.POSITIVE_INFINITY
    if (touchLocalX < 0f) room = minOf(room, viewScreenLeft.toFloat())
    if (touchLocalX > boundsWidth) room = minOf(room, (screenWidth - viewScreenRight).toFloat())
    if (touchLocalY < 0f) room = minOf(room, viewScreenTop.toFloat())
    if (touchLocalY > boundsHeight) room = minOf(room, (screenHeight - viewScreenBottom).toFloat())
    return room.coerceAtLeast(0f)
}

/**
 * 每个光晕表面的调参。dp 值经 [create] 转成 px 后构造一次，draw 路径不再触碰。
 *
 * 两支几何模型由 [oriented] 选择，差异全部收敛到这里：
 * - **oriented = true**（底栏 / scrub 条）：沿手势方向拉成椭圆、亮核朝前进方向前移、
 *   尾迹沿反方向滞后，另含横向增亮。适合水平分段控件——拖动轴基本固定，取向不会反复翻。
 * - **oriented = false**（全局长按高亮）：**等向胀缩 + 位置流动**。光晕不朝向任何方向，
 *   形状只由"手势把控件拉离原位的程度"连续决定；运动由光心滞后追随触点来表达
 *   （泻在手指后方、反向时自然摆回）。**没有取向轴 ⇒ 方向快速反转时没有任何需要
 *   "转过来"的东西，反转天然平滑**——这正是长按拖动场景要的（用户 2026-09-21 指定：
 *   "形状可以根据手势超出边界的程度无极变化，让光效流动起来，而不是一个形状不断变化方向"）。
 */
public class GlowConfig(
    val velocityEpsPxPerSec: Float,
    val travelEpsPx: Float,
    val maxTravelPx: Float,
    val velocityRefPxPerSec: Float,
    val edgeBandPx: Float,
    val tailMaxPx: Float,
    val axialBoost: Float,
    val coreShiftMaxPx: Float,
    val oriented: Boolean = true,
    val pileRefPx: Float = 0f,
    val pileGain: Float = PILE_GAIN,
    /** 底栏：保持贴边前的光团尺度，直接过渡到聚拢，避免先淡出缩小再恢复。 */
    val continuousEdgePile: Boolean = false
) {
    companion object {
        const val VELOCITY_EPS_DP_PER_SEC = 20f
        const val VELOCITY_REF_DP_PER_SEC = 240f
        const val EDGE_BAND_DP = 28f
        const val TAIL_MAX_DP = 10f
        /**
         * 亮核前移上限（dp）。形变满额时光核沿形变方向前移这么多：亮核本身半径约
         * 0.22R（14dp @64dp 基准），前移同量级刚好"探出头"，尾侧留下更长衰减。
         * 与形变量成正比——越变形越聚集；无方向时策略层直接给 0。
         */
        const val CORE_SHIFT_MAX_DP = 14f
        /**
         * 位移方向的"有方向"闸门。**不是 touchSlop**：offset 是渲染后的（带阻力、已钳制的）
         * 位移，手指静止时它恰为 0；而底栏行程上限 4dp 本身就小于 slop，用 slop 会永久关闭
         * 位移方向源。0.5dp 足以把"弹簧收敛残余的亚像素抖动"挡掉。
         */
        const val TRAVEL_EPS_DP = 0.5f
        const val AXIAL_BLEND_LOW = 1.0f
        const val AXIAL_BLEND_HIGH = 1.15f
        const val AXIAL_BOOST = 0.18f
        const val SAT_GAIN = 0.30f
        const val SPD_GAIN = 0.15f
        const val PRESS_SHARE = 0.55f
        const val STRETCH_MAX = 0.45f
        /**
         * 贴边收缩下限。**必须等于 [PILE_RECOVER]**（由 `edgeFloorEqualsThePileTarget`
         * 测试钉住）：越界堆积会把光团尺度收敛到 PILE_RECOVER·R，本值更低 ⇒ 跨界瞬间
         * 等效半径先跌到下限、再由堆积拉回——正是"刚出界先缩小、再被集中放大"的 V 形
         * 割裂（用户 2026-09-21 反馈）。相等 ⇒ 尺寸恰在轮廓处连续：界内 1.0→0.70
         * 单调收缩，界外定住，堆积只改形状与亮度。
         */
        const val EDGE_MIN_SCALE = 0.70f
        /**
         * 贴边 alpha 下限。旧实现在轮廓处衰减到 0，触点一出控件光晕就消失——正是要改掉的
         * 现象（用户 2026-09-21："手势完全超出底栏它就不显示了"）。轮廓外的半个光晕由
         * clipToOutline/clipPath 裁掉，本就只剩一半亮度，这里只需留住另一半的可读性。
         */
        const val EDGE_ALPHA_FLOOR = 0.62f
        /** 堆积参考距离（dp）：触点越出轮廓这么远时堆积满额。 */
        const val PILE_REF_DP = 72f
        /** 堆积满额时的增亮：alpha 乘 (1 + PILE_GAIN·pile)。 */
        const val PILE_GAIN = 0.95f
        /**
         * 堆积满额时的形变比：沿轮廓切向铺开 ×(1 + PILE_SPREAD)，沿法向同时压扁 ÷(1 + PILE_SPREAD)。
         * 面积不变，所以 rx·ry ≤ r² 的渲染护栏不受影响。
         */
        const val PILE_SPREAD = 0.45f
        /**
         * 堆积收敛目标：光团尺度（等效半径 iso = √rx·ry）向 R·PILE_RECOVER 收敛。
         * 取向模型跨界前已由 EDGE_MIN_SCALE（同值）落在这个尺度上，堆积段只换形状；
         * 流动模型界内半径更大（≈R·(1+0.45·stretch)），堆积时同步收小——"集中"
         * 必须读得出聚拢，不能只靠压扁与增亮。
         */
        const val PILE_RECOVER = 0.70f
        /** 堆积量低通（秒）：跨过轮廓瞬间与手指抖动都不该让形状跳变。 */
        const val TAU_PILE_SECONDS = 0.09f
        /**
         * 堆积满额行程低通（秒）。span 由"已越界轴集合的可触达空间 min"决定——弧形路径上
         * 第二根轴越界/回界的瞬间集合成员变化会让 span 硬跳（如 216→40→216），pileTarget
         * 跟着跳、靠 pileEma 快爬，读作一次强度跳变（用户 2026-09-21 抓帧实证）。span 本身
         * 先低通，集合切换与 room 变化都铺成平滑滑动。
         */
        const val TAU_PILE_SPAN_SECONDS = 0.14f
        const val TAU_SPEED_SECONDS = 0.060f
        const val TAU_DIR_SECONDS = 0.025f
        const val TAU_TAIL_SECONDS = 0.040f
        const val TAU_INTENSITY_SECONDS = 0.050f
        const val TAU_AXIAL_SECONDS = 0.030f
        /**
         * 行程量低通（秒）。**方向反转时位移会穿过 0**——元素确实在那一瞬回到原位，
         * 但用瞬时值驱动形变与亮度会让光晕每次反转都"塌一下再长回来"：实测单帧
         * alpha 掉 34%、半径收 6%，来回甩动时就是可见的频闪。低通过后反转变成一次
         * 约 150ms 的平滑呼吸。取 90ms：远短于一次手势，又足够跨过零点。
         */
        const val TAU_TRAVEL_SECONDS = 0.090f
        /**
         * 光心流动滞后（秒）。仅 `oriented = false` 的光晕使用：光心以低通追随触点，
         * 静止时精确落在触点（滞后为 0），一动就"泻"在手指后方，反向时位置量自然摆回。
         * 取 60ms：以典型 500px/s 的拖动速度算约 30px 的拖曳感，足以读出"流动"，
         * 又不至于让高光离开触点太远。
         */
        const val TAU_FLOW_SECONDS = 0.060f
        /**
         * 等向胀缩上限（仅 `oriented = false`）。光晕半径随"控件被拉离原位的程度"
         * 连续胀大这么多：静止是紧凑的一团，拉到底是摊开的一片。与取向模型的
         * [STRETCH_MAX] 同量级，但两个半轴同步——没有长轴就没有取向。
         */
        const val SWELL_MAX = 0.45f
        /**
         * 方向转速上限（度/秒）。只钳转速不钳位置：没有它，方向反转时 EMA 会把
         * k·180° 一帧内搬过来（实测 ≈51°/帧 @120Hz）；有了它，一次 180° 反转铺成
         * 约 333ms 的平滑扫掠。首帧从"无方向"落下不受限（那时还是正圆）。
         * 仅 `oriented = true` 的模型使用。
         */
        const val MAX_ANGULAR_SPEED_DEG_PER_SEC = 540f

        fun create(
            density: Float,
            maxTravelPx: Float,
            travelEpsPx: Float,
            velocityRefPxPerSec: Float,
            edgeBandPx: Float,
            axialBoost: Float = AXIAL_BOOST,
            oriented: Boolean = true,
            pileRefDp: Float = PILE_REF_DP,
            pileGain: Float = PILE_GAIN,
            continuousEdgePile: Boolean = false
        ): GlowConfig {
            val scale = if (density.isFinite() && density > 0f) density else 1f
            return GlowConfig(
                velocityEpsPxPerSec = VELOCITY_EPS_DP_PER_SEC * scale,
                travelEpsPx = travelEpsPx,
                maxTravelPx = maxTravelPx,
                velocityRefPxPerSec = velocityRefPxPerSec,
                edgeBandPx = edgeBandPx,
                tailMaxPx = TAIL_MAX_DP * scale,
                axialBoost = axialBoost,
                coreShiftMaxPx = CORE_SHIFT_MAX_DP * scale,
                oriented = oriented,
                pileRefPx = pileRefDp * scale,
                pileGain = pileGain,
                continuousEdgePile = continuousEdgePile
            )
        }
    }
}

/**
 * 单个光晕表面的全部可变状态：EMA 历史 + 每帧写出的 [shape]。
 *
 * 调用方持有并复用（每个表面一个），update 全程零分配——EMA 状态不放纯函数，
 * 单测可以逐帧喂入验证收敛，也不需要为它造 android.graphics。
 */
public class GlowState {
    private var angleDeg = Float.NaN
    private var unitX = Float.NaN
    private var unitY = Float.NaN
    private var speedEma = 0f
    private var driveEma = 0f
    private var axialEma = 0f
    private var travelEma = 0f
    // 光心流动位置（NaN = 尚未初始化，下一帧 snapping 到触点）。
    // 仅 oriented = false 的模型使用。
    private var flowX = Float.NaN
    private var flowY = Float.NaN
    private var tailLengthEma = 0f
    private var tailVecX = 0f
    private var tailVecY = 0f
    private var pileEma = 0f
    private var pileSpanEma = Float.NaN
    private val normal = FloatArray(2)

    val shape = GlowShape()

    /**
     * 只准在不可见边界调用（press == 0 的那一帧，或 surface 销毁）。
     * 打断续航时**不许** reset：EMA 靠 dt 归一化低通自然收敛，reset 会把可见的椭圆
     * 瞬间掰回正圆。
     */
    fun reset() {
        angleDeg = Float.NaN
        unitX = Float.NaN
        unitY = Float.NaN
        speedEma = 0f
        driveEma = 0f
        axialEma = 0f
        travelEma = 0f
        flowX = Float.NaN
        flowY = Float.NaN
        tailLengthEma = 0f
        tailVecX = 0f
        tailVecY = 0f
        pileEma = 0f
        pileSpanEma = Float.NaN
        shape.reset()
    }

    /**
     * 连续域主映射。所有输入→输出都是 C¹ 函数；钳制只出现在 NaN/非法输入的归零处
     * （NaN 不构成连续路径）。dt 由调用方按真实帧间隔传入，低通系数随之归一化，
     * 60/90/120Hz 是同一条曲线。
     */
    fun update(frame: GlowFrame, dtSeconds: Float, radiusPx: Float, baseAlpha: Int, config: GlowConfig) {
        val dt = if (dtSeconds.isFinite()) dtSeconds.coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS) else DEFAULT_DT_SECONDS
        val press = finiteOrZero(frame.press).coerceAtLeast(0f)
        val offsetX = finiteOrZero(frame.offsetX)
        val offsetY = finiteOrZero(frame.offsetY)
        val velocityX = finiteOrZero(frame.velocityX)
        val velocityY = finiteOrZero(frame.velocityY)
        val centerX = finiteOrZero(frame.centerX)
        val centerY = finiteOrZero(frame.centerY)
        val boundsWidth = finiteOrZero(frame.boundsWidth)
        val boundsHeight = finiteOrZero(frame.boundsHeight)
        val corner = finiteOrZero(frame.cornerRadius).coerceAtLeast(0f)
        val radius = if (radiusPx.isFinite()) radiusPx.coerceIn(0f, MAX_RADIUS_PX) else 0f
        val alphaScale = if (baseAlpha > 0) baseAlpha else 0

        // 速度低通：事件采样的速度必须低通，否则方向与强度都跟着采样噪声抖。
        val speedRaw = length(velocityX, velocityY)
        speedEma += (speedRaw - speedEma) * blendFactor(dt, GlowConfig.TAU_SPEED_SECONDS)

        // 方向：速度/位移单位向量连续混合（blend 也是 smoothStep），角度空间 dt 归一化 EMA。
        val travelRaw = length(offsetX, offsetY)
        val velocityUnitValid = speedRaw > UNIT_EPS
        val displacementUnitValid = travelRaw > config.travelEpsPx
        var targetUnitX: Float
        var targetUnitY: Float
        if (velocityUnitValid && displacementUnitValid) {
            val blend = smoothStep(
                config.velocityEpsPxPerSec * 0.5f,
                config.velocityEpsPxPerSec * 1.5f,
                speedEma
            )
            val mixedX = offsetX / travelRaw + (velocityX / speedRaw - offsetX / travelRaw) * blend
            val mixedY = offsetY / travelRaw + (velocityY / speedRaw - offsetY / travelRaw) * blend
            val mixedLength = length(mixedX, mixedY)
            if (mixedLength < UNIT_EPS) {
                // 两个源近似反向：lerp 会退化成零向量，保持上一帧方向。
                targetUnitX = unitX
                targetUnitY = unitY
            } else {
                targetUnitX = mixedX / mixedLength
                targetUnitY = mixedY / mixedLength
            }
        } else if (velocityUnitValid) {
            targetUnitX = velocityX / speedRaw
            targetUnitY = velocityY / speedRaw
        } else if (displacementUnitValid) {
            targetUnitX = offsetX / travelRaw
            targetUnitY = offsetY / travelRaw
        } else {
            // 无任何方向信号：保持上一帧（首帧为 NaN = 正圆）。
            targetUnitX = unitX
            targetUnitY = unitY
        }
        val targetAngle = if (targetUnitX.isFinite() && targetUnitY.isFinite()) {
            toDegrees(atan2(targetUnitY.toDouble(), targetUnitX.toDouble())).toFloat()
        } else {
            Float.NaN
        }
        if (targetAngle.isFinite()) {
            val k = blendFactor(dt, GlowConfig.TAU_DIR_SECONDS)
            val previousAngle = angleDeg
            angleDeg = if (previousAngle.isFinite()) {
                val delta = shortestAngleDeltaDeg(previousAngle, targetAngle)
                val maxStep = GlowConfig.MAX_ANGULAR_SPEED_DEG_PER_SEC * dt
                // 唯一的速率护栏：方向反转时 EMA 会把 k·180°（≈51°/帧 @120Hz）一帧内搬过来，
                // 现场是椭圆轴自旋四分之一圈。位置仍然连续（无跳变），被钳的只是转速，
                // 且随 dt 归一化——60/120Hz 分别是每秒同样的角速度上限。
                previousAngle + (delta * k).coerceIn(-maxStep, maxStep)
            } else {
                // 首帧从"无方向"落到目标角：此前是正圆（旋转不可见），不构成可见跳变，不受护栏。
                targetAngle
            }
            val radians = toRadians(angleDeg.toDouble())
            unitX = cos(radians).toFloat()
            unitY = sin(radians).toFloat()
        }

        // 强度：静止时恒等于现状（press × baseAlpha），三个增量项各自连续、互不钳制。
        // 行程量先过低通：方向反转时位移穿过 0，瞬时值会让形变与亮度每次反转都
        // 塌一下再长回来（实测单帧 alpha -34%、半径 -6%）。低通后反转是一次平滑呼吸。
        travelEma += (travelRaw - travelEma) * blendFactor(dt, GlowConfig.TAU_TRAVEL_SECONDS)
        val saturation = smoothStep(0f, config.maxTravelPx, travelEma)
        val speedFraction = smoothStep(0f, config.velocityRefPxPerSec, speedEma)
        val driveRaw = saturation + speedFraction - saturation * speedFraction
        driveEma += (driveRaw - driveEma) * blendFactor(dt, GlowConfig.TAU_INTENSITY_SECONDS)
        // 方向性增亮以 speedFraction 为闸门：boost 随速度连续爬升，"从静止到一动"无跳变
        // （窄带闸门会被一帧跨过——speedEma 在猛甩一帧可移动约 88px/s，跳变只是搬了家；
        // 帧间连续性测试两次实战抓住了这两种形态）。
        val axial = if (config.oriented && angleDeg.isFinite()) {
            val radians = toRadians(angleDeg.toDouble())
            smoothStep(
                GlowConfig.AXIAL_BLEND_LOW,
                GlowConfig.AXIAL_BLEND_HIGH,
                abs(cos(radians).toFloat()) / maxOf(abs(sin(radians).toFloat()), ANGLE_UNIT_EPS)
            ) * speedFraction
        } else {
            0f
        }
        // 派生视觉量一律过低通：方向反转扫掠经过 45° 时，轴向比一帧内可跨过整个混合带，
        // 不低通就是约 19/255 的帧间跳变。
        axialEma += (axial - axialEma) * blendFactor(dt, GlowConfig.TAU_AXIAL_SECONDS)
        var alphaUnit = press *
            (1f + GlowConfig.SAT_GAIN * saturation) *
            (1f + GlowConfig.SPD_GAIN * speedFraction) *
            (1f + config.axialBoost * axialEma)

        // 形变量：起拖瞬间从 0 连续长出（latent 拉伸会在方向出现那一帧一步到位）。
        // 两支模型共用同一个标量，只是用法不同。
        val stretch = driveEma * (GlowConfig.PRESS_SHARE + (1f - GlowConfig.PRESS_SHARE) * press)
        var radiusX: Float
        var radiusY: Float
        var coreOffsetX = 0f
        var coreOffsetY = 0f
        var renderX: Float
        var renderY: Float
        if (config.oriented) {
            // 取向模型：沿手势方向拉成椭圆（面积守恒），亮核前移与尾迹见下。
            radiusX = radius * (1f + GlowConfig.STRETCH_MAX * stretch)
            radiusY = if (radiusX > RADIUS_EPS) radius * radius / radiusX else radius
            renderX = centerX
            renderY = centerY
        } else {
            // 流动模型：**等向胀缩**。两个半轴同步，没有长轴 ⇒ 没有取向需要"转过来"。
            // 形状只由 stretch（即手势把控件拉离原位的程度）连续决定——无极变化。
            val swell = radius * (1f + GlowConfig.SWELL_MAX * stretch)
            radiusX = swell
            radiusY = swell
            // **位置流动**：光心以低通追随触点。静止时精确落在触点（滞后为 0），
            // 一动就泻在手指后方；反向时这是个纯位置量，自然摆回，不存在需要转向的轴。
            if (!flowX.isFinite()) flowX = centerX
            if (!flowY.isFinite()) flowY = centerY
            flowX += (centerX - flowX) * blendFactor(dt, GlowConfig.TAU_FLOW_SECONDS)
            flowY += (centerY - flowY) * blendFactor(dt, GlowConfig.TAU_FLOW_SECONDS)
            renderX = flowX
            renderY = flowY
        }

        // 边缘柔化：到圆角轮廓的精确距离（rounded-rect SDF），alpha 与尺寸双衰减。
        // alpha 只衰减到 EDGE_ALPHA_FLOOR 而不是 0：轮廓外的部分由裁剪负责，触点贴边/越界
        // 时光晕仍在。edgeBandPx <= 0 的表面（长按高亮）由 clipPath 负责轮廓裁剪，此因子
        // 必须直通——smoothStep(0,0,·) 是 0 处的硬阶跃。全圆角胶囊（r = h/2）最敏感：
        // 矩形四角本就在胶囊轮廓外。
        val signedDistance = roundedRectSignedDistance(centerX, centerY, boundsWidth, boundsHeight, corner)
        val inside = if (config.edgeBandPx > 0f) smoothStep(0f, config.edgeBandPx, -signedDistance) else 1f
        val edge = if (config.continuousEdgePile) 1f
            else GlowConfig.EDGE_ALPHA_FLOOR + (1f - GlowConfig.EDGE_ALPHA_FLOOR) * inside
        val edgeScale = if (config.continuousEdgePile) 1f
            else GlowConfig.EDGE_MIN_SCALE + (1f - GlowConfig.EDGE_MIN_SCALE) * inside
        radiusX *= edgeScale
        radiusY *= edgeScale
        alphaUnit *= edge

        // 堆积：触点越出轮廓的距离经 smoothStep 与低通得到 0..1 的堆积量。
        // 边界有效且 pileRefPx > 0 才算；否则恒为 0，下面所有堆积项都退化为直通。
        val overshoot = if (boundsWidth > 0f && boundsHeight > 0f) signedDistance.coerceAtLeast(0f) else 0f
        // 满额行程按可触达空间压缩：控件贴屏幕边缘时手指走不满 pileRefPx，
        // 剩多少空间就用多少——贴边控件照样堆出完整"集中"，强弱不再随可用空间忽变
        // （用户 2026-09-21：贴右侧屏缘拖拽时光效强弱随行程上限忽强忽弱）。
        val room = if (frame.pileRoomPx.isFinite()) frame.pileRoomPx.coerceAtLeast(0f) else Float.POSITIVE_INFINITY
        val spanTarget = minOf(config.pileRefPx, room).coerceAtLeast(MIN_PILE_SPAN_PX)
        // span 过低通：room 由"已越界轴集合"的 min 给出，第二根轴越界/回界时集合成员
        // 变化会让 span 硬跳（弧形路径上每周期 216↔40 实测），pileTarget 随之跳变。
        if (!pileSpanEma.isFinite()) pileSpanEma = spanTarget
        pileSpanEma += (spanTarget - pileSpanEma) * blendFactor(dt, GlowConfig.TAU_PILE_SPAN_SECONDS)
        val pileTarget = if (config.pileRefPx > 0f) smoothStep(0f, pileSpanEma, overshoot) else 0f
        pileEma += (pileTarget - pileEma) * blendFactor(dt, GlowConfig.TAU_PILE_SECONDS)
        val pile = pileEma.coerceIn(0f, 1f)

        if (config.oriented) {
            // 亮核前移：与形变量成正比（越变形越朝形变方向聚集），无方向时为 0——否则正圆
            // 光晕会无端偏心头。与半径同样乘 edgeScale，贴边衰减时整体一致收缩。
            // 注意：这里**不需要**为"方向反转"加任何额外收敛——180° 反转对水平椭圆
            // 是视觉上的同一形状，而底栏/scrub 条的拖动轴本就固定在水平方向。
            // 长按高亮走的是流动模型（无亮核前移），彗头甩动问题在那边从结构上不存在。
            coreOffsetX = if (angleDeg.isFinite()) {
                config.coreShiftMaxPx * stretch * edgeScale
            } else {
                0f
            }

            // 拖尾：沿运动反方向滞后，长度与分量各一个 dt 归一化 EMA。
            val tailTarget = smoothStep(0f, config.velocityRefPxPerSec, speedEma) * config.tailMaxPx
            tailLengthEma += (tailTarget - tailLengthEma) * blendFactor(dt, GlowConfig.TAU_TAIL_SECONDS)
            val tailDirX = if (angleDeg.isFinite()) unitX else 0f
            val tailDirY = if (angleDeg.isFinite()) unitY else 0f
            tailVecX += (-tailDirX * tailLengthEma - tailVecX) * blendFactor(dt, GlowConfig.TAU_TAIL_SECONDS)
            tailVecY += (-tailDirY * tailLengthEma - tailVecY) * blendFactor(dt, GlowConfig.TAU_TAIL_SECONDS)
            renderX = centerX + tailVecX
            renderY = centerY + tailVecY
        }

        var rotationDeg = if (config.oriented && angleDeg.isFinite()) angleDeg else 0f
        if (boundsWidth > 0f && boundsHeight > 0f) {
            // 光心绝不被推出边界；band 大于边界时退化为夹在中心。
            renderX = renderX.coerceIn(
                minOf(config.edgeBandPx, boundsWidth * 0.5f),
                maxOf(boundsWidth - config.edgeBandPx, boundsWidth * 0.5f)
            )
            renderY = renderY.coerceIn(
                minOf(config.edgeBandPx, boundsHeight * 0.5f),
                maxOf(boundsHeight - config.edgeBandPx, boundsHeight * 0.5f)
            )
            if (pile > 0f) {
                // 堆积：光心从内侧夹持位置滑到轮廓最近点，椭圆转到以轮廓法向为主轴、
                // 沿法向压扁、沿切向铺开，并整体增亮。全部按 pile 线性混合，跨界连续。
                roundedRectOutwardNormal(centerX, centerY, boundsWidth, boundsHeight, corner, normal)
                val pinnedX = finiteOr(centerX - signedDistance * normal[0], centerX).coerceIn(0f, boundsWidth)
                val pinnedY = finiteOr(centerY - signedDistance * normal[1], centerY).coerceIn(0f, boundsHeight)
                renderX += (pinnedX - renderX) * pile
                renderY += (pinnedY - renderY) * pile
                val normalDeg = toDegrees(atan2(normal[1].toDouble(), normal[0].toDouble())).toFloat()
                if (!config.continuousEdgePile) rotationDeg += shortestAxisDeltaDeg(rotationDeg, normalDeg) * pile
                if (radiusX > 0f && radiusY > 0f) {
                    // 尺寸与长短轴比分开算：面积 = iso²（iso ≤ 基准半径），长短轴比在对数空间里
                    // 从当前值滑向 1/spreadFactor——沿法向压扁、沿切向铺开。
                    var iso = sqrt(radiusX) * sqrt(radiusY)
                    // 双向收敛到 PILE_RECOVER·R：比目标小的（取向模型旧路径）撑起，
                    // 比目标大的（流动模型界内光团）收拢——两个方向都是单调收敛，无回涨。
                    val recovery = if (config.continuousEdgePile) 1f else GlowConfig.PILE_RECOVER
                    iso += (radius * recovery - iso) * pile
                    val spreadFactor = 1f + GlowConfig.PILE_SPREAD * pile
                    if (config.continuousEdgePile) {
                        // 在同一坐标系混合对数形状（迹为0 => 面积守恒），不能先转到法线再换长短轴。
                        // 水平光团贴上/下边时起终长轴都是水平，旧角度插值却会在中途扫过斜角。
                        val relative = toRadians((normalDeg - rotationDeg).toDouble())
                        val from = ln(radiusX / radiusY) * 0.5f
                        val to = -ln(spreadFactor)
                        val xx = from * (1f - pile) + to * cos(2.0 * relative).toFloat() * pile
                        val xy = to * sin(2.0 * relative).toFloat() * pile
                        val magnitude = sqrt(xx * xx + xy * xy)
                        val turn = if (magnitude > 1e-6f) 0.5 * atan2(xy.toDouble(), xx.toDouble()) else 0.0
                        val aniso = exp(magnitude)
                        radiusX = iso * aniso
                        radiusY = iso / aniso
                        rotationDeg += toDegrees(turn).toFloat()
                        // 只换形状坐标系；亮核的屏幕方向不变，继续按原轨迹向中心收拢。
                        coreOffsetY = -coreOffsetX * sin(turn).toFloat()
                        coreOffsetX *= cos(turn).toFloat()
                    } else {
                        val aniso = sqrt(radiusX / radiusY).coerceIn(1e-3f, 1e3f).pow(1f - pile) / spreadFactor
                        radiusX = iso * aniso
                        radiusY = iso / aniso
                    }
                }
                coreOffsetX *= 1f - pile
                coreOffsetY *= 1f - pile
                // 去掉贴边衰减后保持旧的满额亮度，避免将尺度修复变成额外的强光。
                val pileGain = if (config.continuousEdgePile)
                    (GlowConfig.EDGE_ALPHA_FLOOR * (1f + config.pileGain) - 1f).coerceAtLeast(0f)
                else config.pileGain
                alphaUnit *= 1f + pileGain * pile
            }
        }

        val alphaByte = (alphaUnit * alphaScale).roundToInt().coerceIn(0, 255)
        shape.centerX = renderX
        shape.centerY = renderY
        shape.radiusX = radiusX
        shape.radiusY = radiusY
        shape.rotationDeg = rotationDeg
        shape.coreOffsetX = coreOffsetX
        shape.coreOffsetY = coreOffsetY
        shape.pileUnit = pile
        shape.alphaUnit = alphaUnit
        shape.alphaByte = alphaByte
        shape.visible = alphaByte > 0 && radius > RADIUS_EPS && boundsWidth > 0f && boundsHeight > 0f
    }

    companion object {
        const val DEFAULT_DT_SECONDS = 1f / 120f
        const val MIN_DT_SECONDS = 0.001f
        const val MAX_DT_SECONDS = 0.1f
        const val MAX_RADIUS_PX = 1e6f
        private const val UNIT_EPS = 1e-4f
        private const val ANGLE_UNIT_EPS = 1e-4f
        private const val RADIUS_EPS = 1e-3f
        /** 可触达空间为 0 时 smoothStep 的退化护栏；此时越界物理上不可达，任意小值即可。 */
        private const val MIN_PILE_SPAN_PX = 0.5f
    }
}

/** 与 SettingsBackupMotionSpec.smoothStep 同式的三次平滑：两端一阶导为 0，能吸收上游折点。 */
internal fun smoothStep(edgeStart: Float, edgeEnd: Float, value: Float): Float {
    if (edgeStart >= edgeEnd) return if (value < edgeStart) 0f else 1f
    val fraction = ((value - edgeStart) / (edgeEnd - edgeStart)).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}

/** dt 归一化的低通系数：写死每帧系数会让 60Hz 与 120Hz 成为两条曲线。 */
internal fun blendFactor(dtSeconds: Float, tauSeconds: Float): Float {
    if (!dtSeconds.isFinite() || !tauSeconds.isFinite() || tauSeconds <= 0f) return 1f
    if (dtSeconds <= 0f) return 0f
    return 1f - exp(-dtSeconds / tauSeconds)
}

/** 角度差取 ±180° 短边：方向反向时椭圆转过短弧，不翻烧饼。 */
internal fun shortestAngleDeltaDeg(fromDeg: Float, toDeg: Float): Float {
    if (!fromDeg.isFinite() || !toDeg.isFinite()) return 0f
    var delta = (toDeg - fromDeg) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}

/** 椭圆轴的最短角差：轴无方向（θ ≡ θ+180°），差取到 ±90°。 */
internal fun shortestAxisDeltaDeg(fromDeg: Float, toDeg: Float): Float {
    if (!fromDeg.isFinite() || !toDeg.isFinite()) return 0f
    var delta = (toDeg - fromDeg) % 180f
    if (delta > 90f) delta -= 180f
    if (delta < -90f) delta += 180f
    return delta
}

/**
 * 圆角矩形 SDF 的外法向（单位向量），写入 [out]。轮廓外指向远离轮廓，内部指向最近边。
 * 角区（q 非零）取到角圆心的方向；边区取 px/py 较大者对应的轴向；正中心退化为 +Y。
 */
internal fun roundedRectOutwardNormal(x: Float, y: Float, w: Float, h: Float, r: Float, out: FloatArray) {
    if (w <= 0f || h <= 0f) { out[0] = 0f; out[1] = 1f; return }
    val radius = r.coerceIn(0f, minOf(w, h) * 0.5f)
    val dx = x - w * 0.5f
    val dy = y - h * 0.5f
    val sx = if (dx < 0f) -1f else 1f
    val sy = if (dy < 0f) -1f else 1f
    val px = abs(dx) - (w * 0.5f - radius)
    val py = abs(dy) - (h * 0.5f - radius)
    val qx = maxOf(px, 0f)
    val qy = maxOf(py, 0f)
    val q = length(qx, qy)
    if (q > 1e-4f && q.isFinite()) {
        out[0] = sx * qx / q
        out[1] = sy * qy / q
    } else if (px > py) {
        out[0] = sx; out[1] = 0f
    } else {
        out[0] = 0f; out[1] = sy
    }
}

/**
 * 圆角矩形有符号距离（SDF 惯例：内部为负、边界为 0、外部为正）。
 * 约 12 flop，per frame 无压力；[r] <= 0 时退化为矩形。
 */
internal fun roundedRectSignedDistance(x: Float, y: Float, w: Float, h: Float, r: Float): Float {
    if (w <= 0f || h <= 0f) return 0f
    val radius = r.coerceIn(0f, minOf(w, h) * 0.5f)
    val px = abs(x - w * 0.5f) - (w * 0.5f - radius)
    val py = abs(y - h * 0.5f) - (h * 0.5f - radius)
    val qx = maxOf(px, 0f)
    val qy = maxOf(py, 0f)
    return length(qx, qy) + minOf(maxOf(px, py), 0f) - radius
}

private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f

private fun finiteOr(value: Float, fallback: Float): Float = if (value.isFinite()) value else fallback

private fun length(x: Float, y: Float): Float = sqrt(x * x + y * y)

private fun toDegrees(value: Double): Double = value * 180.0 / PI

private fun toRadians(value: Double): Double = value * PI / 180.0
