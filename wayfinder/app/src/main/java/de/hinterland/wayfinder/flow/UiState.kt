package de.hinterland.wayfinder.flow

import de.hinterland.wayfinder.core.Room

enum class Mode {
    /** Standing still, room tiles on screen */
    HOME,

    /** Walking the wander route (room tiles stay on screen, any touch stops the robot) */
    WANDERING,

    /** Answer card: which floor, directions, optionally "walk me to the stairs" */
    INFO,

    /** Walking a visitor to a room */
    GUIDING,

    /** Arrived with the visitor */
    ARRIVED,

    /** Driving back to the home point */
    RETURNING,

    /** Driving to the charging pile */
    CHARGING,

    /** RobotOS has taken over (emergency stop, charging, OTA) */
    PAUSED,
}

enum class Mood { HAPPY, SPEAKING, LISTENING, THINKING, JOY, SAD, SLEEPY }

/** A room tile. */
data class Tile(
    val id: String,
    val name: String,
    val emoji: String,
    /** "Walk me there", "Floors 1 · −1", "On this floor" */
    val hint: String,
    /** The robot can walk there (shown with an arrow) */
    val walkable: Boolean,
)

/** Big floor number on the answer card. */
data class FloorBadge(val label: String, val caption: String, val up: Boolean?)

data class UiState(
    val mode: Mode = Mode.HOME,
    val mood: Mood = Mood.HAPPY,
    val robotName: String = "",
    /** Line under the face ("Taking a little walk", "Listening…") */
    val status: String = "",
    val title: String = "",
    val tiles: List<Tile> = emptyList(),
    /** Room of the current answer card / guided walk */
    val focus: Room? = null,
    val focusEmoji: String = "",
    val floors: List<FloorBadge> = emptyList(),
    val infoText: String = "",
    /** Label of the "walk me to the stairs" button (null = no button) */
    val wayButton: String? = null,
    /** Live note while driving ("Excuse me, let me through…") */
    val navNote: String = "",
    /** What the robot says right now */
    val caption: String = "",
    /** What the robot heard last */
    val heard: String = "",
    /** The speech recognition is listening right now (AgentOS agent status) */
    val listening: Boolean = false,
    val banner: String? = null,
    val battery: Int? = null,
    val simulated: Boolean = false,
)
