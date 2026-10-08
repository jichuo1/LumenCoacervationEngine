package com.lumen.coacervation.engine.sensor

import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import java.lang.ref.WeakReference

/** Window-local producer; it does not alter a binding or business listener. The host chooses the output owner. */
@MainThread
public class LumenSensorLightController @JvmOverloads constructor(
    view:View,private val listener:LumenLightVectorListener,
    options:LumenSensorLightOptions=LumenSensorLightOptions(),input:LumenGravityInput?=null
):AutoCloseable,View.OnAttachStateChangeListener,ViewTreeObserver.OnPreDrawListener,ViewTreeObserver.OnWindowFocusChangeListener {
    private val host=WeakReference(view)
    private val input=input?:AndroidGravityInput(view.context)
    private var options=options
    private val policy=GravityLightPolicy().apply {reset(options)}
    private var observer:ViewTreeObserver?=null
    private var started=false;private var closed=false;private var registered=false;private var failed=false
    private var state=if(options.enabled)LumenSensorLightState.STOPPED else LumenSensorLightState.DISABLED
    private var events=0L;private var updates=0L;private var rejected=0L;private var registrations=0L
    private val raw=LumenGravityListener {x,y,z,now->
        checkMain()
        if(!registered||closed)return@LumenGravityListener
        refresh()
        if(!registered)return@LumenGravityListener
        events++
        val viewNow=host.get()?:return@LumenGravityListener
        val rotation=options.fixedRotation?:viewNow.display?.rotation?:0
        if(policy.update(x,y,z,rotation,now,options)){
            updates++
            try{listener.onLight(policy.x,policy.y,policy.z)}catch(_:Exception){failed=true;unregister();state=LumenSensorLightState.CALLBACK_FAILED}
        }else rejected++
    }
    init {checkMain();view.addOnAttachStateChangeListener(this);observe()}
    public fun start(){if(closed)return;checkMain();started=true;failed=false;refresh()}
    public fun stop(){if(closed)return;checkMain();started=false;unregister();state=if(options.enabled)LumenSensorLightState.STOPPED else LumenSensorLightState.DISABLED}
    public fun update(value:LumenSensorLightOptions){if(closed)return;checkMain();if(options==value)return;unregister();options=value;failed=false;policy.reset(value);refresh()}
    public fun diagnostics():LumenSensorLightDiagnostics {if(!closed)checkMain();return LumenSensorLightDiagnostics(state,input.sensorType,events,updates,rejected,registrations)}
    private fun refresh(){
        if(closed)return
        val view=host.get()
        val reason=when{
            !options.enabled->LumenSensorLightState.DISABLED
            !started->LumenSensorLightState.STOPPED
            view==null||!view.isAttachedToWindow||!view.isShown->LumenSensorLightState.HIDDEN
            !view.hasWindowFocus()->LumenSensorLightState.UNFOCUSED
            options.fixedRotation==null&&view.display?.displayId!=Display.DEFAULT_DISPLAY->LumenSensorLightState.UNSUPPORTED_DISPLAY
            else->null
        }
        if(reason!=null){unregister();state=reason;return}
        if(registered){state=LumenSensorLightState.ACTIVE;return}
        if(failed)return
        policy.reset(options)
        registered=try{input.start(options.sampleRateHz,options.allowAccelerometerFallback,raw)}catch(_:Exception){false}
        if(registered){registrations++;state=LumenSensorLightState.ACTIVE}
        else{failed=true;state=if(input.sensorType==null)LumenSensorLightState.NO_SENSOR else LumenSensorLightState.REGISTER_FAILED}
    }
    private fun unregister(){val wasRegistered=registered;registered=false;if(wasRegistered)try{input.stop()}catch(_:Exception){failed=true;state=LumenSensorLightState.REGISTER_FAILED}}
    private fun observe(){
        val current=host.get()?.viewTreeObserver?:return
        if(observer===current)return
        unobserve();observer=current;current.addOnPreDrawListener(this);current.addOnWindowFocusChangeListener(this)
    }
    private fun unobserve(){observer?.takeIf {it.isAlive}?.let{it.removeOnPreDrawListener(this);it.removeOnWindowFocusChangeListener(this)};observer=null}
    override fun onPreDraw():Boolean {refresh();return true}
    override fun onWindowFocusChanged(hasFocus:Boolean){refresh()}
    override fun onViewAttachedToWindow(v:View){observe();refresh()}
    override fun onViewDetachedFromWindow(v:View){unregister();unobserve();state=LumenSensorLightState.HIDDEN}
    override fun close(){if(closed)return;checkMain();unregister();closed=true;state=LumenSensorLightState.CLOSED;unobserve();host.get()?.removeOnAttachStateChangeListener(this)}
    private fun checkMain(){check(Looper.myLooper()===Looper.getMainLooper()){"Sensor light APIs require the main thread"}}
}
