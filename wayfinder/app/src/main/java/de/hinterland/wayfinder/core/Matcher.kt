package de.hinterland.wayfinder.core

/** What a visitor said, as far as the local rules can tell. */
sealed interface Heard {
    /** A clear request for a room ("Where are the restrooms?", "Main stage, please") */
    data class RoomRequest(val room: Room) : Heard

    /** A room was mentioned, but it is unclear whether the visitor wants to go there */
    data class Mention(val room: Room) : Heard
    data object Stop : Heard
    data object ListRooms : Heard
    data object Yes : Heard
    data object No : Heard
    data object Thanks : Heard
    data object Hello : Heard
    data object Bye : Heard
    data object Unknown : Heard
}

/**
 * Fast keyword understanding, works without network and without the language model.
 * Speech recognition output is messy, so names are also matched fuzzily ("main stayge").
 * English is the main language; common German phrases are understood too.
 */
object Matcher {

    fun understand(text: String, rooms: List<Room>): Heard {
        val n = Text.normalize(text)
        val words = n.trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return Heard.Unknown
        val meaningful = words.filter { it !in FILLER }

        if (isStop(n, words)) return Heard.Stop

        val room = bestRoom(n, words, rooms)
        val explicit = room != null && (STRONG.any { n.contains(it) } || meaningful.size <= 3)
        if (room != null && explicit) return Heard.RoomRequest(room)

        if (LIST.any { n.contains(it) } || n.trim() == "help") return Heard.ListRooms

        val core = meaningful.joinToString(" ")
        if (core in YES || n.trim() in YES) return Heard.Yes
        if (core in NO || n.trim() in NO) return Heard.No

        if (words.size <= 6 && THANKS.any { n.contains(it) }) return Heard.Thanks
        if (words.size <= 5 && BYE.any { n.contains(it) }) return Heard.Bye
        if (words.size <= 4 && HELLO.any { n.startsWith(it) }) return Heard.Hello

        if (room != null) return Heard.Mention(room)
        return Heard.Unknown
    }

    private fun isStop(n: String, words: List<String>): Boolean {
        val t = n.trim()
        if (t in STOP) return true
        return words.size <= 4 && (words.first() in setOf("stop", "stopp", "halt") || words.last() in setOf("stop", "stopp"))
    }

    /** Best matching room, or null. Exact names beat aliases, aliases beat fuzzy matches and categories. */
    fun bestRoom(text: String, rooms: List<Room>): Room? {
        val n = Text.normalize(text)
        return bestRoom(n, n.trim().split(' ').filter { it.isNotEmpty() }, rooms)
    }

    private fun bestRoom(n: String, words: List<String>, rooms: List<Room>): Room? {
        var best: Room? = null
        var bestScore = 0.0
        for (room in rooms) {
            val score = score(n, words, room)
            if (score > bestScore + 1e-9) {
                best = room
                bestScore = score
            }
        }
        return if (bestScore >= 0.7) best else null
    }

    private fun score(n: String, words: List<String>, room: Room): Double {
        var score = 0.0
        val name = Text.key(room.name)
        if (name.isNotEmpty() && Text.containsPhrase(n, name)) score = maxOf(score, 1.0 + name.length * 0.01)
        for (alias in room.aliases + room.mapPoint) {
            val a = Text.key(alias)
            if (a.length >= 2 && Text.containsPhrase(n, a)) score = maxOf(score, 0.95 + a.length * 0.01)
        }
        // "Lab3" vs "lab 3", "Room 2.04" vs "room 204"
        val compactName = name.replace(" ", "")
        if (compactName.length >= 4 && compactName.any { it.isDigit() } && n.replace(" ", "").contains(compactName)) {
            score = maxOf(score, 0.9)
        }
        score = maxOf(score, fuzzy(words, name))
        if (categoryWords(room).any { Text.containsPhrase(n, it) }) score = maxOf(score, 0.7)
        return score
    }

    /** All significant words of the name appear (nearly) in the utterance. */
    private fun fuzzy(words: List<String>, name: String): Double {
        val tokens = name.split(' ').filter { it.isNotEmpty() && it !in GENERIC && (it.length >= 3 || it.any(Char::isDigit)) }
        if (tokens.isEmpty()) return 0.0
        var total = 0.0
        for (t in tokens) {
            val best = words.maxOfOrNull { w ->
                when {
                    t.any(Char::isDigit) || w.any(Char::isDigit) -> if (t == w) 1.0 else 0.0
                    t.length < 4 -> if (t == w) 1.0 else 0.0
                    else -> Text.similarity(t, w)
                }
            } ?: 0.0
            if (best < 0.78) return 0.0
            total += best
        }
        val avg = total / tokens.size
        return if (avg >= 0.85) 0.8 else 0.0
    }

    /** Everyday words for a type of room, e.g. "toilet" for the restrooms. */
    private fun categoryWords(room: Room): List<String> {
        val nameKey = Text.key(room.name)
        return when (room.resolvedIcon) {
            RoomIcon.RESTROOM -> RESTROOM_WORDS
            RoomIcon.FOOD -> FOOD_WORDS
            RoomIcon.ELEVATOR -> ELEVATOR_WORDS
            RoomIcon.RECEPTION -> RECEPTION_WORDS
            RoomIcon.STAGE -> STAGE_WORDS
            RoomIcon.EXIT -> when {
                Regex("exit|ausgang|way out").containsMatchIn(nameKey) -> EXIT_WORDS
                Regex("entrance|eingang").containsMatchIn(nameKey) -> ENTRANCE_WORDS
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ word lists (normalized forms)

    private val STRONG = listOf(
        " where ", " wheres ", " which floor ", " what floor ", " how do i get ", " how can i get ", " how to get ",
        " how do i find ", " how can i find ", " the way to ", " way to ", " directions ", " take me ", " bring me ",
        " show me ", " lead me ", " guide me ", " walk me ", " escort me ", " take us ", " bring us ", " show us ",
        " lead us ", " guide us ", " i need ", " we need ", " looking for ", " i want to go ", " i would like to go ",
        " id like to go ", " i have to go ", " i need to go ", " go to ", " find ", " is there a ", " is there an ",
        " are there ", " can you take ", " could you take ", " can you show ", " could you show ", " can you bring ",
        " get to ", " how far ",
        " wo ist ", " wo sind ", " wo finde ", " wo gibt es ", " wo geht es ", " wo gehts ", " wie komme ich ",
        " wie kommt man ", " bring mich ", " bringen sie mich ", " bringst du mich ", " fuehr mich ", " fuehre mich ",
        " zeig mir ", " zeigen sie mir ", " ich suche ", " wir suchen ", " ich muss ", " ich moechte ", " welcher stock ",
        " welche etage ", " welchem stock ", " zum ", " zur ",
    )

    private val FILLER = setOf(
        "the", "a", "an", "please", "um", "uh", "uhm", "hm", "hmm", "hey", "hi", "hello", "ok", "okay", "so", "well",
        "yes", "yeah", "bitte", "die", "der", "das", "den", "dem", "zur", "zum", "to", "for", "me", "i", "my", "thanks",
        "thank", "you", "danke", "robot", "excuse", "sorry", "now", "just", "oh", "and", "ja", "also", "mal",
    )

    private val GENERIC = setOf("the", "room", "raum", "area", "bereich", "floor", "level", "zone", "of", "and", "to", "im", "in", "der", "die", "das", "at")

    private val STOP = setOf(
        "stop", "stopp", "halt", "wait", "cancel", "stop it", "please stop", "stop please", "stop stop", "stop now",
        "abbrechen", "anhalten", "warte", "warten", "stehen bleiben", "bleib stehen", "never mind", "nevermind",
        "forget it", "hold on", "wait please", "please wait", "wait a moment", "wait a second",
    )

    private val YES = setOf(
        "yes", "yeah", "yep", "yup", "sure", "okay", "ok", "of course", "yes please", "sure thing", "lets go", "go ahead",
        "ja", "gerne", "ja bitte", "ja gerne", "klar", "do it", "alright", "all right", "absolutely", "definitely",
        "why not", "show me", "show me the way", "take me there", "yes show me", "yes take me", "please",
    )

    private val NO = setOf(
        "no", "nope", "no thanks", "no thank", "no thank you", "not now", "nein", "nein danke", "no need",
        "i am fine", "im fine", "that is ok", "thats ok", "its ok", "it is ok", "not necessary", "nah",
    )

    private val THANKS = listOf(" thank you ", " thanks ", " thank ", " danke ", " cheers ", " merci ", " vielen dank ")
    private val BYE = listOf(" bye ", " goodbye ", " good bye ", " see you ", " tschuess ", " ciao ", " auf wiedersehen ", " bye bye ")
    private val HELLO = listOf(
        " hello", " hi ", " hey ", " good morning", " good afternoon", " good evening", " hallo", " moin", " servus",
        " guten tag", " guten morgen",
    )

    private val LIST = listOf(
        " what rooms ", " which rooms ", " what places ", " which places ", " where can you take me ",
        " where can you go ", " what can you do ", " what do you do ", " help me ", " show me the rooms ",
        " list the rooms ", " options ", " what is here ", " whats here ", " what is there ", " welche raeume ",
        " was kannst du ", " wohin kannst du ", " what destinations ", " what rooms are there ",
    )

    private val RESTROOM_WORDS = listOf(
        "restroom", "toilet", "bathroom", "washroom", "lavatory", "loo", "wc", "ladies room", "mens room", "gents",
        "toilette", "toiletten", "klo", "waschraum", "pee", "powder room",
    )
    private val FOOD_WORDS = listOf(
        "coffee", "cafe", "kitchen", "catering", "food", "lunch", "snack", "drinks", "something to drink",
        "something to eat", "kaffee", "essen", "kueche", "buffet", "water",
    )
    private val ELEVATOR_WORDS = listOf("elevator", "lift", "aufzug", "fahrstuhl", "stairs", "staircase", "treppe")
    private val RECEPTION_WORDS = listOf("reception", "front desk", "info desk", "information desk", "registration", "check in", "empfang", "anmeldung")
    private val STAGE_WORDS = listOf(
        "stage", "presentation", "presentations", "pitch", "pitches", "pitching", "keynote", "talks", "auditorium",
        "buehne", "vortrag", "vortraege", "praesentation", "praesentationen",
    )
    private val EXIT_WORDS = listOf("exit", "way out", "ausgang")
    private val ENTRANCE_WORDS = listOf("entrance", "main entrance", "eingang", "front door")
}
