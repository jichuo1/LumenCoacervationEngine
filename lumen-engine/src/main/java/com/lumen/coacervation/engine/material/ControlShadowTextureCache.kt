package com.lumen.coacervation.engine.material

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureHub
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureListener

/**
 * 投射层阴影纹理的进程级缓存：键是量化后的（圆角、模糊），值是白色 alpha 遮罩位图。
 * 字节预算 [ControlShadowPolicy.MAX_CACHE_BYTES]，超出按 LRU 逐出；内存告急时整体清空。
 * 纹理生成在首次绘制时同步完成（模糊是 O(像素) 滑动窗口，单张几百微秒），稳态绘制零分配。
 */
internal object ControlShadowTextureCache {
    private val lock = Any()
    private val entries = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?): Boolean {
            var bytes = 0
            for (bitmap in values) bytes += bitmap.byteCount
            return bytes > ControlShadowPolicy.MAX_CACHE_BYTES
        }
    }
    @Volatile private var registered = false

    private fun key(radiusPx: Int, blurPx: Int): Long =
        (radiusPx.toLong() shl 32) or (blurPx.toLong() and 0xFFFF_FFFFL)

    fun obtain(radiusPx: Float, blurRadiusPx: Float): ControlShadowPolicy.TextureMetrics? {
        val metrics = ControlShadowPolicy.textureMetrics(radiusPx, blurRadiusPx)
        if (metrics.blurPx <= 0) return null
        val id = key(metrics.cornerPx, metrics.blurPx)
        synchronized(lock) {
            entries[id]?.let { return metrics }
            entries[id] = render(metrics)
        }
        if (!registered) {
            registered = true
            LumenMemoryPressureHub.addListener(LumenMemoryPressureListener { clear() })
        }
        return metrics
    }

    fun texture(metrics: ControlShadowPolicy.TextureMetrics): Bitmap? =
        synchronized(lock) { entries[key(metrics.cornerPx, metrics.blurPx)] }

    fun clear() = synchronized(lock) { entries.clear() }

    /** 测试与诊断用：当前缓存占用字节。 */
    fun bytes(): Int = synchronized(lock) {
        var total = 0
        for (bitmap in entries.values) total += bitmap.byteCount
        return total
    }

    private fun render(metrics: ControlShadowPolicy.TextureMetrics): Bitmap {
        val side = metrics.sidePx
        val pad = metrics.padPx.toFloat()
        val bitmap = createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.WHITE
        canvas.drawRoundRect(pad, pad, side - pad, side - pad,
            metrics.cornerPx.toFloat(), metrics.cornerPx.toFloat(), paint)
        val pixels = IntArray(side * side)
        bitmap.getPixels(pixels, 0, side, 0, 0, side, side)
        val blurred = ModernBackdropBlur.blur(pixels, side, side, metrics.blurPx)
        bitmap.setPixels(blurred, 0, side, 0, 0, side, side)
        bitmap.prepareToDraw()
        return bitmap
    }
}
