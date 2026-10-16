package `in`.hashday.pms.sheetal

import android.content.Context
import android.content.SharedPreferences

class CookieUtils(context: Context) {

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("MyPreferences", Context.MODE_PRIVATE)

    init {
        // Initialize anything necessary here
    }

    // Method to manage cookies or other data in shared preferences
    fun saveData(key: String, value: String) {
        val editor = sharedPreferences.edit()
        editor.putString(key, value)
        editor.apply()
    }

    fun getData(key: String): String? {
        return sharedPreferences.getString(key, null)
    }
}
