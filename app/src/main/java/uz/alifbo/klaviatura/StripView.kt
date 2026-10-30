package uz.alifbo.klaviatura

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View

/**
 * Klaviatura tepasidagi qator.
 * Chapda ikki asbob: bufer (nusxalar tarixi) va tarjima ([onTool]: 0 = bufer, 1 = tarjima).
 * O'ngda: tahmin — eng yaxshi taklif o'rtada (qalin), ikkinchisi chapda, uchinchisi o'ngda ([onPick]);
 * yozish boshlanmagan bo'lsa, oxirgi nusxa taklif sifatida ([clipText], [onClip]).
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
                pressed = NONE
                invalidate()
            }
        }

    var onPick: ((Int) -> Unit)? = null
    var onTool: ((Int) -> Unit)? = null
    var onClip: (() -> Unit)? = null

    /** Eng yaxshi taklif probelda avtomatik qo'yiladi: urg'u rangida ko'rsatiladi. */
    var highlightBest: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Taklif qilinayotgan nusxa (bo'lsa, takliflar o'rnida keng tugma bo'lib chiqadi). */
    var clipText: String? = null
        set(value) {
            if (field != value) {
                field = value
                pressed = NONE
                invalidate()
            }
        }

    /** Ekrandagi 3 joy -> taklif tartib raqami (chap, o'rta, o'ng). */
    private val order = intArrayOf(1, 0, 2)
    private var pressed = NONE

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val toolW: Float get() = dp(40f)
    private val toolsEnd: Float get() = toolW * 2 + dp(4f)

    private fun targetAt(x: Float): Int {
        if (width <= 0) return NONE
        if (x < toolsEnd) return if (x < toolW + dp(2f)) TOOL_CLIPBOARD else TOOL_TRANSLATE
        if (clipText != null) return CLIP
        val slotW = (width - toolsEnd) / 3f
        val s = ((x - toolsEnd) / slotW).toInt().coerceIn(0, 2)
        return if (suggestions.getOrNull(order[s]) != null) s else NONE
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        drawTools(canvas)
        val clip = clipText
        if (clip != null) {
            drawClip(canvas, clip)
            return
        }
        if (suggestions.isEmpty()) return
        val slotW = (width - toolsEnd) / 3f
        for (slot in 0..2) {
            val word = suggestions.getOrNull(order[slot]) ?: continue
            val left = toolsEnd + slot * slotW
            if (slot == pressed) {
                rect.set(left + dp(3f), dp(4f), left + slotW - dp(3f), height - dp(4f))
                fill.color = theme.keySpecial
                canvas.drawRoundRect(rect, dp(8f), dp(8f), fill)
            }
            val best = order[slot] == 0
            text.typeface = if (best) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.color = when {
                best && highlightBest -> theme.accent
                best -> theme.text
                else -> theme.textSpecial
            }
            text.textSize = dp(16f)
            val avail = slotW - dp(14f)
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
                val x = toolsEnd + d * slotW
                canvas.drawLine(x, height * 0.28f, x, height * 0.72f, line)
            }
        }
    }

    private fun drawTools(canvas: Canvas) {
        val cy = height / 2f
        for (t in 0..1) {
            val cx = toolW * t + toolW / 2f + dp(2f)
            val id = if (t == 0) TOOL_CLIPBOARD else TOOL_TRANSLATE
            if (pressed == id) {
                rect.set(cx - dp(17f), dp(4f), cx + dp(17f), height - dp(4f))
                fill.color = theme.keySpecial
                canvas.drawRoundRect(rect, dp(8f), dp(8f), fill)
            }
            icon.color = theme.textSpecial
            if (t == 0) {
                Icons.clipboard(canvas, icon, cx, cy, dp(18f))
            } else {
                // "A文" — tarjima belgisi
                text.color = theme.textSpecial
                text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = dp(13f)
                canvas.drawText("A", cx - dp(5f), cy - dp(1f), text)
                text.typeface = Typeface.DEFAULT
                text.textSize = dp(11f)
                canvas.drawText("文", cx + dp(5f), cy + dp(9f), text)
            }
        }
        line.color = theme.hint
        line.alpha = 70
        line.strokeWidth = dp(1f)
        canvas.drawLine(toolsEnd, height * 0.25f, toolsEnd, height * 0.75f, line)
    }

    private fun drawClip(canvas: Canvas, clip: String) {
        rect.set(toolsEnd + dp(6f), dp(5f), width - dp(6f), height - dp(5f))
        fill.color = if (pressed == CLIP) theme.keyPressed else theme.key
        canvas.drawRoundRect(rect, dp(10f), dp(10f), fill)
        icon.color = theme.accent
        Icons.clipboard(canvas, icon, rect.left + dp(16f), rect.centerY(), dp(14f))
        text.color = theme.text
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(14f)
        // chapda 30dp belgi, o'ngda 14dp bo'sh joy qolsin
        val avail = rect.width() - dp(44f)
        val oneLine = clip.replace('\n', ' ').replace('\t', ' ').trim()
        val shown = TextUtils.ellipsize(oneLine, text, avail, TextUtils.TruncateAt.END).toString()
        val fm = text.fontMetrics
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(shown, rect.left + dp(30f), rect.centerY() - (fm.ascent + fm.descent) / 2f, text)
        text.textAlign = Paint.Align.CENTER
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val s = targetAt(e.x)
                if (s != pressed) {
                    pressed = s
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val s = pressed
                pressed = NONE
                invalidate()
                if (s != NONE) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                when (s) {
                    TOOL_CLIPBOARD -> onTool?.invoke(0)
                    TOOL_TRANSLATE -> onTool?.invoke(1)
                    CLIP -> onClip?.invoke()
                    NONE -> {}
                    else -> onPick?.invoke(order[s])
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = NONE
                invalidate()
            }
        }
        return true
    }

    private companion object {
        const val NONE = -1
        const val TOOL_CLIPBOARD = 100
        const val TOOL_TRANSLATE = 101
        const val CLIP = 200
    }
}
