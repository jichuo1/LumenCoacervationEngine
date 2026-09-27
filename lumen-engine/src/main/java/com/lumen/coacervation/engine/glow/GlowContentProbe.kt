package com.lumen.coacervation.engine.glow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.MainThread
import androidx.core.graphics.createBitmap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 悬浮表面下方内容的亮度/细节探针。
 *
 * 与柔光透镜（`LiveBackdropSampler`）同一套分工：主线程只把内容层的绘制指令**录**进 [Picture]
 * （遍历视图树，只有这一步必须在 UI 线程），光栅化与统计全部在后台单线程完成。
 * 采样按 1/[SCALE] 缩放——亮度均值与细节强度都是整块统计量，不需要全分辨率。
 *
 * 单飞：同一时刻至多一批在后台；在飞期间的新请求由调用方记脏、批次回来后续采。
 */
@MainThread
internal class GlowContentProbe(
    private val onSamples: (List<GlowContentSample?>) -> Unit
) : AutoCloseable {
    private class Slot {
        val picture = Picture()
        var width = 0
        var height = 0
        // 以下只在后台线程访问。
        var bitmap: Bitmap? = null
        var canvas: Canvas? = null
        var pixels: IntArray? = null
        var rowLuma: FloatArray? = null
    }

    private val slots = ArrayList<Slot>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerStarted = false
    private val worker: ExecutorService by lazy {
        workerStarted = true
        Executors.newSingleThreadExecutor { task -> Thread(task, "Lumen-GlowProbe").apply { isDaemon = true } }
    }
    private var token = 0
    private var closed = false

    var inFlight = false
        private set

    /**
     * 录制 [regions]（[content] 局部坐标）下方的画面：先画 [background]（窗口底图），再画 [content]。
     *
     * @return false 表示本次没有发出（已关闭或上一批仍在后台）。
     * @throws RuntimeException 录制本身失败（某个 View 不支持软件绘制）；调用方应永久停用探针。
     */
    fun probe(content: View, regions: List<RectF>, background: (Canvas, RectF) -> Unit): Boolean {
        if (closed || inFlight || regions.isEmpty()) return false
        while (slots.size < regions.size) slots += Slot()
        for ((index, region) in regions.withIndex()) {
            val slot = slots[index]
            slot.width = ceil(region.width() / SCALE).toInt().coerceAtLeast(1)
            slot.height = ceil(region.height() / SCALE).toInt().coerceAtLeast(1)
            val recording = slot.picture.beginRecording(slot.width, slot.height)
            try {
                recording.scale(1f / SCALE, 1f / SCALE)
                recording.translate(-region.left, -region.top)
                // 软件绘制对 clipChildren 的子 View 做 quickReject：区域外的卡片整棵跳过。
                recording.clipRect(region)
                background(recording, region)
                content.draw(recording)
            } finally {
                slot.picture.endRecording()
            }
        }
        val batch = slots.subList(0, regions.size).toList()
        inFlight = true
        val batchToken = token
        worker.execute {
            val results = batch.map { slot -> runCatching { measure(slot) }.getOrNull() }
            mainHandler.post {
                inFlight = false
                if (!closed && batchToken == token) onSamples(results)
            }
        }
        return true
    }

    /** 后台：回放到小位图并统计。位图按尺寸复用，只在尺寸变化时重建。 */
    private fun measure(slot: Slot): GlowContentSample? {
        val width = slot.width
        val height = slot.height
        var bitmap = slot.bitmap
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap?.recycle()
            bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            slot.bitmap = bitmap
            slot.canvas = Canvas(bitmap)
            slot.pixels = IntArray(width * height)
            slot.rowLuma = FloatArray(width)
        }
        val canvas = slot.canvas ?: return null
        val pixels = slot.pixels ?: return null
        val above = slot.rowLuma ?: return null
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawPicture(slot.picture)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return GlowContentStatistics.measure(pixels, width, height, above)
    }

    override fun close() {
        if (closed) return
        closed = true
        token++
        if (workerStarted) {
            // 在飞批次仍持有位图；关停排在它之后，由最后一个任务释放。
            worker.execute {
                slots.forEach { it.bitmap?.recycle(); it.bitmap = null; it.canvas = null }
            }
            worker.shutdown()
        }
    }

    private companion object {
        const val SCALE = 4f
    }
}

/**
 * 探针的统计核：亮度均值与细节强度。纯函数，与光栅化分开，便于 JVM 测试。
 */
internal object GlowContentStatistics {
    /**
     * 透明像素（没有底图也没有内容的角落）不计入——它们在屏幕上是窗口根背景，不属于"下方内容"。
     *
     * @param above 长度 ≥ [width] 的暂存行，存上一行亮度；调用方负责复用。
     */
    fun measure(pixels: IntArray, width: Int, height: Int, above: FloatArray): GlowContentSample? {
        require(pixels.size >= width * height && above.size >= width)
        var lumaSum = 0.0
        var lumaCount = 0
        var detailSum = 0.0
        var detailCount = 0
        for (y in 0 until height) {
            var left = Float.NaN
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val luma = if (color ushr 24 < OPAQUE_ENOUGH) Float.NaN else GlowLegibilityPolicy.encodedLuma(color)
                if (!luma.isNaN()) {
                    lumaSum += luma
                    lumaCount++
                    if (!left.isNaN()) { detailSum += abs(luma - left); detailCount++ }
                    if (y > 0 && !above[x].isNaN()) { detailSum += abs(luma - above[x]); detailCount++ }
                }
                left = luma
                above[x] = luma
            }
        }
        if (lumaCount == 0) return null
        return GlowContentSample(
            luma = (lumaSum / lumaCount).toFloat(),
            busyness = if (detailCount == 0) 0f else (detailSum / detailCount).toFloat()
        )
    }

    private const val OPAQUE_ENOUGH = 128
}
