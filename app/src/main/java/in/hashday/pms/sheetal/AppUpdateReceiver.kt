package `in`.hashday.pms.sheetal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

class AppUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppUpdateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "Application updated successfully (MY_PACKAGE_REPLACED). Restarting application...")

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, "App updated successfully! Restarting...", Toast.LENGTH_LONG).show()
            }

            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                } else {
                    Log.e(TAG, "Could not obtain launch intent for package ${context.packageName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting application after update", e)
            }
        }
    }
}
