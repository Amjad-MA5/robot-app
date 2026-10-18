package de.hinterland.wayfinder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {

    private val restrooms = Settings.defaultRooms().first()
    private val stage = Room("map:Main Stage", "Main Stage", mapPoint = "Main Stage", fromMap = true)
    private val workshop = Room("map:Workshop Room", "Workshop Room", mapPoint = "Workshop Room", fromMap = true)
    private val coffee = Room("map:Coffee Bar", "Coffee Bar", mapPoint = "Coffee Bar", fromMap = true)
    private val room204 = Room("r204", "Room 2.04", floors = listOf(2))
    private val exit = Room("map:Exit", "Exit", mapPoint = "Exit", fromMap = true)
    private val rooms = listOf(restrooms, stage, workshop, coffee, room204, exit)

    private fun room(text: String): Room? = (Matcher.understand(text, rooms) as? Heard.RoomRequest)?.room

    @Test
    fun restroomQuestionsInEnglishAndGerman() {
        for (text in listOf(
            "Where are the restrooms?", "where is the toilet", "Where's the bathroom please?", "I need the loo",
            "Toilet?", "WC", "Can you show me the restroom", "Wo ist die Toilette?", "Wo sind die Toiletten", "Klo?",
            "Excuse me, where can I find the ladies room?",
        )) {
            assertEquals(text, restrooms, room(text))
        }
    }

    @Test
    fun roomsByNameAndCategory() {
        assertEquals(stage, room("Take me to the main stage"))
        assertEquals(stage, room("Where are the presentations?"))
        assertEquals(workshop, room("workshop room please"))
        assertEquals(coffee, room("Where can I get some coffee?"))
        assertEquals(exit, room("How do I get to the exit?"))
        assertEquals(exit, room("Wo ist der Ausgang?"))
    }

    @Test
    fun fuzzyAndNumberedNames() {
        assertEquals(stage, room("where is the main stayge"))
        assertEquals(room204, room("Where is room 204?"))
        assertEquals(room204, room("I am looking for room 2.04"))
    }

    @Test
    fun mentionWithoutRequestIsNotAnOrder() {
        val heard = Matcher.understand("I just came from the main stage and it was really great today", rooms)
        assertTrue(heard is Heard.Mention)
    }

    @Test
    fun controlWords() {
        assertEquals(Heard.Stop, Matcher.understand("Stop!", rooms))
        assertEquals(Heard.Stop, Matcher.understand("please stop", rooms))
        assertEquals(Heard.Stop, Matcher.understand("Halt", rooms))
        assertEquals(Heard.Yes, Matcher.understand("Yes please", rooms))
        assertEquals(Heard.Yes, Matcher.understand("ok sure", rooms))
        assertEquals(Heard.No, Matcher.understand("No thanks", rooms))
        assertEquals(Heard.Thanks, Matcher.understand("Thank you so much!", rooms))
        assertEquals(Heard.Hello, Matcher.understand("Hello robot", rooms))
        assertEquals(Heard.Bye, Matcher.understand("Bye bye", rooms))
        assertEquals(Heard.ListRooms, Matcher.understand("Which rooms are there?", rooms))
        assertEquals(Heard.ListRooms, Matcher.understand("What can you do?", rooms))
        assertEquals(Heard.Unknown, Matcher.understand("What is the weather like tomorrow?", rooms))
    }

    @Test
    fun roomBeatsGreeting() {
        assertEquals(restrooms, room("Hi, where is the toilet?"))
        assertEquals(stage, room("Thanks, and where is the main stage?"))
    }

    @Test
    fun noRoomsNoCrash() {
        assertEquals(Heard.Unknown, Matcher.understand("Where is the kitchen?", emptyList()))
        assertEquals(Heard.Unknown, Matcher.understand("", rooms))
    }
}
