package com.lumen.coacervation.engine.host

internal class LumenSurfaceQualityPolicy {
    var current=LumenDetailMode.BALANCED
        private set
    private var lastChange=Long.MIN_VALUE
    private var configuration:LumenSurfaceQualityOptions?=null
    fun update(pressure: Float,now: Long,options: LumenSurfaceQualityOptions): LumenDetailMode {
        if(configuration!=options){configuration=options;current=if(options.enabled)options.mode else LumenDetailMode.HIGH;lastChange=Long.MIN_VALUE}
        if(lastChange!=Long.MIN_VALUE&&now<lastChange)lastChange=now
        if(!options.enabled){current=LumenDetailMode.HIGH;return current}
        if(!options.adaptive){current=options.mode;lastChange=now;return current}
        if(lastChange==Long.MIN_VALUE){current=options.mode;lastChange=now}
        if(now-lastChange<options.minimumDwellMs*1_000_000L)return current
        val next=when(current){
            LumenDetailMode.HIGH->if(pressure>options.lowerThreshold+options.hysteresis)LumenDetailMode.BALANCED else current
            LumenDetailMode.BALANCED->when{
                pressure>options.upperThreshold+options.hysteresis->LumenDetailMode.LOW
                pressure<options.lowerThreshold-options.hysteresis->LumenDetailMode.HIGH
                else->current
            }
            LumenDetailMode.LOW->if(pressure<options.upperThreshold-options.hysteresis)LumenDetailMode.BALANCED else current
        }
        if(next!=current){current=next;lastChange=now}
        return current
    }
}
