package uz.alifbo.klaviatura

import android.text.InputType

/**
 * Tinish belgilari atrofidagi probel va gap boshidagi bosh harf qoidalari (sof mantiq, testlanadi).
 *
 *  - so'zdan keyin . , ! ? ; : … qo'yilsa, ortidan probel o'zi qo'yiladi ("yumshoq" probel);
 *  - raqamdan keyin (3.14, 1,5, 10:30), havola/email ichida va "h.k." kabi qisqartmalarda qo'yilmaydi;
 *  - gap tugagach (. ! ? dan keyin probel), yangi qator va maydon boshida — bosh harf.
 */
object Punct {
    /** So'zdan keyin qo'yilsa, ortidan probel keladigan belgilar. */
    const val SPACE_AFTER = ".,!?;:…"

    /** So'zdan keyin probel bosilib, darhol shular bosilsa, belgi probel oldiga ko'chadi ("so'z ," → "so'z, "). */
    const val SWAPS = ".,!?…"

    /** Yopuvchi belgilar: yumshoq probeldan keyin bosilsa, probel ularning ortiga o'tadi ("«Salom.»"). */
    const val CLOSERS = ")]}»”’"

    /** Gap boshida bosh harfga xalaqit bermaydigan ochuvchi belgilar: `. «Salom`. */
    private const val OPENERS = "([{«“‘\"'"

    /** Raqamdan keyin ham probel qo'yiladigan belgilar (. , : esa 3.14, 1,5, 10:30 da kerak emas). */
    private const val AFTER_DIGIT = "!?;"

    private val URL_SCHEMES = setOf("http", "https", "ftp", "tel", "mailto")

    private fun isBlank(c: Char) = c == ' ' || c == '\t' || c == ' '

    /** Kursordan keyingi belgi shu bo'lsa, avtomatik probel qo'yilmaydi (matn o'rtasida tahrirlash). */
    fun blocksSpace(next: Char): Boolean =
        next.isWhitespace() || next in SPACE_AFTER || next in CLOSERS || next == '"'

    /** Matndagi qo'shtirnoqlar toq sonli — demak, keyingi `"` yopuvchi. */
    fun insideQuote(before: CharSequence): Boolean = before.count { it == '"' } % 2 == 1

    /**
     * [before] (kursorgacha matn, [p] hali qo'yilmagan) ortidan [p] qo'yilsa, keyin probel kerakmi.
     * Oldingi tinish zanjiri o'tkazib yuboriladi: "Nima?!", "Hmm...", "(ha).".
     */
    fun wantsSpaceAfter(before: CharSequence, p: Char): Boolean {
        if (p !in SPACE_AFTER) return false
        var i = before.length
        while (i > 0 && (before[i - 1] in SPACE_AFTER || before[i - 1] in CLOSERS || before[i - 1] == '"')) i--
        if (i == 0) return false
        val c = before[i - 1]
        if (c.isDigit()) return p in AFTER_DIGIT
        if (!ContextParser.isWordChar(c)) return false
        var s = i
        while (s > 0 && !before[s - 1].isWhitespace()) s--
        val token = before.subSequence(s, i).toString()
        // email, havola, fayl yo'li
        if ('@' in token || '/' in token || token.startsWith("www", ignoreCase = true)) return false
        if (p == ':' && token.lowercase() in URL_SCHEMES) return false
        if (p == '.') {
            // "h.k.", "t.b.", "e.g." — bitta kichik lotin harfidan keyin nuqta: qisqartma
            var w = i
            while (w > 0 && before[w - 1].isLetter()) w--
            if (i - w == 1 && before[w] in 'a'..'z') return false
        }
        return true
    }

    /**
     * Kursor gap boshidami: maydon boshi, yangi qator yoki ". ! ?" va probeldan keyin.
     * "..." va "h.k." dan keyin emas (android.text.TextUtils.getCapsMode qoidasi bilan bir xil).
     */
    fun isSentenceStart(before: CharSequence): Boolean {
        var i = before.length
        while (i > 0 && before[i - 1] in OPENERS) i--
        var j = i
        while (j > 0 && isBlank(before[j - 1])) j--
        if (j == 0 || before[j - 1] == '\n') return true
        if (i == j) return false
        while (j > 0 && (before[j - 1] in CLOSERS || before[j - 1] == '"' || before[j - 1] == '\'')) j--
        if (j == 0) return false
        val c = before[j - 1]
        if (c == '!' || c == '?') return true
        if (c != '.') return false
        var k = j - 2
        while (k >= 0) {
            val d = before[k]
            if (d == '.') return false
            if (!d.isLetter()) break
            k--
        }
        return true
    }

    private fun sensitive(inputType: Int): Boolean {
        val v = inputType and InputType.TYPE_MASK_VARIATION
        return v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            v == InputType.TYPE_TEXT_VARIATION_URI ||
            v == InputType.TYPE_TEXT_VARIATION_FILTER
    }

    private fun isText(inputType: Int) = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT

    /** Oddiy matn maydoni (parol, email, havola, qidiruv filtri, "taklif kerak emas" emas): avtoprobel shu yerda. */
    fun isProse(inputType: Int): Boolean =
        isText(inputType) && !sensitive(inputType) && inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0

    const val CAP_NONE = 0

    /** Gap boshidagi bosh harfni o'zimiz hisoblaymiz ([isSentenceStart]). */
    const val CAP_SENTENCES = 1

    /** Har so'z / har harf bosh: maydonning o'zi (getCursorCapsMode) aytadi. */
    const val CAP_SYSTEM = 2

    /**
     * Maydon bosh harf so'ragan bo'lsa (CAP_SENTENCES) — gap boshida; bayroq qo'yilmagan, lekin ko'p qatorli
     * yoki xabar maydoni bo'lsa ham (ko'p ilova bayroqni unutadi). Bir qatorli bayroqsiz maydonlar (login,
     * qidiruv) kichik harfda qoladi.
     */
    fun capMode(inputType: Int): Int {
        if (!isText(inputType) || sensitive(inputType)) return CAP_NONE
        if (inputType and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0) return CAP_SYSTEM
        if (inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0) return CAP_SENTENCES
        val v = inputType and InputType.TYPE_MASK_VARIATION
        val message = v == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE ||
            v == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE ||
            v == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT
        if (message || inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return CAP_SENTENCES
        return CAP_NONE
    }
}

/**
 * Probel holati (Gboard'dagidek):
 *  - so'zdan keyin tinish belgisi — ortidan yumshoq probel; keyin probel bosilsa, ikkinchisi qo'shilmaydi;
 *    tinish yoki yopuvchi belgi bosilsa, probel uning ortiga o'tadi ("Nima?!", "«Salom.»");
 *  - so'z ortidan probel bosilib, darhol . , ! ? … bosilsa, belgi probel oldiga ko'chadi;
 *  - taklif tanlanganda qo'yilgan probeldan keyin probel bosilsa, ikkinchisi qo'shilmaydi;
 *  - probel tez ikki marta bosilsa — ". ";
 *  - Enter bosilsa, biz qo'ygan oxirgi probel olib tashlanadi.
 * Kursor boshqa joyga o'tsa yoki matn o'zgarsa (oxirgi [SNAP] belgi mos kelmasa), holat unutiladi.
 */
class Spacing {

    /** Kursor atrofidagi matn: ilovada InputConnection, testda soxta maydon. */
    interface Field {
        fun before(n: Int): CharSequence?
        fun after(n: Int): CharSequence?
        fun delete(n: Int)
        fun commit(text: String)
    }

    enum class Soft { NONE, WEAK, AUTO_WORD, AUTO_PUNCT }

    enum class SpaceResult { NORMAL, SWALLOWED, PERIOD }

    var autoSpace = true
    var doublePeriod = true

    var state = Soft.NONE
        private set
    private var snap: String? = null
    private var lastSpaceAt = Long.MIN_VALUE / 2

    fun reset() {
        state = Soft.NONE
        snap = null
    }

    private fun mark(f: Field, s: Soft) {
        val b = f.before(SNAP)?.toString()
        if (b == null || !b.endsWith(" ")) {
            reset()
            return
        }
        snap = b
        state = s
    }

    /** Holat hali kuchdami: kursor biz qoldirgan joyda, oldida o'sha probel. */
    private fun live(f: Field): Boolean {
        if (state == Soft.NONE) return false
        val b = f.before(SNAP)?.toString()
        if (b == null || b != snap) {
            reset()
            return false
        }
        return true
    }

    /**
     * Tinish yoki yopuvchi belgi [p] qo'yilishidan oldin chaqiriladi: kerak bo'lsa oldidagi probelni oladi.
     * [MOVED_WORD] — so'z ortidagi probel olindi (so'z allaqachon tuzatilgan/o'rganilgan),
     * [MOVED_PUNCT] — tinish ortidagi yumshoq probel olindi, [MOVED_NONE] — hech narsa.
     */
    fun beforePunct(f: Field, p: Char): Int {
        if (!live(f)) return MOVED_NONE
        val st = state
        val move = when (st) {
            Soft.AUTO_PUNCT -> p in Punct.SPACE_AFTER || p in Punct.CLOSERS ||
                (p == '"' && Punct.insideQuote(f.before(QUOTE_LOOKBACK) ?: ""))
            Soft.WEAK, Soft.AUTO_WORD -> p in Punct.SWAPS
            Soft.NONE -> false
        }
        reset()
        if (!move) return MOVED_NONE
        f.delete(1)
        return if (st == Soft.AUTO_PUNCT) MOVED_PUNCT else MOVED_WORD
    }

    /**
     * [p] ni qo'yadi; so'zdan keyin bo'lsa (yoki yopuvchi belgi yumshoq probel o'rnini olgan bo'lsa) ortidan
     * probel ham. [allowSpace] — maydon bunga mos (oddiy matn, belgilangan qism yo'q). Qo'yilgan matnni qaytaradi.
     */
    fun commitPunct(f: Field, p: Char, allowSpace: Boolean, moved: Int): String {
        var space = false
        if (autoSpace && allowSpace) {
            val next = f.after(1)
            if (next.isNullOrEmpty() || !Punct.blocksSpace(next[0])) {
                space = if (p in Punct.SPACE_AFTER) {
                    Punct.wantsSpaceAfter(f.before(LOOKBACK) ?: "", p)
                } else {
                    moved == MOVED_PUNCT && (p in Punct.CLOSERS || p == '"')
                }
            }
        }
        val text = if (space) "$p " else p.toString()
        f.commit(text)
        if (space) mark(f, Soft.AUTO_PUNCT) else reset()
        return text
    }

    /** Probel bosildi. [SpaceResult.NORMAL] bo'lsa, IME odatdagidek probel qo'yib, [afterSpace] ni chaqiradi. */
    fun onSpace(f: Field, now: Long): SpaceResult {
        if (!live(f)) return SpaceResult.NORMAL
        return when (state) {
            Soft.AUTO_PUNCT -> {
                reset()
                SpaceResult.SWALLOWED
            }
            Soft.AUTO_WORD -> {
                // taklif ortidagi probel endi "foydalanuvchiniki": yana tez bosilsa — nuqta
                state = Soft.WEAK
                lastSpaceAt = now
                SpaceResult.SWALLOWED
            }
            Soft.WEAK -> {
                if (doublePeriod && now - lastSpaceAt in 0..DOUBLE_SPACE_MS) {
                    f.delete(1)
                    f.commit(". ")
                    mark(f, Soft.AUTO_PUNCT)
                    SpaceResult.PERIOD
                } else {
                    reset()
                    SpaceResult.NORMAL
                }
            }
            Soft.NONE -> SpaceResult.NORMAL
        }
    }

    /** Odatdagi probel qo'yilgach: so'z (raqam, yopuvchi belgi) ortidagi probel "kuchsiz" deb eslab qolinadi. */
    fun afterSpace(f: Field, now: Long) {
        val b = f.before(SNAP)?.toString()
        if (b != null && b.length >= 2 && b.endsWith(" ")) {
            val c = b[b.length - 2]
            if (ContextParser.isWordChar(c) || c.isDigit() || c in Punct.CLOSERS || c == '"') {
                snap = b
                state = Soft.WEAK
                lastSpaceAt = now
                return
            }
        }
        reset()
    }

    /** Taklif "so'z " bo'lib qo'yilgach. */
    fun afterPick(f: Field) = mark(f, Soft.AUTO_WORD)

    /** Avtomatik tuzatish qaytarilib, "so'z. " qayta qo'yilgach. */
    fun afterRestore(f: Field) = mark(f, Soft.AUTO_PUNCT)

    /** Enter'dan oldin: biz qo'ygan oxirgi probel olinadi. true — olindi (so'zni qayta tuzatmaslik kerak). */
    fun beforeEnter(f: Field): Boolean {
        val drop = live(f) && (state == Soft.AUTO_PUNCT || state == Soft.AUTO_WORD)
        reset()
        if (drop) f.delete(1)
        return drop
    }

    companion object {
        const val MOVED_NONE = 0
        const val MOVED_WORD = 1
        const val MOVED_PUNCT = 2

        /** Kursor joyini tanish uchun eslab qolinadigan oxirgi belgilar soni. */
        const val SNAP = 24
        const val LOOKBACK = 80
        const val QUOTE_LOOKBACK = 400
        const val DOUBLE_SPACE_MS = 1000L
    }
}
