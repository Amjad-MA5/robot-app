package de.hinterland.wayfinder.robot

import com.ainirobot.agent.AgentCore
import de.hinterland.wayfinder.core.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The robot's voice. */
interface Voice {
    /** Speaks [text] and returns when it is done (or was interrupted). Cancelling stops the speech. */
    suspend fun say(text: String)

    /** Stops any speech immediately. */
    fun stop()
}

/** AgentOS text-to-speech (AgentCore). */
class AgentVoice : Voice {
    override suspend fun say(text: String) {
        // ttsSync returns after playback; the timeout covers the whole playback, so it grows with the text
        val budget = 10_000L + text.length * 110L
        try {
            val result = withTimeoutOrNull(budget + 2_000) { AgentCore.ttsSync(text, budget) }
            if (result == null) {
                AppLog.w("Speech timed out")
                stop()
            }
        } catch (e: CancellationException) {
            stop()
            throw e
        } catch (t: Throwable) {
            // No AgentOS speech service: wait about as long as speaking would take, so the flow stays natural
            AppLog.w("Speech is not available", t)
            delay(600L + text.length * 50L)
        }
    }

    override fun stop() {
        runCatching { AgentCore.stopTTS() }
    }
}

/** No speaker (emulator): only waits as long as speaking would take. */
class SimulatedVoice(private val msPerChar: Long = 45) : Voice {
    override suspend fun say(text: String) {
        delay(500L + text.length * msPerChar)
    }

    override fun stop() {}
}
