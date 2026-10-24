package uz.alifbo.klaviatura

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tayyor til modeli (tools/build-model.mjs bilan yasaladi): tartiblangan lug'at, har so'zning chastotasi
 * (log2 * 10), bitta oldingi so'zdan keyingi so'z jadvali va (ALM2'da) 2-4 oldingi so'zli kontekst jadvallari.
 */
class LanguageModel private constructor(
    private val words: Array<String>,
    private val uni: IntArray,
    private val rowStart: IntArray,
    private val nextIds: IntArray,
    private val nextQ: IntArray,
    private val higher: Array<HigherOrder?>,
) {
    val size: Int get() = words.size

    /** Eng uzun n-gram: 2 = faqat oldingi bitta so'z, 5 = oldingi to'rtta so'z. */
    val maxOrder: Int = higher.indices.lastOrNull { higher[it] != null } ?: 2

    fun word(i: Int): String = words[i]

    fun uni(i: Int): Int = uni[i]

    fun nextCount(i: Int): Int = rowStart[i + 1] - rowStart[i]

    fun nextId(i: Int, k: Int): Int = nextIds[rowStart[i] + k]

    fun nextQuant(i: Int, k: Int): Int = nextQ[rowStart[i] + k]

    /** [prev] dan keyin [id] kelish kuchi (0 = jadvalda yo'q). */
    fun bigramQuant(prev: Int, id: Int): Int {
        for (k in 0 until nextCount(prev)) if (nextId(prev, k) == id) return nextQuant(prev, k)
        return 0
    }

    /**
     * [ctx] (eskidan yangiga so'z o'rinlari, uzunligi 1..4) dan keyin keladigan so'zlarni [action] ga beradi.
     * Kontekst modelda bo'lmasa false.
     */
    fun next(ctx: IntArray, action: (id: Int, q: Int) -> Unit): Boolean {
        if (ctx.size == 1) {
            val p = ctx[0]
            val n = nextCount(p)
            for (k in 0 until n) action(nextId(p, k), nextQuant(p, k))
            return n > 0
        }
        val ho = higher.getOrNull(ctx.size + 1) ?: return false
        val row = ho.find(ctx)
        if (row < 0) return false
        for (k in ho.rowStart[row] until ho.rowStart[row + 1]) action(ho.ids[k], ho.qs[k])
        return true
    }

    fun indexOf(w: String): Int {
        var lo = 0
        var hi = words.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = words[mid].compareTo(w)
            if (c < 0) lo = mid + 1 else if (c > 0) hi = mid - 1 else return mid
        }
        return -1
    }

    /** [prefix] dan katta yoki teng birinchi so'z o'rni. */
    fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid].compareTo(prefix) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Eng ko'p uchraydigan so'zlar (gap boshi belgisisiz), kontekst topilmaganda ishlatiladi. */
    val topUnigrams: IntArray by lazy {
        (1 until words.size)
            .sortedWith(compareByDescending<Int> { uni[it] }.thenBy { words[it] })
            .take(12)
            .toIntArray()
    }

    /** n so'zli jadval: (n-1) so'zli kontekstlar id ketma-ketligi bo'yicha tartiblangan. */
    private class HigherOrder(
        val n: Int,
        val ctx: IntArray,
        val rowStart: IntArray,
        val ids: IntArray,
        val qs: IntArray,
    ) {
        val count: Int get() = rowStart.size - 1

        fun find(key: IntArray): Int {
            val w = n - 1
            var lo = 0
            var hi = count - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                var c = 0
                for (j in 0 until w) {
                    val a = ctx[mid * w + j]
                    val b = key[j]
                    if (a != b) {
                        c = if (a < b) -1 else 1
                        break
                    }
                }
                if (c < 0) lo = mid + 1 else if (c > 0) hi = mid - 1 else return mid
            }
            return -1
        }
    }

    private class IntList {
        var a = IntArray(1024)
        var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }

        fun toArray(): IntArray = a.copyOf(n)
    }

    companion object {
        const val START = "<s>"

        val EMPTY = LanguageModel(emptyArray(), IntArray(0), IntArray(1), IntArray(0), IntArray(0), arrayOfNulls(6))

        fun parse(bytes: ByteArray): LanguageModel {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magicBytes = ByteArray(4)
            bb.get(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)
            require(magic == "ALM1" || magic == "ALM2") { "ALM1/ALM2 emas" }
            val v = bb.int
            require(v in 0..65535) { "lug'at hajmi noto'g'ri: $v" }
            val words = Array(v) { "" }
            val uni = IntArray(v)
            for (i in 0 until v) {
                val len = bb.get().toInt() and 0xFF
                val b = ByteArray(len)
                bb.get(b)
                words[i] = String(b, Charsets.UTF_8)
                uni[i] = bb.get().toInt() and 0xFF
            }
            val rowStart = IntArray(v + 1)
            val ids = IntList()
            val qs = IntList()
            for (i in 0 until v) {
                rowStart[i] = ids.n
                val n = bb.get().toInt() and 0xFF
                for (k in 0 until n) {
                    ids.add(bb.short.toInt() and 0xFFFF)
                    qs.add(bb.get().toInt() and 0xFF)
                }
            }
            rowStart[v] = ids.n

            val higher = arrayOfNulls<HigherOrder>(6)
            if (magic == "ALM2") {
                val maxOrder = bb.get().toInt() and 0xFF
                require(maxOrder in 2..5) { "tartib noto'g'ri: $maxOrder" }
                for (n in 3..maxOrder) {
                    val c = bb.int
                    val w = n - 1
                    val ctx = IntArray(c * w)
                    val rs = IntArray(c + 1)
                    val hIds = IntList()
                    val hQs = IntList()
                    for (i in 0 until c) {
                        for (j in 0 until w) ctx[i * w + j] = bb.short.toInt() and 0xFFFF
                        rs[i] = hIds.n
                        val m = bb.get().toInt() and 0xFF
                        for (k in 0 until m) {
                            hIds.add(bb.short.toInt() and 0xFFFF)
                            hQs.add(bb.get().toInt() and 0xFF)
                        }
                    }
                    rs[c] = hIds.n
                    if (c > 0) higher[n] = HigherOrder(n, ctx, rs, hIds.toArray(), hQs.toArray())
                }
            }
            return LanguageModel(words, uni, rowStart, ids.toArray(), qs.toArray(), higher)
        }
    }
}
