package uz.alifbo.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardStoreTest {

    @Test
    fun yangiNusxaTepada_eskilariSaqlanadi() {
        val s = ClipboardStore()
        s.add("birinchi", 1000)
        s.add("ikkinchi", 2000)
        assertEquals(listOf("ikkinchi", "birinchi"), s.sorted().map { it.text })
        assertEquals("ikkinchi", s.latest()?.text)
    }

    @Test
    fun takrorNusxaTepagaKotariladi() {
        val s = ClipboardStore()
        s.add("a", 1000)
        s.add("b", 2000)
        s.add("a", 3000)
        assertEquals(listOf("a", "b"), s.sorted().map { it.text })
        assertEquals(2, s.size)
    }

    @Test
    fun boshMatnQoshilmaydi() {
        val s = ClipboardStore()
        assertFalse(s.add("   ", 1000))
        assertEquals(0, s.size)
    }

    @Test
    fun qadalganlarTepada_vaOchmaydi() {
        val s = ClipboardStore(maxUnpinned = 2)
        s.add("muhim", 1000)
        s.togglePin("muhim")
        s.add("x", 2000)
        s.add("y", 3000)
        s.add("z", 4000)
        // qadalmaganlardan eng yangi 2 tasi qoladi, qadalgani ham turadi
        assertEquals(listOf("muhim", "z", "y"), s.sorted().map { it.text })
        s.clearUnpinned()
        assertEquals(listOf("muhim"), s.sorted().map { it.text })
    }

    @Test
    fun saqlashVaOqish_yangiQatorVaTablarBilan() {
        val s = ClipboardStore()
        s.add("satr 1\nsatr 2\tustun", 1000)
        s.add("Özbek ş ğ ö ç ʼ", 2000)
        s.togglePin("Özbek ş ğ ö ç ʼ")
        val t = ClipboardStore.parse(s.serialize())
        assertEquals(s.sorted(), t.sorted())
        assertFalse(t.dirty)
        assertTrue(s.dirty)
    }

    @Test
    fun ochirish() {
        val s = ClipboardStore()
        s.add("a", 1000)
        s.add("b", 2000)
        s.remove("a")
        assertEquals(listOf("b"), s.sorted().map { it.text })
        s.clearAll()
        assertEquals(0, s.size)
    }
}
