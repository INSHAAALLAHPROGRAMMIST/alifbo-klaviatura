package uz.alifbo.klaviatura

import java.util.Locale

enum class Kind { CHAR, SHIFT, DELETE, ENTER, SPACE, TO_SYM1, TO_SYM2, TO_LETTERS, LANGUAGE, EMOJI, SPACER }

enum class Page { LETTERS, SYM1, SYM2 }

enum class Shift { OFF, ONCE, CAPS }

enum class EnterStyle { RETURN, GO, SEARCH, SEND, NEXT, DONE }

data class Key(
    val kind: Kind,
    val label: String = "",
    val weight: Float = 1f,
    val popup: List<String> = emptyList(),
)

/** Qatorlarning og'irliklari yig'indisi bir xil bo'ladi (harf tugmalari hamma qatorda teng kenglikda). */
class KbLayout(val rows: List<List<Key>>) {
    val total: Float = rows.maxOf { r -> r.sumOf { it.weight.toDouble() }.toFloat() }
}

enum class Lang(val id: String, val title: String, val label: String, val short: String) {
    UZ("uz", "Özbek (yangi alifbo)", "Özbek", "UZ"),
    EN("en", "English (US)", "English", "EN"),
    RU("ru", "Русский", "Русский", "RU"),
    UZC("uzc", "Ўзбек (кирилл)", "Ўзбек", "ЎЗ");

    companion object {
        fun byId(id: String): Lang? = entries.firstOrNull { it.id == id }
    }
}

/** Locale.ROOT: "i" -> "I" (turkcha İ emas), ö -> Ö, ğ -> Ğ, ş -> Ş, ç -> Ç. */
fun String.upperUz(): String = uppercase(Locale.ROOT)

object Layouts {
    /** Tutuq belgisi (U+02BC). Yangi alifboda "28 harf + 1 tutuq belgisi". */
    val TUTUQ: String = String(Character.toChars(0x02BC))
    private val OKINA: String = String(Character.toChars(0x02BB))
    private val RSQUO: String = String(Character.toChars(0x2019))
    private val LSQUO: String = String(Character.toChars(0x2018))

    private class Spec(
        val r1: List<String>,
        val r2: List<String>,
        val r3: List<String>,
        val extra: Map<String, List<String>> = emptyMap(),
    )

    private val specs: Map<Lang, Spec> = mapOf(
        // Qonun (O'RQ-1184, 21.10.2026) alifbosi: Ö Ğ Ş Ç; w va c yo'q -> w o'rniga ğ, c o'rniga ç.
        Lang.UZ to Spec(
            r1 = listOf("q", "ğ", "e", "r", "t", "y", "u", "i", "o", "p"),
            r2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ş"),
            r3 = listOf("z", "x", "ç", "v", "b", "n", "m", "ö"),
        ),
        Lang.EN to Spec(
            r1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            r2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            r3 = listOf("z", "x", "c", "v", "b", "n", "m"),
            extra = mapOf(
                "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
                "e" to listOf("è", "é", "ê", "ë", "ē"),
                "i" to listOf("î", "ï", "í", "ī", "ì"),
                "o" to listOf("ô", "ö", "ò", "ó", "œ", "ø", "õ"),
                "u" to listOf("û", "ü", "ù", "ú", "ū"),
                "c" to listOf("ç", "ć", "č"),
                "n" to listOf("ñ", "ń"),
                "s" to listOf("ß", "ś", "š"),
                "y" to listOf("ÿ", "ý"),
                "z" to listOf("ž", "ź", "ż"),
            ),
        ),
        Lang.RU to Spec(
            r1 = listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х"),
            r2 = listOf("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э"),
            r3 = listOf("я", "ч", "с", "м", "и", "т", "ь", "б", "ю"),
            extra = mapOf("е" to listOf("ё"), "ь" to listOf("ъ")),
        ),
        Lang.UZC to Spec(
            r1 = listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "ў", "з", "х", "ъ"),
            r2 = listOf("ф", "қ", "в", "а", "п", "р", "о", "л", "д", "ж", "э"),
            r3 = listOf("я", "ч", "с", "м", "и", "т", "б", "ю", "ғ", "ҳ"),
            extra = mapOf(
                "е" to listOf("ё"),
                "ш" to listOf("щ"),
                "и" to listOf("ы"),
                "ъ" to listOf("ь"),
            ),
        ),
    )

    private const val DIGITS = "1234567890"

    private val dotPopup = listOf("?", "!", ":", ";", "…", "-")

    private fun spacer(w: Float) = Key(Kind.SPACER, "", w)

    private fun letterKey(label: String, spec: Spec, digitIndex: Int): Key {
        val pop = buildList {
            if (digitIndex in 0..9) add(DIGITS[digitIndex].toString())
            addAll(spec.extra[label].orEmpty())
        }
        return Key(Kind.CHAR, label, 1f, pop)
    }

    private fun fit(row: List<Key>, total: Float): List<Key> {
        val diff = total - row.sumOf { it.weight.toDouble() }.toFloat()
        return if (diff > 0.001f) listOf(spacer(diff / 2f)) + row + listOf(spacer(diff / 2f)) else row
    }

    /** SPACE bo'lmagan tugmalarni kattalashtiradi, qolgan joyni probel oladi. */
    private fun scaleAndFit(keys: List<Key>, total: Float): List<Key> {
        val scale = total / 10f
        val scaled = keys.map { if (it.kind == Kind.SPACE) it else it.copy(weight = it.weight * scale) }
        val used = scaled.filter { it.kind != Kind.SPACE }.sumOf { it.weight.toDouble() }.toFloat()
        return scaled.map { if (it.kind == Kind.SPACE) it.copy(weight = total - used) else it }
    }

    private fun bottom(lang: Lang?, total: Float, modeKind: Kind, modeLabel: String): List<Key> {
        // probel imkon qadar keng bo'lsin: qolgan tugmalar ixcham
        val keys = buildList {
            add(Key(modeKind, modeLabel, 1.2f))
            add(Key(Kind.LANGUAGE, "", 0.9f))
            add(Key(Kind.EMOJI, "", 0.9f))
            add(Key(Kind.CHAR, ",", 0.8f))
            add(Key(Kind.SPACE, "", 1f))
            when (lang) {
                Lang.UZ -> add(Key(Kind.CHAR, TUTUQ, 0.8f, listOf("'", RSQUO, OKINA)))
                Lang.EN -> add(Key(Kind.CHAR, "'", 0.8f, listOf(RSQUO)))
                else -> {}
            }
            add(Key(Kind.CHAR, ".", 0.8f, dotPopup))
            add(Key(Kind.ENTER, "", 1.3f))
        }
        return scaleAndFit(keys, total)
    }

    fun letters(lang: Lang, numberRow: Boolean = false): KbLayout {
        val spec = specs.getValue(lang)
        val total = maxOf(spec.r1.size, spec.r2.size, spec.r3.size + 2).toFloat()
        val rows = mutableListOf<List<Key>>()
        if (numberRow) {
            rows += DIGITS.map { Key(Kind.CHAR, it.toString(), total / 10f) }
        }
        rows += fit(spec.r1.mapIndexed { i, l -> letterKey(l, spec, if (numberRow) -1 else i) }, total)
        rows += fit(spec.r2.map { letterKey(it, spec, -1) }, total)
        val r3 = spec.r3.map { letterKey(it, spec, -1) }
        val mod = (total - r3.size) / 2f
        rows += listOf(Key(Kind.SHIFT, "", mod)) + r3 + listOf(Key(Kind.DELETE, "", mod))
        rows += bottom(lang, total, Kind.TO_SYM1, "?123")
        return KbLayout(rows)
    }

    private val symPopups: Map<String, List<String>> = mapOf(
        "$" to listOf("€", "£", "¥", "₽", "₸", "¢"),
        "%" to listOf("‰"),
        "-" to listOf("–", "—", "•"),
        "+" to listOf("±", "×", "÷"),
        "\"" to listOf("«", "»", "“", "”", "„"),
        "'" to listOf(TUTUQ, RSQUO, LSQUO, OKINA),
        "!" to listOf("¡"),
        "?" to listOf("¿"),
        "(" to listOf("[", "{", "<"),
        ")" to listOf("]", "}", ">"),
        "/" to listOf("\\", "|"),
        "0" to listOf("°"),
        "1" to listOf("¹", "½"),
        "2" to listOf("²"),
        "3" to listOf("³"),
    )

    private fun chars(vararg labels: String): List<Key> =
        labels.map { Key(Kind.CHAR, it, 1f, symPopups[it].orEmpty()) }

    fun symbols(page: Page): KbLayout {
        val total = 10f
        val rows = when (page) {
            Page.SYM2 -> listOf(
                chars("~", "`", "|", "<", ">", "[", "]", "{", "}", "\\"),
                chars("^", "_", "=", TUTUQ, "«", "»", "“", "”", "–", "—"),
                listOf(Key(Kind.TO_SYM1, "?123", 1.5f)) +
                    chars("£", "€", "¥", "₽", "₸", "№", "°") +
                    listOf(Key(Kind.DELETE, "", 1.5f)),
                bottom(null, total, Kind.TO_LETTERS, "ABC"),
            )
            else -> listOf(
                chars("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                chars("@", "#", "$", "%", "&", "-", "+", "(", ")", "/"),
                listOf(Key(Kind.TO_SYM2, "=\\<", 1.5f)) +
                    chars("*", "\"", "'", ":", ";", "!", "?") +
                    listOf(Key(Kind.DELETE, "", 1.5f)),
                bottom(null, total, Kind.TO_LETTERS, "ABC"),
            )
        }
        return KbLayout(rows)
    }
}
