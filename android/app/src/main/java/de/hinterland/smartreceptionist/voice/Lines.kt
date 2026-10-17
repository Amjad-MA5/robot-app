package de.hinterland.smartreceptionist.voice

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Alle Sätze, die der Roboter sagt oder anzeigt – auf Deutsch und Englisch. */
interface Lines {
    val locale: Locale

    // Ruhezustand
    val idleTitle: String
    val idleSubtitle: String

    fun greeting(company: String, robot: String): String

    // Anliegen
    val askAppointment: String
    val optHaveAppointment: String
    val optWalkIn: String
    val optBook: String
    val askWalkInOrBook: String
    val otherRequest: String

    // Name
    val askName: String
    val nameHint: String
    fun nameThanks(name: String): String

    // Ansprechpartner
    val askEmployeeAppointment: String
    val askEmployeeWalkIn: String
    val askEmployeeBook: String
    val employeeHint: String
    fun employeeNotFound(query: String): String
    fun confirmEmployee(name: String): String
    val chooseEmployee: String
    val showAllEmployees: String
    fun employeeOk(name: String): String

    // Szenario 1
    fun arrivalNotified(employee: String): String
    fun arrivalFailed(employee: String): String

    // Begleitung
    val followMe: String
    val followMeTitle: String
    fun arrivedWaitingRoom(employee: String): String
    val arrivedWaitingRoomGeneric: String
    val returningTitle: String
    val navFailed: String
    val pathBlocked: String
    val stopButton: String

    // Szenario 2
    fun walkInFreeNow(employee: String): String
    fun walkInLater(employee: String, time: String): String
    fun walkInNotifyFailed(employee: String): String
    val askEscort: String
    val optEscortYes: String
    val optEscortNo: String

    // Szenario 3
    fun notAvailableToday(employee: String): String
    fun proposeSlot(employee: String, slot: String): String
    val moreSlotsOnScreen: String
    val optMoreSlots: String
    fun noSlots(employee: String): String
    fun booked(employee: String, slot: String): String
    val bookFailed: String
    val slotTaken: String

    // Allgemein
    val goodbye: String
    val didNotUnderstand: String
    val stillThere: String
    val noResponse: String
    val backendDown: String
    val thinking: String
    val listening: String
    val yes: String
    val no: String
    val cancel: String
    val next: String
    val typeHere: String
    val tapToStart: String
    val summaryTitle: String
    val summaryVisitor: String
    val summaryEmployee: String
    val summaryWhen: String

    fun formatSlot(slot: OffsetDateTime, today: LocalDate = LocalDate.now(slot.offset)): String
    fun formatTime(t: OffsetDateTime): String
    fun formatSlotShort(slot: OffsetDateTime): String
}

object GermanLines : Lines {
    override val locale: Locale = Locale.GERMANY
    override val idleTitle = "Herzlich willkommen!"
    override val idleSubtitle = "Sprechen Sie mich an oder tippen Sie auf den Bildschirm."
    override fun greeting(company: String, robot: String) =
        "Hallo und herzlich willkommen bei $company! Ich bin $robot, die digitale Empfangskraft."

    override val askAppointment = "Haben Sie einen Termin bei uns?"
    override val optHaveAppointment = "Ja, ich habe einen Termin"
    override val optWalkIn = "Nein, ich möchte jemanden sprechen"
    override val optBook = "Ich möchte einen Termin vereinbaren"
    override val askWalkInOrBook = "Möchten Sie jetzt jemanden sprechen oder einen Termin vereinbaren?"
    override val otherRequest = "Dabei kann ich leider noch nicht helfen – ich kümmere mich um die Anmeldung von Besuchern."

    override val askName = "Wie ist Ihr Name, bitte?"
    override val nameHint = "Ihr Name"
    override fun nameThanks(name: String) = "Danke, $name."

    override val askEmployeeAppointment = "Bei wem haben Sie Ihren Termin?"
    override val askEmployeeWalkIn = "Mit wem möchten Sie sprechen?"
    override val askEmployeeBook = "Bei wem möchten Sie einen Termin vereinbaren?"
    override val employeeHint = "Name des Ansprechpartners"
    override fun employeeNotFound(query: String) =
        "Ich habe leider niemanden mit dem Namen $query gefunden. Bitte wählen Sie auf dem Bildschirm aus oder nennen Sie den Namen noch einmal."
    override fun confirmEmployee(name: String) = "Meinen Sie $name?"
    override val chooseEmployee = "Ich habe mehrere passende Personen gefunden. Bitte tippen Sie auf die richtige."
    override val showAllEmployees = "Alle Mitarbeitenden anzeigen"
    override fun employeeOk(name: String) = "Alles klar, $name."

    override fun arrivalNotified(employee: String) = "Vielen Dank! Ich habe $employee Bescheid gegeben, dass Sie da sind."
    override fun arrivalFailed(employee: String) =
        "Ich konnte $employee leider nicht automatisch erreichen. Bitte nehmen Sie trotzdem im Warteraum Platz."

    override val followMe = "Bitte folgen Sie mir, ich bringe Sie in den Warteraum."
    override val followMeTitle = "Bitte folgen Sie mir"
    override fun arrivedWaitingRoom(employee: String) =
        "Hier ist unser Warteraum. Bitte nehmen Sie Platz – $employee kommt gleich zu Ihnen. Einen angenehmen Aufenthalt!"
    override val arrivedWaitingRoomGeneric = "Hier ist unser Warteraum. Bitte nehmen Sie Platz. Einen angenehmen Aufenthalt!"
    override val returningTitle = "Ich fahre zurück zum Empfang"
    override val navFailed = "Entschuldigung, ich kann gerade nicht losfahren. Der Warteraum ist gleich hier in der Nähe – bitte nehmen Sie dort Platz."
    override val pathBlocked = "Entschuldigung, darf ich bitte vorbei?"
    override val stopButton = "Stopp"

    override fun walkInFreeNow(employee: String) =
        "$employee hat laut Kalender gerade Zeit. Ich habe Bescheid gegeben, dass Sie hier sind und nach einem Gespräch fragen."
    override fun walkInLater(employee: String, time: String) =
        "$employee ist heute ab $time Uhr frei. Ich habe Bescheid gegeben, dass Sie nach einem Gespräch gefragt haben."
    override fun walkInNotifyFailed(employee: String) =
        "$employee hat heute noch Zeit, aber ich konnte leider gerade keine Nachricht schicken."
    override val askEscort = "Möchten Sie im Warteraum Platz nehmen?"
    override val optEscortYes = "Ja, bitte begleiten"
    override val optEscortNo = "Nein, danke"

    override fun notAvailableToday(employee: String) =
        "$employee ist heute leider ausgebucht. Ich kann Ihnen aber gern einen Termin anbieten."
    override fun proposeSlot(employee: String, slot: String) = "Der nächste freie Termin bei $employee ist $slot. Passt Ihnen das?"
    override val moreSlotsOnScreen = "Weitere Termine sehen Sie auf dem Bildschirm."
    override val optMoreSlots = "Spätere Termine"
    override fun noSlots(employee: String) =
        "Leider habe ich in den nächsten Tagen keinen freien Termin bei $employee gefunden. Bitte melden Sie sich direkt bei $employee."
    override fun booked(employee: String, slot: String) =
        "Wunderbar! Ich habe den Termin $slot bei $employee eingetragen, und $employee wurde informiert."
    override val bookFailed = "Der Termin konnte leider nicht eingetragen werden."
    override val slotTaken = "Dieser Termin wurde gerade vergeben. Ich schaue nach einem anderen."

    override val goodbye = "Vielen Dank für Ihren Besuch und einen schönen Tag!"
    override val didNotUnderstand = "Entschuldigung, das habe ich nicht verstanden."
    override val stillThere = "Sind Sie noch da?"
    override val noResponse = "Ich bin hier, falls Sie mich brauchen."
    override val backendDown = "Ich habe gerade leider keine Verbindung zu unserem System."
    override val thinking = "Einen Moment …"
    override val listening = "Ich höre zu …"
    override val yes = "Ja"
    override val no = "Nein"
    override val cancel = "Abbrechen"
    override val next = "Weiter"
    override val typeHere = "Hier eintippen"
    override val tapToStart = "Antippen zum Starten"
    override val summaryTitle = "Ihr Termin"
    override val summaryVisitor = "Besucher"
    override val summaryEmployee = "Bei"
    override val summaryWhen = "Wann"

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    override fun formatTime(t: OffsetDateTime): String = t.format(timeFmt)

    override fun formatSlot(slot: OffsetDateTime, today: LocalDate): String {
        val time = formatTime(slot)
        return when (ChronoUnit.DAYS.between(today, slot.toLocalDate())) {
            0L -> "heute um $time Uhr"
            1L -> "morgen um $time Uhr"
            else -> {
                val weekday = slot.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                val month = slot.month.getDisplayName(TextStyle.FULL, locale)
                "am $weekday, den ${slot.dayOfMonth}. $month, um $time Uhr"
            }
        }
    }

    override fun formatSlotShort(slot: OffsetDateTime): String {
        val weekday = slot.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        return "$weekday ${slot.format(DateTimeFormatter.ofPattern("dd.MM."))} · ${formatTime(slot)}"
    }
}

object EnglishLines : Lines {
    override val locale: Locale = Locale.UK
    override val idleTitle = "Welcome!"
    override val idleSubtitle = "Talk to me or tap the screen."
    override fun greeting(company: String, robot: String) =
        "Hello and welcome to $company! I'm $robot, the digital receptionist."

    override val askAppointment = "Do you have an appointment with us?"
    override val optHaveAppointment = "Yes, I have an appointment"
    override val optWalkIn = "No, I'd like to talk to someone"
    override val optBook = "I'd like to make an appointment"
    override val askWalkInOrBook = "Would you like to talk to someone now, or make an appointment?"
    override val otherRequest = "I'm sorry, I can't help with that yet – I take care of visitor check-in."

    override val askName = "May I have your name, please?"
    override val nameHint = "Your name"
    override fun nameThanks(name: String) = "Thank you, $name."

    override val askEmployeeAppointment = "Who is your appointment with?"
    override val askEmployeeWalkIn = "Who would you like to talk to?"
    override val askEmployeeBook = "Who would you like to make an appointment with?"
    override val employeeHint = "Name of your contact"
    override fun employeeNotFound(query: String) =
        "Sorry, I couldn't find anyone called $query. Please pick from the screen or say the name again."
    override fun confirmEmployee(name: String) = "Do you mean $name?"
    override val chooseEmployee = "I found several matching people. Please tap the right one."
    override val showAllEmployees = "Show all employees"
    override fun employeeOk(name: String) = "Alright, $name."

    override fun arrivalNotified(employee: String) = "Thank you! I've let $employee know that you're here."
    override fun arrivalFailed(employee: String) =
        "Unfortunately I couldn't reach $employee automatically. Please take a seat in the waiting room anyway."

    override val followMe = "Please follow me, I'll take you to the waiting room."
    override val followMeTitle = "Please follow me"
    override fun arrivedWaitingRoom(employee: String) =
        "This is our waiting room. Please have a seat – $employee will be with you shortly. Enjoy your stay!"
    override val arrivedWaitingRoomGeneric = "This is our waiting room. Please have a seat. Enjoy your stay!"
    override val returningTitle = "Heading back to reception"
    override val navFailed = "Sorry, I can't move right now. The waiting room is just nearby – please take a seat there."
    override val pathBlocked = "Excuse me, may I pass, please?"
    override val stopButton = "Stop"

    override fun walkInFreeNow(employee: String) =
        "According to the calendar, $employee is free right now. I've let them know you're here and would like to talk."
    override fun walkInLater(employee: String, time: String) =
        "$employee is free today from $time. I've let them know you asked for a conversation."
    override fun walkInNotifyFailed(employee: String) =
        "$employee still has time today, but unfortunately I couldn't send a message right now."
    override val askEscort = "Would you like to take a seat in the waiting room?"
    override val optEscortYes = "Yes, please take me there"
    override val optEscortNo = "No, thank you"

    override fun notAvailableToday(employee: String) =
        "Unfortunately $employee is fully booked today. But I'm happy to offer you an appointment."
    override fun proposeSlot(employee: String, slot: String) = "The next free slot with $employee is $slot. Does that work for you?"
    override val moreSlotsOnScreen = "You can see more options on the screen."
    override val optMoreSlots = "Later dates"
    override fun noSlots(employee: String) =
        "Sorry, I couldn't find a free slot with $employee in the next days. Please contact $employee directly."
    override fun booked(employee: String, slot: String) =
        "Wonderful! I've booked the appointment $slot with $employee, and $employee has been notified."
    override val bookFailed = "Sorry, the appointment could not be booked."
    override val slotTaken = "That slot was just taken. Let me look for another one."

    override val goodbye = "Thank you for your visit and have a nice day!"
    override val didNotUnderstand = "Sorry, I didn't catch that."
    override val stillThere = "Are you still there?"
    override val noResponse = "I'm here if you need me."
    override val backendDown = "Sorry, I can't reach our system right now."
    override val thinking = "One moment …"
    override val listening = "I'm listening …"
    override val yes = "Yes"
    override val no = "No"
    override val cancel = "Cancel"
    override val next = "Next"
    override val typeHere = "Type here"
    override val tapToStart = "Tap to start"
    override val summaryTitle = "Your appointment"
    override val summaryVisitor = "Visitor"
    override val summaryEmployee = "With"
    override val summaryWhen = "When"

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    override fun formatTime(t: OffsetDateTime): String = t.format(timeFmt)

    override fun formatSlot(slot: OffsetDateTime, today: LocalDate): String {
        val time = formatTime(slot)
        return when (ChronoUnit.DAYS.between(today, slot.toLocalDate())) {
            0L -> "today at $time"
            1L -> "tomorrow at $time"
            else -> {
                val weekday = slot.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                val month = slot.month.getDisplayName(TextStyle.FULL, locale)
                "on $weekday, $month ${slot.dayOfMonth}, at $time"
            }
        }
    }

    override fun formatSlotShort(slot: OffsetDateTime): String {
        val weekday = slot.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        return "$weekday ${slot.format(DateTimeFormatter.ofPattern("dd/MM"))} · ${formatTime(slot)}"
    }
}

fun linesFor(language: String): Lines = if (language == "en") EnglishLines else GermanLines
