package uz.alifbo.klaviatura

/**
 * O'zbek yozuvlari orasida o'tkazish (tarjima uchun).
 *
 * Tarjima xizmatlari eski lotin imlosini (oʻ, gʻ, sh, ch) biladi, yangi alifbodagi matnni (ö, ğ, ş, ç)
 * esa turkcha deb o'ylashi mumkin. Shuning uchun yuborishdan oldin eski imloga, kelgan natijani esa
 * yangi alifboga o'giramiz. O'zbek kirill uchun — lotinga va aksincha.
 */
object UzScript {
    private const val OKINA = 'ʻ' // U+02BB: oʻ, gʻ
    private const val TUTUQ = 'ʼ' // U+02BC: tutuq belgisi
    private val APOS = charArrayOf('\'', '‘', '’', 'ʻ', 'ʼ', '`', '´')

    /** Yangi alifbo -> eski lotin: ö -> oʻ, ğ -> gʻ, ş -> sh, ç -> ch (bosh harflar ham). */
    fun newToOld(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (i in s.indices) {
            val c = s[i]
            // "ŞAHAR" -> "SHAHAR", "Şahar" -> "Shahar", "TOŞ" -> "TOSH"
            val loud = (i + 1 < s.length && s[i + 1].isUpperCase()) ||
                (i > 0 && s[i - 1].isUpperCase() && (i + 1 >= s.length || !s[i + 1].isLowerCase()))
            when (c) {
                'ö' -> sb.append('o').append(OKINA)
                'Ö' -> sb.append('O').append(OKINA)
                'ğ' -> sb.append('g').append(OKINA)
                'Ğ' -> sb.append('G').append(OKINA)
                'ş' -> sb.append("sh")
                'Ş' -> sb.append(if (loud) "SH" else "Sh")
                'ç' -> sb.append("ch")
                'Ç' -> sb.append(if (loud) "CH" else "Ch")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Eski lotin (tarjima natijasi) -> yangi alifbo. So'z ichidagi apostrof -> tutuq belgisi (ʼ). */
    fun oldToNew(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val n = if (i + 1 < s.length) s[i + 1] else '\u0000'
            val n2 = if (i + 2 < s.length) s[i + 2] else '\u0000'
            when {
                // gʻ so'z oxirida ham ko'p uchraydi: tog', bog', yog'
                (c == 'g' || c == 'G') && n in APOS -> {
                    sb.append(if (c == 'g') 'ğ' else 'Ğ')
                    i += 2
                }
                // oʻ: qo'shtirnoq bilan adashmaslik uchun ortidan harf kelsa (yoki aniq ʻ bo'lsa)
                (c == 'o' || c == 'O') && n in APOS && (n == OKINA || n2.isLetter()) -> {
                    sb.append(if (c == 'o') 'ö' else 'Ö')
                    i += 2
                }
                (c == 's' || c == 'S') && (n == 'h' || n == 'H') -> {
                    sb.append(if (c == 's') 'ş' else 'Ş')
                    i += 2
                }
                (c == 'c' || c == 'C') && (n == 'h' || n == 'H') -> {
                    sb.append(if (c == 'c') 'ç' else 'Ç')
                    i += 2
                }
                c in APOS && i > 0 && s[i - 1].isLetter() && n.isLetter() -> {
                    sb.append(TUTUQ)
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private val CYR_TO_LAT: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'ж' to "j", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "x",
        'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sh", 'ъ' to "ʼ", 'ь' to "", 'ы' to "i",
        'э' to "e", 'ю' to "yu", 'я' to "ya", 'ё' to "yo",
        'ў' to "oʻ", 'қ' to "q", 'ғ' to "gʻ", 'ҳ' to "h",
    )

    private const val CYR_VOWELS = "аеёиоуўэюяъь"

    /** O'zbek kirill -> eski lotin (tarjima xizmatiga yuborish uchun). */
    fun cyrillicToOldLatin(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (i in s.indices) {
            val c = s[i]
            val lower = c.lowercaseChar()
            val prev = if (i > 0) s[i - 1].lowercaseChar() else ' '
            val m = if (lower == 'е') {
                if (i == 0 || !prev.isLetter() || prev in CYR_VOWELS) "ye" else "e"
            } else {
                CYR_TO_LAT[lower]
            }
            if (m == null) {
                sb.append(c)
                continue
            }
            if (c == lower || m.isEmpty()) {
                sb.append(m)
            } else {
                val loud = i + 1 < s.length && s[i + 1].isUpperCase()
                sb.append(if (loud) m.uppercase() else m.replaceFirstChar { it.uppercaseChar() })
            }
        }
        return sb.toString()
    }

    private val LAT_TO_CYR: Map<Char, String> = mapOf(
        'a' to "а", 'b' to "б", 'd' to "д", 'f' to "ф", 'g' to "г", 'h' to "ҳ", 'i' to "и",
        'j' to "ж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п",
        'q' to "қ", 'r' to "р", 's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'x' to "х",
        'z' to "з", 'ö' to "ў", 'ğ' to "ғ", 'ş' to "ш", 'ç' to "ч", 'ʼ' to "ъ", 'c' to "с", 'w' to "в",
    )

    /** Yangi lotin -> o'zbek kirill (taxminiy, tarjima natijasi uchun yetarli). */
    fun latinToCyrillic(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val lower = c.lowercaseChar()
            val n = if (i + 1 < s.length) s[i + 1].lowercaseChar() else '\u0000'
            val prev = if (i > 0) s[i - 1] else ' '
            var step = 1
            val out: String? = when (lower) {
                'y' -> when (n) {
                    'a' -> "я".also { step = 2 }
                    'o' -> "ё".also { step = 2 }
                    'u' -> "ю".also { step = 2 }
                    'e' -> "е".also { step = 2 }
                    else -> "й"
                }
                'e' -> if (!prev.isLetter()) "э" else "е"
                else -> LAT_TO_CYR[lower]
            }
            if (out == null) {
                sb.append(c)
                i++
                continue
            }
            sb.append(if (c != lower) out.uppercase() else out)
            i += step
        }
        return sb.toString()
    }

    /** O'zbek kirilliga xos harflar (ў қ ғ ҳ) bormi. */
    fun hasUzbekCyrillic(s: String): Boolean = s.any { it in "ўқғҳЎҚҒҲ" }

    fun isMostlyCyrillic(s: String): Boolean {
        var cyr = 0
        var lat = 0
        for (c in s) {
            if (!c.isLetter()) continue
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) cyr++ else lat++
        }
        return cyr > lat
    }

    /** Yangi o'zbek alifbosidagi matnga o'xshaydimi: ö ğ ş ç bor, turkcha/ozarbayjoncha ü ı ə yo'q. */
    fun looksNewUzbek(s: String): Boolean =
        s.any { it in "öğşçÖĞŞÇ" } && s.none { it in "üıəÜİƏ" }
}
