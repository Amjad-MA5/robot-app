package de.hinterland.smartreceptionist.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingStoryTest {

    @Test
    fun usesTheDefaultStoryInTheChosenLanguage() {
        val en = WaitingStory.parts(isGerman = false, customText = "")
        val de = WaitingStory.parts(isGerman = true, customText = "   \n ")
        assertEquals(3, en.size)
        assertEquals(3, de.size)
        assertTrue(en.first().contains("Founders Foundation"))
        assertTrue(de.first().contains("Founders Foundation"))
        assertTrue(en.first() != de.first())
    }

    @Test
    fun customTextIsSplitIntoParagraphs() {
        val parts = WaitingStory.parts(false, "First part\nstill first.\n\n  Second part.  \n\n\nThird.")
        assertEquals(listOf("First part still first.", "Second part.", "Third."), parts)
    }

    @Test
    fun everyLanguageHasOfferAndButtons() {
        for (german in listOf(true, false)) {
            val l = WaitingStory.lines(german)
            assertTrue(l.offer.isNotBlank() && l.title.isNotBlank() && l.optYes.isNotBlank() && l.optNo.isNotBlank())
        }
    }
}
