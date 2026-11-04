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
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.File
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
import kotlin.math.sign
import kotlin.math.sin

@SuppressLint("ViewConstructor")
class CanvasView(ctx: Context, private val tools: ToolState, private val host: Host) : View(ctx) {

    interface Host {
        fun ppi(): Float
        fun onDocEdited()
        fun onViewChanged()
        fun refreshStatus()
        fun onUndoGesture()
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

    // ------------------------------------------------------------------ Ansicht (pt → Bildschirm)

    private val vm = Matrix()
    private val vmInv = Matrix()
    private val pt = FloatArray(2)

    private fun viewMatrix(d: PageDoc, out: Matrix = vm): Matrix {
        val s = scaleOf(d)
        out.reset()
        out.setScale(s, s)
        out.postRotate(d.rot)
        out.postTranslate(d.offX, d.offY)
        return out
    }

    private fun toDoc(d: PageDoc, x: Float, y: Float): PointF {
        viewMatrix(d).invert(vmInv)
        pt[0] = x; pt[1] = y
        vmInv.mapPoints(pt)
        return PointF(pt[0], pt[1])
    }

    private fun toScreen(d: PageDoc, x: Float, y: Float): PointF {
        pt[0] = x; pt[1] = y
        viewMatrix(d).mapPoints(pt)
        return PointF(pt[0], pt[1])
    }

    /** Verschiebt die Ansicht so, dass der Dokumentpunkt (px, py) auf dem Bildschirmpunkt (sx, sy) liegt. */
    private fun place(d: PageDoc, px: Float, py: Float, sx: Float, sy: Float) {
        d.offX = 0f; d.offY = 0f
        val q = toScreen(d, px, py)
        d.offX = sx - q.x
        d.offY = sy - q.y
    }

    fun initView(d: PageDoc) {
        d.zoom = 1f
        d.rot = 0f
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

    fun setZoom(z: Float) {
        val d = doc ?: return
        val c = toDoc(d, width / 2f, height / 2f)
        d.zoom = z
        place(d, c.x, c.y, width / 2f, height / 2f)
        viewChanged()
    }

    fun setPlanRotation(r: Float) {
        val d = doc ?: return
        val c = toDoc(d, width / 2f, height / 2f)
        d.rot = r
        place(d, c.x, c.y, width / 2f, height / 2f)
        viewChanged()
    }

    private fun viewChanged() {
        host.onViewChanged()
        requestHiRes()
        invalidate()
    }

    /** Nach Änderungen an den Elementen (Import fixiert, Undo …). */
    fun itemsChanged() {
        requestHiRes()
        invalidate()
    }

    // ------------------------------------------------------------------ Hochauflösendes Rendern

    private var hires: Bitmap? = null
    private var hrDoc: PageDoc? = null
    private val hrInv = Matrix()
    private var hrSigs: Set<String> = emptySet()
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
        hrSigs = emptySet()
    }

    private fun sig(im: ImageItem) = "${im.id}|${im.x}|${im.y}|${im.w}|${im.h}|${im.rot}"

    private fun itemMatrix(im: ImageItem): Matrix {
        val odd = im.rot % 2 == 1
        val cw = if (odd) im.h else im.w
        val ch = if (odd) im.w else im.h
        val m = Matrix()
        m.setScale(cw / im.srcW, ch / im.srcH)
        m.postTranslate(-cw / 2f, -ch / 2f)
        m.postRotate(im.rot * 90f)
        m.postTranslate(im.x + im.w / 2f, im.y + im.h / 2f)
        return m
    }

    private fun doHiRes() {
        val d = doc ?: return
        val r = d.renderer ?: return
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val s = scaleOf(d)
        val pdfItems = d.items.filter { it is ImageItem && it.isPdf && it.fixed }.map { it as ImageItem }
        val ov = d.overview
        val baseNeeds = ov == null || d.pageW * s > ov.width * 1.1f
        val itemNeeds = pdfItems.any { im ->
            val b = d.bitmaps[im.id]
            val cw = if (im.rot % 2 == 1) im.h else im.w
            b == null || cw * s > b.width * 1.1f
        }
        if (!baseNeeds && !itemNeeds) {
            if (hires != null) { dropHiRes(); invalidate() }
            return
        }
        val gen = ++hrGen
        val m = Matrix(viewMatrix(d))
        val itemMs = pdfItems.map { Matrix(itemMatrix(it)).apply { postConcat(m) } }
        val sigs = pdfItems.map { sig(it) }.toSet()
        r.execute {
            val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (t: Throwable) { null }
            if (bmp != null) {
                var ok = true
                try {
                    r.render(bmp, m)
                    if (pdfItems.isNotEmpty()) {
                        val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val cv = Canvas(bmp)
                        for ((k, im) in pdfItems.withIndex()) {
                            if (gen != hrGen) break
                            tmp.eraseColor(Color.TRANSPARENT)
                            ParcelFileDescriptor.open(File(im.file), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                                PdfRenderer(pfd).use { pr ->
                                    pr.openPage(0).use { pg -> pg.render(tmp, null, itemMs[k], PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                }
                            }
                            cv.drawBitmap(tmp, 0f, 0f, null)
                        }
                        tmp.recycle()
                    }
                } catch (e: Throwable) { ok = false }
                post {
                    if (ok && gen == hrGen && doc === d) {
                        hires?.recycle()
                        hires = bmp; hrDoc = d
                        m.invert(hrInv)
                        hrSigs = sigs
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
        val m = viewMatrix(d)
        val page = RectF(0f, 0f, d.pageW, d.pageH)

        // Sichtbarer Bereich in pt
        m.invert(vmInv)
        val vis = RectF(0f, 0f, width.toFloat(), height.toFloat())
        vmInv.mapRect(vis)
        val minW = 1f / s

        c.save()
        c.concat(m)
        fillP.color = d.bgColor
        c.drawRect(page, fillP)
        c.clipRect(page)
        d.overview?.let { if (!it.isRecycled) c.drawBitmap(it, null, page, bmpP) }
        val hr = hires
        val hrValid = hr != null && hrDoc === d && !hr.isRecycled
        if (hrValid) {
            c.save()
            c.concat(hrInv)
            c.drawBitmap(hr!!, 0f, 0f, bmpP)
            c.restore()
        }
        val items = d.items
        for (it in items) if (it is ImageItem && !(hrValid && it.isPdf && it.fixed && sig(it) in hrSigs)) drawImage(c, d, it)
        for (it in items) {
            when (it) {
                is Stroke -> if (visible(it.bounds, it.width, vis)) drawPoly(c, it.pts, it.path, it.width, it.color, minW)
                is Cover -> if (visible(it.bounds, it.width, vis)) drawPoly(c, it.pts, it.path, it.width, it.color, minW)
                else -> {}
            }
        }
        if (act == PenAct.DRAW && cur.n >= 2) drawCurrent(c, curColor, curWidth, minW)
        if (act == PenAct.ERASE && cur.n >= 2 && tools.eraseBackground && eraseCover) drawCurrent(c, d.bgColor, eraseWidthPt, minW)
        c.restore()

        // Auswahl (Bild)
        d.selected()?.let { im ->
            val cs = floatArrayOf(im.x, im.y, im.x + im.w, im.y, im.x + im.w, im.y + im.h, im.x, im.y + im.h)
            m.mapPoints(cs)
            strokeP.color = Pal.BLACK; strokeP.strokeWidth = 2f * dens
            tmpPath.reset()
            tmpPath.moveTo(cs[0], cs[1]); tmpPath.lineTo(cs[2], cs[3]); tmpPath.lineTo(cs[4], cs[5]); tmpPath.lineTo(cs[6], cs[7]); tmpPath.close()
            c.drawPath(tmpPath, strokeP)
            for (k in 0..3) {
                fillP.color = Pal.WHITE; c.drawCircle(cs[2 * k], cs[2 * k + 1], 12f * dens, fillP)
                c.drawCircle(cs[2 * k], cs[2 * k + 1], 12f * dens, strokeP)
            }
        }

        // Messlinie
        if (tools.tool == Tool.MEASURE) {
            val a = measureA; val b = measureB
            if (a != null && b != null) {
                val ps = floatArrayOf(a.x, a.y, b.x, b.y)
                m.mapPoints(ps)
                strokeP.color = Pal.BLACK; strokeP.strokeWidth = 1.5f * dens
                c.drawLine(ps[0], ps[1], ps[2], ps[3], strokeP)
                fillP.color = Pal.BLACK
                c.drawCircle(ps[0], ps[1], 3f * dens, fillP)
                c.drawCircle(ps[2], ps[3], 3f * dens, fillP)
            }
        }

        if (tools.rulerOn) drawRuler(c, d)

        if (act == PenAct.ERASE && eraserSX >= 0f) {
            strokeP.color = Pal.BLACK; strokeP.strokeWidth = 1f * dens
            c.drawCircle(eraserSX, eraserSY, eraserPx() / 2f, strokeP)
        }
    }

    private fun visible(b: RectF, w: Float, v: RectF) =
        !(b.right + w < v.left || b.left - w > v.right || b.bottom + w < v.top || b.top - w > v.bottom)

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

    // ------------------------------------------------------------------ Lineal (Bildschirmraum)

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
        return String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
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

    private fun norm360(a: Float) = ((a % 360f) + 360f) % 360f

    private fun snapTo(a: Float, step: Float, tol: Float): Float {
        val m = norm360(a)
        val n = (m / step).roundToInt() * step
        return if (abs(m - n) <= tol) norm360(n) else m
    }

    private fun angleDiff(a: Float, b: Float) = ((a - b + 540f) % 360f) - 180f

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
    private var eraseCover = true
    private var itemsBeforeErase: List<Item>? = null
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
                else {
                    mode = Mode.FINGER
                    tapStart(d, e)
                    fingersChanged(d, e, -1)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (mode == Mode.FINGER && isPen(e, i)) {
                    endFingers()
                    tapValid = false
                    mode = Mode.PEN
                    penDown(d, e, i)
                } else if (mode == Mode.FINGER) {
                    tapAdd(e, i)
                    fingersChanged(d, e, -1)
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.PEN -> penMove(d, e)
                Mode.FINGER -> { tapMove(e); fingerMove(d, e) }
                else -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                if (mode == Mode.PEN && e.getPointerId(i) == penId) { penUp(d, e, i); mode = Mode.NONE }
                else if (mode == Mode.FINGER) fingersChanged(d, e, i)
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.PEN && e.getPointerId(0) == penId) penUp(d, e, 0)
                else if (mode == Mode.FINGER) { fingersChanged(d, e, 0); tapEnd(d) }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.PEN) finishAction(d)
                if (mode == Mode.FINGER) endFingers()
                tapValid = false
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun endActions() {
        val d = doc ?: return
        if (mode == Mode.PEN) finishAction(d)
        mode = Mode.NONE
        fCount = 0
    }

    // ---- Dreifinger-Tipp = Rückgängig

    private var tapValid = false
    private var tapT0 = 0L
    private var tapMaxF = 0
    private val tapStarts = HashMap<Int, PointF>()
    private var tapZoom = 1f; private var tapRot = 0f; private var tapOffX = 0f; private var tapOffY = 0f

    private fun tapStart(d: PageDoc, e: MotionEvent) {
        tapValid = true
        tapT0 = SystemClock.uptimeMillis()
        tapMaxF = 1
        tapStarts.clear()
        tapStarts[e.getPointerId(0)] = PointF(e.getX(0), e.getY(0))
        tapZoom = d.zoom; tapRot = d.rot; tapOffX = d.offX; tapOffY = d.offY
    }

    private fun tapAdd(e: MotionEvent, i: Int) {
        tapStarts[e.getPointerId(i)] = PointF(e.getX(i), e.getY(i))
        var n = 0
        for (k in 0 until e.pointerCount) if (!isPen(e, k)) n++
        tapMaxF = max(tapMaxF, n)
    }

    private fun tapMove(e: MotionEvent) {
        if (!tapValid) return
        val lim = 20f * dens
        for (k in 0 until e.pointerCount) {
            val s = tapStarts[e.getPointerId(k)] ?: continue
            if (hypot(e.getX(k) - s.x, e.getY(k) - s.y) > lim) { tapValid = false; return }
        }
    }

    private fun tapEnd(d: PageDoc) {
        if (tapValid && tapMaxF >= 3 && SystemClock.uptimeMillis() - tapT0 < 600) {
            d.zoom = tapZoom; d.rot = tapRot; d.offX = tapOffX; d.offY = tapOffY
            viewChanged()
            host.onUndoGesture()
        }
        tapValid = false
    }

    // ---- Stift

    private fun penDown(d: PageDoc, e: MotionEvent, i: Int) {
        host.onPenDown()
        penId = e.getPointerId(i)
        val x = e.getX(i); val y = e.getY(i)
        val p = toDoc(d, x, y)
        val sel = d.selected()
        if (sel != null) { selectionDown(d, sel, x, y, p.x, p.y); return }
        tempEraser = buttonEraser(e, i)
        startAction(d, p.x, p.y, x, y)
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
        eraseCover = tools.eraseBackground
        itemsBeforeErase = d.items
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
        if (act == PenAct.DRAW || (act == PenAct.ERASE && tempEraser)) {
            val be = buttonEraser(e, i)
            if (be != tempEraser) {
                val x = e.getX(i); val y = e.getY(i)
                val p = toDoc(d, x, y)
                finishAction(d)
                tempEraser = be
                startAction(d, p.x, p.y, x, y)
                return
            }
        }
        val s = scaleOf(d)
        viewMatrix(d).invert(vmInv)
        for (h in 0 until e.historySize) penPoint(d, e.getHistoricalX(i, h), e.getHistoricalY(i, h), s)
        penPoint(d, e.getX(i), e.getY(i), s)
        invalidate()
    }

    /** vmInv muss aktuell sein. */
    private fun penPoint(d: PageDoc, x: Float, y: Float, s: Float) {
        pt[0] = x; pt[1] = y
        vmInv.mapPoints(pt)
        val dx = pt[0]; val dy = pt[1]
        when (act) {
            PenAct.DRAW -> {
                lastx = dx; lasty = dy
                if (tools.rulerOn) { rulerLine(d, dx, dy); return }
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

    /** Parallelführung: Lineal liegt im Bildschirmraum, die Richtung im Dokument ist um die Planrotation verschoben. */
    private fun rulerLine(d: PageDoc, dx: Float, dy: Float) {
        val a = Math.toRadians((tools.rulerAngle - d.rot).toDouble())
        val ux = cos(a).toFloat(); val uy = sin(a).toFloat()
        val t = (dx - sx0) * ux + (dy - sy0) * uy
        cur.clear()
        cur.add(sx0, sy0)
        cur.add(sx0 + ux * t, sy0 + uy * t)
    }

    private fun penUp(d: PageDoc, e: MotionEvent, i: Int) {
        if (act == PenAct.DRAW && !straight && !tools.rulerOn) {
            val s = scaleOf(d)
            val p = toDoc(d, e.getX(i), e.getY(i))
            lastx = p.x; lasty = p.y
            val n = cur.n
            if (hypot(p.x - cur.a[n - 2], p.y - cur.a[n - 1]) * s >= 0.25f) cur.add(p.x, p.y)
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
                if (eraseCover) {
                    d.items = d.items + Cover(d.bgColor, eraseWidthPt, cur.toArray())
                    d.dirty = true
                } else if (d.items === itemsBeforeErase) {
                    d.dropUndo()
                } else d.dirty = true
                itemsBeforeErase = null
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
        val corners = floatArrayOf(im.x, im.y, im.x + im.w, im.y, im.x + im.w, im.y + im.h, im.x, im.y + im.h)
        val sc = corners.copyOf()
        viewMatrix(d).mapPoints(sc)
        val hr = 28f * dens
        for (k in 0..3) {
            if (hypot(x - sc[2 * k], y - sc[2 * k + 1]) <= hr) {
                val o = (k + 2) % 4
                selFixX = corners[2 * o]
                selFixY = corners[2 * o + 1]
                selAspect = im.w / im.h
                d.pushUndo()
                act = PenAct.SCALE
                return
            }
        }
        if (dx in im.x..(im.x + im.w) && dy in im.y..(im.y + im.h)) {
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
        measureText = String.format(Locale.US, "%.2f %s", real, d.unit.label)
        host.refreshStatus()
    }

    fun refreshMeasure() { doc?.let { updateMeasure(it) } }

    fun clearMeasure() {
        measureA = null; measureB = null; measureText = null
        invalidate()
    }

    // ---- Finger: 1 = verschieben, 2 = zoomen/drehen/verschieben bzw. Lineal

    private var fCount = 0
    private val fIds = IntArray(2)
    private var panX = 0f; private var panY = 0f
    private var onRulerG = false
    private var rotating = false
    private var g0cx = 0f; private var g0cy = 0f; private var g0span = 1f; private var g0ang = 0f
    private var g0zoom = 1f; private var g0rot = 0f; private var g0px = 0f; private var g0py = 0f
    private var g0rx = 0f; private var g0ry = 0f; private var g0ra = 0f

    private fun fingersChanged(d: PageDoc, e: MotionEvent, upIndex: Int) {
        val ids = ArrayList<Int>(2)
        for (i in 0 until e.pointerCount) {
            if (i == upIndex || isPen(e, i)) continue
            if (ids.size < 2) ids.add(e.getPointerId(i))
        }
        if (fCount == 2 && ids.size == 2 && ids[0] == fIds[0] && ids[1] == fIds[1]) return
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

    private fun endFingers() { fCount = 0 }

    private fun startTwo(d: PageDoc, e: MotionEvent) {
        val i0 = e.findPointerIndex(fIds[0]); val i1 = e.findPointerIndex(fIds[1])
        if (i0 < 0 || i1 < 0) { fCount = 0; return }
        val x0 = e.getX(i0); val y0 = e.getY(i0); val x1 = e.getX(i1); val y1 = e.getY(i1)
        g0cx = (x0 + x1) / 2f; g0cy = (y0 + y1) / 2f
        g0span = max(hypot(x1 - x0, y1 - y0), 1f)
        g0ang = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        g0zoom = d.zoom; g0rot = d.rot
        val p = toDoc(d, g0cx, g0cy)
        g0px = p.x; g0py = p.y
        g0rx = tools.rulerX; g0ry = tools.rulerY; g0ra = tools.rulerAngle
        rotating = false
        onRulerG = onRuler(x0, y0) && onRuler(x1, y1)
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
            if (onRulerG) {
                tools.rulerX = g0rx + cx - g0cx
                tools.rulerY = g0ry + cy - g0cy
                tools.rulerAngle = snapTo(g0ra + angleDiff(ang, g0ang), 15f, 3f)
                host.refreshStatus()
                invalidate()
                return
            }
            if (!tools.zoomLocked) {
                var z = (g0zoom * span / g0span).coerceIn(0.1f, 32f)
                if (abs(z - 1f) <= 0.03f) z = 1f
                d.zoom = z
            }
            if (!tools.rotLocked) {
                var delta = angleDiff(ang, g0ang)
                if (!rotating && abs(delta) > 12f) {
                    rotating = true
                    g0ang += 12f * sign(delta)
                    delta = angleDiff(ang, g0ang)
                }
                d.rot = if (rotating) snapTo(g0rot + delta, 45f, 5f) else g0rot
            }
            place(d, g0px, g0py, cx, cy)
            viewChanged()
        }
    }
}
