package uz.alifbo.klaviatura

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.io.File

class AlifboIme : InputMethodService(), KeyboardListener, ClipboardHandler {

    private lateinit var prefs: Prefs
    private var root: KeyboardRoot? = null

    private var langs: List<Lang> = Lang.entries
    private var langIdx = 0
    private var page = Page.LETTERS
    private var shift = Shift.OFF
    private var enter = EnterStyle.RETURN
    private var lastShiftTap = 0L

    // tahmin
    private var predictOn = false
    private var learnOn = false
    private var fuzzyOn = true
    private var autoCorrectOn = true

    /** Qatordagi takliflar bosilganda qo'yiladigan matn (ko'rinishi qo'shtirnoqli bo'lishi mumkin). */
    private var stripCommit: List<String> = emptyList()

    /** Oxirgi avtomatik tuzatish: (asl so'z, qo'yilgan matn probel/belgi bilan). */
    private var lastAutoCorrect: Pair<String, String>? = null

    /** Foydalanuvchi qaytargan so'z: keyingi probelda qayta tuzatilmaydi. */
    private var suppressAutoFor: String? = null
    private var justAccepted = false
    private var knownEpoch = 0
    private val models = java.util.concurrent.ConcurrentHashMap<String, LanguageModel>()
    private val personals = HashMap<String, PersonalModel>()
    private val suggesters = HashMap<String, Suggester>()

    // bufer (nusxalar tarixi) va tarjima
    private var clipboard: ClipboardStore? = null
    private var clipboardOn = true
    private var typedThisSession = false
    private var clipboardEpoch = 0
    private var pendingTranslation: Pair<String, Boolean>? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        knownEpoch = prefs.personalEpoch
        clipboardEpoch = prefs.clipboardEpoch
        loadLangs()
        // ~2 MB modelni birinchi harf bosilishini kutmasdan fonda o'qib qo'yamiz
        val first = langs[langIdx]
        Thread { modelFor(first) }.apply { isDaemon = true }.start()
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        } catch (t: Throwable) {
            // bufer kuzatilmasa ham klaviatura ishlayveradi
        }
    }

    override fun onCreateInputView(): View {
        val r = KeyboardRoot(this, this)
        r.strip.onPick = { onSuggestion(it) }
        r.strip.onTool = { if (it == 0) toggleClipboardPanel() else startTranslate() }
        r.strip.onClip = { pasteLatestClip() }
        root = r
        applyPrefs()
        applyLayout()
        return r
    }

    // ---------------------------------------------------------------- bufer

    private fun clipboardStore(): ClipboardStore {
        if (prefs.clipboardEpoch != clipboardEpoch) {
            clipboardEpoch = prefs.clipboardEpoch
            clipboard = null
        }
        clipboard?.let { return it }
        val s = try {
            val f = File(filesDir, CLIPBOARD_FILE)
            if (f.exists()) ClipboardStore.parse(f.readText()) else ClipboardStore()
        } catch (t: Throwable) {
            ClipboardStore()
        }
        clipboard = s
        return s
    }

    private fun saveClipboard() {
        val s = clipboard ?: return
        if (!s.dirty) return
        try {
            File(filesDir, CLIPBOARD_FILE).writeText(s.serialize())
            s.markSaved()
        } catch (t: Throwable) {
            // keyingi safar
        }
    }

    /**
     * Yangi nusxani tarixga qo'shadi. Parol menejerlari "maxfiy" (IS_SENSITIVE) deb belgilagan
     * nusxalar saqlanmaydi.
     */
    private fun captureClipboard() {
        if (!prefs.clipboardHistory) return
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            val store = clipboardStore()
            val latest = store.latest()
            if (latest != null && latest.text == text) return
            if (store.add(text, System.currentTimeMillis())) {
                saveClipboard()
                typedThisSession = false
                root?.updateClipboard(store.sorted())
                refreshSuggestions()
            }
        } catch (t: Throwable) {
            // ba'zi tizimlar fonda buferni o'qishga ruxsat bermaydi
        }
    }

    /** Yozish boshlanmagan bo'lsa, oxirgi yangi nusxa qatorda taklif qilinadi. */
    private fun clipSuggestion(before: CharSequence?): String? {
        if (!clipboardOn || typedThisSession) return null
        val latest = clipboardStore().latest() ?: return null
        if (System.currentTimeMillis() - latest.time > CLIP_FRESH_MS) return null
        if (before != null && before.toString().endsWith(latest.text)) return null
        return latest.text
    }

    private fun pasteLatestClip() {
        val text = clipboardStore().latest()?.text ?: return
        val ic = currentInputConnection ?: return
        typedThisSession = true
        ic.commitText(text, 1)
        afterInput()
    }

    private fun toggleClipboardPanel() {
        val r = root ?: return
        if (r.isClipboardShown) {
            r.hideClipboard()
        } else {
            captureClipboard()
            r.showClipboard(clipboardStore().sorted(), this)
        }
    }

    override fun onClipPaste(text: String) {
        val ic = currentInputConnection ?: return
        typedThisSession = true
        ic.commitText(text, 1)
        root?.hideClipboard()
        afterInput()
    }

    override fun onClipPin(text: String) {
        val s = clipboardStore()
        s.togglePin(text)
        saveClipboard()
        root?.updateClipboard(s.sorted())
    }

    override fun onClipDelete(text: String) {
        val s = clipboardStore()
        s.remove(text)
        saveClipboard()
        root?.updateClipboard(s.sorted())
        refreshSuggestions()
    }

    override fun onClipClear() {
        val s = clipboardStore()
        s.clearUnpinned()
        saveClipboard()
        root?.updateClipboard(s.sorted())
        refreshSuggestions()
    }

    // ---------------------------------------------------------------- tarjima

    /**
     * Belgilangan matnni (yoki maydondagi butun matnni) telefondagi tarjima ilovasiga uzatadi.
     * Natija kelsa, maydonga qaytilganda o'sha matn o'rniga qo'yiladi.
     */
    private fun startTranslate() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val whole = if (selected.isEmpty()) {
            (ic.getTextBeforeCursor(5000, 0) ?: "").toString() + (ic.getTextAfterCursor(5000, 0) ?: "").toString()
        } else ""
        val text = selected.ifEmpty { whole }
        if (text.isBlank()) {
            Toast.makeText(this, "Tarjima uçun avval matn yozing yoki belgilang", Toast.LENGTH_SHORT).show()
            return
        }
        val replaceAll = selected.isEmpty()
        TranslateBridge.request { result -> pendingTranslation = result to replaceAll }
        try {
            startActivity(
                Intent(this, TranslateActivity::class.java)
                    .putExtra(TranslateActivity.EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            TranslateBridge.cancel()
            Toast.makeText(this, "Tarjima oynasi oçilmadi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPendingTranslation() {
        val (text, all) = pendingTranslation ?: return
        val ic = currentInputConnection ?: return
        pendingTranslation = null
        ic.beginBatchEdit()
        if (all) ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadLangs()
        if (prefs.personalEpoch != knownEpoch) {
            knownEpoch = prefs.personalEpoch
            personals.clear()
            suggesters.clear()
        }
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        page = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        ) Page.SYM1 else Page.LETTERS
        enter = enterStyleFor(info)
        shift = Shift.OFF
        justAccepted = false
        if (!restarting) typedThisSession = false
        clipboardOn = prefs.clipboardHistory && !isSensitiveField(info)
        configurePrediction(info)
        root?.hideEmoji()
        root?.hideClipboard()
        applyPrefs()
        applyLayout()
        applyPendingTranslation()
        captureClipboard()
        updateAutoCap()
        refreshSuggestions()
    }

    /** Parol maydonlarida bufer taklif qilinmaydi. */
    private fun isSensitiveField(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
    }

    override fun onFinishInput() {
        savePersonal()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        root?.hideEmoji()
        root?.hideClipboard()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        savePersonal()
        saveClipboard()
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        } catch (t: Throwable) {
            // e'tiborsiz
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPrefs()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshSuggestions()
    }

    // ---------------------------------------------------------------- sozlamalar

    private fun loadLangs() {
        langs = prefs.enabledLangs()
        val last = Lang.byId(prefs.lastLang)
        langIdx = langs.indexOf(last).let { if (it < 0) 0 else it }
    }

    private fun applyPrefs() {
        val r = root ?: return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val k = r.keyboard
        k.keyHeightPx = dp(prefs.keyHeightDp.toFloat() * (if (landscape) 0.8f else 1f))
        k.textScale = prefs.textScalePct / 100f
        k.cornerPx = dp(prefs.cornerDp.toFloat())
        k.hapticOn = prefs.haptic
        k.soundOn = prefs.sound
        k.showHints = prefs.showHints
        r.applyTheme(prefs.theme())
    }

    private fun applyLayout() {
        val r = root ?: return
        val lang = langs[langIdx]
        val k = r.keyboard
        k.casing = page == Page.LETTERS
        k.langName = lang.label
        k.langShorts = langs.map { it.short }
        k.shift = shift
        k.enterStyle = enter
        k.setLayout(
            if (page == Page.LETTERS) Layouts.letters(lang, prefs.numberRow, prefs.uzLayout) else Layouts.symbols(page),
        )
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun enterStyleFor(info: EditorInfo): EnterStyle {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterStyle.RETURN
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> EnterStyle.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterStyle.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterStyle.SEND
            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS -> EnterStyle.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterStyle.DONE
            else -> EnterStyle.RETURN
        }
    }

    // ---------------------------------------------------------------- bosh harflar

    private fun setShift(s: Shift) {
        shift = s
        root?.keyboard?.shift = s
    }

    private fun updateAutoCap() {
        if (page != Page.LETTERS || shift == Shift.CAPS) return
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return
        val caps = ic.getCursorCapsMode(info.inputType)
        val want = if (caps != 0) Shift.ONCE else Shift.OFF
        if (shift != want) setShift(want)
    }

    private fun afterInput() {
        if (shift == Shift.ONCE) setShift(Shift.OFF)
        updateAutoCap()
        refreshSuggestions()
    }

    // ---------------------------------------------------------------- tahmin

    /** Parol, email, havola va "taklif kerak emas" maydonlarida tahmin ham, o'rganish ham o'chiq. */
    private fun configurePrediction(info: EditorInfo) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isText = cls == InputType.TYPE_CLASS_TEXT
        val sensitive = isText && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_FILTER
            )
        val noSuggestions = isText && (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        predictOn = prefs.predict && isText && !sensitive && !noSuggestions
        fuzzyOn = prefs.oldSpellingHelp
        autoCorrectOn = prefs.autoCorrect
        lastAutoCorrect = null
        suppressAutoFor = null
        learnOn = predictOn && prefs.learn &&
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
    }

    private fun modelFor(lang: Lang): LanguageModel = models.getOrPut(lang.id) {
        try {
            assets.open("lm_${lang.id}.bin").use { LanguageModel.parse(it.readBytes()) }
        } catch (t: Throwable) {
            LanguageModel.EMPTY
        }
    }

    private fun personalFor(lang: Lang): PersonalModel = personals.getOrPut(lang.id) {
        try {
            val f = File(filesDir, "personal_${lang.id}.txt")
            if (f.exists()) PersonalModel.parse(f.readText()) else PersonalModel()
        } catch (t: Throwable) {
            PersonalModel()
        }
    }

    private fun suggesterFor(lang: Lang): Suggester =
        suggesters.getOrPut(lang.id) { Suggester(modelFor(lang), personalFor(lang), lang) }

    private fun clearStrip(strip: StripView) {
        strip.suggestions = emptyList()
        strip.highlightBest = false
        stripCommit = emptyList()
    }

    private fun refreshSuggestions() {
        val strip = root?.strip ?: return
        val before = try {
            currentInputConnection?.getTextBeforeCursor(80, 0)
        } catch (t: Throwable) {
            null
        }
        // yozish boshlanmagan bo'lsa: oxirgi nusxa (takliflar o'rnida)
        strip.clipText = try {
            clipSuggestion(before)
        } catch (t: Throwable) {
            null
        }
        if (!predictOn || page != Page.LETTERS) {
            clearStrip(strip)
            return
        }
        try {
            if (before == null) {
                clearStrip(strip)
                return
            }
            val ctx = ContextParser.parse(before)
            val style = Suggester.capStyle(ctx.prefix, shift)
            val sug = suggesterFor(langs[langIdx])
            val words = sug.suggest(ctx, style, 3, fuzzyOn)
            val auto = if (autoCorrectOn && ctx.prefix.length >= 2 && ctx.prefix != suppressAutoFor) {
                sug.autoCorrect(ctx, fuzzyOn)?.let { Suggester.caseLike(it, ctx.prefix) }
            } else null
            if (auto != null && auto != ctx.prefix) {
                // o'rtada: probelda qo'yiladigan tuzatish; chapda: yozilganning o'zi (qo'shtirnoqda)
                val other = words.firstOrNull { it != auto && it != ctx.prefix }
                stripCommit = listOfNotNull(auto, ctx.prefix, other)
                strip.suggestions = listOfNotNull(auto, "“${ctx.prefix}”", other)
                strip.highlightBest = true
            } else {
                stripCommit = words
                strip.suggestions = words
                strip.highlightBest = false
            }
        } catch (t: Throwable) {
            clearStrip(strip)
        }
    }

    /**
     * Probel yoki tinish belgisidan oldin: yozilgan so'z ishonchli tarzda tuzatiladigan bo'lsa, almashtiradi.
     * (asl so'z, qo'yilgan so'z) qaytaradi — orqaga o'chirish tugmasi bilan qaytarish uchun.
     */
    private fun maybeAutoCorrect(ic: InputConnection): Pair<String, String>? {
        if (!autoCorrectOn || !predictOn || page != Page.LETTERS) return null
        return try {
            val before = ic.getTextBeforeCursor(80, 0) ?: return null
            val ctx = ContextParser.parse(before)
            val raw = ctx.prefix
            if (raw.length < 2) return null
            if (raw == suppressAutoFor) {
                suppressAutoFor = null
                return null
            }
            val fixed = suggesterFor(langs[langIdx]).autoCorrect(ctx, fuzzyOn) ?: return null
            val cased = Suggester.caseLike(fixed, raw)
            if (cased == raw) return null
            ic.beginBatchEdit()
            ic.deleteSurroundingText(raw.length, 0)
            ic.commitText(cased, 1)
            ic.endBatchEdit()
            raw to cased
        } catch (t: Throwable) {
            null
        }
    }

    /** Avtomatik tuzatishdan keyin darhol orqaga o'chirish bosilsa: asl so'zni qaytaradi. */
    private fun undoAutoCorrect(ic: InputConnection): Boolean {
        val last = lastAutoCorrect ?: return false
        lastAutoCorrect = null
        val (original, inserted) = last
        val before = ic.getTextBeforeCursor(inserted.length, 0)?.toString() ?: return false
        if (before != inserted) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(inserted.length, 0)
        ic.commitText(original, 1)
        ic.endBatchEdit()
        suppressAutoFor = original
        return true
    }

    private fun onSuggestion(index: Int) {
        val word = stripCommit.getOrNull(index) ?: return
        val ic = currentInputConnection ?: return
        lastAutoCorrect = null
        typedThisSession = true
        try {
            val before = ic.getTextBeforeCursor(80, 0) ?: ""
            val ctx = ContextParser.parse(before)
            ic.beginBatchEdit()
            if (ctx.prefix.isNotEmpty()) ic.deleteSurroundingText(ctx.prefix.length, 0)
            ic.commitText("$word ", 1)
            ic.endBatchEdit()
            learnWord(ctx.history, word)
        } catch (t: Throwable) {
            return
        }
        justAccepted = true
        afterInput()
    }

    private fun learnFromCursor(ic: InputConnection) {
        if (!learnOn) return
        try {
            val before = ic.getTextBeforeCursor(80, 0) ?: return
            val ctx = ContextParser.parse(before)
            learnWord(ctx.history, ctx.prefix)
        } catch (t: Throwable) {
            // o'rganish yozishga xalaqit bermasligi kerak
        }
    }

    private fun learnWord(historyRaw: List<String>, wordRaw: String) {
        if (!learnOn) return
        val lang = langs[langIdx]
        val w = Norm.word(wordRaw, lang)
        if (w.length < 2 || w.length > 30 || !w.all { ContextParser.isWordChar(it) }) return
        val history = historyRaw.map { Norm.word(it, lang) }
        val pm = personalFor(lang)
        pm.learn(history, w)
        if (pm.pending >= 40) savePersonal()
    }

    private fun savePersonal() {
        for ((id, pm) in personals) {
            if (!pm.dirty) continue
            try {
                File(filesDir, "personal_$id.txt").writeText(pm.serialize())
                pm.markSaved()
            } catch (t: Throwable) {
                // joy yetmasa keyingi safar urinamiz
            }
        }
    }

    // ---------------------------------------------------------------- KeyboardListener

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        lastAutoCorrect = null
        typedThisSession = true
        val boundary = text.isNotEmpty() && !ContextParser.isWordChar(text[0])
        val corrected = if (boundary && text.length == 1 && text[0] in ",.!?;:") maybeAutoCorrect(ic) else null
        if (boundary) learnFromCursor(ic)
        if (justAccepted && text.length == 1 && text[0] in ",.!?;:") {
            if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(1, 0)
        }
        justAccepted = false
        ic.commitText(text, 1)
        if (corrected != null) lastAutoCorrect = corrected.first to corrected.second + text
        afterInput()
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        lastAutoCorrect = null
        typedThisSession = true
        val corrected = maybeAutoCorrect(ic)
        learnFromCursor(ic)
        justAccepted = false
        ic.commitText(" ", 1)
        if (corrected != null) lastAutoCorrect = corrected.first to corrected.second + " "
        afterInput()
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return
        typedThisSession = true
        justAccepted = false
        if (undoAutoCorrect(ic)) {
            updateAutoCap()
            refreshSuggestions()
            return
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        updateAutoCap()
        refreshSuggestions()
    }

    override fun onEnter() {
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        lastAutoCorrect = null
        typedThisSession = true
        maybeAutoCorrect(ic)
        learnFromCursor(ic)
        justAccepted = false
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        updateAutoCap()
        refreshSuggestions()
    }

    override fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        val quick = now - lastShiftTap < 350L
        setShift(
            when (shift) {
                Shift.OFF -> Shift.ONCE
                Shift.ONCE -> if (quick) Shift.CAPS else Shift.OFF
                Shift.CAPS -> Shift.OFF
            },
        )
        lastShiftTap = now
        refreshSuggestions()
    }

    override fun onShiftHold() {
        setShift(Shift.CAPS)
        refreshSuggestions()
    }

    override fun onPage(page: Page) {
        this.page = page
        applyLayout()
        if (page == Page.LETTERS) updateAutoCap()
        refreshSuggestions()
    }

    override fun onLanguageNext() {
        setLanguage((langIdx + 1) % langs.size)
    }

    override fun onLanguageSwipe(step: Int) {
        setLanguage((langIdx + step + langs.size) % langs.size)
    }

    override fun onLanguagePick(index: Int) {
        if (index < 0) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        } else {
            setLanguage(index.coerceIn(0, langs.size - 1))
        }
    }

    private fun setLanguage(i: Int) {
        langIdx = i
        prefs.lastLang = langs[i].id
        if (page != Page.LETTERS) page = Page.LETTERS
        applyLayout()
        updateAutoCap()
        refreshSuggestions()
    }

    override fun onEmoji() {
        root?.showEmoji(this)
    }

    private companion object {
        const val CLIPBOARD_FILE = "clipboard.txt"

        /** Shu vaqt ichida nusxalangan matn yozish boshida taklif qilinadi. */
        const val CLIP_FRESH_MS = 15 * 60 * 1000L
    }
}
