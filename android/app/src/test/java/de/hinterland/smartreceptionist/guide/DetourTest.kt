package de.hinterland.smartreceptionist.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetourTest {

    private val catalog = PlaceCatalog(
        listOf("Reception Point", "Waiting Room", "Restroom", "Kitchen", "Office").map { MapPlace(it) },
        waitingRoomName = "Waiting Room",
        receptionName = "Reception Point",
    )

    private fun detour(text: String, current: String = "Waiting Room"): String? =
        Detour.request(text, catalog, current)?.name

    @Test
    fun restroomQuestionOnTheWayToWaitingRoomIsADetour() {
        assertEquals("Restroom", detour("Where is the restroom?"))
        assertEquals("Restroom", detour("Wo ist die Toilette?"))
        assertEquals("Restroom", detour("Ich muss mal"))
        assertEquals("Kitchen", detour("Can you take me to the kitchen please"))
    }

    @Test
    fun askingForTheCurrentTargetIsNotADetour() {
        assertNull(detour("Where is the waiting room?"))
        assertNull(detour("Are we going to the restroom?", current = "Restroom"))
    }

    @Test
    fun receptionIsNeverADetour() {
        assertNull(detour("Where is the reception?"))
    }

    @Test
    fun smallTalkAndNoiseAreIgnored() {
        assertNull(detour("Thank you very much"))
        assertNull(detour("hmm okay"))
        assertNull(detour("Follow me, I'll take you to the waiting room"))
    }

    @Test
    fun unknownPlacesAreIgnored() {
        assertNull(detour("Where is the swimming pool?"))
    }
}
