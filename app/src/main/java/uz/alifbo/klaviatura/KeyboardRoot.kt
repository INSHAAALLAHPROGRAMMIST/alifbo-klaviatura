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
        column.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f).toInt()))
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
    }

    val isEmojiShown: Boolean get() = emoji?.visibility == View.VISIBLE

    fun showEmoji(listener: KeyboardListener) {
        val h = if (keyboard.height > 0) keyboard.height else keyboard.measuredHeight
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
        keyboard.visibility = View.VISIBLE
    }
}
