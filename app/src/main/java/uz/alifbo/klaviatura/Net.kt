package uz.alifbo.klaviatura

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Ilovadagi YAGONA tarmoq kodi (CI boshqa faylda tarmoq API'si paydo bo'lsa yig'ishni to'xtatadi).
 *
 * To'g'ridan-to'g'ri TLS ulanish (tizim sertifikatlari, manzil tekshiriladi). Faqat [ALLOWED_HOSTS]
 * ga: boshqa manzilga ulanish kodi umuman yo'q, network_security_config.xml ham boshqa hamma joyga
 * TLS'ni yopadi. Tor/Orbot orqali EMAS (2026-11: Google Tor chiqish tugunlarini bloklagani uchun
 * olib tashlandi) — ya'ni xizmat so'rov manzilingizni (IP) ko'radi.
 */
object Net {
    /** Faqat shu manzilga (res/xml/network_security_config.xml bilan bir xil ro'yxat). */
    val ALLOWED_HOSTS = setOf("translation.googleapis.com")

    private const val MAX_RESPONSE = 2 shl 20
    private val HEAD_END = byteArrayOf(13, 10, 13, 10)

    class Timeout(cause: Throwable?) : IOException("Vaqt tugadi", cause)

    class TlsFailure(cause: Throwable?) : IOException("TLS xatosi", cause)

    class Response(val code: Int, val headers: Map<String, String>, val body: String)

    /**
     * To'g'ridan-to'g'ri HTTPS POST. Fon oqimida chaqiriladi. [host] faqat [ALLOWED_HOSTS] dan
     * bo'lishi mumkin. Hech qanday cookie yuborilmaydi — faqat so'rovning o'zi va [headers].
     */
    fun post(
        host: String,
        path: String,
        contentType: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
    ): Response {
        require(host in ALLOWED_HOSTS) { "Ruxsat etilmagan manzil" }
        val raw = Socket(host, 443)
        try {
            raw.soTimeout = 20_000
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, host, 443, true) as SSLSocket
            val all = ssl.use {
                ssl.soTimeout = 20_000
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = params
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                    throw TlsFailure(null)
                }
                val out = ssl.getOutputStream()
                out.write(httpRequest(host, path, contentType, body.size, headers))
                out.write(body)
                out.flush()
                readAll(ssl.getInputStream(), MAX_RESPONSE)
            }
            return parseResponse(all)
        } catch (e: SocketTimeoutException) {
            throw Timeout(e)
        } catch (e: SSLException) {
            throw TlsFailure(e)
        } finally {
            try {
                raw.close()
            } catch (e: IOException) {
                // yopilgan
            }
        }
    }

    // ---------------------------------------------------------------- HTTP/1.1

    internal fun httpRequest(
        host: String,
        path: String,
        contentType: String,
        length: Int,
        headers: List<Pair<String, String>>,
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("POST ").append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(host).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Accept: application/json\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in headers) {
            require(k.none { it == '\r' || it == '\n' || it == ':' } && v.none { it == '\r' || it == '\n' })
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    internal fun parseResponse(all: ByteArray): Response {
        val sep = indexOf(all, HEAD_END, 0)
        if (sep < 0) throw IOException("Javob buzuq")
        val lines = String(all, 0, sep, Charsets.ISO_8859_1).split("\r\n")
        val code = lines[0].split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Javob buzuq")
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        var body = all.copyOfRange(sep + HEAD_END.size, all.size)
        if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
            body = dechunk(body)
        } else {
            headers["content-length"]?.toIntOrNull()?.let { if (it in 0..body.size) body = body.copyOf(it) }
        }
        return Response(code, headers, String(body, Charsets.UTF_8))
    }

    internal fun dechunk(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < b.size) {
            val lineEnd = indexOf(b, byteArrayOf(13, 10), i)
            if (lineEnd < 0) break
            val size = String(b, i, lineEnd - i, Charsets.US_ASCII).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size <= 0) break
            val start = lineEnd + 2
            val n = minOf(size, b.size - start)
            out.write(b, start, n)
            i = start + size + 2
        }
        return out.toByteArray()
    }

    private fun indexOf(a: ByteArray, p: ByteArray, from: Int): Int {
        var i = from
        while (i <= a.size - p.size) {
            var ok = true
            for (j in p.indices) {
                if (a[i + j] != p[j]) {
                    ok = false
                    break
                }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    private fun readAll(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > limit) throw IOException("Javob juda katta")
        }
        return out.toByteArray()
    }
}
