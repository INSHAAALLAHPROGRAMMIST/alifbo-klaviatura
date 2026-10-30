package uz.alifbo.klaviatura

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/** IME oynasining ildiz ko'rinishi: tepada qator, o'rtada klaviatura (yoki emoji paneli), eng ustida qatlam. */
class KeyboardRoot(context: Context, listener: KeyboardListener) : FrameLayout(context) {
    val strip = StripView(context)
    val keyboard = KeyboardView(context)
    val overlay = OverlayView(context)

    private val body = FrameLayout(context)
    private var emoji: EmojiPanel? = null
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
        strip.theme = t
        keyboard.theme = t
        overlay.theme = t
        keyboard.invalidate()
        emoji?.applyTheme(t)
        clipboard?.applyTheme(t)
    }

    val isEmojiShown: Boolean get() = emoji?.visibility == View.VISIBLE

    private fun panelHeight(): Int = if (keyboard.height > 0) keyboard.height else keyboard.measuredHeight

    fun showEmoji(listener: KeyboardListener) {
        val h = panelHeight()
        hideClipboard()
        val panel = emoji ?: EmojiPanel(context, onEmojiPick, onEmojiDelete, onEmojiBack).also {
            emoji = it
            body.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        }
        panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply { height = h }
        panel.applyTheme(theme)
        panel.visibility = View.VISIBLE
        keyboard.visibility = View.GONE
    }

    fun hideEmoji() {
        emoji?.visibility = View.GONE
        if (clipboard?.visibility != View.VISIBLE) keyboard.visibility = View.VISIBLE
    }

    private var clipboard: ClipboardPanel? = null

    val isClipboardShown: Boolean get() = clipboard?.visibility == View.VISIBLE

    /** Nusxalar tarixi paneli klaviatura o'rnida (bir xil balandlikda). */
    fun showClipboard(items: List<Clip>, handler: ClipboardHandler) {
        val h = if (isEmojiShown) emoji!!.height else panelHeight()
        emoji?.visibility = View.GONE
        val panel = clipboard ?: ClipboardPanel(context) { hideClipboard() }.also {
            clipboard = it
            body.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        }
        panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply { height = h }
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
        if (!isEmojiShown) keyboard.visibility = View.VISIBLE
    }

    companion object {
        /** Tahmin qatorining balandligi (dp). */
        const val STRIP_DP = 38f
    }
}
