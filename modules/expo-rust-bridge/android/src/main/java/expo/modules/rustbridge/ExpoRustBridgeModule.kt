package expo.modules.rustbridge

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import org.json.JSONObject
import org.json.JSONArray
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import expo.modules.rustbridge.workers.WorkerScheduler
import java.io.File

class ExpoRustBridgeModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoRustBridge")

    // ============================================================================
    // AUTHENTICATION FUNCTIONS
    // ============================================================================

    /**
     * Generate an OAuth authorization URL for Audible login.
     *
     * @param localeCode The Audible locale (e.g., "us", "uk", "de")
     * @param deviceSerial The device serial number (32 hex characters)
     * @return Map with success flag and either data (url, pkce, state) or error message
     */
    Function("generateOAuthUrl") { localeCode: String, deviceSerial: String ->
      val params = JSONObject().apply {
        put("locale_code", localeCode)
        put("device_serial", deviceSerial)
      }
      parseJsonResponse(nativeGenerateOAuthUrl(params.toString()))
    }

    /**
     * Parse the OAuth callback URL to extract authorization code.
     *
     * @param callbackUrl The callback URL received from Audible OAuth
     * @return Map with success flag and either data (auth_code, state) or error message
     */
    Function("parseOAuthCallback") { callbackUrl: String ->
      val params = JSONObject().apply {
        put("callback_url", callbackUrl)
      }
      parseJsonResponse(nativeParseOAuthCallback(params.toString()))
    }

    /**
     * Exchange authorization code for access and refresh tokens.
     *
     * @param localeCode The Audible locale
     * @param authCode The authorization code from callback
     * @param deviceSerial The device serial number
     * @param pkceVerifier The PKCE code verifier from initial OAuth request
     * @return Promise resolving to Map with tokens or rejecting with error
     */
    AsyncFunction("exchangeAuthCode") { localeCode: String, authCode: String, deviceSerial: String, pkceVerifier: String ->
      try {
        val params = JSONObject().apply {
          put("locale_code", localeCode)
          put("authorization_code", authCode)
          put("device_serial", deviceSerial)
          put("pkce_verifier", pkceVerifier)
        }
        val result = nativeExchangeAuthCode(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf(
          "success" to false,
          "error" to "Exchange auth code error: ${e.message}"
        )
      }
    }

    /**
     * Refresh an expired access token using the refresh token.
     *
     * @param localeCode The Audible locale
     * @param refreshToken The refresh token
     * @param deviceSerial The device serial number
     * @return Promise resolving to Map with new tokens or rejecting with error
     */
    AsyncFunction("refreshAccessToken") { localeCode: String, refreshToken: String, deviceSerial: String ->
      try {
        val params = JSONObject().apply {
          put("locale_code", localeCode)
          put("refresh_token", refreshToken)
          put("device_serial", deviceSerial)
        }
        val result = nativeRefreshAccessToken(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf(
          "success" to false,
          "error" to "Refresh token error: ${e.message}"
        )
      }
    }

    /**
     * Get activation bytes for DRM removal using access token.
     *
     * @param localeCode The Audible locale
     * @param accessToken The access token
     * @return Promise resolving to Map with activation bytes or rejecting with error
     */
    AsyncFunction("getActivationBytes") { localeCode: String, accessToken: String ->
      try {
        val params = JSONObject().apply {
          put("locale_code", localeCode)
          put("access_token", accessToken)
        }
        val result = nativeGetActivationBytes(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf(
          "success" to false,
          "error" to "Get activation bytes error: ${e.message}"
        )
      }
    }

    // ============================================================================
    // DATABASE FUNCTIONS
    // ============================================================================

    /**
     * Initialize the SQLite database with schema.
     *
     * @param dbPath The path to the SQLite database file
     * @return Map with success flag and error message if failed
     */
    Function("initDatabase") { dbPath: String ->
      val params = JSONObject().apply {
        put("db_path", dbPath)
      }
      parseJsonResponse(nativeInitDatabase(params.toString()))
    }

    /**
     * Sync library from Audible API to local database.
     *
     * @param dbPath The path to the SQLite database file
     * @param accountJson JSON string containing account info (access_token, locale, etc.)
     * @return Promise resolving to Map with sync results or rejecting with error
     */
    AsyncFunction("syncLibrary") { dbPath: String, accountJson: String ->
      try {
        val params = JSONObject().apply {
          put("db_path", dbPath)
          put("account_json", accountJson)
        }
        val result = nativeSyncLibrary(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf(
          "success" to false,
          "error" to "Sync library error: ${e.message}"
        )
      }
    }

    /**
     * Sync a single page of library from Audible API.
     *
     * This allows for progressive UI updates by fetching one page at a time.
     *
     * @param dbPath The path to the SQLite database file
     * @param accountJson JSON string containing account info (access_token, locale, etc.)
     * @param page The page number to fetch (1-indexed)
     * @return Promise resolving to Map with sync results including has_more flag
     */
    AsyncFunction("syncLibraryPage") { dbPath: String, accountJson: String, page: Int ->
      try {
        val params = JSONObject().apply {
          put("db_path", dbPath)
          put("account_json", accountJson)
          put("page", page)
        }
        val result = nativeSyncLibraryPage(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf(
          "success" to false,
          "error" to "Sync library page error: ${e.message}"
        )
      }
    }

    /**
     * Get paginated list of books from database.
     *
     * @param dbPath The path to the SQLite database file
     * @param offset The pagination offset
     * @param limit The number of books to retrieve
     * @return Map with success flag and list of books or error message
     */
    Function("getBooks") { dbPath: String, offset: Int, limit: Int ->
      val params = JSONObject().apply {
        put("db_path", dbPath)
        put("offset", offset)
        put("limit", limit)
      }
      parseJsonResponse(nativeGetBooks(params.toString()))
    }

    /**
     * Search books in database by title, author, or narrator.
     *
     * @param dbPath The path to the SQLite database file
     * @param query The search query string
     * @return Map with success flag and list of matching books or error message
     */
    Function("searchBooks") { dbPath: String, query: String ->
      val params = JSONObject().apply {
        put("db_path", dbPath)
        put("query", query)
      }
      parseJsonResponse(nativeSearchBooks(params.toString()))
    }

    /**
     * Get books with advanced filtering, sorting, and search.
     *
     * @param dbPath The path to the SQLite database file
     * @param offset Pagination offset
     * @param limit Maximum number of results
     * @param searchQuery Optional search query (searches title, author, narrator)
     * @param seriesName Optional series filter
     * @param category Optional category/genre filter
     * @param sortField Sort field: "title", "release_date", or "date_added"
     * @param sortDirection Sort direction: "asc" or "desc"
     * @return Map with success flag, books array, and total_count
     */
    Function("getBooksWithFilters") {
      dbPath: String,
      offset: Int,
      limit: Int,
      searchQuery: String?,
      seriesName: String?,
      category: String?,
      sortField: String?,
      sortDirection: String?
    ->
      val params = JSONObject().apply {
        put("db_path", dbPath)
        put("offset", offset)
        put("limit", limit)
        if (searchQuery != null) put("search_query", searchQuery)
        if (seriesName != null) put("series_name", seriesName)
        if (category != null) put("category", category)
        if (sortField != null) put("sort_field", sortField)
        if (sortDirection != null) put("sort_direction", sortDirection)
      }
      parseJsonResponse(nativeGetBooksWithFilters(params.toString()))
    }

    /**
     * Get all unique series names from the library.
     *
     * @param dbPath The path to the SQLite database file
     * @return Map with success flag and array of series names
     */
    Function("getAllSeries") { dbPath: String ->
      val params = JSONObject().apply {
        put("db_path", dbPath)
      }
      parseJsonResponse(nativeGetAllSeries(params.toString()))
    }

    /**
     * Get all unique categories/genres from the library.
     *
     * @param dbPath The path to the SQLite database file
     * @return Map with success flag and array of category names
     */
    Function("getAllCategories") { dbPath: String ->
      val params = JSONObject().apply {
        put("db_path", dbPath)
      }
      parseJsonResponse(nativeGetAllCategories(params.toString()))
    }


    /**
     * Get list of supported Audible locales.
     *
     * @return Map with success flag and array of supported locales or error message
     */
    Function("getSupportedLocales") {
      val params = JSONObject() // Empty params
      parseJsonResponse(nativeGetSupportedLocales(params.toString()))
    }

    /**
     * Get customer information from Audible API.
     *
     * @param localeCode The Audible locale (e.g., "us", "uk")
     * @param accessToken Valid access token
     * @return Map with success flag and customer info (name, email) or error message
     */
    AsyncFunction("getCustomerInformation") { localeCode: String, accessToken: String, promise: Promise ->
      val params = JSONObject().apply {
        put("locale_code", localeCode)
        put("access_token", accessToken)
      }
      val response = parseJsonResponse(nativeGetCustomerInformation(params.toString()))
      promise.resolve(response)
    }

    // ============================================================================
    // FFMPEG-KIT FUNCTIONS (16KB Page Size Compatible)
    // ============================================================================

    /**
     * Get audio file duration and metadata using FFprobe.
     *
     * @param filePath Path to audio file
     * @return Promise resolving to Map with duration and metadata
     */
    AsyncFunction("getAudioInfo") { filePath: String ->
      try {
        val session = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(filePath)
        val info = session.mediaInformation

        if (info != null) {
          mapOf(
            "success" to true,
            "data" to mapOf(
              "duration" to info.duration.toDoubleOrNull(),
              "bitrate" to info.bitrate,
              "format" to info.format,
              "size" to info.size
            )
          )
        } else {
          mapOf(
            "success" to false,
            "error" to "Could not get media information"
          )
        }
      } catch (e: Exception) {
        mapOf(
          "success" to false,
          "error" to "Get audio info error: ${e.message}"
        )
      }
    }

    // ============================================================================
    // DOWNLOAD MANAGER FUNCTIONS
    // ============================================================================

    /**
     * Enqueue a download using the persistent download manager.
     *
     * @param dbPath Path to SQLite database
     * @param accountJson Complete account JSON
     * @param asin Book ASIN
     * @param title Book title
     * @param outputDirectory Output directory (can be SAF URI)
     * @param quality Download quality
     * @return Promise resolving to Map with task_id
     */
    AsyncFunction("enqueueDownload") { dbPath: String, accountJson: String, asin: String, title: String, outputDirectory: String, quality: String ->
      try {
        DownloadService.enqueueBook(
          context = appContext.reactContext ?: throw Exception("Context not available"),
          dbPath = dbPath,
          accountJson = accountJson,
          asin = asin,
          title = title,
          outputDirectory = outputDirectory,
          quality = quality
        )

        mapOf(
          "success" to true,
          "data" to mapOf("message" to "Download enqueued")
        )
      } catch (e: Exception) {
        mapOf(
          "success" to false,
          "error" to "Enqueue download error: ${e.message}"
        )
      }
    }

    /**
     * Get download task status.
     *
     * @param dbPath Path to SQLite database
     * @param taskId Task ID
     * @return Map with task details
     */
    Function("getDownloadTask") { dbPath: String, taskId: String ->
      val params = JSONObject().apply {
        put("db_path", dbPath)
        put("task_id", taskId)
      }
      parseJsonResponse(nativeGetDownloadTask(params.toString()))
    }

    /**
     * List download tasks with optional filter.
     *
     * @param dbPath Path to SQLite database
     * @param filter Optional status filter ("queued", "downloading", "completed", "failed", etc.)
     * @return Map with list of tasks
     */
    Function("listDownloadTasks") { dbPath: String, filter: String? ->
      val params = JSONObject().apply {
        put("db_path", dbPath)
        filter?.let { put("filter", it) }
      }
      parseJsonResponse(nativeListDownloadTasks(params.toString()))
    }

    /**
     * Pause a download.
     *
     * @param dbPath Path to SQLite database
     * @param taskId Task ID to pause
     * @return Map with success status
     */
    Function("pauseDownload") { dbPath: String, taskId: String ->
      val params = JSONObject().apply {
        put("db_path", dbPath)
        put("task_id", taskId)
      }
      parseJsonResponse(nativePauseDownload(params.toString()))
    }

    /**
     * Resume a paused download.
     *
     * @param dbPath Path to SQLite database
     * @param taskId Task ID to resume
     * @return Map with success status
     */
    Function("resumeDownload") { dbPath: String, taskId: String ->
      val params = JSONObject().apply {
        put("db_path", dbPath)
        put("task_id", taskId)
      }
      parseJsonResponse(nativeResumeDownload(params.toString()))
    }

    /**
     * Cancel a download.
     *
     * @param dbPath Path to SQLite database
     * @param taskId Task ID to cancel
     * @return Map with success status
     */
    Function("cancelDownload") { dbPath: String, taskId: String ->
      val params = JSONObject().apply {
        put("db_path", dbPath)
        put("task_id", taskId)
      }
      parseJsonResponse(nativeCancelDownload(params.toString()))
    }

    // ============================================================================
    // BACKGROUND TASK MANAGER FUNCTIONS (New System)
    // ============================================================================

    /**
     * Start the background task service.
     * Must be called once when app starts.
     */
    Function("startBackgroundService") {
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        expo.modules.rustbridge.tasks.BackgroundTaskService.start(context)
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Stop the background task service.
     * This will disable all automatic features (token refresh, library sync, auto-download).
     */
    Function("stopBackgroundService") {
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        expo.modules.rustbridge.tasks.BackgroundTaskService.stopService(context)
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Enqueue a download using the new background task system.
     *
     * @param asin Book ASIN
     * @param title Book title
     * @param author Optional book author
     * @param accountJson Complete account JSON
     * @param outputDirectory Output directory (SAF URI)
     * @param quality Download quality
     * @return Map with task_id
     */
    AsyncFunction("enqueueDownloadNew") { asin: String, title: String, author: String?, accountJson: String, outputDirectory: String, quality: String ->
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val cacheDir = context.cacheDir
        val dbPath = File(cacheDir, "audible.db").absolutePath

        // Use DownloadService for downloads
        DownloadService.enqueueBook(
          context = context,
          dbPath = dbPath,
          accountJson = accountJson,
          asin = asin,
          title = title,
          outputDirectory = outputDirectory,
          quality = quality
        )
        mapOf("success" to true, "data" to mapOf("message" to "Download enqueued"))
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Start library sync using the new background task system.
     *
     * @param fullSync Whether to do a full sync (default: false)
     * @return Map with task_id
     */
    AsyncFunction("startLibrarySyncNew") { fullSync: Boolean ->
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        expo.modules.rustbridge.tasks.BackgroundTaskService.startLibrarySync(context, fullSync)
        mapOf("success" to true, "data" to mapOf("message" to "Library sync started"))
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Enable automatic downloads after library sync.
     */
    Function("enableAutoDownload") {
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val manager = expo.modules.rustbridge.tasks.BackgroundTaskManager.getInstance(context)
        manager.enableAutoDownload()
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Disable automatic downloads.
     */
    Function("disableAutoDownload") {
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val manager = expo.modules.rustbridge.tasks.BackgroundTaskManager.getInstance(context)
        manager.disableAutoDownload()
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Save account to SQLite database (single source of truth).
     *
     * @param dbPath Database path
     * @param accountJson Account JSON string
     */
    AsyncFunction("saveAccount") { dbPath: String, accountJson: String ->
      try {
        val params = JSONObject().apply {
          put("db_path", dbPath)
          put("account_json", accountJson)
        }
        val result = nativeSaveAccount(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Get primary account from SQLite database.
     *
     * @param dbPath Database path
     * @return Account JSON or null if no account exists
     */
    AsyncFunction("getPrimaryAccount") { dbPath: String ->
      try {
        val params = JSONObject().apply {
          put("db_path", dbPath)
        }
        val result = nativeGetPrimaryAccount(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Enable automatic library sync.
     *
     * @param intervalHours Sync interval in hours (default: 24)
     */
    Function("enableAutoSync") { intervalHours: Int? ->
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val prefs = context.getSharedPreferences("library_sync_prefs", Context.MODE_PRIVATE)

        // Set interval if provided
        if (intervalHours != null && intervalHours > 0) {
          prefs.edit().putInt("sync_interval_hours", intervalHours).apply()
        }

        prefs.edit().putBoolean("auto_sync_enabled", true).apply()
        mapOf("success" to true, "data" to mapOf("intervalHours" to (intervalHours ?: 24)))
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Disable automatic library sync.
     */
    Function("disableAutoSync") {
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val prefs = context.getSharedPreferences("library_sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_sync_enabled", false).apply()
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Pause a task.
     *
     * @param taskId Task ID to pause
     */
    AsyncFunction("pauseTask") { taskId: String ->
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val manager = expo.modules.rustbridge.tasks.BackgroundTaskManager.getInstance(context)
        val success = kotlinx.coroutines.runBlocking {
          manager.pauseTask(taskId)
        }
        mapOf("success" to success)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Resume a paused task.
     *
     * @param taskId Task ID to resume
     */
    AsyncFunction("resumeTask") { taskId: String ->
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val manager = expo.modules.rustbridge.tasks.BackgroundTaskManager.getInstance(context)
        val success = kotlinx.coroutines.runBlocking {
          manager.resumeTask(taskId)
        }
        mapOf("success" to success)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Cancel a task.
     *
     * @param taskId Task ID to cancel
     */
    AsyncFunction("cancelTask") { taskId: String ->
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val manager = expo.modules.rustbridge.tasks.BackgroundTaskManager.getInstance(context)
        val success = kotlinx.coroutines.runBlocking {
          manager.cancelTask(taskId)
        }
        mapOf("success" to success)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Get all active tasks.
     *
     * @return List of active tasks with their details
     */
    Function("getActiveTasks") {
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val manager = expo.modules.rustbridge.tasks.BackgroundTaskManager.getInstance(context)
        val tasks = manager.getActiveTasks()

        val taskMaps = tasks.map { task ->
          mapOf(
            "id" to task.id,
            "type" to task.type.name,
            "priority" to task.priority.name,
            "status" to task.status.name,
            "metadata" to task.metadata,
            "createdAt" to task.createdAt.time,
            "startedAt" to task.startedAt?.time,
            "completedAt" to task.completedAt?.time,
            "error" to task.error
          )
        }

        mapOf("success" to true, "data" to taskMaps)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Get a specific task by ID.
     *
     * @param taskId Task ID
     * @return Task details or null if not found
     */
    Function("getTask") { taskId: String ->
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val manager = expo.modules.rustbridge.tasks.BackgroundTaskManager.getInstance(context)
        val task = manager.getTask(taskId)

        if (task != null) {
          mapOf(
            "success" to true,
            "data" to mapOf(
              "id" to task.id,
              "type" to task.type.name,
              "priority" to task.priority.name,
              "status" to task.status.name,
              "metadata" to task.metadata,
              "createdAt" to task.createdAt.time,
              "startedAt" to task.startedAt?.time,
              "completedAt" to task.completedAt?.time,
              "error" to task.error
            )
          )
        } else {
          mapOf("success" to false, "error" to "Task not found")
        }
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Clear all tasks (for debugging/recovery from stuck states).
     */
    Function("clearAllTasks") {
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val manager = expo.modules.rustbridge.tasks.BackgroundTaskManager.getInstance(context)
        manager.clearAllTasks()
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Check if the background service is currently running.
     *
     * @return Map with isRunning boolean
     */
    Function("isBackgroundServiceRunning") {
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager

        val isRunning = activityManager.getRunningServices(Integer.MAX_VALUE).any { service ->
          service.service.className == "expo.modules.rustbridge.tasks.BackgroundTaskService"
        }

        mapOf("success" to true, "data" to mapOf("isRunning" to isRunning))
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Clear download state for all books.
     *
     * Resets download status but keeps all book metadata.
     *
     * @param dbPath Database path
     */
    AsyncFunction("clearDownloadState") { dbPath: String ->
      try {
        val params = JSONObject().apply {
          put("db_path", dbPath)
        }
        val result = nativeClearDownloadState(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Get the downloaded file path for a book by ASIN.
     *
     * @param dbPath Database path
     * @param asin Book ASIN (Audible product ID)
     * @return Map with file_path (null if not found)
     */
    AsyncFunction("getBookFilePath") { dbPath: String, asin: String ->
      try {
        val params = JSONObject().apply {
          put("db_path", dbPath)
          put("asin", asin)
        }
        val result = nativeGetBookFilePath(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Clear download state for a single book by ASIN.
     *
     * This marks a book as not downloaded, clearing its download status
     * and removing any download tasks to reset to default state.
     * Optionally deletes the downloaded file from disk.
     *
     * @param dbPath Database path
     * @param asin Book ASIN (Audible product ID)
     * @param deleteFile Whether to delete the downloaded file
     */
    AsyncFunction("clearBookDownloadState") { dbPath: String, asin: String, deleteFile: Boolean ->
      try {
        val params = JSONObject().apply {
          put("db_path", dbPath)
          put("asin", asin)
          put("delete_file", deleteFile)
        }
        val result = nativeClearBookDownloadState(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Set the file path for a book manually.
     *
     * Allows marking a book as downloaded by associating it with an existing
     * audio file. Creates a download task with status "completed".
     *
     * @param dbPath Database path
     * @param asin Audible product ID
     * @param title Book title
     * @param filePath Absolute path to the audio file
     */
    AsyncFunction("setBookFilePath") { dbPath: String, asin: String, title: String, filePath: String ->
      try {
        val params = JSONObject().apply {
          put("db_path", dbPath)
          put("asin", asin)
          put("title", title)
          put("file_path", filePath)
        }
        val result = nativeSetBookFilePath(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Create cover art file (EmbeddedCover.jpg) for a book.
     *
     * Downloads and saves the book's cover image as EmbeddedCover.jpg (500x500)
     * in the same directory as the audio file for Smart Audiobook Player compatibility.
     *
     * @param asin Audible product ID
     * @param coverUrl URL of the cover image
     * @param audioFilePath Path to the audio file (cover will be saved in same directory)
     */
    AsyncFunction("createCoverArtFile") { asin: String, coverUrl: String, audioFilePath: String ->
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")

        // Get the directory containing the audio file
        val audioUri = Uri.parse(if (audioFilePath.startsWith("content://")) audioFilePath else "file://$audioFilePath")
        val audioFile = DocumentFile.fromSingleUri(context, audioUri)
          ?: throw Exception("Could not access audio file")

        val targetDir = audioFile.parentFile
          ?: throw Exception("Could not access parent directory")

        // Download cover image to cache
        val cacheDir = context.cacheDir
        val coverFile = File(cacheDir, "cover_$asin.jpg")

        // Download the cover image
        val url = java.net.URL(coverUrl.replace(Regex("_SL\\d+_"), "_SL500_"))
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.connect()

        if (connection.responseCode != 200) {
          throw Exception("Failed to download cover image: HTTP ${connection.responseCode}")
        }

        coverFile.outputStream().use { output ->
          connection.inputStream.use { input ->
            input.copyTo(output)
          }
        }

        // Load and resize cover image
        val originalBitmap = android.graphics.BitmapFactory.decodeFile(coverFile.absolutePath)
          ?: throw Exception("Failed to decode cover image")

        val resizedBitmap = android.graphics.Bitmap.createScaledBitmap(
          originalBitmap,
          500,
          500,
          true
        )

        // Delete existing EmbeddedCover.jpg if present
        targetDir.findFile("EmbeddedCover.jpg")?.delete()

        // Create new file
        val embeddedCover = targetDir.createFile("image/jpeg", "EmbeddedCover.jpg")
          ?: throw Exception("Failed to create EmbeddedCover.jpg")

        // Write JPEG
        context.contentResolver.openOutputStream(embeddedCover.uri)?.use { outputStream ->
          resizedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outputStream)
        } ?: throw Exception("Failed to open output stream for EmbeddedCover.jpg")

        // Cleanup
        originalBitmap.recycle()
        resizedBitmap.recycle()
        coverFile.delete()

        mapOf(
          "success" to true,
          "data" to mapOf(
            "coverPath" to embeddedCover.uri.toString(),
            "message" to "Cover art created successfully"
          )
        )
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Clear all library data (for testing).
     *
     * @param dbPath Database path
     */
    AsyncFunction("clearLibrary") { dbPath: String ->
      try {
        val params = JSONObject().apply {
          put("db_path", dbPath)
        }
        val result = nativeClearLibrary(params.toString())
        parseJsonResponse(result)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Test bridge connection and verify Rust library is loaded.
     *
     * @return Map with bridge status information
     */
    Function("testBridge") {
      mapOf(
        "bridgeActive" to true,
        "rustLoaded" to true,
        "version" to "0.1.0"
      )
    }

    /**
     * Legacy test function - logs a message from Rust.
     *
     * @param message The message to log
     * @return The response from Rust
     */
    Function("logFromRust") { message: String ->
      val params = JSONObject().apply {
        put("message", message)
      }
      parseJsonResponse(nativeLogFromRust(params.toString()))
    }

    // ============================================================================
    // BACKGROUND WORKER SCHEDULING (WorkManager)
    // ============================================================================

    /**
     * Schedule periodic token refresh worker (12 hour interval recommended).
     *
     * @param intervalHours How often to check for token expiry
     * @return Map with success flag
     */
    Function("scheduleTokenRefresh") { intervalHours: Int ->
      try {
        WorkerScheduler.scheduleTokenRefresh(appContext.reactContext!!, intervalHours.toLong())
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Schedule periodic library sync worker.
     *
     * @param intervalHours How often to sync library (recommended: 24 hours)
     * @param wifiOnly If true, only sync on unmetered (WiFi) connections
     * @return Map with success flag
     */
    Function("scheduleLibrarySync") { intervalHours: Int, wifiOnly: Boolean ->
      try {
        WorkerScheduler.scheduleLibrarySync(appContext.reactContext!!, intervalHours.toLong(), wifiOnly)
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Cancel token refresh worker.
     *
     * @return Map with success flag
     */
    Function("cancelTokenRefresh") {
      try {
        WorkerScheduler.cancelTokenRefresh(appContext.reactContext!!)
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Cancel library sync worker.
     *
     * @return Map with success flag
     */
    Function("cancelLibrarySync") {
      try {
        WorkerScheduler.cancelLibrarySync(appContext.reactContext!!)
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Cancel all background workers.
     *
     * @return Map with success flag
     */
    Function("cancelAllBackgroundTasks") {
      try {
        WorkerScheduler.cancelAllWork(appContext.reactContext!!)
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Get status of token refresh worker.
     *
     * @return Map with worker state
     */
    Function("getTokenRefreshStatus") {
      try {
        val state = WorkerScheduler.getTokenRefreshStatus(appContext.reactContext!!)
        mapOf(
          "success" to true,
          "state" to (state?.toString() ?: "NOT_SCHEDULED")
        )
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Get status of library sync worker.
     *
     * @return Map with worker state
     */
    Function("getLibrarySyncStatus") {
      try {
        val state = WorkerScheduler.getLibrarySyncStatus(appContext.reactContext!!)
        mapOf(
          "success" to true,
          "state" to (state?.toString() ?: "NOT_SCHEDULED")
        )
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Set file naming pattern preference.
     *
     * @param pattern The naming pattern: "flat_file", "author_book_folder", or "author_series_book"
     * @return Map with success status
     */
    Function("setNamingPattern") { pattern: String ->
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("naming_pattern", pattern).apply()
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    /**
     * Get file naming pattern preference.
     *
     * @return Map with pattern value
     */
    Function("getNamingPattern") {
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val pattern = prefs.getString("naming_pattern", "author_series_book") ?: "author_series_book"
        mapOf("success" to true, "data" to mapOf("pattern" to pattern))
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    Function("setSmartPlayerCover") { enabled: Boolean ->
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("smart_player_cover_enabled", enabled.toString()).apply()
        mapOf("success" to true)
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

    Function("getSmartPlayerCover") {
      try {
        val context = appContext.reactContext ?: throw Exception("Context not available")
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val enabled = prefs.getString("smart_player_cover_enabled", "false") == "true"
        mapOf("success" to true, "data" to mapOf("enabled" to enabled))
      } catch (e: Exception) {
        mapOf("success" to false, "error" to e.message)
      }
    }

  }

  // ============================================================================
  // JSON PARSING HELPERS
  // ============================================================================

  /**
   * Escape metadata value for FFmpeg command line.
   * Wraps in double quotes and escapes special characters.
   */
  private fun escapeMetadata(value: String): String {
    val escaped = value
      .replace("\\", "\\\\")  // Escape backslashes
      .replace("\"", "\\\"")  // Escape double quotes
    return "\"$escaped\""  // Wrap in double quotes
  }

  /**
   * Parse JSON response from Rust into a Kotlin Map.
   *
   * Rust returns JSON in the format:
   * Success: { "success": true, "data": {...} }
   * Error: { "success": false, "error": "error message" }
   *
   * @param jsonString The JSON string from Rust
   * @return Map with success flag and either data or error
   */
  private fun parseJsonResponse(jsonString: String): Map<String, Any?> {
    return try {
      val json = JSONObject(jsonString)
      val success = json.getBoolean("success")

      if (success) {
        mapOf(
          "success" to true,
          "data" to parseJsonValue(json.get("data"))
        )
      } else {
        mapOf(
          "success" to false,
          "error" to json.getString("error")
        )
      }
    } catch (e: Exception) {
      mapOf(
        "success" to false,
        "error" to "Failed to parse JSON response: ${e.message}"
      )
    }
  }

  /**
   * Recursively parse JSON values into Kotlin types.
   *
   * @param value The JSON value to parse
   * @return Kotlin representation (Map, List, or primitive)
   */
  private fun parseJsonValue(value: Any?): Any? {
    return when (value) {
      is JSONObject -> {
        val map = mutableMapOf<String, Any?>()
        value.keys().forEach { key ->
          map[key] = parseJsonValue(value.get(key))
        }
        map
      }
      is JSONArray -> {
        (0 until value.length()).map { i ->
          parseJsonValue(value.get(i))
        }
      }
      JSONObject.NULL -> null
      else -> value
    }
  }

  // ============================================================================
  // COMPANION OBJECT
  // ============================================================================

  companion object {
    init {
      try {
        System.loadLibrary("rust_core")
        android.util.Log.i("ExpoRustBridge", "Successfully loaded rust_core library")
      } catch (e: UnsatisfiedLinkError) {
        // Library not found - this is expected in development mode
        // until Rust library is built
        android.util.Log.w("ExpoRustBridge", "Failed to load rust_core library: ${e.message}")
      }
    }

    // All native methods accept a single JSON string parameter
    // Made static so DownloadService can access them
    @JvmStatic external fun nativeGenerateOAuthUrl(paramsJson: String): String
    @JvmStatic external fun nativeParseOAuthCallback(paramsJson: String): String
    @JvmStatic external fun nativeExchangeAuthCode(paramsJson: String): String
    @JvmStatic external fun nativeRefreshAccessToken(paramsJson: String): String
    @JvmStatic external fun nativeGetActivationBytes(paramsJson: String): String
    @JvmStatic external fun nativeInitDatabase(paramsJson: String): String
    @JvmStatic external fun nativeSyncLibrary(paramsJson: String): String
    @JvmStatic external fun nativeSyncLibraryPage(paramsJson: String): String
    @JvmStatic external fun nativeGetBooks(paramsJson: String): String
    @JvmStatic external fun nativeGetBookByAsin(paramsJson: String): String
    @JvmStatic external fun nativeSearchBooks(paramsJson: String): String
    @JvmStatic external fun nativeGetBooksWithFilters(paramsJson: String): String
    @JvmStatic external fun nativeGetAllSeries(paramsJson: String): String
    @JvmStatic external fun nativeGetAllCategories(paramsJson: String): String
    @JvmStatic external fun nativeDownloadBook(paramsJson: String): String
    @JvmStatic external fun nativeDecryptAAX(paramsJson: String): String
    @JvmStatic external fun nativeValidateActivationBytes(paramsJson: String): String
    @JvmStatic external fun nativeGetSupportedLocales(paramsJson: String): String
    @JvmStatic external fun nativeBuildFilePath(paramsJson: String): String
    @JvmStatic external fun nativeGetCustomerInformation(paramsJson: String): String
    @JvmStatic external fun nativeLogFromRust(paramsJson: String): String

    // License function (get license without downloading)
    @JvmStatic external fun nativeGetDownloadLicense(paramsJson: String): String

    // Download Manager functions
    @JvmStatic external fun nativeEnqueueDownload(paramsJson: String): String
    @JvmStatic external fun nativeGetDownloadTask(paramsJson: String): String
    @JvmStatic external fun nativeListDownloadTasks(paramsJson: String): String
    @JvmStatic external fun nativePauseDownload(paramsJson: String): String
    @JvmStatic external fun nativeResumeDownload(paramsJson: String): String
    @JvmStatic external fun nativeCancelDownload(paramsJson: String): String

    // Account functions
    @JvmStatic external fun nativeSaveAccount(paramsJson: String): String
    @JvmStatic external fun nativeGetPrimaryAccount(paramsJson: String): String

    // Testing functions
    @JvmStatic external fun nativeClearDownloadState(paramsJson: String): String
    @JvmStatic external fun nativeGetBookFilePath(paramsJson: String): String
    @JvmStatic external fun nativeClearBookDownloadState(paramsJson: String): String
    @JvmStatic external fun nativeSetBookFilePath(paramsJson: String): String
    @JvmStatic external fun nativeClearLibrary(paramsJson: String): String
  }
}
