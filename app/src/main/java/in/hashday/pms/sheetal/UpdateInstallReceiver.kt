package `in`.hashday.pms.sheetal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UpdateInstallReceiver"
        const val ACTION_INSTALL_COMPLETE = "in.hashday.pms.sheetal.ACTION_INSTALL_COMPLETE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_COMPLETE) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        Log.d(TAG, "Installation status received: $status, message: $message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.d(TAG, "User action required for installation confirmation")
                val userActionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                userActionIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "PackageInstaller installation succeeded")
                showToast(context, "Update installed successfully! Restarting...", Toast.LENGTH_LONG)
                restartApp(context)
            }

            else -> {
                val failureReason = parseFailureMessage(status, message)
                Log.e(TAG, "Package installation failed: $failureReason")
                showToast(context, "Update install failed: $failureReason", Toast.LENGTH_LONG)
            }
        }
    }

    private fun parseFailureMessage(status: Int, message: String?): String {
        if (!message.isNullOrBlank()) return message

        return when (status) {
            PackageInstaller.STATUS_FAILURE -> "Installation failed"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation aborted by user or system"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked by policy or device admin"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Package conflict detected (signature mismatch or existing app error)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Incompatible package version or ABI"
            PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid or corrupted APK file"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Insufficient storage space to install update"
            else -> "Unknown installation error (Status code: $status)"
        }
    }

    private fun restartApp(context: Context) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart application", e)
        }
    }

    private fun showToast(context: Context, msg: String, duration: Int = Toast.LENGTH_SHORT) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, msg, duration).show()
        }
    }
}
