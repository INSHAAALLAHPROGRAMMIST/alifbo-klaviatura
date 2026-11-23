package uz.alifbo.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** HTTP/1.1 qism — tarmoqsiz, baytlar darajasida. */
class NetTest {

    @Test
    fun httpSorovidaOrtiqchaMalumotYoq() {
        val req = String(
            Net.httpRequest("translation.googleapis.com", "/language/translate/v2", "application/json", 12, listOf("X-Goog-Api-Key" to "k")),
            Charsets.UTF_8,
        )
        assertTrue(req.startsWith("POST /language/translate/v2 HTTP/1.1\r\nHost: translation.googleapis.com\r\n"))
        assertTrue(req.endsWith("\r\n\r\n"))
        for (bad in listOf("Cookie", "User-Agent", "Referer", "X-Android", "Accept-Language")) {
            assertTrue(bad, !req.contains(bad))
        }
        try {
            Net.httpRequest("h", "/", "t", 0, listOf("X" to "a\r\nEvil: 1"))
            fail()
        } catch (e: IllegalArgumentException) {
            // sarlavhaga satr qo'shib bo'lmaydi
        }
    }

    @Test
    fun httpJavobOqiladi() {
        val plain = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 7\r\n\r\n{\"a\":1}tail"
        val r = Net.parseResponse(plain.toByteArray())
        assertEquals(200, r.code)
        assertEquals("{\"a\":1}", r.body)

        val chunked = "HTTP/1.1 403 Forbidden\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nSalo\r\n6;x=1\r\nm, ö!\r\n0\r\n\r\n"
        val c = Net.parseResponse(chunked.toByteArray(Charsets.UTF_8))
        assertEquals(403, c.code)
        assertEquals("Salom, ö!", c.body)
    }

    @Test
    fun ruxsatEtilmaganManzilRadEtiladi() {
        try {
            Net.post("evil.example", "/", "t", ByteArray(0), emptyList())
            fail()
        } catch (e: IllegalArgumentException) {
            // faqat ALLOWED_HOSTS
        }
    }
}
