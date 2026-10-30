package uz.alifbo.klaviatura

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.emoji2.emojipicker.EmojiPickerView

/** Pastki qatordagi "ABC" va o'chirish tugmalari. */
@SuppressLint("ClickableViewAccessibility")
internal class BarButton(
    context: Context,
    private val label: String?,
    private val onTap: () -> Unit,
    private val repeating: Boolean = false,
) : View(context) {
    var theme: KbTheme = Themes.light
        set(value) {
            field = value
            invalidate()
        }
    private var pressedNow = false
    private val ui = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val tick: Runnable = object : Runnable {
        override fun run() {
            onTap()
            ui.postDelayed(this, 50L)
        }
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(dp(4f), dp(4f), width - dp(4f), height - dp(4f))
        paint.style = Paint.Style.FILL
        paint.color = if (pressedNow) theme.keyPressed else theme.keySpecial
        canvas.drawRoundRect(rect, dp(8f), dp(8f), paint)
        paint.color = theme.textSpecial
        if (label != null) {
            paint.textSize = dp(15f)
            val fm = paint.fontMetrics
            canvas.drawText(label, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, paint)
        } else {
            Icons.delete(canvas, paint, width / 2f, height / 2f, dp(22f))
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedNow = true
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onTap()
                if (repeating) ui.postDelayed(tick, 400L)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedNow = false
                ui.removeCallbacks(tick)
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}

/** Emoji paneli: androidx'ning rasmiy EmojiPickerView'i (toifalar, so'nggilar, teri ranglari) + pastki qator. */
class EmojiPanel(
    context: Context,
    private val onPick: (String) -> Unit,
    private val onDelete: () -> Unit,
    private val onBack: () -> Unit,
) : LinearLayout(context) {

    private val holder = FrameLayout(context)
    private val abc = BarButton(context, "ABC", { onBack() })
    private val del = BarButton(context, null, { onDelete() }, repeating = true)
    private var builtForDark: Boolean? = null

    init {
        orientation = VERTICAL
        addView(holder, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar.addView(abc, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.4f))
        bar.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 5f))
        bar.addView(del, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.4f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46f).toInt()))
    }

    fun applyTheme(t: KbTheme) {
        setBackgroundColor(t.background)
        abc.theme = t
        del.theme = t
        if (builtForDark != t.isDark) buildPicker(t.isDark)
    }

    private fun buildPicker(dark: Boolean) {
        builtForDark = dark
        holder.removeAllViews()
        try {
            val themed = ContextThemeWrapper(
                context,
                if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light,
            )
            val picker = EmojiPickerView(themed)
            picker.setOnEmojiPickedListener { item -> onPick(item.emoji) }
            holder.addView(picker, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        } catch (t: Throwable) {
            val msg = TextView(context).apply {
                text = "Emoji paneli ochilmadi"
                gravity = Gravity.CENTER
            }
            holder.addView(msg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }
}
