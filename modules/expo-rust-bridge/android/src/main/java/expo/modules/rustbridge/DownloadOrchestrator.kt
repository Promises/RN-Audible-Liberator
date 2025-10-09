package expo.modules.rustbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import androidx.documentfile.provider.DocumentFile
import android.net.Uri

/**
 * Download Orchestrator - Manages the complete download → conversion pipeline
 *
 * Responsibilities:
 * - Manages download queue via Rust PersistentDownloadManager
 * - Monitors download completion and triggers conversions
 * - Manages WiFi-only mode (pauses downloads when WiFi lost)
 * - Coordinates with ConversionManager for decryption
 * - Handles final file copying to user's SAF directory
 * - Provides progress callbacks to UI
 */
class DownloadOrchestrator(
    private val context: Context,
    private val dbPath: String
) {
    companion object {
        private const val TAG = "DownloadOrchestrator"
        private const val PREFS_NAME = "download_orchestrator_prefs"
        private const val PREF_WIFI_ONLY = "wifi_only_mode"
    }

    private val conversionManager = ConversionManager(context)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Network monitoring
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isWifiAvailable = false

    // Active download monitoring jobs
    private val monitoringJobs = mutableMapOf<String, Job>()

    // Callbacks
    private var progressCallback: ((String, String, Double) -> Unit)? = null // (asin, status, percentage)
    private var completionCallback: ((String, String, String) -> Unit)? = null // (asin, title, outputPath)
    private var errorCallback: ((String, String, String) -> Unit)? = null // (asin, title, error)

    init {
        setupNetworkMonitoring()
        setupConversionCallbacks()
        resumePendingTasks()
    }

    /**
     * Get WiFi-only mode setting
     */
    fun isWifiOnlyMode(): Boolean {
        return prefs.getBoolean(PREF_WIFI_ONLY, false)
    }

    /**
     * Set WiFi-only mode
     */
    fun setWifiOnlyMode(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_WIFI_ONLY, enabled).apply()
        Log.d(TAG, "WiFi-only mode: $enabled")

        scope.launch {
            if (enabled && !isWifiAvailable) {
                // Pause all active downloads
                pauseAllActiveDownloads()
            } else if (!enabled || isWifiAvailable) {
                // Resume paused downloads
                resumeAllPausedDownloads()
            }
        }
    }

    /**
     * Enqueue a book for download and conversion
     */
    suspend fun enqueueBook(
        accountJson: String,
        asin: String,
        title: String,
        outputDirectory: String,
        quality: String = "High"
    ): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Enqueueing book: $asin - $title")

        try {
            // Step 1: Get download license
            val licenseParams = JSONObject().apply {
                put("accountJson", accountJson)
                put("asin", asin)
                put("quality", quality)
            }

            val licenseResult = ExpoRustBridgeModule.nativeGetDownloadLicense(licenseParams.toString())
            val parsedLicense = parseJsonResponse(licenseResult)

            if (parsedLicense["success"] != true) {
                throw Exception("License request failed: ${parsedLicense["error"]}")
            }

            val licenseData = parsedLicense["data"] as? Map<*, *> ?: throw Exception("No license data")
            val downloadUrl = licenseData["download_url"] as? String ?: throw Exception("No download URL")
            val totalBytes = (licenseData["total_bytes"] as? Number)?.toLong() ?: 0L
            val aaxcKey = licenseData["aaxc_key"] as? String ?: throw Exception("No AAXC key")
            val aaxcIv = licenseData["aaxc_iv"] as? String ?: throw Exception("No AAXC IV")
            @Suppress("UNCHECKED_CAST")
            val requestHeaders = licenseData["request_headers"] as? Map<String, String>
                ?: mapOf("User-Agent" to "Audible/671 CFNetwork/1240.0.4 Darwin/20.6.0")

            Log.d(TAG, "License obtained. Size: ${totalBytes / 1024 / 1024} MB")

            // Step 2: Prepare paths
            val cacheDir = context.cacheDir
            val audiobooksDir = File(cacheDir, "audiobooks")
            audiobooksDir.mkdirs()

            val encryptedPath = File(audiobooksDir, "$asin.aax").absolutePath
            val decryptedCachePath = File(audiobooksDir, "$asin.m4b").absolutePath

            // Step 3: Enqueue download in Rust manager
            val enqueueParams = JSONObject().apply {
                put("db_path", dbPath)
                put("asin", asin)
                put("title", title)
                put("download_url", downloadUrl)
                put("total_bytes", totalBytes)
                put("download_path", encryptedPath)
                put("output_path", decryptedCachePath)
                put("request_headers", JSONObject(requestHeaders))
            }

            val enqueueResult = ExpoRustBridgeModule.nativeEnqueueDownload(enqueueParams.toString())
            val parsedEnqueue = parseJsonResponse(enqueueResult)

            if (parsedEnqueue["success"] != true) {
                throw Exception("Failed to enqueue: ${parsedEnqueue["error"]}")
            }

            val enqueueData = parsedEnqueue["data"] as? Map<*, *>
            val taskId = enqueueData?.get("task_id") as? String ?: throw Exception("No task ID")

            Log.d(TAG, "Download enqueued: $taskId")

            // Step 4: Start monitoring this download
            startMonitoringDownload(
                taskId = taskId,
                asin = asin,
                title = title,
                encryptedPath = encryptedPath,
                decryptedCachePath = decryptedCachePath,
                outputDirectory = outputDirectory,
                aaxcKey = aaxcKey,
                aaxcIv = aaxcIv
            )

            taskId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue book", e)
            errorCallback?.invoke(asin, title, e.message ?: "Unknown error")
            throw e
        }
    }

    /**
     * Start monitoring a download for completion
     */
    private fun startMonitoringDownload(
        taskId: String,
        asin: String,
        title: String,
        encryptedPath: String,
        decryptedCachePath: String,
        outputDirectory: String,
        aaxcKey: String,
        aaxcIv: String
    ) {
        // Cancel any existing monitoring for this ASIN
        monitoringJobs[asin]?.cancel()

        val job = scope.launch {
            try {
                while (isActive) {
                    delay(2000) // Poll every 2 seconds

                    // Check download status
                    val statusParams = JSONObject().apply {
                        put("db_path", dbPath)
                        put("task_id", taskId)
                    }

                    val statusResult = ExpoRustBridgeModule.nativeGetDownloadTask(statusParams.toString())
                    val parsedStatus = parseJsonResponse(statusResult)

                    if (parsedStatus["success"] == true) {
                        val taskData = parsedStatus["data"] as? Map<*, *>
                        val status = taskData?.get("status") as? String
                        val bytesDownloaded = (taskData?.get("bytes_downloaded") as? Number)?.toLong() ?: 0L
                        val totalBytes = (taskData?.get("total_bytes") as? Number)?.toLong() ?: 1L
                        val percentage = (bytesDownloaded.toDouble() / totalBytes) * 100.0

                        Log.d(TAG, "Download $asin: $status ($percentage%)")

                        // Notify progress
                        progressCallback?.invoke(asin, status ?: "unknown", percentage)

                        when (status) {
                            "completed" -> {
                                Log.d(TAG, "Download completed! Triggering conversion for $asin")

                                // Trigger conversion
                                triggerConversion(
                                    asin, title, encryptedPath, decryptedCachePath,
                                    outputDirectory, aaxcKey, aaxcIv
                                )

                                // Stop monitoring
                                break
                            }
                            "failed" -> {
                                val error = taskData?.get("error") as? String ?: "Unknown error"
                                Log.e(TAG, "Download failed for $asin: $error")
                                errorCallback?.invoke(asin, title, error)
                                break
                            }
                            "cancelled" -> {
                                Log.d(TAG, "Download cancelled for $asin")
                                break
                            }
                        }
                    } else {
                        Log.e(TAG, "Failed to check status: ${parsedStatus["error"]}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error monitoring download $asin", e)
            } finally {
                monitoringJobs.remove(asin)
            }
        }

        monitoringJobs[asin] = job
    }

    /**
     * Trigger conversion after download completes
     */
    private suspend fun triggerConversion(
        asin: String,
        title: String,
        encryptedPath: String,
        decryptedCachePath: String,
        outputDirectory: String,
        aaxcKey: String,
        aaxcIv: String
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting conversion for $asin...")

            // Decrypt using FFmpeg-Kit
            val command = buildList {
                add("-y")
                add("-audible_key")
                add(aaxcKey)
                add("-audible_iv")
                add(aaxcIv)
                add("-i")
                add(encryptedPath)
                add("-c")
                add("copy")
                add("-vn")
                add(decryptedCachePath)
            }.joinToString(" ")

            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                throw Exception("FFmpeg failed: ${session.failStackTrace}")
            }

            Log.d(TAG, "Conversion complete for $asin")

            // Copy to final destination
            copyToFinalDestination(asin, title, decryptedCachePath, outputDirectory)

            // Cleanup encrypted file
            File(encryptedPath).delete()

        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed for $asin", e)
            errorCallback?.invoke(asin, title, e.message ?: "Conversion failed")
        }
    }

    /**
     * Copy decrypted file to user's chosen directory
     */
    private suspend fun copyToFinalDestination(
        asin: String,
        title: String,
        decryptedCachePath: String,
        outputDirectory: String
    ) = withContext(Dispatchers.IO) {
        val cachedFile = File(decryptedCachePath)
        var finalPath = decryptedCachePath

        if (outputDirectory.startsWith("content://")) {
            // SAF URI - use DocumentFile
            val treeUri = Uri.parse(outputDirectory)
            val docDir = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw Exception("Invalid SAF URI")

            if (!docDir.canWrite()) {
                throw Exception("No write permission for SAF directory")
            }

            val fileName = "$asin.m4b"

            // Delete existing
            docDir.findFile(fileName)?.delete()

            // Create new
            val outputFile = docDir.createFile("audio/mp4", fileName)
                ?: docDir.createFile("audio/x-m4b", fileName)
                ?: docDir.createFile("audio/*", fileName)
                ?: throw Exception("Failed to create file in SAF directory")

            Log.d(TAG, "Copying to SAF: ${outputFile.uri}")

            // Copy
            context.contentResolver.openOutputStream(outputFile.uri)?.use { outputStream ->
                cachedFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw Exception("Failed to open output stream")

            finalPath = outputFile.uri.toString()

            // Delete cache file
            cachedFile.delete()
        }

        Log.d(TAG, "Complete! Final path: $finalPath")
        completionCallback?.invoke(asin, title, finalPath)
    }

    /**
     * Setup network monitoring for WiFi-only mode
     */
    private fun setupNetworkMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "WiFi available")
                    isWifiAvailable = true

                    if (isWifiOnlyMode()) {
                        // Resume paused downloads
                        scope.launch {
                            resumeAllPausedDownloads()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "WiFi lost")
                    isWifiAvailable = false

                    if (isWifiOnlyMode()) {
                        // Pause all active downloads
                        scope.launch {
                            pauseAllActiveDownloads()
                        }
                    }
                }
            }

            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)

            // Check initial WiFi state
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            isWifiAvailable = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * Setup conversion callbacks
     */
    private fun setupConversionCallbacks() {
        conversionManager.setProgressListener { task ->
            progressCallback?.invoke(task.asin, "converting", task.progress.percentage)
        }

        conversionManager.setCompletionListener { task ->
            Log.d(TAG, "Conversion completed for ${task.asin}")
            // completionCallback will be called from triggerConversion after file copy
        }
    }

    /**
     * Pause all active downloads
     */
    private suspend fun pauseAllActiveDownloads() = withContext(Dispatchers.IO) {
        try {
            val listParams = JSONObject().apply {
                put("db_path", dbPath)
                put("filter", "downloading")
            }

            val listResult = ExpoRustBridgeModule.nativeListDownloadTasks(listParams.toString())
            val parsed = parseJsonResponse(listResult)

            if (parsed["success"] == true) {
                val data = parsed["data"] as? Map<*, *>
                @Suppress("UNCHECKED_CAST")
                val tasks = data?.get("tasks") as? List<Map<*, *>> ?: emptyList()

                tasks.forEach { task ->
                    val taskId = task["task_id"] as? String ?: return@forEach

                    val pauseParams = JSONObject().apply {
                        put("db_path", dbPath)
                        put("task_id", taskId)
                    }

                    ExpoRustBridgeModule.nativePauseDownload(pauseParams.toString())
                    Log.d(TAG, "Paused download: $taskId (WiFi lost)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing downloads", e)
        }
    }

    /**
     * Resume all paused downloads
     */
    private suspend fun resumeAllPausedDownloads() = withContext(Dispatchers.IO) {
        try {
            val listParams = JSONObject().apply {
                put("db_path", dbPath)
                put("filter", "paused")
            }

            val listResult = ExpoRustBridgeModule.nativeListDownloadTasks(listParams.toString())
            val parsed = parseJsonResponse(listResult)

            if (parsed["success"] == true) {
                val data = parsed["data"] as? Map<*, *>
                @Suppress("UNCHECKED_CAST")
                val tasks = data?.get("tasks") as? List<Map<*, *>> ?: emptyList()

                tasks.forEach { task ->
                    val taskId = task["task_id"] as? String ?: return@forEach

                    val resumeParams = JSONObject().apply {
                        put("db_path", dbPath)
                        put("task_id", taskId)
                    }

                    ExpoRustBridgeModule.nativeResumeDownload(resumeParams.toString())
                    Log.d(TAG, "Resumed download: $taskId (WiFi available)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming downloads", e)
        }
    }

    /**
     * Resume pending tasks on app restart
     */
    private fun resumePendingTasks() {
        scope.launch {
            try {
                // List all incomplete downloads
                val listParams = JSONObject().apply {
                    put("db_path", dbPath)
                }

                val listResult = ExpoRustBridgeModule.nativeListDownloadTasks(listParams.toString())
                val parsed = parseJsonResponse(listResult)

                if (parsed["success"] == true) {
                    val data = parsed["data"] as? Map<*, *>
                    @Suppress("UNCHECKED_CAST")
                    val tasks = data?.get("tasks") as? List<Map<*, *>> ?: emptyList()

                    tasks.forEach { task ->
                        val status = task["status"] as? String
                        val asin = task["asin"] as? String ?: return@forEach
                        val taskId = task["task_id"] as? String ?: return@forEach

                        // Resume monitoring for incomplete downloads
                        if (status in listOf("queued", "downloading", "paused")) {
                            Log.d(TAG, "Resuming monitoring for $asin (status: $status)")
                            // Start monitoring (will need task details - simplified for now)
                            // TODO: Store task metadata in database or SharedPreferences
                        }
                    }
                }

                // Resume conversion manager tasks
                conversionManager.resumeAllPending()

            } catch (e: Exception) {
                Log.e(TAG, "Error resuming pending tasks", e)
            }
        }
    }

    /**
     * Set progress callback
     */
    fun setProgressCallback(callback: (String, String, Double) -> Unit) {
        this.progressCallback = callback
    }

    /**
     * Set completion callback
     */
    fun setCompletionCallback(callback: (String, String, String) -> Unit) {
        this.completionCallback = callback
    }

    /**
     * Set error callback
     */
    fun setErrorCallback(callback: (String, String, String) -> Unit) {
        this.errorCallback = callback
    }

    /**
     * Shutdown orchestrator
     */
    fun shutdown() {
        // Cancel network monitoring
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        }

        // Cancel all monitoring jobs
        monitoringJobs.values.forEach { it.cancel() }
        monitoringJobs.clear()

        // Shutdown managers
        conversionManager.shutdown()
        scope.cancel()
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private fun parseJsonResponse(jsonString: String): Map<String, Any?> {
        return try {
            val json = JSONObject(jsonString)
            val success = json.getBoolean("success")

            if (success) {
                val dataObj = json.getJSONObject("data")
                val dataMap = mutableMapOf<String, Any?>()

                dataObj.keys().forEach { key ->
                    val value = dataObj.get(key)
                    dataMap[key] = if (value == JSONObject.NULL) null else value
                }

                mapOf("success" to true, "data" to dataMap)
            } else {
                mapOf("success" to false, "error" to json.getString("error"))
            }
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "Parse error: ${e.message}")
        }
    }
}
