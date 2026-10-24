package uz.alifbo.klaviatura

import java.util.Locale
import kotlin.math.ln

/** [prefix] — kursordan oldingi yozilayotgan so'z; [prev] — undan oldingi so'z (START = gap boshi, null = noma'lum). */
data class TextContext(val prefix: String, val prev: String?)

enum class CapStyle { NONE, FIRST, ALL }

object ContextParser {
    const val START = LanguageModel.START

    private fun isApos(c: Char): Boolean =
        c == '\'' || c == '‘' || c == '’' || c == 'ʻ' || c == 'ʼ'

    fun isWordChar(c: Char): Boolean = c.isLetter() || isApos(c)

    /** Kursordan oldingi matndan yozilayotgan so'z va undan oldingi so'zni ajratadi. */
    fun parse(before: CharSequence): TextContext {
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        var start = i
        while (start < before.length && isApos(before[start])) start++
        val prefix = before.substring(start)

        var j = i
        while (j > 0) {
            val c = before[j - 1]
            when {
                isWordChar(c) -> {
                    var k = j
                    while (k > 0 && isWordChar(before[k - 1])) k--
                    var ws = k
                    while (ws < j && isApos(before[ws])) ws++
                    val w = before.substring(ws, j)
                    return TextContext(prefix, if (w.isEmpty()) null else w)
                }
                c == '.' || c == '!' || c == '?' || c == '…' || c == '\n' -> return TextContext(prefix, START)
                c.isDigit() -> return TextContext(prefix, null)
                else -> j--
            }
        }
        return TextContext(prefix, START)
    }
}

object Norm {
    /** Modelda saqlanadigan ko'rinish: kichik harf, apostrof turlari birlashtirilgan. */
    fun word(raw: String, lang: Lang): String {
        val s = raw.lowercase(Locale.ROOT)
        return when (lang) {
            Lang.UZ -> buildString(s.length) {
                for (c in s) {
                    append(
                        if (c == '\'' || c == '‘' || c == '’' || c == 'ʻ' || c == '`' || c == '´') 'ʼ' else c,
                    )
                }
            }
            Lang.EN -> s.replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'')
            else -> s
        }
    }
}

class Suggester(
    private val model: LanguageModel,
    private val personal: PersonalModel?,
    private val lang: Lang,
) {
    fun suggest(ctx: TextContext, cap: CapStyle, max: Int = 3): List<String> {
        val prefix = Norm.word(ctx.prefix, lang)
        val prev = ctx.prev?.let { if (it == ContextParser.START) it else Norm.word(it, lang) }
        val prevIdx = if (prev != null) model.indexOf(prev) else -1
        val scores = HashMap<String, Double>()

        fun add(word: String, s: Double) {
            scores[word] = (scores[word] ?: 0.0) + s
        }

        if (prefix.isEmpty()) {
            if (prevIdx >= 0) {
                for (k in 0 until model.nextCount(prevIdx)) {
                    val id = model.nextId(prevIdx, k)
                    add(model.word(id), 100.0 + model.nextQuant(prevIdx, k) + model.uni(id) * 0.2)
                }
            }
            personal?.next(prev)?.forEach { (w, c) -> add(w, 110.0 + 10.0 * log2(c + 1.0)) }
            if (scores.size < max) {
                for (id in model.topUnigrams) {
                    val w = model.word(id)
                    if (w !in scores) scores[w] = model.uni(id) * 0.5
                }
            }
        } else {
            var i = model.lowerBound(prefix)
            while (i < model.size && model.word(i).startsWith(prefix)) {
                val w = model.word(i)
                if (w != prefix && w != ContextParser.START) {
                    var s = model.uni(i).toDouble()
                    if (prevIdx >= 0) {
                        val bq = model.bigramQuant(prevIdx, i)
                        if (bq > 0) s += 60.0 + bq
                    }
                    add(w, s)
                }
                i++
            }
            personal?.withPrefix(prefix)?.forEach { (w, c) ->
                if (w != prefix) add(w, 40.0 + 10.0 * log2(c + 1.0))
            }
        }

        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(max)
            .map { applyCap(it.key, cap) }
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    private fun applyCap(w: String, cap: CapStyle): String = when (cap) {
        CapStyle.NONE -> w
        CapStyle.ALL -> w.uppercase(Locale.ROOT)
        CapStyle.FIRST -> if (w.isEmpty()) w else w.substring(0, 1).uppercase(Locale.ROOT) + w.substring(1)
    }

    companion object {
        /** Taklif harflari yozilayotgan so'z (yoki Shift holati) ga mos katta-kichik bo'lsin. */
        fun capStyle(rawPrefix: String, shift: Shift): CapStyle = when {
            rawPrefix.length >= 2 && rawPrefix.all { !it.isLetter() || it.isUpperCase() } -> CapStyle.ALL
            rawPrefix.isNotEmpty() && rawPrefix[0].isUpperCase() -> CapStyle.FIRST
            rawPrefix.isEmpty() && shift == Shift.CAPS -> CapStyle.ALL
            rawPrefix.isEmpty() && shift == Shift.ONCE -> CapStyle.FIRST
            else -> CapStyle.NONE
        }
    }
}
