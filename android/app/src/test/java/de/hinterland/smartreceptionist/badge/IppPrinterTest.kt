package de.hinterland.smartreceptionist.badge

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class IppPrinterTest {

    private lateinit var server: ServerSocket
    private val received = mutableListOf<ByteArray>()
    private var answerStatus = 0x0000

    /** Simulated printer on a local socket: answers like the bizhub (status, job-id 42, printer attributes). */
    @Before
    fun startFakePrinter() {
        server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val socket = server
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { handle(it.getInputStream(), it.getOutputStream()) }
            }
        }
    }

    private fun handle(input: java.io.InputStream, output: java.io.OutputStream) {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
        assertTrue(head.contains("Content-Type: application/ipp"))
        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(head)!!.groupValues[1].toInt()
        val body = ByteArray(length)
        var read = 0
        while (read < length) read += input.read(body, read, length - read)
        synchronized(received) { received += body }
        val bytes = answer(body)
        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun answer(body: ByteArray): ByteArray {
        val op = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeByte(2); d.writeByte(0); d.writeShort(answerStatus); d.write(body, 4, 4)
        d.writeByte(IppPrinter.TAG_OPERATION)
        attr(d, IppPrinter.TAG_CHARSET, "attributes-charset", "utf-8".toByteArray())
        if (op == IppPrinter.OP_PRINT_JOB) {
            d.writeByte(IppPrinter.TAG_JOB)
            attr(d, IppPrinter.TAG_INTEGER, "job-id", byteArrayOf(0, 0, 0, 42))
        }
        if (op == IppPrinter.OP_GET_PRINTER_ATTRIBUTES) {
            d.writeByte(0x04)
            attr(d, 0x41, "printer-make-and-model", "KONICA MINOLTA bizhub C361i".toByteArray())
            attr(d, IppPrinter.TAG_ENUM, "printer-state", byteArrayOf(0, 0, 0, 3))
            attr(d, IppPrinter.TAG_BOOLEAN, "printer-is-accepting-jobs", byteArrayOf(1))
        }
        d.writeByte(IppPrinter.TAG_END)
        return out.toByteArray()
    }

    @After
    fun stopFakePrinter() = server.close()

    private fun attr(d: DataOutputStream, tag: Int, name: String, value: ByteArray) {
        d.writeByte(tag); d.writeShort(name.length); d.writeBytes(name); d.writeShort(value.size); d.write(value)
    }

    private fun printer() = IppPrinter("http://127.0.0.1:${server.localPort}/ipp", timeoutMs = 5_000)

    @Test
    fun printJobSendsPdfAfterTheAttributes() {
        val pdf = "%PDF-1.4 test".toByteArray()
        val result = printer().printPdf("Visitor badge V-1", pdf)
        assertTrue(result.detail, result.ok)
        assertEquals(42, result.jobId)
        val body = received.single()
        assertArrayEquals(byteArrayOf(2, 0, 0, 2), body.copyOfRange(0, 4)) // IPP 2.0, Print-Job
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("ipp://127.0.0.1:${server.localPort}/ipp"))
        assertTrue(text.contains("application/pdf") && text.contains("iso_a4_210x297mm") && text.contains("print-scaling"))
        assertTrue(text.endsWith("\u0003%PDF-1.4 test")) // the document follows end-of-attributes directly
        val (_, attrs) = IppPrinter.parseResponse(body.copyOfRange(0, body.size - pdf.size))
        assertEquals(listOf<Any>("Visitor badge V-1"), attrs["job-name"])
        assertEquals(listOf<Any>(1), attrs["copies"])
        assertEquals(listOf<Any>("none"), attrs["print-scaling"])
    }

    @Test
    fun statusReadsModelStateAndAcceptingJobs() {
        val info = printer().status()
        assertTrue(info.reachable)
        assertEquals("KONICA MINOLTA bizhub C361i", info.model)
        assertEquals("idle", info.state)
        assertEquals(true, info.acceptingJobs)
    }

    @Test
    fun validateUsesValidateJobOperation() {
        assertTrue(printer().validate().ok)
        assertArrayEquals(byteArrayOf(0, 4), received.single().copyOfRange(2, 4))
    }

    @Test
    fun printerErrorIsReported() {
        answerStatus = 0x0506
        val result = printer().printPdf("x", byteArrayOf(1))
        assertFalse(result.ok)
        assertEquals("printer is not accepting jobs", result.detail)
    }

    @Test
    fun unreachablePrinterIsReportedNotThrown() {
        val port = server.localPort
        server.close()
        val result = IppPrinter("http://127.0.0.1:$port/ipp", timeoutMs = 1_000).printPdf("x", byteArrayOf(1))
        assertFalse(result.ok)
        assertFalse(IppPrinter("http://127.0.0.1:$port/ipp", timeoutMs = 1_000).status().reachable)
        startFakePrinter()
    }

    @Test
    fun addressConversion() {
        assertEquals("http://192.168.0.233:631/ipp", IppPrinter.toHttpUrl("ipp://192.168.0.233/ipp"))
        assertEquals("http://192.168.0.233/ipp", IppPrinter.toHttpUrl("http://192.168.0.233/ipp"))
        assertEquals("http://192.168.0.233/ipp", IppPrinter.toHttpUrl("http://192.168.0.233"))
        assertEquals("ipp://192.168.0.233/ipp", IppPrinter.printerUri("http://192.168.0.233/ipp"))
        assertEquals("ipp://192.168.0.233/ipp", IppPrinter.printerUri("http://192.168.0.233:631/ipp"))
        assertEquals("ipp://10.0.0.5:8631/ipp", IppPrinter.printerUri("http://10.0.0.5:8631/ipp"))
    }
}
