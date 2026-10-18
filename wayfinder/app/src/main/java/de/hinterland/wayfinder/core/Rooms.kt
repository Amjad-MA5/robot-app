package de.hinterland.wayfinder.core

/**
 * Rooms the robot can show on its screen, and how it helps with each of them.
 *
 * Pure Kotlin (no Android classes), so all of it runs in the unit tests.
 */

/** A point saved on the robot's map (RobotAPI getPlaceList, created with the robot's map tool). */
data class MapPlace(
    val name: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    /** 0 = normal area (reachable); anything else = forbidden area or outside the map */
    val status: Int = 0,
    /** Point type from the RobotAPI (0 = normal, 1 = charging point, 3 = standby, 4 = positioning, 8 = reception) */
    val type: Int = 0,
) {
    val reachable: Boolean get() = status == 0

    /** System points (charging pile, positioning point, ...) are never offered to visitors. */
    val isSystemPoint: Boolean get() = type == TYPE_CHARGING || type == TYPE_POSITIONING || looksLikeSystemPoint(name)

    companion object {
        const val TYPE_CHARGING = 1
        const val TYPE_STANDBY = 3
        const val TYPE_POSITIONING = 4
        const val TYPE_RECEPTION = 8

        /** Names RobotOS gives its special points (see Definition.START_CHARGE_PILE_POSE and friends). */
        private val SYSTEM_NAMES = listOf("充电桩", "回充点", "定位点", "电梯中心", "电梯门", "电梯口", "闸机入口", "闸机出口")
        private val CHARGING_WORDS = Regex("(?i)\\b(charg\\w*|charging pile|charging point|docking|dock|lade\\w*)\\b")

        fun looksLikeSystemPoint(name: String): Boolean =
            SYSTEM_NAMES.any { name.contains(it) } || CHARGING_WORDS.containsMatchIn(name)

        /** The reception point of RobotOS, a good default place to wait for visitors. */
        fun looksLikeHome(place: MapPlace): Boolean =
            place.type == TYPE_RECEPTION || place.type == TYPE_STANDBY || place.name.contains("接待点") ||
                Regex("(?i)\\b(reception|home|start|base|empfang)\\b").containsMatchIn(place.name)
    }
}

/** Emoji shown on a room tile. Only emoji from Unicode 6/7, so every robot ROM has a glyph for them. */
enum class RoomIcon(val emoji: String) {
    AUTO(""),
    RESTROOM("🚻"),
    STAGE("🎤"),
    WORKSHOP("💡"),
    MEETING("👥"),
    FOOD("☕"),
    EXIT("🚪"),
    ELEVATOR("🔼"),
    RECEPTION("🛎"),
    OFFICE("💼"),
    LOUNGE("🛋"),
    INFO("📍");

    companion object {
        private val rules: List<Pair<RoomIcon, Regex>> = listOf(
            RESTROOM to Regex("restroom|toilet|bathroom|washroom|lavator|\\bwc\\b|\\bloo\\b|toilette|\\bklo|sanit"),
            STAGE to Regex("stage|auditor|\\baula\\b|theat|pitch|presentation|keynote|plenar|forum|\\bsaal\\b|buehne|bühne|\\bhall\\b"),
            FOOD to Regex("caf[eé]|coffee|kitchen|catering|food|lunch|\\bbar\\b|bistro|kantine|kueche|küche|kaffee|canteen|snack|drink|buffet"),
            EXIT to Regex("\\bexit\\b|entrance|\\bdoor\\b|ausgang|eingang|way out"),
            ELEVATOR to Regex("elevator|\\blift\\b|stair|aufzug|treppe|fahrstuhl"),
            RECEPTION to Regex("reception|\\binfo|front desk|empfang|registration|check.?in|welcome"),
            WORKSHOP to Regex("workshop|\\blab\\b|studio|maker|hack"),
            MEETING to Regex("meeting|conference|seminar|besprechung|konferenz|\\broom\\b|\\braum\\b"),
            OFFICE to Regex("office|\\bbuero\\b|büro|desk"),
            LOUNGE to Regex("lounge|chill|relax|sofa|garden|terrace|terrasse"),
        )

        /** Picks an icon from the words in a room name ("Coffee Bar" -> ☕). */
        fun guess(text: String): RoomIcon {
            val t = text.lowercase()
            return rules.firstOrNull { (_, regex) -> regex.containsMatchIn(t) }?.first ?: INFO
        }

        fun fromName(name: String?): RoomIcon = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO
    }
}

/** One destination on the screen. */
data class Room(
    val id: String,
    /** Shown on the tile and spoken, e.g. "Restrooms" */
    val name: String,
    /** Map point the robot walks visitors to; empty = it cannot walk there */
    val mapPoint: String = "",
    /** Floors the room is on; empty = the robot's own floor */
    val floors: List<Int> = emptyList(),
    /** Extra spoken sentence, e.g. "Take the stairs next to the main entrance." */
    val directions: String = "",
    /** More words visitors may use for it ("toilet", "WC") */
    val aliases: List<String> = emptyList(),
    val icon: RoomIcon = RoomIcon.AUTO,
    val visible: Boolean = true,
    /** Created automatically from a map point (not stored in the settings) */
    val fromMap: Boolean = false,
) {
    val resolvedIcon: RoomIcon
        get() = if (icon != RoomIcon.AUTO) icon else RoomIcon.guess((listOf(name) + aliases).joinToString(" "))
}

/** How the robot helps with a room. */
sealed interface RoomPlan {
    /** The room is on the robot's floor and on its map: walk the visitor there. */
    data class Walk(val point: String) : RoomPlan

    /** The room is on another floor. [via] = map point (stairs, elevator) the robot can walk the visitor to. */
    data class OtherFloor(val floors: List<Int>, val via: String?) : RoomPlan

    /** On this floor, but there is no map point for it: the robot can only describe the way. */
    data object Describe : RoomPlan
}

/**
 * All rooms on the screen: the rooms from the settings plus (optionally) every normal point on the
 * robot's map. A map point that already belongs to a configured room is not shown twice.
 */
class RoomCatalog(
    private val settings: Settings,
    /** Points of the current map; null = not loaded yet (then configured map points are trusted) */
    private val places: List<MapPlace>?,
) {
    private val placesByName: Map<String, MapPlace> = places.orEmpty().associateBy { it.name }

    val placesLoaded: Boolean get() = places != null

    /** Map points the robot may drive to on its own (wander route, home point candidates). */
    val usablePlaces: List<MapPlace> = places.orEmpty().filter { it.name.isNotBlank() && it.reachable && !it.isSystemPoint }

    val rooms: List<Room> = buildRooms()

    private fun buildRooms(): List<Room> {
        val configured = settings.rooms.filter { it.visible && it.name.isNotBlank() }
        if (!settings.autoAddMapPoints) return configured.distinctBy { it.id }
        val taken = configured.flatMap { room ->
            (listOf(room.name, room.mapPoint) + room.aliases).filter { it.isNotBlank() }.map { Text.key(it) }
        }.toSet()
        val fromMap = usablePlaces
            .filter { it.name !in settings.hiddenMapPoints }
            .filter { Text.key(it.name) !in taken }
            .filter { !containsCjk(it.name) }
            .distinctBy { Text.key(it.name) }
            .sortedBy { it.name.lowercase() }
            .map { Room(id = mapRoomId(it.name), name = displayName(it.name), mapPoint = it.name, fromMap = true) }
        // Unique ids: the screen uses them as keys, a duplicate would crash the list
        return (configured + fromMap).distinctBy { it.id }
    }

    fun byId(id: String): Room? = rooms.firstOrNull { it.id == id }

    /** Does [name] exist on the map and can the robot reach it? Unknown map = trust the setting. */
    fun pointUsable(name: String): Boolean {
        if (name.isBlank()) return false
        if (places == null) return true
        val p = placesByName[name] ?: placesByName.values.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        return p != null && p.reachable
    }

    /** Exact spelling of a map point (settings may differ in upper/lower case). */
    fun pointName(name: String): String =
        placesByName[name]?.name ?: places.orEmpty().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.name ?: name.trim()

    fun planFor(room: Room): RoomPlan {
        val robotFloor = settings.robotFloor
        val onThisFloor = room.floors.isEmpty() || robotFloor in room.floors
        val point = room.mapPoint.takeIf { pointUsable(it) }?.let { pointName(it) }
        if (onThisFloor && point != null) return RoomPlan.Walk(point)
        if (onThisFloor) return RoomPlan.Describe
        val via = point ?: settings.stairsPoint.takeIf { pointUsable(it) }?.let { pointName(it) }
        return RoomPlan.OtherFloor(room.floors.filter { it != robotFloor }.distinct(), via)
    }

    /** Points of the wander route (only those that exist and are reachable). */
    val wanderPoints: List<String>
        get() {
            val chosen = settings.wanderPoints
            return if (chosen == null) {
                usablePlaces.map { it.name }.distinct()
            } else {
                chosen.filter { pointUsable(it) }.map { pointName(it) }.distinct()
            }
        }

    /** Where the robot waits for visitors: the configured home point or the RobotOS reception point. */
    val homePoint: String?
        get() {
            val configured = settings.homePoint
            if (configured == Settings.HOME_NONE) return null
            if (configured.isNotBlank()) return configured.takeIf { pointUsable(it) }?.let { pointName(it) }
            return places.orEmpty().firstOrNull { it.reachable && MapPlace.looksLikeHome(it) }?.name
        }

    companion object {
        fun mapRoomId(point: String) = "map:$point"

        /** "main stage" -> "Main stage" (map point names are often typed in lower case). */
        fun displayName(point: String): String = point.trim().replaceFirstChar { it.titlecase() }

        private fun containsCjk(s: String) = s.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    }
}
