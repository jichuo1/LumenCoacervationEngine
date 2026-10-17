package com.lumen.coacervation.engine.material

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureHub
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureListener
import kotlin.math.roundToInt

/**
 * 控件阴影纹理的进程级缓存：键是层、纹理空间圆角、留白与模糊/接触带参数。
 *
 * 纹理按 [ControlShadowPolicy.TEXTURE_SCALE] 降采样、按有符号距离直接烘焙
 * （[ControlShadowPolicy.buildAlphaLut] + 逐像素距离查询），不经过模糊、Canvas 或位图往返。
 * 九宫格在目标侧放大回逻辑尺寸。字节预算 [ControlShadowPolicy.MAX_CACHE_BYTES]，
 * 超出按 LRU 逐出；内存告急时整体清空。
 *
 * 预算按**真实工作集**（同屏并存的全部圆角 × 两层）校验，避免 LRU 在循环访问下退化为每帧重建。
 */
internal object ControlShadowTextureCache {
    private val lock = Any()
    private var totalBytes = 0
    private val entries = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?): Boolean {
            if (totalBytes <= ControlShadowPolicy.MAX_CACHE_BYTES) return false
            if (eldest != null) totalBytes -= eldest.value.byteCount
            return true
        }
    }

    @Volatile private var registered = false

    /**
     * 渲染失败（含内存不足）后关闭整层阴影；下一次内存告急清空时重新允许。
     * 阴影是修饰性能力，失败不得冒泡到宿主的绘制栈（`ENGINEERING_RULES.md` §6）。
     */
    @Volatile private var failed = false

    /**
     * 必须强引用：`LumenMemoryPressureHub` 以 `WeakHashMap` 保存监听器，内联 lambda 在第一次 GC
     * 后就会被回收，而 [registered] 仍为 true，`releaseGraphics()` 将永远清不到缓存。
     */
    private val memoryListener = LumenMemoryPressureListener { clear() }

    /**
     * 命中或生成 [metrics] 对应的纹理。环境层模糊为 0（只留接触带）或已失败时返回 null；
     * 接触带层始终可用，失败时同样返回 null（由调用方跳过该层）。
     */
    fun obtain(metrics: ControlShadowPolicy.TextureMetrics): Bitmap? {
        if (metrics.textureSidePx <= 0 || failed) return null
        if (metrics.layer == ControlShadowPolicy.Layer.CAST && metrics.textureBlurPx <= 0) return null
        val id = metrics.key
        synchronized(lock) { entries[id]?.let { return it } }
        // 锁外烘焙：逐像素距离查询不应阻塞其它表面或主线程上的其它绘制。
        // runCatching 覆盖 createBitmap 的内存不足：修饰性失败只关闭投射层，不冒泡到宿主。
        val bitmap = runCatching { render(metrics) }.getOrElse {
            failed = true
            return null
        }
        synchronized(lock) {
            entries[id]?.let { existing ->
                // 并发烘焙落败：这张图从未被绘制引用，可以直接释放。
                bitmap.recycle()
                return existing
            }
            // 先记账再插入：removeEldestEntry 在 put 内部调用，必须看到含新条目的总量。
            totalBytes += bitmap.byteCount
            entries[id] = bitmap
        }
        register()
        return bitmap
    }

    private fun register() {
        if (registered) return
        registered = true
        LumenMemoryPressureHub.addListener(memoryListener)
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        totalBytes = 0
        failed = false
    }

    /** 测试与诊断用：当前缓存占用字节。 */
    fun bytes(): Int = synchronized(lock) { totalBytes }

    /** 测试与诊断用：当前缓存条目数。 */
    fun count(): Int = synchronized(lock) { entries.size }

    /**
     * 在纹理空间按有符号距离填 alpha：轮廓处达峰，外侧按层剖面衰减，内侧恒为 0。
     * 白色 RGB 由 [android.graphics.PorterDuffColorFilter] 在绘制时替换为阴影颜色。
     */
    private fun render(metrics: ControlShadowPolicy.TextureMetrics): Bitmap {
        val side = metrics.textureSidePx
        val half = side / 2f
        val halfExtent = half - metrics.texturePadPx
        val corner = metrics.textureCornerPx.toFloat()
        val lut = ControlShadowPolicy.buildAlphaLut(metrics)
        val last = lut.size - 1
        val span = metrics.texturePadPx.toFloat().coerceAtLeast(1f)
        val bias = metrics.offsetBias
        val innerTop = metrics.texturePadPx.toFloat()
        val innerHeight = (side - 2 * metrics.texturePadPx).coerceAtLeast(1).toFloat()
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            val dy = y + 0.5f - half
            val row = y * side
            // 顶光感用竖向增益表达：底部更强、顶部更弱，内边界仍贴着表面轮廓，不产生亮缝。
            val yNorm = ((y + 0.5f - innerTop) / innerHeight).coerceIn(0f, 1f)
            val gain = if (bias == 0f) 1f else ControlShadowPolicy.verticalGain(yNorm, bias)
            for (x in 0 until side) {
                val dx = x + 0.5f - half
                val distance = ControlShadowPolicy.roundedRectDistance(dx, dy, halfExtent, halfExtent, corner)
                var index = (distance / span * last).roundToInt()
                if (index < 0) index = 0 else if (index > last) index = last
                val base = lut[index]
                val alpha = if (gain == 1f) base else (base * gain).roundToInt().coerceIn(0, 255)
                pixels[row + x] = if (alpha == 0) 0 else (alpha shl 24) or 0x00FFFFFF
            }
        }
        val bitmap = createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, side, 0, 0, side, side)
        bitmap.prepareToDraw()
        return bitmap
    }
}
