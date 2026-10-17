package de.hinterland.smartreceptionist.badge

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer

data class IppResult(val ok: Boolean, val statusCode: Int, val detail: String, val jobId: Int? = null)

data class PrinterInfo(
    val reachable: Boolean,
    val model: String = "",
    val state: String = "",
    val acceptingJobs: Boolean? = null,
    val detail: String = "",
)

/**
 * Minimal IPP client (RFC 8010/8011) so the robot can print the badge straight on the office printer.
 * Needed because the backend usually runs in the cloud and cannot reach the office network.
 * Same encoding as backend/app/badges/printer.py, verified against the Konica Minolta bizhub C361i.
 * All calls block - run them on Dispatchers.IO.
 */
class IppPrinter(
    url: String,
    private val user: String = "Nova-Reception",
    private val timeoutMs: Int = 45_000,
) {
    val httpUrl: String = toHttpUrl(url)
    private val printerUri: String = printerUri(httpUrl)

    fun status(): PrinterInfo = try {
        val (code, attrs) = post(
            buildRequest(
                OP_GET_PRINTER_ATTRIBUTES, printerUri, user = user,
                requested = listOf("printer-make-and-model", "printer-state", "printer-is-accepting-jobs"),
            ),
        )
        PrinterInfo(
            reachable = code < 0x0100,
            model = attrs["printer-make-and-model"]?.firstOrNull()?.toString().orEmpty(),
            state = when (attrs["printer-state"]?.firstOrNull()) {
                3 -> "idle"
                4 -> "printing"
                5 -> "stopped"
                else -> "unknown"
            },
            acceptingJobs = attrs["printer-is-accepting-jobs"]?.firstOrNull() as? Boolean,
            detail = statusText(code),
        )
    } catch (e: Exception) {
        PrinterInfo(reachable = false, detail = e.message ?: e.javaClass.simpleName)
    }

    /** Validate-Job: would the printer accept a badge job? Uses no paper. */
    fun validate(): IppResult = send(
        buildRequest(
            OP_VALIDATE_JOB, printerUri, user = user, jobName = "Visitor badge (test)",
            documentFormat = PDF, jobAttributes = BADGE_JOB_ATTRIBUTES,
        ),
    )

    fun printPdf(jobName: String, pdf: ByteArray): IppResult = send(
        buildRequest(
            OP_PRINT_JOB, printerUri, requestId = 2, user = user, jobName = jobName,
            documentFormat = PDF, jobAttributes = BADGE_JOB_ATTRIBUTES,
        ) + pdf,
    )

    private fun send(payload: ByteArray): IppResult = try {
        val (code, attrs) = post(payload)
        IppResult(code < 0x0100, code, statusText(code), attrs["job-id"]?.firstOrNull() as? Int)
    } catch (e: Exception) {
        IppResult(false, -1, e.message ?: e.javaClass.simpleName)
    }

    private fun post(payload: ByteArray): Pair<Int, Map<String, List<Any>>> {
        val conn = (URL(httpUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = timeoutMs
            doOutput = true
            // Some printers reject chunked uploads, so always send a Content-Length
            setFixedLengthStreamingMode(payload.size)
            setRequestProperty("Content-Type", "application/ipp")
            setRequestProperty("Accept", "application/ipp")
        }
        try {
            conn.outputStream.use { it.write(payload) }
            val http = conn.responseCode
            if (http != 200) throw java.io.IOException("Printer answered HTTP $http")
            return parseResponse(conn.inputStream.use { it.readBytes() })
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val TAG_OPERATION = 0x01
        const val TAG_JOB = 0x02
        const val TAG_END = 0x03
        const val TAG_INTEGER = 0x21
        const val TAG_BOOLEAN = 0x22
        const val TAG_ENUM = 0x23
        const val TAG_NAME = 0x42
        const val TAG_KEYWORD = 0x44
        const val TAG_URI = 0x45
        const val TAG_CHARSET = 0x47
        const val TAG_LANGUAGE = 0x48
        const val TAG_MIME = 0x49

        const val OP_PRINT_JOB = 0x0002
        const val OP_VALIDATE_JOB = 0x0004
        const val OP_GET_PRINTER_ATTRIBUTES = 0x000B

        private const val PDF = "application/pdf"

        /** A4, one-sided, colour, original size - the card must not be scaled to the page. */
        val BADGE_JOB_ATTRIBUTES: List<Triple<Int, String, Any>> = listOf(
            Triple(TAG_INTEGER, "copies", 1),
            Triple(TAG_KEYWORD, "media", "iso_a4_210x297mm"),
            Triple(TAG_KEYWORD, "sides", "one-sided"),
            Triple(TAG_KEYWORD, "print-color-mode", "color"),
            Triple(TAG_KEYWORD, "print-scaling", "none"),
        )

        /** ipp://host/ipp -> http://host:631/ipp, ipps -> https; http(s) URLs stay as they are. */
        fun toHttpUrl(url: String): String {
            val u = URI(url.trim())
            val scheme = u.scheme?.lowercase() ?: throw IllegalArgumentException("Printer address needs a scheme: $url")
            val path = u.rawPath?.takeIf { it.isNotEmpty() } ?: "/ipp"
            return when (scheme) {
                "ipp" -> "http://${u.host}:${if (u.port > 0) u.port else 631}$path"
                "ipps" -> "https://${u.host}:${if (u.port > 0) u.port else 443}$path"
                "http", "https" -> "$scheme://${u.host}${if (u.port > 0) ":${u.port}" else ""}$path"
                else -> throw IllegalArgumentException("Unsupported printer address: $url")
            }
        }

        /** http://host/ipp -> ipp://host/ipp (the form the printer lists in printer-uri-supported). */
        fun printerUri(httpUrl: String): String {
            val u = URI(httpUrl)
            val port = if (u.port > 0 && u.port !in setOf(80, 443, 631)) ":${u.port}" else ""
            return "${if (u.scheme == "https") "ipps" else "ipp"}://${u.host}$port${u.rawPath?.ifEmpty { "/ipp" } ?: "/ipp"}"
        }

        fun buildRequest(
            operation: Int,
            printerUri: String,
            requestId: Int = 1,
            user: String = "Nova-Reception",
            jobName: String? = null,
            documentFormat: String? = null,
            jobAttributes: List<Triple<Int, String, Any>> = emptyList(),
            requested: List<String> = emptyList(),
        ): ByteArray {
            val buf = ByteArrayOutputStream()
            val out = DataOutputStream(buf)
            out.writeByte(2) // IPP 2.0
            out.writeByte(0)
            out.writeShort(operation)
            out.writeInt(requestId)
            out.writeByte(TAG_OPERATION)
            attr(out, TAG_CHARSET, "attributes-charset", "utf-8")
            attr(out, TAG_LANGUAGE, "attributes-natural-language", "en")
            attr(out, TAG_URI, "printer-uri", printerUri)
            attr(out, TAG_NAME, "requesting-user-name", user)
            jobName?.let { attr(out, TAG_NAME, "job-name", it) }
            documentFormat?.let { attr(out, TAG_MIME, "document-format", it) }
            requested.forEachIndexed { i, name -> attr(out, TAG_KEYWORD, if (i == 0) "requested-attributes" else "", name) }
            if (jobAttributes.isNotEmpty()) {
                out.writeByte(TAG_JOB)
                jobAttributes.forEach { (tag, name, value) -> attr(out, tag, name, value) }
            }
            out.writeByte(TAG_END)
            out.flush()
            return buf.toByteArray()
        }

        private fun attr(out: DataOutputStream, tag: Int, name: String, value: Any) {
            val n = name.toByteArray(Charsets.UTF_8)
            val v = when (tag) {
                TAG_INTEGER, TAG_ENUM -> ByteBuffer.allocate(4).putInt((value as Number).toInt()).array()
                TAG_BOOLEAN -> byteArrayOf(if (value == true) 1 else 0)
                else -> value.toString().toByteArray(Charsets.UTF_8)
            }
            out.writeByte(tag)
            out.writeShort(n.size)
            out.write(n)
            out.writeShort(v.size)
            out.write(v)
        }

        /** Status code + attributes (name -> values) of an IPP response. */
        fun parseResponse(data: ByteArray): Pair<Int, Map<String, List<Any>>> {
            require(data.size >= 8) { "IPP response too short" }
            val b = ByteBuffer.wrap(data)
            val status = b.getShort(2).toInt() and 0xFFFF
            val attrs = LinkedHashMap<String, MutableList<Any>>()
            var i = 8
            var last = ""
            while (i < data.size) {
                val tag = data[i].toInt() and 0xFF
                i += 1
                if (tag == TAG_END) break
                if (tag < 0x10) continue // group delimiter
                val nLen = b.getShort(i).toInt() and 0xFFFF
                i += 2
                val name = String(data, i, nLen, Charsets.UTF_8)
                i += nLen
                val vLen = b.getShort(i).toInt() and 0xFFFF
                i += 2
                val value: Any = when {
                    (tag == TAG_INTEGER || tag == TAG_ENUM) && vLen == 4 -> b.getInt(i)
                    tag == TAG_BOOLEAN -> vLen > 0 && data[i].toInt() == 1
                    tag >= 0x40 -> String(data, i, vLen, Charsets.UTF_8)
                    else -> data.copyOfRange(i, i + vLen)
                }
                i += vLen
                if (name.isNotEmpty()) last = name
                attrs.getOrPut(last) { mutableListOf() }.add(value)
            }
            return status to attrs
        }

        fun statusText(code: Int): String = when (code) {
            0x0000 -> "ok"
            0x0001 -> "ok (attributes adjusted)"
            0x0002 -> "ok (conflicts ignored)"
            0x0400 -> "bad request"
            0x0401 -> "forbidden"
            0x0402 -> "authentication required"
            0x040A -> "document format not supported"
            0x040B -> "attributes not supported"
            0x0500 -> "printer error"
            0x0506 -> "printer is not accepting jobs"
            0x0507 -> "printer busy"
            else -> "IPP status 0x%04x".format(code)
        }
    }
}
