package uz.alifbo.klaviatura

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Barcha belgilar [-1, 1] birlik kvadratida chiziladi va kerakli o'lchamga ko'chiriladi. */
object Icons {
    private val m = Matrix()
    private val out = Path()

    private fun place(src: Path, cx: Float, cy: Float, size: Float): Path {
        m.reset()
        m.setScale(size / 2f, size / 2f)
        m.postTranslate(cx, cy)
        out.reset()
        src.transform(m, out)
        return out
    }

    private val shiftPath = Path().apply {
        moveTo(0f, -1f); lineTo(1f, -0.05f); lineTo(0.45f, -0.05f); lineTo(0.45f, 0.6f)
        lineTo(-0.45f, 0.6f); lineTo(-0.45f, -0.05f); lineTo(-1f, -0.05f); close()
    }

    private val deletePath = Path().apply {
        moveTo(-1f, 0f); lineTo(-0.45f, -0.7f); lineTo(1f, -0.7f); lineTo(1f, 0.7f)
        lineTo(-0.45f, 0.7f); close()
        moveTo(-0.05f, -0.3f); lineTo(0.55f, 0.3f)
        moveTo(0.55f, -0.3f); lineTo(-0.05f, 0.3f)
    }

    private val returnPath = Path().apply {
        moveTo(0.85f, -0.7f); lineTo(0.85f, 0.1f); lineTo(-0.8f, 0.1f)
        moveTo(-0.2f, -0.5f); lineTo(-0.8f, 0.1f); lineTo(-0.2f, 0.7f)
    }

    private val doneCheckPath = Path().apply {
        moveTo(-0.85f, 0.05f); lineTo(-0.3f, 0.6f); lineTo(0.85f, -0.6f)
    }

    private val nextPath = Path().apply {
        moveTo(-0.9f, 0f); lineTo(0.8f, 0f)
        moveTo(0.2f, -0.6f); lineTo(0.85f, 0f); lineTo(0.2f, 0.6f)
    }

    private val searchPath = Path().apply {
        addCircle(-0.2f, -0.2f, 0.6f, Path.Direction.CW)
        moveTo(0.25f, 0.25f); lineTo(0.9f, 0.9f)
    }

    private val sendPath = Path().apply {
        moveTo(-0.9f, -0.75f); lineTo(0.95f, 0f); lineTo(-0.9f, 0.75f); lineTo(-0.55f, 0f); close()
    }

    private val globePath = Path().apply {
        addCircle(0f, 0f, 0.9f, Path.Direction.CW)
        addOval(RectF(-0.4f, -0.9f, 0.4f, 0.9f), Path.Direction.CW)
        moveTo(-0.9f, 0f); lineTo(0.9f, 0f)
    }

    private val facePath = Path().apply {
        addCircle(0f, 0f, 0.9f, Path.Direction.CW)
        moveTo(-0.5f, 0.2f)
        arcTo(RectF(-0.5f, -0.3f, 0.5f, 0.55f), 160f, -140f)
    }

    private fun stroke(p: Paint, size: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = size * 0.09f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
    }

    fun shift(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float, shift: Shift) {
        if (shift == Shift.OFF) stroke(p, s) else p.style = Paint.Style.FILL
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = s * 0.09f
        val dy = if (shift == Shift.CAPS) -s * 0.06f else 0f
        c.drawPath(place(shiftPath, cx, cy + dy, s), p)
        if (shift == Shift.CAPS) {
            p.style = Paint.Style.FILL
            val u = s / 2f
            c.drawRect(cx - 0.45f * u, cy + 0.82f * u, cx + 0.45f * u, cy + 0.98f * u, p)
        }
    }

    fun delete(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        stroke(p, s)
        c.drawPath(place(deletePath, cx, cy, s), p)
    }

    fun enter(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float, style: EnterStyle) {
        stroke(p, s)
        when (style) {
            EnterStyle.RETURN -> c.drawPath(place(returnPath, cx, cy, s), p)
            EnterStyle.DONE -> c.drawPath(place(doneCheckPath, cx, cy, s), p)
            EnterStyle.NEXT, EnterStyle.GO -> c.drawPath(place(nextPath, cx, cy, s), p)
            EnterStyle.SEARCH -> c.drawPath(place(searchPath, cx, cy, s), p)
            EnterStyle.SEND -> {
                p.style = Paint.Style.FILL
                c.drawPath(place(sendPath, cx, cy, s), p)
            }
        }
    }

    fun globe(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        stroke(p, s)
        p.strokeWidth = s * 0.075f
        c.drawPath(place(globePath, cx, cy, s), p)
    }

    fun face(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        stroke(p, s)
        p.strokeWidth = s * 0.075f
        c.drawPath(place(facePath, cx, cy, s), p)
        p.style = Paint.Style.FILL
        val u = s / 2f
        c.drawCircle(cx - 0.33f * u, cy - 0.3f * u, 0.1f * u, p)
        c.drawCircle(cx + 0.33f * u, cy - 0.3f * u, 0.1f * u, p)
    }
}
