package uz.alifbo.klaviatura

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Klaviatura oynasi içidagi kursorli matn. Tizim EditText'i klaviaturaning öz oynasida işonçli
 * işlamaydi (oyna fokus olmaydi), shuning uçun matn va kursor şu yerda çiziladi:
 *  - bosilsa — kursor şu joyga ötadi (matndan keyingi böş joy bosilsa — oxiriga);
 *  - surilsa — matn aylantiriladi;
 *  - biroz bosib turib surilsa — kursor barmoq ortidan yuradi.
 */
@SuppressLint("ClickableViewAccessibility")
class CursorTextView(context: Context, private val singleLine: Boolean) : View(context) {
    /** Foydalanuvçi kursorni köçirdi. */
    var onCursor: ((Int) -> Unit)? = null

    /** Matn örnida turgan xabar bosildi. */
    var onMessageTap: (() -> Unit)? = null

    var textSizeDp = 15f
        set(value) {
            field = value
            built = null
            invalidate()
        }

    private var text = ""
    private var cursor = 0
    private var hint = ""
    private var message: String? = null
    private var textColor = 0
    private var hintColor = 0
    private var caretColor = 0
    private var messageColor = 0

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val caret = Paint(Paint.ANTI_ALIAS_FLAG)
    private var built: StaticLayout? = null
    private var builtWidth = 0
    private var offX = 0f
    private var offY = 0f
    private var reveal = true

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var scrolling = false
    private var dragging = false

    private val startDrag = Runnable {
        if (message == null && !scrolling) {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            moveCursorTo(lastX, lastY)
        }
    }

    private val fling: Runnable = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            offX = scroller.currX.toFloat()
            offY = scroller.currY.toFloat()
            invalidate()
            postOnAnimation(this)
        }
    }

    fun setColors(text: Int, hint: Int, caret: Int) {
        textColor = text
        hintColor = hint
        caretColor = caret
        invalidate()
    }

    fun show(text: String, cursor: Int, hint: String, message: String? = null, messageColor: Int = 0) {
        val at = cursor.coerceIn(0, text.length)
        val changed = text != this.text || at != this.cursor || message != this.message
        if (text != this.text || message != this.message || hint != this.hint) built = null
        this.text = text
        this.cursor = at
        this.hint = hint
        this.message = message
        this.messageColor = messageColor
        if (changed) {
            reveal = true
            scroller.forceFinished(true)
        }
        contentDescription = message ?: text.ifEmpty { hint }
        invalidate()
    }

    private val availW: Int get() = (width - paddingLeft - paddingRight).coerceAtLeast(1)
    private val availH: Int get() = (height - paddingTop - paddingBottom).coerceAtLeast(1)
    private val caretW: Float get() = dp(2f)

    private fun build(): StaticLayout {
        built?.let { if (builtWidth == width) return it }
        paint.textSize = dp(textSizeDp)
        val note = message
        var shown: CharSequence = note ?: text.ifEmpty { hint }
        // bir qatorli körinişda yangi qator probel bölib körinadi (belgilar soni özgarmaydi)
        if (singleLine) shown = shown.toString().replace(NEWLINE, ' ').replace(RETURN, ' ')
        var w = availW
        if (singleLine) {
            if (note != null || text.isEmpty()) {
                shown = TextUtils.ellipsize(shown, paint, (availW - caretW * 3), TextUtils.TruncateAt.END)
            } else {
                w = maxOf(availW, ceil(Layout.getDesiredWidth(shown, paint) + caretW * 2).toInt())
            }
        }
        val layout = StaticLayout.Builder.obtain(shown, 0, shown.length, paint, w).setIncludePad(false).build()
        built = layout
        builtWidth = width
        return layout
    }

    private fun textTop(layout: StaticLayout): Float =
        if (singleLine) (height - layout.height) / 2f else paddingTop.toFloat()

    private fun maxOffX(layout: StaticLayout): Float = (layout.width - availW).toFloat().coerceAtLeast(0f)

    private fun maxOffY(layout: StaticLayout): Float =
        if (singleLine) 0f else (layout.height - availH).toFloat().coerceAtLeast(0f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        reveal = true
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val layout = build()
        val isText = message == null && text.isNotEmpty()
        if (reveal) {
            reveal = false
            if (isText) {
                val x = layout.getPrimaryHorizontal(cursor)
                val margin = dp(18f)
                if (x - offX < margin) offX = x - margin
                if (x - offX > availW - margin) offX = x - availW + margin
                val line = layout.getLineForOffset(cursor)
                if (layout.getLineTop(line) < offY) offY = layout.getLineTop(line).toFloat()
                if (layout.getLineBottom(line) - offY > availH) offY = (layout.getLineBottom(line) - availH).toFloat()
            } else {
                offX = 0f
                offY = 0f
            }
        }
        offX = offX.coerceIn(0f, maxOffX(layout))
        offY = offY.coerceIn(0f, maxOffY(layout))
        val top = textTop(layout)

        canvas.save()
        if (singleLine) {
            canvas.clipRect(paddingLeft.toFloat() - caretW, 0f, (width - paddingRight).toFloat() + caretW, height.toFloat())
        } else {
            canvas.clipRect(paddingLeft.toFloat() - caretW, paddingTop.toFloat(), (width - paddingRight).toFloat() + caretW, (height - paddingBottom).toFloat())
        }
        canvas.translate(paddingLeft - offX, top - offY)
        paint.color = when {
            message != null -> messageColor
            text.isEmpty() -> hintColor
            else -> textColor
        }
        if (message == null && text.isEmpty()) {
            canvas.save()
            canvas.translate(caretW * 3, 0f)
            layout.draw(canvas)
            canvas.restore()
        } else {
            layout.draw(canvas)
        }
        if (message == null) {
            val line = if (isText) layout.getLineForOffset(cursor) else 0
            val x = if (isText) layout.getPrimaryHorizontal(cursor) else 0f
            caret.color = caretColor
            canvas.drawRect(x, layout.getLineTop(line).toFloat(), x + caretW, layout.getLineBottom(line).toFloat(), caret)
        }
        canvas.restore()
    }

    /** Ekran nuqtasiga mos matn o'rni. */
    internal fun offsetAt(x: Float, y: Float): Int {
        if (text.isEmpty()) return 0
        val layout = build()
        val ly = y - textTop(layout) + offY
        // matndan pastdagi böş joy — oxiriga
        if (!singleLine && ly > layout.height) return text.length
        val line = layout.getLineForVertical(ly.toInt().coerceAtLeast(0))
        return layout.getOffsetForHorizontal(line, x - paddingLeft + offX)
    }

    private fun moveCursorTo(x: Float, y: Float) {
        if (text.isEmpty()) return
        val at = offsetAt(x, y)
        if (at != cursor) onCursor?.invoke(at)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                scrolling = false
                dragging = false
                velocity = VelocityTracker.obtain().apply { addMovement(e) }
                postDelayed(startDrag, DRAG_AFTER_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x
                lastY = e.y
                if (dragging) {
                    moveCursorTo(e.x, e.y)
                } else if (scrolling) {
                    offX -= dx
                    offY -= dy
                    invalidate()
                } else if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                    scrolling = true
                    removeCallbacks(startDrag)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(startDrag)
                if (scrolling) {
                    startFling()
                } else if (!dragging) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (message != null) onMessageTap?.invoke() else moveCursorTo(e.x, e.y)
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startDrag)
                endTouch()
            }
        }
        return true
    }

    private fun startFling() {
        val v = velocity ?: return
        val layout = built ?: return
        v.computeCurrentVelocity(1000)
        scroller.fling(
            offX.toInt(), offY.toInt(), -v.xVelocity.toInt(), -v.yVelocity.toInt(),
            0, maxOffX(layout).toInt(), 0, maxOffY(layout).toInt(),
        )
        postOnAnimation(fling)
    }

    private fun endTouch() {
        scrolling = false
        dragging = false
        velocity?.recycle()
        velocity = null
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(startDrag)
        removeCallbacks(fling)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DRAG_AFTER_MS = 300L
        const val NEWLINE = '\n'
        const val RETURN = '\r'
    }
}
