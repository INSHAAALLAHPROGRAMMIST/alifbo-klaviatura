package uz.alifbo.klaviatura

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Ilovadagi YAGONA tarmoq kodi (CI boshqa faylda tarmoq API'si paydo bo'lsa yig'ishni to'xtatadi).
 *
 * Faqat Tor orqali: Orbot'ning telefon ichidagi SOCKS5 portiga (127.0.0.1:9050) ulanib, manzilni
 * Tor'ning o'ziga hal qildiradi — DNS so'rovi ham telefondan chiqmaydi. Keyin TLS (tizim sertifikatlari,
 * manzil tekshiriladi). To'g'ridan-to'g'ri ulanish yo'li umuman yo'q: Tor ishlamasa — xato, matn
 * hech qayerga ketmaydi. Har so'rovga tasodifiy SOCKS login beriladi, shuning uchun Tor har biriga
 * alohida zanjir quradi va xizmat ikki tarjimani IP bo'yicha bir odamniki deb bog'lay olmaydi.
 */
object TorNet {
    const val ORBOT = "org.torproject.android"
    private const val ACTION_START = "org.torproject.android.intent.action.START"
    private const val EXTRA_PACKAGE = "org.torproject.android.intent.extra.PACKAGE_NAME"

    /** Orbot'ning odatiy SOCKS porti. Boshqa ilovalarning "port shu" degan xabarlariga ataylab ishonilmaydi. */
    const val SOCKS_PORT = 9050

    /** Faqat shu manzillarga (res/xml/network_security_config.xml bilan bir xil ro'yxat). */
    val ALLOWED_HOSTS = setOf("translation.googleapis.com", "api.cognitive.microsofttranslator.com")

    private const val MAX_RESPONSE = 2 shl 20
    private val CRLF = byteArrayOf(13, 10)
    private val HEAD_END = byteArrayOf(13, 10, 13, 10)

    /** Orbot ishlamayapti (lokal SOCKS port yopiq). */
    class TorDown(cause: Throwable?) : IOException("Tor ishlamayapti", cause)

    /** Tor ulana olmadi (SOCKS javob kodi). */
    class TorRefused(val code: Int) : IOException("Tor ulana olmadi: $code")

    class Timeout(cause: Throwable?) : IOException("Vaqt tugadi", cause)

    class TlsFailure(cause: Throwable?) : IOException("TLS xatosi", cause)

    class Response(val code: Int, val headers: Map<String, String>, val body: String)

    private val rnd = SecureRandom()

    fun isOrbotInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(ORBOT, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Orbot'dan Tor'ni ishga tushirishni so'raydi (Orbot sozlamasida "fondan ishga tushirish" yoqiq bo'lsa). */
    fun requestStart(ctx: Context) {
        try {
            ctx.sendBroadcast(Intent(ACTION_START).setPackage(ORBOT).putExtra(EXTRA_PACKAGE, ctx.packageName))
        } catch (e: Exception) {
            // Orbot yo'q yoki qabul qilmadi
        }
    }

    fun openOrbot(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(ORBOT) ?: return false
        return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Play Market'dagi Orbot sahifasi. */
    fun openOrbotStore(ctx: Context): Boolean = try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ORBOT")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        false
    }

    /** Orbot'ning SOCKS porti ochiqmi (faqat 127.0.0.1 ga ulanadi, telefondan tashqariga chiqmaydi). */
    fun isUp(): Boolean = try {
        localSocket(1500).close()
        true
    } catch (e: IOException) {
        false
    }

    private fun localSocket(timeoutMs: Int): Socket {
        val s = Socket(Proxy.NO_PROXY)
        s.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), SOCKS_PORT), timeoutMs)
        return s
    }

    /**
     * Tor orqali HTTPS POST. Fon oqimida chaqiriladi. [host] faqat [ALLOWED_HOSTS] dan bo'lishi mumkin.
     * Hech qanday cookie, qurilma yoki ilova belgisi yuborilmaydi — faqat so'rovning o'zi va [headers].
     */
    fun post(
        host: String,
        path: String,
        contentType: String,
        body: ByteArray,
        headers: List<Pair<String, String>>,
    ): Response {
        require(host in ALLOWED_HOSTS) { "Ruxsat etilmagan manzil" }
        val raw = try {
            localSocket(4000)
        } catch (e: IOException) {
            throw TorDown(e)
        }
        try {
            raw.soTimeout = 45_000
            socksConnect(raw.getInputStream(), raw.getOutputStream(), host, 443)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, host, 443, true) as SSLSocket
            val all = ssl.use {
                ssl.soTimeout = 45_000
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

    // ---------------------------------------------------------------- SOCKS5 (RFC 1928, 1929)

    internal fun socksConnect(input: InputStream, out: OutputStream, host: String, port: Int) {
        out.write(greeting())
        out.flush()
        val choice = readN(input, 2)
        // faqat login/parol usuli: Tor shu orqali har so'rovga alohida zanjir beradi
        if (choice[0] != 5.toByte() || choice[1] != 2.toByte()) throw TorRefused(-1)
        out.write(authRequest(randomToken(), randomToken()))
        out.flush()
        val auth = readN(input, 2)
        if (auth[1] != 0.toByte()) throw TorRefused(-2)
        out.write(connectRequest(host, port))
        out.flush()
        val head = readN(input, 4)
        if (head[0] != 5.toByte() || head[1] != 0.toByte()) throw TorRefused(head[1].toInt() and 0xFF)
        when (head[3].toInt()) {
            1 -> readN(input, 4 + 2)
            3 -> readN(input, (readN(input, 1)[0].toInt() and 0xFF) + 2)
            4 -> readN(input, 16 + 2)
            else -> throw TorRefused(-3)
        }
    }

    /** Versiya 5, bitta usul: 0x02 (login/parol). */
    internal fun greeting(): ByteArray = byteArrayOf(5, 1, 2)

    internal fun authRequest(user: String, pass: String): ByteArray {
        val u = user.toByteArray(Charsets.US_ASCII)
        val p = pass.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p
    }

    /** CONNECT, manzil turi 0x03 (domen nomi): nomni Tor'ning chiqish tuguni hal qiladi. */
    internal fun connectRequest(host: String, port: Int): ByteArray {
        val h = host.toByteArray(Charsets.US_ASCII)
        require(h.size in 1..255)
        return byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((port shr 8).toByte(), port.toByte())
    }

    private fun randomToken(): String {
        val abc = "abcdefghijklmnopqrstuvwxyz0123456789"
        return String(CharArray(16) { abc[rnd.nextInt(abc.length)] })
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
            val lineEnd = indexOf(b, CRLF, i)
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

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) throw IOException("Ulanish uzildi")
            got += r
        }
        return b
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
