package de.hinterland.wayfinder.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-app log, shown in the admin screen. On the event floor there is usually no laptop with adb,
 * so the last few hundred events (navigation results, heard sentences, errors) are kept in memory.
 */
object AppLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent

    /** Forwarded to Logcat by the app; tests keep the default (no output). */
    @Volatile var sink: (level: Char, message: String, error: Throwable?) -> Unit = { _, _, _ -> }

    fun i(message: String) = add('I', message, null)
    fun w(message: String, error: Throwable? = null) = add('W', message, error)
    fun e(message: String, error: Throwable? = null) = add('E', message, error)

    private fun add(level: Char, message: String, error: Throwable?) {
        runCatching { sink(level, message, error) }
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$time $level  $message" + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
            _recent.value = lines.toList()
        }
    }
}
