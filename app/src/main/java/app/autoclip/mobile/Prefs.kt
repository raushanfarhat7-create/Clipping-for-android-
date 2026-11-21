package app.autoclip.mobile

import android.content.Context

/** Stores the AutoClip server address the app should load. */
object Prefs {
    private const val FILE = "autoclip"
    private const val KEY_URL = "server_url"

    fun serverUrl(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_URL, null)

    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_URL, url).apply()
    }

    /** "192.168.1.20:8000" -> "http://192.168.1.20:8000"; trailing slashes removed. */
    fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "http://$url"
        }
        return url.trimEnd('/')
    }
}
