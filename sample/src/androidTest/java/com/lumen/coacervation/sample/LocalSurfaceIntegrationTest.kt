package com.lumen.coacervation.sample

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.PixelCopy
import androidx.annotation.RequiresApi
import androidx.test.filters.SdkSuppress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.host.*
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSurfaceIntegrationTest {
    private fun waitForLayout(scenario: ActivityScenario<SurfaceSandboxActivity>) {
        val deadline=SystemClock.uptimeMillis()+5000
        var ready=false
        while (!ready && SystemClock.uptimeMillis()<deadline) {
            scenario.onActivity { ready=it.glass.width>0 && it.glass.isAttachedToWindow && it.glass.hasWindowFocus() }
            if (!ready) SystemClock.sleep(30)
        }
        assertTrue("Surface window did not become visible",ready)
    }

    @Test fun bindingPreservesTheWindowHierarchyPaddingAndUnrelatedBackgrounds() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            waitForLayout(scenario)
            scenario.onActivity { activity ->
                val parent=activity.glass.parent
                val count=activity.preview.childCount
                val root=activity.preview.rootView
                val originalRoot=root.background
                activity.binding.close()
                val original=ColorDrawable(Color.RED)
                activity.glass.background=original
                activity.glass.setPadding(11,12,13,14)
                val binding=activity.session.bind(activity.glass,LumenSurfaceOptions(),activity.content)
                assertSame(parent,activity.glass.parent);assertEquals(count,activity.preview.childCount)
                assertSame(originalRoot,root.background)
                assertEquals(11,activity.glass.paddingLeft)
                binding.close();assertSame(original,activity.glass.background)
                assertEquals(14,activity.glass.paddingBottom)
                val second=activity.session.bind(activity.glass,LumenSurfaceOptions(),activity.content)
                val replacement=ColorDrawable(Color.BLUE);activity.glass.background=replacement
                second.update(LumenSurfaceOptions(opacity=.7f))
                second.close();assertSame(replacement,activity.glass.background)
            }
        }
    }

    @Test fun sourceCannotCaptureItsOwnInjectedSurface() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            waitForLayout(scenario)
            scenario.onActivity { activity ->
                activity.binding.close()
                activity.binding=activity.session.bind(activity.glass,LumenSurfaceOptions(),activity.preview)
                val bitmap=Bitmap.createBitmap(activity.glass.width,activity.glass.height,Bitmap.Config.ARGB_8888)
                activity.glass.background.setBounds(0,0,bitmap.width,bitmap.height)
                activity.glass.background.draw(Canvas(bitmap))
                assertEquals(LumenSurfaceFailure.SELF_FEEDBACK,activity.binding.diagnostics()?.failure)
                assertEquals(LumenSurfaceBackend.STATIC,activity.binding.diagnostics()?.backend)
            }
        }
    }

    @Test fun forcedStaticBackendDoesNotRequireAContentSource() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            waitForLayout(scenario)
            scenario.onActivity { activity ->
                activity.binding.close()
                activity.binding=activity.session.bind(activity.glass,
                    LumenSurfaceOptions(sampling=LumenSurfaceSampling(backend=LumenSurfaceBackend.STATIC)))
                val bitmap=Bitmap.createBitmap(activity.glass.width,activity.glass.height,Bitmap.Config.ARGB_8888)
                activity.glass.background.setBounds(0,0,bitmap.width,bitmap.height)
                activity.glass.background.draw(Canvas(bitmap))
                assertEquals(LumenSurfaceBackend.STATIC,activity.binding.diagnostics()?.backend)
                assertEquals(LumenSurfaceFailure.NONE,activity.binding.diagnostics()?.failure)
            }
        }
    }

    @Test fun softwareCaptureProducesAFrameAndPaletteChangesDoNotRecreateTheActivity() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            waitForLayout(scenario)
            var owner:SurfaceSandboxActivity?=null
            scenario.onActivity {
                owner=it
                it.binding.update(LumenSurfaceOptions(sampling=LumenSurfaceSampling(backend=LumenSurfaceBackend.SOFTWARE,minIntervalMs=0)))
                it.session.notifyContentChanged()
            }
            val deadline=SystemClock.uptimeMillis()+5000
            var captured=false
            while (!captured && SystemClock.uptimeMillis()<deadline) {
                scenario.onActivity { captured=it.binding.diagnostics()?.backend==LumenSurfaceBackend.SOFTWARE }
                if(!captured)SystemClock.sleep(30)
            }
            assertTrue("Software capture never became drawable",captured)
            scenario.onActivity {
                val root=it.preview.rootView;val background=root.background
                it.session.updatePalette(LumenPalette.neutral(true))
                assertSame(owner,it);assertSame(background,root.background)
                assertEquals(1L,it.session.diagnostics().paletteGeneration)
                it.session.releaseGraphics();it.session.resume()
            }
        }
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun gpuFadeUsesTheCurrentSourceAndDoesNotTintTheWholeWindow() {
        // ATD images disable final hardware output while still running View draw callbacks.
        val wasDrawing=if(Build.VERSION.SDK_INT>=33)TestDrawingApi33.isEnabled()else true
        if(Build.VERSION.SDK_INT>=33)TestDrawingApi33.setEnabled(true)
        try {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            waitForLayout(scenario)
            scenario.onActivity { activity ->
                activity.binding.close()
                val custom=object:LumenContentSource {
                    override val coordinateView:View get()=activity.content
                    override fun drawContent(canvas:Canvas) { canvas.drawColor(Color.RED) }
                }
                activity.binding=activity.session.bindSource(activity.glass,
                    LumenSurfaceOptions(radiusDp=0f,tintEnabled=false,edgeEnabled=false,
                        sampling=LumenSurfaceSampling(backend=LumenSurfaceBackend.GPU,softwareFallback=false,
                            blurEnabled=false,refractionEnabled=false,minIntervalMs=0,fadeEnabled=true)),custom)
            }
            val deadline=SystemClock.uptimeMillis()+5000
            var gpu=false
            while(!gpu && SystemClock.uptimeMillis()<deadline) {
                scenario.onActivity { gpu=it.binding.diagnostics()?.backend==LumenSurfaceBackend.GPU }
                if(!gpu)SystemClock.sleep(30)
            }
            assertTrue("GPU local surface never became drawable",gpu)
            var image:Bitmap?=null
            var copyResult=-1
            var x=0;var top=0;var bottom=0;var outside=0
            scenario.onActivity { activity ->
                val location=IntArray(2);activity.glass.getLocationInWindow(location)
                x=location[0]+8;outside=location[0]-8
                top=location[1]+(activity.glass.height*.05f).toInt()
                bottom=location[1]+(activity.glass.height*.95f).toInt()
                val decor=activity.window.decorView
                image=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
            }
            // A RenderNode draw can precede the first buffer reaching the Window surface.
            val copyDeadline=SystemClock.uptimeMillis()+5000
            do {
                val latch=CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.glass.invalidate()
                    PixelCopy.request(activity.window,image!!,{code->copyResult=code;latch.countDown()},Handler(Looper.getMainLooper()))
                }
                assertTrue("PixelCopy callback did not arrive",latch.await(5,TimeUnit.SECONDS))
                if(copyResult!=PixelCopy.SUCCESS)SystemClock.sleep(50)
            } while(copyResult!=PixelCopy.SUCCESS && SystemClock.uptimeMillis()<copyDeadline)
            assertEquals(PixelCopy.SUCCESS,copyResult)
            val screenshot=image!!
            val high=screenshot.getPixel(x,top)
            assertTrue("Top did not show captured red content",Color.red(high)>220 && Color.green(high)<70)
            val low=screenshot.getPixel(x,bottom);val behind=screenshot.getPixel(outside,bottom)
            assertTrue(kotlin.math.abs(Color.red(low)-Color.red(behind))<35)
            assertTrue(kotlin.math.abs(Color.green(low)-Color.green(behind))<35)
            assertTrue(kotlin.math.abs(Color.blue(low)-Color.blue(behind))<35)
            screenshot.recycle()
        }
        } finally {
            if(Build.VERSION.SDK_INT>=33)TestDrawingApi33.setEnabled(wasDrawing)
        }
    }

    @Test fun foreignWindowSourceIsRejectedAndLateCallsAfterCloseAreHarmless() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            waitForLayout(scenario)
            var dialog:Dialog?=null
            var source:View?=null
            scenario.onActivity { activity ->
                dialog=Dialog(activity)
                source=View(activity)
                dialog!!.setContentView(source!!,FrameLayout.LayoutParams(200,200));dialog!!.show()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(source!!.isAttachedToWindow)
                activity.binding.close()
                activity.binding=activity.session.bind(activity.glass,LumenSurfaceOptions(),source)
                val bitmap=Bitmap.createBitmap(activity.glass.width,activity.glass.height,Bitmap.Config.ARGB_8888)
                activity.glass.background.setBounds(0,0,bitmap.width,bitmap.height)
                activity.glass.background.draw(Canvas(bitmap))
                assertEquals(LumenSurfaceFailure.DIFFERENT_WINDOW,activity.binding.diagnostics()?.failure)
                dialog!!.dismiss();activity.session.close()
                activity.binding.update(LumenSurfaceOptions(opacity=.1f));activity.binding.close()
                activity.session.pause();activity.session.resume();activity.session.notifyContentChanged()
                activity.session.updatePalette(LumenPalette.neutral(true))
                assertTrue(activity.session.diagnostics().closed)
                assertEquals(-1L,activity.session.bind(activity.glass).id)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }
}

@RequiresApi(33)
private object TestDrawingApi33 {
    fun isEnabled()=android.graphics.HardwareRenderer.isDrawingEnabled()
    fun setEnabled(value:Boolean)=android.graphics.HardwareRenderer.setDrawingEnabled(value)
}
