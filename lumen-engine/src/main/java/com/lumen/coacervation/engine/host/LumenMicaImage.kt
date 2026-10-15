package com.lumen.coacervation.engine.host

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import androidx.annotation.WorkerThread
import com.lumen.coacervation.engine.fluent.MicaPolicy
import com.lumen.coacervation.engine.material.ModernBackdropBlur

/**
 * Immutable, strongly blurred wallpaper snapshot. Prepare once off the main thread, then share across
 * backdrops/themes. The source is borrowed only during [prepare]; it is never retained or recycled.
 * No system wallpaper/storage permission is requested. Live app content must not be used as the source.
 */
public class LumenMicaImage private constructor(internal val bitmap: Bitmap) {
    public val byteCount: Int get() = bitmap.byteCount

    public companion object {
        /**
         * At most 96 x 96 ARGB pixels (36 KiB), with a 144 KiB preparation working-set budget excluding
         * the caller's input. Supply a readable software bitmap; hardware bitmaps are not accepted.
         * Do not mutate or recycle [source] until this call returns. Cancellation propagates.
         */
        @WorkerThread
        public fun prepare(source: Bitmap): LumenMicaImage {
            check(Looper.myLooper() !== Looper.getMainLooper()) { "Prepare Mica images off the main thread" }
            require(!source.isRecycled && source.config != Bitmap.Config.HARDWARE) { "A readable software bitmap is required" }
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Mica preparation cancelled")
            val (width, height) = MicaPolicy.sampleSize(source.width, source.height)
            val sample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // An opaque base flattens transparent image pixels before the blur; never expose underlying UI.
                val canvas = Canvas(sample)
                canvas.drawColor(0xFF808080.toInt())
                canvas.drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                val pixels = IntArray(width * height)
                sample.getPixels(pixels, 0, width, 0, 0, width, height)
                val blurred = ModernBackdropBlur.blurInto(pixels, IntArray(pixels.size), width, height,
                    (minOf(width, height) / 6).coerceIn(1, 16))
                sample.setPixels(blurred, 0, width, 0, 0, width, height)
                val immutable = checkNotNull(sample.copy(Bitmap.Config.ARGB_8888, false))
                return LumenMicaImage(immutable)
            } finally {
                // The staging bitmap has never been published to a View or RenderThread.
                sample.recycle()
            }
        }
    }
}
