package uz.alifbo.klaviatura

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.max

interface KeyboardListener {
    fun onText(text: String)
    fun onSpace()
    fun onDelete()
    fun onEnter()
    fun onShiftTap()
    fun onShiftHold()
    fun onPage(page: Page)
    fun onLanguageNext()

    /** [index] = yoqilgan tillar ro'yxatidagi o'rin; -1 = tizimdagi klaviatura tanlagich. */
    fun onLanguagePick(index: Int)
    fun onEmoji()
    fun onLanguageSwipe(step: Int)
}

/** Klaviaturaning o'zi: tugmalar Canvas'da chiziladi, teginishlar shu yerda qayta ishlanadi. */
@SuppressLint("ClickableViewAccessibility")
class KeyboardView(context: Context) : View(context) {

    private class Cell(val key: Key, val row: Int, val outer: RectF) {
        val inner = RectF()
    }

    private inner class Ptr {
        var cell: Cell? = null
        var popupItems: List<String>? = null
        var popupRects: List<RectF> = emptyList()
        var popupSel = 0
        var popupMode = 0 // 1 = belgilar, 2 = tillar
        var fired = false
        var swiped = false
        var anchorX = 0f
        var repeats = 0
        val longPress = Runnable { onLongPress(this) }
        val repeat: Runnable = object : Runnable {
            override fun run() {
                if (cell?.key?.kind != Kind.DELETE) return
                listener?.onDelete()
                repeats++
                ui.postDelayed(this, if (repeats > 10) 35L else 55L)
            }
        }
    }

    var listener: KeyboardListener? = null
    var overlay: OverlayView? = null
    var theme: KbTheme = Themes.light
    var keyHeightPx: Float = dp(52f)
    var textScale: Float = 1f
    var cornerPx: Float = dp(8f)
    var hapticOn: Boolean = true
    var soundOn: Boolean = false
    var showHints: Boolean = false
    var longPressMs: Long = 350L
    var casing: Boolean = true
    var shift: Shift = Shift.OFF
        set(value) {
            field = value
            invalidate()
        }
    var enterStyle: EnterStyle = EnterStyle.RETURN
        set(value) {
            field = value
            invalidate()
        }
    var langName: String = ""
    var langShorts: List<String> = emptyList()

    private var layout: KbLayout = Layouts.letters(Lang.UZ)
    private val cells = ArrayList<Cell>()
    private val ptrs = SparseArray<Ptr>()
    private val ui = Handler(Looper.getMainLooper())

    private val padSide = dp(3f)
    private val padTop = dp(6f)
    private val padBottom = dp(6f)
    private val gapX = dp(5f)
    private val gapY = dp(7f)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val locA = IntArray(2)
    private val locB = IntArray(2)

    fun setLayout(l: KbLayout) {
        layout = l
        cancelAll()
        rebuild()
        requestLayout()
        invalidate()
    }

    fun refresh() {
        rebuild()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (layout.rows.size * keyHeightPx + padTop + padBottom).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        cells.clear()
        if (width <= 0) return
        val unit = (width - 2 * padSide) / layout.total
        layout.rows.forEachIndexed { r, row ->
            var x = padSide
            val top = padTop + r * keyHeightPx
            for (k in row) {
                val w = unit * k.weight
                val cell = Cell(k, r, RectF(x, top, x + w, top + keyHeightPx))
                cell.inner.set(
                    cell.outer.left + gapX / 2f, cell.outer.top + gapY / 2f,
                    cell.outer.right - gapX / 2f, cell.outer.bottom - gapY / 2f,
                )
                cells.add(cell)
                x += w
            }
        }
    }

    private fun findCell(x: Float, y: Float): Cell? {
        if (cells.isEmpty()) return null
        val row = ((y - padTop) / keyHeightPx).toInt().coerceIn(0, layout.rows.size - 1)
        var best: Cell? = null
        var bestDist = Float.MAX_VALUE
        for (c in cells) {
            if (c.row != row || c.key.kind == Kind.SPACER) continue
            if (x >= c.outer.left && x < c.outer.right) return c
            val d = if (x < c.outer.left) c.outer.left - x else x - c.outer.right
            if (d < bestDist) {
                bestDist = d
                best = c
            }
        }
        return best
    }

    // ---------------------------------------------------------------- chizish

    private fun labelFor(key: Key): String = when (key.kind) {
        Kind.CHAR -> if (casing && shift != Shift.OFF) key.label.upperUz() else key.label
        Kind.TO_SYM1, Kind.TO_SYM2, Kind.TO_LETTERS -> key.label
        else -> ""
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        val pressed = HashSet<Cell>()
        for (i in 0 until ptrs.size()) ptrs.valueAt(i).cell?.let { pressed.add(it) }
        val hasNumberRow = layout.rows.firstOrNull()?.firstOrNull()?.label == "1" && layout.rows.size == 5
        for (c in cells) {
            if (c.key.kind == Kind.SPACER) continue
            drawKey(canvas, c, c in pressed, hasNumberRow)
        }
    }

    private val body = RectF()
    private val shadowRect = RectF()
    private val shadowDepth = dp(1.3f)
    private val faceRegular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val faceMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun drawKey(canvas: Canvas, c: Cell, pressed: Boolean, hasNumberRow: Boolean) {
        val k = c.key
        val special = k.kind != Kind.CHAR && k.kind != Kind.SPACE
        val base = when {
            k.kind == Kind.ENTER -> theme.accent
            special -> theme.keySpecial
            else -> theme.key
        }

        // tugma ostidagi yupqa soya: bosilganda tugma unga "cho'kadi"
        body.set(c.inner)
        if (pressed) {
            body.offset(0f, shadowDepth * 0.8f)
        } else {
            shadowRect.set(c.inner)
            shadowRect.offset(0f, shadowDepth)
            keyPaint.color = ColorUtils.blendARGB(theme.background, Color.BLACK, if (theme.isDark) 0.55f else 0.17f)
            canvas.drawRoundRect(shadowRect, cornerPx, cornerPx, keyPaint)
        }
        keyPaint.color = if (pressed) {
            if (k.kind == Kind.ENTER) ColorUtils.blendARGB(theme.accent, Color.BLACK, 0.2f) else theme.keyPressed
        } else base
        canvas.drawRoundRect(body, cornerPx, cornerPx, keyPaint)

        val fg = when {
            k.kind == Kind.ENTER -> theme.accentText
            k.kind == Kind.SHIFT && shift != Shift.OFF && casing -> theme.accent
            special -> theme.textSpecial
            else -> theme.text
        }
        val cx = body.centerX()
        val cy = body.centerY()
        val iconSize = minOf(keyHeightPx * 0.40f, body.width() * 0.62f) * textScale.coerceAtMost(1.15f)
        iconPaint.color = fg

        when (k.kind) {
            Kind.CHAR -> drawLabel(canvas, labelFor(k), body, fg, keyHeightPx * 0.42f * textScale, faceRegular)
            Kind.TO_SYM1, Kind.TO_SYM2, Kind.TO_LETTERS ->
                drawLabel(canvas, k.label, body, fg, keyHeightPx * 0.27f * textScale, faceMedium)
            Kind.SPACE -> drawSpace(canvas, body)
            Kind.SHIFT -> Icons.shift(canvas, iconPaint, cx, cy, iconSize, if (casing) shift else Shift.OFF)
            Kind.DELETE -> Icons.delete(canvas, iconPaint, cx, cy, iconSize)
            Kind.ENTER -> Icons.enter(canvas, iconPaint, cx, cy, iconSize, enterStyle)
            Kind.LANGUAGE -> Icons.globe(canvas, iconPaint, cx, cy, iconSize)
            Kind.EMOJI -> Icons.face(canvas, iconPaint, cx, cy, iconSize)
            Kind.SPACER -> {}
        }

        if (showHints && !hasNumberRow && k.kind == Kind.CHAR && c.row == 0) {
            val d = k.popup.firstOrNull()
            if (d != null && d.length == 1 && d[0].isDigit()) {
                textPaint.color = theme.hint
                textPaint.typeface = faceRegular
                textPaint.textSize = keyHeightPx * 0.2f * textScale
                canvas.drawText(d, body.right - dp(6f), body.top + textPaint.textSize + dp(1f), textPaint)
            }
        }
    }

    /** Probel: til nomi va (bir nechta til bo'lsa) yonga surib almashtirish belgilari. */
    private fun drawSpace(canvas: Canvas, r: RectF) {
        drawLabel(canvas, langName, r, theme.hint, keyHeightPx * 0.25f * textScale, faceMedium)
        if (langShorts.size < 2 || r.width() < dp(120f)) return
        textPaint.color = ColorUtils.setAlphaComponent(theme.hint, 140)
        textPaint.typeface = faceRegular
        textPaint.textSize = keyHeightPx * 0.32f
        val fm = textPaint.fontMetrics
        val y = r.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText("‹", r.left + dp(16f), y, textPaint)
        canvas.drawText("›", r.right - dp(16f), y, textPaint)
    }

    private fun drawLabel(canvas: Canvas, text: String, r: RectF, color: Int, size: Float, face: Typeface) {
        if (text.isEmpty()) return
        textPaint.color = color
        textPaint.typeface = face
        textPaint.textSize = size
        val avail = r.width() - dp(6f)
        val w = textPaint.measureText(text)
        if (w > avail && w > 0f) textPaint.textSize = size * avail / w
        val fm = textPaint.fontMetrics
        canvas.drawText(text, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    // ---------------------------------------------------------------- teginish

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val cell = findCell(x, y) ?: return
        val p = Ptr()
        p.cell = cell
        p.anchorX = x
        ptrs.put(id, p)
        haptic(false)
        enterKey(p, cell, true)
        invalidate()
    }

    private fun enterKey(p: Ptr, cell: Cell, initial: Boolean) {
        val k = cell.key
        if (k.kind == Kind.CHAR) showPreview(cell)
        when (k.kind) {
            Kind.DELETE -> if (initial) {
                listener?.onDelete()
                p.repeats = 0
                ui.postDelayed(p.repeat, 400L)
            }
            Kind.CHAR -> if (k.popup.isNotEmpty()) ui.postDelayed(p.longPress, longPressMs)
            Kind.LANGUAGE, Kind.SHIFT -> ui.postDelayed(p.longPress, longPressMs)
            else -> {}
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = ptrs.get(id) ?: return
        if (p.popupItems != null) {
            val idx = popupIndexAt(p, x)
            if (idx != p.popupSel) {
                p.popupSel = idx
                overlay?.setPopupSelection(idx)
            }
            return
        }
        val cur = p.cell ?: return
        if (cur.key.kind == Kind.SPACE) {
            val dx = x - p.anchorX
            val threshold = max(dp(40f), cur.inner.width() / 4f)
            if (p.swiped || abs(dx) > threshold) {
                if (abs(dx) > threshold) {
                    p.swiped = true
                    p.anchorX = x
                    listener?.onLanguageSwipe(if (dx > 0) 1 else -1)
                    haptic(false)
                }
                return
            }
        }
        val next = findCell(x, y) ?: return
        if (next !== cur) {
            ui.removeCallbacks(p.longPress)
            ui.removeCallbacks(p.repeat)
            overlay?.hidePreview()
            p.cell = next
            p.fired = false
            p.anchorX = x
            enterKey(p, next, false)
            invalidate()
        }
    }

    private fun up(id: Int) {
        val p = ptrs.get(id) ?: return
        ptrs.remove(id)
        ui.removeCallbacks(p.longPress)
        ui.removeCallbacks(p.repeat)
        overlay?.hidePreview()
        val items = p.popupItems
        if (items != null) {
            overlay?.hidePopup()
            val sel = p.popupSel.coerceIn(0, items.size - 1)
            if (p.popupMode == 1) listener?.onText(items[sel])
            else if (p.popupMode == 2) listener?.onLanguagePick(if (sel < langShorts.size) sel else -1)
        } else if (!p.fired && !p.swiped) {
            p.cell?.let { activate(it.key) }
        }
        invalidate()
    }

    private fun activate(key: Key) {
        when (key.kind) {
            Kind.CHAR -> listener?.onText(labelFor(key))
            Kind.SPACE -> listener?.onSpace()
            Kind.ENTER -> listener?.onEnter()
            Kind.SHIFT -> listener?.onShiftTap()
            Kind.TO_SYM1 -> listener?.onPage(Page.SYM1)
            Kind.TO_SYM2 -> listener?.onPage(Page.SYM2)
            Kind.TO_LETTERS -> listener?.onPage(Page.LETTERS)
            Kind.LANGUAGE -> listener?.onLanguageNext()
            Kind.EMOJI -> listener?.onEmoji()
            Kind.DELETE, Kind.SPACER -> {}
        }
    }

    private fun cancelAll() {
        for (i in 0 until ptrs.size()) {
            val p = ptrs.valueAt(i)
            ui.removeCallbacks(p.longPress)
            ui.removeCallbacks(p.repeat)
        }
        ptrs.clear()
        overlay?.hidePreview()
        overlay?.hidePopup()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- uzoq bosish

    private fun onLongPress(p: Ptr) {
        val cell = p.cell ?: return
        val key = cell.key
        when (key.kind) {
            Kind.CHAR -> {
                if (key.popup.isEmpty()) return
                val items = key.popup.map { if (casing && shift != Shift.OFF) it.upperUz() else it }
                openPopup(p, cell, items, 1, 40f)
            }
            Kind.LANGUAGE -> openPopup(p, cell, langShorts + "···", 2, 48f)
            Kind.SHIFT -> {
                p.fired = true
                haptic(true)
                listener?.onShiftHold()
            }
            else -> {}
        }
    }

    private fun openPopup(p: Ptr, cell: Cell, items: List<String>, mode: Int, minItemDp: Float) {
        val ov = overlay ?: return
        val itemW = max(cell.outer.width(), dp(minItemDp))
        val h = keyHeightPx * 1.05f
        val top = cell.outer.top - h + keyHeightPx * 0.12f
        val toRight = cell.outer.left + items.size * itemW <= width
        val rects = items.indices.map { i ->
            val l = if (toRight) cell.outer.left + i * itemW else cell.outer.right - (i + 1) * itemW
            RectF(l, top, l + itemW, top + h)
        }
        overlay?.hidePreview()
        p.popupItems = items
        p.popupRects = rects
        p.popupMode = mode
        p.popupSel = 0
        p.fired = true
        haptic(true)
        ov.showPopup(rects.map { toOverlay(it) }, items, 0)
    }

    private fun popupIndexAt(p: Ptr, x: Float): Int {
        val rects = p.popupRects
        for (i in rects.indices) if (x >= rects[i].left && x < rects[i].right) return i
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in rects.indices) {
            val d = abs(rects[i].centerX() - x)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun showPreview(cell: Cell) {
        val ov = overlay ?: return
        ov.showPreview(toOverlay(cell.inner), labelFor(cell.key), keyHeightPx, cornerPx)
    }

    private fun toOverlay(r: RectF): RectF {
        val ov = overlay ?: return r
        getLocationInWindow(locA)
        ov.getLocationInWindow(locB)
        return RectF(r).apply { offset((locA[0] - locB[0]).toFloat(), (locA[1] - locB[1]).toFloat()) }
    }

    private fun haptic(long: Boolean) {
        if (hapticOn) {
            performHapticFeedback(
                if (long) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP,
            )
        }
        if (soundOn) playSoundEffect(SoundEffectConstants.CLICK)
    }
}
