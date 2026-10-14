package com.lumen.coacervation.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.lumen.coacervation.engine.background.LiquidBackgroundConfig
import com.lumen.coacervation.engine.background.LiquidBackgroundConfigCodec
import com.lumen.coacervation.engine.background.LiquidBackgroundConfigIssue
import com.lumen.coacervation.engine.background.LiquidBackgroundMode
import com.lumen.coacervation.engine.background.LiquidBackgroundPreferenceAccess
import com.lumen.coacervation.engine.background.LiquidBackgroundStore
import com.lumen.coacervation.engine.background.LiquidBackgroundTransactions
import com.lumen.coacervation.engine.background.LiquidBackgroundWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LiquidBackgroundTransactionsTest {
    @Test
    fun `preference acquisition failure retains restore false contract`() {
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                error("Credential-protected preferences are unavailable before unlock")
            }
        }

        assertFalse(LiquidBackgroundStore.restoreAutomatic(context))
    }

    @Test
    fun `stale repair cannot overwrite a newer successful publication`() {
        val transactions = LiquidBackgroundTransactions()
        val preferences = Preferences(mapOf("schema_version" to 99))
        val snapshotTaken = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        preferences.afterSnapshot = {
            if (Thread.currentThread().name == "background-reader") {
                snapshotTaken.countDown()
                assertTrue(releaseSnapshot.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            val reader = executor.submit<LiquidBackgroundConfig> {
                Thread.currentThread().name = "background-reader"
                transactions.read(preferences).config
            }
            assertTrue(snapshotTaken.await(5, TimeUnit.SECONDS))
            val writer = executor.submit<LiquidBackgroundWriteResult> {
                writerStarted.countDown()
                transactions.replace(preferences, custom("new"))
            }
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            assertPending(writer)
            releaseSnapshot.countDown()

            assertEquals(LiquidBackgroundConfig.AUTOMATIC, reader.get(5, TimeUnit.SECONDS))
            assertTrue(writer.get(5, TimeUnit.SECONDS).committed)
            assertEquals(custom("new"), preferences.memoryConfig())
            assertEquals(custom("new"), preferences.diskConfig())
        } finally {
            releaseSnapshot.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `repair publishes in memory without waiting for disk`() {
        val transactions = LiquidBackgroundTransactions()
        val preferences = Preferences(mapOf("schema_version" to 99))

        val result = transactions.read(preferences)

        assertEquals(LiquidBackgroundConfigIssue.MISSING_OR_INVALID_SCHEMA, result.issue)
        assertEquals(LiquidBackgroundConfig.AUTOMATIC, preferences.memoryConfig())
        assertEquals(1, preferences.stages)
        assertEquals(0, preferences.persistenceCalls)
    }

    @Test
    fun `canonical reads do not publish or persist`() {
        val transactions = LiquidBackgroundTransactions()
        val preferences = Preferences(LiquidBackgroundConfigCodec.encode(custom("old")))

        assertEquals(custom("old"), transactions.read(preferences).config)
        assertEquals(0, preferences.stages)
        assertEquals(0, preferences.persistenceCalls)
    }

    @Test
    fun `read remains available while an import prepares and persists`() {
        val transactions = LiquidBackgroundTransactions()
        val preferences = Preferences(LiquidBackgroundConfigCodec.encode(custom("old")))
        val preparing = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val persisting = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        preferences.persistenceSteps.add {
            persisting.countDown()
            assertTrue(releasePersistence.await(5, TimeUnit.SECONDS))
            preferences.confirmMemoryOnDisk()
            true
        }
        try {
            val importer = executor.submit<LiquidBackgroundWriteResult> {
                transactions.withWriter {
                    preparing.countDown()
                    assertTrue(releasePreparation.await(5, TimeUnit.SECONDS))
                    transactions.replace(preferences, custom("new"))
                }
            }
            assertTrue(preparing.await(5, TimeUnit.SECONDS))
            assertEquals(custom("old"), executor.submit<LiquidBackgroundConfig> {
                transactions.read(preferences).config
            }.get(5, TimeUnit.SECONDS))
            releasePreparation.countDown()
            assertTrue(persisting.await(5, TimeUnit.SECONDS))
            assertEquals(custom("new"), executor.submit<LiquidBackgroundConfig> {
                transactions.read(preferences).config
            }.get(5, TimeUnit.SECONDS))
            releasePersistence.countDown()
            assertTrue(importer.get(5, TimeUnit.SECONDS).committed)
        } finally {
            releasePreparation.countDown()
            releasePersistence.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `disk failure rolls back memory and confirms old generation before cleanup`() {
        Fixture().use { fixture ->
            fixture.preferences.persistenceSteps.add { false }

            val result = fixture.replace(custom("new"))

            assertFalse(result.committed)
            assertEquals(custom("old"), result.confirmedConfig)
            assertEquals(custom("old"), fixture.preferences.memoryConfig())
            assertEquals(custom("old"), fixture.preferences.diskConfig())
            fixture.assertReferencesExist()
            assertTrue(fixture.asset("old").isFile)
            assertFalse(fixture.asset("new").exists())
        }
    }

    @Test
    fun `false after disk publication and failed rollback keep both generations`() {
        Fixture().use { fixture ->
            fixture.preferences.persistenceSteps.add {
                fixture.preferences.confirmMemoryOnDisk()
                // Android commit awaits a latch after publishing memory. An InterruptedException
                // returns false even when its disk work has already completed.
                Thread.currentThread().interrupt()
                try {
                    CountDownLatch(1).await()
                    error("Interrupted acknowledgement unexpectedly completed")
                } catch (_: InterruptedException) {
                    false
                }
            }
            fixture.preferences.persistenceSteps.add { false }

            val result = fixture.replace(custom("new"))

            assertFalse(result.committed)
            assertNull(result.confirmedConfig)
            assertEquals(custom("old"), fixture.preferences.memoryConfig())
            assertEquals(custom("new"), fixture.preferences.diskConfig())
            fixture.assertReferencesExist()
            assertTrue(fixture.asset("old").isFile)
            assertTrue(fixture.asset("new").isFile)
        }
    }

    @Test
    fun `late uncertain disk write retains its asset until a successful restore`() {
        Fixture().use { fixture ->
            fixture.preferences.persistenceSteps.add { false }
            fixture.preferences.persistenceSteps.add { false }
            assertNull(fixture.replace(custom("new")).confirmedConfig)
            fixture.preferences.completeFirstPendingWrite()
            assertEquals(custom("new"), fixture.preferences.diskConfig())
            fixture.assertReferencesExist()

            val restored = fixture.replace(LiquidBackgroundConfig.AUTOMATIC)

            assertTrue(restored.committed)
            assertEquals(LiquidBackgroundConfig.AUTOMATIC, fixture.preferences.memoryConfig())
            assertEquals(LiquidBackgroundConfig.AUTOMATIC, fixture.preferences.diskConfig())
            assertFalse(fixture.asset("old").exists())
            assertFalse(fixture.asset("new").exists())
        }
    }

    @Test
    fun `successful retry collects uncertain generations and preserves the retry asset`() {
        Fixture().use { fixture ->
            fixture.preferences.persistenceSteps.add { false }
            fixture.preferences.persistenceSteps.add { false }
            assertNull(fixture.replace(custom("new")).confirmedConfig)

            val retry = fixture.replace(custom("retry"))

            assertTrue(retry.committed)
            fixture.assertReferencesExist()
            assertEquals(custom("retry"), fixture.preferences.diskConfig())
            assertFalse(fixture.asset("old").exists())
            assertFalse(fixture.asset("new").exists())
            assertTrue(fixture.asset("retry").isFile)
        }
    }

    @Test
    fun `restore cannot clean another imports active temporary file`() {
        Fixture().use { fixture ->
            val temporary = File(fixture.directory, "liquid_import_active.raw")
            val prepared = CountDownLatch(1)
            val releaseImport = CountDownLatch(1)
            val restoreStarted = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val importer = executor.submit<LiquidBackgroundWriteResult> {
                    fixture.transactions.withWriter {
                        temporary.writeText("in progress")
                        prepared.countDown()
                        assertTrue(releaseImport.await(5, TimeUnit.SECONDS))
                        assertTrue(temporary.isFile)
                        fixture.replace(custom("new"))
                    }
                }
                assertTrue(prepared.await(5, TimeUnit.SECONDS))
                val restore = executor.submit<LiquidBackgroundWriteResult> {
                    restoreStarted.countDown()
                    fixture.replace(LiquidBackgroundConfig.AUTOMATIC)
                }
                assertTrue(restoreStarted.await(5, TimeUnit.SECONDS))
                assertPending(restore)
                assertTrue(temporary.isFile)
                releaseImport.countDown()

                assertTrue(importer.get(5, TimeUnit.SECONDS).committed)
                assertTrue(restore.get(5, TimeUnit.SECONDS).committed)
                assertFalse(temporary.exists())
                assertEquals(LiquidBackgroundConfig.AUTOMATIC, fixture.preferences.diskConfig())
                fixture.assertReferencesExist()
            } finally {
                releaseImport.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `snapshot failure cannot replace an unreadable existing background`() {
        val transactions = LiquidBackgroundTransactions()
        val preferences = Preferences(LiquidBackgroundConfigCodec.encode(custom("old")))
        preferences.afterSnapshot = { error("cannot read preferences") }

        assertEquals(LiquidBackgroundConfigIssue.TYPE_MISMATCH, transactions.read(preferences).issue)
        val result = transactions.replace(preferences, custom("new"))

        assertFalse(result.committed)
        assertNull(result.confirmedConfig)
        assertEquals(custom("old"), preferences.memoryConfig())
        assertEquals(0, preferences.stages)
    }

    @Test
    fun `stage exception after memory publication still rolls back safely`() {
        Fixture().use { fixture ->
            fixture.preferences.afterStage = { config ->
                if (config.assetId == "new") error("failure after memory update")
            }

            val result = fixture.replace(custom("new"))

            assertFalse(result.committed)
            assertEquals(custom("old"), result.confirmedConfig)
            fixture.assertReferencesExist()
            assertFalse(fixture.asset("new").exists())
        }
    }

    @Test
    fun `rollback stage exception preserves all possibly referenced generations`() {
        Fixture().use { fixture ->
            fixture.preferences.persistenceSteps.add {
                fixture.preferences.confirmMemoryOnDisk()
                false
            }
            fixture.preferences.afterStage = { config ->
                if (config.assetId == "old") error("rollback failed after memory update")
            }

            val result = fixture.replace(custom("new"))

            assertFalse(result.committed)
            assertNull(result.confirmedConfig)
            fixture.assertReferencesExist()
            assertTrue(fixture.asset("new").isFile)
            assertTrue(fixture.asset("old").isFile)
        }
    }

    @Test
    fun `persistence exception retains new asset when rollback also fails`() {
        Fixture().use { fixture ->
            fixture.preferences.persistenceSteps.add { error("write failed") }
            fixture.preferences.persistenceSteps.add { false }

            val result = fixture.replace(custom("new"))

            assertFalse(result.committed)
            assertNull(result.confirmedConfig)
            fixture.preferences.completeFirstPendingWrite()
            fixture.assertReferencesExist()
            assertTrue(fixture.asset("new").isFile)
        }
    }

    @Test
    fun `successful acknowledgement without matching readback cannot authorize cleanup`() {
        Fixture().use { fixture ->
            fixture.preferences.persistenceSteps.add {
                fixture.preferences.confirmMemoryOnDisk()
                fixture.preferences.unreadable = true
                true
            }
            fixture.preferences.persistenceSteps.add { false }

            val result = fixture.replace(custom("new"))

            assertFalse(result.committed)
            assertNull(result.confirmedConfig)
            fixture.assertReferencesExist()
        }
    }

    private class Preferences(initial: Map<String, *>) : LiquidBackgroundPreferenceAccess {
        private var memory: Map<String, *> = initial.toMap()
        private var disk: Map<String, *> = initial.toMap()
        private val pendingWrites = ArrayDeque<Map<String, *>>()
        val persistenceSteps = ArrayDeque<() -> Boolean>()
        var afterSnapshot: (() -> Unit)? = null
        var afterStage: ((LiquidBackgroundConfig) -> Unit)? = null
        var unreadable = false
        var stages = 0
        var persistenceCalls = 0

        override fun snapshot(): Map<String, *> {
            val values = synchronized(this) {
                check(!unreadable)
                memory.toMap()
            }
            afterSnapshot?.invoke()
            return values
        }

        override fun stage(config: LiquidBackgroundConfig) {
            synchronized(this) {
                memory = LiquidBackgroundConfigCodec.encode(config)
                pendingWrites.addLast(memory)
                stages++
            }
            afterStage?.invoke(config)
        }

        override fun persist(): Boolean {
            persistenceCalls++
            return if (persistenceSteps.isEmpty()) {
                confirmMemoryOnDisk()
                true
            } else persistenceSteps.removeFirst().invoke()
        }

        fun confirmMemoryOnDisk() = synchronized(this) {
            disk = memory.toMap()
            pendingWrites.clear()
        }

        fun completeFirstPendingWrite() = synchronized(this) {
            assertFalse(pendingWrites.isEmpty())
            disk = pendingWrites.removeFirst()
        }

        fun memoryConfig(): LiquidBackgroundConfig = synchronized(this) {
            LiquidBackgroundConfigCodec.decode(memory).config
        }

        fun diskConfig(): LiquidBackgroundConfig = synchronized(this) {
            LiquidBackgroundConfigCodec.decode(disk).config
        }
    }

    private class Fixture : AutoCloseable {
        val transactions = LiquidBackgroundTransactions()
        val preferences = Preferences(LiquidBackgroundConfigCodec.encode(custom("old")))
        val directory: File = Files.createTempDirectory("background-transactions").toFile()

        init { asset("old").writeText("complete old generation") }

        fun asset(id: String) = File(directory, "background_$id.img")

        fun replace(config: LiquidBackgroundConfig): LiquidBackgroundWriteResult = transactions.withWriter {
            config.assetId?.let { asset(it).writeText("complete generation $it") }
            val result = transactions.replace(preferences, config)
            result.confirmedConfig?.let { confirmed ->
                directory.listFiles()!!.forEach { file ->
                    if (file.name != confirmed.assetId?.let { "background_$it.img" }) file.delete()
                }
            }
            result
        }

        fun assertReferencesExist() {
            listOf(preferences.memoryConfig(), preferences.diskConfig()).forEach { config ->
                config.assetId?.let { assertTrue("Missing referenced generation $it", asset(it).isFile) }
            }
        }

        override fun close() { directory.deleteRecursively() }
    }

    companion object {
        private fun assertPending(operation: Future<*>) {
            try {
                operation.get(200, TimeUnit.MILLISECONDS)
                fail("Writer must wait until the active snapshot or import is released")
            } catch (_: TimeoutException) {
                // A bounded wait checks the ordering without depending on thread scheduling.
            }
        }

        private fun custom(id: String) = LiquidBackgroundConfig(
            mode = LiquidBackgroundMode.CUSTOM,
            assetId = id,
            assetSha256 = "ab".repeat(32),
            normalizedWidth = 1,
            normalizedHeight = 1
        )
    }
}
