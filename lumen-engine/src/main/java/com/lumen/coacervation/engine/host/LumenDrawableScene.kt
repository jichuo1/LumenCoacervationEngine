package com.lumen.coacervation.engine.host

import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.MainThread

/**
 * Replays a caller-recorded Drawable without retaining it or changing its bounds/callback. The caller
 * owns invalidation, visibility, dimensions and recording budgets; no work is scheduled automatically.
 * Keep separate hardware and software instances when a scene also participates in software capture.
 * Hardware recordings retain their RenderNode identity across resize and require a hardware Canvas.
 * All calls belong on the main thread. Close releases recorded commands; late calls are harmless.
 */
@MainThread
public interface LumenDrawableScene : AutoCloseable {
    /** Records the Drawable in its current bounds. Width and height must be positive. */
    public fun record(drawable: Drawable, width: Int, height: Int)
    public fun draw(canvas: Canvas)

    public companion object {
        /**
         * API 29+ hardware scenes downsample by [scale]; software scenes always record at full size.
         * A hardware request on older Android versions uses a software Picture. Scale and its inverse
         * must be finite, with scale in (0, 1]. The caller clips replay to its logical recording bounds;
         * rounding adds less than one raster pixel at the right/bottom (less than 1/scale logical pixels).
         */
        @JvmStatic
        @JvmOverloads
        public fun create(hardware: Boolean, scale: Float = 1f): LumenDrawableScene {
            DrawableScenePolicy.validateScale(scale)
            return if (hardware && Build.VERSION.SDK_INT >= 29) DrawableSceneApi29(scale) else PictureDrawableScene()
        }
    }
}

internal abstract class RecordedDrawableScene(private val hardware: Boolean, private val scale: Float) : LumenDrawableScene {
    private val recordingSize = DrawableScenePolicy.Size()
    private var closed = false
    private var recorded = false

    final override fun record(drawable: Drawable, width: Int, height: Int) {
        if (closed) return
        val size = DrawableScenePolicy.size(width, height, hardware, scale, recordingSize)
        recorded = false
        recordDrawable(drawable, size)
        recorded = true
    }

    final override fun draw(canvas: Canvas) {
        if (closed) return
        require(!hardware || canvas.isHardwareAccelerated) { "Keep a separate software scene for software capture" }
        if (recorded) drawRecorded(canvas)
    }

    final override fun close() {
        if (closed) return
        closed = true
        recorded = false
        release()
    }

    protected abstract fun recordDrawable(drawable: Drawable, size: DrawableScenePolicy.Size)
    protected abstract fun drawRecorded(canvas: Canvas)
    protected abstract fun release()
}

private class PictureDrawableScene : RecordedDrawableScene(false, 1f) {
    private var picture: Picture? = Picture()

    override fun recordDrawable(drawable: Drawable, size: DrawableScenePolicy.Size) {
        val recording = requireNotNull(picture)
        val canvas = recording.beginRecording(size.width, size.height)
        try { drawable.draw(canvas) } finally { recording.endRecording() }
    }

    override fun drawRecorded(canvas: Canvas) = canvas.drawPicture(requireNotNull(picture))
    override fun release() { picture = null }
}
