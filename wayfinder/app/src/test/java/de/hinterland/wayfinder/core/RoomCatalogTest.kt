package de.hinterland.wayfinder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomCatalogTest {

    private val map = listOf(
        MapPlace("Reception", type = MapPlace.TYPE_RECEPTION),
        MapPlace("main stage"),
        MapPlace("Workshop Room"),
        MapPlace("Stairs"),
        MapPlace("Charging Pile", type = MapPlace.TYPE_CHARGING),
        MapPlace("充电桩"),
        MapPlace("定位点", type = MapPlace.TYPE_POSITIONING),
        MapPlace("Storage", status = 1),
    )

    @Test
    fun defaultRoomsPlusMapPointsWithoutSystemPoints() {
        val c = RoomCatalog(Settings(), map)
        assertEquals(listOf("Restrooms", "Main stage", "Reception", "Stairs", "Workshop Room"), c.rooms.map { it.name })
        // Wander route: all normal reachable points, never charging/positioning/unreachable ones
        assertEquals(listOf("Reception", "main stage", "Workshop Room", "Stairs"), c.wanderPoints)
    }

    @Test
    fun restroomsAreOnOtherFloors() {
        val c = RoomCatalog(Settings(), map)
        val plan = c.planFor(c.byId(Settings.RESTROOM_ID)!!)
        assertEquals(RoomPlan.OtherFloor(listOf(1, -1), via = null), plan)
        // With a stairs point the robot can walk visitors there
        val withStairs = RoomCatalog(Settings(stairsPoint = "stairs"), map)
        assertEquals(RoomPlan.OtherFloor(listOf(1, -1), via = "Stairs"), withStairs.planFor(withStairs.byId(Settings.RESTROOM_ID)!!))
    }

    @Test
    fun restroomsOnTheRobotsFloor() {
        val c = RoomCatalog(Settings(robotFloor = 1), map)
        assertEquals(RoomPlan.Describe, c.planFor(c.byId(Settings.RESTROOM_ID)!!))
    }

    @Test
    fun mapPointsAreWalkable() {
        val c = RoomCatalog(Settings(), map)
        val stage = c.rooms.first { it.name == "Main stage" }
        assertEquals(RoomPlan.Walk("main stage"), c.planFor(stage))
    }

    @Test
    fun configuredRoomHidesItsMapPoint() {
        val settings = Settings(rooms = listOf(Room("hall", "Main Hall", mapPoint = "main stage", aliases = listOf("stage"))))
        val c = RoomCatalog(settings, map)
        assertEquals(listOf("Main Hall", "Reception", "Stairs", "Workshop Room"), c.rooms.map { it.name })
        assertEquals(RoomPlan.Walk("main stage"), c.planFor(c.rooms.first()))
    }

    @Test
    fun hiddenPointsAndSwitchedOffAutoAdd() {
        val hidden = RoomCatalog(Settings(hiddenMapPoints = setOf("Stairs", "Reception")), map)
        assertEquals(listOf("Restrooms", "Main stage", "Workshop Room"), hidden.rooms.map { it.name })
        val manual = RoomCatalog(Settings(autoAddMapPoints = false), map)
        assertEquals(listOf("Restrooms"), manual.rooms.map { it.name })
    }

    @Test
    fun missingMapPointIsNotWalkable() {
        val settings = Settings(rooms = listOf(Room("lab", "Lab", mapPoint = "Lab 7")))
        val c = RoomCatalog(settings, map)
        assertEquals(RoomPlan.Describe, c.planFor(c.rooms.first()))
        // Map not loaded yet: trust the setting, navigation reports the problem if it is wrong
        val unknown = RoomCatalog(settings, null)
        assertEquals(RoomPlan.Walk("Lab 7"), unknown.planFor(unknown.rooms.first()))
    }

    @Test
    fun explicitWanderRouteKeepsOrderAndSkipsUnknownPoints() {
        val c = RoomCatalog(Settings(wanderPoints = listOf("Stairs", "Nowhere", "Reception", "Storage")), map)
        assertEquals(listOf("Stairs", "Reception"), c.wanderPoints)
    }

    @Test
    fun homePoint() {
        assertEquals("Reception", RoomCatalog(Settings(), map).homePoint)
        assertEquals("Stairs", RoomCatalog(Settings(homePoint = "Stairs"), map).homePoint)
        assertNull(RoomCatalog(Settings(homePoint = Settings.HOME_NONE), map).homePoint)
        assertNull(RoomCatalog(Settings(homePoint = "Nowhere"), map).homePoint)
    }

    @Test
    fun systemPointDetection() {
        assertTrue(MapPlace("Charging Pile").isSystemPoint)
        assertTrue(MapPlace("Ladestation").isSystemPoint)
        assertTrue(MapPlace("回充点").isSystemPoint)
        assertFalse(MapPlace("Main Stage").isSystemPoint)
        assertFalse(MapPlace("Large Hall").isSystemPoint)
    }

    @Test
    fun iconsFromNames() {
        assertEquals(RoomIcon.RESTROOM, RoomIcon.guess("Toiletten"))
        assertEquals(RoomIcon.STAGE, RoomIcon.guess("Main Stage"))
        assertEquals(RoomIcon.FOOD, RoomIcon.guess("Coffee Bar"))
        assertEquals(RoomIcon.EXIT, RoomIcon.guess("Main Entrance"))
        assertEquals(RoomIcon.WORKSHOP, RoomIcon.guess("Workshop Room"))
        assertEquals(RoomIcon.INFO, RoomIcon.guess("Atrium"))
    }
}
