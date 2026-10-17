package de.hinterland.smartreceptionist.net

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import de.hinterland.smartreceptionist.BuildConfig
import de.hinterland.smartreceptionist.robot.RobotController
import java.io.File

/**
 * In-App-Update: lädt die neueste APK vom Backend und installiert sie –
 * bevorzugt still über den RobotOS-Dienst, sonst über den Android-Paketinstaller.
 */
object AppUpdater {
    sealed class Result {
        data class UpToDate(val version: String) : Result()
        data class Started(val how: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun checkAndInstall(context: Context, backend: BackendClient, onProgress: (Int) -> Unit): Result {
        val info = try {
            backend.latestApp()
        } catch (e: Exception) {
            return Result.Failed("Backend is unavailable: ${e.message}")
        }
        if (!info.available) return Result.Failed("No APK is available on the backend")
        if (info.versionCode <= BuildConfig.VERSION_CODE) return Result.UpToDate(BuildConfig.VERSION_NAME)

        val dir = context.getExternalFilesDir("updates") ?: File(context.cacheDir, "updates")
        val apk = File(dir, "smart-receptionist-${info.versionCode}.apk")
        try {
            backend.download(info.downloadPath, apk, onProgress)
        } catch (e: Exception) {
            return Result.Failed("Download fehlgeschlagen: ${e.message}")
        }
        apk.setReadable(true, false)

        if (RobotController.installApk(apk.absolutePath)) {
            return             Result.Started("RobotOS installed version ${info.versionName}; the app will restart.")
        }
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.Started("Opened the Android installer (version ${info.versionName}).")
        } catch (e: Exception) {
            Log.e("AppUpdater", "Could not start the installer", e)
            Result.Failed("Installation failed: ${e.message}")
        }
    }
}
