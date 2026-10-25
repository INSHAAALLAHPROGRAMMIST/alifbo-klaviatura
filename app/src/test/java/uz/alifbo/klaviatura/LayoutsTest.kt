package uz.alifbo.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {

    private fun cp(vararg codes: Int): Set<String> = codes.map { String(Character.toChars(it)) }.toSet()

    /** Faqat harf qatorlari (pastki qator va raqam qatorisiz) dagi birlamchi belgilar. */
    private fun primaryLetters(l: KbLayout): List<String> =
        l.rows.dropLast(1)
            .flatten()
            .filter { it.kind == Kind.CHAR && it.label.any { ch -> ch.isLetter() } }
            .map { it.label }

    private fun allLetters(l: KbLayout): Set<String> =
        l.rows.dropLast(1).flatten().filter { it.kind == Kind.CHAR }
            .flatMap { listOf(it.label) + it.popup }
            .filter { s -> s.length == 1 && s[0].isLetter() }
            .toSet()

    @Test
    fun yangiOzbekAlifbosi_qonundagiBilanTengMi() {
        // O'RQ-1184 (21.10.2026): A B D E F G Ğ H I J K L M N O Ö P Q R S Ş Ç T U V X Y Z (28 harf)
        val expected = cp(
            0x61, 0x62, 0x64, 0x65, 0x66, 0x67, 0x11F, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E,
            0x6F, 0xF6, 0x70, 0x71, 0x72, 0x73, 0x15F, 0xE7, 0x74, 0x75, 0x76, 0x78, 0x79, 0x7A,
        )
        assertEquals(28, expected.size)
        val keys = Layouts.letters(Lang.UZ).rows.dropLast(1).flatten().filter { it.kind == Kind.CHAR }
        val visible = keys.map { it.label }.filter { it != Layouts.TUTUQ }
        assertEquals("takrorlangan harf bor: $visible", visible.size, visible.toSet().size)
        // ş ğ ö — s, g, o tugmalari ichida yashirin; qolgan 25 harf ko'rinadi
        val hidden = keys.map { it.hint }.filter { it.isNotEmpty() }
        assertEquals(cp(0x15F, 0x11F, 0xF6), hidden.toSet())
        assertEquals(25, visible.size)
        assertEquals(expected, (visible + hidden).toSet())
        assertEquals(String(Character.toChars(0x15F)), keys.first { it.label == "s" }.hint)
        assertEquals(String(Character.toChars(0x11F)), keys.first { it.label == "g" }.hint)
        assertEquals(String(Character.toChars(0xF6)), keys.first { it.label == "o" }.hint)
        // yashirin harf uzoq bosish va yuqoriga surishda birinchi
        for (k in keys) if (k.hint.isNotEmpty()) assertEquals(k.hint, k.popup.first())
    }

    @Test
    fun ozbekLotin_tutuqBelgisiHarflarQatorida() {
        val l = Layouts.letters(Lang.UZ)
        assertTrue(l.rows.first().any { it.kind == Kind.CHAR && it.label == String(Character.toChars(0x02BC)) })
        assertFalse(l.rows.last().any { it.label == Layouts.TUTUQ })
    }

    @Test
    fun ruslarning33HarfiToliq() {
        val expected = (0x430..0x44F).map { String(Character.toChars(it)) }.toSet() + String(Character.toChars(0x451))
        assertEquals(33, expected.size)
        assertEquals(expected, allLetters(Layouts.letters(Lang.RU)))
    }

    @Test
    fun ozbekKiril_ruscha33_va_4ta_maxsusHarf() {
        val ru = (0x430..0x44F).map { String(Character.toChars(it)) }.toSet() + String(Character.toChars(0x451))
        // ў қ ғ ҳ
        val expected = ru + cp(0x45E, 0x49B, 0x493, 0x4B3)
        assertEquals(expected, allLetters(Layouts.letters(Lang.UZC)))
    }

    @Test
    fun english_a_dan_z_gacha() {
        val expected = ('a'..'z').map { it.toString() }.toSet()
        val have = primaryLetters(Layouts.letters(Lang.EN)).toSet()
        assertEquals(expected, have)
    }

    @Test
    fun qatorlarningOgirliklariTeng() {
        for (lang in Lang.entries) {
            for (nr in listOf(false, true)) {
                val l = Layouts.letters(lang, nr)
                for (row in l.rows) {
                    val sum = row.sumOf { it.weight.toDouble() }
                    assertEquals("$lang numberRow=$nr", l.total.toDouble(), sum, 0.02)
                }
            }
        }
        for (p in listOf(Page.SYM1, Page.SYM2)) {
            val l = Layouts.symbols(p)
            for (row in l.rows) assertEquals(l.total.toDouble(), row.sumOf { it.weight.toDouble() }, 0.02)
        }
    }

    @Test
    fun barchaMaxsusBelgilarTugmadaBor() {
        val ascii = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        assertEquals(32, ascii.length)
        val labels = (
            Layouts.symbols(Page.SYM1).rows.flatten() +
                Layouts.symbols(Page.SYM2).rows.flatten() +
                Layouts.letters(Lang.EN).rows.last()
            ).filter { it.kind == Kind.CHAR }.map { it.label }.toSet()
        for (ch in ascii) assertTrue("'$ch' belgisi yo'q", ch.toString() in labels)
    }

    @Test
    fun katta_harf_togri() {
        assertEquals(String(Character.toChars(0xD6)), String(Character.toChars(0xF6)).upperUz())
        assertEquals(String(Character.toChars(0x11E)), String(Character.toChars(0x11F)).upperUz())
        assertEquals(String(Character.toChars(0x15E)), String(Character.toChars(0x15F)).upperUz())
        assertEquals(String(Character.toChars(0xC7)), String(Character.toChars(0xE7)).upperUz())
        // turkcha İ emas, oddiy I
        assertEquals("I", "i".upperUz())
    }

    @Test
    fun raqamlarUzoqBosishdaYuqoriQatorda() {
        val top = Layouts.letters(Lang.UZ).rows.first().filter { it.kind == Kind.CHAR }
        assertEquals("1", top.first().popup.first())
        assertEquals("0", top.last().popup.first())
    }
}
