package uz.alifbo.klaviatura

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Tarjima qatori tugmalari (klaviatura xizmati amalga oshiradi). */
interface TranslateHandler {
    fun onTrSend()
    fun onTrClose()
    fun onTrTarget(code: String)
    fun onTrSwap()
    fun onTrPasteClip()
    fun onTrInsert()
    fun onTrCopy()
    fun onTrStatusTap()
}

/** Tarjima qatorining holati: manba va maqsad tili, yozilayotgan matn, natija yoki holat xabari. */
data class TrState(
    val source: String,
    val target: String,
    val text: String,
    val busy: Boolean = false,
    val status: String? = null,
    val error: Boolean = false,
    val result: String? = null,
)

/** Kichik belgili tugma (Canvas'da chiziladi). */
@SuppressLint("ClickableViewAccessibility")
internal class IconButton(
    context: Context,
    private val painter: (Canvas, Paint, Float, Float, Float) -> Unit,
    private val onTap: () -> Unit,
) : View(context) {
    var theme: KbTheme = Themes.light
        set(value) {
            field = value
            invalidate()
        }
    var accent = false
        set(value) {
            field = value
            invalidate()
        }
    private var down = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        rect.set(dp(3f), dp(5f), width - dp(3f), height - dp(5f))
        paint.style = Paint.Style.FILL
        if (accent || down) {
            paint.color = when {
                accent && down -> theme.keyPressed
                accent -> theme.accent
                else -> theme.keySpecial
            }
            canvas.drawRoundRect(rect, dp(10f), dp(10f), paint)
        }
        paint.color = if (accent && !down) theme.accentText else theme.textSpecial
        painter(canvas, paint, width / 2f, height / 2f, dp(18f))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val was = down
                down = false
                invalidate()
                if (was) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onTap()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                down = false
                invalidate()
            }
        }
        return true
    }
}

/**
 * Klaviatura ustidagi tarjima qatori: tugmalar ilovaga emas, shu qatorga yozadi.
 * [UZ ⇄ EN] [yozilayotgan matn|] [📋] [➤] [✕]; ostida — holat yoki natija va «Qöyiş», «Nusxa».
 */
class TranslateBar(context: Context) : LinearLayout(context) {
    var trHandler: TranslateHandler? = null
    private var theme: KbTheme = Themes.light
    private var state = TrState("UZ", "EN", "")
    private var pickerOpen = false

    private val source = TextView(context)
    private val swap = TextView(context)
    private val target = TextView(context)
    private val input = TextView(context)
    private val paste = IconButton(context, { c, p, x, y, s -> Icons.clipboard(c, p, x, y, s * 0.9f) }, { trHandler?.onTrPasteClip() })
    private val send = IconButton(context, { c, p, x, y, s -> Icons.enter(c, p, x, y, s, EnterStyle.SEND) }, { trHandler?.onTrSend() })
    private val close = TextView(context)
    private val pickerRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val picker = HorizontalScrollView(context)
    private val chips = ArrayList<Pair<String, TextView>>()
    private val status = TextView(context)
    private val resultBox = LinearLayout(context).apply { orientation = VERTICAL }
    private val resultText = TextView(context)
    private val insert = TextView(context)
    private val copy = TextView(context)

    init {
        orientation = VERTICAL
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(6f), 0, dpi(2f), 0)
        }
        for (tv in listOf(source, swap, target)) {
            tv.gravity = Gravity.CENTER
            tv.textSize = 13f
            tv.typeface = Typeface.DEFAULT_BOLD
        }
        swap.text = "⇄"
        swap.textSize = 15f
        swap.setOnClickListener { trHandler?.onTrSwap() }
        target.setOnClickListener {
            pickerOpen = !pickerOpen
            render(state)
        }
        source.setOnClickListener { trHandler?.onTrSwap() }
        row.addView(source, LayoutParams(dpi(34f), dpi(32f)))
        row.addView(swap, LayoutParams(dpi(24f), dpi(32f)))
        row.addView(target, LayoutParams(dpi(34f), dpi(32f)))

        input.textSize = 15f
        input.isSingleLine = true
        input.ellipsize = TextUtils.TruncateAt.START
        input.gravity = Gravity.CENTER_VERTICAL
        input.setPadding(dpi(10f), 0, dpi(10f), 0)
        row.addView(input, LayoutParams(0, dpi(36f), 1f).apply {
            marginStart = dpi(6f)
            marginEnd = dpi(2f)
        })
        row.addView(paste, LayoutParams(dpi(38f), dpi(44f)))
        row.addView(send, LayoutParams(dpi(46f), dpi(44f)))
        close.text = "✕"
        close.textSize = 15f
        close.gravity = Gravity.CENTER
        close.setOnClickListener { trHandler?.onTrClose() }
        row.addView(close, LayoutParams(dpi(34f), dpi(44f)))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dpi(46f)))

        // maqsad tili tanlagichi
        pickerRow.setPadding(dpi(6f), 0, dpi(6f), 0)
        pickerRow.gravity = Gravity.CENTER_VERTICAL
        for ((code, name) in Translator.targets) {
            val chip = TextView(context).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dpi(12f), dpi(6f), dpi(12f), dpi(6f))
                setOnClickListener {
                    pickerOpen = false
                    trHandler?.onTrTarget(code)
                }
            }
            chips.add(code to chip)
            pickerRow.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dpi(6f)
            })
        }
        picker.isHorizontalScrollBarEnabled = false
        picker.addView(pickerRow)
        addView(picker, LayoutParams(LayoutParams.MATCH_PARENT, dpi(40f)))

        status.textSize = 13f
        status.setPadding(dpi(12f), 0, dpi(12f), dpi(6f))
        status.setOnClickListener { trHandler?.onTrStatusTap() }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        resultText.textSize = 15f
        resultText.maxLines = 4
        resultText.ellipsize = TextUtils.TruncateAt.END
        resultText.setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f))
        resultText.setOnClickListener { trHandler?.onTrInsert() }
        resultBox.addView(resultText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        insert.text = "Qöyiş"
        copy.text = "Nusxa"
        for (b in listOf(copy, insert)) {
            b.textSize = 14f
            b.gravity = Gravity.CENTER
            b.typeface = Typeface.DEFAULT_BOLD
            b.setPadding(dpi(16f), dpi(7f), dpi(16f), dpi(7f))
            actions.addView(b, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dpi(8f)
            })
        }
        insert.setOnClickListener { trHandler?.onTrInsert() }
        copy.setOnClickListener { trHandler?.onTrCopy() }
        actions.setPadding(0, 0, dpi(8f), dpi(6f))
        resultBox.addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(resultBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dpi(8f), 0, dpi(8f), dpi(4f))
        })
        render(state)
    }

    private fun dpi(v: Float): Int = dp(v).toInt()

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r)
    }

    fun applyTheme(t: KbTheme) {
        theme = t
        paste.theme = t
        send.theme = t
        render(state)
    }

    /** Matn o'zgarganda tanlagich yopiladi. */
    fun closePicker() {
        pickerOpen = false
    }

    fun render(s: TrState) {
        state = s
        val t = theme
        setBackgroundColor(t.background)
        source.text = s.source
        target.text = s.target
        source.setTextColor(t.textSpecial)
        swap.setTextColor(t.hint)
        target.setTextColor(t.accent)
        target.background = rounded(if (pickerOpen) t.keySpecial else t.background, 8f)
        close.setTextColor(t.hint)

        input.background = rounded(t.key, 10f)
        val sb = SpannableStringBuilder()
        if (s.text.isEmpty()) {
            sb.append("│")
            sb.setSpan(ForegroundColorSpan(t.accent), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = sb.length
            sb.append("Tarjima uçun yozing…")
            sb.setSpan(ForegroundColorSpan(t.hint), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.append(s.text.replace('\n', ' '))
            sb.setSpan(ForegroundColorSpan(t.text), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = sb.length
            sb.append("│")
            sb.setSpan(ForegroundColorSpan(t.accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        input.text = sb
        send.accent = s.text.isNotBlank() && !s.busy

        picker.visibility = if (pickerOpen) View.VISIBLE else View.GONE
        for ((code, chip) in chips) {
            val sel = Translator.shortName(code) == s.target
            chip.background = rounded(if (sel) t.accent else t.key, 9f)
            chip.setTextColor(if (sel) t.accentText else t.text)
        }

        status.visibility = if (s.status != null) View.VISIBLE else View.GONE
        status.text = s.status ?: ""
        status.setTextColor(if (s.error) 0xFFE5484D.toInt() else t.hint)

        resultBox.visibility = if (s.result != null) View.VISIBLE else View.GONE
        resultBox.background = rounded(t.key, 12f)
        resultText.text = s.result ?: ""
        resultText.setTextColor(t.text)
        insert.background = rounded(t.accent, 10f)
        insert.setTextColor(t.accentText)
        copy.background = rounded(t.keySpecial, 10f)
        copy.setTextColor(t.textSpecial)
    }
}
