package de.hinterland.smartreceptionist.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun detectsGerman() {
        listOf(
            "Ich habe einen Termin bei Frau Schmidt", "Guten Tag, ich möchte Herrn Becker sprechen", "Wo ist die Toilette?",
            "Ich liefere ein Paket", "Mein Name ist Peter Müller", "Können Sie mir bitte helfen", "Zeig mir die Mitarbeiterliste",
        ).forEach { assertEquals(it, "de", LanguageDetector.detect(it)) }
    }

    @Test
    fun detectsEnglish() {
        listOf(
            "I have an appointment with Anna Schmidt", "Good morning, I would like to talk to Mr Becker", "Where is the restroom?",
            "I am delivering a package", "My name is Peter Miller", "Can you please help me", "Show me the employee list",
        ).forEach { assertEquals(it, "en", LanguageDetector.detect(it)) }
    }

    @Test
    fun unclearInputGivesNull() {
        listOf("Hallo", "Hello", "Anna Schmidt", "Peter", "ok", "", "Müller").forEach { assertNull(it, LanguageDetector.detect(it)) }
    }
}
