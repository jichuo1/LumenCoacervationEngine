package com.lumen.coacervation.engine.interaction

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.host.LumenLocalPressOptions
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceLightOptions
import com.lumen.coacervation.engine.motion.LumenFixedFrameClock
import com.lumen.coacervation.engine.motion.LumenFrameClock
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Optional observer. The host forwards events; this class neither replaces a listener nor consumes clicks. */
@MainThread
public class LumenSurfaceInteraction @JvmOverloads constructor(
    view: View,private val binding: LumenSurfaceBinding,
    press: LumenLocalPressOptions=LumenLocalPressOptions(),
    light: LumenSurfaceLightOptions=LumenSurfaceLightOptions(),
    private val clock: LumenFrameClock=LumenFrameClock {System.nanoTime()}
) : AutoCloseable,View.OnAttachStateChangeListener,ViewTreeObserver.OnWindowFocusChangeListener {
    private val host=WeakReference(view)
    private var press=press;private var light=light
    private val spring=ElasticSpringAxis()
    private var target=0f
    private var x=0f;private var y=0f
    private var pointer=-1
    private var last=clock.nowNanos()
    private var lastRipple=Long.MIN_VALUE
    private var scheduled=false;private var paused=false;private var closed=false
    private var reducedMotion=false
    private var observer: ViewTreeObserver?=null
    private var filteredX=0f;private var filteredY=0f;private var filteredZ=1f
    private var lightSettling=false
    private val frame=Runnable {scheduled=false;advanceFrame();scheduleIfNeeded()}
    init {view.addOnAttachStateChangeListener(this);observe();resetLight()}

    public fun update(press: LumenLocalPressOptions,light: LumenSurfaceLightOptions,reduceMotion: Boolean=false) {
        if(closed)return
        this.press=press;this.light=light;reducedMotion=reduceMotion
        if(!press.enabled||reduceMotion)cancel()
        resetLight();advanceFrame()
    }
    public fun observeTouch(event: MotionEvent) {
        if(closed||paused)return
        val view=host.get()?:return
        if((!press.enabled&&!light.enabled)||reducedMotion||!ValueAnimator.areAnimatorsEnabled())return
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{pointer=event.getPointerId(0);x=event.x;y=event.y;target=event.pressure.coerceIn(0f,1f);last=clock.nowNanos();scheduleIfNeeded()}
            MotionEvent.ACTION_MOVE->{
                val index=event.findPointerIndex(pointer)
                if(index<0){cancel();return}
                x=event.getX(index);y=event.getY(index)
                if(press.cancelOutside&&(x<0f||y<0f||x>view.width||y>view.height)){cancel();return}
                target=event.getPressure(index).coerceIn(0f,1f);advanceFrame();scheduleIfNeeded()
            }
            MotionEvent.ACTION_UP->{
                if(pointer>=0&&press.rippleEnabled){binding.emitRipplePixels(x,y);lastRipple=clock.nowNanos()}
                pointer=-1;target=0f;scheduleIfNeeded()
            }
            MotionEvent.ACTION_CANCEL->cancel()
            MotionEvent.ACTION_POINTER_UP->if(event.getPointerId(event.actionIndex)==pointer)cancel()
        }
    }
    /** Manual clocks are advanced by their owner; no automatic frame callback runs for them. */
    public fun advanceFrame() {
        if(closed||paused)return
        if(!binding.isBound){close();return}
        val view=host.get()?:return
        if(!view.isAttachedToWindow||!view.isShown||!view.hasWindowFocus()){cancel();return}
        val now=clock.nowNanos();val dt=((now-last).coerceAtLeast(0L)/1_000_000_000f).coerceAtMost(.1f);last=now
        if(reducedMotion||!ValueAnimator.areAnimatorsEnabled())spring.reset(target)
        else spring.advance(dt,target,stiffness=(36f/(press.releaseDurationMs/1000f).let {it*it}).coerceIn(100f,2500f),dampingRatio=.85f)
        binding.setFrameTimeNanos(now,clock is LumenFixedFrameClock)
        binding.setPressPixels(x,y,if(press.enabled)spring.value.coerceIn(0f,1f)else 0f)
        if(light.enabled){
            val angle=light.angleDegrees*Math.PI/180.0;val horizontal=sqrt(1f-light.altitude*light.altitude)
            val influence=light.gestureInfluence*spring.value.coerceIn(0f,1f)
            var tx=cos(angle).toFloat()*horizontal+(x/view.width.coerceAtLeast(1)-.5f)*influence
            var ty=sin(angle).toFloat()*horizontal+(y/view.height.coerceAtLeast(1)-.5f)*influence
            var tz=light.altitude;val length=sqrt(tx*tx+ty*ty+tz*tz);tx/=length;ty/=length;tz/=length
            val weight=if(light.smoothingTimeMs==0L)1f else 1f-exp(-dt/(light.smoothingTimeMs/1000f))
            filteredX+=(tx-filteredX)*weight;filteredY+=(ty-filteredY)*weight;filteredZ+=(tz-filteredZ)*weight
            lightSettling=abs(tx-filteredX)+abs(ty-filteredY)+abs(tz-filteredZ)>.002f
            binding.setLightDirection(filteredX,filteredY,filteredZ)
        }
    }
    private fun needsFrame(): Boolean {
        val now=clock.nowNanos()
        val ripple=lastRipple!=Long.MIN_VALUE&&now-lastRipple<press.rippleLifetimeMs*1_000_000L
        return !spring.atRest(target,.002f)||ripple||lightSettling
    }
    private fun scheduleIfNeeded(){
        if(closed||paused||scheduled||clock is LumenFixedFrameClock||!needsFrame())return
        host.get()?.let {scheduled=true;it.postOnAnimation(frame)}
    }
    public fun cancel(){
        if(closed)return
        pointer=-1;target=0f;spring.reset();lastRipple=Long.MIN_VALUE;lightSettling=false
        host.get()?.removeCallbacks(frame);scheduled=false;binding.clearTransientEffects();resetLight()
    }
    public fun pause(){if(closed)return;cancel();paused=true}
    public fun resume(){if(closed)return;paused=false;last=clock.nowNanos()}
    private fun resetLight(){
        lightSettling=false
        val angle=light.angleDegrees*Math.PI/180.0;val horizontal=sqrt(1f-light.altitude*light.altitude)
        filteredX=cos(angle).toFloat()*horizontal;filteredY=sin(angle).toFloat()*horizontal;filteredZ=light.altitude
    }
    private fun observe(){
        val current=host.get()?.viewTreeObserver?:return
        if(observer===current)return
        observer?.takeIf {it.isAlive}?.removeOnWindowFocusChangeListener(this)
        observer=current;current.addOnWindowFocusChangeListener(this)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean){if(!hasFocus)cancel()}
    override fun onViewAttachedToWindow(v: View){observe();last=clock.nowNanos()}
    override fun onViewDetachedFromWindow(v: View){cancel();observer?.takeIf {it.isAlive}?.removeOnWindowFocusChangeListener(this);observer=null}
    override fun close(){
        if(closed)return
        cancel();closed=true;host.get()?.removeOnAttachStateChangeListener(this)
        observer?.takeIf {it.isAlive}?.removeOnWindowFocusChangeListener(this);observer=null
    }
}
