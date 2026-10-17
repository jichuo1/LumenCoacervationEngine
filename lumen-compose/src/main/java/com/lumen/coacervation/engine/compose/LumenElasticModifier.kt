package com.lumen.coacervation.engine.compose

import android.animation.ValueAnimator
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import com.lumen.coacervation.engine.interaction.ElasticGestureDecision
import com.lumen.coacervation.engine.interaction.ElasticGestureGate
import com.lumen.coacervation.engine.interaction.ElasticMotionPolicy
import com.lumen.coacervation.engine.motion.LumenSpringSpec
import com.lumen.coacervation.engine.interaction.ElasticSpringAxis
import com.lumen.coacervation.engine.interaction.ElasticVector
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-hold elastic dragging using the engine's View gesture and spring policies.
 * Place this before the surface/background modifiers so material and foreground share one layer.
 * Ordinary clicks and scrolls retain their pointer events; only captured movement is consumed.
 *
 * Pass [enabled] = false for text editors, sliders and controls with a business long-press action.
 * Do not wrap those controls in an enabled elastic parent: disabling a child does not disable its
 * ancestors. Ancestors must also allow the desired overflow; this modifier's layer does not clip.
 */
public fun Modifier.lumenElastic(enabled: Boolean = true): Modifier = then(ElasticElement(enabled))

private data class ElasticElement(val enabled: Boolean) : ModifierNodeElement<ElasticNode>() {
    override fun create(): ElasticNode = ElasticNode(enabled)
    override fun update(node: ElasticNode) = node.updateEnabled(enabled)
    override fun InspectorInfo.inspectableProperties() {
        name = "lumenElastic"
        properties["enabled"] = enabled
    }
}

private class ElasticNode(private var enabled: Boolean) : Modifier.Node(),
    PointerInputModifierNode, LayoutModifierNode, CompositionLocalConsumerModifierNode, UnplacedAwareModifierNode {
    private val gate = ElasticGestureGate()
    private val xAxis = ElasticSpringAxis()
    private val yAxis = ElasticSpringAxis()
    private val pressAxis = ElasticSpringAxis()
    private val drag = ElasticVector()
    private val scale = ElasticVector(1f, 1f)
    private var width = 0
    private var height = 0
    private var pixelDensity = 1f
    private var limit = 0f
    private var slop = 0f
    private var pointerId = NO_POINTER
    private var distanceX = 0f
    private var distanceY = 0f
    private var grabbedX = 0f
    private var grabbedY = 0f
    private var lastMoveTime = 0L
    private var captured = false
    private var consumedInMain = false
    private var animation: Job? = null
    private var animationGeneration = 0L
    private var renderedX by mutableFloatStateOf(0f)
    private var renderedY by mutableFloatStateOf(0f)
    private var renderedPress by mutableFloatStateOf(0f)

    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        ElasticMotionPolicy.scale(renderedPress, renderedX, renderedY, limit, scale)
        translationX = renderedX
        translationY = renderedY
        scaleX = scale.x
        scaleY = scale.y
        clip = false
    }

    fun updateEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!enabled) resetMotion()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        width = placeable.width
        height = placeable.height
        pixelDensity = density
        limit = ElasticMotionPolicy.positionLimit(width, height, pixelDensity)
        return layout(width, height) {
            if (enabled) placeable.placeWithLayer(0, 0, layerBlock = layerBlock)
            else placeable.place(0, 0)
        }
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (!enabled || !isAttached || pass == PointerEventPass.Initial) return
        if (!ValueAnimator.areAnimatorsEnabled()) { resetMotion(); return }
        if (pointerEvent.changes.size != 1) {
            resetMotion()
            return
        }
        val change = pointerEvent.changes[0]
        if (pass == PointerEventPass.Final) {
            if (pointerId == change.id.value && !consumedInMain && change.isConsumed && hasMovement(change))
                resetMotion()
            return
        }
        consumedInMain = false
        if (!change.previousPressed && change.pressed) {
            begin(change)
            return
        }
        if (pointerId != change.id.value) return
        if (!change.pressed) {
            pointerId = NO_POINTER
            gate.yield()
            if (captured && change.uptimeMillis - lastMoveTime > 80L) {
                xAxis.velocity = 0f
                yAxis.velocity = 0f
            }
            captured = false
            animate(pressed = false)
            return
        }
        if (!hasMovement(change)) return
        if (change.isConsumed) {
            resetMotion()
            return
        }
        var decision = ElasticGestureDecision.OBSERVE
        // Both positions use this event's coordinates, so ancestor translations do not add drag.
        for (history in change.historical) {
            decision = gate.move(history.uptimeMillis,
                distanceX + history.position.x - change.previousPosition.x,
                distanceY + history.position.y - change.previousPosition.y, slop)
            if (decision == ElasticGestureDecision.YIELD) break
        }
        distanceX += change.position.x - change.previousPosition.x
        distanceY += change.position.y - change.previousPosition.y
        if (decision != ElasticGestureDecision.YIELD)
            decision = gate.move(change.uptimeMillis, distanceX, distanceY, slop)
        if (decision == ElasticGestureDecision.YIELD) {
            resetMotion()
            return
        }
        if (decision != ElasticGestureDecision.CAPTURE && decision != ElasticGestureDecision.DRAG) return
        captured = true
        ElasticMotionPolicy.dragFrom(grabbedX, grabbedY, distanceX, distanceY, slop, limit, drag)
        val elapsed = change.uptimeMillis - lastMoveTime
        xAxis.velocity = ElasticMotionPolicy.releaseVelocity(xAxis.value, drag.x, elapsed, limit)
        yAxis.velocity = ElasticMotionPolicy.releaseVelocity(yAxis.value, drag.y, elapsed, limit)
        xAxis.value = drag.x
        yAxis.value = drag.y
        lastMoveTime = change.uptimeMillis
        change.consume()
        consumedInMain = true
        publishMotion()
        if (!pressAxis.atRest(1f, PRESS_TOLERANCE) && animation?.isActive != true)
            animate(pressed = true)
    }

    private fun begin(change: PointerInputChange) {
        stopAnimation()
        pointerId = change.id.value
        distanceX = 0f
        distanceY = 0f
        grabbedX = xAxis.value
        grabbedY = yAxis.value
        lastMoveTime = change.uptimeMillis
        slop = currentValueOf(LocalViewConfiguration).touchSlop
        captured = false
        gate.begin(change.uptimeMillis)
        // Regrabbing freezes the rendered translation without discarding its release velocity.
        animate(pressed = true)
    }

    private fun hasMovement(change: PointerInputChange): Boolean {
        if (change.position != change.previousPosition) return true
        for (history in change.historical) if (history.position != change.previousPosition) return true
        return false
    }

    private fun animate(pressed: Boolean) {
        stopAnimation()
        val pressure = if (pressed) 1f else 0f
        val positionTolerance = .025f * pixelDensity
        if (pressAxis.atRest(pressure, PRESS_TOLERANCE) &&
            (pressed || xAxis.atRest(0f, positionTolerance) && yAxis.atRest(0f, positionTolerance))) return
        val generation = animationGeneration
        // 安全阀是常量：在协程外算一次，逐帧只做比较。
        val settleDeadlineMillis = ElasticMotionPolicy.safetySettleMillis(LumenSpringSpec.DEFAULT)
        animation = coroutineScope.launch {
            var started = 0L
            var previousFrame = 0L
            try {
                while (isActive && isAttached && enabled && generation == animationGeneration) {
                    val finished = withFrameNanos { now ->
                        if (generation != animationGeneration || !isAttached) return@withFrameNanos true
                        if (!ValueAnimator.areAnimatorsEnabled()) { resetMotion(); return@withFrameNanos true }
                        if (started == 0L) started = now
                        val seconds = if (previousFrame == 0L) 1f / 120f else
                            ((now - previousFrame).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
                        previousFrame = now
                        pressAxis.advance(seconds, pressure)
                        if (!pressed) {
                            xAxis.advance(seconds, 0f)
                            yAxis.advance(seconds, 0f)
                        }
                        val rest = pressAxis.atRest(pressure, PRESS_TOLERANCE) &&
                            (pressed || xAxis.atRest(0f, positionTolerance) && yAxis.atRest(0f, positionTolerance))
                        // 与 View 控制器一致：以弹簧静止为准，安全阀按弹簧自适应。
                        val expired = (now - started) / 1_000_000L >= settleDeadlineMillis
                        if (rest || expired) {
                            pressAxis.reset(pressure)
                            if (!pressed) { xAxis.reset(); yAxis.reset() }
                        }
                        publishMotion()
                        rest || expired
                    }
                    if (finished) break
                }
            } finally {
                if (generation == animationGeneration) animation = null
            }
        }
    }

    private fun stopAnimation() {
        animationGeneration++
        animation?.cancel()
        animation = null
    }

    private fun resetMotion() {
        stopAnimation()
        gate.yield()
        pointerId = NO_POINTER
        captured = false
        consumedInMain = false
        distanceX = 0f
        distanceY = 0f
        xAxis.reset()
        yAxis.reset()
        pressAxis.reset()
        publishMotion()
    }

    private fun publishMotion() {
        // A stable placeWithLayer block only observes Snapshot reads; plain spring fields are not observed.
        renderedX = xAxis.value
        renderedY = yAxis.value
        renderedPress = pressAxis.value
    }

    override fun onCancelPointerInput() = resetMotion()
    override fun onUnplaced() = resetMotion()
    override fun onReset() = resetMotion()
    override fun onDetach() = resetMotion()

    private companion object {
        const val NO_POINTER = Long.MIN_VALUE
        const val PRESS_TOLERANCE = .0003f
    }
}
