package `in`.hashday.pms.sheetal

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object UpdateManager {

    private const val TAG = "UpdateManager"

    // Primary HTTPS and fallback HTTP local intranet endpoints for firewall environment
    private const val UPDATE_METADATA_HTTPS_URL = "https://192.168.6.1/app-update.json"
    private const val UPDATE_METADATA_HTTP_URL = "http://192.168.6.1/app-update.json"
    private const val APK_FILE_NAME = "sheetalpms.apk"

    @Volatile
    private var isCheckInProgress = false

    sealed class FetchResult<out T> {
        data class Success<out T>(val data: T) : FetchResult<T>()
        data class Error(val message: String) : FetchResult<Nothing>()
    }

    suspend fun checkForUpdates(
        context: Context,
        onStatusMessage: (String) -> Unit = {},
        isManualCheck: Boolean = false
    ) {
        if (isCheckInProgress) {
            Log.d(TAG, "Update check already in progress, skipping duplicate call.")
            return
        }

        isCheckInProgress = true
        try {
            val currentVersionName = getCurrentVersionName(context) ?: "1.0.0"
            val currentVersionCode = getCurrentVersionCode(context)

            Log.d(
                TAG,
                "Current version: $currentVersionName (code: $currentVersionCode). Checking updates on local server 192.168.6.1..."
            )

            // Try HTTPS first, fallback to HTTP for intranet server
            var fetchResult = fetchMetadataJson(UPDATE_METADATA_HTTPS_URL)
            if (fetchResult is FetchResult.Error) {
                Log.w(TAG, "HTTPS update check failed (${fetchResult.message}). Trying HTTP fallback...")
                fetchResult = fetchMetadataJson(UPDATE_METADATA_HTTP_URL)
            }

            when (fetchResult) {
                is FetchResult.Error -> {
                    val errorMsg = "Update check failed: ${fetchResult.message}"
                    Log.e(TAG, errorMsg)
                    showToast(context, errorMsg, Toast.LENGTH_LONG)
                    return
                }

                is FetchResult.Success -> {
                    val jsonContent = fetchResult.data
                    val jsonObject = try {
                        JSONObject(jsonContent)
                    } catch (e: Exception) {
                        val errorMsg = "Update check failed: Invalid JSON response in app-update.json"
                        Log.e(TAG, errorMsg, e)
                        showToast(context, errorMsg, Toast.LENGTH_LONG)
                        return
                    }

                    val latestVersionCode = parseVersionCode(jsonObject)
                    val latestVersionName = parseVersionName(jsonObject)
                    val rawApkUrl = parseApkUrl(jsonObject)

                    if (latestVersionName.isBlank() || rawApkUrl.isBlank()) {
                        val errorMsg = "Update check failed: Missing versionName or apkUrl in app-update.json"
                        Log.e(TAG, "$errorMsg. Received: $jsonContent")
                        showToast(context, errorMsg, Toast.LENGTH_LONG)
                        return
                    }

                    val fullApkUrl = resolveApkUrl(rawApkUrl)
                    Log.d(
                        TAG,
                        "Latest version: $latestVersionName (code: $latestVersionCode), APK URL: $fullApkUrl"
                    )

                    if (isNewerVersion(currentVersionCode, latestVersionCode, currentVersionName, latestVersionName)) {
                        withContext(Dispatchers.Main) {
                            onStatusMessage("New update available ($latestVersionName). Downloading...")
                            showToast(
                                context,
                                "New update found ($latestVersionName). Downloading...",
                                Toast.LENGTH_SHORT
                            )
                        }

                        var downloadResult = downloadApk(context, fullApkUrl)
                        if (downloadResult is FetchResult.Error && fullApkUrl.startsWith("https://")) {
                            val httpFallbackUrl = fullApkUrl.replaceFirst("https://", "http://")
                            Log.w(TAG, "HTTPS APK download failed. Retrying HTTP fallback: $httpFallbackUrl")
                            downloadResult = downloadApk(context, httpFallbackUrl)
                        }

                        when (downloadResult) {
                            is FetchResult.Success -> {
                                val downloadedFile = downloadResult.data
                                withContext(Dispatchers.Main) {
                                    onStatusMessage("Download complete. Installing update...")
                                    showToast(context, "Download complete. Starting installation...", Toast.LENGTH_SHORT)
                                    installApk(context, downloadedFile)
                                }
                            }

                            is FetchResult.Error -> {
                                val errorMsg = "APK download failed: ${downloadResult.message}"
                                Log.e(TAG, errorMsg)
                                withContext(Dispatchers.Main) {
                                    showToast(context, errorMsg, Toast.LENGTH_LONG)
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "App is up to date ($currentVersionName)")
                        if (isManualCheck) {
                            showToast(context, "App is already up to date ($currentVersionName).", Toast.LENGTH_SHORT)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            val errorMsg = "Update error: ${e.localizedMessage ?: e.message ?: "Unknown error"}"
            Log.e(TAG, "Error checking for updates", e)
            showToast(context, errorMsg, Toast.LENGTH_LONG)
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

    private fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private suspend fun fetchMetadataJson(url: String): FetchResult<String> = withContext(Dispatchers.IO) {
        try {
            val client = createUnsafeOkHttpClient()
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrBlank()) {
                        FetchResult.Success(bodyString)
                    } else {
                        FetchResult.Error("Empty response body from app-update.json")
                    }
                } else {
                    FetchResult.Error("HTTP ${response.code} ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch metadata from $url", e)
            FetchResult.Error(e.localizedMessage ?: e.message ?: "Server connection error")
        }
    }

    private fun parseVersionCode(jsonObject: JSONObject): Long {
        return when {
            jsonObject.has("versionCode") -> jsonObject.optLong("versionCode", -1L)
            jsonObject.has("version_code") -> jsonObject.optLong("version_code", -1L)
            else -> -1L
        }
    }

    private fun parseVersionName(jsonObject: JSONObject): String {
        return when {
            jsonObject.has("versionName") -> jsonObject.optString("versionName")
            jsonObject.has("version") -> jsonObject.optString("version")
            jsonObject.has("latestVersion") -> jsonObject.optString("latestVersion")
            jsonObject.has("version_name") -> jsonObject.optString("version_name")
            else -> ""
        }
    }

    private fun parseApkUrl(jsonObject: JSONObject): String {
        return when {
            jsonObject.has("apkUrl") -> jsonObject.optString("apkUrl")
            jsonObject.has("apkPath") -> jsonObject.optString("apkPath")
            jsonObject.has("downloadUrl") -> jsonObject.optString("downloadUrl")
            jsonObject.has("url") -> jsonObject.optString("url")
            jsonObject.has("apk_url") -> jsonObject.optString("apk_url")
            jsonObject.has("download_url") -> jsonObject.optString("download_url")
            else -> ""
        }
    }

    private fun resolveApkUrl(rawUrl: String): String {
        return if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else if (rawUrl.startsWith("/")) {
            "http://192.168.6.1$rawUrl"
        } else {
            "http://192.168.6.1/$rawUrl"
        }
    }

    private fun isNewerVersion(
        currentCode: Long,
        latestCode: Long,
        currentName: String,
        latestName: String
    ): Boolean {
        if (latestCode > 0 && currentCode > 0) {
            return latestCode > currentCode
        }
        return compareVersionNames(currentName, latestName) < 0
    }

    private fun compareVersionNames(v1: String, v2: String): Int {
        if (v1 == v2) return 0
        val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
        val maxLength = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLength) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 < p2) return -1
            if (p1 > p2) return 1
        }
        return 0
    }

    private suspend fun downloadApk(context: Context, apkUrl: String): FetchResult<File> = withContext(Dispatchers.IO) {
        try {
            val client = createUnsafeOkHttpClient()
            val request = Request.Builder().url(apkUrl).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext FetchResult.Error("HTTP ${response.code} ${response.message}")
                }

                val body = response.body ?: return@withContext FetchResult.Error("Server returned empty body")
                val targetDir = context.getExternalFilesDir(null) ?: context.filesDir
                val apkFile = File(targetDir, APK_FILE_NAME)

                if (apkFile.exists()) {
                    apkFile.delete()
                }

                body.byteStream().use { inputStream: InputStream ->
                    FileOutputStream(apkFile).use { outputStream: OutputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                if (apkFile.exists() && apkFile.length() > 0) {
                    FetchResult.Success(apkFile)
                } else {
                    FetchResult.Error("Downloaded APK file is empty or corrupted")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK from $apkUrl", e)
            FetchResult.Error(e.localizedMessage ?: e.message ?: "Failed to download update file")
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    showToast(
                        context,
                        "Permission required to install updates. Please enable 'Allow from this source'.",
                        Toast.LENGTH_LONG
                    )
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permissionIntent)
                    return
                }
            }

            if (!apkFile.exists() || apkFile.length() <= 0) {
                showToast(context, "Installation failed: Downloaded APK file is missing or empty.", Toast.LENGTH_LONG)
                return
            }

            try {
                installViaPackageInstaller(context, apkFile)
            } catch (e: Exception) {
                Log.w(TAG, "PackageInstaller session failed, attempting fallback to ACTION_VIEW: ${e.message}", e)
                installViaActionView(context, apkFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching APK installation", e)
            showToast(
                context,
                "Failed to start update installation: ${e.localizedMessage ?: e.message}",
                Toast.LENGTH_LONG
            )
        }
    }

    private fun installViaPackageInstaller(context: Context, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apkFile.length())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set USER_ACTION_NOT_REQUIRED: ${e.message}")
            }
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.openWrite("update_pkg", 0, apkFile.length()).use { outputStream ->
            apkFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
            session.fsync(outputStream)
        }

        val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
            action = UpdateInstallReceiver.ACTION_INSTALL_COMPLETE
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            flags
        )

        session.commit(pendingIntent.intentSender)
        session.close()
        Log.d(TAG, "PackageInstaller installation session $sessionId committed.")
    }

    private fun installViaActionView(context: Context, apkFile: File) {
        try {
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
            Log.e(TAG, "Fallback ACTION_VIEW installation failed", e)
            showToast(context, "Failed to start installation UI: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG)
        }
    }

    @SuppressLint("TrustAllX509TrustManager", "BadHostnameVerifier")
    private fun createUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun showToast(context: Context, msg: String, duration: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, msg, duration).show()
        }
    }
}
