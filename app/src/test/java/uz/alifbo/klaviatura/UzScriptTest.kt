package uz.alifbo.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UzScriptTest {

    @Test
    fun yangidanEskiga_tarjimaXizmatiUchun() {
        assertEquals("Oʻzbekiston — goʻzal oʻlka", UzScript.newToOld("Özbekiston — gözal ölka"))
        assertEquals("Shahar, choy, togʻ", UzScript.newToOld("Şahar, çoy, toğ"))
        assertEquals("TOSHKENT SHAHRI", UzScript.newToOld("TOŞKENT ŞAHRI"))
        assertEquals("maʼno", UzScript.newToOld("maʼno"))
    }

    @Test
    fun eskidanYangiga_natijaUchun() {
        assertEquals("Özbekiston — gözal ölka", UzScript.oldToNew("Oʻzbekiston — goʻzal oʻlka"))
        assertEquals("Özbekiston", UzScript.oldToNew("O'zbekiston"))
        assertEquals("Şahar çoy toğ boğda", UzScript.oldToNew("Shahar choy tog' bog'da"))
        assertEquals("maʼno", UzScript.oldToNew("ma'no"))
        assertEquals("ŞAHAR", UzScript.oldToNew("SHAHAR"))
    }

    @Test
    fun soZOxiridagiTog_qoShtirnoqEmas() {
        assertEquals("Baland toğ.", UzScript.oldToNew("Baland tog'."))
        // "o" dan keyingi yopuvchi qo'shtirnoq ö ga aylanmaydi
        assertEquals("‘kino’ yaxşi", UzScript.oldToNew("‘kino’ yaxshi"))
    }

    @Test
    fun kirilldanLotinga() {
        assertEquals("Oʻzbekiston", UzScript.cyrillicToOldLatin("Ўзбекистон"))
        assertEquals("yer yuzi", UzScript.cyrillicToOldLatin("ер юзи"))
        assertEquals("sheʼr", UzScript.cyrillicToOldLatin("шеър"))
        assertEquals("gʻisht", UzScript.cyrillicToOldLatin("ғишт"))
    }

    @Test
    fun lotindanKirillga() {
        assertEquals("Ўзбекистон", UzScript.latinToCyrillic("Özbekiston"))
        assertEquals("яхши ғишт", UzScript.latinToCyrillic("yaxşi ğişt"))
        assertEquals("эртага", UzScript.latinToCyrillic("ertaga"))
    }

    @Test
    fun yozuvniAniqlash() {
        assertTrue(UzScript.looksNewUzbek("Bugun havo yaxşi"))
        assertFalse(UzScript.looksNewUzbek("Bugün hava çok güzel"))
        assertFalse(UzScript.looksNewUzbek("Hello world"))
        assertTrue(UzScript.hasUzbekCyrillic("Қалайсиз"))
        assertFalse(UzScript.hasUzbekCyrillic("Как дела"))
        assertTrue(UzScript.isMostlyCyrillic("Как дела"))
    }
}
