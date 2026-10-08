package com.lumen.coacervation.engine.assets

import android.content.Context
import android.view.View
import androidx.annotation.MainThread
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import java.io.InputStream

public enum class LumenAssetFormat { LOTTIE, PAG, RIVE }
public enum class LumenAssetFit { CONTAIN, COVER, FILL }
public enum class LumenAssetInputKind { NUMBER, BOOLEAN, TRIGGER }
public data class LumenAssetInput(val name:String,val kind:LumenAssetInputKind)
public enum class LumenAssetState { EMPTY, LOADING, READY, PLAYING, PAUSED, FINISHED, REJECTED, FAILED, CLOSED }
public enum class LumenAssetFailure { NONE, TOO_LARGE, INVALID_ASSET, UNSUPPORTED_ABI, BUDGET, STALE, RENDERER, UNSUPPORTED_OPERATION }
/** Factories can distinguish unsupported features and budget refusal from malformed bytes. */
public class LumenAssetException(public val failure:LumenAssetFailure):IllegalArgumentException(failure.name){init{require(failure!=LumenAssetFailure.NONE)}}
public data class LumenAssetOptions(
    val enabled:Boolean=true,val autoplay:Boolean=false,val speed:Float=1f,val repeatCount:Int=1,
    val framesPerSecond:Int=30,val maximumFileBytes:Int=4*1024*1024,val maximumRenderPixels:Int=1024*1024,
    val maximumDurationMs:Long=60_000,val maximumDecodedImagePixels:Int=1024*1024,
    val opacity:Float=1f,val fit:LumenAssetFit=LumenAssetFit.CONTAIN,val reduceMotion:Boolean=false,val videoEnabled:Boolean=false,
    val pauseWhenUnfocused:Boolean=true,val mute:Boolean=true,val clipToBounds:Boolean=true
) {
    init {require(speed.isFinite()&&speed in .1f..4f&&repeatCount in 1..10&&framesPerSecond in 1..60)
        require(maximumFileBytes in 1024..16*1024*1024&&maximumRenderPixels in 16384..4194304&&maximumDurationMs in 100..600_000&&maximumDecodedImagePixels in 1024..4194304)
        require(opacity.isFinite()&&opacity in 0f..1f)}
}
public data class LumenAssetSelection(val artboard:String?=null,val animation:String?=null,val stateMachine:String?=null){
    init {for(value in listOf(artboard,animation,stateMachine))require(value==null||value.length in 1..128)}
}
public data class LumenAssetMetadata(val format:LumenAssetFormat,val width:Int,val height:Int,val durationMs:Long,val seekable:Boolean,val decodedImagePixels:Long=0,
    val nativeClock:Boolean=false,val speedControl:Boolean=true,val repeatControl:Boolean=true,val frameRateControl:Boolean=true,val inputs:List<LumenAssetInput> = emptyList())
public data class LumenAssetDiagnostics(val state:LumenAssetState,val failure:LumenAssetFailure,val generation:Long,val frames:Long,val dropped:Long,val inFlight:Boolean,val bytes:Int,val metadata:LumenAssetMetadata?)
public fun interface LumenAssetSource { @WorkerThread public fun open():InputStream }
public interface LumenDecodedAsset:AutoCloseable { public val metadata:LumenAssetMetadata; @AnyThread public override fun close() }
/** Parsing is confined to one worker; attach transfers only an explicitly owned decoded handle. */
public interface LumenAssetFactory {
    public val format:LumenAssetFormat
    @WorkerThread public fun decode(bytes:ByteArray,selection:LumenAssetSelection,options:LumenAssetOptions):LumenDecodedAsset
    @MainThread public fun attach(context:Context,asset:LumenDecodedAsset,options:LumenAssetOptions):LumenAssetPlayer
}
@MainThread
public interface LumenAssetPlayer:AutoCloseable {
    public val view:View
    public val metadata:LumenAssetMetadata
    public fun update(options:LumenAssetOptions)
    /** Absolute normalised position, or false for a state-machine asset without timeline seeking. */
    public fun render(progress:Float):Boolean
    public fun setPlaying(playing:Boolean)
    /** Optional asynchronous renderer callbacks, delivered through the session's generation guard. */
    public fun setOnFailure(listener:LumenAssetPlayerFailureListener?)=Unit
    public fun setNumber(name:String,value:Float):Boolean = false
    public fun setBoolean(name:String,value:Boolean):Boolean = false
    public fun fire(name:String):Boolean = false
}
public fun interface LumenAssetListener { public fun onState(state:LumenAssetState,failure:LumenAssetFailure) }
public fun interface LumenAssetPlayerFailureListener { public fun onFailure(failure:LumenAssetFailure) }
