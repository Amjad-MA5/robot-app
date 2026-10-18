package de.hinterland.smartreceptionist.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTest {
    @Test
    fun cleanRemovesMarkdownEmojiAndContractions() {
        assertEquals("I am Norra and I do not know that.", Chat.clean("**I'm** Norra and I don't know that. 🙂"))
        assertEquals("It is nice here. We will help.", Chat.clean("It's nice here. We'll help."))
    }

    @Test
    fun cleanShortensLongAnswersAtSentenceEnd() {
        val long = "This is a sentence. ".repeat(30)
        val out = Chat.clean(long)!!
        assertTrue(out.length <= 280)
        assertTrue(out.endsWith("."))
    }

    @Test
    fun cleanReturnsNullForEmpty() {
        assertNull(Chat.clean("  "))
        assertNull(Chat.clean(null))
    }
}
