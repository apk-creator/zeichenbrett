package app.zeichenbrett

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun roundBg(ctx: Context, color: Int, radiusDp: Float = 10f) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = ctx.dp(radiusDp)
}

/** Weiße Palette mit leicht gerundeten Ecken. */
fun palette(ctx: Context, horizontal: Boolean): LinearLayout = LinearLayout(ctx).apply {
    orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    background = roundBg(ctx, Pal.WHITE)
    elevation = ctx.dp(3f)
    val p = ctx.dpi(4f)
    setPadding(p, p, p, p)
}

/** Paletten-Button: Symbol und/oder Text, invertiert wenn aktiv oder gedrückt. */
@SuppressLint("ViewConstructor")
class Btn(ctx: Context) : View(ctx) {
    var icon: String? = null
        set(v) { field = v; invalidate() }
    var iconFill: Int? = null
    var text: String? = null
        set(v) { field = v; invalidate() }
    var badge: String? = null
        set(v) { field = v; invalidate() }
    var active = false
        set(v) { field = v; invalidate() }
    var enabledBtn = true
        set(v) { field = v; invalidate() }
    var grey = false
    var textScale = 0.3f
    var onTap: (() -> Unit)? = null

    private var down = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun flash() {
        down = true; invalidate()
        postDelayed({ down = false; invalidate() }, 250)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
            MotionEvent.ACTION_MOVE -> {
                val inside = e.x >= 0 && e.y >= 0 && e.x <= width && e.y <= height
                if (inside != down) { down = inside; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val was = down
                down = false; invalidate()
                if (was && enabledBtn) onTap?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val inv = active || (down && enabledBtn)
        if (inv) {
            p.style = Paint.Style.FILL; p.color = Pal.BLACK
            val r = context.dp(7f)
            c.drawRoundRect(1f, 1f, w - 1f, h - 1f, r, r, p)
        }
        val fg = when {
            inv -> Pal.WHITE
            !enabledBtn || grey -> Pal.GREY
            else -> Pal.BLACK
        }
        val ic = icon; val tx = text
        tp.color = fg
        if (ic != null && tx != null) {
            Icons.draw(c, ic, w / 2f, h * 0.38f, h * 0.46f, fg, iconFill, p, inv)
            tp.textSize = h * 0.22f
            c.drawText(tx, w / 2f, h * 0.9f, tp)
        } else if (ic != null) {
            Icons.draw(c, ic, w / 2f, h / 2f, h * 0.52f, fg, iconFill, p, inv)
        } else if (tx != null) {
            tp.textSize = h * textScale
            val b = badge
            if (b != null) {
                val tw = tp.measureText(tx)
                val isz = h * 0.34f
                val total = isz + context.dp(4f) + tw
                val x0 = (w - total) / 2f
                Icons.draw(c, b, x0 + isz / 2f, h / 2f, isz, fg, null, p, inv)
                c.drawText(tx, x0 + isz + context.dp(4f) + tw / 2f, h / 2f - (tp.descent() + tp.ascent()) / 2f, tp)
            } else {
                c.drawText(tx, w / 2f, h / 2f - (tp.descent() + tp.ascent()) / 2f, tp)
            }
        }
    }
}

/** Einfache Liniensymbole im 24er-Raster. */
object Icons {
    private val path = Path()

    fun draw(c: Canvas, name: String, cx: Float, cy: Float, size: Float, fg: Int, fill: Int?, p: Paint, inverted: Boolean) {
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(size / 24f, size / 24f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = fg
        path.reset()
        when (name) {
            "fold" -> {
                path.moveTo(2f, 17f); path.lineTo(7f, 7f); path.lineTo(12f, 17f); path.lineTo(17f, 7f); path.lineTo(22f, 17f)
                c.drawPath(path, p)
            }
            "pen" -> {
                path.moveTo(4f, 20f); path.lineTo(5.2f, 15.2f); path.lineTo(15.5f, 4.9f)
                path.lineTo(19.1f, 8.5f); path.lineTo(8.8f, 18.8f); path.close()
                if (fill != null) {
                    p.style = Paint.Style.FILL; p.color = fill
                    c.drawPath(path, p)
                    p.style = Paint.Style.STROKE; p.color = fg
                }
                c.drawPath(path, p)
                c.drawLine(5.2f, 15.2f, 8.8f, 18.8f, p)
            }
            "eraser" -> {
                path.moveTo(3f, 14f); path.lineTo(11f, 6f); path.lineTo(19f, 14f); path.lineTo(13f, 20f); path.lineTo(9f, 20f); path.close()
                c.drawPath(path, p)
                c.drawLine(7f, 10f, 15f, 18f, p)
                c.drawLine(13f, 20f, 21f, 20f, p)
            }
            "ruler" -> {
                c.drawRect(2f, 8f, 22f, 16f, p)
                var x = 5f; var k = 0
                while (x < 21f) { c.drawLine(x, 8f, x, if (k % 2 == 0) 12f else 10.5f, p); x += 3f; k++ }
            }
            "undo", "redo" -> {
                if (name == "redo") c.scale(-1f, 1f, 12f, 12f)
                path.moveTo(9f, 5f); path.lineTo(4.5f, 9.5f); path.lineTo(9f, 14f)
                path.moveTo(4.5f, 9.5f); path.lineTo(14f, 9.5f)
                path.cubicTo(18f, 9.5f, 20f, 12f, 20f, 14.5f)
                path.cubicTo(20f, 17f, 18f, 19.5f, 14f, 19.5f); path.lineTo(10f, 19.5f)
                c.drawPath(path, p)
            }
            "import" -> {
                path.moveTo(12f, 3f); path.lineTo(12f, 14f)
                path.moveTo(7.5f, 9.5f); path.lineTo(12f, 14f); path.lineTo(16.5f, 9.5f)
                path.moveTo(4f, 14f); path.lineTo(4f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 14f)
                c.drawPath(path, p)
            }
            "save" -> {
                c.drawRect(4f, 4f, 20f, 20f, p)
                path.moveTo(8f, 4f); path.lineTo(8f, 9f); path.lineTo(15f, 9f); path.lineTo(15f, 4f)
                c.drawPath(path, p)
                c.drawRect(7f, 13f, 17f, 20f, p)
            }
            "close" -> {
                c.drawLine(6f, 6f, 18f, 18f, p); c.drawLine(18f, 6f, 6f, 18f, p)
            }
            "lock" -> {
                path.moveTo(8.5f, 11f); path.lineTo(8.5f, 8f); path.cubicTo(8.5f, 3.5f, 15.5f, 3.5f, 15.5f, 8f); path.lineTo(15.5f, 11f)
                c.drawPath(path, p)
                p.style = Paint.Style.FILL
                c.drawRect(6f, 11f, 18f, 20f, p)
            }
            "plus" -> {
                c.drawLine(12f, 5f, 12f, 19f, p); c.drawLine(5f, 12f, 19f, 12f, p)
            }
            "chevron" -> {
                path.moveTo(6f, 9f); path.lineTo(12f, 15f); path.lineTo(18f, 9f)
                c.drawPath(path, p)
            }
            "rotate" -> {
                path.addArc(RectF(5f, 5f, 19f, 19f), -70f, 290f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(16.5f, 2.5f); path.lineTo(15f, 6.5f); path.lineTo(19f, 8f)
                c.drawPath(path, p)
            }
        }
        c.restore()
    }
}

/** Stufenloser Regler mit logarithmischer Skala und Fangpunkten. */
@SuppressLint("ViewConstructor")
class SliderView(
    ctx: Context,
    private val min: Float,
    private val max: Float,
    private val snaps: FloatArray,
    var value: Float,
    private val onChange: (Float) -> Unit,
) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad = ctx.dp(14f)

    private fun pos(v: Float) = (ln(v / min) / ln(max / min)).coerceIn(0f, 1f)
    private fun valAt(t: Float) = min * (max / min).pow(t)

    override fun onDraw(c: Canvas) {
        val w = width - 2 * pad
        val y = height / 2f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = Pal.GREY; p.strokeWidth = context.dp(3f)
        c.drawLine(pad, y, pad + w, y, p)
        p.color = Pal.BLACK; p.strokeWidth = context.dp(1.5f)
        for (s in snaps) {
            val x = pad + pos(s) * w
            c.drawLine(x, y - context.dp(8f), x, y + context.dp(8f), p)
        }
        p.style = Paint.Style.FILL
        c.drawCircle(pad + pos(value) * w, y, context.dp(10f), p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width - 2 * pad
        val t = ((e.x - pad) / w).coerceIn(0f, 1f)
        var v = valAt(t)
        val tol = context.dp(10f)
        for (s in snaps) if (abs(pos(s) * w - t * w) <= tol) { v = s; break }
        value = v
        onChange(v)
        invalidate()
        return true
    }
}

/** Kalibrierbalken: 100 mm bei der aktuellen ppi. */
@SuppressLint("ViewConstructor")
class CalibView(ctx: Context, private val ppi: () -> Float) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Pal.BLACK }

    override fun onDraw(c: Canvas) {
        val pxPerMm = ppi() / 25.4f
        val len = 100f * pxPerMm
        val x0 = (width - len) / 2f
        val y = height * 0.35f
        p.color = Pal.BLACK
        p.style = Paint.Style.FILL
        c.drawRect(x0, y, x0 + len, y + context.dp(6f), p)
        p.strokeWidth = context.dp(1f)
        tp.textSize = context.dp(12f)
        for (i in 0..10) {
            val x = x0 + i * 10f * pxPerMm
            c.drawLine(x, y - context.dp(if (i % 5 == 0) 14f else 8f), x, y, p)
            if (i % 5 == 0) c.drawText("${i * 10}", x, y + context.dp(24f), tp)
        }
        c.drawText("mm", x0 + len + context.dp(18f), y + context.dp(6f), tp)
    }
}
