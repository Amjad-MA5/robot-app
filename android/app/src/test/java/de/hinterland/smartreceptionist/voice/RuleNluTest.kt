package de.hinterland.smartreceptionist.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class RuleNluTest {

    @Test
    fun yesNo() {
        assertEquals(true, RuleNlu.yesNo("Ja, genau"))
        assertEquals(true, RuleNlu.yesNo("Ja bitte"))
        assertEquals(true, RuleNlu.yesNo("Das passt"))
        assertEquals(true, RuleNlu.yesNo("yes please"))
        assertEquals(false, RuleNlu.yesNo("Nein, danke"))
        assertEquals(false, RuleNlu.yesNo("Ja, das passt nicht"))
        assertEquals(false, RuleNlu.yesNo("no"))
        assertNull(RuleNlu.yesNo("Wie spät ist es?"))
    }

    @Test
    fun intent() {
        assertEquals(Intent.APPOINTMENT, RuleNlu.intent("Ich habe einen Termin bei Frau Schmidt"))
        assertEquals(Intent.APPOINTMENT, RuleNlu.intent("Hallo, ich hab um 14 Uhr einen Termin"))
        assertEquals(Intent.APPOINTMENT, RuleNlu.intent("I have an appointment with Anna"))
        assertEquals(Intent.WALK_IN, RuleNlu.intent("Ich habe keinen Termin, möchte aber Herrn Becker sprechen"))
        assertEquals(Intent.WALK_IN, RuleNlu.intent("Ich würde gern mit Lukas sprechen"))
        assertEquals(Intent.BOOK, RuleNlu.intent("Ich möchte einen Termin vereinbaren"))
        assertEquals(Intent.BOOK, RuleNlu.intent("Can I book an appointment?"))
        assertEquals(Intent.BOOK, RuleNlu.intent("Ich hätte gern einen Termin bei Frau Wagner"))
        assertEquals(Intent.BOOK, RuleNlu.intent("Ich bräuchte einen Termin bei Herrn Becker"))
        assertEquals(Intent.APPOINTMENT, RuleNlu.intent("Ich hätte einen Termin bei Frau Schmidt um zehn"))
        assertNull(RuleNlu.intent("Wo ist die Toilette?"))
    }

    @Test
    fun visitorName() {
        assertEquals("Max Mustermann", RuleNlu.visitorNameFromSentence("Hallo, mein Name ist Max Mustermann und ich habe einen Termin"))
        assertEquals("Erika Musterfrau", RuleNlu.visitorNameFromSentence("ich bin erika musterfrau"))
        assertEquals("John Smith", RuleNlu.visitorNameFromSentence("Hi, I'm John Smith"))
        assertNull(RuleNlu.visitorNameFromSentence("Ich habe einen Termin"))
        assertEquals("Max Mustermann", RuleNlu.nameFromAnswer("Max Mustermann"))
        assertEquals("Jan-Ole Peters", RuleNlu.nameFromAnswer("äh, jan-ole peters"))
        assertNull(RuleNlu.nameFromAnswer("Das weiß ich jetzt gerade auch nicht so genau"))
    }

    @Test
    fun employee() {
        assertEquals("Schmidt", RuleNlu.employeeFromSentence("Ich habe einen Termin bei Frau Schmidt"))
        assertEquals("Lukas Becker", RuleNlu.employeeFromSentence("Ich möchte zu Lukas Becker"))
        assertEquals("Anna", RuleNlu.employeeFromSentence("I have a meeting with Anna"))
        assertEquals("Sophie Wagner", RuleNlu.employeeFromAnswer("Sophie Wagner"))
        assertEquals("Wagner", RuleNlu.employeeFromAnswer("Frau Wagner bitte"))
    }

    @Test
    fun slotChoice() {
        val base = OffsetDateTime.parse("2026-10-15T10:00:00+02:00") // Donnerstag
        val slots = listOf(base, base.plusHours(4), base.plusDays(1).withHour(9))
        assertEquals(0, RuleNlu.slotChoice("den ersten bitte", slots))
        assertEquals(1, RuleNlu.slotChoice("um 14 Uhr", slots))
        assertEquals(1, RuleNlu.slotChoice("14:00 passt", slots))
        assertEquals(2, RuleNlu.slotChoice("lieber Freitag", slots))
        assertEquals(2, RuleNlu.slotChoice("the last one", slots))
        assertNull(RuleNlu.slotChoice("hmm", slots))
    }

    @Test
    fun formatting() {
        val slot = OffsetDateTime.parse("2026-10-16T10:30:00+02:00")
        val today = slot.toLocalDate().minusDays(3)
        assertEquals("am Freitag, den 16. Oktober, um 10:30 Uhr", GermanLines.formatSlot(slot, today))
        assertEquals("morgen um 10:30 Uhr", GermanLines.formatSlot(slot, slot.toLocalDate().minusDays(1)))
        assertEquals("on Friday, October 16, at 10:30", EnglishLines.formatSlot(slot, today))
    }

    @Test
    fun recognizesDelivery() {
        assertEquals(Intent.DELIVERY, RuleNlu.intent("Ich habe ein Paket für Herrn Becker"))
        assertEquals(Intent.DELIVERY, RuleNlu.intent("I'm delivering a package for Lukas"))
        assertEquals(Intent.APPOINTMENT, RuleNlu.intent("Ich habe einen Termin bei Frau Schmidt"))
    }

    @Test
    fun recognizesCarrier() {
        assertEquals("DHL", RuleNlu.carrierFromAnswer("Ich komme von DHL"))
        assertEquals("UPS", RuleNlu.carrierFromAnswer("U.P.S."))
        assertEquals("FedEx", RuleNlu.carrierFromAnswer("from Fedex Express"))
        assertEquals("Trans-o-flex", RuleNlu.carrierFromAnswer("von Trans-o-flex"))
        assertNull(RuleNlu.carrierFromAnswer(""))
    }
}
