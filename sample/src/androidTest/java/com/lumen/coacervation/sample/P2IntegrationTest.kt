package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.assets.*
import com.lumen.coacervation.engine.effects.*
import com.lumen.coacervation.engine.motion.LumenFixedFrameClock
import com.lumen.coacervation.engine.sensor.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class P2IntegrationTest {
    @get:org.junit.Rule val hardwareOutput=HardwareOutputRule()
    private fun ready(s:ActivityScenario<P2SandboxActivity>){var ready=false;val end=SystemClock.uptimeMillis()+5000
        while(!ready&&SystemClock.uptimeMillis()<end){s.onActivity{ready=it.target.width>0&&it.target.hasWindowFocus()};if(!ready)SystemClock.sleep(20)};assertTrue(ready)}
    private fun window(s:ActivityScenario<P2SandboxActivity>):Bitmap{
        val latch=CountDownLatch(1);var bitmap:Bitmap?=null;var result=-1
        s.onActivity{a->bitmap=Bitmap.createBitmap(a.window.decorView.width,a.window.decorView.height,Bitmap.Config.ARGB_8888);PixelCopy.request(a.window,bitmap!!,{result=it;latch.countDown()},Handler(Looper.getMainLooper()))}
        assertTrue(latch.await(5,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result);return bitmap!!
    }
    private fun differences(a:Bitmap,b:Bitmap,region:IntArray):Int{
        var changes=0;for(y in region[1] until region[1]+region[3] step 4)for(x in region[0] until region[0]+region[2] step 4)if(a.getPixel(x,y)!=b.getPixel(x,y))changes++;return changes
    }
    private fun region(view:View)=IntArray(4).also{view.getLocationInWindow(it);it[2]=view.width;it[3]=view.height}
    @Test fun sensorIsOptInAndStopsHiddenPausedOrClosed(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s)
            var starts=0;var stops=0;var updates=0;var raw:LumenGravityListener?=null;var sensor:LumenSensorLightController?=null
            s.onActivity{a->
                val input=object:LumenGravityInput{override val sensorType=9;override fun start(rateHz:Int,allowAccelerometerFallback:Boolean,listener:LumenGravityListener):Boolean{starts++;raw=listener;return true};override fun stop(){stops++}}
                sensor=LumenSensorLightController(a.target,LumenLightVectorListener{_,_,_->updates++},input=input)
                sensor!!.start();assertEquals(0,starts)
                sensor!!.update(LumenSensorLightOptions(enabled=true,smoothingTimeMs=0));assertEquals(1,starts)
                raw!!.onGravity(1f,0f,9f,1L);assertEquals(1,updates)
                a.target.visibility=View.INVISIBLE
            }
            SystemClock.sleep(100)
            s.onActivity{a->assertEquals(LumenSensorLightState.HIDDEN,sensor!!.diagnostics().state);assertEquals(1,stops);raw!!.onGravity(2f,0f,9f,40_000_000);assertEquals(1,updates);a.target.visibility=View.VISIBLE}
            SystemClock.sleep(100)
            s.onActivity{sensor!!.stop();val count=updates;raw!!.onGravity(3f,0f,9f,100_000_000);assertEquals(count,updates);sensor!!.close();sensor!!.start();assertEquals(LumenSensorLightState.CLOSED,sensor!!.diagnostics().state)}
        }
    }
    @Test fun fixedParticlesExpireAndDecorationPreservesBusinessView(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s);s.onActivity{a->
            a.effect.close();val original=a.target.background;val parent=a.target.parent;val clock=LumenFixedFrameClock()
            val options=LumenEffectOptions(particles=LumenParticleOptions(enabled=true,lifetimeMs=100))
            val layer=LumenEffectLayer(a.target,options,clock);assertTrue(layer.emitBurst(a.target.width*.5f,a.target.height*.5f)>0)
            assertFalse(layer.diagnostics().scheduled);assertEquals(0L,layer.diagnostics().captureRequests)
            clock.advanceMillis(100);layer.advanceFrame();assertEquals(0,layer.diagnostics().liveParticles)
            assertSame(original,a.target.background);assertSame(parent,a.target.parent)
            layer.close();layer.emitBurst(0f,0f);assertEquals(LumenEffectState.CLOSED,layer.diagnostics().state)
        }}
    }
    @Test fun parameterEnvelopeRoundTripsAndRejectsWrongKnownTypes(){
        val options=LumenEffectOptions(procedural=LumenProceduralOptions(enabled=true,kind=LumenProceduralKind.PAPER),particles=LumenParticleOptions(enabled=true),seed=712)
        val preset=LumenEffectPreset(options);assertEquals(preset,LumenEffectPreset.fromJson(preset.toJson()))
        val json=org.json.JSONObject(preset.toJson());json.getJSONObject("particles").put("maximumParticles","128")
        assertThrows(IllegalArgumentException::class.java){LumenEffectPreset.fromJson(json.toString())}
    }
    @Test fun preservedParticlesFreezeDuringPauseAndResumeWithTheirRemainingLifetime(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s);s.onActivity{a->
            a.effect.close();val clock=LumenFixedFrameClock();val layer=LumenEffectLayer(a.target,LumenEffectOptions(particles=LumenParticleOptions(enabled=true,lifetimeMs=100),clearOnPause=false),clock)
            assertTrue(layer.emitBurst(50f,50f)>0);layer.pause();clock.advanceMillis(1000);layer.resume();layer.advanceFrame();assertTrue(layer.diagnostics().liveParticles>0)
            clock.advanceMillis(100);layer.advanceFrame();assertEquals(0,layer.diagnostics().liveParticles);assertFalse(layer.diagnostics().scheduled);layer.close()
        }}
    }
    @Test fun staleDecodeClosesItsHandleAndRendererFailurePreservesForeignChildren(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s)
            val started=CountDownLatch(1);val finish=CountDownLatch(1);val closed=AtomicInteger();val attached=AtomicInteger();var session:LumenAssetSession?=null;var foreign:View?=null
            val factory=object:LumenAssetFactory{
                override val format=LumenAssetFormat.LOTTIE
                override fun decode(bytes:ByteArray,selection:LumenAssetSelection,options:LumenAssetOptions):LumenDecodedAsset{
                    if(bytes[0].toInt()==1){started.countDown();val deadline=SystemClock.uptimeMillis()+5000;var done=false;while(!done&&SystemClock.uptimeMillis()<deadline)try{done=finish.await(50,TimeUnit.MILLISECONDS)}catch(_:InterruptedException){}}
                    return object:LumenDecodedAsset{override val metadata=LumenAssetMetadata(format,10,10,1000,true);override fun close(){closed.incrementAndGet()}}
                }
                override fun attach(context:android.content.Context,asset:LumenDecodedAsset,options:LumenAssetOptions):LumenAssetPlayer{
                    attached.incrementAndGet();return object:LumenAssetPlayer{
                        override val view=View(context);override val metadata=asset.metadata
                        override fun update(options:LumenAssetOptions)=Unit
                        override fun render(progress:Float):Boolean=throw IllegalStateException("Decorative renderer failed")
                        override fun setPlaying(playing:Boolean)=Unit
                        override fun close()=Unit
                    }
                }
            }
            s.onActivity{a->foreign=View(a);a.assetContainer.addView(foreign);session=LumenAssetSession(a.assetContainer);session!!.load(LumenAssetSource{byteArrayOf(1).inputStream()},factory)}
            assertTrue(started.await(5,TimeUnit.SECONDS))
            s.onActivity{session!!.load(LumenAssetSource{byteArrayOf(2).inputStream()},factory)};finish.countDown()
            val end=SystemClock.uptimeMillis()+5000;var failed=false
            while(!failed&&SystemClock.uptimeMillis()<end){s.onActivity{failed=session!!.diagnostics().state==LumenAssetState.FAILED};if(!failed)SystemClock.sleep(20)}
            assertTrue(failed);assertEquals(1,attached.get());assertEquals(2,closed.get())
            s.onActivity{a->assertEquals(1L,session!!.diagnostics().dropped);assertEquals(LumenAssetFailure.RENDERER,session!!.diagnostics().failure);assertSame(a.assetContainer,foreign!!.parent);session!!.close();session!!.play();assertSame(a.assetContainer,foreign!!.parent)}
        }
    }
    @Test fun oversizedStreamNeverInvokesTheParser(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s);var session:LumenAssetSession?=null;val parsed=AtomicInteger()
            val factory=object:LumenAssetFactory{override val format=LumenAssetFormat.LOTTIE
                override fun decode(bytes:ByteArray,selection:LumenAssetSelection,options:LumenAssetOptions):LumenDecodedAsset{parsed.incrementAndGet();throw IllegalStateException()}
                override fun attach(context:android.content.Context,asset:LumenDecodedAsset,options:LumenAssetOptions):LumenAssetPlayer=throw IllegalStateException()}
            s.onActivity{session=LumenAssetSession(it.assetContainer,LumenAssetOptions(maximumFileBytes=1024));session!!.load(LumenAssetSource{ByteArray(1025).inputStream()},factory)}
            val end=SystemClock.uptimeMillis()+5000;var rejected=false
            while(!rejected&&SystemClock.uptimeMillis()<end){s.onActivity{rejected=session!!.diagnostics().state==LumenAssetState.REJECTED};if(!rejected)SystemClock.sleep(20)}
            assertTrue(rejected);assertEquals(0,parsed.get());s.onActivity{assertEquals(LumenAssetFailure.TOO_LARGE,session!!.diagnostics().failure);session!!.close()}
        }
    }
    @Test fun shrinkingTheByteBudgetDuringLoadRejectsTheCompletedOldPolicy(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s);var session:LumenAssetSession?=null
            val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val json=org.json.JSONObject(context.assets.open("p2/circle.json").bufferedReader().use{it.readText()}).put("unusedPadding","x".repeat(2048)).toString().toByteArray()
            s.onActivity{session=LumenAssetSession(it.assetContainer);session!!.load(LumenAssetSource{json.inputStream()},com.lumen.coacervation.engine.assets.lottie.LumenLottieFactory());session!!.update(LumenAssetOptions(maximumFileBytes=1024))}
            val end=SystemClock.uptimeMillis()+5000;var rejected=false
            while(!rejected&&SystemClock.uptimeMillis()<end){s.onActivity{rejected=session!!.diagnostics().state==LumenAssetState.REJECTED};if(!rejected)SystemClock.sleep(20)}
            assertTrue(rejected);s.onActivity{assertEquals(LumenAssetFailure.TOO_LARGE,session!!.diagnostics().failure);assertNull(session!!.diagnostics().metadata);session!!.close()}
        }
    }
    @Test fun displayPlayerDoesNotReceiveBusinessTouchesOrWakeFromPause(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s);var session:LumenAssetSession?=null;val vendorTouches=AtomicInteger();val businessClicks=AtomicInteger()
            val factory=object:LumenAssetFactory{override val format=LumenAssetFormat.LOTTIE
                override fun decode(bytes:ByteArray,selection:LumenAssetSelection,options:LumenAssetOptions):LumenDecodedAsset=object:LumenDecodedAsset{override val metadata=LumenAssetMetadata(format,10,10,1000,true);override fun close()=Unit}
                override fun attach(context:android.content.Context,asset:LumenDecodedAsset,options:LumenAssetOptions):LumenAssetPlayer=object:LumenAssetPlayer{
                    override val view=object:View(context){override fun onTouchEvent(event:android.view.MotionEvent):Boolean{vendorTouches.incrementAndGet();return true}}
                    override val metadata=asset.metadata;override fun update(options:LumenAssetOptions)=Unit;override fun render(progress:Float)=true;override fun setPlaying(playing:Boolean)=Unit;override fun close()=Unit}
            }
            s.onActivity{a->a.assetContainer.addView(View(a).apply{setOnClickListener{businessClicks.incrementAndGet()}},android.widget.FrameLayout.LayoutParams(-1,-1));session=LumenAssetSession(a.assetContainer);session!!.load(LumenAssetSource{byteArrayOf(1).inputStream()},factory)}
            val end=SystemClock.uptimeMillis()+5000;var loaded=false
            while(!loaded&&SystemClock.uptimeMillis()<end){s.onActivity{loaded=session!!.diagnostics().metadata!=null};if(!loaded)SystemClock.sleep(20)}
            assertTrue(loaded);SystemClock.sleep(100)
            s.onActivity{a->val time=SystemClock.uptimeMillis();for(action in intArrayOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){
                val event=android.view.MotionEvent.obtain(time,time,action,50f,50f,0);assertTrue(a.assetContainer.dispatchTouchEvent(event));event.recycle()}
                assertEquals(0,vendorTouches.get());assertEquals(LumenAssetState.PAUSED,session!!.diagnostics().state)}
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity{assertEquals(1,businessClicks.get());session!!.close()}
        }
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun proceduralGeneratorsActuallyDrawAndDoNotRecompileForParticleFrames(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s)
            SystemClock.sleep(200);val baseline=window(s);var area:IntArray?=null;s.onActivity{area=region(it.target)}
            s.onActivity{it.effect.update(LumenEffectOptions(procedural=LumenProceduralOptions(enabled=true,opacity=1f,color=Color.WHITE,film=LumenFilmOptions(iridescence=1f)),particles=LumenParticleOptions(enabled=true)))}
            SystemClock.sleep(200)
            var before=0L;s.onActivity{before=it.effect.diagnostics().shaderBuilds;assertEquals(1L,before);it.effect.emitBurst(it.target.width*.5f,it.target.height*.5f)}
            SystemClock.sleep(200);val latch=CountDownLatch(1);var bitmap:Bitmap?=null;var result=-1
            s.onActivity{a->bitmap=Bitmap.createBitmap(a.window.decorView.width,a.window.decorView.height,Bitmap.Config.ARGB_8888);PixelCopy.request(a.window,bitmap!!,{result=it;latch.countDown()},Handler(Looper.getMainLooper()))}
            assertTrue(latch.await(5,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
            assertTrue("Film produced no window pixel change",differences(baseline,bitmap!!,area!!)>10)
            s.onActivity{assertEquals(before,it.effect.diagnostics().shaderBuilds);assertFalse(it.effect.diagnostics().shaderFallback);assertEquals(0L,it.effect.diagnostics().captureRequests)};bitmap!!.recycle()
            s.onActivity{it.effect.update(LumenEffectOptions(procedural=LumenProceduralOptions(enabled=true,kind=LumenProceduralKind.PAPER,opacity=1f,color=Color.WHITE)))}
            SystemClock.sleep(100);val paper=window(s);assertTrue(differences(baseline,paper,area!!)>10);paper.recycle()
            val clock=LumenFixedFrameClock()
            s.onActivity{it.effect.close();it.effect=LumenEffectLayer(it.target,LumenEffectOptions(procedural=LumenProceduralOptions(enabled=true,kind=LumenProceduralKind.ENERGY,opacity=1f,color=Color.MAGENTA)),clock);it.effect.triggerEnergy();clock.advanceMillis(450);it.effect.advanceFrame()}
            SystemClock.sleep(100);val energy=window(s);assertTrue(differences(baseline,energy,area!!)>10);energy.recycle()
            s.onActivity{clock.advanceMillis(450);it.effect.advanceFrame();assertFalse(it.effect.diagnostics().scheduled);assertFalse(it.effect.diagnostics().shaderFallback)}
            SystemClock.sleep(100);val ended=window(s);assertEquals("Energy remains opaque after finite lifetime",0,differences(baseline,ended,area!!));ended.recycle()
            s.onActivity{it.effect.triggerEnergy();clock.advanceMillis(300);it.effect.advanceFrame();it.effect.update(LumenEffectOptions(procedural=LumenProceduralOptions(enabled=true,kind=LumenProceduralKind.ENERGY,opacity=1f,color=Color.MAGENTA,energy=LumenEnergyOptions(durationMs=1200))));assertFalse(it.effect.diagnostics().scheduled)}
            SystemClock.sleep(100);val cancelled=window(s);assertEquals("Changing energy parameters leaves a stale phase",0,differences(baseline,cancelled,area!!));cancelled.recycle();baseline.recycle()
        }
    }
    @Test fun lottieLoadsWithoutImplicitPlaybackAndCanClose()=bundledFormat(LumenAssetFormat.LOTTIE)
    @Test fun pagLoadsWithoutImplicitPlaybackAndCanClose()=bundledFormat(LumenAssetFormat.PAG)
    @Test fun riveLoadsWithoutImplicitPlaybackAndCanClose()=bundledFormat(LumenAssetFormat.RIVE)
    @Test fun riveStateInputsValidateNamesAndTypesAndUnknownDurationHasATimeLimit(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s);var session:LumenAssetSession?=null
            val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
            s.onActivity{a->session=LumenAssetSession(a.assetContainer,LumenAssetOptions(maximumDurationMs=1000,framesPerSecond=1));session!!.load(LumenAssetSource{context.assets.open("p2/state_machine_configurations.riv")},com.lumen.coacervation.engine.assets.rive.LumenRiveFactory(a),LumenAssetSelection(stateMachine="mixed"))}
            val end=SystemClock.uptimeMillis()+5000;var loaded=false;var last:LumenAssetDiagnostics?=null
            while(!loaded&&SystemClock.uptimeMillis()<end){s.onActivity{last=session!!.diagnostics();loaded=last!!.metadata!=null};if(!loaded)SystemClock.sleep(20)}
            assertTrue("State machine rejected: $last",loaded)
            s.onActivity{assertEquals(6,session!!.diagnostics().metadata!!.inputs.size);assertFalse(session!!.setNumber("zero",3f));session!!.play();assertFalse(session!!.setNumber("off",1f));assertFalse(session!!.setBoolean("missing",true));assertFalse(session!!.fire("zero"))
                assertTrue(session!!.setNumber("zero",3f));assertTrue(session!!.setBoolean("off",true));assertTrue(session!!.fire("trigger"))}
            SystemClock.sleep(200);s.onActivity{session!!.pause()};SystemClock.sleep(100);s.onActivity{session!!.resume()};SystemClock.sleep(900)
            s.onActivity{assertEquals(LumenAssetState.FINISHED,session!!.diagnostics().state);assertEquals(LumenAssetFailure.NONE,session!!.diagnostics().failure);session!!.close()}
        }
    }
    private fun bundledFormat(format:LumenAssetFormat){
        if(format==LumenAssetFormat.PAG){
            val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val file=org.libpag.PAGFile.Load(context.assets.open("p2/minimal.pag").use{it.readBytes()})
            assertNotNull("Native PAG parser rejected the official fixture",file)
            android.util.Log.i("Lumen-P2","PAG fixture ${file.width()}x${file.height()} duration=${file.duration()} videos=${file.numVideos()}")
        }
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s)
                val baseline=window(s);var area:IntArray?=null;s.onActivity{area=region(it.assetContainer)}
                s.onActivity{it.loadAsset(format)};val deadline=SystemClock.uptimeMillis()+10000;var ready=false;var last:LumenAssetDiagnostics?=null
                while(!ready&&SystemClock.uptimeMillis()<deadline){s.onActivity{last=it.assetsSession?.diagnostics();ready=last?.metadata!=null};if(!ready)SystemClock.sleep(25)}
                assertTrue("$format load failed: $last",ready)
                s.onActivity{assertEquals("$format started without autoplay",LumenAssetState.PAUSED,it.assetsSession!!.diagnostics().state)}
                s.onActivity{it.assetsSession!!.play();if(format!=LumenAssetFormat.RIVE)it.assetsSession!!.seek(.5f)}
                val drawDeadline=SystemClock.uptimeMillis()+5000;var drawn=false
                while(!drawn&&SystemClock.uptimeMillis()<drawDeadline){SystemClock.sleep(100);val rendered=window(s);drawn=differences(baseline,rendered,area!!)>10;rendered.recycle()}
                assertTrue("$format loaded metadata but drew no visible pixels",drawn)
                s.onActivity{it.assetsSession!!.pause()};SystemClock.sleep(100);val paused=window(s);SystemClock.sleep(200);val later=window(s)
                assertEquals("$format keeps rendering after pause",0,differences(paused,later,area!!));paused.recycle();later.recycle()
                s.onActivity{it.assetsSession!!.update(LumenAssetOptions(enabled=false))};SystemClock.sleep(100);val hidden=window(s)
                assertEquals("$format disabled view is still visible",0,differences(baseline,hidden,area!!));hidden.recycle();baseline.recycle()
                s.onActivity{a->assertFalse(a.assetsSession!!.diagnostics().inFlight);a.assetsSession!!.pause();a.assetsSession!!.close();assertEquals(LumenAssetState.CLOSED,a.assetsSession!!.diagnostics().state)}
        }
    }
}
