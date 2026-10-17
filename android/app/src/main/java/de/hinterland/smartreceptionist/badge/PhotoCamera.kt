package de.hinterland.smartreceptionist.badge

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Kamera für das Ausweisfoto. Auf dem Roboter steckt [RobotCamera] dahinter, in Tests eine Attrappe.
 *
 * Die Vorschau ist bereits gedreht (aber nicht gespiegelt – das Spiegeln macht die Oberfläche,
 * damit sich der Besucher wie in einem Spiegel sieht). Das fertige Foto ist nie gespiegelt.
 */
interface PhotoCamera {
    /** Aktuelles Vorschaubild (ca. 8 Bilder pro Sekunde), null = keine Kamera aktiv */
    val preview: StateFlow<Bitmap?>

    /** Welche Kamera gerade läuft (für Admin/Log), z. B. "shared-stream" oder "camera2:1" */
    val source: String

    /** Kamera starten. true, sobald das erste Bild angekommen ist; false = keine Kamera nutzbar. */
    suspend fun open(): Boolean

    /** Aktuelles Bild als JPEG im Hochformat (3:4), null wenn gerade keins vorliegt. */
    suspend fun takePhoto(): ByteArray?

    /** Kamera freigeben – danach läuft die Personenerkennung des Roboters wieder normal. */
    fun close()
}

/** Ersatz, solange keine echte Kamera gesetzt ist (z. B. Emulator, Tests): Ausweis ohne Foto. */
object NoCamera : PhotoCamera {
    override val preview: StateFlow<Bitmap?> = MutableStateFlow(null)
    override val source: String = "none"
    override suspend fun open(): Boolean = false
    override suspend fun takePhoto(): ByteArray? = null
    override fun close() = Unit
}
