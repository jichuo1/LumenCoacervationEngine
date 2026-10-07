package com.lumen.coacervation.engine.host

import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole

/** Explicit intent, host overrides, then accessibility. No persisted material identity is modified. */
internal object LumenMaterialRecipe {
    fun resolve(c: LumenSurfaceOptions,e: LumenMaterialRecipeOptions,palette: LumenPalette): LumenSurfaceOptions {
        if(e.reduceTransparency||e.intent==LumenMaterialIntent.OPAQUE_ACCESSIBLE)return c.copy(
            material=LumenSurfaceMaterial.STATIC,color=(c.color?:palette.surface) or 0xff000000.toInt(),opacity=1f,
            tintEnabled=true,tintOpacity=1f,fallbackTintOpacity=1f,sampling=c.sampling.copy(enabled=false))
        if(e.intent==LumenMaterialIntent.UNCHANGED||!e.useIntentDefaults)return c
        val readingRole=c.role==SurfaceRole.CARD||c.role==SurfaceRole.MODAL||c.role==SurfaceRole.TOP_BAR
        val tint=when(e.intent){LumenMaterialIntent.READING->if(readingRole).38f else .32f;LumenMaterialIntent.CLEAR->.12f;LumenMaterialIntent.DECORATIVE->.16f;else->.20f}
        val fallback=maxOf(if(e.intent==LumenMaterialIntent.READING).86f else .80f,e.contrastFloor)
        return c.copy(tintOpacity=if(c.tintOpacity==.20f)tint else c.tintOpacity,
            fallbackTintOpacity=if(c.fallbackTintOpacity==.80f)fallback else c.fallbackTintOpacity)
    }
}
