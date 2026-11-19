package uz.alifbo.klaviatura

import android.app.Activity
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
        titles.addView(label("Yangi özbek alifbosi uçun aqlli klaviatura", 14f, muted))
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
        buildLangCard()
        buildSourcesCard()

        val foot = label(
            "Ilova heç qanday ruxsat söramaydi va internetga ulana olmaydi. " +
                "Yozganlaringiz faqat telefoningizda qoladi.",
            12f, muted,
        )
        content.addView(foot, lp().apply { topMargin = dpi(20) })

        refreshPreview()
    }

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
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

    private fun buildTranslateCard() {
        val c = card("Tarjima")
        c.addView(
            label(
                "Tahmin qatoridagi «A文» tugmasi belgilangan matnni (yoki maydondagi butun matnni) telefoningizdagi " +
                    "Google Tarjimon ilovasiga uzatadi va tarjimani örniga qö'yadi. Klaviaturaning özi internetga " +
                    "ulanmaydi: matn faqat siz tugmani bosganingizda Google Tarjimonga ketadi. Tarjima avtomatik " +
                    "qaytmasa, uni nusxalang — bufer uni ilib oladi va qatorda taklif qiladi.",
                12f, muted,
            ),
        )
        val installed = TranslateBridge.isGoogleTranslateInstalled(packageManager)
        c.addView(
            label(if (installed) "✓ Google Tarjimon örnatilgan" else "Google Tarjimon örnatilmagan", 13f, if (installed) accent else muted),
            lp().apply { topMargin = dpi(8) },
        )
        if (!installed) {
            c.addView(pill("Google Tarjimonni örnatiş") { TranslateBridge.openStore(this) }, lp().apply { topMargin = dpi(8) })
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
                    "Emoji paneli: AndroidX Emoji2 (Apache-2.0).",
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
}
