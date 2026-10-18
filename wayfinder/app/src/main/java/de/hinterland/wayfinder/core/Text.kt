package de.hinterland.wayfinder.core

import java.text.Normalizer

/** Text helpers shared by the speech matching and the room list. */
object Text {

    private val NUMBER_WORDS = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
        "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12",
        "null" to "0", "eins" to "1", "zwei" to "2", "drei" to "3", "vier" to "4", "fuenf" to "5", "sechs" to "6",
        "sieben" to "7", "acht" to "8", "neun" to "9", "zehn" to "10", "elf" to "11", "zwoelf" to "12",
        "first" to "1", "second" to "2", "third" to "3",
    )

    /**
     * Lower case, umlauts folded (ä -> ae), punctuation removed, number words turned into digits,
     * single spaces. Returned with a space on both ends so whole words can be found with " word ".
     */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace("é", "e").replace("è", "e").replace("à", "a")
            .replace("’", "'").replace("`", "'")
            // "2.04" -> "2 04", "Room-A" -> "room a"
            .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
            .replace("'", "")
        val words = folded.split(Regex("\\s+")).filter { it.isNotBlank() }.map { NUMBER_WORDS[it] ?: it }
        return " " + words.joinToString(" ") + " "
    }

    /** Key to compare names: "Main  Stage!" and "main stage" give the same key. */
    fun key(text: String): String = normalize(text).trim()

    fun words(text: String): List<String> = normalize(text).trim().split(' ').filter { it.isNotEmpty() }

    /** Does the normalized [haystack] contain [phrase] as whole word(s)? Plural "s" is accepted. */
    fun containsPhrase(haystack: String, phrase: String): Boolean {
        val p = key(phrase)
        if (p.isEmpty()) return false
        return haystack.contains(" $p ") || haystack.contains(" ${p}s ") ||
            (p.endsWith("s") && p.length > 3 && haystack.contains(" ${p.dropLast(1)} "))
    }

    /** Levenshtein similarity 0..1 ("stayge" ~ "stage"). */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    /** Adds "the" for speech, except for names with numbers or possessives ("Room 2.04", "Anna's office"). */
    fun the(name: String): String {
        val n = name.trim()
        val lower = n.lowercase()
        return when {
            lower.startsWith("the ") -> n
            n.any { it.isDigit() } || n.contains("'s") || n.contains("’s") -> n
            else -> "the $n"
        }
    }

    /** "The restrooms are" / "The main stage is" */
    fun isPlural(name: String): Boolean {
        val last = key(name).split(' ').lastOrNull().orEmpty()
        return last.length > 3 && last.endsWith("s") && !last.endsWith("ss") && !last.endsWith("us") && !last.endsWith("is")
    }

    /** Short sentence safe for the speech engine: no markdown, emoji or line breaks. */
    fun speakable(raw: String?, maxChars: Int = 300): String? {
        var t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        t = t.replace(Regex("[*_`#>\\[\\]]+"), "")
            .replace(Regex("[\\p{So}\\p{Cs}\\u200d\\ufe0f]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.length > maxChars) {
            val cut = t.substring(0, maxChars)
            val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
            t = if (end > 40) cut.substring(0, end + 1) else cut.trimEnd() + "."
        }
        return t.takeIf { it.isNotBlank() }
    }
}
