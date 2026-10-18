package de.hinterland.wayfinder.core

/**
 * The microphone may pick up the robot's own voice. A recognized sentence that arrives while the robot
 * speaks (or right after) and consists almost only of words of that sentence is ignored, so the robot
 * never reacts to itself. Deliberately conservative: a visitor repeating a room name a moment later
 * is still heard.
 */
class EchoFilter(private val clock: () -> Long, private val graceMs: Long = 500) {

    private class Spoken(val words: Set<String>, var endedAt: Long?)

    private val recent = ArrayDeque<Spoken>()

    fun started(text: String) {
        synchronized(recent) {
            recent.addLast(Spoken(significant(text).toSet(), null))
            while (recent.size > 4) recent.removeFirst()
        }
    }

    fun ended(text: String) {
        val words = significant(text).toSet()
        synchronized(recent) { recent.lastOrNull { it.words == words && it.endedAt == null }?.endedAt = clock() }
    }

    fun isEcho(heard: String): Boolean {
        val words = significant(heard)
        if (words.size < 2) return false
        val now = clock()
        synchronized(recent) {
            return recent.any { s ->
                val ended = s.endedAt
                val active = ended == null || now - ended <= graceMs
                active && words.count { it in s.words } >= words.size * 0.8
            }
        }
    }

    private fun significant(text: String) = Text.words(text).filter { it.length >= 3 }
}
