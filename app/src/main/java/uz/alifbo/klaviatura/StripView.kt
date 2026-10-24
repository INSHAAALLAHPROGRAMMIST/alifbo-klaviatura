package uz.alifbo.klaviatura

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * Klaviatura tepasidagi tahmin qatori: eng yaxshi taklif o'rtada (qalin), ikkinchisi chapda, uchinchisi o'ngda.
 * Taklif ustiga bosilsa [onPick] chaqiriladi (taklifning tartib raqami bilan: 0 = eng yaxshi).
 */
@SuppressLint("ClickableViewAccessibility")
class StripView(context: Context) : View(context) {
    var theme: KbTheme = Themes.light
        set(value) {
            field = value
            invalidate()
        }

    var suggestions: List<String> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                pressedSlot = -1
                invalidate()
            }
        }

    var onPick: ((Int) -> Unit)? = null

    /** Ekrandagi 3 joy -> taklif tartib raqami (chap, o'rta, o'ng). */
    private val order = intArrayOf(1, 0, 2)
    private var pressedSlot = -1

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun slotAt(x: Float): Int {
        if (width <= 0) return -1
        val s = (x / (width / 3f)).toInt().coerceIn(0, 2)
        return if (suggestions.getOrNull(order[s]) != null) s else -1
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        if (suggestions.isEmpty()) return
        val slotW = width / 3f
        for (slot in 0..2) {
            val word = suggestions.getOrNull(order[slot]) ?: continue
            val left = slot * slotW
            if (slot == pressedSlot) {
                rect.set(left + dp(3f), dp(4f), left + slotW - dp(3f), height - dp(4f))
                fill.color = theme.keySpecial
                canvas.drawRoundRect(rect, dp(8f), dp(8f), fill)
            }
            val best = order[slot] == 0
            text.typeface = if (best) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.color = if (best) theme.text else theme.textSpecial
            text.textSize = dp(16f)
            val avail = slotW - dp(16f)
            val w = text.measureText(word)
            if (w > avail && w > 0f) text.textSize = text.textSize * avail / w
            val fm = text.fontMetrics
            canvas.drawText(word, left + slotW / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, text)
        }
        line.color = theme.hint
        line.alpha = 90
        line.strokeWidth = dp(1f)
        for (d in 1..2) {
            val leftHas = suggestions.getOrNull(order[d - 1]) != null
            val rightHas = suggestions.getOrNull(order[d]) != null
            if (leftHas && rightHas) {
                val x = d * slotW
                canvas.drawLine(x, height * 0.28f, x, height * 0.72f, line)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val s = slotAt(e.x)
                if (s != pressedSlot) {
                    pressedSlot = s
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val s = pressedSlot
                pressedSlot = -1
                invalidate()
                if (s >= 0) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onPick?.invoke(order[s])
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedSlot = -1
                invalidate()
            }
        }
        return true
    }
}
