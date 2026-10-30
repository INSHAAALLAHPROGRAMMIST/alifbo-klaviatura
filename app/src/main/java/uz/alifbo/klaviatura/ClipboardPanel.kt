package uz.alifbo.klaviatura

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Bufer paneli tugmalari bosilganda chaqiriladi (klaviatura xizmati amalga oshiradi). */
interface ClipboardHandler {
    fun onClipPaste(text: String)
    fun onClipPin(text: String)
    fun onClipDelete(text: String)
    fun onClipClear()
}

/**
 * Nusxalar tarixi paneli (emoji paneli kabi klaviatura o'rnida): bosilsa — yoziladi,
 * ☆ — qadab qo'yish (tozalashda o'chmaydi), ✕ — o'chirish, "Tozalash" — qadalmaganlarni o'chirish.
 */
class ClipboardPanel(context: Context, private val onBack: () -> Unit) : LinearLayout(context) {
    var clipHandler: ClipboardHandler? = null
    private var theme: KbTheme = Themes.light
    private var items: List<Clip> = emptyList()

    private val title = TextView(context)
    private val clear = TextView(context)
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context)
    private val abc = BarButton(context, "ABC", { onBack() })

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f).toInt(), 0, dp(8f).toInt(), 0)
        }
        title.text = "Bufer"
        title.textSize = 15f
        title.typeface = Typeface.DEFAULT_BOLD
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        clear.text = "Tozalaş"
        clear.textSize = 14f
        clear.setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
        clear.setOnClickListener { clipHandler?.onClipClear() }
        header.addView(clear)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f).toInt()))

        scroll.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        scroll.isVerticalScrollBarEnabled = false
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar.addView(abc, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.4f))
        bar.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 6.4f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46f).toInt()))
    }

    fun applyTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.background)
        title.setTextColor(t.text)
        clear.setTextColor(t.accent)
        abc.theme = t
        render()
    }

    fun setItems(items: List<Clip>) {
        this.items = items
        render()
    }

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r)
    }

    private fun render() {
        list.removeAllViews()
        if (items.isEmpty()) {
            val empty = TextView(context).apply {
                text = "Hali heç narsa nusxalanmagan.\nBiror matnni nusxalang — u şu yerda saqlanadi."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(theme.hint)
                setPadding(dp(16f).toInt(), dp(28f).toInt(), dp(16f).toInt(), dp(28f).toInt())
            }
            list.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return
        }
        val pad = dp(10f).toInt()
        for (c in items) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(theme.key, 10f)
                setPadding(pad + dp(2f).toInt(), pad, dp(4f).toInt(), pad)
                setOnClickListener { clipHandler?.onClipPaste(c.text) }
            }
            val body = TextView(context).apply {
                text = c.text
                textSize = 14f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(theme.text)
            }
            row.addView(body, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val pin = TextView(context).apply {
                text = if (c.pinned) "★" else "☆"
                textSize = 18f
                setTextColor(if (c.pinned) theme.accent else theme.hint)
                setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(6f).toInt(), dp(4f).toInt())
                setOnClickListener { clipHandler?.onClipPin(c.text) }
            }
            row.addView(pin)
            val del = TextView(context).apply {
                text = "✕"
                textSize = 15f
                setTextColor(theme.hint)
                setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())
                setOnClickListener { clipHandler?.onClipDelete(c.text) }
            }
            row.addView(del)
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())
            })
        }
    }
}
