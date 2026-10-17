package de.hinterland.smartreceptionist.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

data class Employee(
    val id: String,
    val name: String,
    val email: String = "",
    val department: String = "",
    val jobTitle: String = "",
    val score: Double = 0.0,
)

data class Slot(val start: OffsetDateTime, val end: OffsetDateTime) {
    fun toJsonStart(): String = start.format(ISO_SECONDS)
    fun toJsonEnd(): String = end.format(ISO_SECONDS)

    private companion object {
        // OffsetDateTime.toString() lässt ":00"-Sekunden weg – das Backend bekommt immer volle Zeitstempel.
        val ISO_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}

data class Notification(val sent: Boolean, val channel: String, val detail: String)

data class ArrivalResult(val employee: Employee, val notification: Notification, val appointmentStart: OffsetDateTime?)

data class DeliveryResult(val notified: Boolean, val waitForRecipient: Boolean)

data class WalkInResult(
    val employee: Employee,
    val freeNow: Boolean,
    val availableToday: Boolean,
    val notified: Boolean,
    val todaySlots: List<Slot>,
    val nextSlots: List<Slot>,
)

data class BookingResult(val created: Boolean, val slot: Slot, val notified: Boolean)

data class BackendHealth(val company: String, val mode: String)

data class AppVersionInfo(val available: Boolean, val versionCode: Int, val versionName: String, val downloadPath: String)

class BackendException(message: String, val httpCode: Int = 0) : IOException(message) {
    val isConflict: Boolean get() = httpCode == 409
}

/**
 * Schlanker HTTP-Client für das Smart-Receptionist-Backend.
 * Nutzt nur Android-Bordmittel (HttpURLConnection + org.json) – keine Zusatzbibliotheken.
 */
class BackendClient(baseUrl: String, private val apiKey: String) {
    private val base = baseUrl.trim().trimEnd('/')

    // --------------------------------------------------------------- Endpunkte
    suspend fun health(): BackendHealth {
        val o = JSONObject(request("GET", "/health", auth = false))
        return BackendHealth(o.optString("company"), o.optString("mode"))
    }

    suspend fun searchEmployees(query: String, limit: Int = 5): List<Employee> {
        val q = URLEncoder.encode(query, "UTF-8")
        return parseEmployees(JSONArray(request("GET", "/api/employees?q=$q&limit=$limit")))
    }

    suspend fun allEmployees(): List<Employee> =
        parseEmployees(JSONArray(request("GET", "/api/employees?limit=200")))

    suspend fun arrival(visitor: String, employeeId: String, purpose: String): ArrivalResult {
        val body = JSONObject().put("visitor_name", visitor).put("employee_id", employeeId).put("purpose", purpose)
        val o = JSONObject(request("POST", "/api/visits/arrival", body))
        val appt = o.optJSONObject("appointment")
        return ArrivalResult(
            employee = parseEmployee(o.getJSONObject("employee")),
            notification = parseNotification(o.optJSONObject("notification")),
            appointmentStart = appt?.let { OffsetDateTime.parse(it.getString("start")) },
        )
    }

    suspend fun delivery(employeeId: String, carrier: String, signatureRequired: Boolean, photoBase64: String?): DeliveryResult {
        val body = JSONObject()
            .put("employee_id", employeeId)
            .put("carrier", carrier)
            .put("signature_required", signatureRequired)
        if (photoBase64 != null) body.put("photo_base64", photoBase64)
        val o = JSONObject(request("POST", "/api/visits/delivery", body))
        return DeliveryResult(
            notified = o.optJSONObject("notification")?.optBoolean("sent") ?: false,
            waitForRecipient = o.optString("handling") == "wait_for_recipient",
        )
    }

    suspend fun walkIn(visitor: String, employeeId: String, purpose: String): WalkInResult {
        val body = JSONObject().put("visitor_name", visitor).put("employee_id", employeeId).put("purpose", purpose)
        val o = JSONObject(request("POST", "/api/visits/walk-in", body))
        return WalkInResult(
            employee = parseEmployee(o.getJSONObject("employee")),
            freeNow = o.optBoolean("free_now"),
            availableToday = o.optBoolean("available_today"),
            notified = o.optBoolean("notified"),
            todaySlots = parseSlots(o.optJSONArray("today_slots")),
            nextSlots = parseSlots(o.optJSONArray("next_slots")),
        )
    }

    suspend fun slots(employeeId: String, fromDay: Int = 0, max: Int = 3): List<Slot> {
        val id = URLEncoder.encode(employeeId, "UTF-8")
        return parseSlots(JSONArray(request("GET", "/api/employees/$id/slots?from_day=$fromDay&max=$max")))
    }

    suspend fun book(visitor: String, employeeId: String, slot: Slot, purpose: String): BookingResult {
        val body = JSONObject()
            .put("visitor_name", visitor)
            .put("employee_id", employeeId)
            .put("start", slot.toJsonStart())
            .put("end", slot.toJsonEnd())
            .put("purpose", purpose)
        val o = JSONObject(request("POST", "/api/appointments", body))
        val s = o.getJSONObject("slot")
        return BookingResult(
            created = o.optBoolean("created"),
            slot = Slot(OffsetDateTime.parse(s.getString("start")), OffsetDateTime.parse(s.getString("end"))),
            notified = o.optJSONObject("notification")?.optBoolean("sent") ?: false,
        )
    }

    suspend fun latestApp(): AppVersionInfo {
        val o = JSONObject(request("GET", "/api/app/latest", auth = false))
        return AppVersionInfo(
            available = o.optBoolean("available"),
            versionCode = o.optInt("version_code"),
            versionName = o.optString("version_name"),
            downloadPath = o.optString("download_path"),
        )
    }

    suspend fun download(path: String, target: File, onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 30000
        }
        try {
            if (conn.responseCode != 200) throw BackendException("Download fehlgeschlagen (HTTP ${conn.responseCode})", conn.responseCode)
            val total = conn.contentLengthLong
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ Intern
    private suspend fun request(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): String =
        withContext(Dispatchers.IO) {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
                if (auth) setRequestProperty("X-Api-Key", apiKey)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                    throw BackendException(detail?.takeIf { it.isNotBlank() } ?: "HTTP $code", code)
                }
                text
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        fun parseEmployee(o: JSONObject) = Employee(
            id = o.getString("id"),
            name = o.optString("name"),
            email = o.optString("email"),
            department = o.optString("department"),
            jobTitle = o.optString("job_title"),
            score = o.optDouble("score", 0.0),
        )

        fun parseEmployees(a: JSONArray): List<Employee> = (0 until a.length()).map { parseEmployee(a.getJSONObject(it)) }

        fun parseSlots(a: JSONArray?): List<Slot> {
            if (a == null) return emptyList()
            return (0 until a.length()).map {
                val s = a.getJSONObject(it)
                Slot(OffsetDateTime.parse(s.getString("start")), OffsetDateTime.parse(s.getString("end")))
            }
        }

        fun parseNotification(o: JSONObject?) = Notification(
            sent = o?.optBoolean("sent") ?: false,
            channel = o?.optString("channel") ?: "",
            detail = o?.optString("detail") ?: "",
        )
    }
}
