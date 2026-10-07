package com.lumen.coacervation.engine.host

import android.graphics.Matrix

/** Per-binding frame storage. Motion callers update primitive values, never rebuild the material. */
internal class LumenSurfaceRenderState {
    var customShape=false
    val shapeA=FloatArray(4)
    val shapeB=FloatArray(4)
    var secondShape=false
    var pressure=0f
    var touchX=0f;var touchY=0f
    var lightOverride=false
    var lightX=0f;var lightY=0f;var lightZ=1f
    val normalTransform=floatArrayOf(1f,0f,0f,1f)
    val windowMatrix=Matrix()
    val matrixValues=FloatArray(9)
    val rippleX=FloatArray(4);val rippleY=FloatArray(4)
    val rippleBorn=LongArray(4) {Long.MIN_VALUE}
    var rippleCursor=0
    var frameNanos=0L
    var manualClock=false
    var rtl=false
    fun clearTransient(){pressure=0f;rippleBorn.fill(Long.MIN_VALUE);lightOverride=false}
}
