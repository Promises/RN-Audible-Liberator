package expo.modules.rustbridge.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Utility object for scheduling WorkManager background tasks
 *
 * This replaces the custom coroutine-based workers with proper WorkManager
 * periodic workers that integrate with Android's system-level job scheduling.
 *
 * Features:
 * - Token refresh: Periodic backup check (just-in-time refresh happens before each API call)
 * - Library sync: Periodic library synchronization with optional WiFi-only constraint
 * - Proper cancellation and rescheduling
 * - System-managed execution (survives reboots, respects battery optimization)
 */
object WorkerScheduler {
    private const val TAG = "WorkerScheduler"
    private const val TOKEN_REFRESH_WORK = "token_refresh_periodic"
    private const val LIBRARY_SYNC_WORK = "library_sync_periodic"

    /**
     * Schedule periodic token refresh worker
     *
     * @param context Application context
     * @param intervalHours How often to check for token expiry (default: 24 hours)
     *                      This is a backup check - just-in-time refresh happens before each API call
     *                      Note: WorkManager enforces minimum 15 minute interval
     *                      Use 0 for testing mode (15 minutes)
     */
    fun scheduleTokenRefresh(context: Context, intervalHours: Long = 24) {
        try {
            // WorkManager enforces minimum 15 minute interval
            // intervalHours = 0 means test mode (15 minutes)
            val interval = when {
                intervalHours == 0L -> 15L  // Test mode: 15 minutes
                intervalHours < 1 -> 15L    // Less than 1 hour: use minimum 15 minutes
                else -> intervalHours * 60  // Convert hours to minutes
            }
            val unit = TimeUnit.MINUTES

            val workRequest = PeriodicWorkRequestBuilder<TokenRefreshWorker>(
                interval,
                unit
            )
                .setInitialDelay(1, TimeUnit.MINUTES) // Run soon after app start
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                TOKEN_REFRESH_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            val displayInterval = if (intervalHours == 0L) "15 minutes (test mode)" else "${intervalHours}h"
            Log.d(TAG, "Token refresh worker scheduled (interval: $displayInterval)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule token refresh worker", e)
            throw e
        }
    }

    /**
     * Schedule periodic library sync worker
     *
     * @param context Application context
     * @param intervalHours How often to sync library (recommended: 24 hours)
     *                      Note: WorkManager enforces minimum 15 minute interval
     * @param wifiOnly If true, only sync on unmetered (WiFi) connections
     */
    fun scheduleLibrarySync(context: Context, intervalHours: Long, wifiOnly: Boolean) {
        try {
            // WorkManager enforces minimum 15 minute interval
            val interval = if (intervalHours < 1) 15L else intervalHours * 60
            val unit = if (intervalHours < 1) TimeUnit.MINUTES else TimeUnit.MINUTES

            // Set network constraints
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            val workRequest = PeriodicWorkRequestBuilder<LibrarySyncWorker>(
                interval,
                unit
            )
                .setConstraints(constraints)
                .setInitialDelay(5, TimeUnit.MINUTES) // Give time for token refresh to run first
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                LIBRARY_SYNC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            Log.d(TAG, "Library sync worker scheduled (interval: ${intervalHours}h, WiFi-only: $wifiOnly)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule library sync worker", e)
            throw e
        }
    }

    /**
     * Cancel token refresh worker
     */
    fun cancelTokenRefresh(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(TOKEN_REFRESH_WORK)
            Log.d(TAG, "Token refresh worker cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel token refresh worker", e)
            throw e
        }
    }

    /**
     * Cancel library sync worker
     */
    fun cancelLibrarySync(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(LIBRARY_SYNC_WORK)
            Log.d(TAG, "Library sync worker cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel library sync worker", e)
            throw e
        }
    }

    /**
     * Cancel all background workers
     */
    fun cancelAllWork(context: Context) {
        try {
            cancelTokenRefresh(context)
            cancelLibrarySync(context)
            Log.d(TAG, "All background workers cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel all workers", e)
            throw e
        }
    }

    /**
     * Get status of token refresh worker
     */
    fun getTokenRefreshStatus(context: Context): WorkInfo.State? {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(TOKEN_REFRESH_WORK)
            .get()
        return workInfos.firstOrNull()?.state
    }

    /**
     * Get status of library sync worker
     */
    fun getLibrarySyncStatus(context: Context): WorkInfo.State? {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(LIBRARY_SYNC_WORK)
            .get()
        return workInfos.firstOrNull()?.state
    }
}
