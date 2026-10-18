package de.hinterland.wayfinder.core

import kotlin.random.Random

/**
 * Everything the robot says or shows, in one place.
 * Written without contractions ("I will", not "I'll") - the speech engine pronounces them more reliably.
 */
object Lines {

    // ------------------------------------------------------------------ screen texts
    const val homeTitle = "Where would you like to go?"
    const val homeHint = "Tap a room or just ask me, for example: “Where are the restrooms?”"
    const val wanderTitle = "Need directions? Tap me!"
    const val noRooms = "No rooms yet. Staff: long-press the top-left corner to set them up."
    const val walkMe = "Walk me there"
    const val thisFloor = "On this floor"
    const val followMeTitle = "Please follow me"
    const val stop = "Stop"
    const val arrivedTitle = "Here we are!"
    const val anythingElse = "Show all rooms"
    const val close = "Thanks!"
    const val pausedTitle = "Taking a short break"
    const val pausedText = "The robot system has taken over (emergency stop, charging or an update). I will be back right away."
    const val chargingTitle = "Recharging my batteries"
    const val chargingText = "I am going to my charging station. See you soon!"
    const val startingTitle = "Waking up…"

    // ------------------------------------------------------------------ status line under the face
    const val statusReady = "Happy to help"
    const val statusListening = "Listening…"
    const val statusThinking = "Thinking…"
    const val statusWandering = "Taking a little walk"
    const val statusGuiding = "Guiding"
    const val statusReturning = "Heading back"
    const val navBlocked = "Excuse me, let me through…"
    const val navOccupied = "Someone is standing at the destination…"
    const val navWaitingRobot = "Waiting for another robot…"

    // ------------------------------------------------------------------ speech
    fun greeting(robotName: String): String = pick(
        "Hi! I am $robotName. Looking for a room? Just ask me or tap my screen.",
        "Hello there! I am $robotName, the guide robot. Where would you like to go?",
        "Hi! Can I help you find your way? I know all the rooms here.",
        "Welcome! I am $robotName. Tap a room on my screen and I will help you get there.",
    )

    fun announcement(robotName: String): String = pick(
        "Hello everyone! I am $robotName, the guide robot. If you are looking for a room, just ask me.",
        "Looking for the restrooms or a presentation room? Tap my screen and I will help you.",
        "Hi! I know my way around here. Ask me where you would like to go.",
    )

    const val touchedWhileWalking = "Oh, hello! Where would you like to go?"

    fun followMe(room: Room): String = pick(
        "Of course! Please follow me to ${Text.the(room.name)}.",
        "Sure! ${Text.the(room.name).replaceFirstChar { it.titlecase() }} it is. Please follow me.",
        "Happy to! Follow me, I will take you to ${Text.the(room.name)}.",
    )

    fun followMeToStairs(point: String, room: Room): String =
        "Please follow me. I will take you to ${Text.the(point)}, from there it is easy to get to ${Text.the(room.name)}."

    const val thisWay = "This way, please. Follow me."
    const val excuseMe = "Excuse me, may I pass through?"

    fun arrived(room: Room, robotName: String): String = pick(
        "Here we are: ${room.name}. Have a great time!",
        "We made it! This is ${Text.the(room.name)}. Enjoy!",
        "Here is ${Text.the(room.name)}. If you need anything else, just ask me.",
    ).let { if (room.resolvedIcon == RoomIcon.EXIT) "Here is ${Text.the(room.name)}. Thank you for visiting, goodbye!" else it }

    fun arrivedVia(point: String, room: Room, floors: List<Int>, robotFloor: Int): String =
        "This is ${Text.the(point)}. ${Text.the(room.name).replaceFirstChar { it.titlecase() }} ${verb(room)} ${Floors.spokenList(floors, robotFloor)}."

    fun alreadyHere(room: Room): String = "We are already here: ${room.name}."

    /** "The restrooms are on the first floor, one floor up, or in the basement, one floor down." */
    fun floorAnswer(room: Room, floors: List<Int>, robotFloor: Int): String {
        val subject = Text.the(room.name).replaceFirstChar { it.titlecase() }
        return "$subject ${verb(room)} ${Floors.spokenList(floors, robotFloor)}."
    }

    fun offerWay(point: String): String = "Shall I walk you to ${Text.the(point)}?"

    fun showWayButton(point: String): String = "Walk me to ${Text.the(point)}"

    fun describeOnly(room: Room): String {
        val subject = Text.the(room.name).replaceFirstChar { it.titlecase() }
        return if (room.directions.isNotBlank()) "$subject ${verb(room)} on this floor. ${room.directions}"
        else "$subject ${verb(room)} on this floor, but I do not have it on my map, so I cannot walk you there. Sorry!"
    }

    fun navFailed(room: Room): String {
        val tip = room.directions.takeIf { it.isNotBlank() }?.let { " $it" } ?: " Please ask one of the staff members."
        return "I am sorry, I cannot get to ${Text.the(room.name)} right now.$tip"
    }

    fun cannotDrive(room: Room, reason: String): String {
        val tip = room.directions.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        return "I am sorry, $reason, so I cannot walk you to ${Text.the(room.name)} right now.$tip"
    }

    const val reasonNotLocalized = "I do not know where I am on my map at the moment"
    const val reasonOffline = "my wheels are not available at the moment"
    const val reasonBattery = "my battery is too low"

    const val stopped = "Okay, I stopped. Where would you like to go?"
    const val youAreWelcome = "You are welcome! Enjoy the event."
    const val goodbye = "Goodbye! Have a wonderful day."
    const val alright = "Alright! Just ask me if you need anything else."
    const val didNotCatch = "Sorry, I did not catch that. Just tap a room on my screen, or tell me where you would like to go."
    const val goingToCharge = "My battery is getting low, so I am going to charge now. See you soon!"
    const val chargeFailed = "I could not reach my charging station."

    fun hello(robotName: String): String = "Hello! I am $robotName. Where would you like to go?"

    fun listRooms(rooms: List<Room>): String {
        if (rooms.isEmpty()) return "I do not know any rooms yet. Please ask one of the staff members."
        val names = rooms.take(6).map { it.name }
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        val more = if (rooms.size > 6) ", and a few more" else ""
        return "I can help you find $list$more. Just tap one on my screen or tell me where you want to go."
    }

    private fun verb(room: Room) = if (Text.isPlural(room.name)) "are" else "is"

    /** Random choice, replaceable in tests. */
    var random: Random = Random.Default

    private fun pick(vararg options: String): String = options[random.nextInt(options.size)]
}
