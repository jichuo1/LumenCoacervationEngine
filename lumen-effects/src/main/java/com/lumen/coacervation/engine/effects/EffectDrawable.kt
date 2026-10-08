package com.lumen.coacervation.engine.effects

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.*

internal class EffectDrawable(private val density:Float):Drawable() {
    var options=LumenEffectOptions()
    val particles=ParticlePool(options.seed)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectangle=RectF();private val clip=Path();private val matrix=Matrix()
    private var program:ProceduralProgramApi33?=null;private var failed=false;private var alphaValue=255
    private val grainX=FloatArray(512);private val grainY=FloatArray(512);private val grainWeight=FloatArray(512)
    private var gradient:LinearGradient?=null
    var phase=0f
    var frames=0L;private set
    var shaderBuilds=0L;private set
    val shaderFailed get()=failed
    init{configure(options)}
    fun configure(value:LumenEffectOptions){
        val reseed=value.seed!=options.seed;val particleChange=value.particles!=options.particles;options=value
        if(reseed)particles.reseed(value.seed)else if(particleChange)particles.clear()
        var seed=if(value.seed==0)1 else value.seed
        fun next():Float{seed=seed xor(seed shl 13);seed=seed xor(seed ushr 17);seed=seed xor(seed shl 5);return(seed ushr 8)/16777216f}
        for(i in 0..511){grainX[i]=next();grainY[i]=next();grainWeight[i]=next()-.5f}
        val film=value.procedural.film;val base=value.procedural.color
        fun spectral(fraction:Float):Int{
            val optical=film.thicknessNm*(.4f+.6f*fraction);val amount=(film.iridescence*(1f-film.roughness*.7f)*film.intensity).coerceIn(0f,1f)
            fun channel(source:Int,wavelength:Float)=((source/255f*(1f-amount)+(.5+.5*cos(4*PI*optical/wavelength))*amount)*255).roundToInt().coerceIn(0,255)
            return Color.rgb(channel(Color.red(base),650f),channel(Color.green(base),510f),channel(Color.blue(base),440f))
        }
        val radians=film.angleDegrees*PI/180.0;val dx=cos(radians).toFloat();val dy=sin(radians).toFloat()
        gradient=LinearGradient(.5f-dx*.5f,.5f-dy*.5f,.5f+dx*.5f,.5f+dy*.5f,intArrayOf(spectral(0f),spectral(.5f),spectral(1f)),null,Shader.TileMode.CLAMP)
        onBoundsChange(bounds);invalidateSelf()
    }
    override fun onBoundsChange(bounds:Rect){rectangle.set(bounds);clip.reset();val r=options.procedural.radiusDp*density;clip.addRoundRect(rectangle,r,r,Path.Direction.CW)
        matrix.setScale(bounds.width().coerceAtLeast(1).toFloat(),bounds.height().coerceAtLeast(1).toFloat());gradient?.setLocalMatrix(matrix)}
    override fun draw(canvas:Canvas){
        if(bounds.isEmpty||bounds.width().toLong()*bounds.height()>options.maximumRenderPixels)return
        val saved=canvas.save();if(options.clipToBounds)canvas.clipPath(clip)
        try{
            val c=options.procedural
            if(c.enabled&&c.opacity>0f){
                var gpu=false
                if(Build.VERSION.SDK_INT>=33&&canvas.isHardwareAccelerated&&c.gpuEnabled&&!failed){
                    try{val p=program?:ProceduralProgramApi33().also{program=it;shaderBuilds++};gpu=p.draw(canvas,rectangle,c,density,phase,options.seed,alphaValue/255f)}
                    catch(_:Exception){failed=true;program?.close();program=null}
                }
                if(!gpu)drawCompatibility(canvas,c)
            }
            val particle=options.particles
            if(particle.enabled){paint.shader=null;paint.style=Paint.Style.FILL;paint.color=particle.color
                for(i in 0..127){if(particles.alpha[i]<=0f)continue;paint.alpha=(Color.alpha(particle.color)*particle.opacity*particles.alpha[i]*alphaValue/255f).roundToInt()
                    if(particle.shape==LumenParticleShape.ORB)canvas.drawCircle(particles.x[i],particles.y[i],particles.radius[i],paint)
                    else{paint.strokeWidth=particles.radius[i]*2f;paint.strokeCap=Paint.Cap.ROUND;canvas.drawLine(particles.x[i],particles.y[i],particles.x[i],particles.y[i]-particle.trailLengthDp*density,paint)}
                }
            }
            frames++
        }finally{paint.shader=null;canvas.restoreToCount(saved)}
    }
    private fun drawCompatibility(canvas:Canvas,c:LumenProceduralOptions){
        paint.style=Paint.Style.FILL;paint.shader=if(c.kind==LumenProceduralKind.FILM)gradient else null;paint.color=c.color
        paint.alpha=(Color.alpha(c.color)*c.opacity*alphaValue/255f).roundToInt();if(c.kind!=LumenProceduralKind.ENERGY)canvas.drawRoundRect(rectangle,c.radiusDp*density,c.radiusDp*density,paint);paint.shader=null
        if(c.kind==LumenProceduralKind.PAPER&&c.paper.grainEnabled){
            for(i in 0 until c.paper.grainCount){paint.color=if(grainWeight[i]<0)Color.BLACK else Color.WHITE
                paint.alpha=((abs(grainWeight[i])*c.paper.contrast+c.paper.relief*.05f)*c.opacity*alphaValue*.6f).roundToInt().coerceIn(0,255)
                canvas.drawCircle(rectangle.left+grainX[i]*rectangle.width(),rectangle.top+grainY[i]*rectangle.height(),c.paper.grainSizeDp*density*.5f,paint)}
        }else if(c.kind==LumenProceduralKind.ENERGY){
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2f*density;paint.color=c.color;paint.alpha=(sin(phase*PI).coerceAtLeast(0.0)*c.opacity*alphaValue*c.energy.intensity*Color.alpha(c.color)/255f).toInt().coerceIn(0,255)
            for(i in 1..c.energy.bands)canvas.drawCircle(rectangle.centerX(),rectangle.centerY(),(i+phase*c.energy.speed)*c.energy.wavelengthDp*density,paint)
            paint.style=Paint.Style.FILL
        }
    }
    fun release(){if(Build.VERSION.SDK_INT>=33)program?.close();program=null;paint.shader=null;particles.clear()}
    override fun setAlpha(alpha:Int){alphaValue=alpha.coerceIn(0,255);invalidateSelf()}
    override fun getAlpha():Int=alphaValue
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Java")override fun getOpacity():Int=PixelFormat.TRANSLUCENT
}
