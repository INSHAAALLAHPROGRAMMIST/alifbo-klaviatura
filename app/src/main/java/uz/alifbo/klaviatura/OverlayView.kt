package uz.alifbo.klaviatura

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Klaviatura ustida chiziladigan yordamchi qatlam: bosilgan harf ko'rinishi va uzoq bosish oynasi. Teginishni ushlamaydi. */
class OverlayView(context: Context) : View(context) {
    var theme: KbTheme = Themes.light

    private var prevRect: RectF? = null
    private var prevText = ""
    private var prevKeyH = 0f

    private var popRects: List<RectF> = emptyList()
    private var popItems: List<String> = emptyList()
    private var popSel = 0
    private var popActive = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    fun showPreview(keyRect: RectF, label: String, keyH: Float) {
        prevRect = RectF(keyRect)
        prevText = label
        prevKeyH = keyH
        invalidate()
    }

    fun hidePreview() {
        if (prevRect != null) {
            prevRect = null
            invalidate()
        }
    }

    fun showPopup(rects: List<RectF>, items: List<String>, selected: Int) {
        popRects = rects
        popItems = items
        popSel = selected
        popActive = true
        invalidate()
    }

    fun setPopupSelection(i: Int) {
        popSel = i
        invalidate()
    }

    fun hidePopup() {
        if (popActive) {
            popActive = false
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        prevRect?.let { drawPreview(canvas, it) }
        if (popActive) drawPopup(canvas)
    }

    private fun drawPreview(canvas: Canvas, key: RectF) {
        if (prevText.isEmpty()) return
        val w = max(key.width() * 1.25f, dp(48f))
        val h = prevKeyH * 1.2f
        val left = (key.centerX() - w / 2f).coerceIn(dp(2f), max(dp(2f), width - w - dp(2f)))
        val bottom = key.top + prevKeyH * 0.30f
        val r = RectF(left, bottom - h, left + w, bottom)
        val corner = dp(10f)
        fill.color = theme.popup
        canvas.drawRoundRect(r, corner, corner, fill)
        border.color = theme.hint
        border.strokeWidth = dp(0.8f)
        canvas.drawRoundRect(r, corner, corner, border)
        text.color = theme.popupText
        text.textSize = prevKeyH * 0.62f
        val fm = text.fontMetrics
        canvas.drawText(prevText, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun drawPopup(canvas: Canvas) {
        if (popRects.isEmpty()) return
        val corner = dp(10f)
        val bg = RectF(
            popRects.minOf { it.left } - dp(2f), popRects.first().top - dp(2f),
            popRects.maxOf { it.right } + dp(2f), popRects.first().bottom + dp(2f),
        )
        fill.color = theme.popup
        canvas.drawRoundRect(bg, corner, corner, fill)
        border.color = theme.hint
        border.strokeWidth = dp(0.8f)
        canvas.drawRoundRect(bg, corner, corner, border)
        for (i in popRects.indices) {
            val r = popRects[i]
            if (i == popSel) {
                fill.color = theme.accent
                canvas.drawRoundRect(RectF(r).apply { inset(dp(2f), dp(2f)) }, corner - dp(2f), corner - dp(2f), fill)
            }
            text.color = if (i == popSel) theme.accentText else theme.popupText
            text.textSize = min(r.height() * 0.5f, dp(26f))
            val label = popItems[i]
            val avail = r.width() - dp(6f)
            val w = text.measureText(label)
            if (w > avail && w > 0f) text.textSize = text.textSize * avail / w
            val fm = text.fontMetrics
            canvas.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, text)
        }
    }
}
