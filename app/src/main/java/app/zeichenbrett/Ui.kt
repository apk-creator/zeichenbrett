package app.zeichenbrett

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

const val R_PANEL = 16f
const val R_BUTTON = 12f

fun roundBg(ctx: Context, color: Int, radiusDp: Float = R_PANEL) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = ctx.dp(radiusDp)
}

fun outlineBg(ctx: Context, radiusDp: Float = R_BUTTON) = GradientDrawable().apply {
    setColor(Pal.WHITE)
    setStroke(ctx.dpi(1.5f), Pal.BLACK)
    cornerRadius = ctx.dp(radiusDp)
}

/** Abstand für den selbst gezeichneten Schatten rund um eine Palette. */
fun shadowInset(ctx: Context) = ctx.dpi(7f)

/**
 * Weiße, gerundete Fläche mit weichem Schatten, der exakt der Rundung folgt
 * (sauberer als der Systemschatten in den Ecken). Die Fläche liegt um [inset] eingerückt.
 */
class ShadowBg(private val ctx: Context, private val color: Int = Pal.WHITE, private val radiusDp: Float = R_PANEL) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val inset = shadowInset(ctx).toFloat()

    override fun draw(c: Canvas) {
        val b = bounds
        val r = ctx.dp(radiusDp)
        val rect = RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        p.color = color
        p.setShadowLayer(ctx.dp(5f), 0f, ctx.dp(1.5f), android.graphics.Color.argb(55, 0, 0, 0))
        c.drawRoundRect(rect, r, r, p)
        p.clearShadowLayer()
        c.drawRoundRect(rect, r, r, p)
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Inhalt auf eine gerundete Form beschneiden (z. B. scrollende Listen). */
fun clipRound(v: View, radiusPx: Float) {
    v.outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }
    v.clipToOutline = true
}

/** Weiße Palette mit gerundeten Ecken und weichem Schatten. */
fun palette(ctx: Context, horizontal: Boolean): LinearLayout = LinearLayout(ctx).apply {
    orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    background = ShadowBg(ctx)
    val p = ctx.dpi(4f) + shadowInset(ctx)
    setPadding(p, p, p, p)
}

/**
 * Einheitlicher Button der ganzen App: Symbol und/oder Text, schwarz auf weiß,
 * invertiert wenn aktiv oder gedrückt, grau wenn nicht verfügbar.
 */
@SuppressLint("ViewConstructor")
class Btn(ctx: Context) : View(ctx) {
    var icon: String? = null
        set(v) { field = v; invalidate() }
    var iconFill: Int? = null
    var iconScale = 1f
    var text: String? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var badge: String? = null
        set(v) { field = v; invalidate() }
    /** Platz für das Badge immer freihalten, damit der Text nicht springt. */
    var badgeSpace = false
    var active = false
        set(v) { field = v; invalidate() }
    var enabledBtn = true
        set(v) { field = v; invalidate() }
    var grey = false
    var textScale = 0.3f
    var textSp = 0f
    var alignLeft = false
    var multiLine = false
    var bold = false
    var onTap: (() -> Unit)? = null

    private var down = false
    /** Tippen und 0,5 s halten. */
    var onLong: (() -> Unit)? = null
    private var longFired = false
    /** Halten startet Ziehen: Start, Bewegung und Ende in Bildschirmkoordinaten. */
    var onDragStart: ((Float, Float) -> Unit)? = null
    var onDragMove: ((Float, Float) -> Unit)? = null
    var onDragEnd: ((Float, Float, Boolean) -> Unit)? = null
    private var dragging = false
    private var rawX = 0f
    private var rawY = 0f
    private var downX = 0f
    private var downY = 0f
    private val longRun = Runnable {
        if (down) {
            longFired = true; down = false; invalidate()
            val ds = onDragStart
            if (ds != null) {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                ds(rawX, rawY)
            } else onLong?.invoke()
        }
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pad = ctx.dp(12f)

    fun flash() {
        down = true; invalidate()
        postDelayed({ down = false; invalidate() }, 250)
    }

    private fun textSize(h: Float) =
        if (textSp > 0f) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp, resources.displayMetrics) else h * textScale

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val h = if (hMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else context.dpi(48f)
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val w = if (wMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else {
            val tx = text
            if (tx != null && icon == null) {
                tp.textSize = textSize(h.toFloat())
                tp.isFakeBoldText = bold
                var want = (tp.measureText(tx) + 2 * pad + (if (badge != null || badgeSpace) h * 0.4f else 0f)).toInt()
                if (wMode == MeasureSpec.AT_MOST) want = minOf(want, MeasureSpec.getSize(widthMeasureSpec))
                max(want, h)
            } else h
        }
        setMeasuredDimension(w, h)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true; longFired = false; dragging = false; invalidate()
                rawX = e.rawX; rawY = e.rawY; downX = e.x; downY = e.y
                if (onLong != null || onDragStart != null) postDelayed(longRun, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                rawX = e.rawX; rawY = e.rawY
                if (dragging) { onDragMove?.invoke(rawX, rawY); return true }
                if (kotlin.math.hypot(e.x - downX, e.y - downY) > context.dp(10f)) removeCallbacks(longRun)
                val inside = e.x >= 0 && e.y >= 0 && e.x <= width && e.y <= height
                if (inside != down) { down = inside; invalidate(); if (!inside) removeCallbacks(longRun) }
            }
            MotionEvent.ACTION_UP -> {
                rawX = e.rawX; rawY = e.rawY
                removeCallbacks(longRun)
                if (dragging) { dragging = false; onDragEnd?.invoke(rawX, rawY, false); return true }
                val was = down
                down = false; invalidate()
                if (was && enabledBtn && !longFired) onTap?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longRun)
                if (dragging) { dragging = false; onDragEnd?.invoke(rawX, rawY, true) }
                down = false; invalidate()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val inv = active || (down && enabledBtn)
        if (inv) {
            // Aktiv bzw. gedrückt: nur schwarz umrahmt
            val sw = context.dp(1.8f)
            p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = Pal.BLACK
            val r = context.dp(R_BUTTON)
            c.drawRoundRect(sw / 2f + 1f, sw / 2f + 1f, w - sw / 2f - 1f, h - sw / 2f - 1f, r, r, p)
        }
        val fg = when {
            !enabledBtn || grey -> Pal.GREY
            else -> Pal.BLACK
        }
        val ic = icon; val tx = text
        tp.color = fg
        tp.isFakeBoldText = bold
        if (ic != null && tx != null) {
            Icons.draw(c, ic, w / 2f, h * 0.36f, h * 0.42f, fg, iconFill, p)
            tp.textSize = h * 0.26f
            tp.textAlign = Paint.Align.CENTER
            c.drawText(tx, w / 2f, h * 0.79f, tp)
        } else if (ic != null) {
            Icons.draw(c, ic, w / 2f, h / 2f, h * 0.52f * iconScale, fg, iconFill, p)
        } else if (tx != null) {
            drawLabel(c, tx, w, h)
        }
    }

    private fun drawLabel(c: Canvas, tx: String, w: Float, h: Float) {
        val size = textSize(h)
        tp.textSize = size
        var x0 = pad
        var avail = w - 2 * pad
        val b = badge
        if (b != null || badgeSpace) {
            val isz = h * 0.34f
            val tw = tp.measureText(tx)
            val total = isz + context.dp(4f) + tw
            val bx = if (alignLeft) pad else (w - total) / 2f
            if (b != null) Icons.draw(c, b, bx + isz / 2f, h / 2f, isz, tp.color, null, p)
            x0 = bx + isz + context.dp(4f)
            avail = w - x0 - pad
            tp.textAlign = Paint.Align.LEFT
            c.drawText(tx, x0, h / 2f - (tp.descent() + tp.ascent()) / 2f, tp)
            return
        }
        if (tp.measureText(tx) <= avail) {
            line(c, tx, w, h / 2f)
            return
        }
        if (multiLine) {
            tp.textSize = size * 0.82f
            val lines = split(tx, avail)
            val lh = tp.fontSpacing * 0.95f
            justify = avail
            line(c, lines.first, w, h / 2f - lh / 2f)
            line(c, lines.second, w, h / 2f + lh / 2f)
            justify = 0f
            return
        }
        line(c, TextUtils.ellipsize(tx, tp, avail, TextUtils.TruncateAt.END).toString(), w, h / 2f)
    }

    private var justify = 0f

    private fun line(c: Canvas, s: String, w: Float, cy: Float) {
        val y = cy - (tp.descent() + tp.ascent()) / 2f
        tp.letterSpacing = 0f
        if (justify > 0f && s.length > 1) {
            val extra = justify - tp.measureText(s)
            if (extra > 0f) tp.letterSpacing = minOf(extra / (s.length - 1) / tp.textSize, 0.08f)
        }
        if (alignLeft) {
            tp.textAlign = Paint.Align.LEFT
            c.drawText(s, pad, y, tp)
        } else {
            tp.textAlign = Paint.Align.CENTER
            c.drawText(s, w / 2f, y, tp)
        }
        tp.letterSpacing = 0f
    }

    /** Teilt an Leer-/Trennzeichen möglichst mittig, sonst nach Breite; zweite Zeile wird bei Bedarf gekürzt. */
    private fun split(tx: String, avail: Float): Pair<String, String> {
        var best = -1
        for (i in tx.indices) {
            if (tx[i] == ' ' || tx[i] == '_' || tx[i] == '-') {
                if (tp.measureText(tx, 0, i + 1) <= avail &&
                    (best < 0 || abs(i - tx.length / 2) < abs(best - tx.length / 2))) best = i
            }
        }
        val first: String
        val rest: String
        if (best > 0) {
            first = tx.substring(0, best + 1).trimEnd()
            rest = tx.substring(best + 1).trimStart()
        } else {
            val n = tp.breakText(tx, true, avail, null).coerceAtLeast(1)
            first = tx.substring(0, n)
            rest = tx.substring(n)
        }
        val second = TextUtils.ellipsize(rest, tp, avail, TextUtils.TruncateAt.END).toString()
        return first to second
    }
}

/** Einfache Liniensymbole im 24er-Raster. */
object Icons {
    private val path = Path()

    fun draw(c: Canvas, name: String, cx: Float, cy: Float, size: Float, fg: Int, fill: Int?, p: Paint) {
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
            "dim" -> {
                c.drawLine(3f, 7f, 3f, 17f, p)
                c.drawLine(21f, 7f, 21f, 17f, p)
                c.drawLine(3.5f, 12f, 20.5f, 12f, p)
                path.moveTo(7.5f, 9f); path.lineTo(3.8f, 12f); path.lineTo(7.5f, 15f)
                path.moveTo(16.5f, 9f); path.lineTo(20.2f, 12f); path.lineTo(16.5f, 15f)
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
                c.drawLine(12f, 4.5f, 12f, 19.5f, p); c.drawLine(4.5f, 12f, 19.5f, 12f, p)
            }
            "chevron" -> {
                path.moveTo(6f, 9f); path.lineTo(12f, 15f); path.lineTo(18f, 9f)
                c.drawPath(path, p)
            }
            "nobg" -> {
                // Durchgestrichenes Klemmbrett: Hintergrund ignorieren, nur eigene Striche radieren
                c.drawRoundRect(5f, 4.5f, 19f, 21.5f, 2f, 2f, p)
                p.style = Paint.Style.FILL
                c.drawRoundRect(8.5f, 2.5f, 15.5f, 6f, 1.2f, 1.2f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.4f
                c.drawLine(2.5f, 21.5f, 21.5f, 2.5f, p)
            }
            "check" -> {
                path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19.5f, 7f)
                c.drawPath(path, p)
            }
            "fit" -> {
                // Rechteck, proportional gefüllt von einem um 45° gedrehten Quadrat
                c.drawRect(2.5f, 5.5f, 21.5f, 18.5f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(12f, 12f, 6.5f, p)
            }
            "orig" -> {
                path.moveTo(5.5f, 8f); path.lineTo(7.5f, 6.5f); path.lineTo(7.5f, 17.5f)
                path.moveTo(16.5f, 8f); path.lineTo(18.5f, 6.5f); path.lineTo(18.5f, 17.5f)
                c.drawPath(path, p)
                p.style = Paint.Style.FILL
                c.drawCircle(12f, 9.5f, 1.3f, p)
                c.drawCircle(12f, 14.5f, 1.3f, p)
            }
            "center" -> {
                // Vier Pfeile, die zur Mitte zeigen
                path.moveTo(12f, 2.5f); path.lineTo(12f, 9.5f); path.moveTo(9.5f, 7f); path.lineTo(12f, 9.5f); path.lineTo(14.5f, 7f)
                path.moveTo(12f, 21.5f); path.lineTo(12f, 14.5f); path.moveTo(9.5f, 17f); path.lineTo(12f, 14.5f); path.lineTo(14.5f, 17f)
                path.moveTo(2.5f, 12f); path.lineTo(9.5f, 12f); path.moveTo(7f, 9.5f); path.lineTo(9.5f, 12f); path.lineTo(7f, 14.5f)
                path.moveTo(21.5f, 12f); path.lineTo(14.5f, 12f); path.moveTo(17f, 9.5f); path.lineTo(14.5f, 12f); path.lineTo(17f, 14.5f)
                c.drawPath(path, p)
            }
            "rotate" -> {
                c.rotate(90f, 12f, 12f)
                // Kreisbogen im Uhrzeigersinn von oben bis links, Pfeilspitze zeigt nach oben
                path.addArc(RectF(5f, 5f, 19f, 19f), -90f, 270f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(5f, 9f); path.lineTo(2.2f, 13.2f); path.lineTo(7.8f, 13.2f); path.close()
                p.style = Paint.Style.FILL
                c.drawPath(path, p)
            }
        }
        c.restore()
    }
}

/**
 * Stufenloser Regler. Die Fangpunkte (inkl. Minimum und Maximum) liegen gleichmäßig verteilt,
 * dazwischen wird logarithmisch interpoliert.
 */
@SuppressLint("ViewConstructor")
class SliderView(
    ctx: Context,
    private val anchors: FloatArray,
    var value: Float,
    private val onChange: (Float) -> Unit,
    private val onRelease: () -> Unit = {},
) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad = ctx.dp(14f)
    private val segs = anchors.size - 1

    private fun pos(v: Float): Float {
        if (v <= anchors[0]) return 0f
        for (i in 0 until segs) {
            val a = anchors[i]; val b = anchors[i + 1]
            if (v <= b) return (i + ln(v / a) / ln(b / a)) / segs
        }
        return 1f
    }

    private fun valAt(t: Float): Float {
        val x = (t * segs).coerceIn(0f, segs.toFloat())
        val i = x.toInt().coerceAtMost(segs - 1)
        val f = x - i
        val a = anchors[i]; val b = anchors[i + 1]
        return a * (b / a).pow(f)
    }

    override fun onDraw(c: Canvas) {
        val w = width - 2 * pad
        val y = height / 2f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = Pal.GREY; p.strokeWidth = context.dp(3f)
        c.drawLine(pad, y, pad + w, y, p)
        p.color = Pal.BLACK; p.strokeWidth = context.dp(1.5f)
        for (i in 0..segs) {
            val x = pad + i.toFloat() / segs * w
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
        for (i in 0..segs) if (abs(i.toFloat() / segs * w - t * w) <= tol) { v = anchors[i]; break }
        value = v
        onChange(v)
        invalidate()
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) onRelease()
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
