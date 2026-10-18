package de.hinterland.wayfinder.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the staff can change in the admin screen (long-press the top-left corner, PIN).
 * Stored as one JSON document, so new settings never break an older installation.
 */
data class Settings(
    val robotName: String = "Norra",
    val venueName: String = "Founders Foundation",
    /** Facts the robot may use in small talk, e.g. "Pitches start at 2 pm in the Main Hall." */
    val eventInfo: String = "",

    /** Rooms shown on the screen (in this order, before the map points) */
    val rooms: List<Room> = defaultRooms(),
    /** Also show every normal point of the robot's map as a room */
    val autoAddMapPoints: Boolean = true,
    /** Map points that are not shown as rooms */
    val hiddenMapPoints: Set<String> = emptySet(),
    /** Floor the robot drives on (0 = ground floor) */
    val robotFloor: Int = 0,
    /** Map point at the stairs/elevator; the robot can walk visitors there for rooms on other floors */
    val stairsPoint: String = "",
    /** Where the robot waits when it does not wander. "" = RobotOS reception point, "-" = none */
    val homePoint: String = "",

    val guideSpeed: Double = 0.5,
    /** How long the robot stays with the visitor after arriving (seconds) */
    val arrivalWaitSec: Int = 12,

    val wanderEnabled: Boolean = true,
    /** Wander route; null = all normal map points */
    val wanderPoints: List<String>? = null,
    val wanderRandom: Boolean = false,
    val wanderSpeed: Double = 0.4,
    /** Pause at every wander point (seconds) */
    val wanderPauseSec: Int = 20,
    /** Start wandering after this long without any visitor (seconds) */
    val idleBeforeWanderSec: Int = 45,

    /** Say hello when someone stands in front of the robot */
    val greetPeople: Boolean = true,
    /** Distance for the greeting (meters) */
    val greetDistance: Double = 1.3,
    /** Now and then invite people nearby to ask for directions while wandering */
    val announcements: Boolean = true,

    /** Below this battery level the robot stops wandering (percent) */
    val wanderMinBattery: Int = 20,
    /** Below this level it drives to the charging pile on its own (percent, 0 = never) */
    val autoChargeBelow: Int = 12,

    /** Understand free questions and chat with the AgentOS language model */
    val useLlm: Boolean = true,
    val adminPin: String = "1234",
) {
    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        put("robotName", robotName)
        put("venueName", venueName)
        put("eventInfo", eventInfo)
        put("rooms", JSONArray().apply { rooms.forEach { put(roomToJson(it)) } })
        put("autoAddMapPoints", autoAddMapPoints)
        put("hiddenMapPoints", JSONArray(hiddenMapPoints.toList()))
        put("robotFloor", robotFloor)
        put("stairsPoint", stairsPoint)
        put("homePoint", homePoint)
        put("guideSpeed", guideSpeed)
        put("arrivalWaitSec", arrivalWaitSec)
        put("wanderEnabled", wanderEnabled)
        if (wanderPoints != null) put("wanderPoints", JSONArray(wanderPoints))
        put("wanderRandom", wanderRandom)
        put("wanderSpeed", wanderSpeed)
        put("wanderPauseSec", wanderPauseSec)
        put("idleBeforeWanderSec", idleBeforeWanderSec)
        put("greetPeople", greetPeople)
        put("greetDistance", greetDistance)
        put("announcements", announcements)
        put("wanderMinBattery", wanderMinBattery)
        put("autoChargeBelow", autoChargeBelow)
        put("useLlm", useLlm)
        put("adminPin", adminPin)
    }.toString()

    /** Values in the allowed ranges (the robot rejects speeds outside 0.1 - 0.85 m/s). */
    fun sanitized(): Settings = copy(
        robotName = robotName.trim().ifBlank { "Norra" },
        venueName = venueName.trim(),
        eventInfo = eventInfo.trim().take(2000),
        rooms = rooms.filter { it.name.isNotBlank() }.map { it.copy(name = it.name.trim(), mapPoint = it.mapPoint.trim()) },
        stairsPoint = stairsPoint.trim(),
        homePoint = homePoint.trim(),
        guideSpeed = guideSpeed.coerceIn(0.2, 0.8),
        arrivalWaitSec = arrivalWaitSec.coerceIn(0, 120),
        wanderSpeed = wanderSpeed.coerceIn(0.2, 0.7),
        wanderPauseSec = wanderPauseSec.coerceIn(0, 600),
        idleBeforeWanderSec = idleBeforeWanderSec.coerceIn(10, 3600),
        greetDistance = greetDistance.coerceIn(0.5, 3.0),
        wanderMinBattery = wanderMinBattery.coerceIn(0, 90),
        autoChargeBelow = autoChargeBelow.coerceIn(0, 50),
        adminPin = adminPin.trim().ifBlank { "1234" },
    )

    companion object {
        /** homePoint value for "no home point" */
        const val HOME_NONE = "-"

        const val RESTROOM_ID = "restrooms"

        fun defaultRooms(): List<Room> = listOf(
            Room(
                id = RESTROOM_ID,
                name = "Restrooms",
                floors = listOf(1, -1),
                aliases = listOf(
                    "restroom", "toilet", "toilets", "bathroom", "washroom", "lavatory", "loo", "wc",
                    "ladies room", "mens room", "gents", "toilette", "toiletten", "klo",
                ),
                icon = RoomIcon.RESTROOM,
            ),
        )

        fun fromJson(json: String?): Settings {
            if (json.isNullOrBlank()) return Settings()
            return try {
                val o = JSONObject(json)
                val d = Settings()
                Settings(
                    robotName = o.optString("robotName", d.robotName),
                    venueName = o.optString("venueName", d.venueName),
                    eventInfo = o.optString("eventInfo", d.eventInfo),
                    rooms = o.optJSONArray("rooms")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::roomFromJson) }
                    } ?: d.rooms,
                    autoAddMapPoints = o.optBoolean("autoAddMapPoints", d.autoAddMapPoints),
                    hiddenMapPoints = o.optJSONArray("hiddenMapPoints")?.let(::strings)?.toSet() ?: d.hiddenMapPoints,
                    robotFloor = o.optInt("robotFloor", d.robotFloor),
                    stairsPoint = o.optString("stairsPoint", d.stairsPoint),
                    homePoint = o.optString("homePoint", d.homePoint),
                    guideSpeed = o.optDouble("guideSpeed", d.guideSpeed),
                    arrivalWaitSec = o.optInt("arrivalWaitSec", d.arrivalWaitSec),
                    wanderEnabled = o.optBoolean("wanderEnabled", d.wanderEnabled),
                    wanderPoints = o.optJSONArray("wanderPoints")?.let(::strings),
                    wanderRandom = o.optBoolean("wanderRandom", d.wanderRandom),
                    wanderSpeed = o.optDouble("wanderSpeed", d.wanderSpeed),
                    wanderPauseSec = o.optInt("wanderPauseSec", d.wanderPauseSec),
                    idleBeforeWanderSec = o.optInt("idleBeforeWanderSec", d.idleBeforeWanderSec),
                    greetPeople = o.optBoolean("greetPeople", d.greetPeople),
                    greetDistance = o.optDouble("greetDistance", d.greetDistance),
                    announcements = o.optBoolean("announcements", d.announcements),
                    wanderMinBattery = o.optInt("wanderMinBattery", d.wanderMinBattery),
                    autoChargeBelow = o.optInt("autoChargeBelow", d.autoChargeBelow),
                    useLlm = o.optBoolean("useLlm", d.useLlm),
                    adminPin = o.optString("adminPin", d.adminPin),
                ).sanitized()
            } catch (t: Throwable) {
                // Broken settings must never stop the robot from working
                Settings()
            }
        }

        private fun strings(arr: JSONArray): List<String> =
            (0 until arr.length()).mapNotNull { arr.optString(it, "").trim().takeIf { s -> s.isNotEmpty() } }

        private fun roomToJson(r: Room) = JSONObject().apply {
            put("id", r.id)
            put("name", r.name)
            put("mapPoint", r.mapPoint)
            put("floors", JSONArray(r.floors))
            put("directions", r.directions)
            put("aliases", JSONArray(r.aliases))
            put("icon", r.icon.name)
            put("visible", r.visible)
        }

        private fun roomFromJson(o: JSONObject): Room? {
            val name = o.optString("name", "").trim()
            if (name.isEmpty()) return null
            val floors = o.optJSONArray("floors")?.let { arr -> (0 until arr.length()).mapNotNull { i -> arr.optInt(i, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } } }
            return Room(
                id = o.optString("id", "").ifBlank { newRoomId(name) },
                name = name,
                mapPoint = o.optString("mapPoint", ""),
                floors = floors.orEmpty(),
                directions = o.optString("directions", ""),
                aliases = o.optJSONArray("aliases")?.let(::strings).orEmpty(),
                icon = RoomIcon.fromName(o.optString("icon", "AUTO")),
                visible = o.optBoolean("visible", true),
            )
        }

        fun newRoomId(name: String): String =
            "room-" + Text.key(name).replace(' ', '-').take(24) + "-" + Math.floorMod(System.nanoTime(), 100_000L)
    }
}
