package uz.alifbo.klaviatura

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionTest {

    private fun model(id: String): LanguageModel =
        LanguageModel.parse(File("src/main/assets/lm_$id.bin").readBytes())

    private fun suggest(lang: Lang, text: String, shift: Shift = Shift.OFF, personal: PersonalModel? = null): List<String> {
        val ctx = ContextParser.parse(text)
        return Suggester(model(lang.id), personal, lang)
            .suggest(ctx, Suggester.capStyle(ctx.prefix, shift), 3)
    }

    private fun cp(vararg codes: Int): String = String(codes, 0, codes.size)

    @Test
    fun kontekst_oddiyHollar() {
        var c = ContextParser.parse("Men bugun u")
        assertEquals("u", c.prefix)
        assertEquals("bugun", c.prev)

        c = ContextParser.parse("Salom. ")
        assertEquals("", c.prefix)
        assertEquals(ContextParser.START, c.prev)

        c = ContextParser.parse("")
        assertEquals("", c.prefix)
        assertEquals(ContextParser.START, c.prev)

        c = ContextParser.parse("men, bu")
        assertEquals("bu", c.prefix)
        assertEquals("men", c.prev)

        c = ContextParser.parse("Men ")
        assertEquals("", c.prefix)
        assertEquals("Men", c.prev)

        c = ContextParser.parse("\"Salom")
        assertEquals("Salom", c.prefix)
        assertEquals(ContextParser.START, c.prev)

        c = ContextParser.parse("12 bu")
        assertEquals("bu", c.prefix)
        assertEquals(null, c.prev)
    }

    @Test
    fun kontekst_apostrofVaTutuq() {
        val tutuq = Layouts.TUTUQ
        assertEquals("ma${tutuq}no", ContextParser.parse("ma${tutuq}no").prefix)
        assertEquals("don't", ContextParser.parse("I don't").prefix)
        assertEquals("don’t", ContextParser.parse("don’t").prefix)
    }

    @Test
    fun norm_ozbekApostroflariBirlashadi() {
        val tutuq = Layouts.TUTUQ
        assertEquals("ma${tutuq}no", Norm.word("Ma'no", Lang.UZ))
        assertEquals("ma${tutuq}no", Norm.word("ma’no", Lang.UZ))
        assertEquals("don't", Norm.word("Don’t", Lang.EN))
    }

    @Test
    fun model_tartiblangan_va_gapBoshiBelgisiBor() {
        for (id in listOf("uz", "uzc", "en", "ru")) {
            val m = model(id)
            assertTrue("$id: bo'sh", m.size > 50)
            assertEquals("<s>", m.word(0))
            for (i in 1 until m.size) {
                assertTrue("$id: tartib buzilgan ${m.word(i - 1)} / ${m.word(i)}", m.word(i - 1) < m.word(i))
            }
            assertEquals(0, m.indexOf("<s>"))
            assertEquals(-1, m.indexOf("zzzzzzzz"))
        }
    }

    @Test
    fun ozbek_keyingiSoz() {
        val s = suggest(Lang.UZ, "Men ")
        assertEquals(3, s.size)
        assertTrue("sizga va bugun kutilgan edi: $s", s.containsAll(listOf("sizga", "bugun")))
    }

    @Test
    fun ozbek_prefiksBoyichaTugatish() {
        assertEquals("yax${cp(0x15F)}i", suggest(Lang.UZ, "Bu yax").first())
        assertEquals("yax${cp(0x15F)}i", suggest(Lang.UZ, "yax").first())
    }

    @Test
    fun ozbek_gapBoshi() {
        assertTrue(suggest(Lang.UZ, "").contains("men"))
        assertTrue(suggest(Lang.UZ, "Salom. ").contains("men"))
    }

    @Test
    fun bilinmaganKontekst_eng_kop_sozlarga_otadi() {
        val ctx = TextContext("", "zzzzqqqq")
        val s = Suggester(model("uz"), null, Lang.UZ).suggest(ctx, CapStyle.NONE, 3)
        assertEquals(3, s.size)
    }

    @Test
    fun katta_harf_uslubi() {
        assertEquals("Yax${cp(0x15F)}i", suggest(Lang.UZ, "Yax").first())
        assertEquals("YAX${cp(0x15E)}I", suggest(Lang.UZ, "YAX").first())
        // gap boshida Shift bosilgan: takliflar bosh harf bilan
        assertTrue(suggest(Lang.UZ, "", Shift.ONCE).all { it[0].isUpperCase() })
    }

    @Test
    fun ozbekKiril_taklif() {
        // я х ш и
        val first = suggest(Lang.UZC, "ях").first()
        assertEquals(cp(0x44F, 0x445, 0x448, 0x438), first)
    }

    @Test
    fun ingliz_va_rus() {
        assertTrue(suggest(Lang.EN, "I don").contains("don't"))
        assertEquals("this", suggest(Lang.EN, "thi").first())
        val ru = suggest(Lang.RU, "Я хо")
        assertTrue("$ru", ru.all { it.startsWith("хо") })
        // "я" dan keyin "хочу" kelgani uchun u oldinga chiqadi
        assertTrue("$ru", ru.contains("хочу"))
        assertEquals(3, suggest(Lang.RU, "Я ").size)
    }

    @Test
    fun shaxsiyOrganish_tavsiyaniOzgartiradi() {
        val p = PersonalModel()
        assertFalse(suggest(Lang.UZ, "bu kitobc", personal = p).contains("kitobcha"))
        repeat(3) { p.learn("bu", "kitobcha") }
        assertEquals("kitobcha", suggest(Lang.UZ, "bu kitobc", personal = p).first())
        // keyingi so'z sifatida ham chiqadi
        assertTrue(suggest(Lang.UZ, "bu ", personal = p).contains("kitobcha"))
    }

    @Test
    fun shaxsiyModel_saqlashVaOqish() {
        val p = PersonalModel()
        p.learn("<s>", "salom")
        p.learn("salom", "dunyo")
        p.learn("salom", "dunyo")
        val q = PersonalModel.parse(p.serialize())
        assertEquals(1, q.uniCount("salom"))
        assertEquals(2, q.uniCount("dunyo"))
        assertEquals(2, q.next("salom")?.get("dunyo"))
        assertFalse(q.dirty)
        assertNotEquals(0, p.pending)
        p.clear()
        assertEquals(0, p.wordCount)
    }
}
