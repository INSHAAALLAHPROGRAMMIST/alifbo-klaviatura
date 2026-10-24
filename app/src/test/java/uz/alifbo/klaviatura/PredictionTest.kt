package uz.alifbo.klaviatura

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tekshiruvlar har qanday modelga (kichik boshlang'ich yoki katta korpusdan) mos yozilgan:
 * aniq tartib emas, faqat tabiiy talablar (prefiksga mos, tez-tez uchraydigan so'z ro'yxatda bor).
 */
class PredictionTest {

    private fun model(id: String): LanguageModel =
        LanguageModel.parse(File("src/main/assets/lm_$id.bin").readBytes())

    private fun suggest(lang: Lang, text: String, shift: Shift = Shift.OFF, personal: PersonalModel? = null): List<String> {
        val ctx = ContextParser.parse(text)
        return Suggester(model(lang.id), personal, lang)
            .suggest(ctx, Suggester.capStyle(ctx.prefix, shift), 3)
    }

    private fun cp(vararg codes: Int): String = String(codes, 0, codes.size)

    private val yaxshi = "yax${cp(0x15F)}i"

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
    fun ozbek_keyingiSoz_va_gapBoshi() {
        assertEquals(3, suggest(Lang.UZ, "Men ").size)
        assertEquals(3, suggest(Lang.UZ, "").size)
        assertEquals(3, suggest(Lang.UZ, "Salom. ").size)
    }

    @Test
    fun ozbek_prefiksBoyichaTugatish() {
        val s = suggest(Lang.UZ, "Bu yax")
        assertTrue("$s", s.isNotEmpty() && s.all { it.startsWith("yax") })
        assertTrue("$s", s.contains(yaxshi))
    }

    @Test
    fun bilinmaganKontekst_eng_kop_sozlarga_otadi() {
        val ctx = TextContext("", "zzzzqqqq")
        val s = Suggester(model("uz"), null, Lang.UZ).suggest(ctx, CapStyle.NONE, 3)
        assertEquals(3, s.size)
    }

    @Test
    fun katta_harf_uslubi() {
        val first = suggest(Lang.UZ, "Yax")
        assertTrue("$first", first.contains("Y" + yaxshi.substring(1)))
        val all = suggest(Lang.UZ, "YAX")
        assertTrue("$all", all.contains(yaxshi.uppercase(Locale.ROOT)))
        assertTrue(all.contains("YAX${cp(0x15E)}I"))
        // gap boshida Shift bosilgan: takliflar bosh harf bilan
        assertTrue(suggest(Lang.UZ, "", Shift.ONCE).all { it[0].isUpperCase() })
    }

    @Test
    fun ozbekKiril_taklif() {
        // "ях" -> "яхши"
        val s = suggest(Lang.UZC, "ях")
        assertTrue("$s", s.contains(cp(0x44F, 0x445, 0x448, 0x438)))
    }

    @Test
    fun ingliz_va_rus() {
        assertTrue(suggest(Lang.EN, "I don").contains("don't"))
        assertTrue(suggest(Lang.EN, "thi").contains("this"))
        val ru = suggest(Lang.RU, "Я хо")
        assertEquals(3, ru.size)
        assertTrue("$ru", ru.all { it.startsWith("хо") })
        assertEquals(3, suggest(Lang.RU, "Я ").size)
    }

    @Test
    fun shaxsiyOrganish_prefiks() {
        val p = PersonalModel()
        assertFalse(suggest(Lang.UZ, "bu zzqal", personal = p).contains("zzqalifbo"))
        repeat(3) { p.learn("bu", "zzqalifbo") }
        assertEquals("zzqalifbo", suggest(Lang.UZ, "bu zzqal", personal = p).first())
    }

    @Test
    fun shaxsiyOrganish_keyingiSoz() {
        val p = PersonalModel()
        repeat(2) { p.learn("zzqoldingi", "zzqkeyingi") }
        assertEquals("zzqkeyingi", suggest(Lang.UZ, "zzqoldingi ", personal = p).first())
    }

    @Test
    fun shaxsiyOrganish_umumiySozdanKuchli() {
        // Foydalanuvchi "yax" dan keyin o'zi ko'p yozgan so'zni (lug'atda yo'q) ko'rishi kerak.
        val p = PersonalModel()
        repeat(4) { p.learn(null, "yaxzzq") }
        assertTrue(suggest(Lang.UZ, "yax", personal = p).contains("yaxzzq"))
    }

    @Test
    fun birMartalikXato_taklifgaChiqmaydi() {
        val p = PersonalModel()
        p.learn("bu", "zzqxato")
        assertFalse(suggest(Lang.UZ, "zzqx", personal = p).contains("zzqxato"))
        assertFalse(suggest(Lang.UZ, "bu ", personal = p).contains("zzqxato"))
        p.learn("bu", "zzqxato")
        assertTrue(suggest(Lang.UZ, "zzqx", personal = p).contains("zzqxato"))
    }

    @Test
    fun lugatdagiSoz_birMartadaYoqKuchayadi() {
        // lug'atdagi so'z bir marta yozilganda ham shaxsiy kuchaytirish oladi
        val p = PersonalModel()
        p.learn("zzqoldin", yaxshi)
        assertTrue(suggest(Lang.UZ, "zzqoldin ", personal = p).contains(yaxshi))
    }

    @Test
    fun kontekst_tortTagachaOldingiSoz() {
        var c = ContextParser.parse("Men bugun uyga k")
        assertEquals("k", c.prefix)
        assertEquals(listOf(ContextParser.START, "Men", "bugun", "uyga"), c.history)

        c = ContextParser.parse("bir ikki uch tört besh o")
        assertEquals(listOf("ikki", "uch", "tört", "besh"), c.history)

        c = ContextParser.parse("Salom. Men ")
        assertEquals(listOf(ContextParser.START, "Men"), c.history)

        c = ContextParser.parse("12 bu ")
        assertEquals(listOf("bu"), c.history)
    }

    private fun fixture(): LanguageModel =
        LanguageModel.parse(File("src/test/resources/alm2_seed_uz.bin").readBytes())

    @Test
    fun alm2_uzunKontekstTaklifniOzgartiradi() {
        val m = fixture()
        assertTrue("maxOrder ${m.maxOrder}", m.maxOrder >= 3)
        val s = Suggester(m, null, Lang.UZ)
        // faqat "bugun" ga qarasak: bigram jadvali ("biz" ham bor)
        val one = s.suggest(TextContext("", "bugun", listOf("bugun")), CapStyle.NONE, 3)
        assertTrue("$one", one.contains("biz"))
        // "Men bugun" ga qarasak: faqat shu iboradan keyin kelgan so'zlar
        val two = s.suggest(ContextParser.parse("Men bugun "), CapStyle.NONE, 3)
        val allowed = setOf("bozorga", "i${cp(0x15F)}ga", "i${cp(0x15F)}ladim", "uyga")
        assertTrue("$two", two.size == 3 && two.all { it in allowed })
    }

    @Test
    fun shaxsiyOrganish_uzunKontekst() {
        val p = PersonalModel()
        repeat(2) { p.learn(listOf("zzqx1", "zzqumumiy"), "zzqaaa") }
        repeat(2) { p.learn(listOf("zzqx2", "zzqumumiy"), "zzqbbb") }
        val s = Suggester(fixture(), p, Lang.UZ)
        assertEquals("zzqaaa", s.suggest(TextContext("", "zzqumumiy", listOf("zzqx1", "zzqumumiy")), CapStyle.NONE, 3).first())
        assertEquals("zzqbbb", s.suggest(TextContext("", "zzqumumiy", listOf("zzqx2", "zzqumumiy")), CapStyle.NONE, 3).first())
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
