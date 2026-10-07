package com.lumen.coacervation.sample

import android.os.Build
import androidx.annotation.RequiresApi
import org.junit.rules.ExternalResource

/** ATD disables final buffers by default. Scope hardware output to each test and restore it on failure. */
class HardwareOutputRule:ExternalResource() {
    private var previous=true
    override fun before(){if(Build.VERSION.SDK_INT>=33){previous=HardwareOutputApi33.enabled();HardwareOutputApi33.set(true)}}
    override fun after(){if(Build.VERSION.SDK_INT>=33)HardwareOutputApi33.set(previous)}
}
@RequiresApi(33)
private object HardwareOutputApi33 {
    fun enabled()=android.graphics.HardwareRenderer.isDrawingEnabled()
    fun set(enabled:Boolean)=android.graphics.HardwareRenderer.setDrawingEnabled(enabled)
}
