package uz.alifbo.klaviatura

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Klaviatura ustida chiziladigan yordamchi qatlam: bosilgan harf ko'rinishi va uzoq bosish oynasi. Teginishni ushlamaydi. */
class OverlayView(context: Context) : View(context) {
    var theme: KbTheme = Themes.light

    private var prevKey: RectF? = null
    private var prevText = ""
    private var prevKeyH = 0f
    private var prevCorner = 0f

    private var popRects: List<RectF> = emptyList()
    private var popItems: List<String> = emptyList()
    private var popSel = 0
    private var popActive = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val bubble = Path()
    private val part = Path()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    /**
     * Qatlam o'z balandligini talab qilmaydi: aks holda View'ning standart o'lchovi (getDefaultSize)
     * AT_MOST rejimida butun ekran balandligini oladi va IME oynasi to'liq ekranga cho'ziladi.
     * Ota-ona FrameLayout ikkinchi o'tishda aniq (EXACTLY) balandlik beradi.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else 0
        setMeasuredDimension(w, h)
    }

    /** [keyRect] — tugmaning ko'rinadigan (ichki) chegarasi, shu qatlam koordinatalarida. */
    fun showPreview(keyRect: RectF, label: String, keyH: Float, corner: Float) {
        prevKey = RectF(keyRect)
        prevText = label
        prevKeyH = keyH
        prevCorner = corner
        invalidate()
    }

    fun hidePreview() {
        if (prevKey != null) {
            prevKey = null
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
        prevKey?.let { drawPreview(canvas, it) }
        if (popActive) drawPopup(canvas)
    }

    private fun shadowColor(): Int = if (theme.isDark) 0x99000000.toInt() else 0x40000000

    /**
     * Bosilgan tugmadan yuqoriga "o'sib chiqadigan" pufakcha: tepada keng qism (katta harf bilan),
     * pastda tugmaning o'zi, ular silliq bo'yin bilan qo'shilgan.
     */
    private fun drawPreview(canvas: Canvas, key: RectF) {
        if (prevText.isEmpty()) return
        val corner = max(prevCorner, dp(6f))
        val bw = min(max(key.width() * 1.45f, dp(54f)), width - dp(4f))
        val bh = min(prevKeyH * 1.08f, max(dp(30f), key.top - dp(1f)))
        val left = (key.centerX() - bw / 2f).coerceIn(dp(2f), max(dp(2f), width - bw - dp(2f)))
        val top = max(dp(1f), key.top - bh)
        val head = RectF(left, top, left + bw, key.top + dp(2f))

        bubble.reset()
        bubble.addRoundRect(head, dp(12f), dp(12f), Path.Direction.CW)
        part.reset()
        part.addRoundRect(key, corner, corner, Path.Direction.CW)
        bubble.op(part, Path.Op.UNION)
        // bo'yin: pufakcha pastidan tugma tepasiga qiya chiziqlar
        part.reset()
        val neckTop = head.bottom - dp(10f)
        part.moveTo(max(head.left + dp(6f), key.left - dp(10f)), neckTop)
        part.lineTo(min(head.right - dp(6f), key.right + dp(10f)), neckTop)
        part.lineTo(key.right, key.top + corner)
        part.lineTo(key.left, key.top + corner)
        part.close()
        bubble.op(part, Path.Op.UNION)

        shadow.color = shadowColor()
        canvas.save()
        canvas.translate(0f, dp(1.5f))
        canvas.drawPath(bubble, shadow)
        canvas.restore()
        fill.color = theme.popup
        canvas.drawPath(bubble, fill)

        text.color = theme.popupText
        text.textSize = min(prevKeyH * 0.62f, head.height() * 0.78f)
        val fm = text.fontMetrics
        canvas.drawText(prevText, head.centerX(), head.centerY() - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun drawPopup(canvas: Canvas) {
        if (popRects.isEmpty()) return
        val top = popRects.first().top - dp(2f)
        val dy = if (top < dp(1f)) dp(1f) - top else 0f
        canvas.save()
        canvas.translate(0f, dy)
        drawPopupInner(canvas)
        canvas.restore()
    }

    private fun drawPopupInner(canvas: Canvas) {
        val corner = dp(12f)
        val bg = RectF(
            popRects.minOf { it.left } - dp(3f), popRects.first().top - dp(3f),
            popRects.maxOf { it.right } + dp(3f), popRects.first().bottom + dp(3f),
        )
        shadow.color = shadowColor()
        canvas.drawRoundRect(RectF(bg).apply { offset(0f, dp(1.5f)) }, corner, corner, shadow)
        fill.color = theme.popup
        canvas.drawRoundRect(bg, corner, corner, fill)
        for (i in popRects.indices) {
            val r = popRects[i]
            if (i == popSel) {
                fill.color = theme.accent
                canvas.drawRoundRect(RectF(r).apply { inset(dp(2f), dp(2f)) }, corner - dp(3f), corner - dp(3f), fill)
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
