package com.lumen.coacervation.engine.host

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.os.Build
import android.os.Looper
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import com.lumen.coacervation.engine.glow.GlowChromeBlurApi31
import com.lumen.coacervation.engine.material.FrostedChromeLensApi33
import com.lumen.coacervation.engine.runtime.LumenGraphicsCounters
import kotlin.math.roundToInt

/**
 * FROSTED without refraction, using the contents already drawn into the current render target.
 * This node records no source View and supplies no underlay. Its caller owns source semantics,
 * hardware-canvas/budget checks, and the sticky fallback after a graphics failure.
 */
@MainThread
@RequiresApi(37)
internal class RenderTargetBackdropApi37(
    density: Float,
    sampling: LumenSurfaceSampling,
    private val counters: LumenGraphicsCounters? = null
) : AutoCloseable {
    private var node: RenderNode? = null
    private val outline = Outline()
    private var blur: RenderEffect? = null
    private var lens: Any? = null
    private var effect: RenderEffect? = null
    private val padding = (if (sampling.blurEnabled) sampling.blurRadiusDp * density * 2f else 0f).roundToInt() + 2
    /** Visible surface dimensions; the recorded node adds [padding] on all sides. */
    private var width = -1
    private var height = -1
    private var radius = Float.NaN
    private var closed = false

    init {
        checkMain()
        check(isSupported()) { "Render target backdrop requires SDK 37.2" }
        blur = if (sampling.blurEnabled && sampling.blurRadiusDp > 0f)
            GlowChromeBlurApi31.create(sampling.blurRadiusDp * density)
        else RenderEffect.createOffsetEffect(0f, 0f)
        // The ordinary FROSTED path also illuminates and clamps premultiplied SDR colour
        // when refraction is disabled. Reuse that exact zero-strength shader.
        lens = FrostedChromeLensApi33.create(0f)
        counters?.shaderCompiled()
        node = RenderNode("Lumen-RenderTargetBackdrop").apply {
            setClipToBounds(true)
            setClipToOutline(true)
        }
    }

    /** [bounds] are Canvas-local; drawRenderNode inherits the actual Canvas transform and clip. */
    @SuppressLint("NewApi") // Every minor-API call is protected by the full SDK 37.2 guard.
    fun draw(canvas: Canvas, bounds: Rect, radius: Float): Boolean {
        if (closed) return false
        checkMain()
        if (!canvas.isHardwareAccelerated || !isSupported() || bounds.isEmpty) return false
        val current = node ?: return false
        val nextWidth = bounds.width()
        val nextHeight = bounds.height()
        current.setPosition(bounds.left - padding, bounds.top - padding, bounds.right + padding, bounds.bottom + padding)
        if (nextWidth != width || nextHeight != height) {
            val built = FrostedChromeLensApi33.effect(lens!!, blur!!, nextWidth, nextHeight, padding, 0f)
            // The boolean describes a property change, not backend availability. An unchanged
            // value is valid; thrown graphics errors are handled by the owning binding.
            current.setBackdropRenderEffect(built)
            effect = built
            counters?.effectBuilt()
            // HWUI records BackdropFilterDrawable before this child, even when the child is empty:
            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/hwui/pipeline/skia/SkiaRecordingCanvas.cpp
            // Its snapshot is bounded by the node, so retain the same blur halo as the budget.
            current.beginRecording(nextWidth + 2 * padding, nextHeight + 2 * padding)
            try {
                // No opaque/transparent base: previously drawn destination pixels are the input.
            } finally {
                current.endRecording()
            }
            counters?.proxyRecorded()
            width = nextWidth
            height = nextHeight
            this.radius = Float.NaN
        }
        if (this.radius != radius) {
            outline.setRoundRect(padding, padding, padding + nextWidth, padding + nextHeight, radius)
            current.setOutline(outline)
            this.radius = radius
        }
        canvas.drawRenderNode(current)
        return true
    }

    @SuppressLint("NewApi") // Node creation requires isSupported(), including its full SDK guard.
    override fun close() {
        if (closed) return
        checkMain()
        closed = true
        val current = node
        node = null
        try {
            if (current != null && isSupported()) current.setBackdropRenderEffect(null)
        } finally {
            try {
                current?.discardDisplayList()
            } finally {
                effect = null
                blur = null
                lens = null
                width = -1
                height = -1
                radius = Float.NaN
            }
        }
    }

    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper()) {
        "Render target backdrop requires the main thread"
    }

    companion object {
        @ChecksSdkIntAtLeast(api = 37)
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 36 &&
            RenderTargetBackdropPolicy.versionSupported(Build.VERSION.SDK_INT, Build.VERSION.SDK_INT_FULL)
    }
}
