package de.hinterland.wayfinder.flow

import android.content.Context
import de.hinterland.wayfinder.core.AppLog
import de.hinterland.wayfinder.core.Settings

/** Keeps the settings as one JSON document in the SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wayfinder", Context.MODE_PRIVATE)

    @Volatile var current: Settings = load()
        private set

    private fun load(): Settings = try {
        Settings.fromJson(prefs.getString(KEY, null))
    } catch (t: Throwable) {
        AppLog.e("Settings could not be read, using defaults", t)
        Settings()
    }

    fun save(settings: Settings) {
        val clean = settings.sanitized()
        current = clean
        prefs.edit().putString(KEY, clean.toJson()).apply()
        AppLog.i("Settings saved")
    }

    private companion object {
        const val KEY = "settings_v1"
    }
}
