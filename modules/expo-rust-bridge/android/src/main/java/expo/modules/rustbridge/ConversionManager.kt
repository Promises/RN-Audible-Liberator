package expo.modules.rustbridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.FFprobeKit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/**
 * Conversion Manager - Manages FFmpeg-Kit conversion queue with pause/resume
 *
 * Features:
 * - Queue-based conversion with FIFO ordering
 * - Pause/resume/cancel operations
 * - Progress tracking via FFmpeg statistics
 * - Persistent state in SharedPreferences
 * - Single-threaded conversion (FFmpeg is CPU-intensive)
 */
class ConversionManager(private val context: Context) {
    companion object {
        private const val TAG = "ConversionManager"
        private const val PREFS_NAME = "conversion_manager_state"
        private const val PREFS_TASKS_KEY = "tasks"
    }

    data class ConversionTask(
        val taskId: String,
        val asin: String,
        val title: String,
        var status: TaskStatus,
        val inputPath: String,
        val outputPath: String,
        val aaxcKey: String,
        val aaxcIv: String,
        var progress: Progress = Progress(),
        var error: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        var startedAt: Long? = null,
        var completedAt: Long? = null
    ) {
        data class Progress(
            var percentage: Double = 0.0,
            var currentTimeMs: Long = 0,
            var durationMs: Long = 0,
            var speedRatio: Double = 0.0
        )
    }

    enum class TaskStatus {
        QUEUED, CONVERTING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val tasks = ConcurrentHashMap<String, ConversionTask>()
    private val isConverting = AtomicBoolean(false)
    private var conversionJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var progressListener: ((ConversionTask) -> Unit)? = null
    private var completionListener: ((ConversionTask) -> Unit)? = null

    init {
        loadPersistedTasks()
    }

    /**
     * Enqueue a conversion task
     */
    fun enqueueConversion(
        taskId: String,
        asin: String,
        title: String,
        inputPath: String,
        outputPath: String,
        aaxcKey: String,
        aaxcIv: String
    ): String {
        val task = ConversionTask(
            taskId = taskId,
            asin = asin,
            title = title,
            status = TaskStatus.QUEUED,
            inputPath = inputPath,
            outputPath = outputPath,
            aaxcKey = aaxcKey,
            aaxcIv = aaxcIv
        )

        tasks[taskId] = task
        persistTasks()

        Log.d(TAG, "Enqueued conversion: $taskId - $title")

        // Auto-start if not already converting
        tryStartNextConversion()

        return taskId
    }

    /**
     * Get a task by ID
     */
    fun getTask(taskId: String): ConversionTask? {
        return tasks[taskId]
    }

    /**
     * List all tasks with optional status filter
     */
    fun listTasks(filter: TaskStatus? = null): List<ConversionTask> {
        return tasks.values
            .filter { filter == null || it.status == filter }
            .sortedBy { it.createdAt }
    }

    /**
     * Get count of active conversions
     */
    fun getActiveCount(): Int {
        return tasks.values.count { it.status == TaskStatus.CONVERTING }
    }

    /**
     * Pause a conversion
     */
    fun pauseConversion(taskId: String) {
        val task = tasks[taskId] ?: return

        if (task.status == TaskStatus.CONVERTING) {
            // Cancel the FFmpeg session
            FFmpegKit.cancel()
            task.status = TaskStatus.PAUSED
            persistTasks()
            Log.d(TAG, "Paused conversion: $taskId")
        } else if (task.status == TaskStatus.QUEUED) {
            // Just update status
            task.status = TaskStatus.PAUSED
            persistTasks()
            Log.d(TAG, "Paused queued conversion: $taskId")
        }
    }

    /**
     * Resume a paused conversion
     */
    fun resumeConversion(taskId: String) {
        val task = tasks[taskId] ?: return

        if (task.status == TaskStatus.PAUSED) {
            task.status = TaskStatus.QUEUED
            persistTasks()
            Log.d(TAG, "Resumed conversion: $taskId")
            tryStartNextConversion()
        }
    }

    /**
     * Cancel a conversion
     */
    fun cancelConversion(taskId: String) {
        val task = tasks[taskId] ?: return

        if (task.status == TaskStatus.CONVERTING) {
            FFmpegKit.cancel()
        }

        task.status = TaskStatus.CANCELLED
        persistTasks()

        // Delete partial output file
        File(task.outputPath).delete()

        Log.d(TAG, "Cancelled conversion: $taskId")

        // Start next task
        tryStartNextConversion()
    }

    /**
     * Retry a failed conversion
     */
    fun retryConversion(taskId: String) {
        val task = tasks[taskId] ?: return

        if (task.status == TaskStatus.FAILED) {
            task.status = TaskStatus.QUEUED
            task.error = null
            task.progress = ConversionTask.Progress()
            persistTasks()
            Log.d(TAG, "Retrying conversion: $taskId")
            tryStartNextConversion()
        }
    }

    /**
     * Set progress listener
     */
    fun setProgressListener(listener: (ConversionTask) -> Unit) {
        this.progressListener = listener
    }

    /**
     * Set completion listener
     */
    fun setCompletionListener(listener: (ConversionTask) -> Unit) {
        this.completionListener = listener
    }

    /**
     * Resume all pending conversions on app restart
     */
    fun resumeAllPending() {
        // Update any "converting" tasks to "queued" (interrupted by app close)
        tasks.values.forEach { task ->
            if (task.status == TaskStatus.CONVERTING) {
                task.status = TaskStatus.QUEUED
            }
        }
        persistTasks()

        // Start conversion if tasks available
        tryStartNextConversion()
    }

    /**
     * Clean up old completed/cancelled tasks
     */
    fun cleanupOldTasks(maxAgeMs: Long = 24 * 60 * 60 * 1000) { // 24 hours default
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val toRemove = tasks.values.filter {
            (it.status == TaskStatus.COMPLETED || it.status == TaskStatus.CANCELLED) &&
            (it.completedAt ?: it.createdAt) < cutoff
        }

        toRemove.forEach { tasks.remove(it.taskId) }

        if (toRemove.isNotEmpty()) {
            persistTasks()
            Log.d(TAG, "Cleaned up ${toRemove.size} old tasks")
        }
    }

    // ========================================================================
    // Internal Methods
    // ========================================================================

    /**
     * Try to start next queued conversion if not already converting
     */
    private fun tryStartNextConversion() {
        if (isConverting.get()) {
            return
        }

        // Get next queued task
        val nextTask = tasks.values
            .filter { it.status == TaskStatus.QUEUED }
            .minByOrNull { it.createdAt }
            ?: return

        startConversion(nextTask)
    }

    /**
     * Start a conversion task
     */
    private fun startConversion(task: ConversionTask) {
        if (!isConverting.compareAndSet(false, true)) {
            Log.w(TAG, "Already converting, cannot start: ${task.taskId}")
            return
        }

        Log.d(TAG, "Starting conversion: ${task.taskId} - ${task.title}")

        // Update status
        task.status = TaskStatus.CONVERTING
        task.startedAt = System.currentTimeMillis()
        persistTasks()

        // Launch coroutine
        conversionJob = coroutineScope.launch {
            try {
                performConversion(task)
            } catch (e: Exception) {
                Log.e(TAG, "Conversion failed: ${task.taskId}", e)
                task.status = TaskStatus.FAILED
                task.error = e.message
                persistTasks()
                progressListener?.invoke(task)
            } finally {
                isConverting.set(false)

                // Notify completion if successful
                if (task.status == TaskStatus.COMPLETED) {
                    completionListener?.invoke(task)
                }

                // Start next task
                tryStartNextConversion()
            }
        }
    }

    /**
     * Perform actual FFmpeg conversion
     */
    private suspend fun performConversion(task: ConversionTask) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Converting: ${task.inputPath} -> ${task.outputPath}")

        // Get duration first
        val probeSession = FFprobeKit.getMediaInformation(task.inputPath)
        val durationMs = probeSession.mediaInformation?.duration?.toLongOrNull() ?: 0L
        task.progress.durationMs = durationMs

        Log.d(TAG, "Duration: ${durationMs}ms (${durationMs / 1000}s)")

        // Build FFmpeg command
        val command = buildList {
            add("-y") // Overwrite output
            add("-audible_key")
            add(task.aaxcKey)
            add("-audible_iv")
            add(task.aaxcIv)
            add("-i")
            add(task.inputPath)
            add("-c")
            add("copy") // Fast copy without re-encoding
            add("-vn") // No video
            add(task.outputPath)
        }.joinToString(" ")

        Log.d(TAG, "FFmpeg command: $command")

        // Set statistics callback for progress
        var lastUpdate = System.currentTimeMillis()
        FFmpegKitConfig.enableStatisticsCallback { statistics: Statistics ->
            val now = System.currentTimeMillis()

            // Update progress (max once per second to avoid overhead)
            if (now - lastUpdate >= 1000) {
                val currentTimeMs = statistics.time.toLong()
                task.progress.currentTimeMs = currentTimeMs

                if (durationMs > 0) {
                    task.progress.percentage = (currentTimeMs.toDouble() / durationMs) * 100.0
                }

                task.progress.speedRatio = statistics.speed.toDouble()

                progressListener?.invoke(task)
                lastUpdate = now
            }
        }

        // Execute conversion
        val session = FFmpegKit.execute(command)

        // Disable statistics callback
        FFmpegKitConfig.enableStatisticsCallback(null)

        // Check result
        if (ReturnCode.isSuccess(session.returnCode)) {
            task.status = TaskStatus.COMPLETED
            task.completedAt = System.currentTimeMillis()
            task.progress.percentage = 100.0
            persistTasks()

            // Delete input file (encrypted)
            File(task.inputPath).delete()

            Log.d(TAG, "Conversion completed: ${task.taskId}")
            progressListener?.invoke(task)
        } else if (ReturnCode.isCancel(session.returnCode)) {
            Log.d(TAG, "Conversion cancelled: ${task.taskId}")
            // Status already set by pauseConversion() or cancelConversion()
        } else {
            task.status = TaskStatus.FAILED
            task.error = "FFmpeg failed: ${session.failStackTrace}"
            persistTasks()

            Log.e(TAG, "Conversion failed: ${task.taskId} - ${task.error}")
            progressListener?.invoke(task)
        }
    }

    /**
     * Load persisted tasks from SharedPreferences
     */
    private fun loadPersistedTasks() {
        try {
            val json = prefs.getString(PREFS_TASKS_KEY, null) ?: return
            val jsonArray = JSONArray(json)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val task = ConversionTask(
                    taskId = obj.getString("taskId"),
                    asin = obj.getString("asin"),
                    title = obj.getString("title"),
                    status = TaskStatus.valueOf(obj.getString("status")),
                    inputPath = obj.getString("inputPath"),
                    outputPath = obj.getString("outputPath"),
                    aaxcKey = obj.getString("aaxcKey"),
                    aaxcIv = obj.getString("aaxcIv"),
                    error = obj.optString("error").takeIf { it.isNotEmpty() },
                    createdAt = obj.getLong("createdAt"),
                    startedAt = obj.optLong("startedAt").takeIf { it > 0 },
                    completedAt = obj.optLong("completedAt").takeIf { it > 0 }
                )
                tasks[task.taskId] = task
            }

            Log.d(TAG, "Loaded ${tasks.size} persisted tasks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted tasks", e)
        }
    }

    /**
     * Persist tasks to SharedPreferences
     */
    private fun persistTasks() {
        try {
            val jsonArray = JSONArray()

            // Only persist non-completed/cancelled tasks
            tasks.values.forEach { task ->
                if (task.status != TaskStatus.COMPLETED && task.status != TaskStatus.CANCELLED) {
                    val obj = JSONObject().apply {
                        put("taskId", task.taskId)
                        put("asin", task.asin)
                        put("title", task.title)
                        put("status", task.status.name)
                        put("inputPath", task.inputPath)
                        put("outputPath", task.outputPath)
                        put("aaxcKey", task.aaxcKey)
                        put("aaxcIv", task.aaxcIv)
                        task.error?.let { put("error", it) }
                        put("createdAt", task.createdAt)
                        task.startedAt?.let { put("startedAt", it) }
                        task.completedAt?.let { put("completedAt", it) }
                    }
                    jsonArray.put(obj)
                }
            }

            prefs.edit().putString(PREFS_TASKS_KEY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist tasks", e)
        }
    }

    /**
     * Clean up and shutdown
     */
    fun shutdown() {
        conversionJob?.cancel()
        coroutineScope.cancel()
    }
}
