package de.hinterland.smartreceptionist.voice

/**
 * Erkennt, ob ein Besucher Deutsch oder Englisch spricht – anhand typischer Wörter.
 *
 * Reines Kotlin ohne Android-Abhängigkeiten → läuft auch in den Unit-Tests. Bewusst vorsichtig:
 * bei kurzen, mehrdeutigen oder gemischten Sätzen kommt null zurück, dann bleibt die Sprache wie sie ist.
 */
object LanguageDetector {
    private val GERMAN = setOf(
        "ich", "bin", "habe", "hab", "einen", "eine", "ein", "der", "die", "das", "den", "dem", "und", "nicht", "kein", "keinen",
        "ja", "nein", "bitte", "danke", "guten", "tag", "morgen", "abend", "möchte", "moechte", "würde", "wuerde", "gern", "gerne",
        "termin", "mit", "bei", "zu", "zum", "zur", "wo", "ist", "sind", "wie", "kann", "können", "koennen", "mein", "meine",
        "name", "heiße", "heisse", "herr", "frau", "sprechen", "besuch", "besuche", "toilette", "klo", "ausgang", "eingang",
        "empfang", "warteraum", "paket", "liefere", "für", "fuer", "von", "wir", "sie", "uns", "mir", "mich", "bringen",
        "zeigen", "zeig", "hallo", "servus", "moin", "tschüss", "tschuess", "genau", "richtig", "passt", "mitarbeiter",
    )
    private val ENGLISH = setOf(
        "i", "am", "have", "has", "an", "a", "the", "and", "not", "no", "yes", "please", "thank", "thanks", "good", "morning",
        "evening", "would", "like", "to", "appointment", "with", "at", "where", "is", "are", "how", "can", "could", "my", "name",
        "mister", "mr", "mrs", "ms", "speak", "talk", "visit", "visiting", "restroom", "bathroom", "toilet", "exit", "entrance",
        "reception", "waiting", "room", "package", "delivering", "delivery", "for", "from", "we", "you", "us", "me", "take",
        "show", "hello", "hi", "hey", "bye", "exactly", "right", "correct", "employee", "employees", "staff", "list", "here",
    )
    /** Wörter, die in beiden Sprachen vorkommen oder zu allgemein sind – zählen nicht */
    private val SHARED = setOf("hallo", "hello", "hi", "name", "bin", "am", "an", "a", "die", "was", "so", "in", "on", "it", "to", "zu")
    private val UMLAUT = Regex("[äöüß]")

    private fun words(text: String): List<String> =
        text.lowercase().replace(Regex("[^\\p{L}'äöüß ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** "de", "en" oder null (unklar). */
    fun detect(text: String): String? {
        val w = words(text)
        if (w.size < 2) return null  // ein einzelnes Wort (oft ein Name) sagt nichts
        var de = if (UMLAUT.containsMatchIn(text)) 1 else 0
        var en = 0
        for (word in w) {
            if (word in SHARED) continue
            if (word in GERMAN) de++
            if (word in ENGLISH) en++
        }
        return when {
            de >= 2 && de >= en + 2 -> "de"
            en >= 2 && en >= de + 2 -> "en"
            de >= 1 && en == 0 && w.size >= 2 -> "de"
            en >= 1 && de == 0 && w.size >= 2 -> "en"
            else -> null
        }
    }
}
