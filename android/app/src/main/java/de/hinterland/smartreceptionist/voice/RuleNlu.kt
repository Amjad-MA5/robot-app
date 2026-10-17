package de.hinterland.smartreceptionist.voice

import java.time.DayOfWeek
import java.time.OffsetDateTime

/** Was der Besucher möchte. */
enum class Intent { APPOINTMENT, WALK_IN, BOOK, OTHER }

/**
 * Regelbasiertes Sprachverständnis (Deutsch + Englisch).
 *
 * Läuft komplett lokal und ohne Netz – dient als Rückfallebene, falls das
 * AgentOS-Sprachmodell nicht antwortet, und als schnelle erste Auswertung.
 */
object RuleNlu {

    private fun norm(text: String): String =
        " " + text.lowercase()
            .replace(Regex("[.,!?;:\"„“()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim() + " "

    private val STRONG_NO = listOf(" nein ", " nee ", " nö ", " noe ", " no ", " nope ")
    private val NEGATION = listOf(
        " nicht ", " kein ", " keinen ", " keine ", " lieber nicht ", " falsch ", " not ", " don't ", " dont ", " wrong ",
    )
    private val YES_WORDS = listOf(
        " ja ", " jawohl ", " genau ", " richtig ", " gerne ", " gern ", " passt ", " okay ", " ok ", " klar ",
        " sicher ", " korrekt ", " stimmt ", " natürlich ", " jep ", " jo ", " bitte ", " einverstanden ",
        " yes ", " yeah ", " yep ", " sure ", " correct ", " right ", " please ", " fine ", " perfect ", " perfekt ",
    )

    /** true = Zustimmung, false = Ablehnung, null = unklar */
    fun yesNo(text: String): Boolean? {
        val t = norm(text)
        if (STRONG_NO.any { t.contains(it) }) return false
        val yes = YES_WORDS.any { t.contains(it) }
        val negated = NEGATION.any { t.contains(it) }
        return when {
            yes && !negated -> true
            negated -> false
            else -> null
        }
    }

    private val BOOK = Regex(
        "termin (vereinbaren|ausmachen|machen|buchen|bekommen|reservieren)|\\bneuen termin|" +
            "\\beinen termin (bitte|möchte|hätte|haben möchte|bekommen)|termin haben möchte|" +
            "(möchte|würde|brauche|bräuchte|will|wollte) (gern |gerne |bitte )?\\beinen termin|hätte (gern|gerne) \\beinen termin|" +
            "(make|book|schedule|arrange|set up) an? (appointment|meeting)|new appointment|need an appointment"
    )
    private val WALK_IN_STRONG = Regex(
        "ohne termin|\\bkein(en)? termin|spontan|no appointment|without an appointment|don't have an appointment"
    )
    private val APPOINTMENT = Regex(
        "(ich habe|ich hab|hab|habe|ich hätte) (einen |ein |um [\\w:]+ (uhr )?einen )?(termin|meeting|besprechung|verabredung)|" +
            "bin (verabredet|eingeladen|angemeldet)|termin (um|bei|mit)|" +
            "(i have|i've got|i got) an? (appointment|meeting)|i'm expected|i am expected|appointment (at|with)"
    )
    private val WALK_IN_WEAK = Regex(
        "(möchte|würde|will|wollte|muss) .*(sprechen|reden)|(talk|speak) (to|with)|see someone"
    )

    fun intent(text: String): Intent? {
        val t = norm(text)
        return when {
            BOOK.containsMatchIn(t) -> Intent.BOOK
            WALK_IN_STRONG.containsMatchIn(t) -> Intent.WALK_IN
            APPOINTMENT.containsMatchIn(t) -> Intent.APPOINTMENT
            WALK_IN_WEAK.containsMatchIn(t) -> Intent.WALK_IN
            else -> null
        }
    }

    private val NAME_INTRO = Regex(
        "(?:mein name ist|mein name lautet|ich heiße|ich heisse|ich bin der|ich bin die|ich bin|hier ist|hier spricht|" +
            "my name is|my name's|i am|i'm|this is|name is)\\s+([\\p{L}'-]+(?:\\s+[\\p{L}'-]+){0,2})",
        RegexOption.IGNORE_CASE,
    )
    private val FILLER = setOf(
        "äh", "ähm", "also", "ja", "hallo", "guten", "tag", "morgen", "abend", "bitte", "danke", "mein", "name", "ist",
        "ich", "heiße", "heisse", "bin", "der", "die", "das", "hi", "hello", "my", "is", "i'm", "im", "and", "und", "von",
        "herr", "frau", "mr", "mrs", "ms", "the", "uh", "um", "so", "well", "okay", "ok",
    )
    private val STOP_AFTER_NAME = setOf(
        "und", "ich", "habe", "hab", "möchte", "will", "würde", "wollte", "bei", "zu", "mit", "and", "i", "have", "would",
        "want", "with", "to", "for", "für", "einen", "termin", "appointment", "hier", "da", "heute", "wegen", "gekommen",
        "zum", "zur", "auf", "um", "here", "at", "a", "an", "the", "looking", "ein", "eine", "gast", "besuch", "kunde",
        "visitor", "guest", "from", "aus",
    )

    private fun capitalizeWords(s: String): String =
        s.split(" ").filter { it.isNotBlank() }.joinToString(" ") { w ->
            w.split("-").joinToString("-") { part -> part.replaceFirstChar { c -> c.titlecase() } }
        }

    /** Name aus einem Satz wie "Ich bin Max Mustermann und habe einen Termin". */
    fun visitorNameFromSentence(text: String): String? {
        val m = NAME_INTRO.find(text) ?: return null
        val words = m.groupValues[1].split(Regex("\\s+")).takeWhile { it.lowercase() !in STOP_AFTER_NAME }
        if (words.isEmpty()) return null
        return capitalizeWords(words.joinToString(" "))
    }

    /** Antwort auf "Wie ist Ihr Name?" – meist nur der Name selbst. */
    fun nameFromAnswer(text: String): String? {
        visitorNameFromSentence(text)?.let { return it }
        val words = text.replace(Regex("[^\\p{L}\\s'-]"), " ").split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.lowercase() !in FILLER }
        if (words.isEmpty() || words.size > 4) return null
        return capitalizeWords(words.joinToString(" "))
    }

    private val EMPLOYEE_INTRO = Regex(
        "(?:bei|zu|mit|für|an|with|to see|see|for|to)\\s+(?:(?:frau|herrn|herr|dr\\.?|doktor|prof\\.?|mr\\.?|mrs\\.?|ms\\.?)\\s+)?" +
            "([\\p{L}'-]+(?:\\s+[\\p{L}'-]+)?)",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_A_NAME = setOf(
        "einem", "einen", "ihnen", "ihm", "ihr", "jemandem", "jemand", "dem", "der", "den", "termin", "uhr", "someone",
        "somebody", "an", "a", "the", "appointment", "meeting", "talk", "speak", "sprechen", "reden", "heute", "today",
        "um", "at", "you", "me", "mir", "mich", "uns", "us", "bitte", "please", "treffen", "sehen", "meet",
        "aber", "but", "gern", "gerne", "kurz", "mal",
    )

    private val HONORIFIC_NAME = Regex(
        "(?:frau|herrn|herr|dr\\.?|doktor|mr\\.?|mrs\\.?|ms\\.?)\\s+([\\p{L}'-]+(?:\\s+[\\p{L}'-]+)?)",
        RegexOption.IGNORE_CASE,
    )

    /** Ansprechpartner aus einem Satz wie "Ich habe einen Termin bei Frau Schmidt". */
    fun employeeFromSentence(text: String): String? {
        // Der eigene Name ("Mein Name ist …") darf nicht als Ansprechpartner gelten
        var withoutOwnName = text
        visitorNameFromSentence(text)?.split(" ")?.forEach { word ->
            withoutOwnName = withoutOwnName.replace(Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE), " ")
        }
        for (regex in listOf(EMPLOYEE_INTRO, HONORIFIC_NAME)) {
            for (m in regex.findAll(withoutOwnName)) {
                val candidate = m.groupValues[1].split(Regex("\\s+"))
                    .takeWhile { it.lowercase() !in NOT_A_NAME && it.lowercase() !in STOP_AFTER_NAME }
                if (candidate.isNotEmpty() && candidate.first().lowercase() !in NOT_A_NAME) {
                    return candidate.joinToString(" ")
                }
            }
        }
        return null
    }

    /** Antwort auf "Zu wem möchten Sie?" – der ganze Satz ohne Füllwörter. */
    fun employeeFromAnswer(text: String): String? {
        employeeFromSentence(text)?.let { return it }
        val words = text.replace(Regex("[^\\p{L}\\s'-]"), " ").split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.lowercase() !in FILLER && it.lowercase() !in NOT_A_NAME && it.lowercase() !in STOP_AFTER_NAME }
        if (words.isEmpty() || words.size > 4) return null
        return words.joinToString(" ")
    }

    private val ORDINALS = listOf(
        listOf("erste", "ersten", "erster", "eins", "1", "first", "one", "früheste", "frühesten"),
        listOf("zweite", "zweiten", "zweiter", "zwei", "2", "second", "two"),
        listOf("dritte", "dritten", "dritter", "drei", "3", "third", "three"),
    )
    private val LAST = listOf("letzte", "letzten", "letzter", "last")

    private val WEEKDAYS = mapOf(
        DayOfWeek.MONDAY to listOf("montag", "monday"),
        DayOfWeek.TUESDAY to listOf("dienstag", "tuesday"),
        DayOfWeek.WEDNESDAY to listOf("mittwoch", "wednesday"),
        DayOfWeek.THURSDAY to listOf("donnerstag", "thursday"),
        DayOfWeek.FRIDAY to listOf("freitag", "friday"),
        DayOfWeek.SATURDAY to listOf("samstag", "saturday"),
        DayOfWeek.SUNDAY to listOf("sonntag", "sunday"),
    )

    /** Welcher der angebotenen Termine gemeint ist (Index) – oder null. */
    fun slotChoice(text: String, slots: List<OffsetDateTime>): Int? {
        if (slots.isEmpty()) return null
        val t = norm(text)
        // Uhrzeit ("um 14 Uhr", "14:30", "10.15") – auf dem Originaltext, damit ":" erhalten bleibt
        Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\b").findAll(text.lowercase()).forEach { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@forEach
            val minute = m.groupValues[2].toIntOrNull()
            if (hour in 6..21) {
                val idx = slots.indexOfFirst { it.hour == hour && (minute == null || it.minute == minute) }
                if (idx >= 0) return idx
            }
        }
        // Wochentag
        WEEKDAYS.forEach { (day, words) ->
            if (words.any { t.contains(" $it ") }) {
                val idx = slots.indexOfFirst { it.dayOfWeek == day }
                if (idx >= 0) return idx
            }
        }
        if (LAST.any { t.contains(" $it ") }) return slots.lastIndex
        // Ordnungszahl ("den zweiten")
        ORDINALS.forEachIndexed { index, words ->
            if (index < slots.size && words.any { t.contains(" $it ") }) return index
        }
        return null
    }

    /** "andere/später/weitere Termine" */
    fun wantsMoreOptions(text: String): Boolean {
        val t = norm(text)
        return listOf(" andere", " später", " spaeter", " weitere", " nächste woche", " other", " later", " another", " more ")
            .any { t.contains(it) }
    }

    /** Reine Dankes-/Abschiedsfloskel ohne weiteres Anliegen */
    fun isThanks(text: String): Boolean {
        val t = norm(text).trim()
        return Regex("^(vielen |danke |dankeschön|danke schön|thank you|thanks|tschüss|ciao|bis dann|schönen tag|auf wiedersehen|bye)[\\p{L} ]{0,25}$")
            .containsMatchIn(t + " ")
    }

    fun wantsCancel(text: String): Boolean {
        val t = norm(text)
        return listOf(" abbrechen ", " stopp ", " stop ", " vergiss es ", " egal ", " tschüss ", " auf wiedersehen ", " cancel ", " never mind ", " bye ")
            .any { t.contains(it) }
    }
}
