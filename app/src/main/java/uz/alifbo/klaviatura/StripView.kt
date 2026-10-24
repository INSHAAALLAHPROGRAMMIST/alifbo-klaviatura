package uz.alifbo.klaviatura

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Klaviatura tepasidagi qator. Keyingi bosqichda bu yerda so'z takliflari chiqadi; hozircha faol til nomi. */
class StripView(context: Context) : View(context) {
    var theme: KbTheme = Themes.light
        set(value) {
            field = value
            invalidate()
        }
    var title: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        if (title.isEmpty()) return
        paint.color = theme.hint
        paint.textSize = dp(13f)
        val fm = paint.fontMetrics
        canvas.drawText(title, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, paint)
    }
}
