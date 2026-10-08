package com.lumen.coacervation.engine.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/** Optional injectable raw source. Callbacks run on the main thread; one controller owns start/stop. */
public interface LumenGravityInput {
    public val sensorType:Int?
    public fun start(rateHz:Int,allowAccelerometerFallback:Boolean,listener:LumenGravityListener):Boolean
    public fun stop()
}
public fun interface LumenGravityListener { public fun onGravity(x:Float,y:Float,z:Float,timestampNanos:Long) }

internal class AndroidGravityInput(context:Context):LumenGravityInput,SensorEventListener {
    private val manager=(context.applicationContext?:context).getSystemService(Context.SENSOR_SERVICE)as? SensorManager
    private val handler=Handler(Looper.getMainLooper())
    private var listener:LumenGravityListener?=null
    override var sensorType:Int?=null;private set
    override fun start(rateHz:Int,allowAccelerometerFallback:Boolean,listener:LumenGravityListener):Boolean {
        stop()
        val sensor=manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)?:if(allowAccelerometerFallback)manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)else null
        sensorType=sensor?.type
        if(sensor==null)return false
        this.listener=listener
        val success=manager?.registerListener(this,sensor,1_000_000/rateHz,handler)==true
        if(!success)this.listener=null
        return success
    }
    override fun stop(){manager?.unregisterListener(this);listener=null}
    override fun onSensorChanged(event:SensorEvent){if(event.values.size>=3)listener?.onGravity(event.values[0],event.values[1],event.values[2],event.timestamp)}
    override fun onAccuracyChanged(sensor:Sensor,accuracy:Int)=Unit
}
