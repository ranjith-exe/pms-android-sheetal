package `in`.hashday.pms.sheetal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val UPDATE_METADATA_URL = "https://hashday.in/sheetal/version.json"
    private const val BASE_URL = "https://hashday.in/sheetal/"
    private const val APK_FILE_NAME = "sheetalpms.apk"

    @Volatile
    private var isCheckInProgress = false

    suspend fun checkForUpdates(context: Context, onStatusMessage: (String) -> Unit = {}) {
        if (isCheckInProgress) {
            Log.d(TAG, "Update check already in progress, skipping duplicate call.")
            return
        }

        isCheckInProgress = true
        try {
            val currentVersionName = getCurrentVersionName(context)
            if (currentVersionName == null) {
                Log.e(TAG, "Could not determine current app version name")
                return
            }

            Log.d(TAG, "Current version: $currentVersionName. Checking update at $UPDATE_METADATA_URL")

            val jsonContent = fetchMetadataJson(UPDATE_METADATA_URL) ?: return
            val jsonObject = JSONObject(jsonContent)

            val latestVersion = parseVersion(jsonObject)
            val rawApkUrl = parseApkUrl(jsonObject)

            if (latestVersion.isBlank() || rawApkUrl.isBlank()) {
                Log.e(TAG, "Invalid metadata format in version.json: $jsonContent")
                return
            }

            val fullApkUrl = resolveApkUrl(rawApkUrl)
            Log.d(TAG, "Latest version: $latestVersion, APK URL: $fullApkUrl")

            if (isNewerVersion(currentVersionName, latestVersion)) {
                withContext(Dispatchers.Main) {
                    onStatusMessage("New update available ($latestVersion). Downloading...")
                    Toast.makeText(context, "New update found ($latestVersion). Downloading...", Toast.LENGTH_SHORT).show()
                }

                val downloadedFile = downloadApk(context, fullApkUrl)
                if (downloadedFile != null && downloadedFile.exists()) {
                    withContext(Dispatchers.Main) {
                        onStatusMessage("Download complete. Installing update...")
                        installApk(context, downloadedFile)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "APK download failed.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Log.d(TAG, "App is up to date ($currentVersionName)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
        } finally {
            isCheckInProgress = false
        }
    }

    private fun getCurrentVersionName(context: Context): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (e: Exception) {
            Log.e(TAG, "Package info error", e)
            null
        }
    }

    private suspend fun fetchMetadataJson(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    Log.e(TAG, "Metadata request failed with HTTP ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch metadata from $url", e)
            null
        }
    }

    private fun parseVersion(jsonObject: JSONObject): String {
        return when {
            jsonObject.has("version") -> jsonObject.optString("version")
            jsonObject.has("latestVersion") -> jsonObject.optString("latestVersion")
            jsonObject.has("versionName") -> jsonObject.optString("versionName")
            jsonObject.has("version_name") -> jsonObject.optString("version_name")
            jsonObject.has("versionCode") -> jsonObject.optString("versionCode")
            else -> ""
        }
    }

    private fun parseApkUrl(jsonObject: JSONObject): String {
        return when {
            jsonObject.has("apkPath") -> jsonObject.optString("apkPath")
            jsonObject.has("apkUrl") -> jsonObject.optString("apkUrl")
            jsonObject.has("url") -> jsonObject.optString("url")
            jsonObject.has("downloadUrl") -> jsonObject.optString("downloadUrl")
            jsonObject.has("apk_url") -> jsonObject.optString("apk_url")
            jsonObject.has("download_url") -> jsonObject.optString("download_url")
            else -> ""
        }
    }

    private fun resolveApkUrl(rawUrl: String): String {
        return if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            try {
                URL(URL(BASE_URL), rawUrl).toString()
            } catch (e: Exception) {
                BASE_URL + rawUrl.removePrefix("/")
            }
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        if (current == latest) return false

        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLength) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (c > l) return false
        }
        return false
    }

    private suspend fun downloadApk(context: Context, apkUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "APK download request failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body ?: return@withContext null
                val apkFile = File(context.getExternalFilesDir(null) ?: context.filesDir, APK_FILE_NAME)

                body.byteStream().use { inputStream: InputStream ->
                    FileOutputStream(apkFile).use { outputStream: OutputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                apkFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK from $apkUrl", e)
            null
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "Permission required to install updates.", Toast.LENGTH_LONG).show()
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permissionIntent)
                    return
                }
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching APK installation", e)
            Toast.makeText(context, "Failed to install update: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
