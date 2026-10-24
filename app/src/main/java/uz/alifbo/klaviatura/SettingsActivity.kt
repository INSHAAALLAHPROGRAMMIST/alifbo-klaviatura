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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Sozlamalar ekrani: yoqish bosqichlari, jonli ko'rinish, tema, o'lchamlar, tillar. Hech qanday tarmoq ishlatmaydi. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var preview: KeyboardView
    private lateinit var previewBox: FrameLayout
    private lateinit var step1Status: TextView
    private lateinit var step2Status: TextView
    private lateinit var customBox: LinearLayout
    private val themeButtons = ArrayList<TextView>()
    private val langButtons = ArrayList<TextView>()
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

        content.addView(label("Alifbo", 30f, ink, bold = true))
        content.addView(label("Yangi özbek alifbosi uçun klaviatura", 14f, muted))

        buildSetupCard()
        buildTryCard()
        buildPreviewCard()
        buildThemeCard()
        buildSizeCard()
        buildOptionsCard()
        buildLangCard()

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
    }

    private fun buildThemeCard() {
        val c = card("Rang mavzusi")
        val names = listOf("Tizim", "Yoruğ", "Qora", "Maxsus")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        names.forEachIndexed { i, n ->
            val b = chip(n) {
                prefs.themeMode = i
                customBox.visibility = if (i == 3) View.VISIBLE else View.GONE
                refreshPreview()
            }
            themeButtons.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpi(6)
            })
        }
        c.addView(row)

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
        preview.setLayout(Layouts.letters(previewLang, prefs.numberRow))
        previewBox.background = GradientDrawable().apply {
            setColor(theme.background)
            cornerRadius = dp(14f)
        }
        themeButtons.forEachIndexed { i, b -> styleChip(b, i == prefs.themeMode) }
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
