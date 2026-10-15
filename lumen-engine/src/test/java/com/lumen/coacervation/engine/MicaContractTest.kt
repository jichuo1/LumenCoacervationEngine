package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.*
import org.junit.Test

class MicaContractTest {
    private val backdrop = SourceContract.read("host/LumenMicaBackdrop.kt")
    private val drawable = SourceContract.read("fluent/MicaBackdropDrawable.kt")
    private val image = SourceContract.read("host/LumenMicaImage.kt")

    @Test fun drawNeverCapturesBlursAllocatesOrBuildsShaders() {
        val draw = drawable.after("override fun draw(canvas: Canvas)").before("override fun onBoundsChange")
        for (forbidden in listOf("Bitmap.create", "BitmapShader(", "IntArray(", "Paint(", "saveLayer",
            "RenderEffect", "RuntimeShader", "ModernBackdropBlur", "postDelayed", "scheduleSelf"))
            assertFalse(forbidden, draw.contains(forbidden))
        assertTrue(draw.contains("if (transition.isRunning(now)) invalidateSelf()"))
        assertTrue(draw.indexOf("canvas.drawRect(rect, fill)") < draw.indexOf("canvas.drawRect(rect, imagePaint)"))
    }

    @Test fun prepareIsWorkerOnlyAndNeverRetainsOrRecyclesBorrowedInput() {
        assertTrue(image.contains("@WorkerThread"))
        assertTrue(image.contains("Looper.myLooper() !== Looper.getMainLooper()"))
        assertTrue(image.contains("MicaPolicy.sampleSize(source.width, source.height)"))
        assertTrue(image.contains("source.config != Bitmap.Config.HARDWARE"))
        assertFalse(image.contains("source.recycle()"))
        assertFalse(image.contains("val source: Bitmap"))
        // Published snapshots must survive RenderThread display-list references.
        assertFalse(drawable.contains(".recycle()"))
    }

    @Test fun memoryPressureDropsBothImagesAndRequiresExplicitReplacement() {
        assertTrue(backdrop.contains("LumenMemoryPressureHub.addListener(memory)"))
        assertTrue(backdrop.contains("LumenMemoryPressureHub.removeListener(memory)"))
        val memory = backdrop.after("private val memory =").before("private val attach")
        assertTrue(memory.contains("image = null"))
        assertTrue(memory.contains("memoryReleased = true"))
        assertTrue(memory.contains("drawable.releaseImage()"))
        val release = drawable.after("fun releaseImage()").before("fun release()")
        assertTrue(release.contains("material = null"))
        assertTrue(release.contains("imagePaint.shader = null"))
    }

    @Test fun everyListenerHasADetachAndCloseRemovalPath() {
        assertTrue(backdrop.contains("observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)"))
        assertTrue(backdrop.contains("context.unregisterReceiver(powerChanged)"))
        assertTrue(backdrop.contains("override fun onViewDetachedFromWindow(v: View) { unobserve() }"))
        val close = backdrop.after("override fun close()").before("private fun observe")
        assertTrue(close.contains("unobserve()"))
        assertTrue(close.contains("view.removeOnAttachStateChangeListener(attach)"))
        assertTrue(close.contains("if (view.background === drawable) replaceBackground(view, original)"))
    }

    @Test fun noWallpaperPermissionsHiddenApisOrRealtimeSampling() {
        for (text in listOf(backdrop, drawable, image)) {
            for (forbidden in listOf("WallpaperManager", "PixelCopy", "LumenSurfaceSession(", "Class.forName",
                "getDeclaredField", "getDeclaredMethod", "READ_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE"))
                assertFalse(forbidden, text.contains(forbidden))
        }
    }
}
