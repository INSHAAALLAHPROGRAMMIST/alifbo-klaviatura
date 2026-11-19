package uz.alifbo.klaviatura

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacingTest {

    /** Kursor oxirida turgan soxta maydon ([tail] — kursordan keyingi matn). */
    private class Fake(initial: String = "", val tail: String = "") : Spacing.Field {
        val sb = StringBuilder(initial)
        override fun before(n: Int): CharSequence = sb.substring(maxOf(0, sb.length - n))
        override fun after(n: Int): CharSequence = tail.take(n)
        override fun delete(n: Int) {
            sb.setLength(maxOf(0, sb.length - n))
        }

        override fun commit(text: String) {
            sb.append(text)
        }

        override fun toString(): String = sb.toString()
    }

    /**
     * AlifboIme'dagi tartibda yozadi (avtomatik tuzatishsiz). [gap] — tugmalar orasidagi vaqt (ms):
     * ikki probel tez bosilsa nuqta bo'lmasligi uchun sukut bo'yicha katta. '\n' — Enter, '|' — taklif
     * tanlandi deb oldingi so'z ortidan probel qo'yiladi.
     */
    private fun type(
        keys: String,
        sp: Spacing = Spacing(),
        f: Fake = Fake(),
        gap: Long = 5000,
        allow: Boolean = true,
    ): String {
        var t = 0L
        for (k in keys) {
            t += gap
            when {
                k == ' ' -> if (sp.onSpace(f, t) == Spacing.SpaceResult.NORMAL) {
                    f.commit(" ")
                    sp.afterSpace(f, t)
                }
                k == '\n' -> {
                    sp.beforeEnter(f)
                    f.commit("\n")
                }
                k == '|' -> {
                    f.commit(" ")
                    sp.afterPick(f)
                }
                k in Punct.SPACE_AFTER || k in Punct.CLOSERS || k == '"' -> {
                    val moved = sp.beforePunct(f, k)
                    sp.commitPunct(f, k, allow, moved)
                }
                else -> {
                    sp.reset()
                    f.commit(k.toString())
                }
            }
        }
        return f.toString()
    }

    @Test
    fun tinishdanKeyinProbel() {
        assertEquals("Salom. Dunyo", type("Salom.Dunyo"))
        assertEquals("Salom, dunyo", type("Salom,dunyo"))
        assertEquals("Nima? Ha! Mana: kitob; daftar", type("Nima?Ha!Mana:kitob;daftar"))
        assertEquals("Salom, ", type("Salom,"))
        // kirill va o'zbekcha harflar ham so'z
        assertEquals("Ўзбек, рус", type("Ўзбек,рус"))
        assertEquals("yaxşi. Ölçov", type("yaxşi.Ölçov"))
    }

    @Test
    fun odatdagiProbelIkkinchisiniQoshmaydi() {
        assertEquals("Salom. Dunyo", type("Salom. Dunyo"))
        assertEquals("Salom, dunyo", type("Salom, dunyo"))
    }

    @Test
    fun raqamlardaProbelYoq() {
        assertEquals("3.14", type("3.14"))
        assertEquals("1,5 kg", type("1,5 kg"))
        assertEquals("soat 10:30", type("soat 10:30"))
        assertEquals("12.05.2026", type("12.05.2026"))
        // undov va so'roqdan keyin raqam bo'lsa ham probel kerak
        assertEquals("Soat 5? Ha", type("Soat 5?Ha"))
    }

    @Test
    fun havolaEmailQisqartma() {
        assertEquals("ali@mail.ru", type("ali@mail.ru"))
        assertEquals("https://zamon.uz/kitob", type("https://zamon.uz/kitob"))
        assertEquals("www.zamon.uz", type("www.zamon.uz"))
        assertEquals("va h.k.", type("va h.k."))
        assertEquals("e.g.", type("e.g."))
        // bosh harfli bir harf — ism-sharif bosh harfi
        assertEquals("A. Qodiriy", type("A.Qodiriy"))
    }

    @Test
    fun tinishZanjiri() {
        assertEquals("Nima?! ", type("Nima?!"))
        assertEquals("Hmm... ", type("Hmm..."))
        assertEquals("Hmm… ", type("Hmm…"))
        assertEquals("Rahmat!!! Xayr", type("Rahmat!!!Xayr"))
    }

    @Test
    fun yopuvchiBelgilar() {
        assertEquals("«Salom.» Keyin", type("«Salom.»Keyin"))
        assertEquals("(ha.) Keyin", type("(ha.)Keyin"))
        assertEquals("U \"Salom.\" dedi", type("U \"Salom.\" dedi"))
        assertEquals("U \"Salom.\" dedi", type("U \"Salom.\"dedi"))
        // tinishsiz yopuvchi belgidan keyin probel qo'yilmaydi
        assertEquals("(ha)", type("(ha)"))
        // ochuvchi qo'shtirnoq oldidagi probel turadi
        assertEquals("U dedi: \"Ha", type("U dedi:\"Ha"))
    }

    @Test
    fun smayliklar() {
        assertEquals("Salom :)", type("Salom :)"))
        assertEquals("Rahmat:) ", type("Rahmat:)"))
    }

    @Test
    fun probelOldigaKochadi() {
        assertEquals("Salom, qalay", type("Salom ,qalay"))
        assertEquals("Ha. Yo'q", type("Ha .Yo'q"))
        // ikki nuqta va nuqtali vergul ko'chmaydi (":)" kabi smayliklar uchun)
        assertEquals("Salom :", type("Salom :"))
    }

    @Test
    fun taklifTanlangach() {
        assertEquals("Salom ", type("Salom|"))
        assertEquals("Salom dunyo", type("Salom| dunyo"))
        assertEquals("Salom. Dunyo", type("Salom|.Dunyo"))
        assertEquals("Salom, dunyo", type("Salom|,dunyo"))
    }

    @Test
    fun ikkiProbelNuqta() {
        assertEquals("Salom. Dunyo", type("Salom  Dunyo", gap = 300))
        // sekin bosilsa — oddiy ikki probel
        assertEquals("Salom  Dunyo", type("Salom  Dunyo", gap = 3000))
        // taklifdan keyin tez ikki probel ham nuqta
        assertEquals("Salom. ", type("Salom|  ", gap = 300))
        val off = Spacing().apply { doublePeriod = false }
        assertEquals("Salom  Dunyo", type("Salom  Dunyo", sp = off, gap = 300))
    }

    @Test
    fun enterOxirgiProbelniOladi() {
        assertEquals("Rahmat.\nXayr", type("Rahmat.\nXayr"))
        assertEquals("Salom\n", type("Salom|\n"))
        // foydalanuvchi o'zi bosgan probel qoladi
        assertEquals("Salom \n", type("Salom \n"))
    }

    @Test
    fun kursorKochsaHolatUnutiladi() {
        val sp = Spacing()
        val f = Fake()
        type("Salom.", sp, f)
        assertEquals("Salom. ", f.toString())
        // ilova matnni o'zgartirdi (yoki kursor boshqa joyga o'tdi)
        f.sb.append("x ")
        type(" ", sp, f)
        assertEquals("Salom. x  ", f.toString())
    }

    @Test
    fun matnOrtasidaProbelQoshilmaydi() {
        assertEquals("Salom,", type("Salom,", f = Fake(tail = " dunyo")))
        assertEquals("Salom.", type("Salom.", f = Fake(tail = ")")))
        assertEquals("Salom, ", type("Salom,", f = Fake(tail = "dunyo")))
    }

    @Test
    fun ochirilganYokiMosEmasMaydon() {
        val off = Spacing().apply { autoSpace = false }
        assertEquals("Salom.Dunyo", type("Salom.Dunyo", sp = off))
        assertEquals("Salom.Dunyo", type("Salom.Dunyo", allow = false))
    }

    @Test
    fun gapBoshi() {
        assertTrue(Punct.isSentenceStart(""))
        assertTrue(Punct.isSentenceStart("Salom. "))
        assertTrue(Punct.isSentenceStart("Salom! "))
        assertTrue(Punct.isSentenceStart("Nima? "))
        assertTrue(Punct.isSentenceStart("Salom.\n"))
        assertTrue(Punct.isSentenceStart("Salom\n"))
        assertTrue(Punct.isSentenceStart("Salom.\n  "))
        assertTrue(Punct.isSentenceStart("Salom.» "))
        assertTrue(Punct.isSentenceStart("Salom. «"))
        assertTrue(Punct.isSentenceStart("A. "))
        assertTrue(Punct.isSentenceStart("Ҳа. "))
        assertFalse(Punct.isSentenceStart("Salom."))
        assertFalse(Punct.isSentenceStart("Salom, "))
        assertFalse(Punct.isSentenceStart("Salom "))
        assertFalse(Punct.isSentenceStart("va h.k. "))
        assertFalse(Punct.isSentenceStart("Hmm... "))
        assertFalse(Punct.isSentenceStart("U dedi: «"))
    }

    @Test
    fun inglizchaI() {
        assertEquals("I", Punct.englishI("i"))
        assertEquals("I'm", Punct.englishI("i'm"))
        assertEquals("I’ll", Punct.englishI("i’ll"))
        assertEquals("I've", Punct.englishI("i've"))
        assertEquals(null, Punct.englishI("I"))
        assertEquals(null, Punct.englishI("it"))
        assertEquals(null, Punct.englishI("is"))
        assertEquals(null, Punct.englishI("i'x"))
    }

    @Test
    fun maydonTurlari() {
        val text = InputType.TYPE_CLASS_TEXT
        assertEquals(Punct.CAP_SENTENCES, Punct.capMode(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
        // bayroq unutilgan ko'p qatorli maydon — baribir gap boshida bosh harf
        assertEquals(Punct.CAP_SENTENCES, Punct.capMode(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertEquals(Punct.CAP_SENTENCES, Punct.capMode(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
        assertEquals(Punct.CAP_SYSTEM, Punct.capMode(text or InputType.TYPE_TEXT_FLAG_CAP_WORDS))
        // bir qatorli bayroqsiz maydon (login, qidiruv) — kichik harf
        assertEquals(Punct.CAP_NONE, Punct.capMode(text))
        assertEquals(Punct.CAP_NONE, Punct.capMode(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals(
            Punct.CAP_NONE,
            Punct.capMode(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES),
        )
        assertEquals(Punct.CAP_NONE, Punct.capMode(InputType.TYPE_CLASS_NUMBER))

        assertTrue(Punct.isProse(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
        assertFalse(Punct.isProse(text or InputType.TYPE_TEXT_VARIATION_URI))
        assertFalse(Punct.isProse(text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
        assertFalse(Punct.isProse(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(Punct.isProse(InputType.TYPE_CLASS_PHONE))
    }
}
