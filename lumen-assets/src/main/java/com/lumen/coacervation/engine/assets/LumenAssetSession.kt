package com.lumen.coacervation.engine.assets

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.annotation.AnyThread
import com.lumen.coacervation.engine.motion.LumenFixedFrameClock
import com.lumen.coacervation.engine.motion.LumenFrameClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Dedicated container owner. One parse in flight and one replacement queued; late handles are closed. */
@MainThread
public class LumenAssetSession @JvmOverloads constructor(
    private val container:ViewGroup,options:LumenAssetOptions=LumenAssetOptions(),
    private val clock:LumenFrameClock=LumenFrameClock {System.nanoTime()}
):AutoCloseable,View.OnAttachStateChangeListener,ViewTreeObserver.OnPreDrawListener,ViewTreeObserver.OnWindowFocusChangeListener {
    private val main=Handler(Looper.getMainLooper())
    private var options=options;private var closed=false;private var paused=false;private var wanted=false
    private var generation=0L;private var bytes=0;private var frames=0L;private var dropped=0L;private var inFlight=false
    private var state=LumenAssetState.EMPTY;private var failure=LumenAssetFailure.NONE
    private var player:LumenAssetPlayer?=null;private var decoded:LumenDecodedAsset?=null
    private var wrapper:FrameLayout?=null
    private var listener:LumenAssetListener?=null;private var task:Future<*>?=null
    private var elapsedMs=0L;private var lastNanos=0L;private var scheduled=false;private var wasVisible=false;private var observer:ViewTreeObserver?=null
    private var accounting=false;private var remainderNanos=0L
    private val worker=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),{r->Thread(r,"Lumen-AssetLoad").apply {isDaemon=true}})
    private val tick=Runnable {scheduled=false;advanceFrame();schedule()}
    init {checkMain();container.addOnAttachStateChangeListener(this);observe()}
    public fun setListener(value:LumenAssetListener?){if(closed)return;checkMain();listener=value}
    public fun load(source:LumenAssetSource,factory:LumenAssetFactory,selection:LumenAssetSelection=LumenAssetSelection()){
        if(closed)return;checkMain();generation++;val token=generation
        task?.cancel(true);worker.purge();releasePlayer();wanted=options.autoplay;elapsedMs=0L;remainderNanos=0L;bytes=0;inFlight=true
        report(LumenAssetState.LOADING,LumenAssetFailure.NONE)
        val policy=options
        task=worker.submit {
            var handle:LumenDecodedAsset?=null;var count=0;var problem=LumenAssetFailure.NONE
            try{
                val data=source.open().use {AssetPlaybackPolicy.readBounded(it,policy.maximumFileBytes)};count=data.size
                if(Thread.currentThread().isInterrupted)throw InterruptedException()
                handle=factory.decode(data,selection,policy)
                if(!AssetPlaybackPolicy.metadataAllowed(handle.metadata,policy)){handle.close();handle=null;problem=LumenAssetFailure.BUDGET}
            }catch(_:InterruptedException){problem=LumenAssetFailure.STALE}
            catch(_:AssetTooLarge){problem=LumenAssetFailure.TOO_LARGE}
            catch(error:LumenAssetException){problem=error.failure}
            catch(_:LinkageError){problem=LumenAssetFailure.UNSUPPORTED_ABI}
            catch(_:Exception){problem=LumenAssetFailure.INVALID_ASSET}
            if(problem!=LumenAssetFailure.NONE){safeClose(handle);handle=null}
            val completed=handle;val size=count;val error=problem
            main.post {
                if(closed||generation!=token){safeClose(completed);dropped++;return@post}
                inFlight=false;bytes=size
                if(completed==null){report(LumenAssetState.REJECTED,error);return@post}
                if(size>options.maximumFileBytes){safeClose(completed);report(LumenAssetState.REJECTED,LumenAssetFailure.TOO_LARGE);return@post}
                if(!AssetPlaybackPolicy.metadataAllowed(completed.metadata,options)||!AssetPlaybackPolicy.capabilitiesAllowed(completed.metadata,options)||completed.metadata.nativeClock&&clock is LumenFixedFrameClock){
                    val issue=if(!AssetPlaybackPolicy.metadataAllowed(completed.metadata,options))LumenAssetFailure.BUDGET else LumenAssetFailure.UNSUPPORTED_OPERATION
                    safeClose(completed);report(LumenAssetState.REJECTED,issue);return@post
                }
                try{
                    decoded=completed
                    val mounted=factory.attach(container.context,completed,options)
                    player=mounted
                    mounted.setOnFailure{error->main.post{if(!closed&&generation==token&&player===mounted){releasePlayer();report(LumenAssetState.FAILED,error)}}}
                    wrapper=PassiveAssetFrame(container.context).also{frame->frame.addView(mounted.view,FrameLayout.LayoutParams(-1,-1));container.addView(frame,ViewGroup.LayoutParams(-1,-1))}
                    mounted.view.isClickable=false;mounted.view.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    applyViewOptions();report(LumenAssetState.READY,LumenAssetFailure.NONE);lastNanos=clock.nowNanos();refresh();advanceFrame();schedule()
                }catch(_:LinkageError){releasePlayer();report(LumenAssetState.FAILED,LumenAssetFailure.UNSUPPORTED_ABI)}
                catch(_:Exception){releasePlayer();report(LumenAssetState.FAILED,LumenAssetFailure.RENDERER)}
            }
        }
    }
    public fun play(){if(closed)return;checkMain();accrueTime(clock.nowNanos());wanted=true;paused=false;if(state==LumenAssetState.FINISHED){elapsedMs=0;remainderNanos=0};refresh();advanceFrame();schedule()}
    public fun pause(){if(closed)return;checkMain();accrueTime(clock.nowNanos());paused=true;accounting=false;stopTick();protect{player?.setPlaying(false)};if(player!=null)report(LumenAssetState.PAUSED,LumenAssetFailure.NONE)}
    public fun resume(){if(closed)return;checkMain();accrueTime(clock.nowNanos());paused=false;refresh();advanceFrame();schedule()}
    public fun seek(progress:Float){if(closed)return;checkMain();require(progress.isFinite()&&progress in 0f..1f);val p=player?:return
        if(!p.metadata.seekable){report(state,LumenAssetFailure.UNSUPPORTED_OPERATION);return}
        protect{elapsedMs=(p.metadata.durationMs*progress/options.speed).toLong();remainderNanos=0;lastNanos=clock.nowNanos();if(p.render(progress))frames++;container.invalidate()}}
    public fun update(value:LumenAssetOptions){if(closed)return;checkMain();val current=player
        accrueTime(clock.nowNanos());stopTick()
        if(current!=null&&bytes>value.maximumFileBytes){options=value;releasePlayer();report(LumenAssetState.REJECTED,LumenAssetFailure.TOO_LARGE);return}
        if(current!=null&&(!AssetPlaybackPolicy.metadataAllowed(current.metadata,value)||!AssetPlaybackPolicy.capabilitiesAllowed(current.metadata,value))){
            val issue=if(!AssetPlaybackPolicy.metadataAllowed(current.metadata,value))LumenAssetFailure.BUDGET else LumenAssetFailure.UNSUPPORTED_OPERATION
            options=value;releasePlayer();report(LumenAssetState.REJECTED,issue);return
        }
        options=value;if(!protect{player?.update(value);applyViewOptions()})return;refresh();advanceFrame();schedule()}
    private fun inputAllowed():Boolean=wanted&&!paused&&!options.reduceMotion&&ValueAnimator.areAnimatorsEnabled()&&visible()
    public fun setNumber(name:String,value:Float):Boolean {if(closed)return false;checkMain();require(name.length in 1..128&&value.isFinite());if(!inputAllowed())return false;var result=false;protect{result=player?.setNumber(name,value)==true};return result}
    public fun setBoolean(name:String,value:Boolean):Boolean {if(closed)return false;checkMain();require(name.length in 1..128);if(!inputAllowed())return false;var result=false;protect{result=player?.setBoolean(name,value)==true};return result}
    public fun fire(name:String):Boolean {if(closed)return false;checkMain();require(name.length in 1..128);if(!inputAllowed())return false;var result=false;protect{result=player?.fire(name)==true};return result}
    public fun diagnostics():LumenAssetDiagnostics {if(!closed)checkMain();return LumenAssetDiagnostics(state,failure,generation,frames,dropped,inFlight,bytes,player?.metadata)}
    /** Fixed clocks only move when their owner calls this; native-clock state machines are explicitly unsupported. */
    public fun advanceFrame(){
        if(closed)return;checkMain();val p=player?:return
        accrueTime(clock.nowNanos())
        if(!visible()){accounting=false;stopTick();protect{p.setPlaying(false)};return}
        val metadata=p.metadata
        if(metadata.nativeClock&&clock is LumenFixedFrameClock){report(LumenAssetState.PAUSED,LumenAssetFailure.UNSUPPORTED_OPERATION);stopTick();p.setPlaying(false);return}
        val duration=if(metadata.durationMs>0)metadata.durationMs else options.maximumDurationMs
        val done=AssetPlaybackPolicy.finished(elapsedMs,duration,options)
        val running=wanted&&!paused&&!options.reduceMotion&&ValueAnimator.areAnimatorsEnabled()&&!done
        accounting=running
        if(!protect{if(!metadata.nativeClock&&p.render(AssetPlaybackPolicy.progress(elapsedMs,duration,options)))frames++;p.setPlaying(running&&metadata.nativeClock)})return
        if(done){wanted=false;stopTick();report(LumenAssetState.FINISHED,LumenAssetFailure.NONE)}
        else report(if(running)LumenAssetState.PLAYING else LumenAssetState.PAUSED,LumenAssetFailure.NONE)
    }
    private fun applyViewOptions(){wrapper?.let{it.clipChildren=options.clipToBounds;it.clipToPadding=options.clipToBounds};player?.view?.alpha=options.opacity}
    private fun accrueTime(now:Long){if(accounting){val delta=(now-lastNanos).coerceAtLeast(0L)+remainderNanos;elapsedMs=(elapsedMs+delta/1_000_000L).coerceAtMost(600_000);remainderNanos=delta%1_000_000L};lastNanos=now}
    private fun visible():Boolean=options.enabled&&container.isAttachedToWindow&&container.isShown&&(!options.pauseWhenUnfocused||container.hasWindowFocus())&&container.width>0&&container.height>0&&container.width.toLong()*container.height<=options.maximumRenderPixels
    private fun refresh(){if(closed)return;val shown=visible();val changed=shown!=wasVisible;if(changed){accrueTime(clock.nowNanos());wasVisible=shown};wrapper?.visibility=if(shown)View.VISIBLE else View.INVISIBLE
        if(!shown||paused||options.reduceMotion||!ValueAnimator.areAnimatorsEnabled()){accrueTime(clock.nowNanos());accounting=false;stopTick();protect{player?.setPlaying(false)}}else{if(changed)advanceFrame();schedule()}}
    private fun schedule(){if(closed||scheduled||clock is LumenFixedFrameClock||player==null||!wanted||paused||options.reduceMotion||!ValueAnimator.areAnimatorsEnabled()||!visible())return
        scheduled=true;main.postDelayed(tick,AssetPlaybackPolicy.nextDelayMillis(player!!.metadata,elapsedMs,options))}
    private fun stopTick(){main.removeCallbacks(tick);scheduled=false}
    private inline fun protect(action:()->Unit):Boolean{try{action();return true}catch(_:LinkageError){releasePlayer();report(LumenAssetState.FAILED,LumenAssetFailure.UNSUPPORTED_ABI)}catch(_:Exception){releasePlayer();report(LumenAssetState.FAILED,LumenAssetFailure.RENDERER)};return false}
    @AnyThread private fun safeClose(value:AutoCloseable?){try{value?.close()}catch(_:Exception){}catch(_:LinkageError){}}
    private fun releasePlayer(){stopTick();accounting=false;val mounted=player;val loaded=decoded;val frame=wrapper;player=null;decoded=null;wrapper=null
        try{mounted?.setPlaying(false)}catch(_:Exception){}catch(_:LinkageError){}
        if(frame?.parent===container)container.removeView(frame);safeClose(mounted);safeClose(loaded)}
    private fun report(value:LumenAssetState,error:LumenAssetFailure){if(state==value&&failure==error)return;state=value;failure=error
        val token=generation;main.post{if(!closed&&token==generation)try{listener?.onState(value,error)}catch(_:Exception){}}}
    private fun observe(){val current=container.viewTreeObserver;if(observer===current)return;unobserve();observer=current;current.addOnPreDrawListener(this);current.addOnWindowFocusChangeListener(this)}
    private fun unobserve(){observer?.takeIf{it.isAlive}?.let{it.removeOnPreDrawListener(this);it.removeOnWindowFocusChangeListener(this)};observer=null}
    override fun onPreDraw():Boolean{if(!closed)refresh();return true}
    override fun onWindowFocusChanged(hasFocus:Boolean){refresh()}
    override fun onViewAttachedToWindow(v:View){observe();refresh()}
    override fun onViewDetachedFromWindow(v:View){accrueTime(clock.nowNanos());accounting=false;stopTick();protect{player?.setPlaying(false)};wasVisible=false;unobserve()}
    override fun close(){if(closed)return;checkMain();generation++;closed=true;task?.cancel(true);worker.shutdownNow();releasePlayer();unobserve();container.removeOnAttachStateChangeListener(this);listener=null;inFlight=false;state=LumenAssetState.CLOSED}
    private fun checkMain(){check(Looper.myLooper()===Looper.getMainLooper()){"Asset session APIs require the main thread"}}
}

/** Prevent vendor pointer handlers from waking a paused display player; leave the event to the host. */
private class PassiveAssetFrame(context:android.content.Context):FrameLayout(context){
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean=true
    override fun onTouchEvent(event:MotionEvent):Boolean=false
}
