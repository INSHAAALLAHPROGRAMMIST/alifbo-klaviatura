package uz.alifbo.klaviatura

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Sozlamalar ekrani: yoqish bosqichlari, jonli ko'rinish, tema, o'lchamlar, tillar. Hech qanday tarmoq ishlatmaydi. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var preview: KeyboardView
    private lateinit var previewBox: FrameLayout
    private lateinit var step1Status: TextView
    private lateinit var step2Status: TextView
    private lateinit var customBox: LinearLayout
    private val themeTiles = ArrayList<Pair<Int, ThemeSwatch>>()
    private val langButtons = ArrayList<TextView>()
    private val layoutButtons = ArrayList<TextView>()
    private var previewLang: Lang = Lang.UZ

    // tarjima va parollar
    private lateinit var keyStatus: TextView
    private lateinit var keyHelp: TextView
    private lateinit var keyInput: EditText
    private lateinit var testResult: TextView
    private lateinit var vaultStatus: TextView
    private var pendingExport: ByteArray? = null

    private var pageBg = 0
    private var cardBg = 0
    private var ink = 0
    private var muted = 0
    private var accent = 0xFF2F6FED.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        previewLang = prefs.enabledLangs().first()

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        pageBg = if (night) 0xFF0F1114.toInt() else 0xFFF2F3F5.toInt()
        cardBg = if (night) 0xFF1A1D22.toInt() else 0xFFFFFFFF.toInt()
        ink = if (night) 0xFFF1F3F6.toInt() else 0xFF1B1E24.toInt()
        muted = if (night) 0xFF9AA1AE.toInt() else 0xFF6B7280.toInt()
        accent = if (night) 0xFF5B8CFF.toInt() else 0xFF2F6FED.toInt()

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(pageBg)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(24), dpi(16), dpi(32))
        }
        scroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dpi(56), dpi(56)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label(getString(R.string.app_name), 28f, ink, bold = true))
        titles.addView(label("Aqlli klaviatura: tarjimon va parollar bilan", 14f, muted))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dpi(14)
        })
        content.addView(header)

        buildSetupCard()
        buildTryCard()
        buildPreviewCard()
        buildThemeCard()
        buildSizeCard()
        buildOptionsCard()
        buildTypingCard()
        buildPredictCard()
        buildClipboardCard()
        buildTranslateCard()
        buildVaultCard()
        buildLangCard()
        buildSourcesCard()

        val foot = label(
            "Söz+ internetga faqat tarjima uçun va faqat siz ➤ bosganda çiqadi (to'g'ridan-to'g'ri, " +
                "Tor'siz — Google xizmatga söroq manzilingizni körishi mumkin). Yozganlaringiz, örganilgan " +
                "sözlar, bufer va parollar faqat telefoningizda qoladi.",
            12f, muted,
        )
        content.addView(foot, lp().apply { topMargin = dpi(20) })

        refreshPreview()
    }

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
        refreshTranslateCard()
        refreshVaultCard()
    }

    // ---------------------------------------------------------------- bo'limlar

    private fun buildSetupCard() {
        val c = card("Yoqiş")
        step1Status = label("", 13f, muted)
        c.addView(label("1. Klaviaturani yoqing", 16f, ink))
        c.addView(step1Status)
        c.addView(pill("Sozlamalarni oçing") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, lp().apply { topMargin = dpi(8) })

        step2Status = label("", 13f, muted)
        c.addView(label("2. Klaviaturani tanlang", 16f, ink), lp().apply { topMargin = dpi(16) })
        c.addView(step2Status)
        c.addView(pill("Klaviaturani almaştiriş") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }, lp().apply { topMargin = dpi(8) })
    }

    private fun buildTryCard() {
        val c = card("Sinab köring")
        val edit = EditText(this).apply {
            hint = "Bu yerga yozib köring…"
            setHintTextColor(muted)
            setTextColor(ink)
            textSize = 17f
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        c.addView(edit)
    }

    private fun buildPreviewCard() {
        val c = card("Körinişi")
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (l in Lang.entries) {
            val b = chip(l.short) {
                previewLang = l
                refreshPreview()
            }
            langButtons.add(b)
            chips.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpi(6)
            })
        }
        c.addView(chips)

        preview = KeyboardView(this)
        previewBox = FrameLayout(this)
        previewBox.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        previewBox.clipToOutline = true
        c.addView(previewBox, lp().apply { topMargin = dpi(10) })

        // o'zbek lotin maketi: QWERTY, "Tezkor" (ikki barmoq) yoki "Bir qo'l" (o'ng bosh barmoq)
        c.addView(label("Özbek maketi", 15f, ink), lp().apply { topMargin = dpi(14) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("QWERTY", "Tezkor", "Bir qöl")
        names.forEachIndexed { i, name ->
            val b = chip(name) {
                prefs.uzLayout = i
                previewLang = Lang.UZ
                refreshPreview()
            }
            layoutButtons.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i < names.size - 1) marginEnd = dpi(6)
            })
        }
        c.addView(row, lp().apply { topMargin = dpi(6) })
        c.addView(
            label(
                "Ikkala maket özbek matnlaridagi harf çastotasi bölyiça hisoblangan. Tezkor — ikki baş barmoq " +
                    "uçun: unlilar chap, undoşlar ö'ngda, barmoqlar navbatma-navbat bosadi (QWERTY'dan ~34% tez). " +
                    "Bir qöl — faqat ö'ng baş barmoq uçun: tez-tez keladigan harflar markazda yonma-yon " +
                    "(QWERTY'dan ~17% tez). Könikiş uçun bir necha hafta kerak.",
                12f, muted,
            ),
            lp().apply { topMargin = dpi(6) },
        )
    }

    private fun buildThemeCard() {
        val c = card("Rang mavzusi")
        // 3 ustunli to'r: har katakda kichik klaviatura ko'rinishi va nomi
        var row: LinearLayout? = null
        Themes.choices.forEachIndexed { i, (mode, name) ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                c.addView(row, lp().apply { if (i > 0) topMargin = dpi(10) })
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener {
                    prefs.themeMode = mode
                    customBox.visibility = if (mode == 3) View.VISIBLE else View.GONE
                    refreshPreview()
                }
            }
            val swatch = ThemeSwatch(this).apply { ring = accent }
            tile.addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(64)))
            tile.addView(label(name, 13f, ink).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = dpi(4) })
            themeTiles.add(mode to swatch)
            row?.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i % 3 != 2) marginEnd = dpi(10)
            })
        }

        customBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.themeMode == 3) View.VISIBLE else View.GONE
        }
        c.addView(customBox, lp().apply { topMargin = dpi(8) })
        colorRow(customBox, "Fon", { prefs.colorBg }, { prefs.colorBg = it })
        colorRow(customBox, "Tugma", { prefs.colorKey }, { prefs.colorKey = it })
        colorRow(customBox, "Matn", { prefs.colorText }, { prefs.colorText = it })
        colorRow(customBox, "Urğu (Enter tugmasi)", { prefs.colorAccent }, { prefs.colorAccent = it })
    }

    private fun buildSizeCard() {
        val c = card("Ölçamlar")
        slider(c, "Tugma balandligi", 40, 68, prefs.keyHeightDp, " dp") { prefs.keyHeightDp = it }
        slider(c, "Harf hajmi", 80, 130, prefs.textScalePct, "%") { prefs.textScalePct = it }
        slider(c, "Yumaloqlik", 0, 16, prefs.cornerDp, " dp") { prefs.cornerDp = it }
    }

    private fun buildOptionsCard() {
        val c = card("Boşqa")
        switchRow(c, "Tebraniş", prefs.haptic) { prefs.haptic = it }
        switchRow(c, "Tugma tovuşi", prefs.sound) { prefs.sound = it }
        switchRow(c, "Raqam qatori", prefs.numberRow) { prefs.numberRow = it }
        switchRow(c, "Tugmalarda raqam körsatkiçi", prefs.showHints) { prefs.showHints = it }
    }

    private fun buildTypingCard() {
        val c = card("Yoziş")
        switchRow(c, "Gap boşida katta harf", prefs.autoCap) { prefs.autoCap = it }
        switchRow(c, "Tinişdan keyin probel", prefs.autoSpace) { prefs.autoSpace = it }
        switchRow(c, "Ikki probel — nuqta", prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it }
        c.addView(
            label(
                "Söz ortidan . , ! ? ; : qöyilsa, probel özi qöyiladi; yana probel bossangiz, ikkinçisi qöşilmaydi. " +
                    "Raqamlarda (3.14, 1,5, 10:30), havola va emaillarda probel qöyilmaydi. " +
                    "Gap tugagaç (. ! ? dan keyin), yangi qatorda va maydon boşida birinçi harf katta bölib çiqadi. " +
                    "Probelni tez ikki marta bossangiz — nuqta va probel.",
                12f, muted,
            ),
        )
    }

    private fun buildPredictCard() {
        val c = card("Söz tahmini")
        switchRow(c, "Tahmin qatori", prefs.predict) { prefs.predict = it }
        switchRow(c, "Şaxsiy örganiş", prefs.learn) { prefs.learn = it }
        switchRow(c, "Avtomatik tuzatiş", prefs.autoCorrect) { prefs.autoCorrect = it }
        c.addView(
            label(
                "Probel yoki tinish belgisi bosilganda xato yozilgan söz tuzatiladi (masalan yaxsi → yaxşi, " +
                    "shahar → şahar). Darhol orqaga öçiriş tugmasini bossangiz, yozganingiz qaytadi.",
                12f, muted,
            ),
        )
        switchRow(c, "Eski yozuvdan yordam", prefs.oldSpellingHelp) { prefs.oldSpellingHelp = it }
        c.addView(
            label(
                "Yangi alifboga könikkunça: «s» yozsangiz «ş» bilan boşlanadigan sözlar ham taklif qilinadi " +
                    "(o → ö, g → ğ ham), eski odat bilan yozilgan sh, oʼ, gʼ esa ş, ö, ğ deb tuşuniladi.",
                12f, muted,
            ),
        )
        c.addView(
            label(
                "Örganilgan sözlar faqat telefoningizda saqlanadi. Parol, email va havola " +
                    "maydonlarida klaviatura heç narsa örganmaydi va taklif körsatmaydi.",
                12f, muted,
            ),
            lp().apply { topMargin = dpi(6) },
        )
        c.addView(pill("Örganilgan sözlarni öçiriş") { clearLearned() }, lp().apply { topMargin = dpi(10) })
    }

    private fun clearLearned() {
        for (l in Lang.entries) File(filesDir, "personal_${l.id}.txt").delete()
        prefs.personalEpoch = prefs.personalEpoch + 1
        Toast.makeText(this, "Örganilgan sözlar öçirildi", Toast.LENGTH_SHORT).show()
    }

    private fun buildLangCard() {
        val c = card("Tillar")
        val enabled = prefs.enabledLangs().toMutableList()
        for (l in Lang.entries) {
            val cb = CheckBox(this).apply {
                text = l.title
                setTextColor(ink)
                textSize = 15f
                isChecked = l in enabled
            }
            cb.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (l !in enabled) enabled.add(l)
                } else if (enabled.size > 1) {
                    enabled.remove(l)
                } else {
                    cb.isChecked = true
                }
                prefs.setEnabledLangs(Lang.entries.filter { it in enabled })
            }
            c.addView(cb)
        }
    }

    private fun buildClipboardCard() {
        val c = card("Bufer")
        switchRow(c, "Nusxalar tarixini saqlaş", prefs.clipboardHistory) { prefs.clipboardHistory = it }
        c.addView(
            label(
                "Nusxa olingan har bir matn şu yerda saqlanadi: eng yangisi tepada, eskilari ham turaveradi. " +
                    "Tahmin qatoridagi 📋 tugmasi tarixni oçadi; ☆ — qadab qö'yiş, ✕ — öçiriş. Yangi nusxa " +
                    "15 daqiqa davomida yozişdan oldin qatorda taklif qilinadi. Parol menejerlari maxfiy deb " +
                    "belgilagan nusxalar saqlanmaydi va parol maydonlarida taklif qilinmaydi. Tarix faqat telefonda.",
                12f, muted,
            ),
            lp().apply { topMargin = dpi(6) },
        )
        c.addView(pill("Bufer tarixini öçiriş") { clearClipboard() }, lp().apply { topMargin = dpi(10) })
    }

    private fun clearClipboard() {
        File(filesDir, "clipboard.txt").delete()
        prefs.clipboardEpoch = prefs.clipboardEpoch + 1
        Toast.makeText(this, "Bufer tarixi öçirildi", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- tarjima (to'g'ridan-to'g'ri)

    private fun buildTranslateCard() {
        val c = card("Tarjima")
        c.addView(
            label(
                "Tahmin qatoridagi «A文» tugmasi klaviatura içida tarjima qatorini oçadi: yozasiz, ➤ bosasiz, " +
                    "tarjimani körib «Qöyiş» bilan maydonga qöyasiz. 📋 — buferdagi matnni (masalan, kelgan xabarni) " +
                    "tarjima qiliş. Matn faqat siz ➤ bosganingizda, Google Cloud Translation xizmatiga " +
                    "to'g'ridan-to'g'ri ketadi (Tor'siz — xizmat söroq manzilingizni körishi mumkin). Parol " +
                    "maydonlarida tarjima öçiq; parollar ombori tarmoqqa heç qaçon çiqmaydi.",
                12f, muted,
            ),
        )

        c.addView(label("Tarjima kaliti", 15f, ink), lp().apply { topMargin = dpi(14) })
        keyStatus = label("", 13f, muted)
        c.addView(keyStatus, lp().apply { topMargin = dpi(8) })
        keyInput = EditText(this).apply {
            hint = "API kaliti (bösh qoldirsangiz, standart kalit işlatiladi)"
            setHintTextColor(muted)
            setTextColor(ink)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        c.addView(keyInput)
        c.addView(pill("Kalitni saqlaş") { saveTranslateKey() }, lp().apply { topMargin = dpi(8) })
        keyHelp = label("", 12f, muted)
        c.addView(keyHelp, lp().apply { topMargin = dpi(8) })

        c.addView(label("Sinab köriş", 15f, ink), lp().apply { topMargin = dpi(16) })
        c.addView(pill("«Salom, dunyo!» ni tarjima qiliş") { testTranslate() }, lp().apply { topMargin = dpi(8) })
        testResult = label("", 13f, muted)
        c.addView(testResult, lp().apply { topMargin = dpi(6) })
        refreshTranslateCard()
    }

    private fun refreshTranslateCard() {
        if (!::keyStatus.isInitialized) return
        val has = Secrets.has(this, Translator.KEY_NAME)
        val usingDefault = Secrets.usingDefault(this, Translator.KEY_NAME)
        keyStatus.text = when {
            has -> "✓ O'zingizning kalitingiz saqlangan (şifrlangan). Yangisini kiritsangiz, almaşadi."
            usingDefault -> "✓ Ilovaga qotirilgan standart kalit işlatilmoqda. Xohlasangiz, özingiznikini kiriting."
            else -> "Kalit hali yöq"
        }
        keyStatus.setTextColor(if (has || usingDefault) accent else muted)
        keyHelp.text = "Özingizning kalitingiz: console.cloud.google.com → «Cloud Translation API» ni " +
            "yoqing → «Credentials» → «Create API key». Oyiga 500 ming belgigaça bepul, hisobda karta " +
            "(billing) bölişi kerak. Xavfsizlik uçun kalitni faqat «Cloud Translation API» bilan çeklang."
    }

    private fun saveTranslateKey() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            if (!Secrets.put(this, Translator.KEY_NAME, null)) {
                Toast.makeText(this, "Kalit öçirilmadi", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "O'z kalitingiz öçirildi, standart kalit işlatiladi", Toast.LENGTH_SHORT).show()
            refreshTranslateCard()
            return
        }
        if (!Secrets.put(this, Translator.KEY_NAME, key)) {
            Toast.makeText(this, "Kalit saqlanmadi", Toast.LENGTH_SHORT).show()
            return
        }
        keyInput.setText("")
        // kalit nusxalab qo'yilgan bo'lsa — bufer tarixidan ham o'chiramiz
        forgetClip(key)
        Toast.makeText(this, "Kalit şifrlab saqlandi", Toast.LENGTH_SHORT).show()
        refreshTranslateCard()
    }

    private fun forgetClip(text: String) {
        try {
            val f = File(filesDir, "clipboard.txt")
            if (!f.exists()) return
            val store = ClipboardStore.parse(f.readText())
            val before = store.size
            store.remove(text)
            if (store.size != before) {
                f.writeText(store.serialize())
                prefs.clipboardEpoch = prefs.clipboardEpoch + 1
            }
        } catch (t: Throwable) {
            // bufer fayli o'qilmadi
        }
    }

    private fun testTranslate() {
        val key = Secrets.get(this, Translator.KEY_NAME)
        if (key.isNullOrBlank()) {
            testResult.text = "Avval kalitni kiriting va saqlang"
            return
        }
        testResult.text = "Yuborilmoqda…"
        Thread {
            val msg = try {
                val src = UzScript.newToOld("Salom, dunyo! Bugun havo juda yaxşi.")
                "✓ " + Translator.translate(key, src, "uz", "en")
            } catch (e: Translator.Failure) {
                "✗ " + e.userMessage
            } catch (e: Exception) {
                "✗ Tarjima bölmadi"
            }
            runOnUiThread { if (!isFinishing) testResult.text = msg }
        }.apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- parollar

    private fun buildVaultCard() {
        val c = card("Parollar")
        vaultStatus = label("", 13f, muted)
        c.addView(vaultStatus)
        switchRow(c, "Parollarni eslab qoliş", prefs.vaultAutoSave) { prefs.vaultAutoSave = it }
        switchRow(c, "Kuçli parol taklifi", prefs.vaultSuggest) { prefs.vaultSuggest = it }
        c.addView(
            label(
                "Biror ilovaga kirganingizda Söz+ login (email, telefon raqami yoki foydalanuvçi nomi) va parolni " +
                    "şifrlab eslab qoladi. Keyingi safar parol maydonida qatorda 🔑 çiqadi — bosilsa, telefon " +
                    "qulfi (barmoq izi yoki PIN) bilan tasdiqlab töldirasiz. Zaif parol yozsangiz yoki yangi parol " +
                    "maydonida, qatorda kuçli parol taklif qilinadi. Parollar telefonning himoyalangan qismidagi " +
                    "kalit bilan şifrlanadi va heç qaçon internetga çiqmaydi.",
                12f, muted,
            ),
            lp().apply { topMargin = dpi(6) },
        )
        c.addView(
            label(
                "Diqqat: telefonda ekran qulfini butunlay öçirsangiz, Android ombor kalitini ham öçiradi. " +
                    "Şuning uçun vaqti-vaqti bilan zaxira nusxa oling (bosh parol bilan şifrlanadi).",
                12f, muted,
            ),
            lp().apply { topMargin = dpi(6) },
        )
        c.addView(pill("Zaxira nusxa oliş") { exportVault() }, lp().apply { topMargin = dpi(10) })
        c.addView(pill("Zaxiradan tiklaş") { importVault() }, lp().apply { topMargin = dpi(8) })
        c.addView(pill("Hamma parollarni öçiriş") { wipeVault() }, lp().apply { topMargin = dpi(8) })
        refreshVaultCard()
    }

    private fun refreshVaultCard() {
        if (!::vaultStatus.isInitialized) return
        vaultStatus.text = if (!VaultCrypto.isDeviceSecure(this)) {
            "⚠ Telefonda ekran qulfi yöq: parollarni saqlaş uçun avval PIN yoki grafik kalit qöying."
        } else {
            "${Vault.count(this)} ta kiriş saqlangan (şifrlangan)"
        }
    }

    /** Ombor ochiq bo'lsa [action]; bo'lmasa telefon qulfini so'raydi. */
    private fun withOpenVault(action: () -> Unit) {
        when (Vault.tryOpen(this)) {
            Vault.Open.OPEN -> action()
            Vault.Open.NEED_AUTH -> VaultBridge.requestUnlock(this) { ok ->
                if (ok && Vault.tryOpen(this) == Vault.Open.OPEN) action()
            }
            Vault.Open.NO_LOCK -> Toast.makeText(this, "Avval telefonda ekran qulfini qöying", Toast.LENGTH_LONG).show()
            Vault.Open.BROKEN -> Toast.makeText(this, "Ombor kaliti bekor bölgan: zaxiradan tiklang", Toast.LENGTH_LONG).show()
        }
    }

    private fun askPassphrase(confirm: Boolean, onOk: (CharArray) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), 0)
        }
        fun field(h: String) = EditText(this).apply {
            hint = h
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val p1 = field("Bosh parol (kamida 8 belgi)")
        val p2 = field("Bosh parolni takrorlang")
        box.addView(p1)
        if (confirm) box.addView(p2)
        AlertDialog.Builder(this)
            .setTitle(if (confirm) "Zaxira uçun bosh parol" else "Zaxira bosh paroli")
            .setMessage(if (confirm) "Bu parolni unutsangiz, zaxirani oçib bölmaydi." else null)
            .setView(box)
            .setPositiveButton("Davom etiş") { _, _ ->
                val a = p1.text.toString()
                if (a.length < 8) {
                    Toast.makeText(this, "Bosh parol kamida 8 belgi bölsin", Toast.LENGTH_SHORT).show()
                } else if (confirm && a != p2.text.toString()) {
                    Toast.makeText(this, "Parollar mos kelmadi", Toast.LENGTH_SHORT).show()
                } else {
                    onOk(a.toCharArray())
                }
            }
            .setNegativeButton("Bekor qiliş", null)
            .show()
    }

    private fun exportVault() {
        withOpenVault {
            askPassphrase(confirm = true) { pass ->
                Thread {
                    val blob = try {
                        Vault.exportBlob(pass)
                    } catch (t: Throwable) {
                        null
                    } finally {
                        pass.fill(' ')
                    }
                    runOnUiThread {
                        if (blob == null) {
                            Toast.makeText(this, "Zaxira tayyorlanmadi", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingExport = blob
                            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/octet-stream")
                                .putExtra(Intent.EXTRA_TITLE, "soz-parollar.szp")
                            try {
                                startActivityForResult(i, REQ_EXPORT)
                            } catch (t: Throwable) {
                                pendingExport = null
                                Toast.makeText(this, "Fayl oynasi oçilmadi", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }.start()
            }
        }
    }

    private fun importVault() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            startActivityForResult(i, REQ_IMPORT)
        } catch (t: Throwable) {
            Toast.makeText(this, "Fayl oynasi oçilmadi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun wipeVault() {
        AlertDialog.Builder(this)
            .setTitle("Hamma parollar öçirilsinmi?")
            .setMessage("Saqlangan barça kirişlar va ularning kaliti butunlay öçiriladi. Buni qaytarib bölmaydi.")
            .setPositiveButton("Öçiriş") { _, _ ->
                Vault.wipe(this)
                refreshVaultCard()
                Toast.makeText(this, "Parollar öçirildi", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Bekor qiliş", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        when (requestCode) {
            REQ_EXPORT -> {
                val blob = pendingExport
                pendingExport = null
                if (resultCode != RESULT_OK || uri == null || blob == null) return
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(blob) }
                    Toast.makeText(this, "Zaxira nusxa saqlandi", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    Toast.makeText(this, "Zaxira yozilmadi", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_IMPORT -> {
                if (resultCode != RESULT_OK || uri == null) return
                val bytes = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (t: Throwable) {
                    null
                }
                if (bytes == null || bytes.size > 5 shl 20) {
                    Toast.makeText(this, "Fayl öqilmadi", Toast.LENGTH_SHORT).show()
                    return
                }
                askPassphrase(confirm = false) { pass ->
                    Thread {
                        val n = try {
                            Vault.importBlob(applicationContext, bytes, pass)
                        } catch (e: VaultCrypto.NoScreenLock) {
                            -1
                        } catch (t: Throwable) {
                            null
                        } finally {
                            pass.fill(' ')
                        }
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                when (n) {
                                    null -> "Bosh parol notöğri yoki fayl buzuq"
                                    -1 -> "Avval telefonda ekran qulfini qöying"
                                    else -> "$n ta kiriş tiklandi"
                                },
                                Toast.LENGTH_LONG,
                            ).show()
                            refreshVaultCard()
                        }
                    }.start()
                }
            }
        }
    }

    private fun buildSourcesCard() {
        val c = card("Manbalar")
        c.addView(
            label(
                "Söz tahmini modellari oçiq matnlardan yasalgan:\n" +
                    "• Özbek tili korpusi — Raqamli texnologiyalar vazirligi, Digitaluz/uzbek-language-corpus " +
                    "(ODC-By 1.0): 5,6 mln hujjat, ~6 mlrd söz (faqat söz statistikasi)\n" +
                    "• UzCrawl — tahrirchi (Apache-2.0): özbekça Telegram bloglari\n" +
                    "• UzBooks — tahrirchi (Apache-2.0): özbekça badiiy kitoblar (faqat söz statistikasi)\n" +
                    "• Tatoeba — tatoeba.org (CC BY 2.0 FR): inglizça va ruscha gaplar\n" +
                    "Emoji paneli: AndroidX Emoji2 (Apache-2.0).\n" +
                    "Tarjima: Google Cloud Translation (to'g'ridan-to'g'ri).",
                13f, ink,
            ),
        )
    }

    // ---------------------------------------------------------------- holat

    private fun refreshSetupStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val cur = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = cur.startsWith("$packageName/")
        step1Status.text = if (enabled) "✓ Yoqilgan" else "Hali yoqilmagan"
        step2Status.text = if (selected) "✓ Tanlangan" else "Hali tanlanmagan"
    }

    private fun refreshPreview() {
        val theme = prefs.theme()
        preview.theme = theme
        preview.keyHeightPx = dp(prefs.keyHeightDp.toFloat())
        preview.textScale = prefs.textScalePct / 100f
        preview.cornerPx = dp(prefs.cornerDp.toFloat())
        preview.showHints = prefs.showHints
        preview.hapticOn = prefs.haptic
        preview.soundOn = prefs.sound
        preview.langName = previewLang.label
        preview.langShorts = Lang.entries.map { it.short }
        preview.setLayout(Layouts.letters(previewLang, prefs.numberRow, prefs.uzLayout))
        layoutButtons.forEachIndexed { i, b -> styleChip(b, i == prefs.uzLayout) }
        previewBox.background = GradientDrawable().apply {
            setColor(theme.background)
            cornerRadius = dp(14f)
        }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        for ((mode, swatch) in themeTiles) {
            swatch.theme = if (mode == 3) {
                Themes.custom(prefs.colorBg, prefs.colorKey, prefs.colorText, prefs.colorAccent)
            } else {
                Themes.preset(mode, night) ?: Themes.light
            }
            swatch.selectedTile = mode == prefs.themeMode
        }
        langButtons.forEachIndexed { i, b -> styleChip(b, Lang.entries[i] == previewLang) }
    }

    // ---------------------------------------------------------------- kichik yordamchilar

    private fun dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun lp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp)
    }

    private fun label(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(14), dpi(16), dpi(16))
            background = rounded(cardBg, 16f)
        }
        c.addView(label(title.uppercase(), 12f, muted, bold = true), lp().apply { bottomMargin = dpi(8) })
        content.addView(c, lp().apply { topMargin = dpi(14) })
        return c
    }

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        setPadding(dpi(16), dpi(11), dpi(16), dpi(11))
        background = rounded(accent, 12f)
        setOnClickListener { onClick() }
    }

    private fun chip(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dpi(8), dpi(9), dpi(8), dpi(9))
        setOnClickListener { onClick() }
        styleChip(this, false)
    }

    private fun styleChip(b: TextView, selected: Boolean) {
        b.background = rounded(if (selected) accent else pageBg, 10f)
        b.setTextColor(if (selected) 0xFFFFFFFF.toInt() else ink)
    }

    private fun switchRow(parent: LinearLayout, text: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val s = Switch(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(ink)
            isChecked = value
            setPadding(0, dpi(6), 0, dpi(6))
            setOnCheckedChangeListener { _, v ->
                onChange(v)
                refreshPreview()
            }
        }
        parent.addView(s, lp())
    }

    private fun slider(
        parent: LinearLayout,
        name: String,
        min: Int,
        max: Int,
        value: Int,
        unit: String,
        onChange: (Int) -> Unit,
    ) {
        val title = label("$name: $value$unit", 14f, ink)
        parent.addView(title, lp().apply { topMargin = dpi(6) })
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress + min
                    title.text = "$name: $v$unit"
                    if (fromUser) {
                        onChange(v)
                        refreshPreview()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        parent.addView(bar, lp())
    }

    private fun colorRow(parent: LinearLayout, name: String, get: () -> Int, set: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(get(), hsv)

        val swatch = View(this).apply { background = rounded(get(), 14f) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(8), 0, dpi(8))
        }
        head.addView(swatch, LinearLayout.LayoutParams(dpi(28), dpi(28)))
        head.addView(label(name, 15f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dpi(12)
        })

        val bars = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        fun apply() {
            val c = Color.HSVToColor(hsv)
            set(c)
            swatch.background = rounded(c, 14f)
            refreshPreview()
        }

        fun addBar(max: Int, start: Int, onValue: (Int) -> Unit) {
            val sb = SeekBar(this).apply {
                this.max = max
                progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            onValue(progress)
                            apply()
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            bars.addView(sb, lp())
        }
        addBar(360, hsv[0].toInt()) { hsv[0] = it.toFloat() }
        addBar(100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f }
        addBar(100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f }

        head.setOnClickListener {
            bars.visibility = if (bars.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        parent.addView(head, lp())
        parent.addView(bars, lp())
    }

    private companion object {
        const val REQ_EXPORT = 61
        const val REQ_IMPORT = 62
    }
}
