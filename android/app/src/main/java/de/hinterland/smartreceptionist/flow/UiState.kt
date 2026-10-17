package de.hinterland.smartreceptionist.flow

import de.hinterland.smartreceptionist.net.Employee

enum class Phase { IDLE, CONVERSATION, ESCORT, RETURNING }

/** Gesichtsausdruck des Roboters */
enum class Mood { HAPPY, LISTENING, THINKING, SPEAKING, JOY, SAD }

data class UiOption(val id: String, val label: String, val primary: Boolean = false)

enum class InputKind { NAME, EMPLOYEE }

data class TextInput(val kind: InputKind, val hint: String)

data class Summary(val visitor: String, val employee: String, val whenText: String)

/** Ausweisfoto: LIVE = Kamerabild (Besucher sieht sich selbst), REVIEW = aufgenommenes Foto */
enum class PhotoMode { LIVE, REVIEW }

data class UiState(
    val phase: Phase = Phase.IDLE,
    val mood: Mood = Mood.HAPPY,
    /** Große Überschrift (meist die aktuelle Frage) */
    val title: String = "",
    val subtitle: String = "",
    /** Was der Roboter gerade sagt (Untertitel) */
    val caption: String = "",
    /** Was der Roboter vom Besucher verstanden hat */
    val heard: String = "",
    val options: List<UiOption> = emptyList(),
    val input: TextInput? = null,
    val employees: List<Employee> = emptyList(),
    val summary: Summary? = null,
    /** Hinweisleiste (z. B. Fehler) */
    val banner: String? = null,
    val showCancel: Boolean = false,
    val showStop: Boolean = false,
    val backendOk: Boolean? = null,
    val robotOk: Boolean = false,
    // ---- Besucherausweis (nur während des Foto-/Druckschritts gesetzt)
    /** Statt des Gesichts das Kamerabild bzw. das aufgenommene Foto zeigen */
    val photoMode: PhotoMode? = null,
    /** Aufgenommenes Foto (JPEG) zur Kontrolle durch den Besucher */
    val photo: ByteArray? = null,
    /** Countdown vor dem Auslösen (3, 2, 1; 0 = Blitz) */
    val countdown: Int? = null,
    /** Kamerabild spiegeln (wie ein Spiegel) */
    val mirrorPreview: Boolean = true,
    /** Vorschau des fertigen Ausweises (JPEG) */
    val badgePreview: ByteArray? = null,
)

sealed class UserInput {
    data class Voice(val text: String) : UserInput()
    data class Typed(val text: String) : UserInput()
    data class Option(val id: String) : UserInput()
    data class PickEmployee(val employee: Employee) : UserInput()
    data object Cancel : UserInput()
}
