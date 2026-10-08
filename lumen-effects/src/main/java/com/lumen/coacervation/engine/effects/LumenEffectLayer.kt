package com.lumen.coacervation.engine.effects

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.motion.LumenFixedFrameClock
import com.lumen.coacervation.engine.motion.LumenFrameClock
import java.lang.ref.WeakReference

/** Independent decoration through ViewOverlay. It owns no background, child, input listener or capture source. */
@MainThread
public class LumenEffectLayer @JvmOverloads constructor(view:View,options:LumenEffectOptions=LumenEffectOptions(),private val clock:LumenFrameClock=LumenFrameClock{System.nanoTime()})
    :AutoCloseable,View.OnAttachStateChangeListener,View.OnLayoutChangeListener,ViewTreeObserver.OnPreDrawListener,ViewTreeObserver.OnWindowFocusChangeListener {
    private val host=WeakReference(view);private var options=options
    private val drawable=EffectDrawable(view.resources.displayMetrics.density).apply{configure(options)}
    private val main=Handler(Looper.getMainLooper());private var observer:ViewTreeObserver?=null
    private var closed=false;private var paused=false;private var scheduled=false;private var visible=false
    private var energyBorn=Long.MIN_VALUE;private var energyElapsed=0L;private var last=clock.nowNanos()
    private var suspendedAt=Long.MIN_VALUE
    private var state=LumenEffectState.DISABLED
    private val tick=Runnable{scheduled=false;advanceFrame();schedule()}
    init{checkMain();view.overlay.add(drawable);view.addOnAttachStateChangeListener(this);view.addOnLayoutChangeListener(this);observe();layout(view);refresh()}
    public fun update(value:LumenEffectOptions){if(closed)return;checkMain();val energyChanged=value.procedural.energy!=options.procedural.energy
        options=value;drawable.configure(value)
        if(energyChanged||!value.procedural.enabled||value.procedural.kind!=LumenProceduralKind.ENERGY){energyBorn=Long.MIN_VALUE;energyElapsed=0;drawable.phase=0f}
        if(value.reduceMotion)clear();if(!active())stopTick();host.get()?.let{layout(it)};refresh();schedule()}
    public fun emitBurst(x:Float,y:Float):Int{if(closed)return 0;checkMain();val view=host.get()?:return 0
        require(x.isFinite()&&y.isFinite()&&x in 0f..view.width.toFloat()&&y in 0f..view.height.toFloat())
        if(!runningAllowed()||options.reduceMotion||!ValueAnimator.areAnimatorsEnabled())return 0
        val count=drawable.particles.emit(x,y,view.resources.displayMetrics.density,clock.nowNanos(),options.particles);view.invalidate();schedule();return count}
    public fun triggerEnergy(){if(closed)return;checkMain();if(!runningAllowed()||!options.procedural.enabled||options.procedural.kind!=LumenProceduralKind.ENERGY||options.reduceMotion||!ValueAnimator.areAnimatorsEnabled())return
        energyBorn=clock.nowNanos();energyElapsed=0;last=energyBorn;drawable.phase=0f;host.get()?.invalidate();schedule()}
    public fun pause(){if(closed)return;checkMain();paused=true;stopTick();refresh();state=LumenEffectState.PAUSED}
    public fun resume(){if(closed)return;checkMain();paused=false;last=clock.nowNanos();refresh();schedule()}
    public fun clear(){if(closed)return;checkMain();drawable.particles.clear();energyBorn=Long.MIN_VALUE;energyElapsed=0;drawable.phase=0f;stopTick();host.get()?.invalidate()}
    public fun advanceFrame(){if(closed)return;checkMain();refresh();if(!runningAllowed())return
        if(options.reduceMotion||!ValueAnimator.areAnimatorsEnabled()){clear();state=LumenEffectState.IDLE;return}
        val now=clock.nowNanos();if(now<last){clear();last=now;return};last=now
        drawable.particles.update(now)
        if(energyBorn!=Long.MIN_VALUE){energyElapsed=(now-energyBorn)/1_000_000L;drawable.phase=(energyElapsed.toFloat()/options.procedural.energy.durationMs).coerceIn(0f,1f)
            if(energyElapsed>=options.procedural.energy.durationMs)energyBorn=Long.MIN_VALUE}
        if(!options.particles.enabled)drawable.particles.clear()
        host.get()?.invalidate();state=if(active())LumenEffectState.ACTIVE else if(drawable.shaderFailed)LumenEffectState.SHADER_FALLBACK else LumenEffectState.IDLE
    }
    public fun diagnostics():LumenEffectDiagnostics{if(!closed)checkMain();return LumenEffectDiagnostics(state,drawable.particles.live,drawable.frames,drawable.shaderBuilds,drawable.particles.dropped,scheduled,shaderFallback=drawable.shaderFailed)}
    private fun active():Boolean=drawable.particles.live>0||energyBorn!=Long.MIN_VALUE
    private fun runningAllowed():Boolean{val v=host.get()?:return false;return !closed&&!paused&&visible&&v.width.toLong()*v.height<=options.maximumRenderPixels}
    private fun refresh(){if(closed)return;val v=host.get();val shown=v!=null&&v.isAttachedToWindow&&v.isShown&&(!options.pauseWhenUnfocused||v.hasWindowFocus())
        if(!shown||paused){if(visible){suspendedAt=clock.nowNanos();if(options.clearOnPause)clear()};visible=false;stopTick();drawable.setVisible(false,false);state=if(paused)LumenEffectState.PAUSED else LumenEffectState.HIDDEN;return}
        if(!visible&&suspendedAt!=Long.MIN_VALUE){val now=clock.nowNanos();val delta=now-suspendedAt
            if(delta<0)clear()else if(!options.clearOnPause){drawable.particles.shiftTime(delta);if(energyBorn!=Long.MIN_VALUE)energyBorn+=delta};suspendedAt=Long.MIN_VALUE;last=now}
        visible=true;drawable.setVisible(options.procedural.enabled||options.particles.enabled,false)
        state=when{v!!.width.toLong()*v.height>options.maximumRenderPixels->LumenEffectState.BUDGET;!options.procedural.enabled&&!options.particles.enabled->LumenEffectState.DISABLED;active()->LumenEffectState.ACTIVE;else->LumenEffectState.IDLE}
    }
    private fun schedule(){if(closed||scheduled||clock is LumenFixedFrameClock||!runningAllowed()||!active())return;scheduled=true;main.postDelayed(tick,(1000L/options.framesPerSecond).coerceAtLeast(16))}
    private fun stopTick(){main.removeCallbacks(tick);scheduled=false}
    private fun layout(v:View){drawable.setBounds(0,0,v.width,v.height);v.invalidate()}
    private fun observe(){val o=host.get()?.viewTreeObserver?:return;if(observer===o)return;unobserve();observer=o;o.addOnPreDrawListener(this);o.addOnWindowFocusChangeListener(this)}
    private fun unobserve(){observer?.takeIf{it.isAlive}?.let{it.removeOnPreDrawListener(this);it.removeOnWindowFocusChangeListener(this)};observer=null}
    override fun onPreDraw():Boolean{refresh();return true}
    override fun onWindowFocusChanged(hasFocus:Boolean){refresh();schedule()}
    override fun onViewAttachedToWindow(v:View){observe();layout(v);refresh();schedule()}
    override fun onViewDetachedFromWindow(v:View){clear();stopTick();unobserve();drawable.release();visible=false;state=LumenEffectState.HIDDEN}
    override fun onLayoutChange(v:View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int){layout(v);refresh()}
    override fun close(){if(closed)return;checkMain();clear();closed=true;stopTick();unobserve();host.get()?.let{it.overlay.remove(drawable);it.removeOnAttachStateChangeListener(this);it.removeOnLayoutChangeListener(this)};drawable.release();state=LumenEffectState.CLOSED}
    private fun checkMain(){check(Looper.myLooper()===Looper.getMainLooper()){"Effects require the main thread"}}
}
