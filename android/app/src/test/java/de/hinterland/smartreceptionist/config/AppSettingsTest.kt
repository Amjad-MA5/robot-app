package de.hinterland.smartreceptionist.config

import de.hinterland.smartreceptionist.voice.linesFor
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun defaultsToEnglish() {
        val settings = AppSettings()

        assertEquals("en", settings.language)
        assertEquals("Welcome!", linesFor(settings.language).idleTitle)
    }
}
