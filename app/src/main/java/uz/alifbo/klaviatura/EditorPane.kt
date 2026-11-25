package uz.alifbo.klaviatura

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Töliq ekran muharriri tugmalari (klaviatura xizmati amalga oshiradi). */
interface EditorHandler {
    /** Tayyor: matn saqlanadi va muharrir yopiladi. */
    fun onEditorDone()

    /** Bekor: ilova maydoni özgarmaydi. */
    fun onEditorCancel()
    fun onEditorPaste()
    fun onEditorCopy()

    /** Tarjima matni tahrirlanayotganda: yopib, tarjima qilinsin. */
    fun onEditorSend()
    fun onEditorCursor(position: Int)
}

/** [translate] = tarjima matni (➤ bor, ✕ yöq); aks holda ilova maydonidagi matn. */
data class EditorState(val title: String, val text: String, val cursor: Int, val translate: Boolean)

/**
 * Yozilayotgan matnni töliq ekranda ko'riş va tahrirlaş: yuqorida tugmalar, ostida butun matn,
 * pastda odatdagi klaviatura.
 */
class EditorPane(context: Context) : LinearLayout(context) {
    var editorHandler: EditorHandler? = null

    private val title = TextView(context)
    private val paste = IconButton(context, { c, p, x, y, s -> Icons.clipboard(c, p, x, y, s * 0.9f) }, { editorHandler?.onEditorPaste() })
    private val copy = TextView(context)
    private val send = IconButton(context, { c, p, x, y, s -> Icons.enter(c, p, x, y, s, EnterStyle.SEND) }, { editorHandler?.onEditorSend() })
    private val cancel = TextView(context)
    private val done = TextView(context)
    val body = CursorTextView(context, singleLine = false)

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14f), 0, dpi(6f), 0)
        }
        title.textSize = 13f
        title.maxLines = 1
        title.typeface = Typeface.DEFAULT_BOLD
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(paste, LayoutParams(dpi(42f), dpi(HEADER_DP)))
        for (b in listOf(copy, cancel, done)) {
            b.textSize = 14f
            b.gravity = Gravity.CENTER
            b.setPadding(dpi(10f), 0, dpi(10f), 0)
        }
        copy.text = "Nusxa"
        copy.setOnClickListener { editorHandler?.onEditorCopy() }
        header.addView(copy, LayoutParams(LayoutParams.WRAP_CONTENT, dpi(34f)))
        header.addView(send, LayoutParams(dpi(46f), dpi(HEADER_DP)))
        cancel.text = "Bekor"
        cancel.setOnClickListener { editorHandler?.onEditorCancel() }
        header.addView(cancel, LayoutParams(LayoutParams.WRAP_CONTENT, dpi(34f)))
        done.text = "✓ Tayyor"
        done.typeface = Typeface.DEFAULT_BOLD
        done.setOnClickListener { editorHandler?.onEditorDone() }
        header.addView(done, LayoutParams(LayoutParams.WRAP_CONTENT, dpi(34f)).apply { marginStart = dpi(4f) })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dpi(HEADER_DP)))

        body.textSizeDp = 17f
        body.setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
        body.onCursor = { editorHandler?.onEditorCursor(it) }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(dpi(8f), 0, dpi(8f), dpi(4f))
        })
    }

    private fun dpi(v: Float): Int = dp(v).toInt()

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r)
    }

    fun applyTheme(t: KbTheme) {
        setBackgroundColor(t.background)
        title.setTextColor(t.hint)
        paste.theme = t
        send.theme = t
        send.accent = true
        copy.setTextColor(t.textSpecial)
        cancel.setTextColor(t.hint)
        done.setTextColor(t.accentText)
        done.background = rounded(t.accent, 10f)
        body.background = rounded(t.key, 12f)
        body.setColors(t.text, t.hint, t.accent)
    }

    fun render(s: EditorState) {
        title.text = s.title
        send.visibility = if (s.translate) View.VISIBLE else View.GONE
        cancel.visibility = if (s.translate) View.GONE else View.VISIBLE
        body.show(s.text, s.cursor, "Matn yozing…")
    }

    companion object {
        const val HEADER_DP = 44f
    }
}
