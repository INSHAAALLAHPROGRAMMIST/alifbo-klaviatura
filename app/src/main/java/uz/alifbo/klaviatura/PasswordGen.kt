package uz.alifbo.klaviatura

import java.security.SecureRandom
import kotlin.math.ln

/** Kuchli parol yaratish va parol kuchini taxminiy baholash. */
object PasswordGen {
    // bir-biriga o'xshash belgilar (0 O, 1 l I) ataylab yo'q: qo'lda ko'chirilganda adashmaslik uchun
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%&*-_=+?"

    private val COMMON = setOf(
        "password", "parol", "qwerty", "123456", "1234567", "12345678", "123456789", "1234567890",
        "111111", "000000", "abc123", "admin", "iloveyou", "qwerty123", "password1", "salom", "uzbekistan",
        "toshkent", "toşkent", "parol123", "11111111", "987654321", "qwertyuiop", "asdfgh", "zxcvbn",
    )

    /** [length] belgili parol: har turdan kamida bittasi bor, aralashtirilgan. */
    fun generate(length: Int = 16, rnd: SecureRandom = SecureRandom()): String {
        val n = length.coerceAtLeast(8)
        val sets = listOf(LOWER, UPPER, DIGITS, SYMBOLS)
        val all = sets.joinToString("")
        val chars = ArrayList<Char>(n)
        for (s in sets) chars.add(s[rnd.nextInt(s.length)])
        while (chars.size < n) chars.add(all[rnd.nextInt(all.length)])
        for (i in chars.size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = chars[i]
            chars[i] = chars[j]
            chars[j] = t
        }
        return chars.joinToString("")
    }

    /** 0 — juda zaif, 1 — zaif, 2 — o'rtacha, 3 — yaxshi, 4 — kuchli. */
    fun strength(p: String): Int {
        if (p.length < 6) return 0
        val lower = p.lowercase()
        if (lower in COMMON || COMMON.any { lower.startsWith(it) && p.length - it.length <= 3 }) return 0
        if (p.toSet().size <= 2) return 0
        var pool = 0
        if (p.any { it.isLowerCase() }) pool += 26
        if (p.any { it.isUpperCase() }) pool += 26
        if (p.any { it.isDigit() }) pool += 10
        if (p.any { !it.isLetterOrDigit() }) pool += 20
        var bits = p.length * ln(pool.toDouble().coerceAtLeast(2.0)) / ln(2.0)
        if (hasRun(lower)) bits -= 12
        if (p.all { it.isDigit() }) bits -= 10
        return when {
            bits < 30 -> 0
            bits < 40 -> 1
            bits < 60 -> 2
            bits < 80 -> 3
            else -> 4
        }
    }

    /** Ketma-ket 4 belgi (1234, abcd, 4321) yoki klaviatura qatori bo'lagi. */
    private fun hasRun(s: String): Boolean {
        var up = 1
        var down = 1
        for (i in 1 until s.length) {
            val d = s[i] - s[i - 1]
            up = if (d == 1) up + 1 else 1
            down = if (d == -1) down + 1 else 1
            if (up >= 4 || down >= 4) return true
        }
        return listOf("qwer", "asdf", "zxcv", "йцук", "фыва").any { it in s }
    }
}
