package de.hinterland.smartreceptionist.flow

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ainirobot.agent.AgentCore
import de.hinterland.smartreceptionist.config.AppSettings
import de.hinterland.smartreceptionist.net.BackendClient
import de.hinterland.smartreceptionist.net.BackendException
import de.hinterland.smartreceptionist.net.Employee
import de.hinterland.smartreceptionist.net.Slot
import de.hinterland.smartreceptionist.robot.NavEvent
import de.hinterland.smartreceptionist.robot.NavResult
import de.hinterland.smartreceptionist.robot.RobotController
import de.hinterland.smartreceptionist.voice.Intent
import de.hinterland.smartreceptionist.voice.Lines
import de.hinterland.smartreceptionist.voice.RuleNlu
import de.hinterland.smartreceptionist.voice.Step
import de.hinterland.smartreceptionist.voice.Understander
import de.hinterland.smartreceptionist.voice.Understanding
import de.hinterland.smartreceptionist.voice.linesFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Herzstück der App: steuert das Gespräch mit dem Besucher als Ablauf (Coroutine).
 *
 *  Ruhe → Person erkannt → Begrüßung → Anliegen → Name → Ansprechpartner →
 *   ① Termin vorhanden:  Teams-Nachricht → Warteraum → zurück zum Empfang
 *   ② ohne Termin:       Kalender prüfen → heute frei? Mitarbeiter informieren (→ optional Warteraum)
 *   ③ Termin vereinbaren: freie Termine aus dem Kalender vorschlagen → eintragen
 *
 * Jede Frage kann per Sprache ODER Touch beantwortet werden.
 */
class ReceptionViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    var settings: AppSettings = AppSettings.load(app)
        private set
    private val lines: Lines get() = linesFor(settings.language)
    private var backend = BackendClient(settings.backendUrl, settings.apiKey)
    private var companyName: String = settings.companyName
    private val understander = Understander { settings.useLlm }

    private val inputs = Channel<UserInput>(Channel.UNLIMITED)
    private var conversationJob: Job? = null
    private var detectionJob: Job? = null
    private var healthJob: Job? = null
    private var inForeground = false

    /** Nach einem Gespräch erst wieder begrüßen, wenn die Person weg ist (oder nach Ablauf der Zeit). */
    private var rearmAt = 0L
    private var needPersonGone = false
    private var lastBlockedSay = 0L

    init {
        showIdle()
        viewModelScope.launch {
            RobotController.connected.collect { ok -> _ui.update { it.copy(robotOk = ok) } }
        }
        viewModelScope.launch {
            RobotController.suspended.collect { suspended ->
                // System übernimmt (Not-Aus, Akku, Laden …): laufendes Gespräch sauber beenden.
                if (suspended && conversationJob?.isActive == true) {
                    conversationJob?.cancel()
                    showIdle()
                }
            }
        }
    }

    // =============================================================== Lebenszyklus
    fun onForeground() {
        inForeground = true
        applyAgentDefaults()
        RobotController.resetHead()
        startDetection()
        startHealthChecks()
    }

    fun onBackground() {
        inForeground = false
        cancelConversation()
        detectionJob?.cancel()
        healthJob?.cancel()
    }

    private fun applyAgentDefaults() {
        if (!RobotController.isCoreServiceInstalled(getApplication())) return
        runCatching {
            // Wir werten Spracheingaben selbst aus (Ablaufsteuerung), daher keine automatische Action-Planung.
            AgentCore.isDisablePlan = true
            AgentCore.isEnableVoiceBar = false
            AgentCore.isMicrophoneMuted = false
            AgentCore.clearContext()
        }.onFailure { Log.w(TAG, "AgentCore nicht verfügbar", it) }
    }

    fun updateSettings(newSettings: AppSettings) {
        AppSettings.save(getApplication(), newSettings)
        settings = AppSettings.load(getApplication())
        backend = BackendClient(settings.backendUrl, settings.apiKey)
        companyName = settings.companyName
        if (_ui.value.phase == Phase.IDLE) showIdle()
        startHealthChecks()
    }

    fun backendClient(): BackendClient = backend

    /** Admin-Bereich offen → keine Begrüßung, keine Spracheingaben verarbeiten */
    private var adminOpen = false

    fun setAdminOpen(open: Boolean) {
        adminOpen = open
        if (open) cancel()
    }

    // ============================================================ Eingänge (UI/SDK)
    /** ASR-Ergebnis vom AgentOS-SDK (läuft auf einem Worker-Thread). */
    fun onAsr(text: String, final: Boolean) {
        if (text.isBlank() || adminOpen) return
        val phase = _ui.value.phase
        if (phase == Phase.ESCORT || phase == Phase.RETURNING) return
        _ui.update { it.copy(heard = text) }
        if (!final) return
        if (phase == Phase.IDLE) {
            // Kurz nach einem Gespräch ("Danke, tschüss!") nicht sofort neu starten
            val justFinished = SystemClock.elapsedRealtime() < rearmAt + 4_000
            if (justFinished || RuleNlu.wantsCancel(text) || RuleNlu.isThanks(text)) return
            if (conversationJob?.isActive != true) viewModelScope.launch { startConversation(text) }
        } else {
            inputs.trySend(UserInput.Voice(text))
        }
    }

    fun onScreenTap() {
        if (!adminOpen && _ui.value.phase == Phase.IDLE && conversationJob?.isActive != true) startConversation(null)
    }

    fun choose(id: String) = inputs.trySend(UserInput.Option(id))
    fun submitText(text: String) { if (text.isNotBlank()) inputs.trySend(UserInput.Typed(text.trim())) }
    fun pickEmployee(e: Employee) = inputs.trySend(UserInput.PickEmployee(e))

    fun cancel() {
        runCatching { AgentCore.stopTTS() }
        cancelConversation()
    }

    /** Stopp-Knopf während der Fahrt */
    fun stopDriving() {
        RobotController.stopNavigation()
        cancelConversation()
    }

    private fun cancelConversation() {
        conversationJob?.cancel()
        conversationJob = null
        runCatching { AgentCore.isMicrophoneMuted = false }
        showIdle()
        armCooldown()
    }

    // =========================================================== Personenerkennung
    private fun startDetection() {
        detectionJob?.cancel()
        detectionJob = viewModelScope.launch {
            var seen = 0
            var absent = 0
            while (isActive) {
                delay(350)
                if (!inForeground || adminOpen || _ui.value.phase != Phase.IDLE || conversationJob?.isActive == true) {
                    seen = 0
                    continue
                }
                val count = withContext(Dispatchers.IO) { RobotController.personsWithin(settings.greetDistance.toDouble()) }
                if (count > 0) { seen++; absent = 0 } else { absent++; seen = 0 }
                val now = SystemClock.elapsedRealtime()
                if (needPersonGone) {
                    // Gleiche Person nicht sofort erneut ansprechen
                    if (absent >= 6 || now > rearmAt + 25_000) needPersonGone = false
                    continue
                }
                if (now < rearmAt || !settings.autoGreet) continue
                if (seen >= 2) {
                    seen = 0
                    startConversation(null)
                }
            }
        }
    }

    private fun armCooldown() {
        rearmAt = SystemClock.elapsedRealtime() + 6_000
        needPersonGone = true
    }

    private fun startHealthChecks() {
        healthJob?.cancel()
        healthJob = viewModelScope.launch {
            while (isActive) {
                val ok = runCatching { backend.health() }.onSuccess { h ->
                    if (h.company.isNotBlank()) companyName = h.company
                }.isSuccess
                _ui.update { it.copy(backendOk = ok) }
                delay(if (ok) 30_000 else 10_000)
            }
        }
    }

    // ================================================================= UI-Helfer
    private fun showIdle() {
        val l = lines
        _ui.update {
            UiState(
                phase = Phase.IDLE,
                mood = Mood.HAPPY,
                title = l.idleTitle,
                subtitle = l.idleSubtitle,
                backendOk = it.backendOk,
                robotOk = it.robotOk,
            )
        }
    }

    private fun setScreen(
        title: String,
        subtitle: String = "",
        options: List<UiOption> = emptyList(),
        input: TextInput? = null,
        employees: List<Employee> = emptyList(),
        mood: Mood = Mood.SPEAKING,
        phase: Phase = Phase.CONVERSATION,
        summary: Summary? = null,
    ) {
        _ui.update {
            it.copy(
                phase = phase,
                mood = mood,
                title = title,
                subtitle = subtitle,
                options = options,
                input = input,
                employees = employees,
                summary = summary,
                heard = "",
                banner = null,
                showCancel = phase == Phase.CONVERSATION,
                showStop = phase == Phase.ESCORT || phase == Phase.RETURNING,
            )
        }
    }

    private fun setMood(mood: Mood) = _ui.update { it.copy(mood = mood) }

    // ============================================================ Sprechen/Hören
    /** Spricht [text] und wartet, bis die Ausgabe fertig ist. */
    private suspend fun say(text: String) {
        _ui.update { it.copy(caption = text, mood = if (it.mood == Mood.JOY || it.mood == Mood.SAD) it.mood else Mood.SPEAKING) }
        try {
            val result = withTimeoutOrNull(20_000L + text.length * 120L) {
                AgentCore.ttsSync(text, 20_000L + text.length * 120L)
            }
            if (result == null) Log.w(TAG, "TTS-Zeitüberschreitung")
        } catch (e: CancellationException) {
            runCatching { AgentCore.stopTTS() }
            throw e
        } catch (t: Throwable) {
            // Ohne AgentOS (z. B. Emulator): Lesezeit simulieren, damit der Ablauf weiterläuft.
            Log.w(TAG, "TTS nicht verfügbar", t)
            delay(800L + text.length * 45L)
        }
    }

    private fun drainInputs() {
        while (inputs.tryReceive().isSuccess) { /* alte Eingaben verwerfen */ }
    }

    /**
     * Stellt eine Frage (Anzeige + Sprache) und wartet auf Sprache ODER Touch.
     * Liefert null, wenn auch nach einer Nachfrage niemand antwortet.
     */
    private suspend fun ask(
        question: String,
        preface: String? = null,
        title: String = question,
        subtitle: String = "",
        options: List<UiOption> = emptyList(),
        input: TextInput? = null,
        employees: List<Employee> = emptyList(),
        timeoutMs: Long = 18_000,
    ): UserInput? {
        drainInputs()
        setScreen(title, subtitle, options, input, employees)
        val spoken = listOfNotNull(preface, question).joinToString(" ")
        var speech: Job = viewModelScope.launch {
            say(spoken)
            setMood(Mood.LISTENING)
        }
        try {
            for (attempt in 0..1) {
                val budget = timeoutMs + (if (attempt == 0) spoken.length else question.length) * 80L
                val received = withTimeoutOrNull(budget) { inputs.receive() }
                if (received != null) {
                    if (speech.isActive) {
                        runCatching { AgentCore.stopTTS() }
                        speech.cancel()
                    }
                    if (received is UserInput.Voice || received is UserInput.Typed) setMood(Mood.THINKING)
                    return received
                }
                if (attempt == 0) {
                    speech.cancel()
                    speech = viewModelScope.launch {
                        say("${lines.stillThere} $question")
                        setMood(Mood.LISTENING)
                    }
                }
            }
            return null
        } finally {
            if (speech.isActive) speech.cancel()
        }
    }

    private suspend fun interpret(
        input: UserInput,
        step: Step,
        question: String,
        slots: List<Slot> = emptyList(),
    ): Understanding? {
        val text = when (input) {
            is UserInput.Voice -> input.text
            is UserInput.Typed -> input.text
            else -> return null
        }
        _ui.update { it.copy(heard = text, mood = Mood.THINKING) }
        val labels = slots.map { lines.formatSlot(it.start, LocalDate.now(it.start.offset)) }
        return understander.understand(text, step, question, slots.map { it.start }, labels)
    }

    // ================================================================== Gespräch
    private fun startConversation(firstUtterance: String?) {
        if (conversationJob?.isActive == true) return
        conversationJob = viewModelScope.launch {
            try {
                conversation(firstUtterance)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Fehler im Gesprächsablauf", t)
                runCatching { say(lines.backendDown) }
            } finally {
                runCatching { AgentCore.isMicrophoneMuted = false }
                if (conversationJob == coroutineContext[Job]) {
                    showIdle()
                    armCooldown()
                }
            }
        }
    }

    private class Visit {
        var intent: Intent? = null
        var visitorName: String? = null
        var employeeQuery: String? = null
        var employee: Employee? = null
        var purpose: String = ""

        fun absorb(u: Understanding?) {
            if (u == null) return
            if (intent == null) intent = u.intent
            if (visitorName == null) visitorName = cleanName(u.visitorName)
            if (employeeQuery == null) employeeQuery = u.employeeName?.trim()?.takeIf { it.length >= 2 }
            if (purpose.isBlank()) purpose = u.purpose?.trim().orEmpty()
        }
    }

    private suspend fun conversation(firstUtterance: String?) {
        val l = lines
        val visit = Visit()
        setScreen(title = "👋 " + l.idleTitle, mood = Mood.JOY)
        RobotController.resetHead()
        val greeting = l.greeting(companyName, settings.robotName)
        if (firstUtterance != null) {
            _ui.update { it.copy(heard = firstUtterance, mood = Mood.THINKING) }
            visit.absorb(understander.understand(firstUtterance, Step.GREETING, greeting))
        }
        // Begrüßung und erste Frage in einem Satz → keine Pause dazwischen
        if (visit.intent != null) say(greeting)

        if (!askIntent(visit, preface = greeting)) return
        if (!askVisitorName(visit)) return
        val employee = resolveEmployee(visit) ?: return
        visit.employee = employee

        when (visit.intent) {
            Intent.APPOINTMENT -> scenarioAppointment(visit, employee)
            Intent.WALK_IN -> scenarioWalkIn(visit, employee)
            Intent.BOOK -> scenarioBook(visit, employee, initialSlots = null)
            else -> say(l.goodbye)
        }
    }

    // ---------------------------------------------------------------- 1. Anliegen
    private suspend fun askIntent(visit: Visit, preface: String?): Boolean {
        val l = lines
        var attempts = 0
        var greetingFirst = preface
        while (visit.intent == null || visit.intent == Intent.OTHER) {
            if (visit.intent == Intent.OTHER) {
                say(l.otherRequest)
                visit.intent = null
            }
            if (++attempts > 3) {
                say(l.goodbye)
                return false
            }
            val options = listOf(
                UiOption(OPT_APPOINTMENT, l.optHaveAppointment, primary = true),
                UiOption(OPT_WALK_IN, l.optWalkIn),
                UiOption(OPT_BOOK, l.optBook),
            )
            val input = ask(l.askAppointment, preface = greetingFirst, options = options) ?: return noResponse()
            greetingFirst = null
            when (input) {
                is UserInput.Option -> visit.intent = intentFromOption(input.id)
                is UserInput.Cancel -> return false
                is UserInput.Voice, is UserInput.Typed -> {
                    val u = interpret(input, Step.INTENT, l.askAppointment) ?: continue
                    if (u.wantsCancel) { say(l.goodbye); return false }
                    visit.absorb(u)
                    if (visit.intent == null) {
                        when (u.answer) {
                            true -> visit.intent = Intent.APPOINTMENT
                            false -> {
                                val follow = ask(
                                    l.askWalkInOrBook,
                                    options = listOf(UiOption(OPT_WALK_IN, l.optWalkIn, true), UiOption(OPT_BOOK, l.optBook)),
                                ) ?: return noResponse()
                                visit.intent = when (follow) {
                                    is UserInput.Option -> intentFromOption(follow.id)
                                    is UserInput.Voice, is UserInput.Typed -> {
                                        val f = interpret(follow, Step.INTENT, l.askWalkInOrBook)
                                        visit.absorb(f)
                                        visit.intent ?: if (f?.answer == true) Intent.WALK_IN else null
                                    }
                                    else -> null
                                }
                            }
                            null -> say(l.didNotUnderstand)
                        }
                    }
                }
                else -> Unit
            }
        }
        return true
    }

    private fun intentFromOption(id: String): Intent? = when (id) {
        OPT_APPOINTMENT -> Intent.APPOINTMENT
        OPT_WALK_IN -> Intent.WALK_IN
        OPT_BOOK -> Intent.BOOK
        else -> null
    }

    // ------------------------------------------------------------------- 2. Name
    private suspend fun askVisitorName(visit: Visit): Boolean {
        val l = lines
        var attempts = 0
        while (visit.visitorName == null) {
            if (++attempts > 3) {
                visit.visitorName = if (settings.isGerman) "ein Gast" else "a guest"
                break
            }
            val input = ask(
                l.askName,
                subtitle = l.typeHere,
                input = TextInput(InputKind.NAME, l.nameHint),
            ) ?: return noResponse()
            when (input) {
                is UserInput.Typed -> visit.visitorName = cleanName(input.text)
                is UserInput.Voice -> {
                    val u = interpret(input, Step.NAME, l.askName)
                    if (u?.wantsCancel == true) { say(l.goodbye); return false }
                    visit.visitorName = cleanName(u?.visitorName)
                    if (visit.employeeQuery == null) visit.employeeQuery = u?.employeeName
                    if (visit.visitorName == null) say(l.didNotUnderstand)
                }
                is UserInput.Cancel -> return false
                else -> Unit
            }
        }
        return true
    }

    // ------------------------------------------------------- 3. Ansprechpartner
    private fun employeeQuestion(intent: Intent?): String = when (intent) {
        Intent.APPOINTMENT -> lines.askEmployeeAppointment
        Intent.BOOK -> lines.askEmployeeBook
        else -> lines.askEmployeeWalkIn
    }

    private suspend fun resolveEmployee(visit: Visit): Employee? {
        val l = lines
        var query = visit.employeeQuery
        var preface: String? = visit.visitorName?.let { l.nameThanks(it) }
        repeat(5) {
            if (query == null) {
                val question = employeeQuestion(visit.intent)
                val input = ask(
                    question,
                    preface = preface,
                    subtitle = l.typeHere,
                    input = TextInput(InputKind.EMPLOYEE, l.employeeHint),
                    options = listOf(UiOption(OPT_ALL_EMPLOYEES, l.showAllEmployees)),
                ) ?: return noResponseNull()
                preface = null
                when (input) {
                    is UserInput.PickEmployee -> return input.employee
                    is UserInput.Typed -> query = input.text
                    is UserInput.Voice -> {
                        val u = interpret(input, Step.EMPLOYEE, question)
                        if (u?.wantsCancel == true) { say(l.goodbye); return null }
                        query = u?.employeeName
                        if (query == null) say(l.didNotUnderstand)
                    }
                    is UserInput.Option -> if (input.id == OPT_ALL_EMPLOYEES) {
                        return pickFromList(allEmployees(), l.chooseEmployee) ?: return null
                    }
                    is UserInput.Cancel -> return null
                }
                if (query == null) return@repeat
            }

            setMood(Mood.THINKING)
            val matches = try {
                backend.searchEmployees(query!!, limit = 5)
            } catch (e: Exception) {
                Log.w(TAG, "Mitarbeitersuche fehlgeschlagen", e)
                say(l.backendDown)
                return null
            }
            val top = matches.firstOrNull()
            val second = matches.getOrNull(1)
            when {
                top == null -> {
                    val all = allEmployees()
                    val picked = pickFromList(all, l.employeeNotFound(query!!)) ?: return null
                    return picked
                }
                top.score >= 88 && (second == null || top.score - second.score >= 8) -> return top
                else -> {
                    val candidates = matches.take(4)
                    val input = ask(
                        l.confirmEmployee(top.name),
                        options = listOf(UiOption(OPT_YES, l.yes, true), UiOption(OPT_NO, l.no)),
                        employees = candidates,
                    ) ?: return noResponseNull()
                    when (input) {
                        is UserInput.PickEmployee -> return input.employee
                        is UserInput.Option -> if (input.id == OPT_YES) return top else query = null
                        is UserInput.Voice, is UserInput.Typed -> {
                            val u = interpret(input, Step.CONFIRM, l.confirmEmployee(top.name))
                            when {
                                u?.answer == true && u.employeeName == null -> return top
                                u?.employeeName != null -> query = u.employeeName
                                else -> query = null
                            }
                        }
                        is UserInput.Cancel -> return null
                    }
                }
            }
        }
        return null
    }

    private suspend fun allEmployees(): List<Employee> =
        runCatching { backend.allEmployees() }.getOrElse { emptyList() }

    private suspend fun pickFromList(employees: List<Employee>, question: String): Employee? {
        if (employees.isEmpty()) {
            say(lines.backendDown)
            return null
        }
        val input = ask(question, employees = employees, input = TextInput(InputKind.EMPLOYEE, lines.employeeHint)) ?: return noResponseNull()
        return when (input) {
            is UserInput.PickEmployee -> input.employee
            is UserInput.Typed, is UserInput.Voice -> {
                val text = (input as? UserInput.Typed)?.text
                    ?: interpret(input, Step.EMPLOYEE, question)?.employeeName
                    ?: return null
                runCatching { backend.searchEmployees(text, 1).firstOrNull() }.getOrNull()
            }
            else -> null
        }
    }

    // ============================================== Szenario 1: Termin vorhanden
    private suspend fun scenarioAppointment(visit: Visit, employee: Employee) {
        val l = lines
        setScreen(title = employee.name, subtitle = l.thinking, mood = Mood.THINKING)
        val notified = try {
            backend.arrival(visit.visitorName.orEmpty(), employee.id, visit.purpose).notification.sent
        } catch (e: Exception) {
            Log.w(TAG, "Ankunft melden fehlgeschlagen", e)
            false
        }
        if (notified) {
            setMood(Mood.JOY)
            say(l.arrivalNotified(employee.name))
        } else {
            setMood(Mood.SAD)
            say(l.arrivalFailed(employee.name))
        }
        escortToWaitingRoom(employee)
    }

    // ============================================== Szenario 2: ohne Termin
    private suspend fun scenarioWalkIn(visit: Visit, employee: Employee) {
        val l = lines
        setScreen(title = employee.name, subtitle = l.thinking, mood = Mood.THINKING)
        val result = try {
            backend.walkIn(visit.visitorName.orEmpty(), employee.id, visit.purpose)
        } catch (e: Exception) {
            Log.w(TAG, "Walk-in fehlgeschlagen", e)
            say(l.backendDown)
            return
        }
        if (!result.availableToday) {
            setMood(Mood.SAD)
            say(l.notAvailableToday(employee.name))
            scenarioBook(visit, employee, initialSlots = result.nextSlots)
            return
        }
        val message = when {
            !result.notified -> l.walkInNotifyFailed(employee.name)
            result.freeNow -> l.walkInFreeNow(employee.name)
            else -> l.walkInLater(employee.name, result.todaySlots.firstOrNull()?.let { l.formatTime(it.start) } ?: "")
        }
        setMood(if (result.notified) Mood.JOY else Mood.SAD)
        val input = ask(
            l.askEscort,
            preface = message,
            options = listOf(UiOption(OPT_YES, l.optEscortYes, true), UiOption(OPT_NO, l.optEscortNo)),
        )
        val wantsEscort = when (input) {
            is UserInput.Option -> input.id == OPT_YES
            is UserInput.Voice, is UserInput.Typed -> interpret(input, Step.YES_NO, l.askEscort)?.answer == true
            else -> false
        }
        if (wantsEscort) escortToWaitingRoom(employee) else say(l.goodbye)
    }

    // ============================================== Szenario 3: Termin vereinbaren
    private suspend fun scenarioBook(visit: Visit, employee: Employee, initialSlots: List<Slot>?) {
        val l = lines
        var slots: List<Slot> = initialSlots ?: loadSlots(employee, fromDay = 0) ?: return
        var rounds = 0
        while (rounds++ < 5) {
            if (slots.isEmpty()) {
                setMood(Mood.SAD)
                say(l.noSlots(employee.name))
                say(l.goodbye)
                return
            }
            val first = slots.first()
            val question = l.proposeSlot(employee.name, l.formatSlot(first.start, LocalDate.now(first.start.offset)))
            val options = slots.mapIndexed { i, s -> UiOption("$OPT_SLOT$i", l.formatSlotShort(s.start), primary = i == 0) } +
                UiOption(OPT_MORE, l.optMoreSlots)
            val input = ask(
                question,
                title = employee.name,
                subtitle = if (slots.size > 1) l.moreSlotsOnScreen else "",
                options = options,
            ) ?: run {
                noResponse()
                return
            }

            var chosen: Int? = null
            var more = false
            when (input) {
                is UserInput.Option -> when {
                    input.id.startsWith(OPT_SLOT) -> chosen = input.id.removePrefix(OPT_SLOT).toIntOrNull()
                    input.id == OPT_MORE -> more = true
                }
                is UserInput.Voice, is UserInput.Typed -> {
                    val u = interpret(input, Step.SLOT, question, slots)
                    when {
                        u == null -> Unit
                        u.wantsCancel -> { say(l.goodbye); return }
                        u.slotChoice != null -> chosen = u.slotChoice
                        u.wantsMore -> more = true
                        u.answer == true -> chosen = 0
                        u.answer == false -> more = true
                    }
                    if (chosen == null && !more) say(l.didNotUnderstand)
                }
                is UserInput.Cancel -> return
                else -> Unit
            }

            if (more) {
                val last = slots.last().start
                val fromDay = ChronoUnit.DAYS.between(LocalDate.now(last.offset), last.toLocalDate()).toInt() + 1
                slots = loadSlots(employee, fromDay.coerceAtLeast(1)) ?: return
                continue
            }
            val index = chosen ?: continue
            val slot = slots.getOrNull(index) ?: continue
            setScreen(title = employee.name, subtitle = l.thinking, mood = Mood.THINKING)
            try {
                val booking = backend.book(visit.visitorName.orEmpty(), employee.id, slot, visit.purpose)
                val whenText = l.formatSlot(booking.slot.start, LocalDate.now(booking.slot.start.offset))
                setScreen(
                    title = l.summaryTitle,
                    mood = Mood.JOY,
                    summary = Summary(
                        visitor = visit.visitorName.orEmpty(),
                        employee = employee.name,
                        whenText = l.formatSlotShort(booking.slot.start) + "–" + l.formatTime(booking.slot.end),
                    ),
                )
                say(l.booked(employee.name, whenText))
                say(l.goodbye)
                delay(4000) // Zusammenfassung noch kurz stehen lassen (z. B. für ein Foto)
                return
            } catch (e: BackendException) {
                if (e.isConflict) {
                    say(l.slotTaken)
                    slots = loadSlots(employee, 0) ?: return
                } else {
                    Log.w(TAG, "Buchung fehlgeschlagen", e)
                    setMood(Mood.SAD)
                    say(l.bookFailed)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Buchung fehlgeschlagen", e)
                setMood(Mood.SAD)
                say(l.bookFailed)
                return
            }
        }
        say(l.goodbye)
    }

    private suspend fun loadSlots(employee: Employee, fromDay: Int): List<Slot>? {
        setMood(Mood.THINKING)
        return try {
            backend.slots(employee.id, fromDay = fromDay)
        } catch (e: Exception) {
            Log.w(TAG, "Terminsuche fehlgeschlagen", e)
            say(lines.backendDown)
            null
        }
    }

    // =========================================== Begleitung in den Warteraum
    private suspend fun escortToWaitingRoom(employee: Employee?) {
        val l = lines
        setScreen(title = l.followMeTitle, subtitle = settings.waitingRoom, mood = Mood.JOY, phase = Phase.ESCORT)
        say(l.followMe)
        if (!RobotController.connected.value) {
            _ui.update { it.copy(banner = "RobotAPI nicht verbunden – Navigation nicht möglich") }
            say(l.navFailed)
            return
        }
        runCatching { AgentCore.isMicrophoneMuted = true }
        try {
            val result = RobotController.navigateTo(settings.waitingRoom, settings.escortSpeed) { event -> onNavEvent(event) }
            when (result) {
                is NavResult.Arrived -> {
                    runCatching { AgentCore.isMicrophoneMuted = false }
                    setScreen(title = "🙂", subtitle = settings.waitingRoom, mood = Mood.JOY, phase = Phase.ESCORT)
                    say(employee?.let { l.arrivedWaitingRoom(it.name) } ?: l.arrivedWaitingRoomGeneric)
                    delay(settings.waitingRoomPauseSec * 1000L)
                }
                is NavResult.Cancelled -> return
                is NavResult.Failed -> {
                    Log.w(TAG, "Fahrt zum Warteraum fehlgeschlagen: ${result.code} ${result.message}")
                    runCatching { AgentCore.isMicrophoneMuted = false }
                    _ui.update { it.copy(banner = "Warteraum: ${result.message}") }
                    setMood(Mood.SAD)
                    say(l.navFailed)
                }
            }
            returnToReception()
        } finally {
            runCatching { AgentCore.isMicrophoneMuted = false }
        }
    }

    private suspend fun returnToReception() {
        val l = lines
        setScreen(title = l.returningTitle, subtitle = settings.receptionPoint, mood = Mood.HAPPY, phase = Phase.RETURNING)
        runCatching { AgentCore.isMicrophoneMuted = true }
        val result = RobotController.navigateTo(settings.receptionPoint, settings.returnSpeed) { event -> onNavEvent(event) }
        runCatching { AgentCore.isMicrophoneMuted = false }
        if (result is NavResult.Failed) {
            Log.w(TAG, "Rückfahrt fehlgeschlagen: ${result.code} ${result.message}")
            _ui.update { it.copy(banner = "Rückfahrt: ${result.message}") }
            delay(3000)
        }
    }

    private fun onNavEvent(event: NavEvent) {
        if (event == NavEvent.BLOCKED) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBlockedSay > 10_000) {
                lastBlockedSay = now
                // Kurze Bitte ohne zu warten (Fahrt läuft weiter)
                runCatching { AgentCore.tts(lines.pathBlocked, 8000L, null) }
            }
        }
    }

    // ================================================================= Ende
    private suspend fun noResponse(): Boolean {
        say(lines.noResponse)
        return false
    }

    private suspend fun noResponseNull(): Employee? {
        say(lines.noResponse)
        return null
    }

    companion object {
        private const val TAG = "ReceptionFlow"
        const val OPT_APPOINTMENT = "intent:appointment"
        const val OPT_WALK_IN = "intent:walk_in"
        const val OPT_BOOK = "intent:book"
        const val OPT_YES = "yes"
        const val OPT_NO = "no"
        const val OPT_MORE = "slots:more"
        const val OPT_SLOT = "slot:"
        const val OPT_ALL_EMPLOYEES = "employees:all"

        /** Name säubern; Unsinn ("unknown", Einzelbuchstaben) verwerfen. */
        fun cleanName(raw: String?): String? {
            val name = raw?.replace(Regex("[^\\p{L}\\s'.-]"), " ")?.replace(Regex("\\s+"), " ")?.trim() ?: return null
            if (name.length < 2 || name.length > 60) return null
            if (name.lowercase() in setOf("null", "unknown", "unbekannt", "none", "visitor", "besucher")) return null
            return name.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
        }
    }
}
