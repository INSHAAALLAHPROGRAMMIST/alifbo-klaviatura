package uz.alifbo.klaviatura

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** So'rov tanasi va javoblar (xizmatlarning hujjatlaridagi shakl bo'yicha), tarmoqsiz. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslatorTest {

    @Test
    fun googleSorovi() {
        val o = JSONObject(Translator.googleBody("Salom \"do'st\"", "uz", "en"))
        assertEquals("Salom \"do'st\"", o.getJSONArray("q").getString(0))
        assertEquals("en", o.getString("target"))
        assertEquals("uz", o.getString("source"))
        assertEquals("text", o.getString("format"))
        assertFalse(JSONObject(Translator.googleBody("x", null, "ru")).has("source"))
    }

    @Test
    fun googleJavobi() {
        val body = """{"data":{"translations":[{"translatedText":"Hello, world!","detectedSourceLanguage":"uz"}]}}"""
        assertEquals("Hello, world!", Translator.googleParse(200, body))
        try {
            Translator.googleParse(403, """{"error":{"code":403,"message":"API key not valid","status":"PERMISSION_DENIED"}}""")
            fail()
        } catch (e: Translator.Failure) {
            assertTrue(e.userMessage.contains("Kalit"))
        }
    }

    @Test
    fun azureSorovVaJavob() {
        assertEquals("/translate?api-version=3.0&textType=plain&to=en&from=uz", Translator.azurePath("uz", "en"))
        assertEquals("/translate?api-version=3.0&textType=plain&to=ru", Translator.azurePath(null, "ru"))
        assertEquals("Salom", JSONArray(Translator.azureBody("Salom")).getJSONObject(0).getString("Text"))
        val body = """[{"detectedLanguage":{"language":"en","score":1.0},"translations":[{"text":"Salom, dunyo!","to":"uz"}]}]"""
        assertEquals("Salom, dunyo!", Translator.azureParse(200, body))
        try {
            Translator.azureParse(429, """{"error":{"code":429001,"message":"quota"}}""")
            fail()
        } catch (e: Translator.Failure) {
            assertTrue(e.userMessage.contains("kvota"))
        }
    }

    @Test
    fun ozbekchaManbaVaNatija() {
        // yangi alifbo -> xizmatga eski imloda
        assertEquals("uz" to "Bugun havo juda yaxshi", Translator.prepareSource("Bugun havo juda yaxşi", Lang.UZ, false))
        // kirill -> lotin
        assertEquals("uz" to "Qalaysiz", Translator.prepareSource("Қалайсиз", Lang.UZC, false))
        // buferdan kelgan inglizcha matn: tilni xizmat o'zi aniqlaydi
        val (src, text) = Translator.prepareSource("Good morning", Lang.UZ, true)
        assertNull(src)
        assertEquals("Good morning", text)
        // natija yangi alifboda yoki kirillda
        assertEquals("Özbekiston gözal", Translator.finish("Oʻzbekiston goʻzal", "uz"))
        assertEquals("Ўзбекистон", Translator.finish("Oʻzbekiston", "uzc"))
        assertEquals("Hello", Translator.finish("Hello", "en"))
        assertEquals("uz", Translator.serviceTarget("uzc", Translator.GOOGLE))
        assertEquals("zh-Hans", Translator.serviceTarget("zh", Translator.AZURE))
    }
}
