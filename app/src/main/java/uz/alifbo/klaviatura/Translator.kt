package uz.alifbo.klaviatura

import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Rasmiy tarjima xizmatlari, to'g'ridan-to'g'ri ([Net]): Google Cloud Translation (oyiga 500 ming
 * belgigacha bepul) yoki Microsoft Azure Translator (oyiga 2 mln belgigacha bepul).
 * 2026-11: Tor olib tashlandi (Google chiqish tugunlarini bloklagani uchun) — xizmat so'rov
 * manzilingizni (IP) ko'radi. Xizmatga faqat tarjima qilinadigan matn va kalit boradi.
 *
 * Tarjima rejimi ([DEFAULT], [GOOGLE], [AZURE]): "Standart" — ilovaga yig'ishda qotirilgan kalit
 * (Google), qolgan ikkisi — foydalanuvchining o'z kaliti.
 */
object Translator {
    const val GOOGLE = 0
    const val AZURE = 1

    /** Standart rejim: ilovaga CI'da qotirilgan Google kaliti ([Secrets.defaultKey]). */
    const val DEFAULT = 2

    private const val GOOGLE_HOST = "translation.googleapis.com"
    private const val AZURE_HOST = "api.cognitive.microsofttranslator.com"
    private const val JSON = "application/json; charset=UTF-8"

    /** Shifrlangan kalit nomi ([Secrets] da); [DEFAULT] uchun foydalanuvchi kaliti yo'q. */
    fun keyName(mode: Int): String = if (mode == AZURE) "azure_key" else "google_key"

    /** Tarjima xizmati: [AZURE] yoki [GOOGLE] ([DEFAULT] ham Google). */
    fun service(mode: Int): Int = if (mode == AZURE) AZURE else GOOGLE

    /** Rejim uchun ishlatiladigan kalit: standart — qotirilgan, aks holda foydalanuvchi saqlagani. */
    fun keyFor(ctx: android.content.Context, mode: Int): String? =
        if (mode == DEFAULT) Secrets.defaultKey() else Secrets.get(ctx, keyName(mode))

    /** Bitta so'rovda eng ko'pi shuncha belgi (bepul kvotani tejash uchun ham). */
    const val MAX_CHARS = 3000

    /** Tarjima tillari: (kod, qisqa nom). "uzc" — o'zbekchaga tarjima, keyin kirillga o'giriladi. */
    val targets: List<Pair<String, String>> = listOf(
        "uz" to "UZ", "en" to "EN", "ru" to "RU", "uzc" to "ЎЗ", "tr" to "TR",
        "de" to "DE", "fr" to "FR", "ko" to "KO", "ar" to "AR", "zh" to "ZH",
    )

    fun shortName(code: String): String = targets.firstOrNull { it.first == code }?.second ?: code.uppercase()

    /** Tilning o'z nomi (panelda tanişga qulay). */
    fun displayName(code: String): String = when (code) {
        "uz" -> "Özbek"
        "uzc" -> "Ўзбек"
        "en" -> "English"
        "ru" -> "Русский"
        "tr" -> "Türkçe"
        "de" -> "Deutsch"
        "fr" -> "Français"
        "ko" -> "한국어"
        "ar" -> "العربية"
        "zh" -> "中文"
        else -> code.uppercase()
    }

    /** Foydalanuvchiga ko'rsatiladigan xato. */
    class Failure(
        val userMessage: String,
        cause: Throwable? = null,
        /** Vaqtincha xizmat xatosi: qayta urinish foydali. */
        val retry: Boolean = false,
    ) : Exception(userMessage, cause)

    /** Vaqtincha (5xx) xatoda eng ko'pi shuncha urinish. */
    private const val ATTEMPTS = 2

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

    internal fun serviceTarget(target: String, service: Int): String = when (target) {
        "uzc" -> "uz"
        "zh" -> if (service == AZURE) "zh-Hans" else "zh-CN"
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
        if (code != 200) throw httpFailure(code, body)
        val arr = JSONObject(body).getJSONObject("data").getJSONArray("translations")
        return (0 until arr.length()).joinToString("\n") { arr.getJSONObject(it).getString("translatedText") }
    }

    // ---------------------------------------------------------------- Azure Translator v3

    internal fun azurePath(source: String?, target: String): String =
        "/translate?api-version=3.0&textType=plain&to=$target" + (if (source != null) "&from=$source" else "")

    internal fun azureBody(text: String): String = JSONArray().put(JSONObject().put("Text", text)).toString()

    internal fun azureParse(code: Int, body: String): String {
        if (code != 200) throw httpFailure(code, body)
        return JSONArray(body).getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text")
    }

    // ---------------------------------------------------------------- xatolar

    /** Xizmatning JSON xatosidan (xabar, sabab). Google: error.message + error.details[].reason. */
    private fun errorInfo(body: String): Pair<String?, String?> = try {
        val t = body.trim()
        if (!t.startsWith("{")) {
            null to null
        } else {
            val e = JSONObject(t).optJSONObject("error")
            val message = e?.optString("message")?.takeIf { it.isNotBlank() }
            var reason: String? = e?.optString("status")?.takeIf { it.isNotBlank() }
            val details = e?.optJSONArray("details")
            if (details != null) {
                for (i in 0 until details.length()) {
                    details.optJSONObject(i)?.optString("reason")?.takeIf { it.isNotBlank() }?.let { reason = it }
                }
            }
            message to reason
        }
    } catch (e: JSONException) {
        null to null
    }

    /** HTTP xatosini foydalanuvchiga tushunarli sababga aylantiradi. */
    internal fun httpFailure(code: Int, body: String): Failure {
        val (message, reason) = errorInfo(body)
        val m = (message ?: "").lowercase()
        val r = (reason ?: "").uppercase()
        val json = body.trimStart().let { it.startsWith("{") || it.startsWith("[") }
        if (!json && (code == 403 || code == 429 || code == 503)) {
            return Failure("Xizmat söroqni töşdi ($code)", retry = true)
        }
        val text = when {
            r == "API_KEY_INVALID" || "api key not valid" in m ->
                "Kalit notöğri: nusxalaşda xato bölgan bölişi mumkin, qayta kiriting"
            r == "SERVICE_DISABLED" || "has not been used" in m || "is disabled" in m ->
                "Cloud Translation API kalit tegişli loyihada yoqilmagan"
            r == "BILLING_DISABLED" || "billing" in m ->
                "Loyihaga billing (karta) ulanmagan yoki faol emas"
            r == "API_KEY_SERVICE_BLOCKED" || "are blocked" in m && "api" in m ->
                "Kalit çeklovi Cloud Translation API'ni ruxsat etmayapti"
            r.startsWith("API_KEY_") && r.endsWith("_BLOCKED") ->
                "Kalitda ilova/sayt/IP çeklovi bor: «Application restrictions» = None qiling"
            code == 429 || r == "RATE_LIMIT_EXCEEDED" || r == "RESOURCE_EXHAUSTED" ->
                "Bepul kvota tugadi yoki söroqlar juda tez"
            code == 401 || code == 403 -> "Kalit notöğri yoki bu xizmatga ruxsati yöq"
            code == 400 -> "Xizmat söroqni qabul qilmadi"
            code in 500..599 -> "Xizmat vaqtinça işlamayapti"
            else -> "Tarjima xatosi"
        }
        val tail = listOfNotNull(code.toString(), reason, message?.take(140)).joinToString(" · ")
        return Failure("$text ($tail)", retry = code in 500..599)
    }

    /** Tarjima (fon oqimida). Tarmoqqa faqat [Net] orqali chiqadi; to'g'ridan-to'g'ri (Tor'siz). */
    fun translate(mode: Int, key: String, region: String, text: String, source: String?, target: String): String {
        if (key.isBlank()) throw Failure("Avval Söz+ sozlamalarida tarjima kalitini kiriting")
        var last: Failure? = null
        repeat(ATTEMPTS) {
            try {
                return translateOnce(service(mode), key, region, text, source, target)
            } catch (e: Failure) {
                if (!e.retry) throw e
                last = e
            }
        }
        throw last ?: Failure("Tarjima bölmadi")
    }

    private fun translateOnce(service: Int, key: String, region: String, text: String, source: String?, target: String): String {
        val t = serviceTarget(target, service)
        val clipped = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        try {
            return if (service == AZURE) {
                val headers = ArrayList<Pair<String, String>>()
                headers.add("Ocp-Apim-Subscription-Key" to key.trim())
                if (region.isNotBlank()) headers.add("Ocp-Apim-Subscription-Region" to region.trim())
                val r = Net.post(AZURE_HOST, azurePath(source, t), JSON, azureBody(clipped).toByteArray(Charsets.UTF_8), headers)
                azureParse(r.code, r.body)
            } else {
                val headers = listOf("X-Goog-Api-Key" to key.trim())
                val r = Net.post(GOOGLE_HOST, "/language/translate/v2", JSON, googleBody(clipped, source, t).toByteArray(Charsets.UTF_8), headers)
                googleParse(r.code, r.body)
            }
        } catch (e: Net.Timeout) {
            throw Failure("Javob kelmadi (vaqt tugadi)", e, retry = true)
        } catch (e: Net.TlsFailure) {
            throw Failure("Xavfsiz ulaniş (TLS) tasdiqlanmadi — tarjima yuborilmadi", e)
        } catch (e: JSONException) {
            throw Failure("Xizmat javobini tuşunib bölmadi", e)
        } catch (e: IOException) {
            throw Failure("Tarmoq xatosi, qayta urinib köring", e, retry = true)
        } catch (e: IllegalArgumentException) {
            throw Failure("Kalit yoki mintaqa notöğri yozilgan", e)
        }
    }
}
