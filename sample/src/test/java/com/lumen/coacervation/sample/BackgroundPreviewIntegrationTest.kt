package com.lumen.coacervation.sample

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPreviewIntegrationTest {
    private fun source(name: String): String {
        val relative = "src/main/java/com/lumen/coacervation/sample/$name.kt"
        val file = listOf(File(relative), File("sample/$relative")).firstOrNull(File::isFile)
            ?: error("Missing sample source: $name")
        return file.readText().replace("\r\n", "\n")
    }

    @Test fun hostOwnsOnePreviewWorkerAndClosesItWithActivity() {
        val loader = source("SampleBackgroundPreviewLoader")
        val activity = source("SampleActivity")
        assertTrue(loader.contains("private val worker = Executors.newSingleThreadExecutor"))
        assertFalse(loader.contains("companion object"))
        assertTrue(loader.contains("private val owner = WeakReference(activity)"))
        assertTrue(loader.contains("pending?.cancel(true)"))
        assertTrue(loader.contains("worker.shutdownNow()"))
        assertTrue(activity.contains("backgroundPreviewLoader?.close()"))
    }

    @Test fun sampleWaitsForLayoutAndRejectsStaleOrDetachedRecipients() {
        val loader = source("SampleBackgroundPreviewLoader")
        assertTrue(loader.contains("preview.doOnLayout"))
        assertTrue(loader.contains("LiquidBackgroundStore.decodePreview(context, config, width, height, palette)"))
        assertTrue(loader.contains("request != generation"))
        assertTrue(loader.contains("destination.isFinishing || destination.isDestroyed"))
        assertTrue(loader.contains("target == null || !target.isAttachedToWindow"))
        assertTrue(loader.contains("Thread.currentThread().isInterrupted"))
        assertTrue(loader.contains("bitmap.recycle() else target.setImageBitmap(bitmap)"))
    }
}
