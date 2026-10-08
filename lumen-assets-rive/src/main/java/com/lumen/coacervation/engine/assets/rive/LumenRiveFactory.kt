@file:Suppress("DEPRECATION")
package com.lumen.coacervation.engine.assets.rive

import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.File
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.Loop
import app.rive.runtime.kotlin.core.RendererType
import app.rive.runtime.kotlin.core.Rive
import com.lumen.coacervation.engine.assets.*

/** Typed View compatibility adapter for pinned 11.14. Native clock/state machines are never claimed seekable. */
public class LumenRiveFactory(context:Context):LumenAssetFactory {
    private val context=context.applicationContext?:context
    override val format=LumenAssetFormat.RIVE
    override fun decode(bytes:ByteArray,selection:LumenAssetSelection,options:LumenAssetOptions):LumenDecodedAsset {
        Rive.init(context)
        val file=File(bytes,RendererType.Rive)
        try{
            val artboardName=selection.artboard;val animationName=selection.animation
            val artboard=if(artboardName!=null)file.artboard(artboardName)else file.artboard(0)
            var duration=0L
            var resolvedAnimation:String?=null
            val machine=selection.stateMachine
            val inputs=ArrayList<LumenAssetInput>()
            if(machine!=null){val state=artboard.stateMachine(machine)
                if(state.inputCount>1024)throw LumenAssetException(LumenAssetFailure.BUDGET)
                val names=HashSet<String>()
                for(index in 0 until state.inputCount){val input=state.input(index);val name=input.name
                    require(name.length in 1..128&&names.add(name))
                    val kind=when{input.isNumber->LumenAssetInputKind.NUMBER;input.isBoolean->LumenAssetInputKind.BOOLEAN;input.isTrigger->LumenAssetInputKind.TRIGGER;else->throw LumenAssetException(LumenAssetFailure.UNSUPPORTED_OPERATION)}
                    inputs.add(LumenAssetInput(name,kind))}
            }
            else if(artboard.animationCount>0){val animation=if(animationName!=null)artboard.animation(animationName)else artboard.animation(0);duration=(animation.effectiveDurationInSeconds*1000).toLong();resolvedAnimation=animation.name}
            val metadata=LumenAssetMetadata(format,artboard.width.toInt(),artboard.height.toInt(),duration,false,nativeClock=true,speedControl=false,repeatControl=false,frameRateControl=false,inputs=inputs.toList())
            if(metadata.width.toLong()*metadata.height>options.maximumRenderPixels||duration>options.maximumDurationMs)throw LumenAssetException(LumenAssetFailure.BUDGET)
            return Decoded(file,selection.copy(artboard=artboard.name,animation=resolvedAnimation),metadata)
        }catch(e:Exception){file.release();throw e}
    }
    override fun attach(context:Context,asset:LumenDecodedAsset,options:LumenAssetOptions):LumenAssetPlayer=Player(context,asset as Decoded,options)
    private class Decoded(val file:File,val selection:LumenAssetSelection,override val metadata:LumenAssetMetadata):LumenDecodedAsset {
        private var closed=false
        override fun close(){if(!closed){closed=true;file.release()}}
    }
    private class OwnedLifecycle:LifecycleOwner {
        val registry=LifecycleRegistry(this)
        override val lifecycle:Lifecycle get()=registry
    }
    private class Player(context:Context,private val asset:Decoded,options:LumenAssetOptions):LumenAssetPlayer {
        private val lifecycle=OwnedLifecycle()
        private val widget=RiveAnimationView(RiveAnimationView.Builder(context).setRendererType(RendererType.Rive).setAutoplay(false).setShouldLoadCDNAssets(false))
        private var closed=false;private var playing=false
        private val inputTypes=asset.metadata.inputs.associate{it.name to it.kind}
        override val view:View get()=widget
        override val metadata get()=asset.metadata
        init{
            lifecycle.registry.currentState=Lifecycle.State.CREATED;widget.setViewTreeLifecycleOwner(lifecycle)
            widget.setRiveFile(asset.file,artboardName=asset.selection.artboard,animationName=asset.selection.animation,stateMachineName=asset.selection.stateMachine,autoplay=false)
            update(options)
        }
        override fun update(options:LumenAssetOptions){if(closed)return
            require(options.speed==1f&&options.repeatCount==1){"Rive native-clock adapter supports speed1 and one play; frame submission rate does not control its native clock"}
            widget.fit=when(options.fit){LumenAssetFit.CONTAIN->Fit.CONTAIN;LumenAssetFit.COVER->Fit.COVER;LumenAssetFit.FILL->Fit.FILL}
            widget.setVolume(if(options.mute)0f else 1f)
        }
        override fun render(progress:Float):Boolean=false
        override fun setPlaying(playing:Boolean){if(closed||this.playing==playing)return;this.playing=playing
            if(playing){lifecycle.registry.currentState=Lifecycle.State.RESUMED;val name=asset.selection.stateMachine?:asset.selection.animation
                if(name!=null)widget.play(name,loop=Loop.ONESHOT,isStateMachine=asset.selection.stateMachine!=null)else widget.play(loop=Loop.ONESHOT)
            }else{widget.pause();lifecycle.registry.currentState=Lifecycle.State.CREATED}}
        override fun setNumber(name:String,value:Float):Boolean{if(closed||inputTypes[name]!=LumenAssetInputKind.NUMBER)return false;val machine=asset.selection.stateMachine?:return false;widget.setNumberState(machine,name,value);return true}
        override fun setBoolean(name:String,value:Boolean):Boolean{if(closed||inputTypes[name]!=LumenAssetInputKind.BOOLEAN)return false;val machine=asset.selection.stateMachine?:return false;widget.setBooleanState(machine,name,value);return true}
        override fun fire(name:String):Boolean{if(closed||inputTypes[name]!=LumenAssetInputKind.TRIGGER)return false;val machine=asset.selection.stateMachine?:return false;widget.fireState(machine,name);return true}
        override fun close(){if(closed)return;widget.stop();closed=true;playing=false;lifecycle.registry.currentState=Lifecycle.State.DESTROYED;widget.setViewTreeLifecycleOwner(null)}
    }
}
