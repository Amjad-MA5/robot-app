package de.hinterland.smartreceptionist.guide

/**
 * Sätze der Wegweiser-Funktion (Deutsch + Englisch).
 *
 * Bewusst in einer eigenen Datei statt in voice/Lines.kt, damit parallele Änderungen
 * im Team (Übersetzungen, Teams-Anbindung) sich nicht in die Quere kommen.
 */
interface GuideLines {
    /** Knopf auf der Startfrage */
    val optGuide: String
    val askWhere: String
    val chooseOnScreen: String
    fun startGuiding(d: Destination): String
    fun arrived(d: Destination): String
    fun alreadyHere(d: Destination): String
    fun notOnMap(kind: PlaceKind): String
    val unknownPlace: String
    fun navFailed(d: Destination): String
    val noPlaces: String
    val robotOffline: String
    fun whichOne(kind: PlaceKind): String
    /** Anzeigename eines Ziels (Kartenname bleibt in Klammern sichtbar, wenn er abweicht) */
    fun label(d: Destination): String
}

object GermanGuideLines : GuideLines {
    override val optGuide = "🧭 Wo finde ich …?"
    override val askWhere = "Wohin darf ich Sie bringen?"
    override val chooseOnScreen = "Tippen Sie einfach auf ein Ziel oder sagen Sie es mir."
    override fun startGuiding(d: Destination) = when (d.kind) {
        PlaceKind.EXIT -> "Gerne! Ich begleite Sie ${to(d)}. Bitte folgen Sie mir."
        PlaceKind.CHARGING -> "Alles klar, ich fahre ${to(d)}."
        else -> "Gerne! Bitte folgen Sie mir, ich bringe Sie ${to(d)}."
    }
    override fun arrived(d: Destination) = when (d.kind) {
        PlaceKind.RESTROOM -> "Hier sind die Toiletten. Ich fahre wieder zurück zum Empfang."
        PlaceKind.WAITING_ROOM -> "Hier ist unser Warteraum. Bitte nehmen Sie Platz. Einen angenehmen Aufenthalt!"
        PlaceKind.EXIT -> "Hier ist der Ausgang. Vielen Dank für Ihren Besuch und auf Wiedersehen!"
        PlaceKind.ENTRANCE -> "Hier ist der Eingang."
        PlaceKind.OFFICE -> "Hier ist das Büro."
        PlaceKind.MEETING_ROOM -> "Hier ist der Besprechungsraum."
        PlaceKind.KITCHEN -> "Hier ist die Küche. Lassen Sie es sich schmecken!"
        PlaceKind.ELEVATOR -> "Hier ist der Aufzug."
        PlaceKind.RECEPTION -> "Hier ist der Empfang. Wie kann ich Ihnen helfen?"
        PlaceKind.CHARGING -> "Hier ist die Ladestation."
        PlaceKind.OTHER -> "Wir sind da: ${d.name}."
    }
    override fun alreadyHere(d: Destination) = when (d.kind) {
        PlaceKind.RECEPTION -> "Wir sind bereits am Empfang – ich bin hier die Empfangskraft."
        else -> "Wir sind schon da: ${label(d)}."
    }
    override fun notOnMap(kind: PlaceKind) =
        "${capitalized(article(kind))} habe ich leider noch nicht auf meiner Karte. Wohin darf ich Sie sonst bringen?"
    override val unknownPlace = "Diesen Ort kenne ich leider nicht. Wohin darf ich Sie bringen?"
    override fun navFailed(d: Destination) = "Entschuldigung, ich komme gerade nicht ${to(d)}. Bitte fragen Sie kurz eine Kollegin oder einen Kollegen."
    override val noPlaces = "Ich habe gerade keine Ziele auf meiner Karte."
    override val robotOffline = "Entschuldigung, ich kann gerade nicht losfahren."
    override fun whichOne(kind: PlaceKind) = "Es gibt mehrere davon. Welches meinen Sie?"

    override fun label(d: Destination): String = when (d.kind) {
        PlaceKind.WAITING_ROOM -> "Warteraum"
        PlaceKind.RESTROOM -> "Toiletten"
        PlaceKind.EXIT -> "Ausgang"
        PlaceKind.ENTRANCE -> "Eingang"
        PlaceKind.RECEPTION -> "Empfang"
        PlaceKind.CHARGING -> "Ladestation"
        PlaceKind.KITCHEN -> "Küche"
        PlaceKind.ELEVATOR -> "Aufzug"
        PlaceKind.OFFICE -> if (d.name.equals("Office", true)) "Büro" else d.name
        PlaceKind.MEETING_ROOM, PlaceKind.OTHER -> d.name
    }

    /** „zur Toilette“, „zum Ausgang“ … */
    private fun to(d: Destination): String = when (d.kind) {
        PlaceKind.WAITING_ROOM -> "in den Warteraum"
        PlaceKind.RESTROOM -> "zu den Toiletten"
        PlaceKind.EXIT -> "zum Ausgang"
        PlaceKind.ENTRANCE -> "zum Eingang"
        PlaceKind.RECEPTION -> "zum Empfang"
        PlaceKind.CHARGING -> "zur Ladestation"
        PlaceKind.KITCHEN -> "in die Küche"
        PlaceKind.ELEVATOR -> "zum Aufzug"
        PlaceKind.OFFICE -> if (d.name.equals("Office", true) || d.name.equals("Büro", true)) "ins Büro" else "zu ${d.name}"
        PlaceKind.MEETING_ROOM -> "in den Besprechungsraum"
        PlaceKind.OTHER -> "zu ${d.name}"
    }

    private fun article(kind: PlaceKind): String = when (kind) {
        PlaceKind.WAITING_ROOM -> "den Warteraum"
        PlaceKind.RESTROOM -> "die Toiletten"
        PlaceKind.EXIT -> "den Ausgang"
        PlaceKind.ENTRANCE -> "den Eingang"
        PlaceKind.RECEPTION -> "den Empfang"
        PlaceKind.CHARGING -> "die Ladestation"
        PlaceKind.KITCHEN -> "die Küche"
        PlaceKind.ELEVATOR -> "den Aufzug"
        PlaceKind.OFFICE -> "das Büro"
        PlaceKind.MEETING_ROOM -> "den Besprechungsraum"
        PlaceKind.OTHER -> "diesen Ort"
    }

    private fun capitalized(s: String) = s.replaceFirstChar { it.titlecase() }
}

object EnglishGuideLines : GuideLines {
    override val optGuide = "🧭 Where can I find …?"
    override val askWhere = "Where would you like to go?"
    override val chooseOnScreen = "Just tap a destination or tell me."
    override fun startGuiding(d: Destination) = when (d.kind) {
        PlaceKind.EXIT -> "Of course! I'll walk you to the exit. Please follow me."
        PlaceKind.CHARGING -> "Alright, heading to the charging station."
        else -> "Of course! Please follow me, I'll take you to ${the(d)}."
    }
    override fun arrived(d: Destination) = when (d.kind) {
        PlaceKind.RESTROOM -> "Here are the restrooms. I'll head back to reception now."
        PlaceKind.WAITING_ROOM -> "This is our waiting room. Please have a seat. Enjoy your stay!"
        PlaceKind.EXIT -> "Here is the exit. Thank you for your visit and goodbye!"
        PlaceKind.RECEPTION -> "This is reception. How can I help you?"
        PlaceKind.OTHER -> "Here we are: ${d.name}."
        else -> "Here is ${the(d)}."
    }
    override fun alreadyHere(d: Destination) = when (d.kind) {
        PlaceKind.RECEPTION -> "We're already at reception – I'm the receptionist here."
        else -> "We're already there: ${label(d)}."
    }
    override fun notOnMap(kind: PlaceKind) =
        "Sorry, ${the(kind)} isn't on my map yet. Where else can I take you?"
    override val unknownPlace = "Sorry, I don't know that place. Where would you like to go?"
    override fun navFailed(d: Destination) = "Sorry, I can't get to ${the(d)} right now. Please ask a member of staff."
    override val noPlaces = "I don't have any destinations on my map right now."
    override val robotOffline = "Sorry, I can't move right now."
    override fun whichOne(kind: PlaceKind) = "There are several. Which one do you mean?"

    override fun label(d: Destination): String = when (d.kind) {
        PlaceKind.WAITING_ROOM -> "Waiting room"
        PlaceKind.RESTROOM -> "Restrooms"
        PlaceKind.EXIT -> "Exit"
        PlaceKind.ENTRANCE -> "Entrance"
        PlaceKind.RECEPTION -> "Reception"
        PlaceKind.CHARGING -> "Charging station"
        PlaceKind.KITCHEN -> "Kitchen"
        PlaceKind.ELEVATOR -> "Elevator"
        PlaceKind.OFFICE, PlaceKind.MEETING_ROOM, PlaceKind.OTHER -> d.name
    }

    private fun the(d: Destination): String = when (d.kind) {
        PlaceKind.OFFICE, PlaceKind.MEETING_ROOM, PlaceKind.OTHER -> if (d.name.equals("Office", true)) "the office" else d.name
        else -> the(d.kind)
    }

    private fun the(kind: PlaceKind): String = when (kind) {
        PlaceKind.WAITING_ROOM -> "the waiting room"
        PlaceKind.RESTROOM -> "the restrooms"
        PlaceKind.EXIT -> "the exit"
        PlaceKind.ENTRANCE -> "the entrance"
        PlaceKind.RECEPTION -> "reception"
        PlaceKind.CHARGING -> "the charging station"
        PlaceKind.KITCHEN -> "the kitchen"
        PlaceKind.ELEVATOR -> "the elevator"
        PlaceKind.OFFICE -> "the office"
        PlaceKind.MEETING_ROOM -> "the meeting room"
        PlaceKind.OTHER -> "that place"
    }
}

fun guideLinesFor(language: String): GuideLines = if (language == "en") EnglishGuideLines else GermanGuideLines
