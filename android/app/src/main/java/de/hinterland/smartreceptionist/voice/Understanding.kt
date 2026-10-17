package de.hinterland.smartreceptionist.voice

import android.util.Log
import com.ainirobot.agent.AgentCore
import com.ainirobot.agent.base.llm.LLMConfig
import com.ainirobot.agent.base.llm.LLMMessage
import com.ainirobot.agent.base.llm.Role
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.OffsetDateTime

/** Was aus einer Äußerung des Besuchers herausgelesen wurde. */
data class Understanding(
    val intent: Intent? = null,
    /** Antwort auf eine Ja/Nein-Frage */
    val answer: Boolean? = null,
    val visitorName: String? = null,
    val employeeName: String? = null,
    val purpose: String? = null,
    /** Index des gewählten Terminvorschlags (0-basiert) */
    val slotChoice: Int? = null,
    val wantsMore: Boolean = false,
    val wantsCancel: Boolean = false,
) {
    /** Lücken mit Werten aus [other] füllen (eigene Werte haben Vorrang). */
    fun mergedWith(other: Understanding) = Understanding(
        intent = intent ?: other.intent,
        answer = answer ?: other.answer,
        visitorName = visitorName ?: other.visitorName,
        employeeName = employeeName ?: other.employeeName,
        purpose = purpose ?: other.purpose,
        slotChoice = slotChoice ?: other.slotChoice,
        wantsMore = wantsMore || other.wantsMore,
        wantsCancel = wantsCancel || other.wantsCancel,
    )
}

/** In welchem Schritt des Gesprächs die Äußerung kam – hilft bei der Deutung. */
enum class Step { GREETING, INTENT, NAME, EMPLOYEE, CONFIRM, SLOT, YES_NO }

/**
 * Sprachverständnis: zuerst das AgentOS-Sprachmodell (llmSync, ohne Sprachausgabe),
 * bei Fehler/Zeitüberschreitung die lokalen Regeln aus [RuleNlu].
 */
class Understander(private val useLlm: () -> Boolean) {

    suspend fun understand(
        utterance: String,
        step: Step,
        question: String,
        slots: List<OffsetDateTime> = emptyList(),
        slotLabels: List<String> = emptyList(),
    ): Understanding {
        val rules = byRules(utterance, step, slots)
        if (!useLlm()) return rules
        val llm = byLlm(utterance, step, question, slotLabels) ?: return rules
        val checked = if (llm.slotChoice != null && llm.slotChoice !in slots.indices) llm.copy(slotChoice = null) else llm
        // Das Sprachmodell versteht Namen und Absichten besser; Regeln füllen Lücken.
        return checked.mergedWith(rules)
    }

    fun byRules(utterance: String, step: Step, slots: List<OffsetDateTime> = emptyList()): Understanding {
        val intent = RuleNlu.intent(utterance)
        return Understanding(
            intent = intent,
            answer = RuleNlu.yesNo(utterance),
            visitorName = if (step == Step.NAME) RuleNlu.nameFromAnswer(utterance) else RuleNlu.visitorNameFromSentence(utterance),
            employeeName = if (step == Step.EMPLOYEE) RuleNlu.employeeFromAnswer(utterance) else RuleNlu.employeeFromSentence(utterance),
            slotChoice = if (slots.isNotEmpty()) RuleNlu.slotChoice(utterance, slots) else null,
            wantsMore = step == Step.SLOT && RuleNlu.wantsMoreOptions(utterance),
            wantsCancel = RuleNlu.wantsCancel(utterance),
        )
    }

    private suspend fun byLlm(utterance: String, step: Step, question: String, offeredSlots: List<String>): Understanding? {
        val slotsText = if (offeredSlots.isEmpty()) "none" else offeredSlots.mapIndexed { i, s -> "${i + 1}) $s" }.joinToString("; ")
        val system = """
            You are the language-understanding module of a reception robot in an office lobby.
            The robot just asked the visitor: "$question" (dialog step: $step).
            Offered appointment options: $slotsText.
            Analyse the visitor's reply (it comes from speech recognition, German or English, may contain recognition errors).
            Reply ONLY with one compact JSON object, no markdown, with exactly these keys:
            {"intent": "appointment" | "walk_in" | "book" | "delivery" | "other" | null,
             "answer": "yes" | "no" | null,
             "visitor_name": string | null,
             "employee_name": string | null,
             "purpose": string | null,
             "slot_choice": integer | null,
             "wants_more_options": boolean,
             "wants_cancel": boolean}
            Rules:
            - intent "appointment" = visitor already has an appointment/meeting; "walk_in" = no appointment but wants to talk to someone now;
              "book" = wants to make/schedule a new appointment; "delivery" = is delivering a package/parcel/mail for someone; "other" = unrelated request; null if not stated.
            - "answer" = whether the reply means yes or no to the robot's question; null if neither.
            - visitor_name = the visitor's OWN name only if they state it (in dialog step NAME a bare name is the visitor's name). Never use the employee's name here.
            - employee_name = the person at the company they want to see (keep titles out, e.g. "Frau Schmidt" -> "Schmidt"). In dialog step EMPLOYEE a bare name is the employee.
            - slot_choice = 1-based number of the chosen appointment option, if they pick one (e.g. "the second", a weekday or time that matches an option).
            - Never invent information. Use null when unsure.
        """.trimIndent()
        val messages = listOf(LLMMessage(Role.SYSTEM, system), LLMMessage(Role.USER, utterance))
        return try {
            val result = withTimeoutOrNull(9000) {
                AgentCore.llmSync(messages, LLMConfig(temperature = 0.0f, maxTokens = 200), 8000, false)
            } ?: return null
            if (!result.isSuccess) return null
            val content = result.result?.message?.content ?: return null
            parse(content)
        } catch (t: Throwable) {
            Log.w("Understander", "LLM-Auswertung fehlgeschlagen", t)
            null
        }
    }

    companion object {
        /** Robust: sucht das erste {...} im Text (Modelle umschließen JSON gern mit ```). */
        fun parse(content: String): Understanding? {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try {
                val o = JSONObject(content.substring(start, end + 1))
                fun str(key: String): String? =
                    if (o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
                Understanding(
                    intent = when (str("intent")?.lowercase()) {
                        "appointment" -> Intent.APPOINTMENT
                        "walk_in", "walkin", "walk-in" -> Intent.WALK_IN
                        "book" -> Intent.BOOK
                        "delivery" -> Intent.DELIVERY
                        "other" -> Intent.OTHER
                        else -> null
                    },
                    answer = when (str("answer")?.lowercase()) {
                        "yes", "true", "ja" -> true
                        "no", "false", "nein" -> false
                        else -> null
                    },
                    visitorName = str("visitor_name"),
                    employeeName = str("employee_name"),
                    purpose = str("purpose"),
                    slotChoice = if (o.has("slot_choice") && !o.isNull("slot_choice")) o.optInt("slot_choice", 0).takeIf { it > 0 }?.minus(1) else null,
                    wantsMore = o.optBoolean("wants_more_options", false),
                    wantsCancel = o.optBoolean("wants_cancel", false),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
