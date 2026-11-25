package uz.alifbo.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Klaviatura içidagi matn maydoni: kursor, qöyiş, yozish qoidalari va maydonga qaytariş. */
class TextBufferTest {

    private fun buf(text: String = "", at: Int = text.length, max: Int = 100) = TextBuffer(max).apply { set(text, at) }

    private fun TextBuffer.keys(s: String) {
        for (c in s) if (c == ' ') space() else type(c.toString(), autoSpace = true)
    }

    @Test
    fun yozishOxirida() {
        val b = buf()
        b.keys("Salom, dunyo.")
        // tinişdan keyin probel özi qöyiladi, qöldan bosilgan probel ikkinçisini qöşmaydi
        assertEquals("Salom, dunyo. ", b.text)
        assertEquals(b.length, b.cursor)
    }

    @Test
    fun kursorOrtada() {
        val b = buf("Salom dunyo")
        b.moveTo(5)
        b.keys(",")
        // keyingi probel bor: ikkinçisi qöşilmaydi
        assertEquals("Salom, dunyo", b.text)
        assertEquals(6, b.cursor)
        b.delete()
        assertEquals("Salom dunyo", b.text)
        b.type("m", true)
        assertEquals("Salomm dunyo", b.text)
        // oxiriga qaytib davom etiladi
        b.moveTo(999)
        b.keys("!")
        assertEquals("Salomm dunyo! ", b.text)
    }

    @Test
    fun kursorKoçganidaAvtoProbelOlibTaşlanmaydi() {
        val b = buf()
        b.keys("Ha.")
        b.moveTo(0)
        b.moveTo(b.length)
        b.keys(".")
        // kursor köçgan: endi bu oddiy probel
        assertEquals("Ha. .", b.text)
    }

    @Test
    fun qöyişKursorJoyigaVaChegara() {
        val b = buf("ab", at = 1, max = 5)
        assertEquals(3, b.paste("12345"))
        assertEquals("a123b", b.text)
        assertEquals(4, b.cursor)
        assertEquals(0, b.paste("x"))
        b.type("y", true)
        assertEquals("a123b", b.text)
    }

    @Test
    fun emojiBölinmaydi() {
        val smile = String(Character.toChars(0x1F600))
        val b = buf("a" + smile + "b")
        b.moveTo(2)
        assertEquals(1, b.cursor)
        b.moveTo(3)
        b.delete()
        assertEquals("ab", b.text)
        // chegarada ham yarim belgi qolmaydi
        val small = buf(max = 2)
        assertEquals(1, small.paste("a" + smile))
        assertEquals("a", small.text)
    }

    @Test
    fun taklifSözOrnigaQöyiladi() {
        val b = buf("Bugun hav juda", at = 9)
        b.pick("havo")
        assertEquals("Bugun havo juda", b.text)
        assertEquals(11, b.cursor)
        // söz içida turib tanlansa, butun söz almaşadi
        val m = buf("yaxsi kun", at = 3)
        m.pick("yaxşi")
        assertEquals("yaxşi kun", m.text)
        // keyingi söz taklifi mavjud sözni öçirmaydi
        val n = buf("bir kun", at = 4)
        n.pick("yaxşi")
        assertEquals("bir yaxşi kun", n.text)
    }

    @Test
    fun yangiQator() {
        val b = buf()
        b.keys("Salom.")
        b.newline()
        assertEquals("Salom.\n", b.text)
    }

    @Test
    fun faqatÖzgarganQism() {
        assertNull(TextChange.between("abc", "abc"))
        assertEquals(TextChange(6, 6, "katta "), TextChange.between("Salom dunyo", "Salom katta dunyo"))
        assertEquals(TextChange(3, 4, ""), TextChange.between("abccd", "abcd"))
        val c = TextChange.between("aaa", "aaaa")!!
        assertEquals("aaaa", "aaa".replaceRange(c.start, c.end, c.insert))
        val d = TextChange.between("bir ikki uç", "")!!
        assertEquals(TextChange(0, 11, ""), d)
    }

    @Test
    fun özgarişSurrogatniBölmaydi() {
        val x = String(Character.toChars(0x1F600))
        val y = String(Character.toChars(0x1F601))
        val c = TextChange.between("a" + x + "b", "a" + y + "b")!!
        assertEquals(1, c.start)
        assertEquals(3, c.end)
        assertEquals(y, c.insert)
        assertFalse(c.insert.isEmpty())
        assertTrue(c.insert.length == 2)
    }
}
