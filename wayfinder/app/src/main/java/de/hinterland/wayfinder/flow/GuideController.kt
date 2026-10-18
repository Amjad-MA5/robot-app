package de.hinterland.wayfinder.flow

import de.hinterland.wayfinder.core.AppLog
import de.hinterland.wayfinder.core.Brain
import de.hinterland.wayfinder.core.BrainContext
import de.hinterland.wayfinder.core.BrainIntent
import de.hinterland.wayfinder.core.EchoFilter
import de.hinterland.wayfinder.core.Floors
import de.hinterland.wayfinder.core.Heard
import de.hinterland.wayfinder.core.Lines
import de.hinterland.wayfinder.core.MapPlace
import de.hinterland.wayfinder.core.Matcher
import de.hinterland.wayfinder.core.Room
import de.hinterland.wayfinder.core.RoomCatalog
import de.hinterland.wayfinder.core.RoomPlan
import de.hinterland.wayfinder.core.Settings
import de.hinterland.wayfinder.core.Text
import de.hinterland.wayfinder.core.WanderPlanner
import de.hinterland.wayfinder.robot.NavEvent
import de.hinterland.wayfinder.robot.NavResult
import de.hinterland.wayfinder.robot.Robot
import de.hinterland.wayfinder.robot.RobotState
import de.hinterland.wayfinder.robot.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The robot's behaviour, independent of Android (unit-tested with a fake robot and virtual time).
 *
 *  HOME ──(no visitor for a while)──> WANDERING ──(touch, voice, face)──> HOME
 *  HOME/WANDERING ──(room on this floor)──> GUIDING ──> ARRIVED ──> WANDERING (or back to the home point)
 *  HOME/WANDERING ──(room on another floor)──> INFO ──("walk me to the stairs")──> GUIDING
 *  any ──(RobotOS takes over)──> PAUSED ──(recovery)──> HOME
 *
 * Only one task (guiding, wandering, a short talk ...) runs at a time; a new one cancels the old one,
 * and cancelling a drive stops the wheels. Every input may arrive at any time and from any thread.
 */
class GuideController(
    private val scope: CoroutineScope,
    private val robot: Robot,
    private val voice: Voice,
    private val brain: Brain,
    private val settingsSource: () -> Settings,
    private val clock: () -> Long,
) {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val settings: Settings get() = settingsSource()

    private enum class Task { NONE, TALK, INFO, GUIDE, WANDER, RETURN, CHARGE }

    private var task: Job? = null
    private val activeTasks = mutableListOf<Job>()
    private var taskKind = Task.NONE
    private var brainJob: Job? = null
    private var asideJob: Job? = null
    private var captionJob: Job? = null
    private var heardJob: Job? = null
    private val watchers = mutableListOf<Job>()

    @Volatile private var places: List<MapPlace>? = null
    private val planner = WanderPlanner(clock)
    private val echo = EchoFilter(clock)

    private var foreground = false
    private var adminOpen = false
    private var suspended = false
    private var battery: Int? = null
    /** Last answer of the RobotAPI localization check (only shown to the staff, never blocks a drive) */
    private var localized: Boolean? = null
    /** A drive failed with "not localized": no wandering until then (guiding is still tried) */
    private var notLocalizedUntil = 0L
    private var charging = false
    private var chargeRetryAt = 0L

    private var lastInteraction = 0L
    private var lastPresence = LONG_AGO
    private var lastGreet = LONG_AGO
    private var lastAnnouncement = LONG_AGO
    private var lastFallback = LONG_AGO
    private var lastBlockedSpeech = LONG_AGO
    private var greetedCurrentVisitor = false
    private var presentPolls = 0
    private var absentPolls = 0
    private var speaking = 0

    /** Last final speech result: the recognizer sometimes delivers the same sentence twice */
    private var lastFinalText = ""
    private var lastFinalAt = LONG_AGO

    /** Map point of the running guided walk (to ignore the same request twice) */
    private var guidingTo: String? = null

    /** "Walk me to the stairs" target on the current answer card */
    private var infoVia: String? = null
    private var infoFloors: List<Int> = emptyList()

    init {
        scope.launch { robot.state.collect { onRobotState(it) } }
        show(Mode.HOME)
    }

    // ================================================================== lifecycle

    fun onForeground() {
        if (foreground) return
        foreground = true
        robot.connect()
        robot.resetHead()
        touch()
        if (!suspended) show(Mode.HOME)
        startWatchers()
    }

    fun onBackground() {
        if (!foreground) return
        foreground = false
        stopWatchers()
        cancelTask()
        robot.stopNavigation()
        voice.stop()
    }

    fun setAdminOpen(open: Boolean) {
        adminOpen = open
        if (open) {
            cancelTask()
            robot.stopNavigation()
            voice.stop()
        } else {
            touch()
            refreshTiles()
            if (!suspended) show(Mode.HOME)
        }
    }

    fun onSettingsChanged() {
        refreshTiles()
        updateBanner()
    }

    fun reloadPlaces() {
        scope.launch { loadPlaces() }
    }

    /** Admin "Start wandering now" */
    fun startWanderingNow() {
        touch()
        if (wanderAllowed()) startWander() else AppLog.w("Wandering is not possible right now: ${wanderBlocker()}")
    }

    /** Admin test drive; returns a short result for the admin screen. Cancel the coroutine to stop. */
    suspend fun testDrive(point: String): String {
        val result = robot.navigate(point, settings.guideSpeed, GUIDE_TOLERANCE) { AppLog.i("Test drive: $it") }
        planner.moved(if (result == NavResult.Arrived) point else null)
        return when (result) {
            NavResult.Arrived -> "Arrived at $point"
            NavResult.Cancelled -> "Stopped"
            is NavResult.Failed -> "Failed (${result.code}): ${result.message}"
        }
    }

    /** Why the robot does not wander right now (admin screen), null = it may wander. */
    fun wanderBlocker(): String? {
        val s = settings
        return when {
            !s.wanderEnabled -> "Wandering is switched off"
            suspended -> "The robot system has taken over"
            !robot.state.value.connected -> "RobotAPI is not connected"
            clock() < notLocalizedUntil -> "The robot is not localized (last drive failed)"
            charging -> "Charging"
            battery?.let { it < s.wanderMinBattery } == true -> "Battery below ${s.wanderMinBattery} %"
            catalog().wanderPoints.isEmpty() -> "No wander points (map not loaded or no points selected)"
            else -> null
        }
    }

    fun knownPlaces(): List<MapPlace>? = places

    // ================================================================== inputs (UI, speech, AgentOS)

    /** Any touch on the screen (before the tile itself handles the tap). */
    fun onAnyTouch() {
        if (!canInteract()) return
        touch()
        val mode = _ui.value.mode
        if (mode == Mode.WANDERING || mode == Mode.RETURNING) {
            AppLog.i("Touched while walking: stopping")
            robot.stopNavigation()
            startTask(Task.TALK) {
                show(Mode.HOME)
                delay(TOUCH_GREETING_DELAY_MS) // a tap on a tile follows right away and replaces this
                say(Lines.touchedWhileWalking)
            }
        } else if (mode == Mode.CHARGING && taskKind != Task.CHARGE) {
            show(Mode.HOME)
        }
    }

    fun onRoomTapped(id: String) {
        if (!canInteract()) return
        touch()
        val room = catalog().byId(id) ?: return
        AppLog.i("Tapped: ${room.name}")
        helpWith(room)
    }

    fun onStop() {
        if (!canInteract()) return
        touch()
        val moving = taskKind in MOVING_TASKS
        AppLog.i("Stop pressed")
        robot.stopNavigation()
        voice.stop()
        startTask(Task.TALK) {
            show(Mode.HOME)
            if (moving) say(Lines.stopped)
        }
    }

    /** "Walk me to the stairs" on the answer card (or "yes" to that question). */
    fun onWayButton() {
        if (!canInteract()) return
        val st = _ui.value
        val room = st.focus ?: return
        val via = infoVia ?: return
        if (st.mode != Mode.INFO) return
        touch()
        guide(room, via, otherFloors = infoFloors)
    }

    /** "Thanks" / "Show all rooms" on a card. */
    fun onCloseCard() {
        if (!canInteract()) return
        touch()
        cancelTask()
        voice.stop()
        show(Mode.HOME)
    }

    /** Speech recognition result from AgentOS (worker thread). */
    fun onSpeech(text: String, final: Boolean) {
        scope.launch { handleSpeech(text, final) }
    }

    /** AgentOS agent status: listening / thinking / processing / reset_status */
    fun onAgentStatus(status: String) {
        val listening = status.equals("listening", ignoreCase = true)
        if (_ui.value.listening != listening) _ui.update { it.copy(listening = listening) }
    }

    // ================================================================== speech understanding

    private fun handleSpeech(raw: String, final: Boolean) {
        if (!canInteract()) return
        val text = raw.trim()
        if (text.isEmpty()) return
        if (!final) {
            setHeard(text)
            return
        }
        if (echo.isEcho(text)) {
            AppLog.i("Ignored (my own voice): $text")
            return
        }
        val now = clock()
        if (text.equals(lastFinalText, ignoreCase = true) && now - lastFinalAt < DUPLICATE_SPEECH_MS) return
        lastFinalText = text
        lastFinalAt = now
        setHeard(text)
        val catalog = catalog()
        val heard = Matcher.understand(text, catalog.rooms)
        AppLog.i("Heard \"$text\" -> ${heard.javaClass.simpleName}")
        val mode = _ui.value.mode
        val guiding = mode == Mode.GUIDING
        when (heard) {
            Heard.Stop -> if (taskKind in MOVING_TASKS || speaking > 0 || mode == Mode.INFO || mode == Mode.ARRIVED) onStop()
            is Heard.RoomRequest -> {
                touch()
                helpWith(heard.room)
            }
            Heard.ListRooms -> if (!guiding) {
                touch()
                talk(Lines.listRooms(catalog.rooms))
            }
            Heard.Yes -> if (mode == Mode.INFO && infoVia != null) onWayButton() else if (!guiding) askBrain(text, catalog, null)
            Heard.No -> if (mode == Mode.INFO || mode == Mode.ARRIVED) {
                touch()
                talk(Lines.alright)
            }
            Heard.Thanks -> if (!guiding) {
                touch()
                talk(Lines.youAreWelcome)
            }
            Heard.Hello -> if (!guiding) {
                touch()
                talk(Lines.hello(settings.robotName))
            }
            Heard.Bye -> if (!guiding) {
                touch()
                talk(Lines.goodbye)
            }
            is Heard.Mention -> if (!guiding) askBrain(text, catalog, heard.room)
            Heard.Unknown -> if (!guiding) askBrain(text, catalog, null)
        }
    }

    /** Sentences the rules do not understand go to the language model (if switched on). */
    private fun askBrain(text: String, catalog: RoomCatalog, mention: Room?) {
        val mode = _ui.value.mode
        if (mode == Mode.CHARGING || mode == Mode.PAUSED) return
        val s = settings
        if (!s.useLlm) {
            fallback(text, mention)
            return
        }
        brainJob?.cancel()
        brainJob = scope.launch {
            val showThinking = mode != Mode.WANDERING && mode != Mode.RETURNING
            if (showThinking) setMood(Mood.THINKING)
            val context = BrainContext(s.robotName, s.venueName, s.eventInfo, s.robotFloor, catalog.rooms.map { it to catalog.planFor(it) })
            val reply = brain.interpret(text, context)
            if (showThinking && speaking == 0) setMood(restingMood(_ui.value.mode))
            if (!canInteract()) return@launch
            when (reply?.intent) {
                BrainIntent.ROOM -> {
                    val room = reply.roomId?.let { catalog.byId(it) }
                    if (room != null) {
                        touch()
                        helpWith(room)
                    } else {
                        fallback(text, mention)
                    }
                }
                BrainIntent.LIST_ROOMS -> {
                    touch()
                    talk(Lines.listRooms(catalog.rooms))
                }
                BrainIntent.STOP -> if (taskKind in MOVING_TASKS) onStop()
                BrainIntent.CHAT -> reply.reply?.let {
                    touch()
                    talk(it)
                }
                BrainIntent.IGNORE -> AppLog.i("Language model: not meant for me")
                null -> fallback(text, mention)
            }
        }
    }

    /** No language model: act on a mentioned room in a short sentence, otherwise ask again (not too often). */
    private fun fallback(text: String, mention: Room?) {
        if (mention != null && Text.words(text).size <= 6) {
            touch()
            helpWith(mention)
            return
        }
        val mode = _ui.value.mode
        if (mode == Mode.WANDERING || mode == Mode.RETURNING || mode == Mode.GUIDING) return
        val now = clock()
        if (now - lastFallback < FALLBACK_COOLDOWN_MS) return
        lastFallback = now
        touch()
        talk(Lines.didNotCatch)
    }

    // ================================================================== rooms

    private fun helpWith(room: Room) {
        val catalog = catalog()
        when (val plan = catalog.planFor(room)) {
            is RoomPlan.Walk -> guide(room, plan.point, otherFloors = null)
            is RoomPlan.OtherFloor -> showFloors(room, plan)
            RoomPlan.Describe -> describe(room)
        }
    }

    /**
     * Why the robot cannot drive right now (spoken), null = it can. Localization is deliberately not checked
     * here: the drive itself reports "not localized" reliably, a status query might not.
     */
    private fun driveBlocker(): String? = when {
        !robot.state.value.connected || suspended -> Lines.reasonOffline
        battery?.let { it <= CRITICAL_BATTERY } == true -> Lines.reasonBattery
        else -> null
    }

    private fun guide(room: Room, point: String, otherFloors: List<Int>?) {
        if (taskKind == Task.GUIDE && guidingTo == point) {
            AppLog.i("Already on the way to $point")
            return
        }
        startTask(Task.GUIDE) {
            val s = settings
            val blocker = driveBlocker()
            if (blocker != null) {
                showCard(room, if (otherFloors != null) floorBadges(otherFloors) else emptyList(), room.directions, mood = Mood.SAD)
                say(Lines.cannotDrive(room, blocker))
                delay(INFO_TIMEOUT_MS)
                show(Mode.HOME)
                return@startTask
            }
            guidingTo = point
            charging = false
            try {
                show(Mode.GUIDING) {
                    copy(
                        focus = room,
                        focusEmoji = room.resolvedIcon.emoji,
                        title = Lines.followMeTitle,
                        infoText = if (otherFloors != null) "${Text.the(point).replaceFirstChar { it.titlecase() }} → ${Floors.short(otherFloors)}" else "",
                        mood = Mood.JOY,
                    )
                }
                val intro = launch { say(if (otherFloors != null) Lines.followMeToStairs(point, room) else Lines.followMe(room)) }
                val reminder = launch {
                    delay(REMINDER_FIRST_MS)
                    while (isActive) {
                        if (speaking == 0) say(Lines.thisWay)
                        delay(REMINDER_EVERY_MS)
                    }
                }
                val startedAt = clock()
                val result = drive(point, s.guideSpeed, GUIDE_TOLERANCE, guiding = true)
                reminder.cancel()
                when (result) {
                    NavResult.Arrived -> {
                        if (clock() - startedAt < ALREADY_THERE_MS && otherFloors == null) {
                            // The robot was standing at the point already
                            intro.cancel()
                            voice.stop()
                            touch()
                            showCard(room, emptyList(), room.directions, mood = Mood.JOY)
                            say(Lines.alreadyHere(room))
                            delay(s.arrivalWaitSec * 1000L)
                            show(Mode.HOME)
                            return@startTask
                        }
                        intro.join()
                        arrived(room, point, otherFloors)
                    }
                    NavResult.Cancelled -> {
                        intro.cancel()
                        show(Mode.HOME)
                    }
                    is NavResult.Failed -> {
                        intro.cancel()
                        AppLog.w("Guiding to '$point' failed: ${result.code} ${result.message}")
                        showCard(room, if (otherFloors != null) floorBadges(otherFloors) else emptyList(), room.directions, mood = Mood.SAD)
                        say(if (result.notLocalized) Lines.cannotDrive(room, Lines.reasonNotLocalized) else Lines.navFailed(room))
                        delay(FAILED_CARD_MS)
                        show(Mode.HOME)
                    }
                }
            } finally {
                guidingTo = null
            }
        }
    }

    private suspend fun arrived(room: Room, point: String, otherFloors: List<Int>?) {
        val s = settings
        touch()
        show(Mode.ARRIVED) {
            copy(
                focus = room,
                focusEmoji = room.resolvedIcon.emoji,
                title = Lines.arrivedTitle,
                infoText = if (otherFloors != null) Lines.arrivedVia(point, room, otherFloors, s.robotFloor) else room.name,
                floors = if (otherFloors != null) floorBadges(otherFloors) else emptyList(),
                mood = Mood.JOY,
            )
        }
        say(if (otherFloors != null) Lines.arrivedVia(point, room, otherFloors, s.robotFloor) else Lines.arrived(room, s.robotName))
        delay(s.arrivalWaitSec * 1000L)
        afterVisitor()
    }

    /** After a visitor has been helped: continue the tour, or go back to the home point. */
    private fun afterVisitor() {
        val home = catalog().homePoint
        when {
            wanderAllowed() -> startWander()
            home != null && home != planner.current -> returnHome()
            else -> show(Mode.HOME)
        }
    }

    private fun showFloors(room: Room, plan: RoomPlan.OtherFloor) = startTask(Task.INFO) {
        val s = settings
        val via = plan.via?.takeIf { driveBlocker() == null }
        infoVia = via
        infoFloors = plan.floors
        showCard(room, floorBadges(plan.floors), room.directions, wayButton = via?.let { Lines.showWayButton(it) })
        val sentence = buildString {
            append(Lines.floorAnswer(room, plan.floors, s.robotFloor))
            if (room.directions.isNotBlank()) append(' ').append(room.directions.trim())
            if (via != null) append(' ').append(Lines.offerWay(via))
        }
        say(sentence)
        delay(INFO_TIMEOUT_MS)
        show(Mode.HOME)
    }

    private fun describe(room: Room) = startTask(Task.INFO) {
        infoVia = null
        showCard(room, listOf(FloorBadge(Floors.badge(settings.robotFloor), "This floor", null)), room.directions)
        say(Lines.describeOnly(room))
        delay(INFO_TIMEOUT_MS)
        show(Mode.HOME)
    }

    private fun showCard(room: Room, floors: List<FloorBadge>, text: String, wayButton: String? = null, mood: Mood? = null) {
        show(Mode.INFO) {
            copy(
                focus = room,
                focusEmoji = room.resolvedIcon.emoji,
                title = room.name,
                floors = floors,
                infoText = text,
                wayButton = wayButton,
                mood = mood ?: this.mood,
            )
        }
    }

    private fun floorBadges(floors: List<Int>): List<FloorBadge> {
        val from = settings.robotFloor
        return floors.distinct().sortedDescending().map {
            FloorBadge(Floors.badge(it), Floors.caption(it), up = if (it > from) true else if (it < from) false else null)
        }
    }

    // ================================================================== wandering

    private fun wanderAllowed(): Boolean = foreground && !adminOpen && wanderBlocker() == null

    private fun shouldAutoWander(): Boolean {
        if (activeTasks.any { it.isActive } || _ui.value.mode != Mode.HOME) return false
        if (speaking > 0 || brainJob?.isActive == true) return false
        if (!wanderAllowed()) return false
        val idleSince = maxOf(lastInteraction, lastPresence)
        return clock() - idleSince >= settings.idleBeforeWanderSec * 1000L
    }

    private fun startWander() = startTask(Task.WANDER) {
        AppLog.i("Wandering starts")
        var failuresInRow = 0
        while (isActive) {
            if (!wanderAllowed()) break
            val s = settings
            val next = planner.next(catalog().wanderPoints, s.wanderRandom) ?: break
            show(Mode.WANDERING) { copy(title = Lines.wanderTitle) }
            when (val result = drive(next, s.wanderSpeed, WANDER_TOLERANCE, guiding = false)) {
                NavResult.Arrived -> {
                    planner.arrived(next)
                    failuresInRow = 0
                    pauseAtWanderPoint()
                }
                NavResult.Cancelled -> break
                is NavResult.Failed -> {
                    planner.failed(next)
                    failuresInRow++
                    AppLog.w("Wander to '$next' failed: ${result.code} ${result.message}")
                    if (result.notLocalized) break
                    if (result.code == NavResult.SUSPENDED || result.code == NavResult.NOT_CONNECTED) break
                    _ui.update { it.copy(navNote = "") }
                    delay(if (failuresInRow >= 3) WANDER_BACKOFF_MS else WANDER_RETRY_MS)
                }
            }
        }
        AppLog.i("Wandering ends")
        touch()
        show(Mode.HOME)
    }

    private suspend fun pauseAtWanderPoint() {
        val s = settings
        _ui.update { it.copy(navNote = "") }
        val now = clock()
        if (s.announcements && now - lastAnnouncement >= ANNOUNCEMENT_COOLDOWN_MS && robot.facesWithin(ANNOUNCEMENT_DISTANCE) > 0) {
            lastAnnouncement = now
            say(Lines.announcement(s.robotName))
        }
        delay(s.wanderPauseSec * 1000L)
    }

    private fun returnHome() = startTask(Task.RETURN) {
        val home = catalog().homePoint
        if (home == null || home == planner.current) {
            show(Mode.HOME)
            return@startTask
        }
        show(Mode.RETURNING) { copy(title = Lines.wanderTitle) }
        val result = drive(home, settings.wanderSpeed, WANDER_TOLERANCE, guiding = false)
        if (result == NavResult.Arrived) planner.arrived(home)
        touch()
        show(Mode.HOME)
    }

    // ================================================================== battery

    private fun maybeCharge() {
        val s = settings
        val b = battery ?: return
        if (s.autoChargeBelow <= 0 || b > s.autoChargeBelow || charging) return
        if (!canInteract() || clock() < chargeRetryAt) return
        val mode = _ui.value.mode
        if (mode != Mode.HOME && mode != Mode.WANDERING) return
        if (taskKind != Task.NONE && taskKind != Task.WANDER) return
        startTask(Task.CHARGE) {
            if (!robot.hasChargingPile()) {
                AppLog.w("Battery low, but there is no charging pile")
                chargeRetryAt = clock() + 30 * 60_000L
                show(Mode.HOME)
                return@startTask
            }
            show(Mode.CHARGING) { copy(title = Lines.chargingTitle, infoText = Lines.chargingText, mood = Mood.SLEEPY) }
            say(Lines.goingToCharge)
            val result = robot.goCharge()
            chargeRetryAt = clock() + 10 * 60_000L
            planner.moved(null)
            if (result == NavResult.Arrived) {
                charging = true
                show(Mode.CHARGING) { copy(title = Lines.chargingTitle, infoText = "", mood = Mood.SLEEPY) }
            } else {
                AppLog.w("Going to charge failed: $result")
                say(Lines.chargeFailed)
                show(Mode.HOME)
            }
        }
    }

    // ================================================================== watchers

    private fun startWatchers() {
        stopWatchers()
        watchers += scope.launch { connectLoop() }
        watchers += scope.launch { placesLoop() }
        watchers += scope.launch { statusLoop() }
        watchers += scope.launch { idleLoop() }
        watchers += scope.launch { presenceLoop() }
    }

    private fun stopWatchers() {
        watchers.forEach { it.cancel() }
        watchers.clear()
    }

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            delay(10_000)
            if (!robot.state.value.connected) robot.connect()
        }
    }

    private suspend fun placesLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val ok = loadPlaces()
            failures = if (ok) 0 else failures + 1
            delay(if (ok) PLACES_REFRESH_MS else minOf(3_000L * (failures + 1), 30_000L))
        }
    }

    private suspend fun loadPlaces(): Boolean {
        if (!robot.state.value.connected) return false
        val loaded = robot.places() ?: return false
        if (loaded != places) {
            AppLog.i("Map points: ${loaded.joinToString { it.name }.ifEmpty { "(none)" }}")
            places = loaded
            refreshTiles()
            updateBanner()
        }
        return true
    }

    private suspend fun statusLoop() {
        while (currentCoroutineContext().isActive) {
            robot.battery()?.let { battery = it }
            robot.isLocalized()?.let { localized = it }
            val s = settings
            if (charging && (battery ?: 0) >= CHARGED_LEVEL) charging = false
            if (battery?.let { it >= s.wanderMinBattery } == true && _ui.value.mode == Mode.CHARGING && taskKind != Task.CHARGE) show(Mode.HOME)
            _ui.update { it.copy(battery = battery) }
            updateBanner()
            maybeCharge()
            delay(STATUS_POLL_MS)
        }
    }

    private suspend fun idleLoop() {
        while (currentCoroutineContext().isActive) {
            delay(1_000)
            if (shouldAutoWander()) startWander()
        }
    }

    private suspend fun presenceLoop() {
        while (currentCoroutineContext().isActive) {
            delay(PRESENCE_POLL_MS)
            val s = settings
            val mode = _ui.value.mode
            if (!canInteract() || !s.greetPeople || (mode != Mode.HOME && mode != Mode.WANDERING)) {
                presentPolls = 0
                continue
            }
            val faces = robot.facesWithin(s.greetDistance)
            val now = clock()
            if (faces > 0) {
                presentPolls++
                absentPolls = 0
                lastPresence = now
            } else {
                absentPolls++
                presentPolls = 0
            }
            if (absentPolls >= 6) greetedCurrentVisitor = false
            val quiet = speaking == 0 && (taskKind == Task.NONE || taskKind == Task.WANDER) && brainJob?.isActive != true
            if (presentPolls >= 2 && !greetedCurrentVisitor && quiet &&
                now - lastGreet >= GREET_COOLDOWN_MS && now - lastInteraction >= GREET_AFTER_INTERACTION_MS
            ) {
                greetedCurrentVisitor = true
                lastGreet = now
                AppLog.i("Someone is in front of me: greeting")
                startTask(Task.TALK) {
                    show(Mode.HOME)
                    touch()
                    say(Lines.greeting(settings.robotName))
                }
            }
        }
    }

    private fun onRobotState(state: RobotState) {
        val wasSuspended = suspended
        suspended = state.suspended
        if (state.suspended && !wasSuspended) {
            AppLog.w("Paused by RobotOS")
            cancelTask()
            voice.stop()
            show(Mode.PAUSED) { copy(title = Lines.pausedTitle, infoText = Lines.pausedText, mood = Mood.SLEEPY) }
        } else if (!state.suspended && wasSuspended) {
            touch()
            planner.moved(null)
            show(Mode.HOME)
        }
        if (state.connected && places == null && foreground) scope.launch { loadPlaces() }
        updateBanner()
    }

    // ================================================================== helpers

    private fun canInteract() = foreground && !adminOpen && !suspended

    private fun touch() {
        lastInteraction = clock()
    }

    private fun catalog() = RoomCatalog(settings, places)

    /**
     * Runs [block] as the only task. Older tasks are cancelled right away (that stops the wheels) and the new
     * task waits until all of them have really finished, so two drives never overlap - even when several
     * inputs arrive at once (a touch, then the tap on a tile).
     */
    private fun startTask(kind: Task, block: suspend CoroutineScope.() -> Unit) {
        activeTasks.removeAll { it.isCompleted }
        val previous = activeTasks.toList()
        previous.forEach { it.cancel() }
        brainJob?.cancel()
        asideJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous.forEach { it.join() }
            taskKind = kind
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.e("Task $kind failed", t)
                show(Mode.HOME)
            } finally {
                if (task === coroutineContext[Job]) taskKind = Task.NONE
            }
        }
        activeTasks += job
        task = job
        job.start()
    }

    private fun cancelTask() {
        activeTasks.forEach { it.cancel() }
        taskKind = Task.NONE
        brainJob?.cancel()
        asideJob?.cancel()
    }

    private fun talk(text: String) = startTask(Task.TALK) {
        show(Mode.HOME)
        say(text)
    }

    private suspend fun say(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        speaking++
        echo.started(t)
        captionJob?.cancel()
        _ui.update { it.copy(caption = t, mood = if (it.mood == Mood.JOY || it.mood == Mood.SAD) it.mood else Mood.SPEAKING) }
        try {
            voice.say(t)
        } finally {
            speaking--
            echo.ended(t)
            if (speaking == 0) {
                _ui.update { it.copy(mood = restingMood(it.mode, it.mood)) }
                captionJob = scope.launch {
                    delay(CAPTION_KEEP_MS)
                    if (speaking == 0) _ui.update { it.copy(caption = "") }
                }
            }
        }
    }

    /** Short remark while a task keeps running ("Excuse me, may I pass?"). */
    private fun sayAside(text: String) {
        if (speaking > 0) return
        asideJob?.cancel()
        asideJob = scope.launch { say(text) }
    }

    private suspend fun drive(point: String, speed: Double, tolerance: Double, guiding: Boolean): NavResult {
        val result = robot.navigate(point, speed, tolerance) { event -> scope.launch { onNavEvent(event, guiding) } }
        _ui.update { it.copy(navNote = "") }
        if (result is NavResult.Failed && result.notLocalized) {
            notLocalizedUntil = clock() + NOT_LOCALIZED_PAUSE_MS
            updateBanner()
        } else if (result == NavResult.Arrived && notLocalizedUntil != 0L) {
            notLocalizedUntil = 0L
            updateBanner()
        }
        if (guiding) planner.moved(if (result == NavResult.Arrived) point else null)
        return result
    }

    private fun onNavEvent(event: NavEvent, guiding: Boolean) {
        val note = when (event) {
            NavEvent.BLOCKED -> Lines.navBlocked
            NavEvent.DESTINATION_OCCUPIED -> Lines.navOccupied
            NavEvent.WAITING_FOR_ROBOT -> Lines.navWaitingRobot
            NavEvent.LOCALIZATION_LOST -> {
                AppLog.w("Localization lost while driving")
                ""
            }
            else -> ""
        }
        if (taskKind in MOVING_TASKS) _ui.update { it.copy(navNote = note) }
        val now = clock()
        if (event == NavEvent.BLOCKED && taskKind in MOVING_TASKS && now - lastBlockedSpeech >= BLOCKED_SPEECH_COOLDOWN_MS) {
            lastBlockedSpeech = now
            sayAside(Lines.excuseMe)
        }
    }

    private fun setHeard(text: String) {
        heardJob?.cancel()
        _ui.update { it.copy(heard = text) }
        heardJob = scope.launch {
            delay(HEARD_KEEP_MS)
            _ui.update { it.copy(heard = "") }
        }
    }

    private fun setMood(mood: Mood) = _ui.update { it.copy(mood = mood) }

    private fun restingMood(mode: Mode, current: Mood? = null): Mood = when (mode) {
        Mode.ARRIVED -> Mood.JOY
        Mode.PAUSED, Mode.CHARGING -> Mood.SLEEPY
        Mode.INFO -> if (current == Mood.SAD) Mood.SAD else Mood.HAPPY
        else -> Mood.HAPPY
    }

    private fun refreshTiles() {
        val catalog = catalog()
        _ui.update { it.copy(tiles = tilesFor(catalog), robotName = settings.robotName) }
    }

    private fun tilesFor(catalog: RoomCatalog): List<Tile> = catalog.rooms.map { room ->
        val plan = catalog.planFor(room)
        val hint = when (plan) {
            is RoomPlan.Walk -> Lines.walkMe
            is RoomPlan.OtherFloor -> Floors.short(plan.floors)
            RoomPlan.Describe -> Lines.thisFloor
        }
        Tile(room.id, room.name, room.resolvedIcon.emoji, hint, plan is RoomPlan.Walk)
    }

    /** Switches the screen; card fields are cleared unless [build] sets them. */
    private fun show(mode: Mode, build: UiState.() -> UiState = { this }) {
        val s = settings
        val catalog = catalog()
        _ui.update { old ->
            old.copy(
                mode = mode,
                robotName = s.robotName,
                tiles = tilesFor(catalog),
                title = when (mode) {
                    Mode.HOME -> Lines.homeTitle
                    Mode.WANDERING, Mode.RETURNING -> Lines.wanderTitle
                    else -> old.title
                },
                status = when (mode) {
                    Mode.HOME, Mode.INFO, Mode.ARRIVED -> Lines.statusReady
                    Mode.WANDERING -> Lines.statusWandering
                    Mode.GUIDING -> Lines.statusGuiding
                    Mode.RETURNING -> Lines.statusReturning
                    Mode.CHARGING -> Lines.chargingTitle
                    Mode.PAUSED -> Lines.pausedTitle
                },
                focus = null,
                focusEmoji = "",
                floors = emptyList(),
                infoText = "",
                wayButton = null,
                navNote = "",
                mood = if (speaking > 0) old.mood else restingMood(mode),
                simulated = robot.state.value.simulated,
            ).build()
        }
        if (mode != Mode.INFO) infoVia = null
    }

    private fun updateBanner() {
        val st = robot.state.value
        val s = settings
        val b = battery
        val text = when {
            st.simulated -> "Demo mode: no robot connected, driving is simulated"
            !st.connected -> "Connecting to the robot…"
            localized == false || clock() < notLocalizedUntil -> "I am not localized on my map. Staff: please relocalize me with the map tool."
            places?.isEmpty() == true -> "There are no points on my map yet. Staff: add points with the map tool."
            b != null && b < s.wanderMinBattery -> "Battery low ($b %): I will stay here and help."
            else -> null
        }
        if (_ui.value.banner != text) _ui.update { it.copy(banner = text) }
    }

    companion object {
        const val GUIDE_TOLERANCE = 0.35
        const val WANDER_TOLERANCE = 0.6

        private const val LONG_AGO = Long.MIN_VALUE / 4
        private const val CRITICAL_BATTERY = 6
        private const val CHARGED_LEVEL = 80
        const val NOT_LOCALIZED_PAUSE_MS = 120_000L
        const val DUPLICATE_SPEECH_MS = 1_500L
        const val ALREADY_THERE_MS = 1_500L

        const val INFO_TIMEOUT_MS = 25_000L
        const val FAILED_CARD_MS = 8_000L
        const val TOUCH_GREETING_DELAY_MS = 700L
        const val GREET_COOLDOWN_MS = 25_000L
        const val GREET_AFTER_INTERACTION_MS = 8_000L
        const val ANNOUNCEMENT_COOLDOWN_MS = 180_000L
        const val ANNOUNCEMENT_DISTANCE = 3.0
        const val FALLBACK_COOLDOWN_MS = 12_000L
        const val BLOCKED_SPEECH_COOLDOWN_MS = 15_000L
        const val REMINDER_FIRST_MS = 30_000L
        const val REMINDER_EVERY_MS = 40_000L
        const val WANDER_RETRY_MS = 4_000L
        const val WANDER_BACKOFF_MS = 60_000L
        const val PRESENCE_POLL_MS = 500L
        const val STATUS_POLL_MS = 20_000L
        const val PLACES_REFRESH_MS = 60_000L
        const val CAPTION_KEEP_MS = 8_000L
        const val HEARD_KEEP_MS = 6_000L

        private val MOVING_TASKS = setOf(Task.GUIDE, Task.WANDER, Task.RETURN, Task.CHARGE)
    }
}
