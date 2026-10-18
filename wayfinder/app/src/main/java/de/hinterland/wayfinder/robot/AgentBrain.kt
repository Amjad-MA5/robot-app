package de.hinterland.wayfinder.robot

import com.ainirobot.agent.AgentCore
import com.ainirobot.agent.base.llm.LLMConfig
import com.ainirobot.agent.base.llm.LLMMessage
import com.ainirobot.agent.base.llm.Role
import de.hinterland.wayfinder.core.AppLog
import de.hinterland.wayfinder.core.Brain
import de.hinterland.wayfinder.core.BrainContext
import de.hinterland.wayfinder.core.BrainPrompt
import de.hinterland.wayfinder.core.BrainReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Language understanding with the AgentOS language model (AgentCore.llmSync, not streamed, so nothing is
 * spoken automatically). Only used for sentences the local rules do not understand.
 */
class AgentBrain(private val enabled: () -> Boolean) : Brain {

    override suspend fun interpret(utterance: String, context: BrainContext): BrainReply? {
        if (!enabled()) return null
        val messages = listOf(
            LLMMessage(Role.SYSTEM, BrainPrompt.system(context)),
            LLMMessage(Role.USER, utterance),
        )
        return try {
            val result = withTimeoutOrNull(9_000) {
                AgentCore.llmSync(messages, LLMConfig(temperature = 0.3f, maxTokens = 160, timeout = 8), 8_000, false)
            }
            if (result == null || !result.isSuccess) {
                AppLog.w("Language model: no answer (${result?.status})")
                return null
            }
            val content = result.result?.message?.content
            BrainPrompt.parse(content, context.rooms.map { it.first.id }.toSet()).also {
                AppLog.i("Language model: $it")
                if (it == null) AppLog.w("Language model answer not usable: ${content?.take(160)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w("Language model failed", t)
            null
        }
    }
}
