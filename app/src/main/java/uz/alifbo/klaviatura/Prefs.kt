package uz.alifbo.klaviatura

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/** Sozlamalar faqat telefon ichidagi SharedPreferences'da saqlanadi. */
class Prefs(private val context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("alifbo", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    /** 0 = tizimga ergashadi, 1 = yorug', 2 = qorong'i, 3 = maxsus, 4..8 = tayyor temalar (Themes.choices) */
    var themeMode: Int
        get() = int("themeMode", 0)
        set(v) = putInt("themeMode", v)

    var colorBg: Int
        get() = int("cBg", 0xFFE9EBEF.toInt())
        set(v) = putInt("cBg", v)
    var colorKey: Int
        get() = int("cKey", 0xFFFFFFFF.toInt())
        set(v) = putInt("cKey", v)
    var colorText: Int
        get() = int("cText", 0xFF1B1E24.toInt())
        set(v) = putInt("cText", v)
    var colorAccent: Int
        get() = int("cAccent", 0xFF2F6FED.toInt())
        set(v) = putInt("cAccent", v)

    var keyHeightDp: Int
        get() = int("keyH", 52)
        set(v) = putInt("keyH", v)
    var textScalePct: Int
        get() = int("textScale", 100)
        set(v) = putInt("textScale", v)
    var cornerDp: Int
        get() = int("corner", 8)
        set(v) = putInt("corner", v)

    var haptic: Boolean
        get() = bool("haptic", true)
        set(v) = putBool("haptic", v)
    var sound: Boolean
        get() = bool("sound", false)
        set(v) = putBool("sound", v)
    var numberRow: Boolean
        get() = bool("numberRow", false)
        set(v) = putBool("numberRow", v)
    var showHints: Boolean
        get() = bool("hints", false)
        set(v) = putBool("hints", v)

    /** O'zbek lotin maketi: Layouts.UZ_QWERTY / UZ_FAST (Tezkor) / UZ_ONE_HAND (Bir qo'l). */
    var uzLayout: Int
        get() = int("uzLayout", if (bool("uzFast", false)) Layouts.UZ_FAST else Layouts.UZ_QWERTY)
        set(v) = putInt("uzLayout", v)

    /** Nusxalar tarixi (bufer): nusxa olingan matnlar telefonda saqlanadi va taklif qilinadi. */
    var clipboardHistory: Boolean
        get() = bool("clipHistory", true)
        set(v) = putBool("clipHistory", v)

    /** Bufer tarixini o'chirganda oshiriladi: klaviatura xotiradagi nusxani tashlab yuboradi. */
    var clipboardEpoch: Int
        get() = int("clipEpoch", 0)
        set(v) = putInt("clipEpoch", v)

    /**
     * Tarjima rejimi: Translator.DEFAULT (standart, ilovaga qotirilgan kalit; sukut), GOOGLE yoki AZURE
     * (foydalanuvchining o'z kaliti, Secrets'da shifrlangan).
     */
    var trEngine: Int
        get() = int("trEngine", Translator.DEFAULT)
        set(v) = putInt("trEngine", v)

    /** Azure resursining mintaqasi (masalan westeurope); global resurs uchun bo'sh. */
    var azureRegion: String
        get() = sp.getString("azureRegion", "") ?: ""
        set(v) { sp.edit().putString("azureRegion", v).apply() }

    /** Tarjima qilinadigan til (Translator.targets kodi). */
    var trTarget: String
        get() = sp.getString("trTarget", "en") ?: "en"
        set(v) { sp.edit().putString("trTarget", v).apply() }

    /** Kirish maydonlariga yozilgan parollarni shifrlab eslab qolish. */
    var vaultAutoSave: Boolean
        get() = bool("vaultAutoSave", true)
        set(v) = putBool("vaultAutoSave", v)

    /** Zaif parol yozilganda yoki yangi parol maydonida kuchli parol taklif qilish. */
    var vaultSuggest: Boolean
        get() = bool("vaultSuggest", true)
        set(v) = putBool("vaultSuggest", v)

    /** Tahmin qatori (so'z takliflari). */
    var predict: Boolean
        get() = bool("predict", true)
        set(v) = putBool("predict", v)

    /** Yozilgan so'zlardan o'rganish (faqat telefonda saqlanadi). */
    var learn: Boolean
        get() = bool("learn", true)
        set(v) = putBool("learn", v)

    /** Eski yozuvdan yordam: "s" yozilsa "ş" li so'zlar ham taklif qilinadi va h.k. (o'tish davri uchun). */
    var oldSpellingHelp: Boolean
        get() = bool("oldSpelling", true)
        set(v) = putBool("oldSpelling", v)

    /** Probelda xato so'zni avtomatik tuzatish (orqaga o'chirish bilan qaytariladi). */
    var autoCorrect: Boolean
        get() = bool("autoCorrect", true)
        set(v) = putBool("autoCorrect", v)

    /** Gap boshida (maydon boshi, yangi qator, ". ! ?" dan keyin) avtomatik bosh harf. */
    var autoCap: Boolean
        get() = bool("autoCap", true)
        set(v) = putBool("autoCap", v)

    /** So'zdan keyin . , ! ? ; : qo'yilsa, ortidan probel o'zi qo'yiladi. */
    var autoSpace: Boolean
        get() = bool("autoSpace", true)
        set(v) = putBool("autoSpace", v)

    /** Probel tez ikki marta bosilsa — nuqta va probel. */
    var doubleSpacePeriod: Boolean
        get() = bool("dblSpace", true)
        set(v) = putBool("dblSpace", v)

    /** O'rganilganlarni o'chirganda oshiriladi: klaviatura xotiradagi nusxani tashlab yuboradi. */
    var personalEpoch: Int
        get() = int("personalEpoch", 0)
        set(v) = putInt("personalEpoch", v)

    var langsCsv: String
        get() = sp.getString("langs", "uz,en,ru,uzc") ?: "uz,en,ru,uzc"
        set(v) { sp.edit().putString("langs", v).apply() }
    var lastLang: String
        get() = sp.getString("lang", "uz") ?: "uz"
        set(v) { sp.edit().putString("lang", v).apply() }

    fun enabledLangs(): List<Lang> {
        val list = langsCsv.split(",").mapNotNull { Lang.byId(it.trim()) }.distinct()
        return list.ifEmpty { Lang.entries }
    }

    fun setEnabledLangs(langs: List<Lang>) {
        langsCsv = langs.joinToString(",") { it.id }
    }

    fun theme(): KbTheme {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (themeMode == 3) return Themes.custom(colorBg, colorKey, colorText, colorAccent)
        return Themes.preset(themeMode, night) ?: if (night) Themes.dark else Themes.light
    }
}
