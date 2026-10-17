package de.hinterland.smartreceptionist.guide

import java.text.Normalizer

/**
 * Wegweiser-Funktion, Teil 1: Welche Orte gibt es, und welcher ist gemeint?
 *
 * Reines Kotlin ohne Android-Abhängigkeiten → läuft auch in den Unit-Tests.
 *
 * Die Kartenpunkte kommen live vom Roboter (RobotAPI getPlaceList, angelegt im
 * „Advanced Map Editor“ im Robot Portal). Jeder Punkt wird anhand seines Namens einer
 * [PlaceKind] zugeordnet (z. B. „Restroom“ → [PlaceKind.RESTROOM]). Besucher dürfen den
 * Ort dann in Alltagssprache nennen („Wo ist das Klo?“, „Where's the bathroom?“).
 * Neue Punkte, die keiner Art zugeordnet werden können, funktionieren trotzdem – dann
 * über ihren Namen („Bring mich zum Lab 3“).
 */

/** Art eines Ortes mit Synonymen, unter denen Besucher ihn ansprechen. */
enum class PlaceKind(
    /** Wörter/Wortfolgen (klein, ohne Satzzeichen), die diesen Ort meinen */
    val synonyms: List<String>,
    /** Reihenfolge auf dem Bildschirm (kleiner = weiter vorne) */
    val order: Int,
) {
    WAITING_ROOM(
        listOf(
            "warteraum", "wartezimmer", "wartebereich", "warteecke", "wartezone", "lounge",
            "waiting room", "waiting area", "waiting lounge",
        ),
        order = 0,
    ),
    RESTROOM(
        listOf(
            "toilette", "toiletten", "wc", "klo", "klos", "waschraum", "waschräume", "sanitärraum", "badezimmer",
            "restroom", "restrooms", "bathroom", "bathrooms", "toilet", "toilets", "loo", "washroom", "lavatory",
            "men's room", "mens room", "ladies room", "ladies' room",
        ),
        order = 1,
    ),
    MEETING_ROOM(
        listOf(
            "besprechungsraum", "besprechungszimmer", "meetingraum", "meeting raum", "konferenzraum", "konferenzzimmer",
            "sitzungssaal", "meeting room", "conference room", "boardroom", "board room",
        ),
        order = 2,
    ),
    OFFICE(listOf("büro", "büros", "office", "offices", "arbeitsbereich", "workspace"), order = 3),
    KITCHEN(
        listOf("küche", "teeküche", "kaffeeküche", "kaffeeecke", "kaffeemaschine", "kitchen", "pantry", "coffee machine", "coffee corner"),
        order = 4,
    ),
    ELEVATOR(listOf("aufzug", "fahrstuhl", "lift", "elevator", "elevators"), order = 5),
    ENTRANCE(listOf("eingang", "haupteingang", "eingangstür", "entrance", "main entrance", "entry"), order = 6),
    EXIT(listOf("ausgang", "notausgang", "exit", "way out"), order = 7),
    RECEPTION(
        listOf("empfang", "rezeption", "empfangstresen", "anmeldung", "reception", "front desk", "reception desk"),
        order = 8,
    ),
    CHARGING(
        listOf("ladestation", "ladesäule", "ladepunkt", "aufladen", "charging", "charger", "charging station", "charging pole", "charging pile"),
        order = 9,
    ),
    /** Kartenpunkt, der keiner bekannten Art entspricht – nur über seinen Namen erreichbar */
    OTHER(emptyList(), order = 10),
}

/** Ein Punkt der Roboterkarte, wie ihn die RobotAPI liefert. */
data class MapPlace(
    val name: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    /** 0 = erreichbar, 1 = Sperrzone, 2 = außerhalb der Karte (laut RobotAPI-Doku) */
    val status: Int = 0,
) {
    val reachable: Boolean get() = status == 0
}

/** Ein Ziel, zu dem der Roboter einen Besucher bringen kann. */
data class Destination(val place: MapPlace, val kind: PlaceKind) {
    val name: String get() = place.name
}

/** Was der Besucher möchte, wenn er nach einem Ort fragt. */
data class GuideRequest(
    /** Passende Ziele auf der Karte (leer = Ort unbekannt oder nicht genannt) */
    val candidates: List<Destination> = emptyList(),
    /** Gewünschte Art, auch wenn es sie auf der Karte (noch) nicht gibt – z. B. Warteraum */
    val kind: PlaceKind? = null,
)

/** Kleinschreibung, Satzzeichen weg, Leerraum vereinheitlicht, mit Leerzeichen am Rand. */
internal fun normalize(text: String): String {
    val nfc = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
    return " " + nfc
        .replace("’", "'")
        .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim() + " "
}

/** Umlaute auf ae/oe/ue/ss abbilden – Spracherkennung und Kartennamen schreiben das unterschiedlich. */
internal fun fold(text: String): String =
    text.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")

/** Enthält [haystack] (normalisiert) das Wort/die Wortfolge [phrase] als ganze Wörter? */
internal fun containsPhrase(haystack: String, phrase: String): Boolean {
    val h = fold(haystack)
    val p = fold(phrase.trim())
    if (p.isEmpty()) return false
    // Zusammengesetzte deutsche Wörter: "Damentoilette", "Besprechungsraum2" usw.
    if (p.length >= 6 && !p.contains(' ') && h.contains(p)) return true
    return h.contains(" $p ") || h.contains(" ${p}s ") || h.contains(" ${p}n ")
}

/** Alle Kartenpunkte, eingeteilt nach Art. */
class PlaceCatalog(
    places: List<MapPlace>,
    /** Name des Warteraums aus den App-Einstellungen (hat Vorrang vor der Synonymsuche) */
    private val waitingRoomName: String = "",
    /** Name des Empfangspunkts aus den App-Einstellungen */
    private val receptionName: String = "",
) {
    val destinations: List<Destination> = places
        .filter { it.name.isNotBlank() }
        .distinctBy { it.name }
        .map { Destination(it, classify(it.name)) }
        .sortedWith(compareBy({ it.kind.order }, { it.name.lowercase() }))

    val isEmpty: Boolean get() = destinations.isEmpty()

    fun names(): List<String> = destinations.map { it.name }

    fun byName(name: String): Destination? =
        destinations.firstOrNull { it.name == name }
            ?: destinations.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Alle Kartenpunkte einer Art (meist genau einer). */
    fun ofKind(kind: PlaceKind): List<Destination> = destinations.filter { it.kind == kind && it.place.reachable }

    /** Ziele für die Auswahl auf dem Bildschirm (ohne den Empfang, dort steht der Roboter ja). */
    fun touchDestinations(): List<Destination> =
        destinations.filter { it.place.reachable && it.kind != PlaceKind.RECEPTION }

    /** Der Empfangspunkt (für die Rückfahrt). */
    fun reception(): Destination? = byName(receptionName) ?: ofKind(PlaceKind.RECEPTION).firstOrNull()

    /** Der Warteraum – erst der eingestellte Name, sonst ein Punkt mit passendem Namen. */
    fun waitingRoom(): Destination? = byName(waitingRoomName) ?: ofKind(PlaceKind.WAITING_ROOM).firstOrNull()

    private fun classify(name: String): PlaceKind {
        if (receptionName.isNotBlank() && name.equals(receptionName, ignoreCase = true)) return PlaceKind.RECEPTION
        if (waitingRoomName.isNotBlank() && name.equals(waitingRoomName, ignoreCase = true)) return PlaceKind.WAITING_ROOM
        val n = normalize(name)
        // Spezifische Arten zuerst prüfen ("Charging Pole" soll nicht als etwas anderes gelten)
        val checkOrder = listOf(
            PlaceKind.CHARGING, PlaceKind.RECEPTION, PlaceKind.WAITING_ROOM, PlaceKind.RESTROOM, PlaceKind.MEETING_ROOM,
            PlaceKind.KITCHEN, PlaceKind.ELEVATOR, PlaceKind.EXIT, PlaceKind.ENTRANCE, PlaceKind.OFFICE,
        )
        return checkOrder.firstOrNull { kind -> kind.synonyms.any { containsPhrase(n, it) } } ?: PlaceKind.OTHER
    }

    /**
     * Welche Ziele meint der Text? Zuerst exakte Kartennamen („Gate Exit“), dann Arten
     * über Synonyme („Ausgang“). Liefert die erkannte Art auch dann, wenn es dazu keinen
     * Kartenpunkt gibt (damit der Roboter sagen kann: „Den Warteraum kenne ich noch nicht“).
     */
    fun match(text: String): GuideRequest? {
        val n = normalize(text)
        // 1) Kartenname direkt genannt (längster Name zuerst: "Gate Exit" vor "Exit")
        val byName = destinations
            .filter { it.place.reachable && nameMentioned(n, it.name) }
            .sortedByDescending { it.name.length }
        if (byName.isNotEmpty()) {
            val best = byName.first()
            return GuideRequest(listOf(best), best.kind)
        }
        // 2) Art über Synonyme
        val kind = PlaceKind.entries
            .filter { it != PlaceKind.OTHER }
            .firstOrNull { k -> k.synonyms.any { containsPhrase(n, it) } }
            ?: needPhraseKind(n)
            ?: return null
        val candidates = when (kind) {
            PlaceKind.WAITING_ROOM -> listOfNotNull(waitingRoom())
            PlaceKind.RECEPTION -> listOfNotNull(reception())
            else -> ofKind(kind)
        }
        return GuideRequest(candidates, kind)
    }

    private fun nameMentioned(normalizedText: String, placeName: String): Boolean {
        val name = normalize(placeName).trim()
        if (name.length < 2) return false
        if (containsPhrase(normalizedText, name)) return true
        // "Gate Exit" auch erkennen, wenn nur der markante Teil fällt? Nein – das erledigen die
        // Synonyme. Hier nur Namen ohne Leerzeichen-Probleme ("Lab3" vs. "Lab 3"):
        val compactName = fold(name).replace(" ", "")
        val compactText = fold(normalizedText).replace(" ", "")
        return compactName.length >= 4 && compactText.contains(compactName)
    }

    /** Umschreibungen ohne Ortsnamen: „Ich muss mal“, „Wie komme ich hier raus?“ */
    private fun needPhraseKind(n: String): PlaceKind? {
        val f = fold(n)
        return when {
            Regex(" (ich )?muss (mal|kurz mal|mal kurz) ").containsMatchIn(f) && !f.contains(" muss mal sagen") -> PlaceKind.RESTROOM
            Regex(" (haende|hände) waschen | frisch machen | frischmachen | wash my hands ").containsMatchIn(n) -> PlaceKind.RESTROOM
            Regex(" (wie komme ich|wo geht es|wo gehts|wo geht's) (hier )?(raus|hinaus|nach draussen) ").containsMatchIn(f) -> PlaceKind.EXIT
            Regex(" (how do i get|how can i get|the way) out ").containsMatchIn(f) -> PlaceKind.EXIT
            else -> null
        }
    }
}

/**
 * Erkennt, ob ein Besucher nach dem Weg fragt oder hingebracht werden möchte.
 *
 * Damit „Ich habe einen Termin im Besprechungsraum“ nicht versehentlich eine Fahrt
 * auslöst, braucht es zusätzlich zum Ort eine Weg-Frage („Wo ist …“, „Bringen Sie mich …“)
 * oder eine sehr kurze Äußerung, die nur aus dem Ort besteht („Toilette?“).
 */
object GuideNlu {

    /** Eindeutige Bitten um Weg/Begleitung */
    private val STRONG = Regex(
        listOf(
            // Deutsch
            "wo (ist|sind|befindet sich|befinden sich|finde ich|gibt es|geht es|gehts|geht's|kann ich)",
            "wie komme ich", "wie kommt man", "wie gelange ich", "den weg (zu|zum|zur|in|ins|nach)", "weg zum", "weg zur",
            "(bring|bringe|bringen sie|bringst du|führ|führe|führen sie|führst du|begleite|begleiten sie|begleitest du) (mich|uns)",
            "(zeig|zeige|zeigen sie|zeigst du) (mir|uns)",
            "kannst du mich", "können sie mich", "könnten sie mich", "würden sie mich",
            "(fahr|fahre|fahren sie|geh|gehe|gehen sie) (zu|zum|zur|in|ins|nach)",
            "ich muss (mal |kurz |dringend )*(zu|zum|zur|auf|aufs|in|ins)",
            "(muss|möchte|will|würde gern|würde gerne) (mal |kurz |bitte )*(zu|zum|zur|auf|aufs|in|ins) (die|der|das|den|dem)?",
            "ich muss (mal|kurz mal|mal kurz)( |$)", "wohin",
            // Englisch
            "where (is|are|'s|can i find|do i find|can i|do i)", "where's",
            "how (do|can) (i|we) get", "how do i find", "the way to", "directions to",
            "(take|bring|show|lead|guide|walk|escort) (me|us)",
            "(go|drive) to", "i need to go", "i have to go", "need the", "looking for", "i'm looking", "i am looking",
        ).joinToString("|") { "(?:$it)" },
    )

    /** Füllwörter, die eine „nur der Ort“-Äußerung nicht länger machen sollen */
    private val FILLER = setOf(
        "ähm", "äh", "hm", "also", "bitte", "die", "der", "das", "den", "dem", "zur", "zum", "zu", "ein", "eine", "einen", "the",
        "a", "an", "please", "hallo", "hi", "hello", "ja", "yes", "ok", "okay", "danke", "thanks", "mal", "kurz", "unser", "eure",
        "ihr", "ihre", "your", "nach", "to",
    )

    private val HONORIFIC = Regex("\\b(frau|herr|herrn|mr|mrs|ms|dr)\\b")

    fun hasStrongPhrase(text: String): Boolean = STRONG.containsMatchIn(normalize(text))

    /**
     * Liefert eine [GuideRequest], wenn der Besucher nach einem Ort fragt, sonst null.
     *
     * @param inCheckIn true, wenn gerade die Anmeldung läuft (Name/Ansprechpartner …). Dann reicht
     *                  ein bloßer Ortsname nicht – es muss eine Weg-Frage dabei sein.
     * @param mentionsVisitPurpose true, wenn derselbe Satz auch ein Anmelde-Anliegen enthält
     *                  („Ich habe einen Termin …“). Dann ist der Warteraum kein eigener Wunsch –
     *                  die Anmeldung endet ohnehin dort.
     */
    fun detect(
        text: String,
        catalog: PlaceCatalog,
        inCheckIn: Boolean = false,
        mentionsVisitPurpose: Boolean = false,
    ): GuideRequest? {
        val n = normalize(text)
        val request = catalog.match(text) ?: return genericRequest(n)
        val strong = STRONG.containsMatchIn(n)
        val meaningfulWords = n.trim().split(" ").filter { it.isNotBlank() && it !in FILLER }
        val shortPlaceOnly = !inCheckIn && meaningfulWords.size <= 3
        val implicitNeed = request.kind == PlaceKind.RESTROOM && Regex(" (muss mal|wash my hands|hände waschen) ").containsMatchIn(n)
        if (!strong && !shortPlaceOnly && !implicitNeed) return null
        // Termin-Anliegen + Warteraum → normale Anmeldung (endet ohnehin im Warteraum)
        if (mentionsVisitPurpose && request.kind == PlaceKind.WAITING_ROOM) return null
        // "Wo ist Frau Schmidt im Büro?" → Ansprechpartner gesucht, nicht der Raum
        if (request.kind == PlaceKind.OFFICE && HONORIFIC.containsMatchIn(n)) return null
        return request
    }

    /** „Können Sie mich wohin bringen?“ ohne Ort → Auswahl auf dem Bildschirm anbieten. */
    private fun genericRequest(n: String): GuideRequest? {
        val asksForGuidance = Regex(
            " (wegweiser|wegbeschreibung|orientierung|wohin|zeigen sie mir den weg|zeig mir den weg|show me around|directions|guide me|take me somewhere) ",
        ).containsMatchIn(n)
        return if (asksForGuidance) GuideRequest() else null
    }
}
