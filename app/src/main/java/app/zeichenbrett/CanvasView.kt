package app.zeichenbrett

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

@SuppressLint("ViewConstructor")
class CanvasView(ctx: Context, private val tools: ToolState, private val host: Host) : View(ctx) {

    interface Host {
        fun ppi(): Float
        fun onDocEdited()
        fun onViewChanged()
        fun refreshStatus()
        fun onUndoGesture(undo: Boolean)
        fun onPenDown()
    }

    var doc: PageDoc? = null
        set(v) {
            if (field !== v) {
                endActions()
                field = v
                dropHiRes()
                measureA = null; measureB = null; measureText = null
                if (v != null && !v.viewInit && width > 0) initView(v)
                requestHiRes()
                invalidate()
            }
        }

    var measureText: String? = null
        private set
    private var measureA: PointF? = null
    private var measureB: PointF? = null

    private val dens = resources.displayMetrics.density
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Pal.BLACK }
    private val tmpPath = Path()
    private val rulerH get() = 56f * dens

    fun pxPerPt() = host.ppi() / 72f
    fun scaleOf(d: PageDoc) = pxPerPt() * d.zoom

    // ------------------------------------------------------------------ Ansicht

    fun initView(d: PageDoc) {
        d.zoom = 1f
        val s = scaleOf(d)
        val pw = d.pageW * s
        val ph = d.pageH * s
        d.offX = if (pw < width) (width - pw) / 2f else 24f * dens
        d.offY = if (ph < height) (height - ph) / 2f else 88f * dens
        d.viewInit = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        doc?.let { if (!it.viewInit) initView(it) }
        if (w > 0 && h > 0) {
            if (!tools.rulerPlaced) {
                tools.rulerX = w / 2f; tools.rulerY = h * 0.55f; tools.rulerPlaced = true
            } else {
                tools.rulerX = tools.rulerX.coerceIn(0f, w.toFloat())
                tools.rulerY = tools.rulerY.coerceIn(0f, h - rulerH)
            }
        }
        requestHiRes()
    }

    fun setZoom(z: Float, fx: Float = width / 2f, fy: Float = height / 2f) {
        val d = doc ?: return
        val s0 = scaleOf(d)
        val px = (fx - d.offX) / s0
        val py = (fy - d.offY) / s0
        d.zoom = z
        val s1 = scaleOf(d)
        d.offX = fx - px * s1
        d.offY = fy - py * s1
        viewChanged()
    }

    private fun viewChanged() {
        host.onViewChanged()
        requestHiRes()
        invalidate()
    }

    // ------------------------------------------------------------------ Hochauflösendes Rendern

    private var hires: Bitmap? = null
    private var hrDoc: PageDoc? = null
    private var hrScale = 1f
    private var hrOffX = 0f
    private var hrOffY = 0f
    private var hrGen = 0
    private val hrRunnable = Runnable { doHiRes() }

    fun requestHiRes() {
        removeCallbacks(hrRunnable)
        postDelayed(hrRunnable, 120)
    }

    private fun dropHiRes() {
        hrGen++
        hires?.recycle()
        hires = null
        hrDoc = null
    }

    private fun doHiRes() {
        val d = doc ?: return
        val r = d.renderer ?: return
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val s = scaleOf(d)
        val ov = d.overview
        if (ov != null && d.pageW * s <= ov.width * 1.1f) {
            if (hires != null) { dropHiRes(); invalidate() }
            return
        }
        val gen = ++hrGen
        val offX = d.offX; val offY = d.offY
        r.execute {
            val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (t: Throwable) { null }
            if (bmp != null) {
                var ok = true
                try {
                    val m = Matrix()
                    m.setScale(s, s)
                    m.postTranslate(offX, offY)
                    r.render(bmp, m)
                } catch (e: Exception) { ok = false }
                post {
                    if (ok && gen == hrGen && doc === d) {
                        hires?.recycle()
                        hires = bmp; hrDoc = d; hrScale = s; hrOffX = offX; hrOffY = offY
                        invalidate()
                    } else bmp.recycle()
                }
            }
        }
    }

    // ------------------------------------------------------------------ Zeichnen

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.BACKGROUND)
        val d = doc ?: return
        val s = scaleOf(d)
        val page = RectF(d.offX, d.offY, d.offX + d.pageW * s, d.offY + d.pageH * s)

        fillP.color = d.bgColor
        c.drawRect(page, fillP)
        d.overview?.let { if (!it.isRecycled) c.drawBitmap(it, null, page, bmpP) }
        val hr = hires
        if (hr != null && hrDoc === d && !hr.isRecycled) {
            c.save()
            c.clipRect(page)
            val k = s / hrScale
            c.translate(d.offX, d.offY)
            c.scale(k, k)
            c.translate(-hrOffX, -hrOffY)
            c.drawBitmap(hr, 0f, 0f, bmpP)
            c.restore()
        }

        // Sichtbarer Bereich in pt
        val vl = -d.offX / s; val vt = -d.offY / s
        val vr = (width - d.offX) / s; val vb = (height - d.offY) / s
        val minW = 1f / s

        c.save()
        c.clipRect(page)
        c.translate(d.offX, d.offY)
        c.scale(s, s)
        val items = d.items
        for (it in items) if (it is ImageItem) drawImage(c, d, it)
        for (it in items) {
            when (it) {
                is Stroke -> if (visible(it.bounds, it.width, vl, vt, vr, vb)) drawPoly(c, it.pts, it.path, it.width, it.color, minW)
                is Cover -> if (visible(it.bounds, it.width, vl, vt, vr, vb)) drawPoly(c, it.pts, it.path, it.width, it.color, minW)
                else -> {}
            }
        }
        if (act == PenAct.DRAW && cur.n >= 2) drawCurrent(c, curColor, curWidth, minW)
        if (act == PenAct.ERASE && cur.n >= 2) drawCurrent(c, d.bgColor, eraseWidthPt, minW)
        c.restore()

        // Auswahl (Bild)
        d.selected()?.let { im ->
            val l = d.offX + im.x * s; val t = d.offY + im.y * s
            val r = l + im.w * s; val b = t + im.h * s
            strokeP.color = Pal.BLACK; strokeP.strokeWidth = 2f * dens
            c.drawRect(l, t, r, b, strokeP)
            for (p in arrayOf(floatArrayOf(l, t), floatArrayOf(r, t), floatArrayOf(r, b), floatArrayOf(l, b))) {
                fillP.color = Pal.WHITE; c.drawCircle(p[0], p[1], 12f * dens, fillP)
                c.drawCircle(p[0], p[1], 12f * dens, strokeP)
            }
        }

        // Messlinie
        if (tools.tool == Tool.MEASURE) {
            val a = measureA; val b = measureB
            if (a != null && b != null) {
                val ax = d.offX + a.x * s; val ay = d.offY + a.y * s
                val bx = d.offX + b.x * s; val by = d.offY + b.y * s
                strokeP.color = Pal.BLACK; strokeP.strokeWidth = 1.5f * dens
                c.drawLine(ax, ay, bx, by, strokeP)
                fillP.color = Pal.BLACK
                c.drawCircle(ax, ay, 3f * dens, fillP)
                c.drawCircle(bx, by, 3f * dens, fillP)
            }
        }

        if (tools.rulerOn) drawRuler(c, d)

        if (act == PenAct.ERASE && eraserSX >= 0f) {
            strokeP.color = Pal.BLACK; strokeP.strokeWidth = 1f * dens
            c.drawCircle(eraserSX, eraserSY, eraserPx() / 2f, strokeP)
        }
    }

    private fun visible(b: RectF, w: Float, l: Float, t: Float, r: Float, bt: Float) =
        !(b.right + w < l || b.left - w > r || b.bottom + w < t || b.top - w > bt)

    private fun drawPoly(c: Canvas, pts: FloatArray, path: Path, w: Float, color: Int, minW: Float) {
        if (pts.size == 2) {
            fillP.color = color
            c.drawCircle(pts[0], pts[1], max(w, minW) / 2f, fillP)
        } else {
            strokeP.color = color
            strokeP.strokeWidth = max(w, minW)
            c.drawPath(path, strokeP)
        }
    }

    private fun drawCurrent(c: Canvas, color: Int, w: Float, minW: Float) {
        if (cur.n == 2) {
            fillP.color = color
            c.drawCircle(cur.a[0], cur.a[1], max(w, minW) / 2f, fillP)
            return
        }
        tmpPath.reset()
        tmpPath.moveTo(cur.a[0], cur.a[1])
        var i = 2
        while (i + 1 < cur.n) { tmpPath.lineTo(cur.a[i], cur.a[i + 1]); i += 2 }
        strokeP.color = color
        strokeP.strokeWidth = max(w, minW)
        c.drawPath(tmpPath, strokeP)
    }

    private fun drawImage(c: Canvas, d: PageDoc, im: ImageItem) {
        val odd = im.rot % 2 == 1
        val cw = if (odd) im.h else im.w
        val ch = if (odd) im.w else im.h
        c.save()
        c.translate(im.x + im.w / 2f, im.y + im.h / 2f)
        c.rotate(im.rot * 90f)
        val dst = RectF(-cw / 2f, -ch / 2f, cw / 2f, ch / 2f)
        val bmp = d.bitmaps[im.id]
        if (bmp != null && !bmp.isRecycled) c.drawBitmap(bmp, null, dst, bmpP)
        else { fillP.color = Color.LTGRAY; c.drawRect(dst, fillP) }
        c.restore()
    }

    // ------------------------------------------------------------------ Lineal

    private fun nice(v: Double): Double {
        val e = floor(log10(v))
        val base = 10.0.pow(e)
        val f = v / base
        val n = when {
            f <= 1.0 -> 1.0
            f <= 2.0 -> 2.0
            f <= 5.0 -> 5.0
            else -> 10.0
        }
        return n * base
    }

    private fun fmtNum(v: Double): String {
        if (abs(v - v.roundToLong()) < 1e-9) return v.roundToLong().toString()
        return String.format(Locale.GERMANY, "%.3f", v).trimEnd('0').trimEnd(',')
    }

    private fun drawRuler(c: Canvas, d: PageDoc) {
        val L = (width + height) * 2f
        val H = rulerH
        c.save()
        c.translate(tools.rulerX, tools.rulerY)
        c.rotate(tools.rulerAngle)
        fillP.color = Color.argb(215, 250, 250, 250)
        c.drawRect(-L, 0f, L, H, fillP)
        strokeP.color = Pal.BLACK
        strokeP.strokeWidth = 1.5f * dens
        c.drawLine(-L, 0f, L, 0f, strokeP)
        c.drawLine(-L, H, L, H, strokeP)

        // Teilung in der Messeinheit des Dokuments
        val pxPerMm = host.ppi() / 25.4f * d.zoom
        val unitPx = (d.unit.mm / d.scale * pxPerMm).toFloat()
        if (unitPx > 0f) {
            val labelStep = nice((70f * dens / unitPx).toDouble())
            var tickStep = labelStep / 5.0
            var ratio = 5
            if (tickStep * unitPx < 7f * dens) { tickStep = labelStep / 2.0; ratio = 2 }
            if (tickStep * unitPx < 7f * dens) { tickStep = labelStep; ratio = 1 }
            val tickPx = (tickStep * unitPx).toFloat()
            val i0 = floor(-L / tickPx).toInt()
            val i1 = ceil(L / tickPx).toInt()
            strokeP.strokeWidth = 1f * dens
            textP.textSize = 11f * dens
            for (i in i0..i1) {
                val x = i * tickPx
                val major = i % ratio == 0
                c.drawLine(x, 0f, x, if (major) 16f * dens else 8f * dens, strokeP)
                if (major) c.drawText(fmtNum(i * tickStep), x, 30f * dens, textP)
            }
            textP.textSize = 11f * dens
            c.drawText(d.unit.label, 0f, H - 8f * dens, textP)
        }
        c.restore()
    }

    private fun onRuler(x: Float, y: Float): Boolean {
        if (!tools.rulerOn) return false
        val a = Math.toRadians(-tools.rulerAngle.toDouble())
        val dx = x - tools.rulerX; val dy = y - tools.rulerY
        val ly = (dx * sin(a) + dy * cos(a)).toFloat()
        return ly >= -24f * dens && ly <= rulerH + 24f * dens
    }

    private fun snapAngle(a: Float): Float {
        val m = ((a % 360f) + 360f) % 360f
        val n = (m / 15f).roundToInt() * 15f
        return if (abs(m - n) <= 3f) n % 360f else m
    }

    // ------------------------------------------------------------------ Eingabe

    private enum class Mode { NONE, PEN, FINGER }
    private enum class PenAct { NONE, DRAW, ERASE, MEASURE, MOVE, SCALE }

    private var mode = Mode.NONE
    private var act = PenAct.NONE
    private var penId = -1
    private var tempEraser = false
    private val cur = FList()
    private var curColor = 0
    private var curWidth = 0f
    private var sx0 = 0f; private var sy0 = 0f
    private var smx = 0f; private var smy = 0f
    private var lastx = 0f; private var lasty = 0f
    private var straight = false
    private var holdSX = 0f; private var holdSY = 0f
    private var eraseWidthPt = 0f
    private var eraserSX = -1f; private var eraserSY = -1f
    private var selFixX = 0f; private var selFixY = 0f; private var selAspect = 1f
    private var grabDX = 0f; private var grabDY = 0f

    private val holdRun = Runnable {
        if (mode == Mode.PEN && act == PenAct.DRAW && !tools.rulerOn && !straight) {
            straight = true
            cur.clear(); cur.add(sx0, sy0); cur.add(lastx, lasty)
            invalidate()
        }
    }

    private fun eraserPx(): Float = tools.eraserPct / 100f * 0.25f * min(width, height)

    private fun isPen(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun buttonEraser(e: MotionEvent, i: Int): Boolean =
        e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER ||
            (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = doc ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fCount = 0
                if (isPen(e, 0)) { mode = Mode.PEN; penDown(d, e, 0) }
                else { mode = Mode.FINGER; fingersChanged(d, e, -1) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (mode == Mode.FINGER && isPen(e, i)) {
                    endFingers(d)
                    mode = Mode.PEN
                    penDown(d, e, i)
                } else if (mode == Mode.FINGER) fingersChanged(d, e, -1)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.PEN -> penMove(d, e)
                Mode.FINGER -> fingerMove(d, e)
                else -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                if (mode == Mode.PEN && e.getPointerId(i) == penId) { penUp(d, e, i); mode = Mode.NONE }
                else if (mode == Mode.FINGER) fingersChanged(d, e, i)
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.PEN && e.getPointerId(0) == penId) penUp(d, e, 0)
                else if (mode == Mode.FINGER) fingersChanged(d, e, 0)
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.PEN) finishAction(d)
                if (mode == Mode.FINGER) endFingers(d)
                mode = Mode.NONE
            }
        }
        return true
    }

    /** Laufende Aktion abschließen (z. B. beim Dokumentwechsel). */
    private fun endActions() {
        val d = doc ?: return
        if (mode == Mode.PEN) finishAction(d)
        mode = Mode.NONE
        fCount = 0
    }

    // ---- Stift

    private fun penDown(d: PageDoc, e: MotionEvent, i: Int) {
        host.onPenDown()
        penId = e.getPointerId(i)
        val x = e.getX(i); val y = e.getY(i)
        val s = scaleOf(d)
        val dx = (x - d.offX) / s; val dy = (y - d.offY) / s
        val sel = d.selected()
        if (sel != null) { selectionDown(d, sel, x, y, dx, dy); return }
        tempEraser = buttonEraser(e, i)
        startAction(d, dx, dy, x, y)
    }

    private fun startAction(d: PageDoc, dx: Float, dy: Float, x: Float, y: Float) {
        when {
            tempEraser || tools.tool == Tool.ERASER -> startErase(d, dx, dy, x, y)
            tools.tool == Tool.MEASURE -> {
                act = PenAct.MEASURE
                measureA = PointF(dx, dy); measureB = PointF(dx, dy)
                updateMeasure(d)
            }
            else -> startDraw(dx, dy, x, y)
        }
        invalidate()
    }

    private fun startDraw(dx: Float, dy: Float, x: Float, y: Float) {
        act = PenAct.DRAW
        cur.clear(); cur.add(dx, dy)
        sx0 = dx; sy0 = dy; smx = dx; smy = dy; lastx = dx; lasty = dy
        curColor = Pal.PENS[tools.pen]
        curWidth = tools.penWidth[tools.pen]
        straight = false
        holdSX = x; holdSY = y
        removeCallbacks(holdRun)
        if (!tools.rulerOn) postDelayed(holdRun, 333)
    }

    private fun startErase(d: PageDoc, dx: Float, dy: Float, x: Float, y: Float) {
        act = PenAct.ERASE
        d.pushUndo()
        cur.clear(); cur.add(dx, dy)
        eraseWidthPt = eraserPx() / scaleOf(d)
        eraserSX = x; eraserSY = y
        applyErase(d, dx, dy, dx, dy)
    }

    private fun applyErase(d: PageDoc, ax: Float, ay: Float, bx: Float, by: Float) {
        Geo.erase(d.items, ax, ay, bx, by, eraseWidthPt / 2f)?.let { d.items = it }
    }

    private fun penMove(d: PageDoc, e: MotionEvent) {
        val i = e.findPointerIndex(penId)
        if (i < 0) return
        val s = scaleOf(d)
        if (act == PenAct.DRAW || (act == PenAct.ERASE && tempEraser)) {
            val be = buttonEraser(e, i)
            if (be != tempEraser) {
                val x = e.getX(i); val y = e.getY(i)
                finishAction(d)
                tempEraser = be
                startAction(d, (x - d.offX) / s, (y - d.offY) / s, x, y)
                return
            }
        }
        for (h in 0 until e.historySize) penPoint(d, e.getHistoricalX(i, h), e.getHistoricalY(i, h), s)
        penPoint(d, e.getX(i), e.getY(i), s)
        invalidate()
    }

    private fun penPoint(d: PageDoc, x: Float, y: Float, s: Float) {
        val dx = (x - d.offX) / s
        val dy = (y - d.offY) / s
        when (act) {
            PenAct.DRAW -> {
                lastx = dx; lasty = dy
                if (tools.rulerOn) { rulerLine(dx, dy); return }
                if (straight) { cur.clear(); cur.add(sx0, sy0); cur.add(dx, dy); return }
                if (hypot(x - holdSX, y - holdSY) > 4f * dens) {
                    holdSX = x; holdSY = y
                    removeCallbacks(holdRun)
                    postDelayed(holdRun, 333)
                }
                smx += (dx - smx) * 0.55f
                smy += (dy - smy) * 0.55f
                val n = cur.n
                if (hypot(smx - cur.a[n - 2], smy - cur.a[n - 1]) * s >= 0.75f) cur.add(smx, smy)
            }
            PenAct.ERASE -> {
                val n = cur.n
                val ax = cur.a[n - 2]; val ay = cur.a[n - 1]
                eraserSX = x; eraserSY = y
                if (hypot(dx - ax, dy - ay) * s < 0.5f) return
                applyErase(d, ax, ay, dx, dy)
                cur.add(dx, dy)
            }
            PenAct.MEASURE -> { measureB?.set(dx, dy); updateMeasure(d) }
            PenAct.MOVE -> updateSel(d) { it.copy(x = dx - grabDX, y = dy - grabDY) }
            PenAct.SCALE -> {
                val ddx = dx - selFixX; val ddy = dy - selFixY
                val nw = max(abs(ddx), abs(ddy) * selAspect).coerceAtLeast(5f)
                val nh = nw / selAspect
                val nx = if (ddx < 0) selFixX - nw else selFixX
                val ny = if (ddy < 0) selFixY - nh else selFixY
                updateSel(d) { it.copy(x = nx, y = ny, w = nw, h = nh) }
            }
            PenAct.NONE -> {}
        }
    }

    private fun rulerLine(dx: Float, dy: Float) {
        val a = Math.toRadians(tools.rulerAngle.toDouble())
        val ux = cos(a).toFloat(); val uy = sin(a).toFloat()
        val t = (dx - sx0) * ux + (dy - sy0) * uy
        cur.clear()
        cur.add(sx0, sy0)
        cur.add(sx0 + ux * t, sy0 + uy * t)
    }

    private fun penUp(d: PageDoc, e: MotionEvent, i: Int) {
        if (act == PenAct.DRAW && !straight && !tools.rulerOn) {
            val s = scaleOf(d)
            val dx = (e.getX(i) - d.offX) / s; val dy = (e.getY(i) - d.offY) / s
            lastx = dx; lasty = dy
            val n = cur.n
            if (hypot(dx - cur.a[n - 2], dy - cur.a[n - 1]) * s >= 0.25f) cur.add(dx, dy)
        }
        finishAction(d)
        penId = -1
    }

    private fun finishAction(d: PageDoc) {
        removeCallbacks(holdRun)
        when (act) {
            PenAct.DRAW -> {
                var pts = cur.toArray()
                if (pts.size == 4 && pts[0] == pts[2] && pts[1] == pts[3]) pts = pts.copyOf(2)
                if (pts.size >= 2) {
                    d.pushUndo()
                    d.items = d.items + Stroke(curColor, curWidth, pts)
                    d.dirty = true
                    host.onDocEdited()
                }
            }
            PenAct.ERASE -> {
                d.items = d.items + Cover(d.bgColor, eraseWidthPt, cur.toArray())
                d.dirty = true
                eraserSX = -1f
                host.onDocEdited()
            }
            PenAct.MOVE, PenAct.SCALE -> {
                d.dirty = true
                host.onDocEdited()
            }
            else -> {}
        }
        cur.clear()
        act = PenAct.NONE
        invalidate()
    }

    // ---- Bildauswahl

    private fun selectionDown(d: PageDoc, im: ImageItem, x: Float, y: Float, dx: Float, dy: Float) {
        val s = scaleOf(d)
        val l = d.offX + im.x * s; val t = d.offY + im.y * s
        val r = l + im.w * s; val b = t + im.h * s
        val corners = arrayOf(floatArrayOf(l, t), floatArrayOf(r, t), floatArrayOf(r, b), floatArrayOf(l, b))
        val hr = 28f * dens
        for (k in 0..3) {
            if (hypot(x - corners[k][0], y - corners[k][1]) <= hr) {
                val opp = corners[(k + 2) % 4]
                selFixX = (opp[0] - d.offX) / s
                selFixY = (opp[1] - d.offY) / s
                selAspect = im.w / im.h
                d.pushUndo()
                act = PenAct.SCALE
                return
            }
        }
        if (x in l..r && y in t..b) {
            grabDX = dx - im.x; grabDY = dy - im.y
            d.pushUndo()
            act = PenAct.MOVE
            return
        }
        act = PenAct.NONE
    }

    private inline fun updateSel(d: PageDoc, f: (ImageItem) -> ImageItem) {
        val id = d.selectedImage ?: return
        d.items = d.items.map { if (it is ImageItem && it.id == id) f(it) else it }
    }

    fun rotateSelected() {
        val d = doc ?: return
        if (d.selected() == null) return
        d.pushUndo()
        updateSel(d) {
            val cx = it.x + it.w / 2f; val cy = it.y + it.h / 2f
            it.copy(rot = (it.rot + 1) % 4, w = it.h, h = it.w, x = cx - it.h / 2f, y = cy - it.w / 2f)
        }
        d.dirty = true
        host.onDocEdited()
        invalidate()
    }

    // ---- Messen

    private fun updateMeasure(d: PageDoc) {
        val a = measureA; val b = measureB
        if (a == null || b == null) { measureText = null; host.refreshStatus(); return }
        val lenPt = hypot(b.x - a.x, b.y - a.y)
        val real = lenPt / PT_PER_MM * d.scale / d.unit.mm
        measureText = String.format(Locale.GERMANY, "%.2f %s", real, d.unit.label)
        host.refreshStatus()
    }

    fun refreshMeasure() { doc?.let { updateMeasure(it) } }

    fun clearMeasure() {
        measureA = null; measureB = null; measureText = null
        invalidate()
    }

    // ---- Finger

    private var fCount = 0
    private val fIds = IntArray(2)
    private var panX = 0f; private var panY = 0f
    private var onRulerG = false
    private var g0cx = 0f; private var g0cy = 0f; private var g0span = 1f; private var g0ang = 0f
    private var g0zoom = 1f; private var g0offX = 0f; private var g0offY = 0f; private var g0time = 0L
    private var g0rx = 0f; private var g0ry = 0f; private var g0ra = 0f
    private var lastCx = 0f; private var lastCy = 0f; private var lastSpan = 1f

    private fun fingersChanged(d: PageDoc, e: MotionEvent, upIndex: Int) {
        val ids = ArrayList<Int>(2)
        for (i in 0 until e.pointerCount) {
            if (i == upIndex || isPen(e, i)) continue
            if (ids.size < 2) ids.add(e.getPointerId(i))
        }
        if (fCount == 2 && ids.size == 2 && ids[0] == fIds[0] && ids[1] == fIds[1]) return
        if (fCount == 2) endTwo(d)
        fCount = ids.size
        if (fCount == 1) {
            fIds[0] = ids[0]
            val i = e.findPointerIndex(fIds[0])
            if (i >= 0) { panX = e.getX(i); panY = e.getY(i) }
        } else if (fCount == 2) {
            fIds[0] = ids[0]; fIds[1] = ids[1]
            startTwo(d, e)
        }
    }

    private fun endFingers(d: PageDoc) {
        if (fCount == 2) endTwo(d)
        fCount = 0
    }

    private fun startTwo(d: PageDoc, e: MotionEvent) {
        val i0 = e.findPointerIndex(fIds[0]); val i1 = e.findPointerIndex(fIds[1])
        if (i0 < 0 || i1 < 0) { fCount = 0; return }
        val x0 = e.getX(i0); val y0 = e.getY(i0); val x1 = e.getX(i1); val y1 = e.getY(i1)
        g0cx = (x0 + x1) / 2f; g0cy = (y0 + y1) / 2f
        g0span = max(hypot(x1 - x0, y1 - y0), 1f)
        g0ang = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        g0zoom = d.zoom; g0offX = d.offX; g0offY = d.offY
        g0rx = tools.rulerX; g0ry = tools.rulerY; g0ra = tools.rulerAngle
        g0time = SystemClock.uptimeMillis()
        lastCx = g0cx; lastCy = g0cy; lastSpan = g0span
        onRulerG = onRuler(x0, y0) && onRuler(x1, y1)
    }

    private fun endTwo(d: PageDoc) {
        val dt = SystemClock.uptimeMillis() - g0time
        val dx = lastCx - g0cx; val dy = lastCy - g0cy
        val ratio = lastSpan / g0span
        val minSwipe = host.ppi() / 25.4f * 12f
        if (!onRulerG && dt < 700 && abs(dx) > minSwipe && abs(dy) < abs(dx) * 0.6f && abs(ratio - 1f) < 0.2f) {
            d.zoom = g0zoom; d.offX = g0offX; d.offY = g0offY
            viewChanged()
            host.onUndoGesture(dx < 0)
        }
        fCount = 0
    }

    private fun fingerMove(d: PageDoc, e: MotionEvent) {
        if (fCount == 1) {
            val i = e.findPointerIndex(fIds[0])
            if (i < 0) return
            val x = e.getX(i); val y = e.getY(i)
            d.offX += x - panX; d.offY += y - panY
            panX = x; panY = y
            viewChanged()
        } else if (fCount == 2) {
            val i0 = e.findPointerIndex(fIds[0]); val i1 = e.findPointerIndex(fIds[1])
            if (i0 < 0 || i1 < 0) return
            val x0 = e.getX(i0); val y0 = e.getY(i0); val x1 = e.getX(i1); val y1 = e.getY(i1)
            val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f
            val span = max(hypot(x1 - x0, y1 - y0), 1f)
            val ang = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
            lastCx = cx; lastCy = cy; lastSpan = span
            if (onRulerG) {
                tools.rulerX = g0rx + cx - g0cx
                tools.rulerY = g0ry + cy - g0cy
                val diff = ((ang - g0ang + 540f) % 360f) - 180f
                tools.rulerAngle = snapAngle(g0ra + diff)
                host.refreshStatus()
                invalidate()
            } else if (tools.zoomLocked) {
                d.offX = g0offX + cx - g0cx
                d.offY = g0offY + cy - g0cy
                viewChanged()
            } else {
                var z = (g0zoom * span / g0span).coerceIn(0.1f, 32f)
                if (abs(z - 1f) <= 0.03f) z = 1f
                val s0 = pxPerPt() * g0zoom
                val px = (g0cx - g0offX) / s0
                val py = (g0cy - g0offY) / s0
                d.zoom = z
                val s1 = scaleOf(d)
                d.offX = cx - px * s1
                d.offY = cy - py * s1
                viewChanged()
            }
        }
    }
}
