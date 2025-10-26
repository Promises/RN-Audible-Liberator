package expo.modules.rustbridge.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that starts BackgroundTaskService on device boot
 *
 * This ensures the background task system is always running, even after:
 * - Device reboots
 * - App updates
 * - System kills the app
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Boot receiver triggered: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "Starting BackgroundTaskService on boot/update")

                try {
                    // Start the background service
                    BackgroundTaskService.start(context)
                    Log.d(TAG, "BackgroundTaskService started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start BackgroundTaskService on boot", e)
                }
            }
        }
    }
}
