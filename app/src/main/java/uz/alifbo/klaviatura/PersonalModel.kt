package uz.alifbo.klaviatura

import java.util.TreeMap

/**
 * Foydalanuvchining o'zi yozgan so'zlardan o'rganilgan jadval. Faqat telefonda, ilovaning shaxsiy
 * papkasida saqlanadi; tarmoqqa chiqmaydi. Har til uchun alohida nusxa.
 */
class PersonalModel {
    private val uni = TreeMap<String, Int>()
    private val big = HashMap<String, HashMap<String, Int>>()

    var dirty: Boolean = false
        private set

    /** Oxirgi saqlashdan beri o'zgarishlar soni. */
    var pending: Int = 0
        private set

    val wordCount: Int get() = uni.size

    fun learn(prev: String?, word: String) {
        uni[word] = (uni[word] ?: 0) + 1
        if (prev != null) {
            val row = big.getOrPut(prev) { HashMap() }
            row[word] = (row[word] ?: 0) + 1
        }
        dirty = true
        pending++
    }

    fun uniCount(word: String): Int = uni[word] ?: 0

    fun next(prev: String?): Map<String, Int>? = if (prev == null) null else big[prev]

    fun withPrefix(prefix: String): Map<String, Int> = uni.subMap(prefix, prefix + '￿')

    fun clear() {
        uni.clear()
        big.clear()
        dirty = true
        pending++
    }

    fun markSaved() {
        dirty = false
        pending = 0
    }

    /** Juda katta bo'lib ketmasligi uchun bir marta uchragan yozuvlarni tashlaydi. */
    private fun prune() {
        if (uni.size > MAX_WORDS) {
            val it = uni.entries.iterator()
            while (it.hasNext() && uni.size > KEEP_WORDS) if (it.next().value <= 1) it.remove()
        }
        var total = big.values.sumOf { it.size }
        if (total > MAX_PAIRS) {
            for (row in big.values) {
                val it = row.entries.iterator()
                while (it.hasNext() && total > KEEP_PAIRS) if (it.next().value <= 1) {
                    it.remove()
                    total--
                }
            }
            big.values.removeAll { it.isEmpty() }
        }
    }

    fun serialize(): String {
        prune()
        val sb = StringBuilder()
        for ((w, c) in uni) sb.append("U\t").append(w).append('\t').append(c).append('\n')
        for ((p, row) in big) for ((w, c) in row) {
            sb.append("B\t").append(p).append('\t').append(w).append('\t').append(c).append('\n')
        }
        return sb.toString()
    }

    companion object {
        private const val MAX_WORDS = 20000
        private const val KEEP_WORDS = 15000
        private const val MAX_PAIRS = 40000
        private const val KEEP_PAIRS = 30000

        fun parse(text: String): PersonalModel {
            val m = PersonalModel()
            for (line in text.lineSequence()) {
                val f = line.split('\t')
                when {
                    f.size == 3 && f[0] == "U" -> f[2].toIntOrNull()?.let { m.uni[f[1]] = it }
                    f.size == 4 && f[0] == "B" -> f[3].toIntOrNull()?.let {
                        m.big.getOrPut(f[1]) { HashMap() }[f[2]] = it
                    }
                }
            }
            m.markSaved()
            return m
        }
    }
}
