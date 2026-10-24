package `in`.hashday.pms.sheetal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

object DeviceIdManager {

    private const val TAG = "DeviceIdManager"
    private const val PREFS_NAME = "device_identity_prefs"
    private const val KEY_DEVICE_ID = "unique_device_id"

    /**
     * Retrieves a permanent, unique Device ID. 
     * Because this uses Settings.Secure.ANDROID_ID (which is stable per-app-signature per-device on Android 8+),
     * hashing this value guarantees that if the app is uninstalled, updated, or its data is cleared,
     * it will mathematically regenerate the exact same Device ID upon the next launch.
     */
    @Synchronized
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedId = prefs.getString(KEY_DEVICE_ID, null)
        if (!cachedId.isNullOrBlank()) {
            return cachedId
        }

        // Generate deterministic persistent ID
        val newId = generateHardwareDeviceId(context)
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()

        return newId
    }

    @SuppressLint("HardwareIds")
    private fun generateHardwareDeviceId(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching ANDROID_ID", e)
            ""
        }

        val hardwareSeed = "${androidId}_${Build.BOARD}_${Build.BRAND}_${Build.MODEL}_${Build.MANUFACTURER}"
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(hardwareSeed.toByteArray(Charsets.UTF_8))
            val hexString = hash.joinToString("") { "%02x".format(it) }
            "sheetal_${hexString.substring(0, 16)}"
        } catch (e: Exception) {
            val uuid = UUID.nameUUIDFromBytes(hardwareSeed.toByteArray(Charsets.UTF_8))
            "sheetal_${uuid.toString().replace("-", "").substring(0, 16)}"
        }
    }
}
