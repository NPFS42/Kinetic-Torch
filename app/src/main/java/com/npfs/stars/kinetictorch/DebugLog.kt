package com.npfs.stars.kinetictorch

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Debug-only, local JSONL recorder. Sensor callbacks never perform disk I/O. */
object DebugLog {
    private const val TAG = "KineticTorchDebug"
    private const val LIMIT = 2L * 1024 * 1024
    private const val FILES = 4
    private sealed interface Work {
        data class Line(val text: String) : Work
        data class Barrier(val done: CountDownLatch) : Work
    }
    private val queue = ArrayBlockingQueue<Work>(4096)
    private val dropped = AtomicLong()
    private val sequence = AtomicLong()
    private val diskLock = Any()
    private val session = UUID.randomUUID().toString()
    @Volatile private var directory: File? = null
    @Volatile var lastDecision = "Waiting for service events"
        private set
    @Volatile var storageError: String? = null
        private set

    @Synchronized fun start(context: Context) {
        if (!BuildConfig.DEBUG || directory != null) return
        directory = File(context.applicationContext.filesDir, "debug-logs").also { it.mkdirs() }
        Thread({ consume() }, "kinetic-debug-writer").apply { isDaemon = true; start() }
        event("process_start", "version" to BuildConfig.VERSION_NAME,
            "package" to context.packageName, "sdk" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
            "max_retained_bytes_approx" to LIMIT * FILES)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            event("uncaught_exception", "thread" to thread.name,
                "exception" to error.javaClass.name, "stack" to Log.getStackTraceString(error))
            flush(1000)
            previous?.uncaughtException(thread, error)
        }
    }

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        if (!BuildConfig.DEBUG || directory == null) return
        // Diagnostics must never break torch control, even if storage/serialization fails.
        try {
            val row = JSONObject().put("event", name).put("session", session)
                .put("seq", sequence.incrementAndGet()).put("wall_ms", System.currentTimeMillis())
                .put("elapsed_ms", SystemClock.elapsedRealtime()).put("thread", Thread.currentThread().name)
            fields.forEach { (key, value) ->
                row.put(key, when (value) {
                    null -> JSONObject.NULL
                    is Float -> if (value.isFinite()) value else value.toString()
                    is Double -> if (value.isFinite()) value else value.toString()
                    is Enum<*> -> value.name
                    else -> value
                })
            }
            val text = row.toString()
            if (!queue.offer(Work.Line(text))) dropped.incrementAndGet()
            if (name !in setOf("motion_sample", "motion_raw", "ui_motion_sample", "gesture_reset", "proximity_sample")) {
                lastDecision = "$name: ${fields.firstOrNull { it.first == "reason" }?.second ?: "recorded"}"
                Log.d(TAG, text)
            }
        } catch (error: Exception) {
            storageError = error.javaClass.simpleName
            Log.e(TAG, "Diagnostic recording failed", error)
        }
    }

    fun <T> action(name: String, block: () -> T): T {
        event(name, "reason" to "REQUESTED")
        try {
            return block().also { event(name, "reason" to "API_RETURNED") }
        } catch (error: Exception) {
            event(name, "reason" to "API_EXCEPTION", "exception" to error.javaClass.name,
                "message" to error.message, "stack" to Log.getStackTraceString(error))
            throw error
        }
    }

    private fun flush(timeoutMs: Long): Boolean {
        val done = CountDownLatch(1)
        return try {
            queue.offer(Work.Barrier(done), timeoutMs, TimeUnit.MILLISECONDS) &&
                done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    /** Call off the UI thread. Export oldest retained record first, including queue-loss notices. */
    fun export(output: OutputStream) {
        check(BuildConfig.DEBUG && directory != null) { "Debug logger is not running" }
        check(flush(2000)) { "Logger busy; try export again" }
        synchronized(diskLock) {
            check(storageError == null) { "Log storage failed: $storageError" }
            for (index in FILES - 1 downTo 0) {
                file(index).takeIf { it.exists() }?.inputStream()?.use { it.copyTo(output) }
            }
        }
    }

    private fun file(index: Int) = File(requireNotNull(directory), "trace-$index.jsonl")

    private fun append(line: String) {
        synchronized(diskLock) {
            if (file(0).length() + line.toByteArray(Charsets.UTF_8).size + 1 > LIMIT) {
                file(FILES - 1).takeIf { it.exists() }?.let { check(it.delete()) }
                for (index in FILES - 2 downTo 0) {
                    file(index).takeIf { it.exists() }?.let { check(it.renameTo(file(index + 1))) }
                }
            }
            file(0).appendText(line + "\n", Charsets.UTF_8)
        }
    }

    private fun consume() {
        while (true) {
            when (val work = queue.take()) {
                is Work.Barrier -> work.done.countDown()
                is Work.Line -> try {
                    val lost = dropped.getAndSet(0)
                    if (lost > 0) append(JSONObject().put("event", "records_dropped")
                        .put("session", session).put("count", lost)
                        .put("elapsed_ms", SystemClock.elapsedRealtime()).toString())
                    append(work.text)
                } catch (error: Exception) {
                    storageError = error.javaClass.simpleName
                    Log.e(TAG, "Could not write debug trace", error)
                }
            }
        }
    }
}
