package expo.modules.rustbridge.tasks

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Unified foreground service for all background tasks
 *
 * Responsibilities:
 * - Keeps app alive for background work
 * - Manages BackgroundTaskManager lifecycle
 * - Shows unified notification for all tasks
 * - Monitors network connectivity
 * - Handles service commands (start download, sync, etc.)
 */
class BackgroundTaskService : Service() {
    companion object {
        private const val TAG = "BackgroundTaskService"

        // Actions
        const val ACTION_START_SERVICE = "expo.modules.rustbridge.START_SERVICE"
        const val ACTION_ENQUEUE_DOWNLOAD = "expo.modules.rustbridge.ENQUEUE_DOWNLOAD"
        const val ACTION_START_SYNC = "expo.modules.rustbridge.START_SYNC"
        const val ACTION_ENABLE_AUTO_DOWNLOAD = "expo.modules.rustbridge.ENABLE_AUTO_DOWNLOAD"
        const val ACTION_DISABLE_AUTO_DOWNLOAD = "expo.modules.rustbridge.DISABLE_AUTO_DOWNLOAD"
        const val ACTION_PAUSE_TASK = "expo.modules.rustbridge.PAUSE_TASK"
        const val ACTION_RESUME_TASK = "expo.modules.rustbridge.RESUME_TASK"
        const val ACTION_CANCEL_TASK = "expo.modules.rustbridge.CANCEL_TASK"
        const val ACTION_STOP_SERVICE = "expo.modules.rustbridge.STOP_SERVICE"

        // Extras
        const val EXTRA_ASIN = "asin"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_ACCOUNT_JSON = "account_json"
        const val EXTRA_OUTPUT_DIR = "output_dir"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_FULL_SYNC = "full_sync"

        /**
         * Start the background service
         */
        fun start(context: Context) {
            val intent = Intent(context, BackgroundTaskService::class.java).apply {
                action = ACTION_START_SERVICE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Enqueue a download
         */
        fun enqueueDownload(
            context: Context,
            asin: String,
            title: String,
            author: String?,
            accountJson: String,
            outputDirectory: String,
            quality: String = "High"
        ) {
            val intent = Intent(context, BackgroundTaskService::class.java).apply {
                action = ACTION_ENQUEUE_DOWNLOAD
                putExtra(EXTRA_ASIN, asin)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_AUTHOR, author)
                putExtra(EXTRA_ACCOUNT_JSON, accountJson)
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
         * Start library sync
         */
        fun startLibrarySync(context: Context, fullSync: Boolean = false) {
            val intent = Intent(context, BackgroundTaskService::class.java).apply {
                action = ACTION_START_SYNC
                putExtra(EXTRA_FULL_SYNC, fullSync)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the background service (disables all automatic features)
         */
        fun stopService(context: Context) {
            val intent = Intent(context, BackgroundTaskService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var taskManager: BackgroundTaskManager
    private lateinit var notificationManager: BackgroundNotificationManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Network monitoring
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Notification update job
    private var notificationUpdateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Get task manager instance
        taskManager = BackgroundTaskManager.getInstance(applicationContext)
        notificationManager = BackgroundNotificationManager(applicationContext)

        // Start foreground immediately
        val initialNotification = notificationManager.getInitialNotification()
        startForeground(BackgroundNotificationManager.NOTIFICATION_ID, initialNotification)

        // Start task manager
        taskManager.start()

        // Setup network monitoring
        setupNetworkMonitoring()

        // Start notification updates
        startNotificationUpdates()

        Log.d(TAG, "Service started successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                // Service already started in onCreate
                Log.d(TAG, "Service start requested")
            }
            ACTION_ENQUEUE_DOWNLOAD -> handleEnqueueDownload(intent)
            ACTION_START_SYNC -> handleStartSync(intent)
            ACTION_ENABLE_AUTO_DOWNLOAD -> handleEnableAutoDownload()
            ACTION_DISABLE_AUTO_DOWNLOAD -> handleDisableAutoDownload()
            ACTION_PAUSE_TASK -> handlePauseTask(intent)
            ACTION_RESUME_TASK -> handleResumeTask(intent)
            ACTION_CANCEL_TASK -> handleCancelTask(intent)
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stop service requested - shutting down")
                stopSelf()
                return START_NOT_STICKY  // Don't restart
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        // Cleanup network monitoring
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        }

        // Stop notification updates
        notificationUpdateJob?.cancel()

        // Stop task manager (pauses monitoring loops)
        taskManager.stop()

        // Cancel scope
        serviceScope.cancel()

        Log.d(TAG, "Service fully stopped - all background work paused")
    }

    // ========================================================================
    // Command Handlers
    // ========================================================================

    private fun handleEnqueueDownload(intent: Intent) {
        val asin = intent.getStringExtra(EXTRA_ASIN) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val author = intent.getStringExtra(EXTRA_AUTHOR)
        val accountJson = intent.getStringExtra(EXTRA_ACCOUNT_JSON) ?: return
        val outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: return
        val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "High"

        Log.d(TAG, "Enqueueing download: $asin - $title")

        try {
            taskManager.enqueueDownload(
                asin = asin,
                title = title,
                author = author,
                accountJson = accountJson,
                outputDirectory = outputDir,
                quality = quality
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download", e)
        }
    }

    private fun handleStartSync(intent: Intent) {
        val fullSync = intent.getBooleanExtra(EXTRA_FULL_SYNC, false)

        Log.d(TAG, "Starting library sync (full=$fullSync)")

        try {
            taskManager.startLibrarySync(fullSync)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sync", e)
        }
    }

    private fun handleEnableAutoDownload() {
        Log.d(TAG, "Enabling auto-download")
        taskManager.enableAutoDownload()
    }

    private fun handleDisableAutoDownload() {
        Log.d(TAG, "Disabling auto-download")
        taskManager.disableAutoDownload()
    }

    private fun handlePauseTask(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return

        Log.d(TAG, "Pausing task: $taskId")

        serviceScope.launch {
            try {
                taskManager.pauseTask(taskId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause task", e)
            }
        }
    }

    private fun handleResumeTask(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return

        Log.d(TAG, "Resuming task: $taskId")

        serviceScope.launch {
            try {
                taskManager.resumeTask(taskId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume task", e)
            }
        }
    }

    private fun handleCancelTask(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return

        Log.d(TAG, "Cancelling task: $taskId")

        serviceScope.launch {
            try {
                taskManager.cancelTask(taskId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel task", e)
            }
        }
    }

    // ========================================================================
    // Network Monitoring
    // ========================================================================

    private fun setupNetworkMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "WiFi available")
                    taskManager.setWifiAvailable(true)
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "WiFi lost")
                    taskManager.setWifiAvailable(false)
                }
            }

            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)

            // Check initial WiFi state
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val isWifiAvailable = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            taskManager.setWifiAvailable(isWifiAvailable)
            Log.d(TAG, "Initial WiFi state: $isWifiAvailable")
        }
    }

    // ========================================================================
    // Notification Updates
    // ========================================================================

    private fun startNotificationUpdates() {
        notificationUpdateJob = serviceScope.launch {
            // Give tasks time to start before first check (avoid race condition)
            // Event loop processes queue every 1 second, so wait 2 seconds to be safe
            delay(2000)

            // Update notification every 2 seconds based on active tasks
            while (isActive) {
                try {
                    val activeTasks = taskManager.getActiveTasks()
                    notificationManager.show(activeTasks)

                    // Check if we should stop the service
                    if (activeTasks.isEmpty()) {
                        Log.d(TAG, "No active tasks - stopping service")
                        stopForeground(true) // Remove notification
                        stopSelf()
                        break
                    } else {
                        Log.d(TAG, "${activeTasks.size} active tasks")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating notification", e)
                }

                delay(2000)
            }
        }
    }
}
