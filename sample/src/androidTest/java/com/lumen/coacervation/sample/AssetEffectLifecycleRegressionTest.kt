package com.lumen.coacervation.sample

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.assets.*
import com.lumen.coacervation.engine.assets.rive.LumenRiveFactory
import com.lumen.coacervation.engine.effects.*
import com.lumen.coacervation.engine.motion.LumenFixedFrameClock
import com.lumen.coacervation.engine.motion.LumenFrameClock
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android callbacks and pinned SDK ownership; reflection reads diagnostic ref counts only. */
@RunWith(AndroidJUnit4::class)
class AssetEffectLifecycleRegressionTest {
    @get:org.junit.Rule val hardwareOutput=HardwareOutputRule()

    private fun await(scenario:ActivityScenario<P2SandboxActivity>,message:String,condition:(P2SandboxActivity)->Boolean){
        val deadline=SystemClock.uptimeMillis()+5000
        var done=false
        while(!done&&SystemClock.uptimeMillis()<deadline){scenario.onActivity{done=condition(it)};if(!done)SystemClock.sleep(20)}
        assertTrue(message,done)
    }
    private fun ready(scenario:ActivityScenario<P2SandboxActivity>){
        await(scenario,"Test window must be laid out and focused"){it.target.width>0&&it.target.hasWindowFocus()}
        var width=0;var height=0
        scenario.onActivity{a->
            width=minOf(a.target.width,480);height=minOf(a.target.height,320)
            a.target.layoutParams=a.target.layoutParams.apply{this.width=width;this.height=height}
        }
        await(scenario,"Bounded fixture must complete layout"){it.target.width==width&&it.target.height==height&&!it.target.isLayoutRequested&&it.target.hasWindowFocus()}
    }
    private fun field(value:Any,name:String):Any=value.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(value)!!
    private fun refs(value:Any):Int=(value.javaClass.getMethod("getRefs").invoke(value) as AtomicInteger).get()
    private fun controller(player:LumenAssetPlayer):Any=player.view.javaClass.getMethod("getController").invoke(player.view)
    private fun loader(player:LumenAssetPlayer):Any {
        val attributes=player.view.javaClass.getMethod("getRendererAttributes").invoke(player.view)
        return attributes.javaClass.getMethod("getAssetLoader").invoke(attributes)
    }
    private fun assertDisposed(file:Any){
        assertEquals(0,refs(file))
        assertEquals(false,file.javaClass.getMethod("getHasCppObject").invoke(file))
    }
    private fun riveBytes():ByteArray=InstrumentationRegistry.getInstrumentation().targetContext.assets.open("p2/circle_move.riv").use{it.readBytes()}
    private class HostOwner(context:Context):ContextWrapper(context),LifecycleOwner {
        val registry=LifecycleRegistry(this)
        override val lifecycle:Lifecycle get()=registry
    }

    @Test fun neverAttachedRiveClosesWithOrWithoutAHostLifecycleOwner(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s)
            val bytes=riveBytes()
            for(withHostOwner in listOf(false,true)){
                lateinit var factory:LumenRiveFactory
                s.onActivity{factory=LumenRiveFactory(it)}
                val decoded=factory.decode(bytes,LumenAssetSelection(),LumenAssetOptions())
                val file=field(decoded,"file")
                s.onActivity{a->
                    val hostOwner=if(withHostOwner)HostOwner(a).apply{registry.currentState=Lifecycle.State.RESUMED}else null
                    val player=factory.attach(hostOwner?:a,decoded,LumenAssetOptions())
                    try{
                        assertFalse(player.view.isAttachedToWindow)
                        val controller=controller(player);val loader=loader(player)
                        assertEquals(1,refs(controller));assertEquals(2,refs(file))
                        if(hostOwner!=null)assertEquals("SDK must use the player's private owner",0,hostOwner.registry.observerCount)
                        player.close();player.close()
                        assertEquals(0,refs(controller));assertEquals(0,refs(loader));assertEquals(1,refs(file))
                        decoded.close();decoded.close();assertDisposed(file)
                        hostOwner?.registry?.currentState=Lifecycle.State.DESTROYED
                        assertEquals(0,refs(controller));assertDisposed(file)
                    }finally{player.close();decoded.close()}
                }
            }
        }
    }

    @Test fun failedRivePlayerConstructionReturnsItsFileReference(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s)
            lateinit var factory:LumenRiveFactory
            s.onActivity{factory=LumenRiveFactory(it)}
            val decoded=factory.decode(riveBytes(),LumenAssetSelection(),LumenAssetOptions())
            val file=field(decoded,"file")
            try{
                s.onActivity{a->
                    // configure fails after setRiveFile acquired the controller's file reference.
                    assertThrows(IllegalArgumentException::class.java){factory.attach(a,decoded,LumenAssetOptions(speed=2f))}
                    assertEquals("Failed player construction must return its reference",1,refs(file))
                }
            }finally{decoded.close()}
            assertDisposed(file)
        }
    }

    @Test fun attachedRiveReturnsLifecycleAndRendererReferencesInEitherCloseOrder(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s)
            lateinit var factory:LumenRiveFactory
            s.onActivity{factory=LumenRiveFactory(it)}
            for(closeBeforeDetach in listOf(false,true)){
                val decoded=factory.decode(riveBytes(),LumenAssetSelection(),LumenAssetOptions())
                val file=field(decoded,"file")
                lateinit var player:LumenAssetPlayer
                lateinit var observedController:Any
                s.onActivity{a->
                    player=factory.attach(a,decoded,LumenAssetOptions());observedController=controller(player)
                    a.assetContainer.addView(player.view,FrameLayout.LayoutParams(100,100))
                    assertTrue(player.view.isAttachedToWindow);assertEquals(2,refs(observedController))
                    if(closeBeforeDetach){player.close();assertEquals(1,refs(observedController));decoded.close();assertEquals(1,refs(file))}
                    a.assetContainer.removeView(player.view)
                }
                try{
                    await(s,"Renderer worker must finish returning its own reference"){
                        if(closeBeforeDetach)refs(observedController)==0&&refs(file)==0&&file.javaClass.getMethod("getHasCppObject").invoke(file)==false
                        else refs(observedController)==1
                    }
                    s.onActivity{player.close();decoded.close();assertEquals(0,refs(observedController));assertDisposed(file)}
                }finally{s.onActivity{a->if(player.view.parent===a.assetContainer)a.assetContainer.removeView(player.view);player.close();decoded.close()}}
            }
        }
    }

    @Test fun assetMountFailureRemovesItsWrapperAndPreservesForeignChildren(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s)
            for(failAttach in listOf(true,false)){
                val decodedClosed=AtomicInteger();val playerClosed=AtomicInteger()
                lateinit var session:LumenAssetSession
                lateinit var sibling:View
                val factory=object:LumenAssetFactory {
                    override val format=LumenAssetFormat.LOTTIE
                    override fun decode(bytes:ByteArray,selection:LumenAssetSelection,options:LumenAssetOptions)=object:LumenDecodedAsset{
                        override val metadata=LumenAssetMetadata(format,10,10,1000,true)
                        override fun close(){decodedClosed.incrementAndGet()}
                    }
                    override fun attach(context:Context,asset:LumenDecodedAsset,options:LumenAssetOptions)=object:LumenAssetPlayer{
                        override val view=object:View(context){override fun onAttachedToWindow(){super.onAttachedToWindow();if(failAttach)throw IllegalStateException("Injected attach failure after parent insertion")}}
                        override val metadata=asset.metadata
                        override fun update(options:LumenAssetOptions)=Unit
                        override fun render(progress:Float)=true
                        override fun setPlaying(playing:Boolean)=Unit
                        override fun close(){playerClosed.incrementAndGet()}
                    }
                }
                s.onActivity{a->sibling=View(a);a.assetContainer.addView(sibling);session=LumenAssetSession(a.assetContainer);session.load(LumenAssetSource{byteArrayOf(1).inputStream()},factory)}
                try{
                    await(s,"Asset mount callback must complete"){
                        val state=session.diagnostics().state
                        if(failAttach)state==LumenAssetState.FAILED else state==LumenAssetState.READY||state==LumenAssetState.PAUSED
                    }
                    s.onActivity{a->
                        assertSame(a.assetContainer,sibling.parent)
                        assertEquals(if(failAttach)1 else 2,a.assetContainer.childCount)
                        assertEquals(if(failAttach)LumenAssetFailure.RENDERER else LumenAssetFailure.NONE,session.diagnostics().failure)
                        session.close();session.close()
                        assertEquals(1,a.assetContainer.childCount);assertSame(sibling,a.assetContainer.getChildAt(0))
                        assertEquals(1,playerClosed.get());assertEquals(1,decodedClosed.get())
                    }
                }finally{s.onActivity{a->session.close();a.assetContainer.removeView(sibling)}}
            }
        }
    }

    @Test fun wrapperRemovalFailureStillClosesBothOwnedHandles(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s)
            val playerClosed=AtomicInteger();val decodedClosed=AtomicInteger()
            lateinit var session:LumenAssetSession
            lateinit var container:FrameLayout
            val factory=object:LumenAssetFactory{
                override val format=LumenAssetFormat.LOTTIE
                override fun decode(bytes:ByteArray,selection:LumenAssetSelection,options:LumenAssetOptions)=object:LumenDecodedAsset{
                    override val metadata=LumenAssetMetadata(format,10,10,1000,true)
                    override fun close(){decodedClosed.incrementAndGet()}
                }
                override fun attach(context:Context,asset:LumenDecodedAsset,options:LumenAssetOptions)=object:LumenAssetPlayer{
                    override val view=View(context)
                    override val metadata=asset.metadata
                    override fun update(options:LumenAssetOptions)=Unit
                    override fun render(progress:Float)=true
                    override fun setPlaying(playing:Boolean)=Unit
                    override fun close(){playerClosed.incrementAndGet();throw IllegalStateException("Injected player close failure")}
                }
            }
            s.onActivity{a->
                container=object:FrameLayout(a){override fun removeView(view:View){super.removeView(view);throw IllegalStateException("Injected removal failure after removal")}}
                session=LumenAssetSession(container);session.load(LumenAssetSource{byteArrayOf(1).inputStream()},factory)
            }
            try{
                await(s,"Off-window player must mount without playing"){session.diagnostics().state==LumenAssetState.READY}
                s.onActivity{session.close();session.close();assertEquals(0,container.childCount);assertEquals(1,playerClosed.get());assertEquals(1,decodedClosed.get())}
            }finally{s.onActivity{session.close()}}
        }
    }

    private fun effectOptions(clearOnPause:Boolean)=LumenEffectOptions(
        procedural=LumenProceduralOptions(enabled=true,kind=LumenProceduralKind.ENERGY,gpuEnabled=false,energy=LumenEnergyOptions(durationMs=1000)),
        particles=LumenParticleOptions(enabled=true,lifetimeMs=1000),maximumRenderPixels=1048576,clearOnPause=clearOnPause)
    private fun phase(layer:LumenEffectLayer):Float {
        val drawable=field(layer,"drawable")
        return drawable.javaClass.getMethod("getPhase").invoke(drawable) as Float
    }

    @Test fun visibilityRestoresRetainedTicksAndFreezesBothLifetimes(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s)
            var now=0L
            var live=0
            lateinit var layer:LumenEffectLayer
            s.onActivity{a->
                a.effect.close();layer=LumenEffectLayer(a.target,effectOptions(false),LumenFrameClock{now})
                assertFalse(layer.diagnostics().scheduled);assertEquals(0,layer.diagnostics().liveParticles)
                assertTrue(layer.emitBurst(10f,10f)>0);layer.triggerEnergy()
                now=250_000_000L;layer.advanceFrame()
                live=layer.diagnostics().liveParticles;assertEquals(.25f,phase(layer),0f)
                a.target.visibility=View.INVISIBLE
            }
            try{
                await(s,"Android visibility observation must suspend retained effects"){layer.diagnostics().state==LumenEffectState.HIDDEN&&!layer.diagnostics().scheduled}
                s.onActivity{a->now=5_000_000_000L;a.target.visibility=View.VISIBLE}
                await(s,"Android pre-draw must restore the existing tick"){layer.diagnostics().state==LumenEffectState.ACTIVE&&layer.diagnostics().scheduled}
                s.onActivity{a->
                    assertTrue(a.target.hasWindowFocus());assertTrue(a.target.isAttachedToWindow)
                    assertEquals("Showing must preserve the existing particles",live,layer.diagnostics().liveParticles);assertEquals(.25f,phase(layer),0f)
                    repeat(3){layer.onPreDraw()};assertTrue(layer.diagnostics().scheduled)
                    now+=749_000_000L;layer.advanceFrame();assertTrue(layer.diagnostics().liveParticles>0);assertEquals(.999f,phase(layer),.00001f)
                    now+=1_000_000L;layer.advanceFrame();assertEquals(0,layer.diagnostics().liveParticles);assertEquals(LumenEffectState.IDLE,layer.diagnostics().state)
                }
                await(s,"Finite retained effects must stop their tick"){!layer.diagnostics().scheduled}
            }finally{s.onActivity{layer.close();it.target.visibility=View.VISIBLE}}
        }
    }

    @Test fun defaultVisibilityClearAndEmptyLayerDoNotEmitOrSchedule(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s);s.onActivity{a->
            a.effect.close();val layer=LumenEffectLayer(a.target,effectOptions(true))
            try{
                a.target.visibility=View.INVISIBLE;layer.onPreDraw();a.target.visibility=View.VISIBLE;layer.onPreDraw()
                assertEquals(0,layer.diagnostics().liveParticles);assertFalse(layer.diagnostics().scheduled)
                assertTrue(layer.emitBurst(10f,10f)>0);layer.triggerEnergy()
                a.target.visibility=View.INVISIBLE;layer.onPreDraw();a.target.visibility=View.VISIBLE;layer.onPreDraw()
                assertEquals(0,layer.diagnostics().liveParticles);assertEquals(0f,phase(layer),0f);assertFalse(layer.diagnostics().scheduled)
            }finally{layer.close();a.target.visibility=View.VISIBLE}
        }}
    }

    @Test fun fixedClockVisibilityRestoresFrozenStateWithoutScheduling(){
        ActivityScenario.launch(P2SandboxActivity::class.java).use{s->ready(s);s.onActivity{a->
            a.effect.close();val clock=LumenFixedFrameClock();val layer=LumenEffectLayer(a.target,effectOptions(false),clock)
            try{
                assertTrue(layer.emitBurst(10f,10f)>0);layer.triggerEnergy();clock.advanceMillis(250);layer.advanceFrame()
                a.target.visibility=View.INVISIBLE;layer.onPreDraw();clock.advanceMillis(5000);a.target.visibility=View.VISIBLE;layer.onPreDraw()
                assertFalse(layer.diagnostics().scheduled);assertEquals(.25f,phase(layer),0f)
                clock.advanceMillis(750);layer.advanceFrame();assertEquals(0,layer.diagnostics().liveParticles);assertEquals(LumenEffectState.IDLE,layer.diagnostics().state)
            }finally{layer.close();a.target.visibility=View.VISIBLE}
        }}
    }
}
