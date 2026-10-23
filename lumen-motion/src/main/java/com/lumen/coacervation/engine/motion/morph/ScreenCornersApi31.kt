package com.lumen.coacervation.engine.motion.morph

import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import androidx.annotation.RequiresApi

/** Keep API 31 platform types out of the base host and old-system verification paths. */
@RequiresApi(31)
internal object ScreenCornersApi31 {
    fun read(host: View, insets: WindowInsets?, location: IntArray, out: FloatArray) {
        host.getLocationInWindow(location)
        for (index in 0..3) {
            val position = when (index) {
                0 -> RoundedCorner.POSITION_TOP_LEFT
                1 -> RoundedCorner.POSITION_TOP_RIGHT
                2 -> RoundedCorner.POSITION_BOTTOM_RIGHT
                else -> RoundedCorner.POSITION_BOTTOM_LEFT
            }
            val corner = insets?.getRoundedCorner(position)
            out[index] = if (corner == null) 0f else {
                val center = corner.center
                ScreenCornerMorphPolicy.radiusInHost(index, corner.radius.toFloat(), center.x.toFloat(),
                    center.y.toFloat(), location[0].toFloat(), location[1].toFloat(),
                    host.width.toFloat(), host.height.toFloat())
            }
        }
    }
}
