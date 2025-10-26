package expo.modules.rustbridge.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import expo.modules.rustbridge.ExpoRustBridgeModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Date

/**
 * WorkManager-based token refresh worker
 *
 * Features:
 * - Checks token expiry and refreshes if needed
 * - Runs periodically (recommended: 12 hours)
 * - Saves updated tokens to SQLite database
 * - Handles errors gracefully with retry logic
 */
class TokenRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TokenRefreshWorker"
        private const val REFRESH_THRESHOLD_MS = 1800_000L // Refresh if < 30 minutes remaining

        /**
         * Parse date from multiple possible formats
         */
        private fun parseDate(dateStr: String): Date? {
            // Try ISO 8601 format first (e.g., "2025-10-24T00:09:28.000Z")
            try {
                return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(dateStr)
            } catch (e: Exception) {
                // Ignore, try next format
            }

            // Try Java toString() format (e.g., "Fri Oct 24 00:09:28 GMT+02:00 2025")
            try {
                return java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.US).parse(dateStr)
            } catch (e: Exception) {
                // Ignore, try next format
            }

            // Try ISO 8601 without milliseconds (e.g., "2025-10-24T00:09:28Z")
            try {
                return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(dateStr)
            } catch (e: Exception) {
                // Ignore
            }

            return null
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Token refresh worker started")

            // Get database path
            val dbPath = applicationContext.cacheDir.absolutePath + "/audible.db"

            // Load account from SQLite database
            val getAccountParams = JSONObject().apply {
                put("db_path", dbPath)
            }
            val accountResultJson = ExpoRustBridgeModule.nativeGetPrimaryAccount(getAccountParams.toString())
            val accountResultObj = JSONObject(accountResultJson)

            if (!accountResultObj.getBoolean("success")) {
                Log.d(TAG, "No account found, skipping token refresh")
                return@withContext Result.success()
            }

            val accountJson = accountResultObj.getJSONObject("data").optString("account")
            if (accountJson.isNullOrEmpty() || accountJson == "null") {
                Log.d(TAG, "No account in database, skipping token refresh")
                return@withContext Result.success()
            }

            val account = JSONObject(accountJson)
            val identity = account.optJSONObject("identity")
            if (identity == null) {
                Log.d(TAG, "No identity in account, skipping")
                return@withContext Result.success()
            }

            // Debug: Log identity structure
            Log.d(TAG, "Identity keys: ${identity.keys().asSequence().toList()}")

            // Log current refresh token from database (for debugging)
            val currentRefreshToken = identity.optString("refresh_token", "")
            Log.d(TAG, "Loaded refresh_token from DB: ${if (currentRefreshToken.isEmpty()) "EMPTY/NULL" else currentRefreshToken.substring(0, minOf(20, currentRefreshToken.length)) + "..."}")

            // Get token expiry
            val accessTokenObj = identity.opt("access_token")
            val expiresAtStr = when (accessTokenObj) {
                is JSONObject -> accessTokenObj.optString("expires_at")
                else -> null
            }

            if (expiresAtStr == null) {
                Log.d(TAG, "No token expiry found, skipping refresh")
                return@withContext Result.success()
            }

            // Parse expiry date (supports multiple formats)
            val expiresAt = parseDate(expiresAtStr)
            if (expiresAt == null) {
                Log.e(TAG, "Invalid expiry date: $expiresAtStr")
                return@withContext Result.failure()
            }

            val now = Date()
            val timeRemaining = expiresAt.time - now.time

            Log.d(TAG, "Token expires at: $expiresAt (${timeRemaining / 1000 / 60} minutes remaining)")

            // Refresh if already expired OR if less than 30 minutes remaining
            val shouldRefresh = timeRemaining <= 0 || timeRemaining < REFRESH_THRESHOLD_MS

            if (!shouldRefresh) {
                val hoursRemaining = timeRemaining / 1000 / 60 / 60
                Log.d(TAG, "Token still valid for $hoursRemaining hours, no refresh needed")
                return@withContext Result.success()
            }

            if (timeRemaining <= 0) {
                Log.d(TAG, "Token EXPIRED! Triggering immediate refresh")
            } else {
                Log.d(TAG, "Token expiring soon (< 30 minutes), triggering refresh")
            }

            // Get refresh credentials
            val countryCode = account.getJSONObject("locale").getString("country_code")
            val refreshToken = identity.getString("refresh_token")
            val deviceSerial = identity.getString("device_serial_number")

            // Log refresh token for debugging (first 20 chars only)
            val tokenPreview = if (refreshToken.length > 20) refreshToken.substring(0, 20) + "..." else refreshToken
            Log.d(TAG, "Using refresh_token: $tokenPreview")

            // Call Rust to refresh token
            Log.d(TAG, "Calling Rust to refresh token")
            val refreshParams = JSONObject().apply {
                put("locale_code", countryCode)
                put("refresh_token", refreshToken)
                put("device_serial", deviceSerial)
            }
            val refreshResultJson = ExpoRustBridgeModule.nativeRefreshAccessToken(refreshParams.toString())
            val refreshResultObj = JSONObject(refreshResultJson)

            if (!refreshResultObj.getBoolean("success")) {
                val error = refreshResultObj.optString("error", "Failed to refresh token")
                Log.e(TAG, "Token refresh failed: $error")
                return@withContext Result.retry()
            }

            // Extract new tokens
            val dataObj = refreshResultObj.getJSONObject("data")
            val newAccessToken = dataObj.getString("access_token")
            val expiresIn = dataObj.optString("expires_in", "3600").toLongOrNull() ?: 3600L

            // Only update refresh token if Amazon actually returned a new one
            // Amazon often doesn't return a new refresh_token - keep the old one in that case
            val newRefreshToken = if (dataObj.has("refresh_token") && !dataObj.isNull("refresh_token")) {
                val token = dataObj.getString("refresh_token")
                if (token.isNotEmpty()) {
                    Log.d(TAG, "Amazon returned new refresh_token: ${token.substring(0, minOf(20, token.length))}...")
                    token
                } else {
                    Log.d(TAG, "Amazon returned empty refresh_token, keeping old one")
                    refreshToken
                }
            } else {
                Log.d(TAG, "Amazon didn't return refresh_token, keeping old one")
                refreshToken
            }

            val newExpiry = Date(System.currentTimeMillis() + (expiresIn * 1000))

            // Update account with new token
            identity.put("access_token", JSONObject().apply {
                put("token", newAccessToken)
                put("expires_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(newExpiry))
            })
            identity.put("refresh_token", newRefreshToken)

            // Save updated account to database
            val saveParams = JSONObject().apply {
                put("db_path", dbPath)
                put("account_json", account.toString())
            }
            val saveResult = ExpoRustBridgeModule.nativeSaveAccount(saveParams.toString())
            val saveResultObj = JSONObject(saveResult)

            if (!saveResultObj.getBoolean("success")) {
                val error = saveResultObj.optString("error", "Failed to save account")
                Log.e(TAG, "Failed to save updated account: $error")
                return@withContext Result.retry()
            }

            Log.d(TAG, "Token refresh complete. New expiry: $newExpiry")
            return@withContext Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Token refresh worker failed", e)
            return@withContext Result.retry()
        }
    }
}
