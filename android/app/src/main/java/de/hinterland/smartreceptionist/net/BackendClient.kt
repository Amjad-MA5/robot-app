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
import java.util.Base64

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

/** Result of POST /api/badges: the badge was created (and printed by the backend, if requested). */
data class BadgeResult(
    val id: String,
    val printed: Boolean,
    val printMethod: String,
    val printDetail: String,
    /** Card preview as JPEG - only for the screen, never stored */
    val previewJpeg: ByteArray?,
    /** Print-ready A4 page, only when requested with returnPdf - the robot sends it to the printer */
    val pdf: ByteArray?,
)

/** One line of the visitor log kept by the backend */
data class VisitEntry(val time: String, val type: String, val visitor: String, val employee: String, val result: String)

data class PrinterTestResult(val sent: Boolean, val method: String, val detail: String, val pdf: ByteArray?)

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

    /**
     * Creates a visitor badge. The photo (JPEG) is optional and only processed in memory.
     * [print] = the backend prints itself; [returnPdf] = the PDF comes back so the robot can print it.
     */
    suspend fun createBadge(
        visitor: String,
        employee: Employee?,
        purpose: String,
        language: String,
        photoJpeg: ByteArray?,
        print: Boolean = true,
        returnPdf: Boolean = false,
        timeoutMs: Int = 45_000,
    ): BadgeResult {
        val body = JSONObject()
            .put("visitor_name", visitor)
            .put("employee_id", employee?.id ?: "")
            .put("employee_name", employee?.name ?: "")
            .put("purpose", purpose)
            .put("language", language)
            .put("print", print)
            .put("return_pdf", returnPdf)
        if (photoJpeg != null) body.put("photo_jpeg_base64", Base64.getEncoder().encodeToString(photoJpeg))
        val o = JSONObject(request("POST", "/api/badges", body, timeoutMs = timeoutMs))
        val printInfo = o.optJSONObject("print")
        return BadgeResult(
            id = o.optString("id"),
            printed = printInfo?.optBoolean("sent") ?: false,
            printMethod = printInfo?.optString("method") ?: "",
            printDetail = printInfo?.optString("detail") ?: "",
            previewJpeg = decodeBase64(o, "preview_jpeg_base64"),
            pdf = decodeBase64(o, "print_pdf_base64"),
        )
    }

    /** Tells the backend whether the robot managed to print the badge (shown in the dashboard). */
    suspend fun reportBadgePrinted(badgeId: String, sent: Boolean, method: String, detail: String, jobId: Int?) {
        val body = JSONObject().put("sent", sent).put("method", method).put("detail", detail).put("job_id", jobId ?: JSONObject.NULL)
        request("POST", "/api/badges/${URLEncoder.encode(badgeId, "UTF-8")}/printed", body)
    }

    /** Visitor log, newest first. [date] = YYYY-MM-DD or empty for all days. */
    suspend fun visitLog(date: String = "", query: String = ""): List<VisitEntry> {
        val path = "/api/visits/log?date=${URLEncoder.encode(date, "UTF-8")}&q=${URLEncoder.encode(query, "UTF-8")}"
        val arr = JSONArray(request("GET", path))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VisitEntry(o.optString("time"), o.optString("type"), o.optString("visitor"), o.optString("employee"), o.optString("result"))
        }
    }

    /** Printer status as seen from the backend, as a short text for the admin area */
    suspend fun printerStatus(): String {
        val o = JSONObject(request("GET", "/api/printer/status"))
        return if (o.optBoolean("reachable")) {
            "${o.optString("model")} · ${o.optString("state")}"
        } else {
            "not reachable: ${o.optString("detail", o.optString("url"))}"
        }
    }

    /** Test badge without photo: printed by the backend, or returned as PDF for the robot */
    suspend fun printerTest(print: Boolean = true, returnPdf: Boolean = false): PrinterTestResult {
        val body = JSONObject().put("print", print).put("return_pdf", returnPdf)
        val o = JSONObject(request("POST", "/api/printer/test", body, timeoutMs = 45_000))
        return PrinterTestResult(
            sent = o.optBoolean("sent"),
            method = o.optString("method"),
            detail = o.optString("detail"),
            pdf = decodeBase64(o, "print_pdf_base64"),
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
    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        timeoutMs: Int = 20_000,
    ): String =
        withContext(Dispatchers.IO) {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5000
                readTimeout = timeoutMs
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
        /** null for missing/null fields - JSONObject.optString would return the text "null" */
        private fun decodeBase64(o: JSONObject, key: String): ByteArray? {
            if (!o.has(key) || o.isNull(key)) return null
            val text = o.optString(key).takeIf { it.isNotBlank() } ?: return null
            return runCatching { Base64.getDecoder().decode(text) }.getOrNull()
        }

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
