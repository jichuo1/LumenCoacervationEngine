/* Copyright (C) 2021 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * See NOTICE for the framework StretchEffect coordinate derivation.
 */
package com.lumen.coacervation.engine.liquid

import kotlin.math.exp

/** Public EdgeEffect distance -> HWUI stretch intensity; no reflection or frame allocations. */
internal object LiquidStretchSamplingPolicy {
    fun intensity(distance: Float): Float {
        if (!distance.isFinite() || distance <= 0f) return 0f
        val bounded = distance.coerceAtMost(1f)
        return .016f * (bounded + 1f - exp(-bounded * (Math.E / .33)).toFloat())
    }

    /** Inverse of the framework's texture lookup; bottom is the top mapping reflected about 0.5. */
    fun outputPosition(input: Float, overscroll: Float): Float {
        if (overscroll == 0f) return input
        val amount = kotlin.math.abs(overscroll)
        val q = if (overscroll < 0f) 1f - input else input
        val k = 1f + amount
        val mapped = k * k * q / (1f + .3f * amount + .7f * amount * k * q)
        return if (overscroll < 0f) 1f - mapped else mapped
    }
}
