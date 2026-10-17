package de.hinterland.smartreceptionist.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kartenpunkte wie im Robot Portal → Advanced Map Editor (Floor1) angelegt. */
class GuideNluTest {

    private val mapPlaces = listOf("Gate Entrance", "Gate Exit", "Reception Point", "Office", "Charging Pole", "Restroom")
        .map { MapPlace(it) }
    private val catalog = PlaceCatalog(mapPlaces, waitingRoomName = "Warteraum", receptionName = "Reception Point")

    private fun target(text: String, inCheckIn: Boolean = false): String? =
        GuideNlu.detect(text, catalog, inCheckIn)?.candidates?.singleOrNull()?.name

    @Test
    fun classifiesAllMapPoints() {
        val kinds = catalog.destinations.associate { it.name to it.kind }
        assertEquals(PlaceKind.ENTRANCE, kinds["Gate Entrance"])
        assertEquals(PlaceKind.EXIT, kinds["Gate Exit"])
        assertEquals(PlaceKind.RECEPTION, kinds["Reception Point"])
        assertEquals(PlaceKind.OFFICE, kinds["Office"])
        assertEquals(PlaceKind.CHARGING, kinds["Charging Pole"])
        assertEquals(PlaceKind.RESTROOM, kinds["Restroom"])
        assertEquals("Reception Point", catalog.reception()?.name)
        // Auf dem Bildschirm alle Ziele außer dem Empfang selbst
        assertEquals(5, catalog.touchDestinations().size)
        assertEquals("Restroom", catalog.touchDestinations().first().name)
    }

    @Test
    fun everyMapPointIsReachableBySpeech() {
        assertEquals("Restroom", target("Wo ist die Toilette?"))
        assertEquals("Restroom", target("Entschuldigung, wo finde ich das WC?"))
        assertEquals("Restroom", target("Ich muss mal"))
        assertEquals("Restroom", target("Where is the bathroom?"))
        assertEquals("Restroom", target("Can you take me to the restroom please"))
        assertEquals("Gate Exit", target("Wo ist der Ausgang?"))
        assertEquals("Gate Exit", target("Wie komme ich hier raus?"))
        assertEquals("Gate Exit", target("Bring me to the gate exit"))
        assertEquals("Gate Entrance", target("Zeigen Sie mir bitte den Eingang"))
        assertEquals("Gate Entrance", target("Where's the main entrance?"))
        assertEquals("Office", target("Bringen Sie mich ins Büro"))
        assertEquals("Office", target("How do I get to the office?"))
        assertEquals("Charging Pole", target("Fahr zur Ladestation"))
        assertEquals("Charging Pole", target("go to the charging pole"))
        assertEquals("Reception Point", target("Wo ist der Empfang?"))
    }

    @Test
    fun shortUtteranceIsEnoughOutsideCheckIn() {
        assertEquals("Restroom", target("Toilette?"))
        assertEquals("Restroom", target("Restroom please"))
        // Während der Anmeldung (Name, Ansprechpartner …) braucht es eine echte Wegfrage
        assertNull(target("Toilette", inCheckIn = true))
        assertEquals("Restroom", target("Wo ist denn die Toilette?", inCheckIn = true))
    }

    @Test
    fun checkInSentencesDoNotTriggerGuide() {
        assertNull(GuideNlu.detect("Ich habe einen Termin bei Frau Schmidt", catalog))
        assertNull(GuideNlu.detect("Ich habe einen Termin im Büro von Herrn Becker", catalog, inCheckIn = true))
        assertNull(GuideNlu.detect("Wo ist Frau Schmidt?", catalog))
        assertNull(GuideNlu.detect("Ich suche Herrn Becker im Büro", catalog))
        assertNull(GuideNlu.detect("Max Mustermann", catalog, inCheckIn = true))
        assertNull(GuideNlu.detect("Ja, genau", catalog, inCheckIn = true))
        // Termin + Warteraum → normale Anmeldung (die endet im Warteraum)
        assertNull(GuideNlu.detect("Ich habe einen Termin, wo ist der Warteraum?", catalog, mentionsVisitPurpose = true))
    }

    @Test
    fun waitingRoomMissingOnMapIsReported() {
        val request = GuideNlu.detect("Wo ist der Warteraum?", catalog)
        assertNotNull(request)
        assertEquals(PlaceKind.WAITING_ROOM, request!!.kind)
        assertTrue(request.candidates.isEmpty())
        // Sobald der Punkt angelegt ist (egal ob deutsch oder englisch benannt), klappt es
        val withRoom = PlaceCatalog(mapPlaces + MapPlace("Waiting Room"), "Warteraum", "Reception Point")
        assertEquals("Waiting Room", withRoom.waitingRoom()?.name)
        assertEquals("Waiting Room", GuideNlu.detect("Bringen Sie mich bitte in den Warteraum", withRoom)?.candidates?.single()?.name)
    }

    @Test
    fun unknownPointsWorkByName() {
        val withLab = PlaceCatalog(mapPlaces + MapPlace("Lab 3") + MapPlace("Kantine"), "Warteraum", "Reception Point")
        assertEquals(PlaceKind.OTHER, withLab.byName("Lab 3")?.kind)
        assertEquals("Lab 3", GuideNlu.detect("Bring mich bitte zum Lab 3", withLab)?.candidates?.single()?.name)
        assertEquals("Kantine", GuideNlu.detect("Wo ist die Kantine?", withLab)?.candidates?.single()?.name)
    }

    @Test
    fun genericGuideRequestAsksForTarget() {
        val request = GuideNlu.detect("Können Sie mir eine Wegbeschreibung geben?", catalog)
        assertNotNull(request)
        assertTrue(request!!.candidates.isEmpty())
        assertNull(request.kind)
    }

    @Test
    fun unreachablePointsAreSkipped() {
        val blocked = PlaceCatalog(listOf(MapPlace("Restroom", status = 1), MapPlace("Office")), "", "")
        assertTrue(GuideNlu.detect("Wo ist die Toilette?", blocked)!!.candidates.isEmpty())
        assertEquals(listOf("Office"), blocked.touchDestinations().map { it.name })
    }

    @Test
    fun germanLinesUseCorrectArticles() {
        val restroom = catalog.byName("Restroom")!!
        val exit = catalog.byName("Gate Exit")!!
        assertEquals("Gerne! Bitte folgen Sie mir, ich bringe Sie zu den Toiletten.", GermanGuideLines.startGuiding(restroom))
        assertEquals("Gerne! Ich begleite Sie zum Ausgang. Bitte folgen Sie mir.", GermanGuideLines.startGuiding(exit))
        assertEquals("Den Warteraum habe ich leider noch nicht auf meiner Karte. Wohin darf ich Sie sonst bringen?",
            GermanGuideLines.notOnMap(PlaceKind.WAITING_ROOM))
        assertEquals("Büro", GermanGuideLines.label(catalog.byName("Office")!!))
        assertEquals("Of course! Please follow me, I'll take you to the restrooms.", EnglishGuideLines.startGuiding(restroom))
    }
}
