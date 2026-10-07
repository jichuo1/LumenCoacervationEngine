package com.lumen.coacervation.engine.widget

import com.lumen.coacervation.engine.host.LumenSurfaceGeometryOptions

/** Four AA pixels plus the smooth-min expansion and a Gaussian tail below one alpha code at opacity .8. */
internal object FusionDecorationBounds {
    fun haloDp(options:LumenSurfaceGeometryOptions):Float =
        (if(options.fusionEnabled)options.fusionRadiusDp/4f else 0f)+
            (if(options.shadowEnabled)options.shadowRadiusDp*3.5f else 0f)+4f
}
