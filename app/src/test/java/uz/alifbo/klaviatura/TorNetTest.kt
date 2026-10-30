package uz.alifbo.klaviatura

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Tor (SOCKS5) va HTTP qismlari — tarmoqsiz, baytlar darajasida. */
class TorNetTest {

    @Test
    fun socksXabarlariRfcBoyicha() {
        // faqat login/parol usuli taklif qilinadi (har so'rovga alohida Tor zanjiri uchun)
        assertArrayEquals(byteArrayOf(5, 1, 2), TorNet.greeting())
        assertArrayEquals(byteArrayOf(1, 1, 'a'.code.toByte(), 2, 'b'.code.toByte(), 'c'.code.toByte()), TorNet.authRequest("a", "bc"))
        val host = "translation.googleapis.com"
        val c = TorNet.connectRequest(host, 443)
        // 0x03 — domen nomi: DNS telefonda emas, Tor chiqish tugunida hal qilinadi
        assertEquals(3.toByte(), c[3])
        assertEquals(host.length.toByte(), c[4])
        assertEquals(host, String(c, 5, host.length, Charsets.US_ASCII))
        assertEquals(1.toByte(), c[c.size - 2])
        assertEquals(0xBB.toByte(), c[c.size - 1])
    }

    @Test
    fun socksMuloqoti() {
        // server: usul 0x02, login qabul, CONNECT muvaffaqiyatli (IPv4 javob)
        val server = byteArrayOf(5, 2, 1, 0, 5, 0, 0, 1, 10, 0, 0, 1, 1, 0xBB.toByte())
        val out = ByteArrayOutputStream()
        TorNet.socksConnect(ByteArrayInputStream(server), out, "api.cognitive.microsofttranslator.com", 443)
        val sent = out.toByteArray()
        assertArrayEquals(byteArrayOf(5, 1, 2), sent.copyOfRange(0, 3))
        assertEquals(1.toByte(), sent[3])
        assertEquals(16.toByte(), sent[4]) // tasodifiy login uzunligi
    }

    @Test
    fun socksRadQilinsaXato() {
        // server login usulini qabul qilmadi
        try {
            TorNet.socksConnect(ByteArrayInputStream(byteArrayOf(5, 0xFF.toByte())), ByteArrayOutputStream(), "x.y", 443)
            fail()
        } catch (e: TorNet.TorRefused) {
            // kutilgan
        }
        // CONNECT rad etildi (host unreachable = 4)
        try {
            TorNet.socksConnect(ByteArrayInputStream(byteArrayOf(5, 2, 1, 0, 5, 4, 0, 1)), ByteArrayOutputStream(), "x.y", 443)
            fail()
        } catch (e: TorNet.TorRefused) {
            assertEquals(4, e.code)
        }
    }

    @Test
    fun httpSorovidaOrtiqchaMalumotYoq() {
        val req = String(
            TorNet.httpRequest("translation.googleapis.com", "/language/translate/v2", "application/json", 12, listOf("X-Goog-Api-Key" to "k")),
            Charsets.UTF_8,
        )
        assertTrue(req.startsWith("POST /language/translate/v2 HTTP/1.1\r\nHost: translation.googleapis.com\r\n"))
        assertTrue(req.endsWith("\r\n\r\n"))
        for (bad in listOf("Cookie", "User-Agent", "Referer", "X-Android", "Accept-Language")) {
            assertTrue(bad, !req.contains(bad))
        }
        try {
            TorNet.httpRequest("h", "/", "t", 0, listOf("X" to "a\r\nEvil: 1"))
            fail()
        } catch (e: IllegalArgumentException) {
            // sarlavhaga satr qo'shib bo'lmaydi
        }
    }

    @Test
    fun httpJavobOqiladi() {
        val plain = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 7\r\n\r\n{\"a\":1}tail"
        val r = TorNet.parseResponse(plain.toByteArray())
        assertEquals(200, r.code)
        assertEquals("{\"a\":1}", r.body)

        val chunked = "HTTP/1.1 403 Forbidden\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nSalo\r\n6;x=1\r\nm, ö!\r\n0\r\n\r\n"
        val c = TorNet.parseResponse(chunked.toByteArray(Charsets.UTF_8))
        assertEquals(403, c.code)
        assertEquals("Salom, ö!", c.body)
    }
}
