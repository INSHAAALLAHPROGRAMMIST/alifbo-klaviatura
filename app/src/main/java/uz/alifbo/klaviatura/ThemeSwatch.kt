package uz.alifbo.klaviatura

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Sozlamalardagi tema katagi: temaning ranglarida chizilgan kichik klaviatura. */
class ThemeSwatch(context: Context) : View(context) {
    var theme: KbTheme = Themes.light
        set(value) {
            field = value
            invalidate()
        }
    var selectedTile: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var ring: Int = 0xFF2F6FED.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val k = RectF()

    override fun onDraw(canvas: Canvas) {
        val inset = dp(3f)
        r.set(inset, inset, width - inset, height - inset)
        paint.style = Paint.Style.FILL
        paint.color = theme.background
        canvas.drawRoundRect(r, dp(12f), dp(12f), paint)

        val pad = dp(8f)
        val gap = dp(3f)
        val kh = (r.height() - 2 * pad - gap) / 2f
        val kw = (r.width() - 2 * pad - 4 * gap) / 5f
        val radius = dp(3f)
        // 1-qator: beshta harf tugmasi
        for (i in 0 until 5) {
            val x = r.left + pad + i * (kw + gap)
            key(canvas, x, r.top + pad, kw, kh, theme.key, radius)
        }
        // 2-qator: maxsus tugma, probel, Enter (urg'u rangi)
        val y = r.top + pad + kh + gap
        key(canvas, r.left + pad, y, kw, kh, theme.keySpecial, radius)
        key(canvas, r.left + pad + kw + gap, y, kw * 3 + gap * 2, kh, theme.key, radius)
        key(canvas, r.left + pad + 4 * (kw + gap), y, kw, kh, theme.accent, radius)

        if (selectedTile) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f)
            paint.color = ring
            r.inset(-dp(1.5f), -dp(1.5f))
            canvas.drawRoundRect(r, dp(13f), dp(13f), paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun key(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float) {
        k.set(x, y, x + w, y + h)
        paint.color = color
        canvas.drawRoundRect(k, radius, radius, paint)
    }
}
