package com.lumen.coacervation.engine.host

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.lumen.coacervation.engine.runtime.LatestPreparation
import java.util.concurrent.Executors

/** Acquire a dedicated software bitmap on demand, on the preparer's worker thread. */
public fun interface LumenMicaImageSource {
    /**
     * Ownership of the returned bitmap transfers to the preparer, which always recycles it.
     * Allocate/acquire resources inside this call: a replaced or cancelled source may never run.
     * Keep acquisition bounded and interruptible. Do not return a shared or currently displayed bitmap.
     */
    @WorkerThread public fun load(): Bitmap
}

public enum class LumenMicaPreparationFailure { NONE, SOURCE_FAILED, PREPARATION_FAILED, WORKER_UNAVAILABLE }

public fun interface LumenMicaPreparationListener {
    /** Asynchronous main-thread delivery, independent of View attachment. Only the latest request is delivered. */
    @MainThread public fun onPrepared(image: LumenMicaImage?, failure: LumenMicaPreparationFailure)
}

/**
 * Optional, owner-scoped Mica preparation. Keeps at most one active operation, one latest pending source
 * and one latest undelivered result. Uses the exact synchronous [LumenMicaImage.prepare] recipe.
 * Close with the owner; no process-wide worker, View, Context or wallpaper access is retained.
 */
@MainThread
public class LumenMicaPreparer : AutoCloseable {
    private class Result(val image: LumenMicaImage?, val failure: LumenMicaPreparationFailure)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Lumen-MicaPrepare").apply { isDaemon = true }
    }
    private val queue = LatestPreparation(
        execute = worker::execute,
        post = main::post,
        unavailable = { Result(null, LumenMicaPreparationFailure.WORKER_UNAVAILABLE) },
        // Only snapshots that have never reached the listener may be recycled.
        discard = { result: Result -> result.image?.bitmap?.recycle() }
    )
    public val isClosed: Boolean get() = queue.isClosed

    init { checkMain() }

    /**
     * Replaces the pending source and suppresses all older callbacks, including already posted results.
     * The source is invoked only on the worker. Success transfers an independent immutable snapshot to
     * the listener; the listener may share it across backdrops and must not recycle its pixels.
     */
    public fun prepare(source: LumenMicaImageSource, listener: LumenMicaPreparationListener) {
        if (isClosed) return
        checkMain()
        queue.submit({ prepareOwned(source) }) { result ->
            try { listener.onPrepared(result.image, result.failure) } catch (_: Exception) {
                // A host callback failure must not strand the queue or recycle a published snapshot.
            }
        }
    }

    /**
     * Drops pending input/results and suppresses in-flight delivery. Active acquisition may finish;
     * its input remains owned until the worker returns. A later prepare is allowed.
     */
    public fun cancel() {
        if (isClosed) return
        checkMain(); queue.cancel()
    }

    override fun close() {
        if (isClosed) return
        checkMain(); queue.close(); worker.shutdownNow()
    }

    @WorkerThread
    private fun prepareOwned(source: LumenMicaImageSource): Result {
        var input: Bitmap? = null
        var failure = LumenMicaPreparationFailure.SOURCE_FAILED
        return try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            input = source.load()
            failure = LumenMicaPreparationFailure.PREPARATION_FAILED
            Result(LumenMicaImage.prepare(input), LumenMicaPreparationFailure.NONE)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); Result(null, failure)
        } catch (_: OutOfMemoryError) {
            Result(null, failure)
        } catch (_: Exception) {
            Result(null, failure)
        } finally {
            input?.recycle()
        }
    }

    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper()) { "Use Mica preparers on the main thread" }
}
