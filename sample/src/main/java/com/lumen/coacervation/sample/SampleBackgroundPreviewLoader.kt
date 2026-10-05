package com.lumen.coacervation.sample

import android.app.Activity
import android.widget.ImageView
import androidx.annotation.MainThread
import androidx.core.view.doOnLayout
import com.lumen.coacervation.engine.background.LiquidBackgroundConfig
import com.lumen.coacervation.engine.background.LiquidBackgroundStore
import com.lumen.coacervation.engine.model.LumenPalette
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** 宿主接入示例：Activity 拥有预览任务，布局后解码，交付前验证代次与 View 生命周期。 */
internal class SampleBackgroundPreviewLoader(activity: Activity) {
    private val owner = WeakReference(activity)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Lumen-SamplePreview").apply { isDaemon = true }
    }
    private var pending: Future<*>? = null
    private var generation = 0L
    private var closed = false

    @MainThread
    fun load(preview: ImageView, config: LiquidBackgroundConfig, palette: LumenPalette) {
        if (closed) return
        val request = ++generation
        pending?.cancel(true)
        val recipient = WeakReference(preview)
        preview.doOnLayout { view ->
            val activity = owner.get() ?: return@doOnLayout
            if (closed || request != generation || activity.isFinishing || activity.isDestroyed ||
                !view.isAttachedToWindow || view.width <= 0 || view.height <= 0
            ) return@doOnLayout
            val context = activity.applicationContext
            val width = view.width
            val height = view.height
            pending = worker.submit {
                val bitmap = LiquidBackgroundStore.decodePreview(context, config, width, height, palette)
                    ?: return@submit
                if (Thread.currentThread().isInterrupted) {
                    bitmap.recycle()
                    return@submit
                }
                val destination = owner.get()
                if (destination == null) bitmap.recycle() else destination.runOnUiThread {
                    val target = recipient.get()
                    if (closed || request != generation || destination.isFinishing || destination.isDestroyed ||
                        target == null || !target.isAttachedToWindow
                    ) bitmap.recycle() else target.setImageBitmap(bitmap)
                }
            }
        }
    }

    @MainThread
    fun close() {
        if (closed) return
        closed = true
        generation++
        pending?.cancel(true)
        worker.shutdownNow()
    }
}
