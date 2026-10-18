package de.hinterland.wayfinder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAndParsingTest {

    @Test
    fun settingsRoundTrip() {
        val s = Settings(
            robotName = "Norra",
            rooms = Settings.defaultRooms() + Room("hall", "Main Hall", mapPoint = "Hall", floors = listOf(0), directions = "Straight ahead.", icon = RoomIcon.STAGE),
            hiddenMapPoints = setOf("Storage"),
            robotFloor = 0,
            stairsPoint = "Stairs",
            homePoint = Settings.HOME_NONE,
            wanderPoints = listOf("A", "B"),
            wanderRandom = true,
            guideSpeed = 0.55,
            eventInfo = "Pitches at 2 pm.",
        )
        assertEquals(s, Settings.fromJson(s.toJson()))
    }

    @Test
    fun brokenOrMissingSettingsFallBackToDefaults() {
        assertEquals(Settings(), Settings.fromJson(null))
        assertEquals(Settings(), Settings.fromJson("not json"))
        val partial = Settings.fromJson("""{"robotName":"Robo","guideSpeed":5}""")
        assertEquals("Robo", partial.robotName)
        assertEquals(0.8, partial.guideSpeed, 1e-9) // clamped
        assertEquals(Settings.defaultRooms(), partial.rooms)
        assertNull(partial.wanderPoints)
    }

    @Test
    fun placeList() {
        val json = """[{"name":"Main Stage","x":1.5,"y":-2,"theta":0.3,"id":"a","status":0},
            {"name":"充电桩","x":0,"y":0,"status":0,"typeId":1},{"name":"","x":0},{"name":"Blocked","status":2}]"""
        val places = PlaceParser.parse(json)!!
        assertEquals(listOf("Main Stage", "充电桩", "Blocked"), places.map { it.name })
        assertEquals(1.5, places[0].x, 1e-9)
        assertTrue(places[1].isSystemPoint)
        assertFalse(places[2].reachable)
        assertNull(PlaceParser.parse("error: not connected"))
        assertEquals(emptyList<MapPlace>(), PlaceParser.parse("[]"))
    }

    @Test
    fun languageModelAnswers() {
        val ids = setOf("restrooms", "map:Main Stage")
        assertEquals(BrainReply(BrainIntent.ROOM, "map:Main Stage", null), BrainPrompt.parse("""{"intent":"room","room_id":"map:Main Stage","reply":null}""", ids))
        assertEquals(
            BrainReply(BrainIntent.CHAT, null, "I am a robot."),
            BrainPrompt.parse("```json\n{\"intent\": \"chat\", \"room_id\": null, \"reply\": \"I am a robot.\"}\n```", ids),
        )
        assertEquals(BrainIntent.IGNORE, BrainPrompt.parse("""{"intent":"ignore"}""", ids)?.intent)
        // Unknown room id with a reply becomes small talk; without a reply it is unusable
        assertEquals(BrainIntent.CHAT, BrainPrompt.parse("""{"intent":"room","room_id":"kitchen","reply":"I do not know the kitchen."}""", ids)?.intent)
        assertNull(BrainPrompt.parse("""{"intent":"room","room_id":"kitchen"}""", ids))
        assertNull(BrainPrompt.parse("""{"intent":"dance"}""", ids))
        assertNull(BrainPrompt.parse("no json", ids))
        assertNull(BrainPrompt.parse(null, ids))
    }

    @Test
    fun promptListsRoomsAndFloors() {
        val restrooms = Settings.defaultRooms().first()
        val stage = Room("map:Main Stage", "Main Stage", mapPoint = "Main Stage")
        val prompt = BrainPrompt.system(
            BrainContext("Norra", "Founders Foundation", "", 0, listOf(restrooms to RoomPlan.OtherFloor(listOf(1, -1), null), stage to RoomPlan.Walk("Main Stage"))),
        )
        assertTrue(prompt, prompt.contains("- restrooms: Restrooms (also called: restroom, toilet"))
        assertTrue(prompt, prompt.contains("on the first floor, one floor up, or in the basement, one floor down"))
        assertTrue(prompt, prompt.contains("- map:Main Stage: Main Stage - on this floor, you can walk visitors there"))
        assertTrue(prompt.lines().none { it.startsWith("            ") }) // indentation removed
    }

    @Test
    fun echoOfOwnVoiceIsIgnored() {
        var now = 0L
        val echo = EchoFilter({ now })
        echo.started("Of course! Please follow me to the Main Stage.")
        assertTrue(echo.isEcho("please follow me to the main stage"))
        assertFalse(echo.isEcho("where is the coffee bar"))
        now = 1_000
        echo.ended("Of course! Please follow me to the Main Stage.")
        now = 3_000
        assertFalse(echo.isEcho("please follow me to the main stage"))
    }

    @Test
    fun wanderPlannerInOrderAndSkipsFailingPoints() {
        var now = 0L
        val planner = WanderPlanner({ now })
        val route = listOf("A", "B", "C")
        assertEquals("A", planner.next(route, false))
        planner.arrived("A")
        assertEquals("B", planner.next(route, false))
        planner.failed("B")
        assertEquals("C", planner.next(route, false)) // continues after the failed point
        planner.failed("B")
        planner.arrived("C")
        assertEquals("A", planner.next(route, false))
        planner.arrived("A")
        assertEquals("C", planner.next(route, false)) // B is skipped for a while
        now += 6 * 60_000
        assertEquals("B", planner.next(route, false))
    }

    @Test
    fun wanderPlannerRandomNeverRepeats() {
        val planner = WanderPlanner({ 0L }, kotlin.random.Random(7))
        val route = listOf("A", "B", "C", "D")
        var last: String? = null
        repeat(50) {
            val next = planner.next(route, true)!!
            assertTrue(next != last)
            planner.arrived(next)
            last = next
        }
        assertNull(WanderPlanner({ 0L }).also { it.arrived("A") }.next(listOf("A"), false))
    }
}
