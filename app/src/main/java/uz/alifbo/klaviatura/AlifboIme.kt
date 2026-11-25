package uz.alifbo.klaviatura

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

class AlifboIme : InputMethodService(), KeyboardListener, ClipboardHandler, TranslateHandler, EditorHandler, VaultHandler {

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

    /** Oxirgi avtomatik tuzatish: asl so'z, qo'yilgan so'z va ortidan qo'yilgan probel/belgi. */
    private class AutoFix(val original: String, val fixed: String, val sep: String)

    private var lastAutoCorrect: AutoFix? = null

    /** Maydonga qöyilgan tarjima: natija, asl matn va u belgilangan matndan boshlanganmi (qaytariş uçun). */
    private class TrUndo(val result: String, val original: String, val fromSelection: Boolean)

    /** Foydalanuvchi qaytargan so'z: keyingi probelda qayta tuzatilmaydi. */
    private var suppressAutoFor: String? = null
    private var knownEpoch = 0

    // tinish belgilari atrofidagi probel va gap boshidagi bosh harf
    private val spacing = Spacing()
    private var autoCapOn = true
    private var proseField = false
    private var selActive = false

    /** Belgilar sahifasida belgi yozildi: probel/Enter'dan keyin harflarga qaytiladi. */
    private var symTyped = false
    private val models = java.util.concurrent.ConcurrentHashMap<String, LanguageModel>()
    private val personals = HashMap<String, PersonalModel>()
    private val suggesters = HashMap<String, Suggester>()

    // bufer (nusxalar tarixi)
    private var clipboard: ClipboardStore? = null
    private var clipboardOn = true
    private var typedThisSession = false
    private var clipboardEpoch = 0
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    // tarjima (to'g'ridan-to'g'ri, Tor'siz): tugmalar ilovaga emas, tarjima qatoriga yozadi
    private var trMode = false
    private val trBuf = TextBuffer(Translator.MAX_CHARS)

    // töliq ekran muharriri: tarjimada trBuf, aks holda ilova maydonidagi matn nusxasi
    private var editorOpen = false
    private val fieldBuf = TextBuffer(FIELD_MAX)

    /** Muharrir oçilganda maydonda turgan matn (saqlaşda maydon özgarmaganini tekşiriş uçun). */
    private var fieldOriginal: String? = null

    /** Tugmalar yozadigan içki matn; null bölsa — to'ğridan-to'ğri ilovaga. */
    private val box: TextBuffer?
        get() = when {
            trMode -> trBuf
            editorOpen -> fieldBuf
            else -> null
        }
    private var trFromClip = false
    private var trUndo: TrUndo? = null

    /** Matn belgilab ochilgan tarjima: natija belgilangan matn o'rniga yoziladi. */
    private var trFromSel = false
    private var trStatus: String? = null
    private var trError = false
    private var trBusy = false
    private var trSeq = 0
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    // parollar
    private var fieldKind = FieldKind.OTHER
    private var fieldPkg = ""
    private var incognito = false
    private var filledByVault = false
    private var unlockInFlight = false
    private var warnedNoLock = false

    /** Oxirgi tark etilgan qisqa matn maydoni (login bo'lishi mumkin): (ilova, matn, vaqt). Faqat xotirada. */
    private var lastUser: Triple<String, String, Long>? = null

    /** Joriy kirish maydonidagi oxirgi ko'rilgan matn (faqat xotirada, maydon almashganda tozalanadi). */
    private var liveText: String? = null

    /** Login to'ldirilgach keyingi parol maydoniga yoziladigan parol: (ilova, parol, vaqt). */
    private var queuedPass: Triple<String, String, Long>? = null
    private var genPassword: String? = null
    private var pendingVault: Pair<String, () -> Unit>? = null
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Vault.lock()
        }
    }

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
        try {
            ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (t: Throwable) {
            // ombor baribir vaqt o'tib qulflanadi
        }
    }

    override fun onCreateInputView(): View {
        closeEditor(true)
        val r = KeyboardRoot(this, this)
        r.strip.onPick = { onSuggestion(it) }
        r.strip.onTool = { tool ->
            when {
                tool == 0 -> toggleClipboardPanel()
                tool == 3 -> if (editorOpen) closeEditor(true) else openEditor()
                // tarjima matni muharririda «A文» bosilsa — faqat yig'iladi, matn yöqolmaydi
                tool == 1 && editorOpen && trMode -> closeEditor(true)
                else -> {
                    closeEditor(true)
                    if (tool == 1) toggleTranslate() else toggleVaultPanel()
                }
            }
        }
        r.strip.onClip = { pasteLatestClip() }
        r.strip.onAction = { onStripAction(it) }
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
            updateSecureFlag()
        }
    }

    override fun onClipPaste(text: String) {
        typedThisSession = true
        root?.hideClipboard()
        if (box != null) {
            insertIntoBox(text)
            return
        }
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
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

    // ---------------------------------------------------------------- tarjima (to'g'ridan-to'g'ri)

    private fun toggleTranslate() {
        if (trMode) closeTranslate() else openTranslate()
    }

    private fun openTranslate() {
        val r = root ?: return
        if (fieldKind.isPassword) {
            Toast.makeText(this, "Parol maydonida tarjima öçiq", Toast.LENGTH_SHORT).show()
            return
        }
        r.hideEmoji()
        r.hideClipboard()
        r.hideVault()
        trMode = true
        trBuf.clear()
        // belgilangan matn bo'lsa — o'shani tarjima qilamiz, natija uning o'rniga qo'yiladi
        val sel = try {
            currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
        } catch (t: Throwable) {
            ""
        }
        if (sel.isNotBlank()) trBuf.set(sel)
        trFromSel = sel.isNotBlank()
        trFromClip = false
        trUndo = null
        trStatus = null
        trError = false
        r.showTranslate(this)
        r.keyboard.enterStyle = EnterStyle.SEND
        boxAutoCap()
        renderTranslate()
        refreshSuggestions()
    }

    private fun closeTranslate() {
        if (!trMode) return
        editorOpen = false
        trMode = false
        trSeq++
        trBusy = false
        trBuf.clear()
        trUndo = null
        trStatus = null
        root?.hideTranslate()
        root?.keyboard?.enterStyle = enter
        updateAutoCap()
        refreshSuggestions()
    }

    /** Tarjima qatori va (ochiq bo'lsa) to'liq ekran muharririni qayta chizadi. */
    private fun renderTranslate() {
        if (trMode) {
            root?.trBar?.render(
                TrState(trSourceName(), Translator.shortName(prefs.trTarget), trBuf.text, trBusy, trStatus, trError, trBuf.cursor),
            )
        }
        renderEditor()
    }

    private fun trSourceName(): String = if (trFromClip) "Avto" else langs[langIdx].short

    /** Ichki matn o'zgardi: eski holat xabari (va qaytariş) endi mos emas. */
    private fun boxChanged() {
        if (trMode) {
            if (!trBusy) {
                trUndo = null
                trStatus = null
                trError = false
            }
            if (trBuf.isEmpty()) trFromClip = false
        }
        cursorMoved()
    }

    private fun cursorMoved() {
        if (shift == Shift.ONCE) setShift(Shift.OFF)
        boxAutoCap()
        renderTranslate()
        refreshSuggestions()
    }

    private fun boxAutoCap() {
        val own = box ?: return
        if (page != Page.LETTERS || shift == Shift.CAPS || !autoCapOn) return
        if (!trMode) {
            val info = currentInputEditorInfo ?: return
            if (Punct.capMode(info.inputType) == Punct.CAP_NONE) return
        }
        val want = if (Punct.isSentenceStart(own.before())) Shift.ONCE else Shift.OFF
        if (shift != want) setShift(want)
    }

    private fun keyEnterStyle(): EnterStyle = when {
        editorOpen -> EnterStyle.RETURN
        trMode -> EnterStyle.SEND
        else -> enter
    }

    override fun onTrSend() {
        if (trBusy) return
        val text = trBuf.text.trim()
        if (text.isEmpty()) return
        val mode = prefs.trEngine
        val key = Translator.keyFor(this, mode)
        if (key.isNullOrBlank()) {
            trStatus = if (mode == Translator.DEFAULT) {
                "Standart kalit yöq: sozlamalarga (bosing)"
            } else {
                "Tarjima kaliti yöq: sozlamalarga (bosing)"
            }
            trError = true
            renderTranslate()
            return
        }
        val region = prefs.azureRegion
        val target = prefs.trTarget
        val (source, payload) = Translator.prepareSource(text, langs[langIdx], trFromClip)
        trBusy = true
        trError = false
        trUndo = null
        trStatus = "Tarjima qilinmoqda…"
        val seq = ++trSeq
        renderTranslate()
        worker.execute {
            var result: String? = null
            var error: String? = null
            try {
                result = Translator.finish(Translator.translate(mode, key, region, payload, source, target), target)
            } catch (e: Translator.Failure) {
                error = e.userMessage
            } catch (e: InterruptedException) {
                return@execute
            } catch (e: Exception) {
                error = "Tarjima bölmadi"
            }
            val res = result
            val err = error
            ui.post {
                if (seq != trSeq) return@post
                trBusy = false
                if (res != null) {
                    insertTranslation(res, text)
                } else {
                    trStatus = err
                    trError = true
                    renderTranslate()
                }
            }
        }
    }

    /**
     * Tayyor tarjima klaviatura içida emas, to'ğridan-to'ğri yozilayotgan maydonga yoziladi
     * (belgilangan matn bo'lsa — uning o'rniga). Qatorda qisqa «qaytariş» xabari qoladi.
     */
    private fun insertTranslation(res: String, original: String) {
        val ic = currentInputConnection
        if (ic == null) {
            // maydon yöq: natija yöqolmasin
            try {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tarjima", res))
                trStatus = "Maydon yöq: tarjima nusxalandi"
            } catch (t: Throwable) {
                trStatus = "Maydon yöq, qöyib bölmadi"
            }
            trError = true
            renderTranslate()
            return
        }
        typedThisSession = true
        lastAutoCorrect = null
        spacing.reset()
        ic.commitText(res, 1)
        trUndo = TrUndo(res, original, trFromSel)
        trFromSel = false
        // yuborilgandan keyin yozilgan yangi matn saqlanadi
        if (trBuf.text.trim() == original) {
            trBuf.clear()
            trFromClip = false
        }
        trStatus = "✓ Qöyildi · qaytariş"
        trError = false
        boxAutoCap()
        renderTranslate()
        refreshSuggestions()
    }

    /** Oxirgi qöyilgan tarjimani olib tashlab, asl matnni qaytaradi (kursor özgarmagan bölsa). */
    private fun undoTranslation() {
        val u = trUndo ?: return
        val ic = currentInputConnection
        val before = try {
            ic?.getTextBeforeCursor(u.result.length, 0)?.toString()
        } catch (t: Throwable) {
            null
        }
        if (ic == null || before != u.result) {
            trUndo = null
            trStatus = "Qaytarib bölmadi: matn özgargan"
            trError = true
            renderTranslate()
            return
        }
        ic.deleteSurroundingText(u.result.length, 0)
        spacing.reset()
        trUndo = null
        if (u.fromSelection) {
            ic.commitText(u.original, 1)
            trStatus = "↩ Qaytarildi"
        } else {
            trBuf.set(u.original)
            trStatus = null
        }
        trError = false
        trFromSel = false
        boxAutoCap()
        renderTranslate()
        refreshSuggestions()
    }

    override fun onTrClose() {
        closeTranslate()
    }

    override fun onTrLangs() {
        root?.showTrLangs(
            langs,
            langIdx,
            prefs.trTarget,
            onSource = { i ->
                root?.hideTrLangs()
                if (i != langIdx) setLanguage(i) else renderTranslate()
            },
            onTarget = { code ->
                prefs.trTarget = code
                trUndo = null
                if (!trBusy) {
                    trStatus = null
                    trError = false
                }
                root?.hideTrLangs()
                renderTranslate()
            },
        )
    }

    /** Tillarni almashtiradi: maqsad tili klaviaturada bo'lsa, klaviatura shu tilga o'tadi. */
    override fun onTrSwap() {
        val tgt = Lang.byId(prefs.trTarget)
        val i = if (tgt == null) -1 else langs.indexOf(tgt)
        if (i < 0) {
            trStatus = "Bu til klaviaturada yöq: tilni tanlang"
            trError = false
            renderTranslate()
            return
        }
        prefs.trTarget = langs[langIdx].id
        trUndo = null
        trFromClip = false
        if (!trBusy) {
            trStatus = null
            trError = false
        }
        setLanguage(i)
    }

    override fun onTrPasteClip() {
        pasteIntoBox()
    }

    override fun onTrCursor(position: Int) {
        trBuf.moveTo(position)
        cursorMoved()
    }

    override fun onTrStatusTap() {
        if (trUndo != null) {
            undoTranslation()
            return
        }
        if (trBusy) return
        if (!(trError && trStatus?.contains("kalit") == true)) {
            // oddiy xabar: yopiladi, yozilgan matn qaytib körinadi
            trStatus = null
            trError = false
            renderTranslate()
            return
        }
        run {
            try {
                startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) {
                // sozlamalar ochilmadi
            }
        }
    }

    // ---------------------------------------------------------------- ichki matnga qo'yish

    /** Tizim buferidagi matn (bo'lmasa — tarixdagi oxirgisi) va u maxfiy deb belgilanganmi. */
    private fun currentClip(): Pair<String, Boolean>? {
        try {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this)?.toString()
                val secret = clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
                if (!text.isNullOrEmpty()) return text to secret
            }
        } catch (t: Throwable) {
            // tarixdagisini ko'ramiz
        }
        val saved = clipboardStore().latest()?.text
        return if (saved.isNullOrEmpty()) null else saved to false
    }

    private fun pasteIntoBox() {
        if (box == null) return
        val clip = currentClip()
        if (clip == null) {
            notice("Buferda matn yöq")
            return
        }
        if (trMode && clip.second) {
            notice("Maxfiy nusxa tarjimaga qöyilmaydi")
            return
        }
        insertIntoBox(clip.first)
    }

    /** Matnni kursor joyiga qo'yadi. */
    private fun insertIntoBox(text: String) {
        val own = box ?: return
        val added = own.paste(text)
        // matnning kamida yarmi qo'yilgan bo'lsa, manba tilini xizmat o'zi aniqlaydi
        if (trMode && added > 0 && added >= own.length - added) {
            trFromClip = true
            trFromSel = false
        }
        boxChanged()
        if (added < text.length) toast("Matn juda uzun: faqat boş qismi qöyildi")
    }

    private fun notice(message: String) {
        if (trMode && !editorOpen) {
            trStatus = message
            trError = false
            renderTranslate()
        } else {
            toast(message)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun copyText(text: String): Boolean = try {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Matn", text))
        true
    } catch (t: Throwable) {
        false
    }

    // ---------------------------------------------------------------- to'liq ekran muharriri

    /** Qatordagi to'liq ekran tugmasi: matn uzun bo'lganda (yoki muharrir ochiq turganda). */
    private fun canExpand(ic: InputConnection?): Boolean {
        if (editorOpen) return true
        if (trMode) return trBuf.length > EXPAND_TRANSLATE || trBuf.text.contains('\n')
        if (fieldKind.isPassword || ic == null) return false
        val info = currentInputEditorInfo ?: return false
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return try {
            val before = ic.getTextBeforeCursor(EXPAND_FIELD, 0) ?: ""
            val after = ic.getTextAfterCursor(EXPAND_FIELD, 0) ?: ""
            before.length + after.length >= EXPAND_FIELD || before.contains('\n') || after.contains('\n')
        } catch (t: Throwable) {
            false
        }
    }

    /** Ilova maydonidagi butun matn va kursor o'rni; to'liq o'qib bo'lmasa — null. */
    private fun readField(ic: InputConnection): Pair<String, Int>? = try {
        val before = ic.getTextBeforeCursor(FIELD_MAX, 0)?.toString()
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(FIELD_MAX, 0)?.toString()
        if (before == null || after == null) {
            null
        } else {
            val full = before + selected + after
            val whole = fieldText(ic)
            if (full.length >= FIELD_MAX || (whole != null && whole.length > full.length)) {
                null
            } else {
                full to before.length + selected.length
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun openEditor() {
        val r = root ?: return
        if (editorOpen || fieldKind.isPassword) return
        if (!trMode) {
            val ic = currentInputConnection ?: return
            val field = readField(ic)
            if (field == null) {
                toast("Bu maydondagi matnni töliq öqib bölmadi")
                return
            }
            fieldOriginal = field.first
            fieldBuf.set(field.first, field.second)
        }
        r.hideEmoji()
        r.hideClipboard()
        r.hideVault()
        r.hideTrLangs()
        updateSecureFlag()
        editorOpen = true
        spacing.reset()
        lastAutoCorrect = null
        r.showEditor(this)
        r.keyboard.enterStyle = keyEnterStyle()
        boxAutoCap()
        renderTranslate()
        refreshSuggestions()
    }

    private fun closeEditor(apply: Boolean) {
        if (!editorOpen) return
        editorOpen = false
        root?.hideEditor()
        if (!trMode) {
            if (apply) applyField()
            fieldOriginal = null
            fieldBuf.clear()
        }
        root?.keyboard?.enterStyle = keyEnterStyle()
        updateAutoCap()
        renderTranslate()
        refreshSuggestions()
    }

    /** Tahrirlangan matnni maydonga yozadi: faqat o'zgargan qismi almashtiriladi. */
    private fun applyField() {
        val original = fieldOriginal ?: return
        val edited = fieldBuf.text
        val ic = currentInputConnection
        val now = if (ic == null) null else readField(ic)?.first
        if (ic == null || now != original) {
            // maydon almashgan yoki o'zgargan: ustidan yozmaymiz, matn ham yo'qolmaydi
            if (edited != original && copyText(edited)) toast("Maydon özgargan: tahrirlangan matn nusxalandi")
            return
        }
        val change = TextChange.between(original, edited)
        ic.beginBatchEdit()
        if (change != null) {
            ic.setSelection(change.start, change.end)
            ic.commitText(change.insert, 1)
        }
        ic.setSelection(fieldBuf.cursor, fieldBuf.cursor)
        ic.endBatchEdit()
        typedThisSession = true
        spacing.reset()
        lastAutoCorrect = null
    }

    private fun renderEditor() {
        if (!editorOpen) return
        val own = box ?: return
        val title = if (trMode) {
            "${trSourceName()} → ${Translator.shortName(prefs.trTarget)} · ${own.length}/${Translator.MAX_CHARS}"
        } else {
            "Tahrirlaş · ${own.length} belgi"
        }
        root?.editor?.render(EditorState(title, own.text, own.cursor, trMode))
    }

    override fun onEditorDone() {
        closeEditor(true)
    }

    override fun onEditorCancel() {
        closeEditor(false)
    }

    override fun onEditorPaste() {
        pasteIntoBox()
    }

    override fun onEditorCopy() {
        val text = box?.text
        if (!text.isNullOrEmpty() && copyText(text)) toast("✓ Nusxalandi")
    }

    override fun onEditorSend() {
        closeEditor(true)
        onTrSend()
    }

    override fun onEditorCursor(position: Int) {
        box?.moveTo(position)
        cursorMoved()
    }

    /** Muharrir ekranni egallaganda ilova oynasi siljimaydi, teginishlar esa muharrirga boradi. */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val r = root ?: return
        if (!r.isEditorShown || r.height == 0) return
        val at = IntArray(2)
        r.getLocationInWindow(at)
        val top = at[1] + r.editorHeight
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(at[0], at[1], at[0] + r.width, at[1] + r.height)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && editorOpen && isInputViewShown) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // «Orqaga» avval muharrirni yopadi (matn saqlanadi), klaviaturani emas
        if (keyCode == KeyEvent.KEYCODE_BACK && editorOpen && isInputViewShown) {
            closeEditor(true)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- parollar

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }

    private fun fieldText(ic: InputConnection): String? = try {
        ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
            ?: run {
                val b = ic.getTextBeforeCursor(1000, 0)
                val a = ic.getTextAfterCursor(1000, 0)
                if (b == null && a == null) null else "${b ?: ""}${a ?: ""}"
            }
    } catch (t: Throwable) {
        null
    }

    /** Maydondagi butun matnni [text] bilan almashtiradi. */
    private fun replaceField(ic: InputConnection, text: String) {
        val len = fieldText(ic)?.length ?: 0
        ic.beginBatchEdit()
        ic.finishComposingText()
        if (len > 0) ic.setSelection(0, len)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    /**
     * Kirish maydonidagi matnni xotirada kuzatib boradi: maydondan chiqilganda ulanish allaqachon
     * yopilgan bo'lishi mumkin, shuning uchun oxirgi ko'rilgan holat ishlatiladi.
     */
    private fun trackField() {
        if (fieldKind == FieldKind.OTHER || fieldPkg.isEmpty() || fieldPkg == packageName) return
        val ic = currentInputConnection ?: return
        fieldText(ic)?.let { liveText = it }
    }

    /** Maydondan chiqilganda: login bo'lsa eslab turadi, parol bo'lsa (login bilan birga) shifrlab saqlaydi. */
    private fun captureCredential() {
        if (fieldKind == FieldKind.OTHER || fieldPkg.isEmpty() || fieldPkg == packageName) return
        if (incognito || unlockInFlight || !prefs.vaultAutoSave) return
        val fresh = currentInputConnection?.let { fieldText(it) }
        val text = fresh?.takeIf { it.isNotEmpty() } ?: liveText ?: return
        val now = System.currentTimeMillis()
        when (fieldKind) {
            FieldKind.USER, FieldKind.SHORT_TEXT -> {
                val t = text.trim()
                if (t.isNotEmpty() && t.length <= 100 && '\n' !in t) lastUser = Triple(fieldPkg, t, now)
            }
            FieldKind.PASSWORD, FieldKind.NEW_PASSWORD -> {
                if (filledByVault || text.length < 4 || text.length > 200) return
                val user = lastUser?.takeIf { it.first == fieldPkg && now - it.third < 10 * 60_000L }?.second ?: ""
                rememberCredential(user, text)
            }
            else -> {}
        }
    }

    private fun rememberCredential(user: String, pass: String) {
        try {
            if (Vault.isSaved(this, fieldPkg, user, pass)) return
            Vault.remember(this, Cred(Vault.newId(), fieldPkg, appLabel(fieldPkg), user, pass, System.currentTimeMillis()))
            Toast.makeText(this, "Söz+ parolni şifrlab eslab qoldi", Toast.LENGTH_SHORT).show()
        } catch (e: VaultCrypto.NoScreenLock) {
            if (!warnedNoLock) {
                warnedNoLock = true
                Toast.makeText(this, "Parolni saqlaş uçun telefonda ekran qulfi (PIN) kerak", Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) {
            // saqlanmadi — yozishga xalaqit bermaydi
        }
    }

    /** Ombor ochiq bo'lsa [action]; bo'lmasa telefon qulfini so'raydi va klaviatura qayta ochilganda bajaradi. */
    private fun withVault(action: () -> Unit) {
        when (Vault.tryOpen(this)) {
            Vault.Open.OPEN -> action()
            Vault.Open.NEED_AUTH -> {
                pendingVault = fieldPkg to action
                unlockInFlight = true
                VaultBridge.requestUnlock(this) { ok -> onUnlockResult(ok) }
            }
            Vault.Open.NO_LOCK -> Toast.makeText(this, "Parollar uçun avval telefonda ekran qulfini (PIN yoki grafik kalit) qöying", Toast.LENGTH_LONG).show()
            Vault.Open.BROKEN -> Toast.makeText(this, "Parollar kaliti bekor bölgan (ekran qulfi öçirilganmi?). Sozlamalardan zaxiradan tiklang.", Toast.LENGTH_LONG).show()
        }
    }

    /** Qulf ochildi: ombor ochiladi, kutilgan amal klaviatura qayta ko'ringanda bajariladi. */
    private fun onUnlockResult(ok: Boolean) {
        if (ok) {
            Vault.tryOpen(applicationContext)
        } else {
            pendingVault = null
        }
    }

    private fun vaultState(note: String? = null): VaultState {
        val g = genPassword ?: PasswordGen.generate().also { genPassword = it }
        val all = Vault.all()
        return VaultState(Vault.isOpen, all.filter { it.app == fieldPkg }, all.filter { it.app != fieldPkg }, g, note)
    }

    private fun showVaultPanel() {
        val r = root ?: return
        closeEditor(true)
        closeTranslate()
        r.showVault(vaultState(), this)
        updateSecureFlag()
    }

    private fun toggleVaultPanel() {
        val r = root ?: return
        if (r.isVaultShown) {
            r.hideVault()
            updateSecureFlag()
            return
        }
        // telefon yaqinda ochilgan bo'lsa, so'roqsiz ochiladi; aks holda panel "Oçiş" tugmasi bilan chiqadi
        val note = when (Vault.tryOpen(this)) {
            Vault.Open.NO_LOCK -> "Parollarni saqlaş uçun telefonda ekran qulfi (PIN yoki grafik kalit) kerak."
            Vault.Open.BROKEN -> "Ombor kaliti bekor bölgan: Söz+ sozlamalaridan zaxira nusxadan tiklang."
            else -> null
        }
        closeTranslate()
        r.showVault(vaultState(note), this)
        updateSecureFlag()
    }

    private fun refreshVaultPanel() {
        root?.updateVault(vaultState())
    }

    override fun onVaultFill(c: Cred) {
        fillCred(c)
    }

    private fun fillCred(c: Cred) {
        val ic = currentInputConnection ?: return
        root?.hideVault()
        updateSecureFlag()
        typedThisSession = true
        if (fieldKind.isPassword) {
            replaceField(ic, c.pass)
            filledByVault = true
        } else {
            if (c.user.isNotEmpty()) replaceField(ic, c.user)
            if (c.pass.isNotEmpty()) queuedPass = Triple(fieldPkg, c.pass, System.currentTimeMillis())
            val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
            if (action == EditorInfo.IME_ACTION_NEXT) ic.performEditorAction(action)
        }
        updateAutoCap()
        refreshSuggestions()
    }

    override fun onVaultDelete(c: Cred) {
        try {
            Vault.delete(this, c.id)
        } catch (t: Throwable) {
            // o'chmadi
        }
        refreshVaultPanel()
        refreshSuggestions()
    }

    override fun onVaultUseGenerated(password: String) {
        val ic = currentInputConnection ?: return
        root?.hideVault()
        updateSecureFlag()
        typedThisSession = true
        if (fieldKind.isPassword) replaceField(ic, password) else ic.commitText(password, 1)
        refreshSuggestions()
    }

    override fun onVaultRegenerate() {
        genPassword = PasswordGen.generate()
        refreshVaultPanel()
        refreshSuggestions()
    }

    override fun onVaultUnlock() {
        withVault { showVaultPanel() }
    }

    override fun onVaultLock() {
        Vault.lock()
        refreshVaultPanel()
        refreshSuggestions()
    }

    /** Kirish maydonlarida qatordagi tugmalar: saqlangan parol va (ba'zan) kuchli parol taklifi. */
    private fun credentialActions(ic: InputConnection?): List<StripAction> {
        if (!fieldKind.isLogin || fieldPkg.isEmpty() || fieldPkg == packageName) return emptyList()
        val list = ArrayList<StripAction>(2)
        if (Vault.isOpen) {
            val mine = Vault.forApp(fieldPkg)
            if (mine.size == 1) {
                list.add(StripAction(ACT_FILL, StripAction.ICON_KEY, mine[0].user.ifEmpty { "Saqlangan parol" }))
            } else if (mine.size > 1) {
                list.add(StripAction(ACT_OPEN, StripAction.ICON_KEY, "${mine.size} ta saqlangan kiriş"))
            }
        } else if (Vault.hasEntriesFor(this, fieldPkg)) {
            list.add(StripAction(ACT_OPEN, StripAction.ICON_KEY, "Saqlangan parol"))
        }
        if (fieldKind.isPassword && prefs.vaultSuggest) {
            val typed = liveText ?: (if (ic != null) fieldText(ic).orEmpty() else "")
            val g = genPassword
            val weak = typed.length >= 4 && typed != g && PasswordGen.strength(typed) <= 2
            if ((fieldKind == FieldKind.NEW_PASSWORD && typed.isEmpty()) || weak) {
                val p = g ?: PasswordGen.generate().also { genPassword = it }
                list.add(StripAction(ACT_GEN, StripAction.ICON_DICE, if (weak) "Kuçliroq: $p" else p, accent = true))
            }
        }
        return list
    }

    private fun onStripAction(id: Int) {
        when (id) {
            ACT_FILL -> withVault {
                val mine = Vault.forApp(fieldPkg)
                if (mine.size == 1) fillCred(mine[0]) else showVaultPanel()
            }
            ACT_OPEN -> withVault {
                val mine = Vault.forApp(fieldPkg)
                if (mine.size == 1) fillCred(mine[0]) else showVaultPanel()
            }
            ACT_GEN -> genPassword?.let { onVaultUseGenerated(it) }
        }
    }

    /** Parol maydonida va parollar panelida klaviatura oynasini ekran rasmi/yozuvidan yashiradi. */
    private fun updateSecureFlag() {
        val w = window?.window ?: return
        val secure = fieldKind.isPassword || root?.isVaultShown == true
        try {
            if (secure) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (t: Throwable) {
            // ba'zi tizimlarda IME oynasiga ruxsat yo'q
        }
    }

    // ---------------------------------------------------------------- kirish

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val pkg = attribute.packageName ?: ""
        if (pkg != fieldPkg) genPassword = null
        fieldPkg = pkg
        fieldKind = if (attribute.inputType == InputType.TYPE_NULL) FieldKind.OTHER else FieldKind.of(attribute)
        incognito = attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        if (!restarting) {
            filledByVault = false
            liveText = null
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        unlockInFlight = false
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
        configureSpacing(info)
        if (!restarting) typedThisSession = false
        clipboardOn = prefs.clipboardHistory && !isSensitiveField(info)
        configurePrediction(info)
        if (!restarting || fieldKind.isPassword) closeEditor(true)
        if (fieldKind.isPassword) closeTranslate()
        root?.hideEmoji()
        root?.hideClipboard()
        root?.hideVault()
        applyPrefs()
        applyLayout()
        captureClipboard()
        fillQueuedPassword()
        runPendingVault()
        trackField()
        updateSecureFlag()
        updateAutoCap()
        refreshSuggestions()
    }

    /** Login to'ldirilib "Keyingi" bosilgan bo'lsa, parol maydoniga parolni yozadi. */
    private fun fillQueuedPassword() {
        val (pkg, pass, time) = queuedPass ?: return
        if (!fieldKind.isPassword) return
        queuedPass = null
        if (pkg != fieldPkg || System.currentTimeMillis() - time > 20_000L) return
        val ic = currentInputConnection ?: return
        if (!fieldText(ic).isNullOrEmpty()) return
        ic.commitText(pass, 1)
        filledByVault = true
    }

    private fun runPendingVault() {
        val (pkg, action) = pendingVault ?: return
        if (!Vault.isOpen) return
        pendingVault = null
        if (pkg == fieldPkg) action()
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
        closeEditor(true)
        captureCredential()
        savePersonal()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        closeEditor(true)
        closeTranslate()
        root?.hideEmoji()
        root?.hideClipboard()
        root?.hideVault()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        savePersonal()
        saveClipboard()
        trSeq++
        worker.shutdownNow()
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        } catch (t: Throwable) {
            // e'tiborsiz
        }
        try {
            unregisterReceiver(screenOff)
        } catch (t: Throwable) {
            // ro'yxatda yo'q
        }
        Vault.lock()
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
        selActive = newSelStart != newSelEnd
        trackField()
        if (box == null) refreshSuggestions()
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
        k.enterStyle = keyEnterStyle()
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

    /**
     * Gap boshida (maydon boshi, yangi qator, ". ! ?" va probeldan keyin) bosh harf. Gap boshini o'zimiz
     * aniqlaymiz: ilovaning getCursorCapsMode javobi ba'zi maydonlarda (bayroq unutilgan ko'p qatorli
     * maydonlar) doim 0 bo'ladi.
     */
    private fun updateAutoCap() {
        if (box != null) {
            boxAutoCap()
            return
        }
        if (page != Page.LETTERS || shift == Shift.CAPS || !autoCapOn) return
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = try {
            when (Punct.capMode(info.inputType)) {
                Punct.CAP_SENTENCES -> {
                    val before = ic.getTextBeforeCursor(CAP_LOOKBACK, 0)
                    if (before == null) ic.getCursorCapsMode(info.inputType) != 0 else Punct.isSentenceStart(before)
                }
                Punct.CAP_SYSTEM -> ic.getCursorCapsMode(info.inputType) != 0
                else -> false
            }
        } catch (t: Throwable) {
            false
        }
        val want = if (caps) Shift.ONCE else Shift.OFF
        if (shift != want) setShift(want)
    }

    private fun afterInput() {
        if (shift == Shift.ONCE) setShift(Shift.OFF)
        updateAutoCap()
        refreshSuggestions()
    }

    // ---------------------------------------------------------------- tinish va probel

    private class IcField(private val ic: InputConnection) : Spacing.Field {
        override fun before(n: Int): CharSequence? = try {
            ic.getTextBeforeCursor(n, 0)
        } catch (t: Throwable) {
            null
        }

        override fun after(n: Int): CharSequence? = try {
            ic.getTextAfterCursor(n, 0)
        } catch (t: Throwable) {
            null
        }

        override fun delete(n: Int) {
            ic.deleteSurroundingText(n, 0)
        }

        override fun commit(text: String) {
            ic.commitText(text, 1)
        }
    }

    private fun configureSpacing(info: EditorInfo) {
        spacing.reset()
        spacing.autoSpace = prefs.autoSpace
        spacing.doublePeriod = prefs.doubleSpacePeriod
        autoCapOn = prefs.autoCap
        proseField = Punct.isProse(info.inputType)
        selActive = info.initialSelStart >= 0 && info.initialSelStart != info.initialSelEnd
        symTyped = false
    }

    /** Belgilar sahifasida belgi yozilib, probel/Enter bosilsa — harflarga qaytamiz (Gboard'dagidek). */
    private fun leaveSymbols() {
        if (page == Page.LETTERS || !symTyped) return
        val info = currentInputEditorInfo ?: return
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return
        symTyped = false
        page = Page.LETTERS
        applyLayout()
    }

    private fun isPunct(c: Char): Boolean = c in Punct.SPACE_AFTER || c in Punct.CLOSERS || c == '"'

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
        val ic = currentInputConnection
        val own = box
        strip.expandable = canExpand(ic)
        // kirish maydonlarida: saqlangan parol va kuchli parol taklifi (takliflar o'rnida)
        val acts = if (own != null) emptyList() else try {
            credentialActions(ic)
        } catch (t: Throwable) {
            emptyList()
        }
        strip.actions = acts
        if (acts.isNotEmpty()) {
            strip.clipText = null
            clearStrip(strip)
            return
        }
        val before: CharSequence? = if (own != null) {
            own.before().takeLast(80)
        } else {
            try {
                ic?.getTextBeforeCursor(80, 0)
            } catch (t: Throwable) {
                null
            }
        }
        // yozish boshlanmagan bo'lsa: oxirgi nusxa (takliflar o'rnida)
        strip.clipText = if (own != null) null else try {
            clipSuggestion(before)
        } catch (t: Throwable) {
            null
        }
        val predicting = if (own != null) prefs.predict else predictOn
        if (!predicting || page != Page.LETTERS) {
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
            val auto = if (own == null && autoCorrectOn && ctx.prefix.length >= 2 && ctx.prefix != suppressAutoFor) {
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
            // inglizcha "i", "i'm" — doim "I", "I'm"
            val pronoun = if (langs[langIdx] == Lang.EN) Punct.englishI(raw) else null
            if (pronoun == null && raw.length < 2) return null
            if (raw == suppressAutoFor) {
                suppressAutoFor = null
                return null
            }
            val cased = pronoun ?: run {
                val fixed = suggesterFor(langs[langIdx]).autoCorrect(ctx, fuzzyOn) ?: return null
                Suggester.caseLike(fixed, raw)
            }
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

    /**
     * Avtomatik tuzatishdan keyin darhol orqaga o'chirish bosilsa: asl so'zni qaytaradi. Probelda tuzatilgan
     * bo'lsa — so'zning o'zi (keyingi probelda qayta tuzatilmaydi); tinish belgisida — belgi va probel saqlanadi.
     */
    private fun undoAutoCorrect(ic: InputConnection): Boolean {
        val last = lastAutoCorrect ?: return false
        lastAutoCorrect = null
        val inserted = last.fixed + last.sep
        val before = ic.getTextBeforeCursor(inserted.length, 0)?.toString() ?: return false
        if (before != inserted) return false
        val restore = if (last.sep == " ") last.original else last.original + last.sep
        ic.beginBatchEdit()
        ic.deleteSurroundingText(inserted.length, 0)
        ic.commitText(restore, 1)
        ic.endBatchEdit()
        if (last.sep == " ") {
            suppressAutoFor = last.original
        } else if (restore.endsWith(' ')) {
            spacing.afterRestore(IcField(ic))
        }
        return true
    }

    private fun onSuggestion(index: Int) {
        val word = stripCommit.getOrNull(index) ?: return
        val own = box
        if (own != null) {
            own.pick(word)
            boxChanged()
            return
        }
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
            // taklif ortidagi probel: keyin probel bosilsa ikkinchisi qo'shilmaydi, tinish belgisi oldiga o'tadi
            spacing.afterPick(IcField(ic))
            learnWord(ctx.history, word)
        } catch (t: Throwable) {
            return
        }
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
        if (page != Page.LETTERS) symTyped = true
        val own = box
        if (own != null) {
            if (own.type(text, spacing.autoSpace)) leaveSymbols()
            boxChanged()
            return
        }
        val ic = currentInputConnection ?: return
        lastAutoCorrect = null
        typedThisSession = true
        val p = text.singleOrNull()
        if (p == null || !isPunct(p) || selActive) {
            spacing.reset()
            if (text.isNotEmpty() && !ContextParser.isWordChar(text[0])) learnFromCursor(ic)
            ic.commitText(text, 1)
            afterInput()
            return
        }
        val f = IcField(ic)
        // "so'z ." → "so'z. ", "Nima? !" → "Nima?! ": oldidagi probel belgining ortiga o'tadi.
        // (batch edit ataylab yo'q: ba'zi maydonlar — masalan Chrome — batch ichida o'qishga eski matnni beradi)
        val moved = spacing.beforePunct(f, p)
        val corrected = if (moved == Spacing.MOVED_NONE && p in Punct.SPACE_AFTER) maybeAutoCorrect(ic) else null
        if (moved == Spacing.MOVED_NONE) learnFromCursor(ic)
        val committed = spacing.commitPunct(f, p, proseField, moved)
        if (corrected != null) lastAutoCorrect = AutoFix(corrected.first, corrected.second, committed)
        if (committed.endsWith(' ')) leaveSymbols()
        afterInput()
    }

    override fun onSpace() {
        val own = box
        if (own != null) {
            own.space()
            leaveSymbols()
            boxChanged()
            return
        }
        val ic = currentInputConnection ?: return
        lastAutoCorrect = null
        typedThisSession = true
        val f = IcField(ic)
        val now = SystemClock.uptimeMillis()
        // avtomatik qo'yilgan probeldan keyin probel — ikkinchisi qo'shilmaydi; tez ikki probel — ". "
        val res = if (selActive) Spacing.SpaceResult.NORMAL else spacing.onSpace(f, now)
        if (res == Spacing.SpaceResult.NORMAL) {
            val corrected = maybeAutoCorrect(ic)
            learnFromCursor(ic)
            ic.commitText(" ", 1)
            spacing.afterSpace(f, now)
            if (corrected != null) lastAutoCorrect = AutoFix(corrected.first, corrected.second, " ")
        }
        leaveSymbols()
        afterInput()
    }

    override fun onDelete() {
        val own = box
        if (own != null) {
            own.delete()
            boxChanged()
            return
        }
        val ic = currentInputConnection ?: return
        typedThisSession = true
        spacing.reset()
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
        if (editorOpen) {
            box?.newline()
            leaveSymbols()
            boxChanged()
            return
        }
        if (trMode) {
            onTrSend()
            return
        }
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        lastAutoCorrect = null
        typedThisSession = true
        // oxirdagi avtomatik probel ("Rahmat. ") yuborishdan/yangi qatordan oldin olinadi
        if (!spacing.beforeEnter(IcField(ic))) {
            maybeAutoCorrect(ic)
            learnFromCursor(ic)
        }
        leaveSymbols()
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
        symTyped = false
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
        renderTranslate()
        refreshSuggestions()
    }

    override fun onEmoji() {
        root?.showEmoji(this)
    }

    private companion object {
        const val CLIPBOARD_FILE = "clipboard.txt"

        /** Muharrirda oçiladigan matnning eng katta uzunligi. */
        const val FIELD_MAX = 50_000

        /** Töliq ekran tugmasi şu uzunlikdan boşlab (yoki yangi qator bölsa) çiqadi. */
        const val EXPAND_FIELD = 60
        const val EXPAND_TRANSLATE = 20

        /** Shu vaqt ichida nusxalangan matn yozish boshida taklif qilinadi. */
        const val CLIP_FRESH_MS = 15 * 60 * 1000L

        const val ACT_FILL = 1
        const val ACT_OPEN = 2
        const val ACT_GEN = 3

        /** Gap boshini aniqlash uchun kursordan oldingi shuncha belgi o'qiladi. */
        const val CAP_LOOKBACK = 80
    }
}
