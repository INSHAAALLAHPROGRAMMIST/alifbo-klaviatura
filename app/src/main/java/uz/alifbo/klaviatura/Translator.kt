package uz.alifbo.klaviatura

import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Rasmiy tarjima xizmatlari (foydalanuvchining o'z API kaliti bilan), faqat Tor orqali ([TorNet]).
 * Google Cloud Translation (oyiga 500 ming belgigacha bepul) yoki Microsoft Azure Translator
 * (oyiga 2 mln belgigacha bepul). Xizmatga faqat tarjima qilinadigan matn va kalit boradi.
 */
object Translator {
    const val GOOGLE = 0
    const val AZURE = 1

    private const val GOOGLE_HOST = "translation.googleapis.com"
    private const val AZURE_HOST = "api.cognitive.microsofttranslator.com"
    private const val JSON = "application/json; charset=UTF-8"

    /** Bitta so'rovda eng ko'pi shuncha belgi (bepul kvotani tejash uchun ham). */
    const val MAX_CHARS = 3000

    /** Tarjima tillari: (kod, qisqa nom). "uzc" — o'zbekchaga tarjima, keyin kirillga o'giriladi. */
    val targets: List<Pair<String, String>> = listOf(
        "uz" to "UZ", "en" to "EN", "ru" to "RU", "uzc" to "ЎЗ", "tr" to "TR",
        "de" to "DE", "fr" to "FR", "ko" to "KO", "ar" to "AR", "zh" to "ZH",
    )

    fun shortName(code: String): String = targets.firstOrNull { it.first == code }?.second ?: code.uppercase()

    /** Shifrlangan kalit nomi ([Secrets] da). */
    fun keyName(engine: Int): String = if (engine == AZURE) "azure_key" else "google_key"

    /** Foydalanuvchiga ko'rsatiladigan xato. */
    class Failure(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

    /**
     * Yuboriladigan manba: (til kodi yoki null = xizmat o'zi aniqlaydi, yuboriladigan matn).
     * Yangi alifbodagi o'zbekcha eski imloga, o'zbek kirill lotinga o'giriladi.
     */
    fun prepareSource(text: String, keyboard: Lang, fromClipboard: Boolean): Pair<String?, String> {
        if (UzScript.hasUzbekCyrillic(text) ||
            (keyboard == Lang.UZC && !fromClipboard && UzScript.isMostlyCyrillic(text))
        ) {
            return "uz" to UzScript.cyrillicToOldLatin(text)
        }
        if (UzScript.looksNewUzbek(text)) return "uz" to UzScript.newToOld(text)
        if (fromClipboard) return null to text
        return when (keyboard) {
            Lang.UZ -> "uz" to UzScript.newToOld(text)
            Lang.EN -> "en" to text
            Lang.RU -> "ru" to text
            Lang.UZC -> "uz" to UzScript.cyrillicToOldLatin(text)
        }
    }

    /** Xizmatdan kelgan natija foydalanuvchi tanlagan yozuvda: o'zbekcha — yangi alifbo yoki kirill. */
    fun finish(result: String, target: String): String = when (target) {
        "uz" -> UzScript.oldToNew(result)
        "uzc" -> UzScript.latinToCyrillic(UzScript.oldToNew(result))
        else -> result
    }

    internal fun serviceTarget(target: String, engine: Int): String = when (target) {
        "uzc" -> "uz"
        "zh" -> if (engine == AZURE) "zh-Hans" else "zh-CN"
        else -> target
    }

    // ---------------------------------------------------------------- Google Cloud Translation v2

    internal fun googleBody(text: String, source: String?, target: String): String {
        val o = JSONObject()
        o.put("q", JSONArray().put(text))
        o.put("target", target)
        if (source != null) o.put("source", source)
        o.put("format", "text")
        return o.toString()
    }

    internal fun googleParse(code: Int, body: String): String {
        if (code != 200) throw httpFailure(code, errorMessage(body))
        val arr = JSONObject(body).getJSONObject("data").getJSONArray("translations")
        return (0 until arr.length()).joinToString("\n") { arr.getJSONObject(it).getString("translatedText") }
    }

    // ---------------------------------------------------------------- Azure Translator v3

    internal fun azurePath(source: String?, target: String): String =
        "/translate?api-version=3.0&textType=plain&to=$target" + (if (source != null) "&from=$source" else "")

    internal fun azureBody(text: String): String = JSONArray().put(JSONObject().put("Text", text)).toString()

    internal fun azureParse(code: Int, body: String): String {
        if (code != 200) throw httpFailure(code, errorMessage(body))
        return JSONArray(body).getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text")
    }

    // ---------------------------------------------------------------- xatolar

    private fun errorMessage(body: String): String? = try {
        val t = body.trim()
        if (t.startsWith("{")) JSONObject(t).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } else null
    } catch (e: JSONException) {
        null
    }

    internal fun httpFailure(code: Int, detail: String?): Failure = Failure(
        when (code) {
            400 -> "Xizmat söroqni qabul qilmadi" + (detail?.let { ": ${it.take(120)}" } ?: "")
            401, 403 -> "Kalit notöğri yoki bu xizmatga ruxsati yöq"
            429 -> "Bepul kvota tugadi yoki söroqlar juda tez"
            in 500..599 -> "Xizmat vaqtinça işlamayapti ($code)"
            else -> "Tarjima xatosi ($code)"
        },
    )

    /** Tarjima (fon oqimida). Tarmoqqa faqat [TorNet] orqali chiqadi; Tor bo'lmasa — [Failure]. */
    fun translate(engine: Int, key: String, region: String, text: String, source: String?, target: String): String {
        if (key.isBlank()) throw Failure("Avval Söz+ sozlamalarida tarjima kalitini kiriting")
        val t = serviceTarget(target, engine)
        val clipped = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        try {
            return if (engine == AZURE) {
                val headers = ArrayList<Pair<String, String>>()
                headers.add("Ocp-Apim-Subscription-Key" to key.trim())
                if (region.isNotBlank()) headers.add("Ocp-Apim-Subscription-Region" to region.trim())
                val r = TorNet.post(AZURE_HOST, azurePath(source, t), JSON, azureBody(clipped).toByteArray(Charsets.UTF_8), headers)
                azureParse(r.code, r.body)
            } else {
                val headers = listOf("X-Goog-Api-Key" to key.trim())
                val r = TorNet.post(GOOGLE_HOST, "/language/translate/v2", JSON, googleBody(clipped, source, t).toByteArray(Charsets.UTF_8), headers)
                googleParse(r.code, r.body)
            }
        } catch (e: TorNet.TorDown) {
            throw Failure("Orbot (Tor) işlamayapti", e)
        } catch (e: TorNet.TorRefused) {
            throw Failure("Tor tarmoği xizmatga ulana olmadi, qayta urinib köring", e)
        } catch (e: TorNet.Timeout) {
            throw Failure("Tor orqali javob kelmadi (vaqt tugadi), qayta urinib köring", e)
        } catch (e: TorNet.TlsFailure) {
            throw Failure("Xavfsiz ulaniş (TLS) tasdiqlanmadi — tarjima yuborilmadi", e)
        } catch (e: JSONException) {
            throw Failure("Xizmat javobini tuşunib bölmadi", e)
        } catch (e: IOException) {
            throw Failure("Tarmoq xatosi, qayta urinib köring", e)
        } catch (e: IllegalArgumentException) {
            throw Failure("Kalit yoki mintaqa notöğri yozilgan", e)
        }
    }
}
