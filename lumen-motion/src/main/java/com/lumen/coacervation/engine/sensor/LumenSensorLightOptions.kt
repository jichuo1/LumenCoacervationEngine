package com.lumen.coacervation.engine.sensor

/** Raw sensor input is opt-in. All rates stay below Android's high-rate permission threshold. */
public data class LumenSensorLightOptions(
    val enabled: Boolean = false,
    val allowAccelerometerFallback: Boolean = true,
    val sampleRateHz: Int = 30,
    val minimumUpdateIntervalMs: Long = 32,
    val smoothingTimeMs: Long = 150,
    val influence: Float = .5f,
    val maximumTiltDegrees: Float = 45f,
    val maximumAngularSpeedDegrees: Float = 180f,
    val changeThresholdDegrees: Float = .3f,
    val flatThreshold: Float = .025f,
    val baseAngleDegrees: Float = 145f,
    val baseAltitude: Float = .65f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val fixedRotation: Int? = null
) {
    init {
        require(sampleRateHz in 1..60 && minimumUpdateIntervalMs in 16..1000 && smoothingTimeMs in 0..2000)
        bound(influence,0f,1f);bound(maximumTiltDegrees,0f,60f);bound(maximumAngularSpeedDegrees,10f,360f)
        bound(changeThresholdDegrees,0f,10f);bound(flatThreshold,0f,.2f);bound(baseAngleDegrees,0f,360f);bound(baseAltitude,.05f,1f)
        require(fixedRotation==null||fixedRotation in 0..3)
    }
}
private fun bound(value:Float,low:Float,high:Float){require(value.isFinite()&&value in low..high)}

public enum class LumenSensorLightState { DISABLED, STOPPED, HIDDEN, UNFOCUSED, UNSUPPORTED_DISPLAY, NO_SENSOR, REGISTER_FAILED, CALLBACK_FAILED, ACTIVE, CLOSED }
/** rejected counts invalid and rate/deadband-suppressed events, not registration failures. */
public data class LumenSensorLightDiagnostics(val state:LumenSensorLightState,val sensorType:Int?,val events:Long,val updates:Long,val rejected:Long,val registrations:Long)
public fun interface LumenLightVectorListener { public fun onLight(x:Float,y:Float,z:Float) }
