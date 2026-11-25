package uz.alifbo.klaviatura

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * IME oynasining ildiz ko'rinishi: tepada tarjima qatori (yashirin bo'lishi mumkin) va tahmin qatori,
 * o'rtada klaviatura (yoki emoji / bufer / parollar paneli), eng ustida qatlam.
 */
class KeyboardRoot(context: Context, listener: KeyboardListener) : FrameLayout(context) {
    val trBar = TranslateBar(context)
    val strip = StripView(context)
    val keyboard = KeyboardView(context)
    val overlay = OverlayView(context)
    val editor = EditorPane(context)

    private val body = FrameLayout(context)
    private var translateOn = false
    private var emoji: EmojiPanel? = null
    private var clipboard: ClipboardPanel? = null
    private var vault: VaultPanel? = null
    private var trLangs: TrLangPanel? = null
    private var theme: KbTheme = Themes.light

    private val onEmojiPick: (String) -> Unit
    private val onEmojiDelete: () -> Unit
    private val onEmojiBack: () -> Unit

    init {
        keyboard.listener = listener
        keyboard.overlay = overlay
        onEmojiPick = { listener.onText(it) }
        onEmojiDelete = { listener.onDelete() }
        onEmojiBack = { hideEmoji() }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        editor.visibility = View.GONE
        column.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        trBar.visibility = View.GONE
        column.addView(trBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(STRIP_DP).toInt()))
        body.addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clipChildren = false
        clipToPadding = false
    }

    fun applyTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.background)
        trBar.applyTheme(t)
        editor.applyTheme(t)
        strip.theme = t
        keyboard.theme = t
        overlay.theme = t
        keyboard.invalidate()
        emoji?.applyTheme(t)
        clipboard?.applyTheme(t)
        vault?.applyTheme(t)
        trLangs?.applyTheme(t)
    }

    val isEmojiShown: Boolean get() = emoji?.visibility == View.VISIBLE
    val isClipboardShown: Boolean get() = clipboard?.visibility == View.VISIBLE
    val isVaultShown: Boolean get() = vault?.visibility == View.VISIBLE
    val isTrLangsShown: Boolean get() = trLangs?.visibility == View.VISIBLE
    val isTranslateShown: Boolean get() = translateOn
    val isEditorShown: Boolean get() = editor.visibility == View.VISIBLE

    /** Muharrir balandligi (px) — ilova ekrani joyidan siljimasligi uçun xizmatga kerak. */
    val editorHeight: Int get() = if (isEditorShown) editor.layoutParams.height else 0

    /** Ekranning qolgan qismini egallaydigan muharrir; klaviatura va tahmin qatori joyida qoladi. */
    fun showEditor(handler: EditorHandler) {
        val screen = resources.displayMetrics.heightPixels
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (id > 0) resources.getDimensionPixelSize(id) else dp(24f).toInt()
        val below = panelHeight() + dp(STRIP_DP).toInt()
        // pastki tizim paneli uçun zaxira qoldiriladi
        val h = (screen - statusBar - below - dp(EDITOR_RESERVE_DP).toInt()).coerceAtLeast(dp(EDITOR_MIN_DP).toInt())
        editor.editorHandler = handler
        editor.layoutParams = (editor.layoutParams as LinearLayout.LayoutParams).apply { height = h }
        editor.applyTheme(theme)
        editor.visibility = View.VISIBLE
        trBar.visibility = View.GONE
        strip.activeTool = 3
    }

    fun hideEditor() {
        if (!isEditorShown) return
        editor.visibility = View.GONE
        trBar.visibility = if (translateOn) View.VISIBLE else View.GONE
        strip.activeTool = if (translateOn) 1 else -1
    }

    /** Panel klaviatura bilan bir xil balandlikda (boshqa panel ochiq bo'lsa — o'shaniki). */
    private fun panelHeight(): Int {
        if (keyboard.visibility == View.VISIBLE && keyboard.height > 0) return keyboard.height
        for (p in listOf<View?>(emoji, clipboard, vault, trLangs)) {
            if (p != null && p.visibility == View.VISIBLE && p.height > 0) return p.height
        }
        return if (keyboard.height > 0) keyboard.height else keyboard.measuredHeight
    }

    private fun anyPanelShown(): Boolean = isEmojiShown || isClipboardShown || isVaultShown || isTrLangsShown

    private fun place(panel: View, h: Int) {
        if (panel.parent == null) body.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply { height = h }
    }

    fun showEmoji(listener: KeyboardListener) {
        val h = panelHeight()
        clipboard?.visibility = View.GONE
        vault?.visibility = View.GONE
        trLangs?.visibility = View.GONE
        val panel = emoji ?: EmojiPanel(context, onEmojiPick, onEmojiDelete, onEmojiBack).also { emoji = it }
        place(panel, h)
        panel.applyTheme(theme)
        panel.visibility = View.VISIBLE
        keyboard.visibility = View.GONE
    }

    fun hideEmoji() {
        emoji?.visibility = View.GONE
        if (!anyPanelShown()) keyboard.visibility = View.VISIBLE
    }

    /** Nusxalar tarixi paneli klaviatura o'rnida (bir xil balandlikda). */
    fun showClipboard(items: List<Clip>, handler: ClipboardHandler) {
        val h = panelHeight()
        emoji?.visibility = View.GONE
        vault?.visibility = View.GONE
        trLangs?.visibility = View.GONE
        val panel = clipboard ?: ClipboardPanel(context) { hideClipboard() }.also { clipboard = it }
        place(panel, h)
        panel.clipHandler = handler
        panel.applyTheme(theme)
        panel.setItems(items)
        panel.visibility = View.VISIBLE
        keyboard.visibility = View.GONE
    }

    fun updateClipboard(items: List<Clip>) {
        clipboard?.takeIf { it.visibility == View.VISIBLE }?.setItems(items)
    }

    fun hideClipboard() {
        clipboard?.visibility = View.GONE
        if (!anyPanelShown()) keyboard.visibility = View.VISIBLE
    }

    /** Parollar paneli klaviatura o'rnida. */
    fun showVault(state: VaultState, handler: VaultHandler) {
        val h = panelHeight()
        emoji?.visibility = View.GONE
        clipboard?.visibility = View.GONE
        trLangs?.visibility = View.GONE
        val panel = vault ?: VaultPanel(context) { hideVault() }.also { vault = it }
        place(panel, h)
        panel.vaultHandler = handler
        panel.applyTheme(theme)
        panel.setState(state)
        panel.visibility = View.VISIBLE
        keyboard.visibility = View.GONE
    }

    fun updateVault(state: VaultState) {
        vault?.takeIf { it.visibility == View.VISIBLE }?.setState(state)
    }

    fun hideVault() {
        vault?.visibility = View.GONE
        if (!anyPanelShown()) keyboard.visibility = View.VISIBLE
    }

    fun showTranslate(handler: TranslateHandler) {
        trBar.trHandler = handler
        translateOn = true
        trBar.visibility = View.VISIBLE
        strip.activeTool = 1
    }

    /** Til tanlaş paneli klaviatura o'rnida (bir xil balandlikda). */
    fun showTrLangs(
        sources: List<Lang>,
        sourceIdx: Int,
        targetCode: String,
        onSource: (Int) -> Unit,
        onTarget: (String) -> Unit,
    ) {
        val h = panelHeight()
        emoji?.visibility = View.GONE
        clipboard?.visibility = View.GONE
        vault?.visibility = View.GONE
        val panel = trLangs ?: TrLangPanel(context, { hideTrLangs() }, { onSource(it) }, { onTarget(it) }).also { trLangs = it }
        place(panel, h)
        panel.applyTheme(theme)
        panel.setState(sources, sourceIdx, targetCode)
        panel.visibility = View.VISIBLE
        keyboard.visibility = View.GONE
    }

    fun hideTrLangs() {
        trLangs?.visibility = View.GONE
        if (!anyPanelShown()) keyboard.visibility = View.VISIBLE
    }

    fun hideTranslate() {
        hideEditor()
        hideTrLangs()
        translateOn = false
        trBar.visibility = View.GONE
        if (strip.activeTool == 1) strip.activeTool = -1
    }

    companion object {
        /** Tahmin qatorining balandligi (dp). */
        const val STRIP_DP = 38f
        private const val EDITOR_RESERVE_DP = 56f
        private const val EDITOR_MIN_DP = 120f
    }
}
