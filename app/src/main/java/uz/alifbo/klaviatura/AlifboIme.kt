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

class AlifboIme : InputMethodService(), KeyboardListener, ClipboardHandler, TranslateHandler, VaultHandler {

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

    // bufer (nusxalar tarixi)
    private var clipboard: ClipboardStore? = null
    private var clipboardOn = true
    private var typedThisSession = false
    private var clipboardEpoch = 0
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    // tarjima (Tor orqali): tugmalar ilovaga emas, tarjima qatoriga yozadi
    private var trMode = false
    private val trBuf = StringBuilder()
    private var trFromClip = false
    private var trResult: String? = null
    private var trStatus: String? = null
    private var trError = false
    private var trOrbotHint = false
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
        val r = KeyboardRoot(this, this)
        r.strip.onPick = { onSuggestion(it) }
        r.strip.onTool = { tool ->
            when (tool) {
                0 -> toggleClipboardPanel()
                1 -> toggleTranslate()
                else -> toggleVaultPanel()
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
        if (trMode) {
            trBuf.append(text)
            trFromClip = true
            trChanged()
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

    // ---------------------------------------------------------------- tarjima (Tor orqali)

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
        trBuf.setLength(0)
        // belgilangan matn bo'lsa — o'shani tarjima qilamiz, natija uning o'rniga qo'yiladi
        val sel = try {
            currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
        } catch (t: Throwable) {
            ""
        }
        if (sel.isNotBlank()) trBuf.append(sel)
        trFromClip = false
        trResult = null
        trStatus = null
        trError = false
        trOrbotHint = false
        r.showTranslate(this)
        r.keyboard.enterStyle = EnterStyle.SEND
        trAutoCap()
        renderTranslate()
        refreshSuggestions()
    }

    private fun closeTranslate() {
        if (!trMode) return
        trMode = false
        trSeq++
        trBusy = false
        trBuf.setLength(0)
        trResult = null
        trStatus = null
        root?.hideTranslate()
        root?.keyboard?.enterStyle = enter
        updateAutoCap()
        refreshSuggestions()
    }

    private fun renderTranslate() {
        if (!trMode) return
        val src = if (trFromClip) "Avto" else langs[langIdx].short
        root?.trBar?.render(
            TrState(src, Translator.shortName(prefs.trTarget), trBuf.toString(), trBusy, trStatus, trError, trResult),
        )
    }

    /** Tarjima qatoridagi matn o'zgardi: eski natija endi mos emas. */
    private fun trChanged() {
        trResult = null
        if (!trBusy) {
            trStatus = null
            trError = false
            trOrbotHint = false
        }
        if (shift == Shift.ONCE) setShift(Shift.OFF)
        trAutoCap()
        renderTranslate()
        refreshSuggestions()
    }

    private fun trAutoCap() {
        if (page != Page.LETTERS || shift == Shift.CAPS) return
        val s = trBuf.toString()
        val want = if (s.isBlank() || Regex("[.!?…]\\s+$").containsMatchIn(s)) Shift.ONCE else Shift.OFF
        if (shift != want) setShift(want)
    }

    private fun trDeleteLast() {
        if (trBuf.isEmpty()) return
        val n = Character.charCount(trBuf.codePointBefore(trBuf.length))
        trBuf.setLength(trBuf.length - n)
    }

    override fun onTrSend() {
        if (trBusy) return
        val text = trBuf.toString().trim()
        if (text.isEmpty()) return
        val engine = prefs.trEngine
        val key = Secrets.get(this, Translator.keyName(engine))
        if (key.isNullOrBlank()) {
            trStatus = "Avval Söz+ sozlamalarida tarjima kalitini kiriting (bosing)"
            trError = true
            trOrbotHint = false
            renderTranslate()
            return
        }
        val region = prefs.azureRegion
        val target = prefs.trTarget
        val (source, payload) = Translator.prepareSource(text, langs[langIdx], trFromClip)
        trBusy = true
        trError = false
        trOrbotHint = false
        trResult = null
        trStatus = "Tor orqali tarjima qilinmoqda…"
        val seq = ++trSeq
        renderTranslate()
        val app = applicationContext
        worker.execute {
            var result: String? = null
            var error: String? = null
            var orbot = false
            try {
                if (!TorNet.isUp()) {
                    orbot = true
                    if (!TorNet.isOrbotInstalled(app)) {
                        throw Translator.Failure("Tor uçun Orbot ilovasi kerak — Söz+ sozlamalaridan örnating")
                    }
                    TorNet.requestStart(app)
                    ui.post {
                        if (seq == trSeq) {
                            trStatus = "Orbot (Tor) işga tuşirilmoqda…"
                            renderTranslate()
                        }
                    }
                    var up = false
                    for (i in 0 until 30) {
                        Thread.sleep(1000)
                        if (seq != trSeq) return@execute
                        if (TorNet.isUp()) {
                            up = true
                            break
                        }
                    }
                    if (!up) throw Translator.Failure("Orbot ulanmadi: Orbot'ni oçib «Start» bosing (bosing — oçiladi)")
                    orbot = false
                    ui.post {
                        if (seq == trSeq) {
                            trStatus = "Tor orqali tarjima qilinmoqda…"
                            renderTranslate()
                        }
                    }
                }
                result = Translator.finish(Translator.translate(engine, key, region, payload, source, target), target)
            } catch (e: Translator.Failure) {
                error = e.userMessage
            } catch (e: InterruptedException) {
                return@execute
            } catch (e: Exception) {
                error = "Tarjima bölmadi"
            }
            val res = result
            val err = error
            val orbotErr = orbot
            ui.post {
                if (seq != trSeq) return@post
                trBusy = false
                if (res != null) {
                    trResult = res
                    trStatus = null
                    trError = false
                } else {
                    trStatus = err
                    trError = true
                    trOrbotHint = orbotErr
                }
                renderTranslate()
            }
        }
    }

    override fun onTrClose() {
        closeTranslate()
    }

    override fun onTrTarget(code: String) {
        prefs.trTarget = code
        trResult = null
        renderTranslate()
    }

    /** Tillarni almashtiradi: maqsad tili klaviaturada bo'lsa, klaviatura shu tilga o'tadi. */
    override fun onTrSwap() {
        val tgt = Lang.byId(prefs.trTarget)
        val i = if (tgt == null) -1 else langs.indexOf(tgt)
        if (i < 0) {
            trStatus = "Bu til klaviaturada yöq: faqat maqsad tilini tanlang"
            trError = false
            renderTranslate()
            return
        }
        prefs.trTarget = langs[langIdx].id
        trResult?.let {
            trBuf.setLength(0)
            trBuf.append(it)
        }
        trResult = null
        trFromClip = false
        setLanguage(i)
    }

    override fun onTrPasteClip() {
        val text = clipboardStore().latest()?.text
        if (text.isNullOrBlank()) {
            trStatus = "Buferda matn yöq"
            trError = false
            renderTranslate()
            return
        }
        trBuf.setLength(0)
        trBuf.append(text.take(Translator.MAX_CHARS))
        trFromClip = true
        trChanged()
    }

    override fun onTrInsert() {
        val res = trResult ?: return
        val ic = currentInputConnection ?: return
        typedThisSession = true
        // belgilangan matndan boshlangan bo'lsa, commitText uning o'rniga qo'yadi
        ic.commitText(res, 1)
        trBuf.setLength(0)
        trResult = null
        trFromClip = false
        trStatus = "✓ Qöyildi"
        trError = false
        trAutoCap()
        renderTranslate()
        refreshSuggestions()
    }

    override fun onTrCopy() {
        val res = trResult ?: return
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tarjima", res))
            trStatus = "✓ Nusxalandi"
            trError = false
        } catch (t: Throwable) {
            trStatus = "Nusxalab bölmadi"
            trError = true
        }
        renderTranslate()
    }

    override fun onTrStatusTap() {
        if (trOrbotHint) {
            if (!TorNet.openOrbot(this)) TorNet.openOrbotStore(this)
        } else if (trError && trStatus?.contains("kalit") == true) {
            try {
                startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) {
                // sozlamalar ochilmadi
            }
        }
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
        justAccepted = false
        if (!restarting) typedThisSession = false
        clipboardOn = prefs.clipboardHistory && !isSensitiveField(info)
        configurePrediction(info)
        if (fieldKind.isPassword) closeTranslate()
        root?.hideEmoji()
        root?.hideClipboard()
        root?.hideVault()
        applyPrefs()
        applyLayout()
        if (trMode) root?.keyboard?.enterStyle = EnterStyle.SEND
        captureClipboard()
        fillQueuedPassword()
        runPendingVault()
        trackField()
        updateSecureFlag()
        if (trMode) trAutoCap() else updateAutoCap()
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
        captureCredential()
        savePersonal()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
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
        trackField()
        if (!trMode) refreshSuggestions()
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
        k.enterStyle = if (trMode) EnterStyle.SEND else enter
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
        if (trMode) {
            trAutoCap()
            return
        }
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
        val ic = currentInputConnection
        // kirish maydonlarida: saqlangan parol va kuchli parol taklifi (takliflar o'rnida)
        val acts = if (trMode) emptyList() else try {
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
        val before: CharSequence? = if (trMode) {
            trBuf.toString().takeLast(80)
        } else {
            try {
                ic?.getTextBeforeCursor(80, 0)
            } catch (t: Throwable) {
                null
            }
        }
        // yozish boshlanmagan bo'lsa: oxirgi nusxa (takliflar o'rnida)
        strip.clipText = if (trMode) null else try {
            clipSuggestion(before)
        } catch (t: Throwable) {
            null
        }
        val predicting = if (trMode) prefs.predict else predictOn
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
            val auto = if (!trMode && autoCorrectOn && ctx.prefix.length >= 2 && ctx.prefix != suppressAutoFor) {
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
        if (trMode) {
            val ctx = ContextParser.parse(trBuf.toString().takeLast(80))
            trBuf.setLength(trBuf.length - ctx.prefix.length)
            trBuf.append(word).append(' ')
            trChanged()
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
        if (trMode) {
            if (trBuf.length + text.length <= Translator.MAX_CHARS) trBuf.append(text)
            trChanged()
            return
        }
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
        if (trMode) {
            trBuf.append(' ')
            trChanged()
            return
        }
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
        if (trMode) {
            trDeleteLast()
            trChanged()
            return
        }
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
        if (trMode) {
            if (trResult != null && trBuf.isNotEmpty()) onTrInsert() else onTrSend()
            return
        }
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
        renderTranslate()
        refreshSuggestions()
    }

    override fun onEmoji() {
        root?.showEmoji(this)
    }

    private companion object {
        const val CLIPBOARD_FILE = "clipboard.txt"

        /** Shu vaqt ichida nusxalangan matn yozish boshida taklif qilinadi. */
        const val CLIP_FRESH_MS = 15 * 60 * 1000L

        const val ACT_FILL = 1
        const val ACT_OPEN = 2
        const val ACT_GEN = 3
    }
}
