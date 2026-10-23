package com.lumen.coacervation.sample

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*

/** Frame fences and actual window pixels, without waiting for a held animation to become idle. */
internal object WindowRegressionCapture {
    fun main(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    fun frame(view: View) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            main { ready = view.isAttachedToWindow && view.isLaidOut && view.width > 0 && view.height > 0 && view.hasWindowFocus() }
            if (!ready) SystemClock.sleep(16)
        }
        assertTrue("The verification window must be focused and laid out before capture/input", ready)
        val done = CountDownLatch(1)
        main { view.postOnAnimation { view.postOnAnimation { done.countDown() } }; view.invalidate() }
        assertTrue("Two actual display frames must arrive", done.await(
            (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS))
    }

    fun copy(activity: Activity, view: View, check: (Bitmap) -> Unit) {
        repeat(3) { attempt ->
            frame(view)
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            val ownership = Any()
            var pending = false
            var abandoned = false
            var result = -1
            try {
                main {
                    val location = IntArray(2)
                    view.getLocationInWindow(location)
                    synchronized(ownership) { pending = true }
                    try {
                        PixelCopy.request(activity.window,
                            Rect(location[0], location[1], location[0] + view.width, location[1] + view.height), image,
                            { code ->
                                synchronized(ownership) {
                                    result = code; pending = false
                                    if (abandoned) image.recycle()
                                }
                                done.countDown()
                            }, Handler(Looper.getMainLooper()))
                    } catch (failure: Throwable) {
                        synchronized(ownership) { pending = false }
                        throw failure
                    }
                }
                assertTrue("Window capture must finish", done.await(5, TimeUnit.SECONDS))
                if (result == PixelCopy.ERROR_SOURCE_NO_DATA && attempt < 2) return@repeat
                assertEquals(PixelCopy.SUCCESS, result)
                check(image)
                return
            } finally {
                synchronized(ownership) { abandoned = true; if (!pending && !image.isRecycled) image.recycle() }
            }
        }
    }
}
