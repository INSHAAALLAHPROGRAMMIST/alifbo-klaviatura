package uz.alifbo.klaviatura

import java.util.Locale
import kotlin.math.ln

/**
 * [prefix] — kursordan oldingi yozilayotgan so'z; [prev] — undan oldingi so'z (START = gap boshi, null = noma'lum);
 * [history] — oldingi so'zlar eskidan yangiga (eng ko'pi 4 ta, gap boshi bo'lsa birinchisi START).
 */
data class TextContext(val prefix: String, val prev: String?, val history: List<String> = listOfNotNull(prev))

enum class CapStyle { NONE, FIRST, ALL }

object ContextParser {
    const val START = LanguageModel.START
    const val MAX_HISTORY = 4

    private fun isApos(c: Char): Boolean =
        c == '\'' || c == '‘' || c == '’' || c == 'ʻ' || c == 'ʼ'

    fun isWordChar(c: Char): Boolean = c.isLetter() || isApos(c)

    private fun isTerminator(c: Char): Boolean = c == '.' || c == '!' || c == '?' || c == '…' || c == '\n'

    /** Kursordan oldingi matndan yozilayotgan so'z va undan oldingi (eng ko'pi 4 ta) so'zni ajratadi. */
    fun parse(before: CharSequence): TextContext {
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        var start = i
        while (start < before.length && isApos(before[start])) start++
        val prefix = before.substring(start)

        val newestFirst = ArrayList<String>(MAX_HISTORY)
        var j = i
        scan@ while (newestFirst.size < MAX_HISTORY) {
            if (j == 0) {
                newestFirst.add(START)
                break
            }
            val c = before[j - 1]
            when {
                isWordChar(c) -> {
                    var k = j
                    while (k > 0 && isWordChar(before[k - 1])) k--
                    var ws = k
                    while (ws < j && isApos(before[ws])) ws++
                    val w = before.substring(ws, j)
                    if (w.isEmpty()) break@scan
                    newestFirst.add(w)
                    j = k
                }
                isTerminator(c) -> {
                    newestFirst.add(START)
                    break@scan
                }
                c.isDigit() -> break@scan
                else -> j--
            }
        }
        return TextContext(prefix, newestFirst.firstOrNull(), newestFirst.asReversed().toList())
    }
}

object Norm {
    /** Modelda saqlanadigan ko'rinish: kichik harf, apostrof turlari birlashtirilgan. */
    fun word(raw: String, lang: Lang): String {
        if (raw == ContextParser.START) return raw
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

/**
 * Yangi alifboga o'tish davri uchun yordam: eski odat bilan yozilgan prefiksning yangi alifbodagi variantlari.
 * s -> ş, o -> ö, g -> ğ, c -> ç, shuningdek sh -> ş, ch -> ç, oʼ -> ö, gʼ -> ğ.
 * Faqat davomi bor (lug'atda shunday boshlanadigan so'z bor) variantlar qaytariladi.
 */
object OldSpelling {
    private const val MAX_VARIANTS = 32
    private const val TUTUQ = 'ʼ'

    /** (variant, almashtirishlar soni); birinchisi — yozilganning o'zi (agar davomi bo'lsa). */
    fun variants(prefix: String, exists: (String) -> Boolean): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val sb = StringBuilder()

        fun go(i: Int, subs: Int) {
            if (out.size >= MAX_VARIANTS) return
            if (sb.isNotEmpty() && !exists(sb.toString())) return
            if (i == prefix.length) {
                out.add(sb.toString() to subs)
                return
            }
            val c = prefix[i]
            val n = if (i + 1 < prefix.length) prefix[i + 1] else '\u0000'

            fun branch(s: String, next: Int, add: Int) {
                val len = sb.length
                sb.append(s)
                go(next, subs + add)
                sb.setLength(len)
            }

            branch(c.toString(), i + 1, 0)
            when {
                c == 's' && n == 'h' -> branch("ş", i + 2, 1)
                c == 'c' && n == 'h' -> branch("ç", i + 2, 1)
                c == 'o' && n == TUTUQ -> branch("ö", i + 2, 1)
                c == 'g' && n == TUTUQ -> branch("ğ", i + 2, 1)
            }
            when (c) {
                's' -> branch("ş", i + 1, 1)
                'o' -> branch("ö", i + 1, 1)
                'g' -> branch("ğ", i + 1, 1)
                'c' -> branch("ç", i + 1, 1)
            }
        }

        go(0, 0)
        return out
    }
}

class Suggester(
    private val model: LanguageModel,
    private val personal: PersonalModel?,
    private val lang: Lang,
) {
    /**
     * [fuzzy] — eski yozuvdan yordam (faqat yangi o'zbek lotinida): "s" yozilsa "ş" bilan boshlanadigan
     * so'zlar ham taklif qilinadi va h.k. Almashtirilgan har harf uchun kichik jarima bor, shuning uchun
     * yozilganga aynan mos so'z teng holatda oldinda turadi.
     */
    fun suggest(ctx: TextContext, cap: CapStyle, max: Int = 3, fuzzy: Boolean = false): List<String> {
        val prefix = Norm.word(ctx.prefix, lang)
        val hist = ctx.history.map { Norm.word(it, lang) }

        // modeldagi kontekst: lug'atda yo'q so'zdan keyingi qismi (noma'lum so'z kontekstni uzadi)
        val ids = ArrayList<Int>(hist.size)
        for (w in hist) {
            val id = model.indexOf(w)
            if (id < 0) ids.clear() else ids.add(id)
        }
        // har tartib uchun (kontekst uzunligi + 1) -> (so'z id -> kuch)
        val byOrder = ArrayList<Pair<Int, HashMap<Int, Int>>>()
        for (len in 1..minOf(ids.size, model.maxOrder - 1)) {
            val c = IntArray(len) { ids[ids.size - len + it] }
            val m = HashMap<Int, Int>()
            if (model.next(c) { id, q -> m[id] = q }) byOrder.add(len + 1 to m)
        }
        // shaxsiy kontekstlar: oxirgi 1..4 so'z
        val personalCtx = (1..minOf(hist.size, ContextParser.MAX_HISTORY)).map { k ->
            k to hist.subList(hist.size - k, hist.size).joinToString(" ")
        }

        val scores = HashMap<String, Double>()

        fun add(word: String, s: Double) {
            scores[word] = (scores[word] ?: 0.0) + s
        }

        if (prefix.isEmpty()) {
            // uzunroq mos kelgan kontekst kuchliroq: 2 so'z -> 100, 3 -> 200, 4 -> 300, 5 -> 400
            for ((order, m) in byOrder) {
                for ((id, q) in m) {
                    val w = model.word(id)
                    if (w == ContextParser.START) continue
                    add(w, 100.0 * (order - 1) + q + if (order == 2) model.uni(id) * 0.2 else 0.0)
                }
            }
            // shaxsiy odat katta korpusdagi umumiy statistikadan kuchliroq bo'lsin
            for ((k, key) in personalCtx) {
                personal?.next(key)?.forEach { (w, c) ->
                    if (trusted(w, c)) add(w, 150.0 + 40.0 * (k - 1) + 30.0 * log2(c + 1.0))
                }
            }
            if (scores.size < max) {
                for (id in model.topUnigrams) {
                    val w = model.word(id)
                    if (w !in scores) scores[w] = model.uni(id) * 0.5
                }
            }
        } else {
            val variants = if (fuzzy && lang == Lang.UZ) {
                OldSpelling.variants(prefix) { hasPrefix(it) }
            } else {
                listOf(prefix to 0)
            }
            for ((p, subs) in variants) {
                val penalty = SUBSTITUTION_PENALTY * subs
                var i = model.lowerBound(p)
                while (i < model.size && model.word(i).startsWith(p)) {
                    val w = model.word(i)
                    if (w != prefix && w != ContextParser.START) {
                        var s = model.uni(i).toDouble() - penalty
                        for ((order, m) in byOrder) m[i]?.let { s += 60.0 * (order - 1) + it }
                        add(w, s)
                    }
                    i++
                }
            }
            val pm = personal
            if (pm != null) {
                for ((p, subs) in variants) {
                    for ((w, c) in pm.withPrefix(p)) {
                        if (w == prefix || !trusted(w, c)) continue
                        var s = 120.0 + 25.0 * log2(c + 1.0) - SUBSTITUTION_PENALTY * subs
                        for ((k, key) in personalCtx) pm.next(key)?.get(w)?.let { s += 40.0 * k }
                        add(w, s)
                    }
                }
            }
        }

        return topK(scores, max).map { applyCap(it, cap) }
    }

    /**
     * Lug'atda yo'q shaxsiy so'z kamida 2 marta yozilgandagina taklif qilinadi:
     * bir marta qilingan imlo xatosi taklifga chiqib qolmasin.
     */
    private fun trusted(word: String, count: Int): Boolean = count >= 2 || model.indexOf(word) >= 0

    /** Lug'atda yoki shaxsiy so'zlarda [p] bilan boshlanadigan so'z bormi. */
    private fun hasPrefix(p: String): Boolean {
        val i = model.lowerBound(p)
        if (i < model.size && model.word(i).startsWith(p)) return true
        return personal?.withPrefix(p)?.isNotEmpty() == true
    }

    /** Hammasini saralamasdan eng yaxshi [k] tasini tanlaydi (bir harfli prefiksda minglab nomzod bo'ladi). */
    private fun topK(scores: Map<String, Double>, k: Int): List<String> {
        val best = ArrayList<Map.Entry<String, Double>>(k + 1)
        for (e in scores.entries) {
            var pos = best.size
            while (pos > 0 && better(e, best[pos - 1])) pos--
            if (pos < k) {
                best.add(pos, e)
                if (best.size > k) best.removeAt(best.size - 1)
            }
        }
        return best.map { it.key }
    }

    private fun better(a: Map.Entry<String, Double>, b: Map.Entry<String, Double>): Boolean =
        a.value > b.value || (a.value == b.value && a.key < b.key)

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    private fun applyCap(w: String, cap: CapStyle): String = when (cap) {
        CapStyle.NONE -> w
        CapStyle.ALL -> w.uppercase(Locale.ROOT)
        CapStyle.FIRST -> if (w.isEmpty()) w else w.substring(0, 1).uppercase(Locale.ROOT) + w.substring(1)
    }

    companion object {
        /** Eski yozuvdan almashtirilgan har harf uchun (log2 * 10 shkalasida ~0.8 marta kamroq uchrash). */
        private const val SUBSTITUTION_PENALTY = 8.0

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
