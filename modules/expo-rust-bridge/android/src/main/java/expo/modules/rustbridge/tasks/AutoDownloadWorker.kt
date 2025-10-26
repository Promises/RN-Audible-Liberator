package expo.modules.rustbridge.tasks

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import expo.modules.rustbridge.ExpoRustBridgeModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import org.json.JSONObject

/**
 * Worker for automatic downloads
 *
 * Features:
 * - Listens for LibrarySyncComplete events
 * - Finds books matching download criteria
 * - Enqueues downloads automatically
 * - Respects WiFi-only and storage limits
 * - Configurable download criteria (wishlist, series, etc.)
 */
class AutoDownloadWorker(
    private val context: Context,
    private val manager: BackgroundTaskManager
) {
    companion object {
        private const val TAG = "AutoDownloadWorker"
        private const val PREFS_NAME = "auto_download_prefs"
        private const val PREF_ENABLED = "enabled"
        private const val PREF_WIFI_ONLY = "wifi_only"
        private const val PREF_MAX_DOWNLOADS = "max_downloads"
        private const val PREF_CRITERIA = "criteria"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var eventListenerJob: Job? = null

    /**
     * Enable automatic downloads
     */
    fun enable() {
        Log.d(TAG, "Enabling auto-download")
        prefs.edit().putBoolean(PREF_ENABLED, true).apply()
        startEventListener()
    }

    /**
     * Disable automatic downloads
     */
    fun disable() {
        Log.d(TAG, "Disabling auto-download")
        prefs.edit().putBoolean(PREF_ENABLED, false).apply()
        stopEventListener()
    }

    /**
     * Check if auto-download is enabled
     */
    fun isEnabled(): Boolean = prefs.getBoolean(PREF_ENABLED, false)

    /**
     * Execute an auto-download task
     */
    suspend fun execute(task: Task) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Executing auto-download task")

            // Check if enabled
            if (!isEnabled()) {
                Log.d(TAG, "Auto-download is disabled, skipping")
                task.status = TaskStatus.CANCELLED
                task.completedAt = java.util.Date()
                manager.emitEvent(TaskEvent.TaskCancelled(task))
                manager.unregisterActiveTask(task.id)
                return@withContext
            }

            // Check WiFi if required
            val wifiOnly = prefs.getBoolean(PREF_WIFI_ONLY, true)
            if (wifiOnly && !manager.isWifiAvailable()) {
                Log.d(TAG, "WiFi required but not available, skipping")
                task.status = TaskStatus.CANCELLED
                task.completedAt = java.util.Date()
                manager.emitEvent(TaskEvent.TaskCancelled(task))
                manager.unregisterActiveTask(task.id)
                return@withContext
            }

            // Find books to download
            val booksToDownload = findBooksToDownload()

            if (booksToDownload.isEmpty()) {
                Log.d(TAG, "No books match auto-download criteria")
                task.status = TaskStatus.COMPLETED
                task.completedAt = java.util.Date()
                manager.emitEvent(TaskEvent.AutoDownloadComplete(task.id, 0))
                manager.emitEvent(TaskEvent.TaskCompleted(task))
                manager.unregisterActiveTask(task.id)
                return@withContext
            }

            Log.d(TAG, "Found ${booksToDownload.size} books to auto-download")
            manager.emitEvent(TaskEvent.AutoDownloadStarted(task.id, booksToDownload.size))

            // Enqueue downloads
            var downloadedCount = 0
            for (book in booksToDownload) {
                try {
                    enqueueDownload(book)
                    downloadedCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enqueue download for ${book["asin"]}", e)
                }
            }

            // Mark as completed
            task.status = TaskStatus.COMPLETED
            task.completedAt = java.util.Date()
            manager.emitEvent(TaskEvent.AutoDownloadComplete(task.id, downloadedCount))
            manager.emitEvent(TaskEvent.TaskCompleted(task))
            manager.unregisterActiveTask(task.id)

            Log.d(TAG, "Auto-download complete: $downloadedCount downloads enqueued")

        } catch (e: Exception) {
            Log.e(TAG, "Auto-download failed", e)
            task.status = TaskStatus.FAILED
            task.error = e.message
            task.completedAt = java.util.Date()
            manager.emitEvent(TaskEvent.TaskFailed(task, e.message ?: "Auto-download failed"))
            manager.unregisterActiveTask(task.id)
        }
    }

    /**
     * Start listening for library sync completion
     */
    private fun startEventListener() {
        if (eventListenerJob?.isActive == true) {
            Log.w(TAG, "Event listener already running")
            return
        }

        Log.d(TAG, "Starting event listener for library sync completion")

        eventListenerJob = scope.launch {
            manager.eventFlow
                .filter { it is TaskEvent.LibrarySyncComplete }
                .collect { event ->
                    Log.d(TAG, "Library sync completed, triggering auto-download check")

                    // Create and enqueue auto-download task
                    val taskId = "auto_download_${System.currentTimeMillis()}"
                    val task = Task(
                        id = taskId,
                        type = TaskType.AUTO_DOWNLOAD,
                        priority = TaskPriority.MEDIUM,
                        status = TaskStatus.PENDING
                    )

                    // Execute immediately
                    execute(task)
                }
        }
    }

    /**
     * Stop event listener
     */
    private fun stopEventListener() {
        Log.d(TAG, "Stopping event listener")
        eventListenerJob?.cancel()
        eventListenerJob = null
    }

    /**
     * Find books that match auto-download criteria
     */
    private suspend fun findBooksToDownload(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val dbPath = manager.getDbPath()
            val maxDownloads = prefs.getInt(PREF_MAX_DOWNLOADS, 10)

            // Get all books from database
            val getBooksParams = JSONObject().apply {
                put("db_path", dbPath)
                put("offset", 0)
                put("limit", 1000)
            }
            val booksResultJson = ExpoRustBridgeModule.nativeGetBooks(getBooksParams.toString())
            val booksResultObj = JSONObject(booksResultJson)

            if (!booksResultObj.getBoolean("success")) {
                Log.w(TAG, "Failed to get books: ${booksResultObj.optString("error")}")
                return@withContext emptyList()
            }

            val dataObj = booksResultObj.getJSONObject("data")
            val booksArray = dataObj.getJSONArray("books")

            // Convert JSONArray to List<Map<String, Any>>
            val books = mutableListOf<Map<String, Any>>()
            for (i in 0 until booksArray.length()) {
                val bookObj = booksArray.getJSONObject(i)
                val bookMap = mutableMapOf<String, Any>()
                bookObj.keys().forEach { key ->
                    bookMap[key] = bookObj.get(key)
                }
                books.add(bookMap)
            }

            // Filter books based on criteria
            // TODO: Implement configurable criteria (wishlist, series, new releases, etc.)
            val matchingBooks = books.filter { book: Map<String, Any> ->
                // Example criteria: Books not already downloaded
                val downloadStatus = book.getOrDefault("download_status", "not_downloaded") as? String
                downloadStatus != "downloaded"
            }.take(maxDownloads)

            matchingBooks

        } catch (e: Exception) {
            Log.e(TAG, "Error finding books to download", e)
            emptyList()
        }
    }

    /**
     * Enqueue a download for a book
     */
    private suspend fun enqueueDownload(book: Map<String, Any>) = withContext(Dispatchers.IO) {
        try {
            val asin = book["asin"] as? String ?: throw Exception("No ASIN")
            val title = book["title"] as? String ?: throw Exception("No title")
            val author = book["author"] as? String

            // Load account and output directory from preferences
            val accountJson = getAccountJson() ?: throw Exception("No account")
            val outputDir = getOutputDirectory() ?: throw Exception("No output directory")

            Log.d(TAG, "Enqueueing auto-download: $asin - $title")

            // Use manager to enqueue download
            manager.enqueueDownload(
                asin = asin,
                title = title,
                author = author,
                accountJson = accountJson,
                outputDirectory = outputDir,
                quality = "High"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download", e)
            throw e
        }
    }

    /**
     * Get account JSON from SQLite database
     */
    private fun getAccountJson(): String? {
        return try {
            val getAccountParams = org.json.JSONObject().apply {
                put("db_path", manager.getDbPath())
            }
            val accountResultJson = ExpoRustBridgeModule.nativeGetPrimaryAccount(getAccountParams.toString())
            val accountResultObj = org.json.JSONObject(accountResultJson)

            if (!accountResultObj.getBoolean("success")) {
                android.util.Log.d(TAG, "Failed to get account from database")
                null
            } else {
                val accountJson = accountResultObj.getJSONObject("data").optString("account")
                if (accountJson.isNullOrEmpty() || accountJson == "null") null else accountJson
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error getting account from database", e)
            null
        }
    }

    /**
     * Get output directory from preferences
     */
    private fun getOutputDirectory(): String? {
        // TODO: Get from user settings
        // For now, return a default path
        val defaultDir = context.getExternalFilesDir(null)?.absolutePath
        return defaultDir?.let { "file://$it/audiobooks" }
    }
}
