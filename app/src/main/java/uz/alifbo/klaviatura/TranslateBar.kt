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
import android.widget.LinearLayout
import android.widget.TextView

/** Tarjima qatori tugmalari (klaviatura xizmati amalga oshiradi). */
interface TranslateHandler {
    fun onTrSend()
    fun onTrClose()
    /** Til tanlash paneli ochiladi (yozilayotgan va tarjima tili). */
    fun onTrLangs()
    fun onTrSwap()
    fun onTrPasteClip()
    /** Qator içidagi holat xabari bosildi (qaytariş, sozlamalar yoki yopiş). */
    fun onTrStatusTap()
}

/**
 * Tarjima qatorining holati: manba va maqsad tili, yozilayotgan matn, holat xabari.
 * Natija qatorda körsatilmaydi — to'ğridan-to'ğri maydonga yoziladi.
 */
data class TrState(
    val source: String,
    val target: String,
    val text: String,
    val busy: Boolean = false,
    val status: String? = null,
    val error: Boolean = false,
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
 * Klaviatura ustidagi tarjima qatori: bitta ixcham qator, balandligi heç qaçon özgarmaydi.
 * [UZ ⇄ EN] [yozilayotgan matn yoki holat|] [📋] [➤] [✕]. Tillar bosilsa — til tanlash paneli.
 */
class TranslateBar(context: Context) : LinearLayout(context) {
    var trHandler: TranslateHandler? = null
    private var theme: KbTheme = Themes.light
    private var state = TrState("UZ", "EN", "")

    private val langBox = LinearLayout(context)
    private val source = TextView(context)
    private val swap = TextView(context)
    private val target = TextView(context)
    private val input = TextView(context)
    private val paste = IconButton(context, { c, p, x, y, s -> Icons.clipboard(c, p, x, y, s * 0.9f) }, { trHandler?.onTrPasteClip() })
    private val send = IconButton(context, { c, p, x, y, s -> Icons.enter(c, p, x, y, s, EnterStyle.SEND) }, { trHandler?.onTrSend() })
    private val close = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(6f), 0, dpi(2f), 0)

        for (tv in listOf(source, swap, target)) {
            tv.gravity = Gravity.CENTER
            tv.textSize = 13f
            tv.typeface = Typeface.DEFAULT_BOLD
        }
        swap.text = "⇄"
        swap.textSize = 15f
        swap.setOnClickListener { trHandler?.onTrSwap() }
        source.setOnClickListener { trHandler?.onTrLangs() }
        target.setOnClickListener { trHandler?.onTrLangs() }
        langBox.orientation = HORIZONTAL
        langBox.gravity = Gravity.CENTER_VERTICAL
        langBox.addView(source, LayoutParams(dpi(34f), LayoutParams.MATCH_PARENT))
        langBox.addView(swap, LayoutParams(dpi(24f), LayoutParams.MATCH_PARENT))
        langBox.addView(target, LayoutParams(dpi(34f), LayoutParams.MATCH_PARENT))
        addView(langBox, LayoutParams(LayoutParams.WRAP_CONTENT, dpi(34f)))

        input.textSize = 15f
        input.isSingleLine = true
        input.ellipsize = TextUtils.TruncateAt.START
        input.gravity = Gravity.CENTER_VERTICAL
        input.setPadding(dpi(10f), 0, dpi(10f), 0)
        input.setOnClickListener { if (state.status != null) trHandler?.onTrStatusTap() }
        addView(input, LayoutParams(0, dpi(34f), 1f).apply {
            marginStart = dpi(6f)
            marginEnd = dpi(2f)
        })
        addView(paste, LayoutParams(dpi(36f), dpi(HEIGHT_DP)))
        addView(send, LayoutParams(dpi(44f), dpi(HEIGHT_DP)))
        close.text = "✕"
        close.textSize = 15f
        close.gravity = Gravity.CENTER
        close.setOnClickListener { trHandler?.onTrClose() }
        addView(close, LayoutParams(dpi(32f), dpi(HEIGHT_DP)))
        render(state)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // balandlik qat'iy: holat xabari yoki matn uzunligi klaviatura oynasini kattalaştirmaydi
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(dpi(HEIGHT_DP), MeasureSpec.EXACTLY))
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

    fun render(s: TrState) {
        state = s
        val t = theme
        setBackgroundColor(t.background)
        source.text = s.source
        target.text = s.target
        source.setTextColor(t.textSpecial)
        swap.setTextColor(t.hint)
        target.setTextColor(t.accent)
        langBox.background = rounded(t.keySpecial, 10f)
        close.setTextColor(t.hint)

        input.background = rounded(t.key, 10f)
        val sb = SpannableStringBuilder()
        val status = s.status
        if (status != null) {
            // holat matn o'rnida körinadi (qator balandligi özgarmaydi); yozilganda yoki bosilganda yo'qoladi
            sb.append(status)
            val c = if (s.error) 0xFFE5484D.toInt() else if (s.busy) t.hint else t.accent
            sb.setSpan(ForegroundColorSpan(c), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            input.ellipsize = TextUtils.TruncateAt.END
        } else if (s.text.isEmpty()) {
            sb.append("│")
            sb.setSpan(ForegroundColorSpan(t.accent), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = sb.length
            sb.append("Tarjima uçun yozing…")
            sb.setSpan(ForegroundColorSpan(t.hint), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            input.ellipsize = TextUtils.TruncateAt.END
        } else {
            sb.append(s.text.replace(NEWLINE, ' '))
            sb.setSpan(ForegroundColorSpan(t.text), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = sb.length
            sb.append("│")
            sb.setSpan(ForegroundColorSpan(t.accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            input.ellipsize = TextUtils.TruncateAt.START
        }
        input.text = sb
        send.accent = s.text.isNotBlank() && !s.busy
    }

    companion object {
        /** Tarjima qatorining qat'iy balandligi (dp). */
        const val HEIGHT_DP = 40f
        private const val NEWLINE = '\n'
    }
}
