package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.host.LumenMicaBackdrop
import com.lumen.coacervation.engine.host.LumenMicaImage
import com.lumen.coacervation.engine.host.LumenMicaOptions
import com.lumen.coacervation.engine.host.LumenMicaPreparer
import com.lumen.coacervation.engine.host.LumenMicaPreparationFailure as Failure
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MicaPreparerIntegrationTest {
    private fun input(): Bitmap = Bitmap.createBitmap(128, 192, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.RED)
        canvas.drawRect(15f, 0f, 85f, 120f, Paint().apply { color = Color.BLUE })
        canvas.drawRect(65f, 75f, 128f, 192f, Paint().apply { color = Color.GREEN })
    }

    @Test fun ownedInputIsRecycledAndLatestOutputMatchesSynchronousRecipe() {
        val expectedInput = input()
        val expected = try { LumenMicaImage.prepare(expectedInput) } finally { expectedInput.recycle() }
        val source = AtomicReference<Bitmap>()
        val result = AtomicReference<LumenMicaImage>()
        val complete = CountDownLatch(1)
        ActivityScenario.launch(MicaSandboxActivity::class.java).use { scenario ->
            lateinit var preparer: LumenMicaPreparer
            try {
                scenario.onActivity {
                    preparer = LumenMicaPreparer()
                    preparer.prepare({
                        assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                        input().also(source::set)
                    }, { image, failure ->
                        assertSame(Looper.getMainLooper(), Looper.myLooper())
                        assertEquals(Failure.NONE, failure)
                        assertNotNull(image)
                        result.set(image); complete.countDown()
                    })
                }
                assertTrue(complete.await(5, TimeUnit.SECONDS))
                assertTrue(source.get().isRecycled)
                scenario.onActivity { activity ->
                    val view = View(activity).apply { layout(0, 0, 32, 32) }
                    val options = LumenMicaOptions(reduceMotion = true, fallbackWhenWindowInactive = false,
                        fallbackOnLowRamDevice = false, fallbackOnPowerSave = false,
                        tintOpacity = 0f, luminosityOpacity = 0f)
                    LumenMicaBackdrop(view, LumenPalette.neutral(false), options, expected).use { backdrop ->
                        val before = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                        val after = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                        try {
                            view.background.setBounds(0, 0, 32, 32)
                            view.background.draw(Canvas(before))
                            backdrop.setImage(result.get())
                            view.background.draw(Canvas(after))
                            assertTrue("Async preparation must preserve the synchronous pixels", before.sameAs(after))
                            preparer.close()
                            // A delivered snapshot stays usable after its preparer closes.
                            backdrop.setImage(result.get()); view.background.draw(Canvas(after))
                            assertTrue(before.sameAs(after))
                        } finally { before.recycle(); after.recycle() }
                    }
                }
            } finally { scenario.onActivity { preparer.close() } }
        }
    }

    @Test fun cancelAndCloseSuppressActiveAndPendingCallbacksAndRecycleInput() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acquired = AtomicReference<Bitmap>()
        val pendingLoads = AtomicInteger()
        val callbacks = AtomicInteger()
        ActivityScenario.launch(MicaSandboxActivity::class.java).use { scenario ->
            lateinit var preparer: LumenMicaPreparer
            try {
                scenario.onActivity {
                    preparer = LumenMicaPreparer()
                    preparer.prepare({
                        val bitmap = input().also(acquired::set)
                        try {
                            entered.countDown()
                            val deadline = SystemClock.uptimeMillis() + 5_000
                            // Deliberately finish after close, even when acquisition ignores interruption.
                            while (release.count > 0 && SystemClock.uptimeMillis() < deadline) {
                                try { release.await(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                            }
                            check(release.count == 0L)
                            bitmap
                        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
                    }, { _, _ -> callbacks.incrementAndGet() })
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    preparer.cancel()
                    repeat(500) {
                        preparer.prepare({ pendingLoads.incrementAndGet(); input() }, { _, _ -> callbacks.incrementAndGet() })
                    }
                    preparer.close(); preparer.close()
                    preparer.prepare({ pendingLoads.incrementAndGet(); input() }, { _, _ -> callbacks.incrementAndGet() })
                    assertTrue(preparer.isClosed)
                }
                release.countDown()
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (!acquired.get().isRecycled && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
                assertTrue(acquired.get().isRecycled)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(0, pendingLoads.get())
                assertEquals(0, callbacks.get())
            } finally { release.countDown(); scenario.onActivity { preparer.close() } }
        }
    }

    @Test fun sourceAndPreparationFailuresAreAsynchronousAndDoNotPreventReuse() {
        ActivityScenario.launch(MicaSandboxActivity::class.java).use { scenario ->
            lateinit var preparer: LumenMicaPreparer
            scenario.onActivity { preparer = LumenMicaPreparer() }
            try {
                for (expected in listOf(Failure.SOURCE_FAILED, Failure.PREPARATION_FAILED, Failure.NONE)) {
                    val complete = CountDownLatch(1)
                    scenario.onActivity {
                        var submitting = true
                        preparer.prepare({
                            if (expected == Failure.SOURCE_FAILED) throw IllegalArgumentException("fixture")
                            input().also { if (expected == Failure.PREPARATION_FAILED) it.recycle() }
                        }, { image, failure ->
                            assertFalse("Callbacks must be posted", submitting)
                            assertSame(Looper.getMainLooper(), Looper.myLooper())
                            assertEquals(expected, failure)
                            if (failure == Failure.NONE) assertNotNull(image) else assertNull(image)
                            complete.countDown()
                        })
                        submitting = false
                    }
                    assertTrue(complete.await(5, TimeUnit.SECONDS))
                }
            } finally { scenario.onActivity { preparer.close() } }
        }
    }
}
