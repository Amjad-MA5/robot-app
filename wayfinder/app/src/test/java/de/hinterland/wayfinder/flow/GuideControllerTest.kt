package de.hinterland.wayfinder.flow

import de.hinterland.wayfinder.core.Brain
import de.hinterland.wayfinder.core.BrainContext
import de.hinterland.wayfinder.core.BrainIntent
import de.hinterland.wayfinder.core.BrainReply
import de.hinterland.wayfinder.core.Lines
import de.hinterland.wayfinder.core.MapPlace
import de.hinterland.wayfinder.core.Settings
import de.hinterland.wayfinder.robot.NavEvent
import de.hinterland.wayfinder.robot.NavResult
import de.hinterland.wayfinder.robot.Robot
import de.hinterland.wayfinder.robot.RobotState
import de.hinterland.wayfinder.robot.Voice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

private class FakeRobot : Robot {
    override val state = MutableStateFlow(RobotState(connected = true))
    var map: List<MapPlace>? = listOf(
        MapPlace("Reception", type = MapPlace.TYPE_RECEPTION),
        MapPlace("Main Stage"),
        MapPlace("Coffee Bar"),
        MapPlace("Stairs"),
        MapPlace("Charging Pile", type = MapPlace.TYPE_CHARGING),
    )
    val drives = mutableListOf<String>()
    val results = ArrayDeque<NavResult>()
    var driveMs = 5_000L
    var faces = 0
    var batteryLevel: Int? = 80
    var localizedValue: Boolean? = true
    var stops = 0
    var charges = 0
    /** Drive that is running right now (null = standing) */
    var driving: String? = null
    private var activeDrives = 0
    /** Two drives at the same time would be a bug (the real robot would get conflicting commands) */
    var maxConcurrentDrives = 0

    override suspend fun places() = map

    override suspend fun navigate(place: String, speed: Double, tolerance: Double, onEvent: (NavEvent) -> Unit): NavResult {
        drives += place
        driving = place
        activeDrives++
        maxConcurrentDrives = maxOf(maxConcurrentDrives, activeDrives)
        try {
            onEvent(NavEvent.STARTED)
            delay(driveMs)
            return results.removeFirstOrNull() ?: NavResult.Arrived
        } finally {
            activeDrives--
            if (activeDrives == 0) driving = null
        }
    }

    override fun stopNavigation() {
        stops++
    }

    override suspend fun facesWithin(meters: Double) = faces
    override suspend fun battery() = batteryLevel
    override suspend fun isLocalized() = localizedValue
    override suspend fun hasChargingPile() = true

    override suspend fun goCharge(): NavResult {
        charges++
        delay(2_000)
        return NavResult.Arrived
    }

    override fun resetHead() {}
}

private class FakeVoice : Voice {
    val said = mutableListOf<String>()
    override suspend fun say(text: String) {
        said += text
        delay(1_000)
    }

    override fun stop() {}
}

private class FakeBrain : Brain {
    var reply: BrainReply? = null
    var calls = 0
    override suspend fun interpret(utterance: String, context: BrainContext): BrainReply? {
        calls++
        delay(300)
        return reply
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GuideControllerTest {

    private val robot = FakeRobot()
    private val voice = FakeVoice()
    private val brain = FakeBrain()
    private var settings = Settings(idleBeforeWanderSec = 45, wanderPauseSec = 10, arrivalWaitSec = 12, greetPeople = false, announcements = false)

    @Before
    fun fixedRandom() {
        Lines.random = Random(3)
    }

    @After
    fun neverTwoDrivesAtOnce() {
        assertTrue("Two drives overlapped", robot.maxConcurrentDrives <= 1)
    }

    private fun TestScope.controller(): GuideController {
        val c = GuideController(backgroundScope, robot, voice, brain, { settings }, { testScheduler.currentTime })
        c.onForeground()
        runCurrent()
        return c
    }

    private fun TestScope.step(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun restroomsAreAnnouncedWithTheirFloorsWithoutDriving() = runTest {
        val c = controller()
        c.onRoomTapped(Settings.RESTROOM_ID)
        step(100)
        val ui = c.ui.value
        assertEquals(Mode.INFO, ui.mode)
        assertEquals(listOf("1", "−1"), ui.floors.map { it.label })
        assertEquals(null, ui.wayButton)
        assertTrue(robot.drives.isEmpty())
        assertTrue(voice.said.last(), voice.said.last().contains("first floor, one floor up, or in the basement, one floor down"))
        // The card closes on its own
        step(GuideController.INFO_TIMEOUT_MS + 2_000)
        assertEquals(Mode.HOME, c.ui.value.mode)
    }

    @Test
    fun visitorIsGuidedAndTheRobotThenContinuesWandering() = runTest {
        val c = controller()
        c.onRoomTapped("map:Main Stage")
        step(100)
        assertEquals(Mode.GUIDING, c.ui.value.mode)
        assertEquals(listOf("Main Stage"), robot.drives)
        step(5_000)
        assertEquals(Mode.ARRIVED, c.ui.value.mode)
        assertTrue(voice.said.any { it.contains("Main Stage") && !it.contains("follow", ignoreCase = true) })
        step(1_000 + 12_000 + 100)
        assertEquals(Mode.WANDERING, c.ui.value.mode)
        assertEquals("Coffee Bar", robot.drives.last()) // next point after the Main Stage
    }

    @Test
    fun otherFloorWithStairsPointOffersTheWay() = runTest {
        settings = settings.copy(stairsPoint = "Stairs")
        val c = controller()
        c.onSpeech("Excuse me, where are the toilets?", true)
        step(100)
        assertEquals(Mode.INFO, c.ui.value.mode)
        assertEquals(Lines.showWayButton("Stairs"), c.ui.value.wayButton)
        assertTrue(voice.said.last().contains("Shall I walk you to the Stairs?"))
        c.onSpeech("yes please", true)
        step(100)
        assertEquals(Mode.GUIDING, c.ui.value.mode)
        assertEquals(listOf("Stairs"), robot.drives)
        step(5_000)
        assertEquals(Mode.ARRIVED, c.ui.value.mode)
        assertTrue(voice.said.any { it.startsWith("This is the Stairs. The Restrooms are on the first floor") })
    }

    @Test
    fun idleRobotStartsWanderingAndATouchStopsIt() = runTest {
        val c = controller()
        step(44_000)
        assertEquals(Mode.HOME, c.ui.value.mode)
        step(2_000)
        assertEquals(Mode.WANDERING, c.ui.value.mode)
        assertEquals("Reception", robot.driving)
        c.onAnyTouch()
        step(50)
        assertEquals(Mode.HOME, c.ui.value.mode)
        assertEquals(null, robot.driving)
        assertTrue(robot.stops >= 1)
        step(GuideController.TOUCH_GREETING_DELAY_MS + 100)
        assertEquals(Lines.touchedWhileWalking, voice.said.last())
        // No new tour while the visitor is busy with the screen
        step(30_000)
        assertEquals(Mode.HOME, c.ui.value.mode)
    }

    @Test
    fun tapOnATileWhileWanderingGoesStraightToTheRoom() = runTest {
        val c = controller()
        step(46_000)
        assertEquals(Mode.WANDERING, c.ui.value.mode)
        c.onAnyTouch() // the touch arrives first ...
        c.onRoomTapped("map:Coffee Bar") // ... then the tap on the tile
        step(100)
        assertEquals(Mode.GUIDING, c.ui.value.mode)
        assertEquals("Coffee Bar", robot.driving)
        assertTrue(voice.said.none { it == Lines.touchedWhileWalking })
    }

    @Test
    fun touchFollowedByAdminScreenStopsTheTourCompletely() = runTest {
        val c = controller()
        step(46_000)
        assertEquals("Reception", robot.driving)
        c.onAnyTouch() // schedules the short greeting ...
        c.setAdminOpen(true) // ... which is cancelled before it ever runs
        step(50)
        assertEquals(null, robot.driving)
        step(60_000)
        assertEquals(listOf("Reception"), robot.drives)
    }

    @Test
    fun wanderRouteSkipsAFailingPoint() = runTest {
        settings = settings.copy(wanderPoints = listOf("Main Stage", "Coffee Bar", "Stairs"))
        robot.results.addAll(listOf(NavResult.Arrived, NavResult.Failed(-109, "blocked")))
        val c = controller()
        step(46_000) // starts: Main Stage (arrives after 5 s)
        step(5_000 + 10_000) // pause, then Coffee Bar (fails after 5 s)
        step(5_000 + GuideController.WANDER_RETRY_MS + 100)
        assertEquals(listOf("Main Stage", "Coffee Bar", "Stairs"), robot.drives)
        assertEquals(Mode.WANDERING, c.ui.value.mode)
    }

    @Test
    fun stopButtonStopsTheWheels() = runTest {
        val c = controller()
        c.onRoomTapped("map:Main Stage")
        step(1_000)
        assertEquals("Main Stage", robot.driving)
        c.onStop()
        step(50)
        assertEquals(Mode.HOME, c.ui.value.mode)
        assertEquals(null, robot.driving)
        assertTrue(robot.stops >= 1)
        assertEquals(Lines.stopped, voice.said.last())
    }

    @Test
    fun voiceStopWhileGuiding() = runTest {
        val c = controller()
        c.onSpeech("take me to the coffee bar", true)
        step(1_500)
        assertEquals(Mode.GUIDING, c.ui.value.mode)
        c.onSpeech("stop", true)
        step(50)
        assertEquals(Mode.HOME, c.ui.value.mode)
        assertEquals(null, robot.driving)
    }

    @Test
    fun failedDriveIsExplained() = runTest {
        robot.results.add(NavResult.Failed(-109, "The way is blocked"))
        val c = controller()
        c.onRoomTapped("map:Main Stage")
        step(5_100)
        assertEquals(Mode.INFO, c.ui.value.mode)
        assertEquals(Mood.SAD, c.ui.value.mood)
        assertTrue(voice.said.last().startsWith("I am sorry, I cannot get to the Main Stage right now."))
        step(1_000 + GuideController.FAILED_CARD_MS + 100)
        assertEquals(Mode.HOME, c.ui.value.mode)
    }

    @Test
    fun notLocalizedRobotExplainsItAndPausesWandering() = runTest {
        robot.results.add(NavResult.Failed(NavResult.NOT_LOCALIZED, "The robot is not localized"))
        val c = controller()
        c.onRoomTapped("map:Main Stage")
        step(5_100)
        assertTrue(voice.said.last(), voice.said.last().contains("I do not know where I am on my map"))
        assertTrue(c.ui.value.banner.orEmpty().contains("not localized"))
        step(100_000)
        assertEquals(listOf("Main Stage"), robot.drives) // no wandering right after the failure
        step(GuideController.NOT_LOCALIZED_PAUSE_MS)
        assertTrue(robot.drives.size > 1) // tries again later; a successful drive clears the warning
        step(6_000)
        assertEquals(null, c.ui.value.banner)
    }

    @Test
    fun localizationQueryOnlyWarnsTheStaff() = runTest {
        robot.localizedValue = false
        val c = controller()
        assertTrue(c.ui.value.banner.orEmpty().contains("not localized"))
        c.onRoomTapped("map:Main Stage")
        step(100)
        assertEquals(listOf("Main Stage"), robot.drives) // a possibly wrong status never blocks guiding
    }

    @Test
    fun systemTakeoverPausesEverything() = runTest {
        val c = controller()
        c.onRoomTapped("map:Main Stage")
        step(1_000)
        robot.state.value = robot.state.value.copy(suspended = true)
        step(50)
        assertEquals(Mode.PAUSED, c.ui.value.mode)
        assertEquals(null, robot.driving)
        c.onSpeech("where is the main stage", true)
        step(100)
        assertEquals(Mode.PAUSED, c.ui.value.mode)
        robot.state.value = robot.state.value.copy(suspended = false)
        step(50)
        assertEquals(Mode.HOME, c.ui.value.mode)
    }

    @Test
    fun smallTalkGoesToTheLanguageModel() = runTest {
        brain.reply = BrainReply(BrainIntent.CHAT, null, "I am a guide robot. Nice to meet you!")
        val c = controller()
        c.onSpeech("Who built you?", true)
        step(400)
        assertEquals(1, brain.calls)
        assertEquals("I am a guide robot. Nice to meet you!", voice.said.last())
    }

    @Test
    fun languageModelCanPickARoom() = runTest {
        brain.reply = BrainReply(BrainIntent.ROOM, "map:Coffee Bar", null)
        val c = controller()
        c.onSpeech("I am so thirsty, I need a cappuccino", true)
        step(400)
        assertEquals(Mode.GUIDING, c.ui.value.mode)
        assertEquals("Coffee Bar", robot.driving)
    }

    @Test
    fun withoutLanguageModelTheRobotAsksAgainButNotTooOften() = runTest {
        brain.reply = null
        val c = controller()
        c.onSpeech("Blah blah something", true)
        step(400)
        assertEquals(Lines.didNotCatch, voice.said.last())
        val count = voice.said.size
        c.onSpeech("More noise here", true)
        step(400)
        assertEquals(count, voice.said.size)
    }

    @Test
    fun ownVoiceIsIgnored() = runTest {
        val c = controller()
        c.onRoomTapped(Settings.RESTROOM_ID)
        step(10) // the robot is speaking the floor answer now
        c.onSpeech("the restrooms are on the first floor one floor up", true)
        step(100)
        assertEquals(Mode.INFO, c.ui.value.mode)
        assertEquals(1, voice.said.size)
    }

    @Test
    fun visitorInFrontOfTheWanderingRobotIsGreeted() = runTest {
        settings = settings.copy(greetPeople = true)
        val c = controller()
        step(46_000)
        assertEquals(Mode.WANDERING, c.ui.value.mode)
        robot.faces = 1
        step(1_100)
        assertEquals(Mode.HOME, c.ui.value.mode)
        assertEquals(null, robot.driving)
        assertTrue(voice.said.last().contains("I am Norra") || voice.said.last().contains("help you find"))
    }

    @Test
    fun lowBatteryDrivesToTheCharger() = runTest {
        robot.batteryLevel = 10
        val c = controller()
        step(100)
        assertEquals(Mode.CHARGING, c.ui.value.mode)
        step(3_100)
        assertEquals(1, robot.charges)
        assertEquals(Mode.CHARGING, c.ui.value.mode)
        step(120_000)
        assertTrue(robot.drives.isEmpty()) // no wandering while charging
    }

    @Test
    fun backgroundStopsEverything() = runTest {
        val c = controller()
        c.onRoomTapped("map:Main Stage")
        step(1_000)
        c.onBackground()
        step(50)
        assertEquals(null, robot.driving)
        step(120_000)
        assertEquals(listOf("Main Stage"), robot.drives)
    }

    @Test
    fun alreadyAtTheDestination() = runTest {
        robot.driveMs = 0
        val c = controller()
        c.onRoomTapped("map:Main Stage")
        step(100)
        assertEquals(Lines.alreadyHere(c.ui.value.focus!!), voice.said.last())
    }

    @Test
    fun sameSentenceTwiceIsHandledOnce() = runTest {
        val c = controller()
        c.onSpeech("Where are the restrooms?", true)
        c.onSpeech("Where are the restrooms?", true)
        step(100)
        assertEquals(1, voice.said.size)
        assertEquals(Mode.INFO, c.ui.value.mode)
    }

    @Test
    fun newRequestWhileGuidingSwitchesTheDestination() = runTest {
        val c = controller()
        c.onRoomTapped("map:Main Stage")
        step(1_000)
        c.onSpeech("No wait, take me to the coffee bar", true)
        step(100)
        assertEquals("Coffee Bar", robot.driving)
        assertEquals("Coffee Bar", c.ui.value.focus?.name)
    }
}
