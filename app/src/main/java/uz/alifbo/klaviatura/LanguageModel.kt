package uz.alifbo.klaviatura

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tayyor til modeli (ALM1, tools/build-model.mjs bilan yasaladi): tartiblangan lug'at,
 * har so'zning chastotasi (log2 * 10) va keyingi so'z jadvali (eng ko'pi bilan 8 ta).
 */
class LanguageModel private constructor(
    private val words: Array<String>,
    private val uni: IntArray,
    private val rowStart: IntArray,
    private val nextIds: IntArray,
    private val nextQ: IntArray,
) {
    val size: Int get() = words.size

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

        val EMPTY = LanguageModel(emptyArray(), IntArray(0), IntArray(1), IntArray(0), IntArray(0))

        fun parse(bytes: ByteArray): LanguageModel {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            bb.get(magic)
            require(String(magic, Charsets.US_ASCII) == "ALM1") { "ALM1 emas" }
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
            return LanguageModel(words, uni, rowStart, ids.toArray(), qs.toArray())
        }
    }
}
