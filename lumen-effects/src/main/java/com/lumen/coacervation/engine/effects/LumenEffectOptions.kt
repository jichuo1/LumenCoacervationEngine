package com.lumen.coacervation.engine.effects

public enum class LumenProceduralKind { FILM, PAPER, ENERGY }
public enum class LumenParticleShape { ORB, STREAK }
public data class LumenFilmOptions(val thicknessNm:Float=550f,val iridescence:Float=.65f,val roughness:Float=.3f,val angleDegrees:Float=145f,val intensity:Float=1f){
    init{bound(thicknessNm,100f,2000f);bound(iridescence,0f,1f);bound(roughness,.05f,1f);bound(angleDegrees,0f,360f);bound(intensity,0f,2f)}
}
public data class LumenPaperOptions(val grainEnabled:Boolean=true,val grainCount:Int=256,val grainSizeDp:Float=1.5f,val contrast:Float=.25f,val relief:Float=.2f){
    init{require(grainCount in 16..512);bound(grainSizeDp,.5f,12f);bound(contrast,0f,1f);bound(relief,0f,1f)}
}
public data class LumenEnergyOptions(val durationMs:Long=900,val speed:Float=1f,val bands:Int=3,val wavelengthDp:Float=32f,val intensity:Float=1f){
    init{require(durationMs in 100..4000&&bands in 1..8);bound(speed,.1f,3f);bound(wavelengthDp,8f,96f);bound(intensity,0f,2f)}
}
public data class LumenProceduralOptions(val enabled:Boolean=false,val kind:LumenProceduralKind=LumenProceduralKind.FILM,
    val opacity:Float=.35f,val color:Int=0xffefefff.toInt(),val radiusDp:Float=16f,val gpuEnabled:Boolean=true,
    val film:LumenFilmOptions=LumenFilmOptions(),val paper:LumenPaperOptions=LumenPaperOptions(),val energy:LumenEnergyOptions=LumenEnergyOptions()){
    init{bound(opacity,0f,1f);bound(radiusDp,0f,128f)}
}
public data class LumenParticleOptions(val enabled:Boolean=false,val shape:LumenParticleShape=LumenParticleShape.ORB,
    val maximumParticles:Int=64,val burstCount:Int=24,val lifetimeMs:Long=1000,val speedDpPerSecond:Float=120f,
    val gravityDpPerSecondSquared:Float=80f,val radiusDp:Float=3f,val spreadDegrees:Float=140f,val directionDegrees:Float=270f,
    val trailLengthDp:Float=8f,val opacity:Float=.7f,val color:Int=0xff799fff.toInt(),val maximumParticlePixels:Int=65536){
    init{require(maximumParticles in 4..128&&burstCount in 1..64&&lifetimeMs in 100..4000&&maximumParticlePixels in 1024..262144)
        bound(speedDpPerSecond,0f,800f);bound(gravityDpPerSecondSquared,-300f,300f);bound(radiusDp,1f,12f);bound(spreadDegrees,0f,360f);bound(directionDegrees,0f,360f);bound(trailLengthDp,0f,24f);bound(opacity,0f,1f)}
}
public data class LumenEffectOptions(val procedural:LumenProceduralOptions=LumenProceduralOptions(),val particles:LumenParticleOptions=LumenParticleOptions(),
    val seed:Int=1,val framesPerSecond:Int=30,val maximumRenderPixels:Int=262144,val reduceMotion:Boolean=false,
    val pauseWhenUnfocused:Boolean=true,val clearOnPause:Boolean=true,val clipToBounds:Boolean=true){
    init{require(framesPerSecond in 1..60&&maximumRenderPixels in 16384..1048576)}
}
public enum class LumenEffectState { DISABLED, IDLE, ACTIVE, PAUSED, HIDDEN, BUDGET, SHADER_FALLBACK, CLOSED }
public data class LumenEffectDiagnostics(val state:LumenEffectState,val liveParticles:Int,val renderedFrames:Long,val shaderBuilds:Long,val droppedParticles:Long,val scheduled:Boolean,val captureRequests:Long=0,val shaderFallback:Boolean=false)
private fun bound(value:Float,low:Float,high:Float){require(value.isFinite()&&value in low..high)}
