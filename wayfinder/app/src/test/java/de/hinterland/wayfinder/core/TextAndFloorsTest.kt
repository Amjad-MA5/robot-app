package de.hinterland.wayfinder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextAndFloorsTest {

    @Test
    fun floorSentences() {
        assertEquals("on the first floor, one floor up, or in the basement, one floor down", Floors.spokenList(listOf(1, -1), 0))
        assertEquals("on the second floor, two floors up", Floors.spokenList(listOf(2), 0))
        assertEquals("on the ground floor, one floor down", Floors.spokenList(listOf(0), 1))
        assertEquals("on this floor", Floors.spokenList(listOf(0), 0))
        assertEquals("Floors 1 · −1", Floors.short(listOf(-1, 1)))
        assertEquals("Floor 3", Floors.short(listOf(3)))
        assertEquals("Basement", Floors.caption(-1))
        assertEquals("First floor", Floors.caption(1))
    }

    @Test
    fun floorParsing() {
        assertEquals(listOf(1, -1), Floors.parse("1, -1"))
        assertEquals(listOf(1, -1), Floors.parse("1 / −1"))
        assertEquals(emptyList<Int>(), Floors.parse(""))
        assertEquals(listOf(2), Floors.parse("2, x, 2"))
    }

    @Test
    fun restroomAnswer() {
        val restrooms = Settings.defaultRooms().first()
        assertEquals(
            "The Restrooms are on the first floor, one floor up, or in the basement, one floor down.",
            Lines.floorAnswer(restrooms, listOf(1, -1), 0),
        )
    }

    @Test
    fun articlesAndPlural() {
        assertEquals("the Main Stage", Text.the("Main Stage"))
        assertEquals("Room 2.04", Text.the("Room 2.04"))
        assertEquals("Anna's office", Text.the("Anna's office"))
        assertTrue(Text.isPlural("Restrooms"))
        assertFalse(Text.isPlural("Main Stage"))
        assertFalse(Text.isPlural("Campus"))
    }

    @Test
    fun normalization() {
        assertEquals(" wo ist die kueche ", Text.normalize("Wo ist die Küche?"))
        assertEquals(" room 2 04 ", Text.normalize("Room 2.04"))
        assertEquals(" wheres room 3 ", Text.normalize("Where's room three?"))
        assertTrue(Text.similarity("stage", "stayge") > 0.8)
    }

    @Test
    fun speakableText() {
        assertEquals("Hello there!", Text.speakable("**Hello** there! 😀"))
        assertNull(Text.speakable("   "))
        val long = "This is a sentence. ".repeat(40)
        assertTrue(Text.speakable(long)!!.length <= 300)
    }

    @Test
    fun linesNeverContainContractions() {
        Lines.random = Random(1)
        val room = Room("x", "Main Stage", mapPoint = "Main Stage")
        val samples = (0 until 20).flatMap {
            listOf(Lines.greeting("Norra"), Lines.followMe(room), Lines.arrived(room, "Norra"), Lines.announcement("Norra"))
        } + listOf(Lines.didNotCatch, Lines.stopped, Lines.navFailed(room), Lines.describeOnly(room), Lines.listRooms(listOf(room)))
        samples.forEach { assertFalse(it, Regex("\\w'(ll|re|ve|m|s|d|t)\\b").containsMatchIn(it.replace("Anna's", ""))) }
    }
}
