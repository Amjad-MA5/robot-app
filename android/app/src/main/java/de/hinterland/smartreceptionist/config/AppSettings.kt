package de.hinterland.smartreceptionist.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Alle Einstellungen der App. Werden im Admin-Bereich (lange auf die linke obere
 * Ecke drücken → PIN) geändert und in den SharedPreferences gespeichert.
 */
data class AppSettings(
    /** Öffentliche Backend-Adresse; im Admin-Bereich änderbar. */
    val backendUrl: String = "https://robot-app-nv9c.onrender.com",
    /** Muss mit SR_API_KEY im Backend übereinstimmen */
    val apiKey: String = "hinterland-demo",
    /** Name des Kartenpunkts für den Warteraum (Karten-Tool des Roboters) */
    val waitingRoom: String = "Warteraum",
    /** Name des Kartenpunkts am Empfang, zu dem der Roboter zurückkehrt */
    val receptionPoint: String = "Reception Point",
    /** "de" oder "en" */
    val language: String = "en",
    /** Name, mit dem sich der Roboter vorstellt */
    val robotName: String = "Norra",
    /** Wird überschrieben, sobald das Backend seinen Firmennamen liefert */
    val companyName: String = "Founders Foundation",
    /** Personen näher als dieser Abstand (Meter) werden automatisch begrüßt */
    val greetDistance: Float = 1.8f,
    /** Automatisch begrüßen, sobald jemand vor dem Roboter steht */
    val autoGreet: Boolean = true,
    /** Geschwindigkeit beim Begleiten des Gastes (m/s, 0.1 – 0.85) */
    val escortSpeed: Float = 0.5f,
    /** Geschwindigkeit bei der Rückfahrt zum Empfang (m/s) */
    val returnSpeed: Float = 0.7f,
    /** Wartezeit im Warteraum, bevor der Roboter zurückfährt (Sekunden) */
    val waitingRoomPauseSec: Int = 4,
    /** Sprachverständnis über das AgentOS-Sprachmodell (sonst nur Regeln) */
    val useLlm: Boolean = true,
    val freeChat: Boolean = true,
    /** Bei Paketlieferungen ein Foto des Pakets aufnehmen (Kamera-App des Geräts) */
    val deliveryPhoto: Boolean = false,
    /** PIN für den Admin-Bereich */
    val adminPin: String = "1234",
    /** Print a visitor badge with photo when a guest with an appointment arrives */
    val badgeEnabled: Boolean = true,
    /**
     * IPP address of the office printer. The robot sends the badge there itself, because a cloud
     * backend cannot reach the office network. Empty = the backend prints (backend in the office network).
     */
    val printerUrl: String = "http://192.168.0.233/ipp",
    /** Camera rotation in degrees (0/90/180/270), -1 = automatic */
    val cameraRotation: Int = -1,
    /** "auto", "shared" (robot's video stream) or "camera2" (Android camera) */
    val cameraSource: String = "auto",
    /** Mirror the preview so the guest sees themselves like in a mirror */
    val cameraMirror: Boolean = true,
    /** Where to pick up the badge, e.g. "at the printer next to reception" (empty = default text) */
    val badgePickupHint: String = "",
    /** Im Warteraum eine kurze Geschichte über die Founders Foundation anbieten */
    val waitingStory: Boolean = true,
    /** Eigener Text für die Geschichte (Absätze durch Leerzeilen getrennt; leer = Standardtext) */
    val waitingStoryText: String = "",
) {
    val isGerman: Boolean get() = language != "en"

    companion object {
        private const val PREFS = "smart_receptionist_settings"

        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = AppSettings()
            return AppSettings(
                backendUrl = p.getString("backendUrl", d.backendUrl) ?: d.backendUrl,
                apiKey = p.getString("apiKey", d.apiKey) ?: d.apiKey,
                waitingRoom = p.getString("waitingRoom", d.waitingRoom) ?: d.waitingRoom,
                receptionPoint = p.getString("receptionPoint", d.receptionPoint) ?: d.receptionPoint,
                language = p.getString("language", d.language) ?: d.language,
                robotName = p.getString("robotName", d.robotName) ?: d.robotName,
                companyName = p.getString("companyName", d.companyName) ?: d.companyName,
                greetDistance = p.getFloat("greetDistance", d.greetDistance),
                autoGreet = p.getBoolean("autoGreet", d.autoGreet),
                escortSpeed = p.getFloat("escortSpeed", d.escortSpeed),
                returnSpeed = p.getFloat("returnSpeed", d.returnSpeed),
                waitingRoomPauseSec = p.getInt("waitingRoomPauseSec", d.waitingRoomPauseSec),
                useLlm = p.getBoolean("useLlm", d.useLlm),
                freeChat = p.getBoolean("freeChat", d.freeChat),
                deliveryPhoto = p.getBoolean("deliveryPhoto", d.deliveryPhoto),
                adminPin = p.getString("adminPin", d.adminPin) ?: d.adminPin,
                badgeEnabled = p.getBoolean("badgeEnabled", d.badgeEnabled),
                printerUrl = p.getString("printerUrl", d.printerUrl) ?: d.printerUrl,
                cameraRotation = p.getInt("cameraRotation", d.cameraRotation),
                cameraSource = p.getString("cameraSource", d.cameraSource) ?: d.cameraSource,
                cameraMirror = p.getBoolean("cameraMirror", d.cameraMirror),
                badgePickupHint = p.getString("badgePickupHint", d.badgePickupHint) ?: d.badgePickupHint,
                waitingStory = p.getBoolean("waitingStory", d.waitingStory),
                waitingStoryText = p.getString("waitingStoryText", d.waitingStoryText) ?: d.waitingStoryText,
            )
        }

        fun save(context: Context, s: AppSettings) {
            val p: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.edit()
                .putString("backendUrl", s.backendUrl.trim().trimEnd('/'))
                .putString("apiKey", s.apiKey.trim())
                .putString("waitingRoom", s.waitingRoom.trim())
                .putString("receptionPoint", s.receptionPoint.trim())
                .putString("language", s.language)
                .putString("robotName", s.robotName.trim())
                .putString("companyName", s.companyName.trim())
                .putFloat("greetDistance", s.greetDistance)
                .putBoolean("autoGreet", s.autoGreet)
                .putFloat("escortSpeed", s.escortSpeed)
                .putFloat("returnSpeed", s.returnSpeed)
                .putInt("waitingRoomPauseSec", s.waitingRoomPauseSec)
                .putBoolean("useLlm", s.useLlm)
                .putBoolean("freeChat", s.freeChat)
                .putBoolean("deliveryPhoto", s.deliveryPhoto)
                .putString("adminPin", s.adminPin.trim())
                .putBoolean("badgeEnabled", s.badgeEnabled)
                .putString("printerUrl", s.printerUrl.trim())
                .putInt("cameraRotation", s.cameraRotation)
                .putString("cameraSource", s.cameraSource)
                .putBoolean("cameraMirror", s.cameraMirror)
                .putString("badgePickupHint", s.badgePickupHint.trim())
                .putBoolean("waitingStory", s.waitingStory)
                .putString("waitingStoryText", s.waitingStoryText.trim())
                .apply()
        }
    }
}
