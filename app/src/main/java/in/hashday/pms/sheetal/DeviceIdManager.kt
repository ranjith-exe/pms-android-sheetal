package `in`.hashday.pms.sheetal

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object DeviceIdManager {

    private const val TAG = "DeviceIdManager"
    private const val PREFS_NAME = "device_identity_prefs"
    private const val KEY_DEVICE_ID = "unique_device_id"
    private const val BACKUP_FILE_NAME = ".sheetalpms_unique_device_id"

    @Synchronized
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var cachedId = prefs.getString(KEY_DEVICE_ID, null)
        if (!cachedId.isNullOrBlank()) {
            return cachedId
        }

        // Try reading from external storage backup file
        cachedId = readFromExternalStorageBackup()
        if (!cachedId.isNullOrBlank()) {
            prefs.edit().putString(KEY_DEVICE_ID, cachedId).apply()
            return cachedId
        }

        // Generate persistent ID based on hardware Android ID & signatures
        val newId = generateHardwareDeviceId(context)
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        saveToExternalStorageBackup(newId)

        return newId
    }

    private fun generateHardwareDeviceId(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
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

    private fun readFromExternalStorageBackup(): String? {
        return try {
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val file = File(docsDir, BACKUP_FILE_NAME)
            if (file.exists() && file.canRead()) {
                val content = file.readText().trim()
                if (content.startsWith("sheetal_")) {
                    return content
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not read backup device ID from external storage: ${e.message}")
            null
        }
    }

    private fun saveToExternalStorageBackup(deviceId: String) {
        try {
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (!docsDir.exists()) {
                docsDir.mkdirs()
            }
            val file = File(docsDir, BACKUP_FILE_NAME)
            file.writeText(deviceId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save backup device ID to external storage: ${e.message}")
        }
    }
}
