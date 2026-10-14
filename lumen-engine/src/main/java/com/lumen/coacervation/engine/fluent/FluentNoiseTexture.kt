package com.lumen.coacervation.engine.fluent

import android.graphics.Bitmap
import android.os.Looper
import androidx.annotation.MainThread

/**
 * The acrylic noise tile, shared by every Fluent drawable in the process and reference counted.
 * WinUI tiles a grey noise image at 2% opacity; this one is generated, so no asset is shipped.
 * The last release only drops the reference: a display list may still hold the pixels, so it is never recycled.
 */
@MainThread
internal object FluentNoiseTexture {
    const val SIZE = 64
    /** ARGB_8888 budget; guarded by FluentNoiseTextureTest. */
    const val BYTES = SIZE * SIZE * 4
    private const val SEED = 0x2545F491

    private var bitmap: Bitmap? = null
    private var references = 0

    val referenceCount: Int get() = references

    fun acquire(): Bitmap {
        checkMain()
        val current = bitmap ?: Bitmap.createBitmap(pixels(SEED), SIZE, SIZE, Bitmap.Config.ARGB_8888).also { bitmap = it }
        references++
        return current
    }

    fun release() {
        checkMain()
        if (references == 0) return
        references--
        if (references == 0) bitmap = null
    }

    /** Opaque grey noise, uniform in 0..255, from a fixed xorshift sequence so every run matches. */
    fun pixels(seed: Int): IntArray {
        var state = if (seed == 0) 1 else seed
        return IntArray(SIZE * SIZE) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val grey = state ushr 24
            argb(255, grey, grey, grey)
        }
    }

    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper()) { "Fluent noise requires the main thread" }
}
