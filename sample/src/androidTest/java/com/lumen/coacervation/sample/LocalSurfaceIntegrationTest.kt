package com.lumen.coacervation.sample

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import android.view.PixelCopy
import androidx.annotation.RequiresApi
import androidx.test.filters.SdkSuppress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.View
import android.view.Gravity
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
                assertEquals(LumenSurfaceBackend.STATIC,it.binding.diagnostics()?.backend)
                it.binding.update(LumenSurfaceOptions(enabled=false))
                assertEquals(LumenSurfaceBackend.STATIC,it.binding.diagnostics()?.backend)
                assertEquals(LumenSurfaceFailure.NONE,it.binding.diagnostics()?.failure)
                it.binding.update(LumenSurfaceOptions(sampling=LumenSurfaceSampling(backend=LumenSurfaceBackend.SOFTWARE,minIntervalMs=0)))
                assertEquals(LumenSurfaceFailure.FRAME_PENDING,it.binding.diagnostics()?.failure)
                it.session.pause()
                assertEquals(LumenSurfaceBackend.STATIC,it.binding.diagnostics()?.backend)
                assertEquals(LumenSurfaceFailure.PAUSED,it.binding.diagnostics()?.failure)
                it.session.resume();it.session.releaseGraphics()
                assertEquals(LumenSurfaceBackend.STATIC,it.binding.diagnostics()?.backend)
                assertEquals(LumenSurfaceFailure.MEMORY_PRESSURE,it.binding.diagnostics()?.failure)
                it.session.resume()
            }
        }
    }

    @Test fun mixedSoftwareIntervalsRemainIndependentAndTheFinalSlowFrameIsSampled() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            waitForLayout(scenario)
            val fastTimes=ArrayList<Long>();val slowTimes=ArrayList<Long>()
            var slowBinding:LumenSurfaceBinding?=null
            scenario.onActivity { activity ->
                activity.binding.close()
                activity.glass.layoutParams=FrameLayout.LayoutParams(40,40,Gravity.TOP or Gravity.LEFT).apply {topMargin=8;leftMargin=8}
                val slow=View(activity)
                activity.preview.addView(slow,FrameLayout.LayoutParams(40,40,Gravity.BOTTOM or Gravity.LEFT).apply {bottomMargin=8;leftMargin=8})
                val clip=Rect()
                val source=object:LumenContentSource {
                    override val coordinateView:View get()=activity.content
                    override fun drawContent(canvas:Canvas) {
                        canvas.getClipBounds(clip)
                        (if(clip.centerY()<coordinateView.height/2)fastTimes else slowTimes).add(SystemClock.uptimeMillis())
                        canvas.drawColor(Color.RED)
                    }
                }
                val options=LumenSurfaceOptions(radiusDp=0f,tintEnabled=false,edgeEnabled=false,
                    sampling=LumenSurfaceSampling(backend=LumenSurfaceBackend.SOFTWARE,blurEnabled=false,refractionEnabled=false,minIntervalMs=0))
                activity.binding=activity.session.bindSource(activity.glass,options,source)
                slowBinding=activity.session.bindSource(slow,options.copy(sampling=options.sampling.copy(minIntervalMs=1000)),source)
            }
            val firstDeadline=SystemClock.uptimeMillis()+5000
            var ready=false
            while(!ready && SystemClock.uptimeMillis()<firstDeadline) {
                scenario.onActivity { ready=it.binding.diagnostics()?.backend==LumenSurfaceBackend.SOFTWARE && slowBinding?.diagnostics()?.backend==LumenSurfaceBackend.SOFTWARE }
                if(!ready)SystemClock.sleep(20)
            }
            assertTrue("Both software surfaces must produce an initial frame",ready)
            var initialFast=0;var initialSlow=0;var lastSlow=0L
            scenario.onActivity { initialFast=fastTimes.size;initialSlow=slowTimes.size;lastSlow=slowTimes.last() }
            var lastChange=0L
            for(i in 0 until 15) { scenario.onActivity {lastChange=SystemClock.uptimeMillis();it.session.notifyContentChanged() };SystemClock.sleep(20) }
            scenario.onActivity {
                assertTrue("Fast surface should keep sampling",fastTimes.size>initialFast+2)
                // Full-suite UI/GC load can make 15 nominal 20ms pulses exceed the slow interval.
                // Check the actual capture gaps, and keep the no-extra-capture assertion when still inside it.
                if(SystemClock.uptimeMillis()-lastSlow<950)assertEquals("Fast surface must not accelerate slow surface",initialSlow,slowTimes.size)
                for(i in initialSlow until slowTimes.size)assertTrue("Slow surface interval: $slowTimes",slowTimes[i]-slowTimes[i-1]>=950)
            }
            val finalDeadline=SystemClock.uptimeMillis()+3000
            var finalSample=false
            while(!finalSample && SystemClock.uptimeMillis()<finalDeadline) {
                scenario.onActivity { finalSample=slowTimes.size>initialSlow&&slowTimes.last()>=lastChange }
                if(!finalSample)SystemClock.sleep(20)
            }
            assertTrue("Slow surface must sample the final pending frame after motion stops",finalSample)
            var count=0
            scenario.onActivity {assertTrue(slowTimes.last()-lastSlow>=950);for(i in initialSlow until slowTimes.size)assertTrue(slowTimes[i]-slowTimes[i-1]>=950);count=fastTimes.size+slowTimes.size }
            SystemClock.sleep(200)
            scenario.onActivity { assertEquals("A static page must stop sampling",count,fastTimes.size+slowTimes.size) }
        }
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun gpuFadeUsesTheCurrentSourceAndDoesNotTintTheWholeWindow() {
        checkGpuFade(LumenSurfaceFadeCurve.SMOOTH)
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun linearGpuFadeHasUniformPixelCoverage() {
        checkGpuFade(LumenSurfaceFadeCurve.LINEAR)
    }

    private fun checkGpuFade(curve: LumenSurfaceFadeCurve) {
        // ATD images disable final hardware output while still running View draw callbacks.
        val wasDrawing=if(Build.VERSION.SDK_INT>=33)TestDrawingApi33.isEnabled()else true
        Log.i("Lumen-SurfaceTest","GPU fade: enable drawing")
        if(Build.VERSION.SDK_INT>=33)TestDrawingApi33.setEnabled(true)
        Log.i("Lumen-SurfaceTest","GPU fade: launch local surface")
        try {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            waitForLayout(scenario)
            Log.i("Lumen-SurfaceTest","GPU fade: window ready")
            scenario.onActivity { activity ->
                activity.binding.close()
                val custom=object:LumenContentSource {
                    override val coordinateView:View get()=activity.content
                    override fun drawContent(canvas:Canvas) { canvas.drawColor(Color.RED) }
                }
                activity.binding=activity.session.bindSource(activity.glass,
                    LumenSurfaceOptions(radiusDp=0f,tintEnabled=false,edgeEnabled=false,
                        sampling=LumenSurfaceSampling(backend=LumenSurfaceBackend.GPU,softwareFallback=false,
                            blurEnabled=false,refractionEnabled=false,minIntervalMs=0,fadeEnabled=true,fadeCurve=curve)),custom)
            }
            val deadline=SystemClock.uptimeMillis()+5000
            var gpu=false
            while(!gpu && SystemClock.uptimeMillis()<deadline) {
                scenario.onActivity { gpu=it.binding.diagnostics()?.backend==LumenSurfaceBackend.GPU }
                if(!gpu)SystemClock.sleep(30)
            }
            assertTrue("GPU local surface never became drawable",gpu)
            // Opt into custom geometry, then clear it: strict GPU-only input must return to the legacy node plan.
            scenario.onActivity {a->a.binding.setShapesPixels(0f,0f,a.glass.width.toFloat(),a.glass.height.toFloat(),0f,0f,0f,0f,false);a.binding.clearCustomShapes()}
            val restoredDeadline=SystemClock.uptimeMillis()+5000
            gpu=false
            while(!gpu&&SystemClock.uptimeMillis()<restoredDeadline){scenario.onActivity {gpu=it.binding.diagnostics()?.backend==LumenSurfaceBackend.GPU};if(!gpu)SystemClock.sleep(25)}
            assertTrue("Cleared custom geometry must restore the node path",gpu)
            Log.i("Lumen-SurfaceTest","GPU fade: backend ready, copy pixels")
            var image:Bitmap?=null
            var copyResult=-1
            var x=0;var top=0;var bottom=0;var outside=0;var origin=0;var height=0
            scenario.onActivity { activity ->
                val location=IntArray(2);activity.glass.getLocationInWindow(location)
                x=location[0]+8;outside=location[0]-8
                origin=location[1];height=activity.glass.height
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
            Log.i("Lumen-SurfaceTest","GPU fade: copied pixels")
            val screenshot=image!!
            val high=screenshot.getPixel(x,top)
            assertTrue("Top did not show captured red content",Color.red(high)>220 && Color.green(high)<70)
            val low=screenshot.getPixel(x,bottom);val behind=screenshot.getPixel(outside,bottom)
            assertTrue(kotlin.math.abs(Color.red(low)-Color.red(behind))<35)
            assertTrue(kotlin.math.abs(Color.green(low)-Color.green(behind))<35)
            assertTrue(kotlin.math.abs(Color.blue(low)-Color.blue(behind))<35)
            if (curve == LumenSurfaceFadeCurve.LINEAR) {
                for (step in 1..9) {
                    val y=origin+(height*step/10f).toInt()
                    val fraction=(y-origin+.5f)/height
                    val actual=screenshot.getPixel(x,y)
                    val background=screenshot.getPixel(outside,y)
                    val expectedRed=255f*(1f-fraction)+Color.red(background)*fraction
                    assertEquals("Linear red coverage at $step/10",expectedRed,Color.red(actual).toFloat(),4f)
                    assertEquals("Linear green coverage at $step/10",Color.green(background)*fraction,Color.green(actual).toFloat(),4f)
                    assertEquals("Linear blue coverage at $step/10",Color.blue(background)*fraction,Color.blue(actual).toFloat(),4f)
                }
            }
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
