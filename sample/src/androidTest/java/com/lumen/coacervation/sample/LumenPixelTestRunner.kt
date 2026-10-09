package com.lumen.coacervation.sample

import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.test.runner.AndroidJUnitRunner

/** ATD pixel tests need final buffers for the whole run, not repeated renderer enable/disable cycles. */
class LumenPixelTestRunner : AndroidJUnitRunner() {
    private var previousDrawing: Boolean? = null

    override fun onCreate(arguments: Bundle) {
        if (Build.VERSION.SDK_INT >= 33) {
            previousDrawing = DrawingApi33.enabled()
            DrawingApi33.set(true)
        }
        super.onCreate(arguments)
    }

    override fun finish(resultCode: Int, results: Bundle) {
        try {
            if (Build.VERSION.SDK_INT >= 33) previousDrawing?.let(DrawingApi33::set)
        } finally {
            super.finish(resultCode, results)
        }
    }
}

@RequiresApi(33)
private object DrawingApi33 {
    fun enabled(): Boolean = android.graphics.HardwareRenderer.isDrawingEnabled()
    fun set(enabled: Boolean) = android.graphics.HardwareRenderer.setDrawingEnabled(enabled)
}
