package uz.alifbo.klaviatura

/**
 * Klaviatura içidagi matn maydonlari (tarjima qatori, töliq ekran muharriri) uçun matn va kursor.
 * Yozish qoidalari ilovaga yozişdagidek: tinişdan keyin probel özi qöyiladi, ikkinçisi qöşilmaydi.
 * Hamma amal kursor turgan joyda bajariladi. Sof mantiq (testlanadi).
 */
class TextBuffer(private val max: Int) {
    private val sb = StringBuilder()

    var cursor = 0
        private set

    /** Kursor oldidagi probelni klaviatura özi qöygan (tinişdan yoki taklifdan keyin). */
    private var soft = false

    val length: Int get() = sb.length
    val text: String get() = sb.toString()

    fun isEmpty(): Boolean = sb.isEmpty()

    /** Kursorgacha bölgan matn. */
    fun before(): String = sb.substring(0, cursor)

    fun set(value: String, at: Int = Int.MAX_VALUE) {
        sb.setLength(0)
        sb.append(clip(value, max))
        cursor = snap(at)
        soft = false
    }

    fun clear() = set("")

    fun moveTo(position: Int) {
        val n = snap(position)
        if (n != cursor) {
            cursor = n
            soft = false
        }
    }

    /** Buferdan qöyiş: kursor joyiga, siğganiça. Qöyilgan belgilar sonini qaytaradi. */
    fun paste(value: String): Int {
        soft = false
        val part = clip(value, max - sb.length)
        put(part)
        return part.length
    }

    /** Tugmadan yozilgan belgi. Ortidan probel avtomatik qöyilgan bölsa — true. */
    fun type(value: String, autoSpace: Boolean): Boolean {
        val p = value.singleOrNull()
        var moved = false
        if (p != null && soft && spaceBefore() &&
            (p in Punct.SPACE_AFTER || p in Punct.CLOSERS || (p == '"' && Punct.insideQuote(before())))
        ) {
            cut(1)
            moved = true
        }
        soft = false
        if (sb.length + value.length > max) return false
        val space = p != null && autoSpace && sb.length + 2 <= max &&
            (cursor == sb.length || !Punct.blocksSpace(sb[cursor])) &&
            if (p in Punct.SPACE_AFTER) Punct.wantsSpaceAfter(before(), p) else moved && (p in Punct.CLOSERS || p == '"')
        put(value)
        if (space) {
            put(" ")
            soft = true
        }
        return space
    }

    fun space() {
        // klaviatura qöygan probeldan keyin probel — ikkinçisi qöşilmaydi
        if (!(soft && spaceBefore()) && sb.length < max) put(" ")
        soft = false
    }

    fun newline() {
        if (soft && spaceBefore()) cut(1)
        soft = false
        if (sb.length < max) put("\n")
    }

    fun delete() {
        soft = false
        if (cursor == 0) return
        cut(Character.charCount(sb.codePointBefore(cursor)))
    }

    /** Taklif qilingan söz: yozilayotgan söz (kursor içida bölsa — butun söz) örniga qöyiladi. */
    fun pick(word: String) {
        val prefix = ContextParser.parse(before().takeLast(80)).prefix
        if (prefix.isNotEmpty()) {
            var end = cursor
            while (end < sb.length && ContextParser.isWordChar(sb[end])) end++
            sb.delete(cursor, end)
            cut(prefix.length)
        }
        put(clip(word, max - sb.length))
        soft = false
        if (cursor < sb.length && sb[cursor] == ' ') {
            cursor++
        } else if (sb.length < max) {
            put(" ")
            soft = true
        }
    }

    private fun spaceBefore(): Boolean = cursor > 0 && sb[cursor - 1] == ' '

    private fun put(value: String) {
        sb.insert(cursor, value)
        cursor += value.length
    }

    private fun cut(count: Int) {
        sb.delete(cursor - count, cursor)
        cursor -= count
    }

    /** Kursor emoji (surrogat juftlik) o'rtasiga tuşmaydi. */
    private fun snap(position: Int): Int {
        var n = position.coerceIn(0, sb.length)
        if (n > 0 && n < sb.length && Character.isLowSurrogate(sb[n]) && Character.isHighSurrogate(sb[n - 1])) n--
        return n
    }

    private fun clip(value: String, room: Int): String {
        if (value.length <= room) return value
        var n = room.coerceAtLeast(0)
        if (n > 0 && Character.isHighSurrogate(value[n - 1])) n--
        return value.substring(0, n)
    }
}

/** Eski matnning [start, end) qismi [insert] bilan almaştirilsa, yangi matn hosil böladi. */
data class TextChange(val start: Int, val end: Int, val insert: String) {
    companion object {
        /**
         * Faqat özgargan örta qismni topadi (bir xil boşi va oxiri tegilmaydi — ilovadagi formatlaş,
         * eslatma va maxsus emojilar saqlanib qoladi). Özgariş bölmasa — null.
         */
        fun between(old: String, new: String): TextChange? {
            if (old == new) return null
            val limit = minOf(old.length, new.length)
            var p = 0
            while (p < limit && old[p] == new[p]) p++
            if (p > 0 && Character.isHighSurrogate(old[p - 1])) p--
            var s = 0
            while (s < limit - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
            if (s > 0 && Character.isLowSurrogate(old[old.length - s])) s--
            return TextChange(p, old.length - s, new.substring(p, new.length - s))
        }
    }
}
