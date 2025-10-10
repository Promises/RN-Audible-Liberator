package expo.modules.rustbridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.*

/**
 * Foreground Service for background downloads and conversions
 *
 * This service:
 * - Keeps downloads/conversions alive when app is backgrounded
 * - Shows persistent notification with progress
 * - Orchestrates download → conversion pipeline
 * - Handles lifecycle events and cleanup
 */
class DownloadService : Service() {
    companion object {
        private const val TAG = "DownloadService"
        private const val NOTIFICATION_CHANNEL_ID = "audiobook_downloads"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_ENQUEUE_DOWNLOAD = "expo.modules.rustbridge.ENQUEUE_DOWNLOAD"
        private const val ACTION_PAUSE_TASK = "expo.modules.rustbridge.PAUSE_TASK"
        private const val ACTION_RESUME_TASK = "expo.modules.rustbridge.RESUME_TASK"
        private const val ACTION_CANCEL_TASK = "expo.modules.rustbridge.CANCEL_TASK"
        private const val ACTION_SET_WIFI_ONLY = "expo.modules.rustbridge.SET_WIFI_ONLY"

        private const val EXTRA_DB_PATH = "db_path"
        private const val EXTRA_ACCOUNT_JSON = "account_json"
        private const val EXTRA_ASIN = "asin"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_OUTPUT_DIR = "output_dir"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_WIFI_ONLY = "wifi_only"

        /**
         * Enqueue a book download
         */
        fun enqueueBook(
            context: Context,
            dbPath: String,
            accountJson: String,
            asin: String,
            title: String,
            outputDirectory: String,
            quality: String = "High"
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE_DOWNLOAD
                putExtra(EXTRA_DB_PATH, dbPath)
                putExtra(EXTRA_ACCOUNT_JSON, accountJson)
                putExtra(EXTRA_ASIN, asin)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_OUTPUT_DIR, outputDirectory)
                putExtra(EXTRA_QUALITY, quality)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Pause a task
         */
        fun pauseTask(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE_TASK
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }

        /**
         * Resume a task
         */
        fun resumeTask(context: Context, dbPath: String, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME_TASK
                putExtra(EXTRA_DB_PATH, dbPath)
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }

        /**
         * Cancel a task
         */
        fun cancelTask(context: Context, dbPath: String, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_TASK
                putExtra(EXTRA_DB_PATH, dbPath)
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }
    }

    private lateinit var orchestrator: DownloadOrchestrator
    private lateinit var notificationManager: DownloadNotificationManager
    private lateinit var dbPath: String
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Track current download info for notifications
    private var currentDownload: DownloadInfo? = null

    data class DownloadInfo(
        val asin: String,
        val title: String,
        val author: String? = null,
        val totalBytes: Long = 0
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Get database path from intent or use default
        val cacheDir = applicationContext.cacheDir
        dbPath = File(cacheDir, "audible.db").absolutePath

        orchestrator = DownloadOrchestrator(applicationContext, dbPath)
        notificationManager = DownloadNotificationManager(applicationContext)

        // Set up orchestrator callbacks
        orchestrator.setProgressCallback { asin, stage, percentage, bytesDownloaded, totalBytes ->
            currentDownload?.let { download ->
                val progress = DownloadNotificationManager.DownloadProgress(
                    asin = download.asin,
                    title = download.title,
                    author = download.author,
                    stage = stage,
                    percentage = percentage.toInt(),
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes
                )
                notificationManager.showProgress(progress)
            }
        }

        orchestrator.setCompletionCallback { asin, title, outputPath ->
            currentDownload?.let { download ->
                notificationManager.showCompletion(download.title, download.author, outputPath)
            }
            currentDownload = null
        }

        orchestrator.setErrorCallback { asin, title, error ->
            currentDownload?.let { download ->
                notificationManager.showError(download.title, download.author, error)
            }
            currentDownload = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        // Start foreground immediately
        val initialNotification = notificationManager.getInitialNotification()
        Log.d(TAG, "Starting foreground service with notification")
        startForeground(NOTIFICATION_ID, initialNotification)

        when (intent?.action) {
            ACTION_ENQUEUE_DOWNLOAD -> handleEnqueueDownload(intent)
            ACTION_PAUSE_TASK -> handlePauseTask(intent)
            ACTION_RESUME_TASK -> handleResumeTask(intent)
            ACTION_CANCEL_TASK -> handleCancelTask(intent)
            ACTION_SET_WIFI_ONLY -> handleSetWifiOnly(intent)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        orchestrator.shutdown()
        serviceScope.cancel()
    }

    // ========================================================================
    // Intent Handlers
    // ========================================================================

    private fun handleEnqueueDownload(intent: Intent) {
        val accountJson = intent.getStringExtra(EXTRA_ACCOUNT_JSON) ?: return
        val asin = intent.getStringExtra(EXTRA_ASIN) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: return
        val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "High"

        Log.d(TAG, "Enqueueing download via orchestrator: $asin - $title")

        // Store current download info for notifications
        currentDownload = DownloadInfo(
            asin = asin,
            title = title,
            author = null, // TODO: Pass author from intent
            totalBytes = 0
        )

        // Use service scope to call suspend function
        serviceScope.launch {
            try {
                orchestrator.enqueueBook(accountJson, asin, title, outputDir, quality)
                Log.d(TAG, "Book enqueued successfully: $asin")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue book", e)
                currentDownload?.let { download ->
                    notificationManager.showError(download.title, download.author, e.message ?: "Unknown error")
                }
                currentDownload = null
            }
        }
    }

    private fun handlePauseTask(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        Log.d(TAG, "Pausing download: $taskId")

        try {
            val pauseParams = JSONObject().apply {
                put("db_path", dbPath)
                put("task_id", taskId)
            }
            ExpoRustBridgeModule.nativePauseDownload(pauseParams.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause download", e)
        }
    }

    private fun handleResumeTask(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        Log.d(TAG, "Resuming download: $taskId")

        try {
            val resumeParams = JSONObject().apply {
                put("db_path", dbPath)
                put("task_id", taskId)
            }
            ExpoRustBridgeModule.nativeResumeDownload(resumeParams.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume download", e)
        }
    }

    private fun handleCancelTask(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        Log.d(TAG, "Cancelling download: $taskId")

        try {
            val cancelParams = JSONObject().apply {
                put("db_path", dbPath)
                put("task_id", taskId)
            }
            ExpoRustBridgeModule.nativeCancelDownload(cancelParams.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel download", e)
        }
    }

    private fun handleSetWifiOnly(intent: Intent) {
        val wifiOnly = intent.getBooleanExtra(EXTRA_WIFI_ONLY, false)
        Log.d(TAG, "Setting WiFi-only mode: $wifiOnly")
        orchestrator.setWifiOnlyMode(wifiOnly)
    }
}
