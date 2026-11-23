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
    fun googleXatoSababiKorinadi() {
        val billing = """{"error":{"code":403,"message":"This API method requires billing to be enabled.","status":"PERMISSION_DENIED",
            "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"BILLING_DISABLED"}]}}"""
        val b = Translator.httpFailure(403, billing)
        assertTrue(b.userMessage, b.userMessage.contains("billing"))
        assertTrue(b.userMessage.contains("BILLING_DISABLED"))
        assertFalse(b.retry)

        val disabled = """{"error":{"code":403,"message":"Cloud Translation API has not been used in project 1 before or it is disabled.",
            "status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED"}]}}"""
        assertTrue(Translator.httpFailure(403, disabled).userMessage.contains("yoqilmagan"))

        val android = """{"error":{"code":403,"message":"Requests from this Android client application <empty> are blocked.",
            "status":"PERMISSION_DENIED","details":[{"reason":"API_KEY_ANDROID_APP_BLOCKED"}]}}"""
        assertTrue(Translator.httpFailure(403, android).userMessage.contains("Application restrictions"))
    }

    @Test
    fun vaqtinchaXatoQaytaUrinadi() {
        // JSON emas javob (HTML, g'alati trafik sahifasi va h.k.) 403/429/503 — qayta urinish foydali
        val html = "<html><body>We're sorry... but your computer or network may be sending automated queries.</body></html>"
        val f = Translator.httpFailure(403, html)
        assertTrue(f.retry)
        assertTrue(Translator.httpFailure(503, "Service Unavailable").retry)
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
        assertEquals("en", Translator.serviceTarget("en", Translator.AZURE))
        assertEquals("zh-CN", Translator.serviceTarget("zh", Translator.GOOGLE))
        assertEquals("zh-Hans", Translator.serviceTarget("zh", Translator.AZURE))
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
    fun rejimlar() {
        // standart rejim ham Google xizmati; kalit nomlari alohida
        assertEquals(Translator.GOOGLE, Translator.service(Translator.DEFAULT))
        assertEquals(Translator.GOOGLE, Translator.service(Translator.GOOGLE))
        assertEquals(Translator.AZURE, Translator.service(Translator.AZURE))
        assertEquals("azure_key", Translator.keyName(Translator.AZURE))
        assertEquals("google_key", Translator.keyName(Translator.GOOGLE))
        // standart rejim qotirilgan kalitni oladi (CI sirida bo'lsa bor, bo'lmasa null); o'z kalitlari bo'sh
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(Secrets.defaultKey(), Translator.keyFor(ctx, Translator.DEFAULT))
        assertNull(Translator.keyFor(ctx, Translator.AZURE))
        assertTrue(Net.ALLOWED_HOSTS.containsAll(listOf("translation.googleapis.com", "api.cognitive.microsofttranslator.com")))
    }
}
