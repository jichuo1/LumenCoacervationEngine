package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.host.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EnhancedSurfaceIntegrationTest {
    @get:org.junit.Rule val hardwareOutput=HardwareOutputRule()
    private fun ready(scenario: ActivityScenario<SurfaceSandboxActivity>) {
        val end=SystemClock.uptimeMillis()+5000;var ready=false
        while(!ready&&SystemClock.uptimeMillis()<end){scenario.onActivity {ready=it.glass.width>0&&it.glass.hasWindowFocus()};if(!ready)SystemClock.sleep(25)}
        assertTrue(ready)
    }
    private fun waitGpu(scenario: ActivityScenario<SurfaceSandboxActivity>) {
        val end=SystemClock.uptimeMillis()+7000;var gpu=false
        while(!gpu&&SystemClock.uptimeMillis()<end){scenario.onActivity {gpu=it.binding.diagnostics()?.backend==LumenSurfaceBackend.GPU};if(!gpu)SystemClock.sleep(25)}
        scenario.onActivity {assertTrue("Enhanced shader never drew: ${it.binding.diagnostics()}",gpu)}
    }
    @SdkSuppress(minSdkVersion=33)
    @Test fun fusionPressAndLightCompileAndContinuousFramesDoNotBuildRenderEffects() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {scenario->
            ready(scenario)
            scenario.onActivity {
                it.binding.updateEnhancements(LumenSurfaceEnhancements(
                    geometry=LumenSurfaceGeometryOptions(cornersEnabled=true,corners=LumenSurfaceCorners(32f,4f,24f,12f),fusionEnabled=true),
                    press=LumenLocalPressOptions(enabled=true,rippleEnabled=true),light=LumenSurfaceLightOptions(enabled=true),
                    debug=LumenSurfaceDebugOptions(timingEnabled=true)))
                val w=it.glass.width.toFloat();val h=it.glass.height.toFloat()
                it.binding.setShapesPixels(8f,8f,w*.35f,h-16f,w*.4f,8f,w*.35f,h-16f,true)
            }
            waitGpu(scenario)
            var before=0L;var shaders=0L;var recordings=0L
            scenario.onActivity {val p=it.session.performanceDiagnostics();before=p.effectChainBuilds;shaders=p.runtimeShaderBuilds;recordings=p.contentRecordings}
            for(i in 0..12){
                scenario.onActivity {
                    it.binding.setPressPixels(it.glass.width*.3f,it.glass.height*.5f,i/12f)
                    it.binding.setLightDirection(i/12f-.5f,.4f,.8f)
                }
                SystemClock.sleep(20)
            }
            scenario.onActivity {
                val p=it.session.performanceDiagnostics()
                assertEquals(before,p.effectChainBuilds);assertEquals(shaders,p.runtimeShaderBuilds)
                assertTrue(p.activeSoftwareBytes in 1..8L*1024*1024)
                it.binding.clearTransientEffects();it.session.releaseGraphics()
                assertEquals(0L,it.session.performanceDiagnostics().activeSoftwareBytes)
            }
        }
    }
    @SdkSuppress(minSdkVersion=33)
    @Test fun progressiveBlurProducesAnActualWindowImageAndStaysWithinBudget() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {scenario->
            ready(scenario)
            scenario.onActivity {
                it.binding.updateEnhancements(LumenSurfaceEnhancements(progressiveBlur=LumenProgressiveBlurOptions(enabled=true)))
            }
            waitGpu(scenario)
            var image:Bitmap?=null;var result=-1
            val latch=CountDownLatch(1)
            scenario.onActivity {
                image=Bitmap.createBitmap(it.window.decorView.width,it.window.decorView.height,Bitmap.Config.ARGB_8888)
                PixelCopy.request(it.window,image!!,{code->result=code;latch.countDown()},Handler(Looper.getMainLooper()))
            }
            assertTrue(latch.await(5,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
            val pixels=IntArray(image!!.width*image!!.height);image!!.getPixels(pixels,0,image!!.width,0,0,image!!.width,image!!.height)
            assertTrue(pixels.any {Color.red(it)!=Color.blue(it)})
            scenario.onActivity {assertTrue(it.session.performanceDiagnostics().activeSoftwareBytes<=8L*1024*1024)}
            image!!.recycle()
        }
    }
    @Test fun opaqueAccessibleIntentNeedsNoCaptureAndKeepsTheOriginalClick() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {scenario->
            ready(scenario)
            scenario.onActivity {
                var clicked=false;it.glass.setOnClickListener {clicked=true}
                it.binding.updateEnhancements(LumenSurfaceEnhancements(material=LumenMaterialRecipeOptions(intent=LumenMaterialIntent.OPAQUE_ACCESSIBLE)))
                it.glass.performClick();assertTrue(clicked)
            }
            SystemClock.sleep(100)
            scenario.onActivity {assertEquals(LumenSurfaceBackend.STATIC,it.binding.diagnostics()?.backend);assertEquals(0L,it.session.performanceDiagnostics().activeSoftwareBytes)}
        }
    }
    @Test fun presetsRoundTripAllGroupsAndRejectInvalidKnownValues(){
        val preset=LumenEffectPreset(enhancements=LumenSurfaceEnhancements(geometry=LumenSurfaceGeometryOptions(cornersEnabled=true,corners=LumenSurfaceCorners(1f,2f,3f,4f),fusionEnabled=true),
            progressiveBlur=LumenProgressiveBlurOptions(enabled=true),press=LumenLocalPressOptions(enabled=true,rippleEnabled=true),light=LumenSurfaceLightOptions(enabled=true),
            quality=LumenSurfaceQualityOptions(enabled=true,adaptive=true),debug=LumenSurfaceDebugOptions(drawSamplingBounds=true)),seed=123)
        assertEquals(preset,LumenEffectPreset.fromJson(preset.toJson()))
        val unknown=org.json.JSONObject(preset.toJson()).put("unknownFutureField",123).toString()
        assertEquals(preset,LumenEffectPreset.fromJson(unknown))
        assertThrows(IllegalArgumentException::class.java){LumenEffectPreset.fromJson(org.json.JSONObject(preset.toJson()).put("schema",2).toString())}
        val invalid=org.json.JSONObject(preset.toJson());invalid.getJSONObject("press").put("enabled","true")
        assertThrows(IllegalArgumentException::class.java){LumenEffectPreset.fromJson(invalid.toString())}
        for((key,value)in listOf("geometry" to 1,"press" to org.json.JSONObject.NULL,"schema" to 1.5,"colorAssumption" to "LINEAR_HDR")){
            assertThrows(IllegalArgumentException::class.java){LumenEffectPreset.fromJson(org.json.JSONObject(preset.toJson()).put(key,value).toString())}
        }
        val badCorners=org.json.JSONObject(preset.toJson());badCorners.getJSONObject("geometry").put("corners",org.json.JSONArray().put("1").put(2).put(3).put(4))
        assertThrows(IllegalArgumentException::class.java){LumenEffectPreset.fromJson(badCorners.toString())}
        assertThrows(IllegalArgumentException::class.java){LumenEffectPreset.fromJson("{\"schema\":1,\"future\":"+"[".repeat(16)+"0"+"]".repeat(16)+"}")}
    }
    @Test fun prohibitedDependencyStopsBeforeFirstCaptureAndRecoversAfterAvailabilityChanges(){
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {scenario->
            ready(scenario)
            var available=LumenSourceAvailability.PROHIBITED;var epoch=1L;var draws=0
            var dependencies=emptyList<LumenContentSource>();var currentSource:LumenContentSource?=null
            scenario.onActivity {a->
                a.binding.close()
                val dependency=object:LumenVersionedContentSource {
                    override val coordinateView get()=a.content
                    override val sourceEpoch get()=epoch
                    override val contentVersion get()=0L
                    override val availability get()=available
                    override val dependencies get()=dependencies
                    override fun drawContent(canvas:android.graphics.Canvas)=Unit
                }
                val source=object:LumenVersionedContentSource {
                    override val coordinateView get()=a.content
                    override val excludesSurfaces get()=true
                    override val sourceEpoch get()=0L
                    override val contentVersion get()=0L
                    override val availability get()=LumenSourceAvailability.READY
                    override val dependencies=listOf<LumenContentSource>(dependency)
                    override fun drawContent(canvas:android.graphics.Canvas){draws++;a.content.draw(canvas)}
                }
                currentSource=source
                a.binding=a.session.bindSource(a.glass,LumenSurfaceOptions(sampling=LumenSurfaceSampling(backend=LumenSurfaceBackend.SOFTWARE)),source)
                val bitmap=Bitmap.createBitmap(a.glass.width,a.glass.height,Bitmap.Config.ARGB_8888)
                a.glass.background.draw(android.graphics.Canvas(bitmap));bitmap.recycle()
                assertEquals(LumenSurfaceFailure.NO_SOURCE,a.binding.diagnostics()?.failure)
                assertEquals(0,draws);assertEquals(LumenSourceAvailability.PROHIBITED,a.binding.samplingRegions()?.availability)
                available=LumenSourceAvailability.READY;a.session.notifyContentChanged()
            }
            var captured=false;val end=SystemClock.uptimeMillis()+5000
            while(!captured&&SystemClock.uptimeMillis()<end){scenario.onActivity {captured=it.binding.diagnostics()?.backend==LumenSurfaceBackend.SOFTWARE};if(!captured)SystemClock.sleep(25)}
            assertTrue(captured)
            scenario.onActivity {a->
                assertTrue(draws>0);val oldEpoch=a.binding.samplingRegions()!!.sourceEpoch
                available=LumenSourceAvailability.TEMPORARILY_UNAVAILABLE;epoch++;a.session.notifyContentChanged()
                a.content.invalidate();a.glass.invalidate()
                assertTrue(oldEpoch>0L)
            }
            SystemClock.sleep(100)
            scenario.onActivity {a->assertEquals(LumenSurfaceBackend.STATIC,a.binding.diagnostics()?.backend);assertEquals(0L,a.session.performanceDiagnostics().activeSoftwareBytes);assertNull(a.binding.samplingRegions()?.estimatedSourceAgeNanos)}
            scenario.onActivity {a->available=LumenSourceAvailability.READY;dependencies=listOf(currentSource!!);epoch++;a.session.notifyContentChanged()}
            SystemClock.sleep(100)
            scenario.onActivity {a->assertEquals(LumenSurfaceFailure.SELF_FEEDBACK,a.binding.diagnostics()?.failure);assertEquals(0L,a.session.performanceDiagnostics().activeSoftwareBytes)}
        }
    }
    @Test fun fusedSelectorKeepsBusinessSelectionAndRestoresOnlyOwnedResources(){
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {scenario->
            ready(scenario);var choice:com.lumen.coacervation.engine.widget.LumenSlidingSelection?=null
            var decorator:com.lumen.coacervation.engine.widget.LumenFusedSelection?=null
            var selected=-1;var moving=false;var notifications=0
            val original=android.graphics.drawable.ColorDrawable(Color.GREEN)
            val listener=com.lumen.coacervation.engine.widget.LumenSlidingSelection.OnGeometryListener {_,_,_,_,_,_,_,_,inMotion->moving=inMotion;notifications++}
            scenario.onActivity {a->
                choice=com.lumen.coacervation.engine.widget.LumenSlidingSelection(a,original,android.widget.LinearLayout.HORIZONTAL).also {c->
                    repeat(3){c.addOption(android.widget.TextView(a).apply {text="选项 $it"},android.widget.LinearLayout.LayoutParams(0,64,1f))}
                    c.onSelect={selected=it};c.setOnGeometryListener(listener)
                    a.preview.addView(c,android.widget.FrameLayout.LayoutParams(300,64).apply {topMargin=10})
                    decorator=com.lumen.coacervation.engine.widget.LumenFusedSelection(c,com.lumen.coacervation.engine.model.LumenPalette.neutral(false))
                }
            }
            SystemClock.sleep(100);scenario.onActivity {choice!!.options.getChildAt(2).performClick()}
            SystemClock.sleep(50);scenario.onActivity {choice!!.options.getChildAt(1).performClick()}
            SystemClock.sleep(500)
            scenario.onActivity {
                decorator!!.update(LumenSurfaceOptions(radiusDp=0f,color=Color.WHITE,tintOpacity=1f,edgeEnabled=false,
                    sampling=LumenSurfaceSampling(blurEnabled=false,refractionEnabled=false)),
                    LumenSurfaceEnhancements(geometry=LumenSurfaceGeometryOptions(fusionEnabled=true,shadowEnabled=true,shadowRadiusDp=24f)))
            }
            SystemClock.sleep(250)
            val imageLatch=CountDownLatch(1);var frame:Bitmap?=null;var copy=-1;var selectedX=0;var selectedY=0
            scenario.onActivity {a->
                val row=choice!!.options.getChildAt(1);val location=IntArray(2);row.getLocationInWindow(location)
                selectedX=location[0]+row.width/2;selectedY=location[1]+row.height/2
                frame=Bitmap.createBitmap(a.window.decorView.width,a.window.decorView.height,Bitmap.Config.ARGB_8888)
                PixelCopy.request(a.window,frame!!,{copy=it;imageLatch.countDown()},Handler(Looper.getMainLooper()))
            }
            assertTrue(imageLatch.await(5,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,copy)
            val selectedPixel=frame!!.getPixel(selectedX,selectedY)
            assertTrue("Changing halo moved the selected entity",Color.red(selectedPixel)>245&&Color.green(selectedPixel)>245&&Color.blue(selectedPixel)>245)
            frame!!.recycle()
            scenario.onActivity {
                assertEquals(1,selected);assertEquals(1,choice!!.selectedIndex);assertTrue(choice!!.options.getChildAt(1).isSelected)
                assertTrue(notifications>1);assertFalse(moving)
                decorator!!.update(LumenSurfaceOptions(sampling=LumenSurfaceSampling(blurEnabled=false,refractionEnabled=false)),LumenSurfaceEnhancements(geometry=LumenSurfaceGeometryOptions(fusionEnabled=true)))
                decorator!!.setSharedNodeBaseline(true)
                assertNotSame(original,choice!!.indicator.background)
                choice!!.select(2,animate=false)
            }
            var baselineFrame=false;val deadline=SystemClock.uptimeMillis()+5000
            while(!baselineFrame&&SystemClock.uptimeMillis()<deadline){
                scenario.onActivity {baselineFrame=decorator!!.diagnostics()?.backend==(if(android.os.Build.VERSION.SDK_INT>=31)LumenSurfaceBackend.GPU else LumenSurfaceBackend.SOFTWARE)}
                if(!baselineFrame)SystemClock.sleep(25)
            }
            assertTrue("Shared source baseline never drew",baselineFrame)
            scenario.onActivity {
                decorator!!.setSharedNodeBaseline(false)
                decorator!!.close();assertSame(original,choice!!.indicator.background);assertSame(listener,choice!!.getOnGeometryListener())
                decorator=com.lumen.coacervation.engine.widget.LumenFusedSelection(choice!!,com.lumen.coacervation.engine.model.LumenPalette.neutral(false))
                val later=com.lumen.coacervation.engine.widget.LumenSlidingSelection.OnGeometryListener {_,_,_,_,_,_,_,_,_->}
                choice!!.setOnGeometryListener(later);val background=android.graphics.drawable.ColorDrawable(Color.RED);choice!!.indicator.background=background
                decorator!!.close();assertSame(later,choice!!.getOnGeometryListener());assertSame(background,choice!!.indicator.background)
            }
        }
    }
}
