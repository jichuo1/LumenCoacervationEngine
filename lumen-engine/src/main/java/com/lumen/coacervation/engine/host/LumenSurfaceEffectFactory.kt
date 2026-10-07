package com.lumen.coacervation.engine.host

import android.os.Build
import androidx.annotation.RequiresApi
import com.lumen.coacervation.engine.runtime.LumenGraphicsCounters

/** Configuration-time stage declaration. It owns no View, shader, bitmap or platform-specific type. */
internal data class LumenSurfaceEffectRequirements(
    val captureBackground: Boolean,val weakBlurDp: Float,val strongBlurDp: Float,
    val haloDp: Float,val needsTouch: Boolean,val needsTime: Boolean
)

/** Controlled structure selection; instances and their source resources belong to a binding. */
internal object LumenSurfaceEffectFactory {
    enum class Plan { LEGACY_NODE, DIRECT_BITMAP, COMPAT_CONTOUR, OPAQUE }
    fun plan(api: Int,surface: LumenSurfaceOptions,enhanced: Boolean): Plan = when {
        !surface.sampling.enabled||surface.material==LumenSurfaceMaterial.STATIC||surface.sampling.backend==LumenSurfaceBackend.STATIC->Plan.OPAQUE
        !enhanced->Plan.LEGACY_NODE
        api>=33&&surface.sampling.backend!=LumenSurfaceBackend.SOFTWARE->Plan.DIRECT_BITMAP
        else->Plan.COMPAT_CONTOUR
    }
    fun requirements(surface: LumenSurfaceOptions,e: LumenSurfaceEnhancements): LumenSurfaceEffectRequirements {
        val capture=surface.enabled&&surface.sampling.enabled&&surface.material!=LumenSurfaceMaterial.STATIC&&surface.sampling.backend!=LumenSurfaceBackend.STATIC
        val progressive=e.progressiveBlur
        val strong=if(!capture)0f else if(progressive.enabled)progressive.strongRadiusDp else if(surface.sampling.blurEnabled)surface.sampling.blurRadiusDp else 0f
        val weak=if(capture&&progressive.enabled)progressive.weakRadiusDp else 0f
        val motion=!e.material.reduceMotion
        val wave=motion&&e.press.rippleEnabled
        val warp=(if(surface.sampling.refractionEnabled)8f*surface.sampling.refractionStrength else 0f)+
            (if(motion&&e.press.enabled)8f else 0f)+(if(wave)e.press.rippleAmplitudeDp*e.press.maxRipples else 0f)
        return LumenSurfaceEffectRequirements(capture,weak,strong,2f*strong+warp+e.material.chromaticStrength+4f,
            motion&&(e.press.enabled||e.light.enabled&&e.light.gestureInfluence>0f),wave)
    }
    @RequiresApi(33)
    fun direct(counters: LumenGraphicsCounters): DirectSurfaceProgramApi33 {
        check(Build.VERSION.SDK_INT>=33)
        return DirectSurfaceProgramApi33(counters)
    }
}
