package app.zeichenbrett

import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Affine 2D-Abbildung: x' = a·x + c·y + e, y' = b·x + d·y + f */
class Aff(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    /** this ∘ o (erst o, dann this) */
    operator fun times(o: Aff) = Aff(
        a * o.a + c * o.b,
        b * o.a + d * o.b,
        a * o.c + c * o.d,
        b * o.c + d * o.d,
        a * o.e + c * o.f + e,
        b * o.e + d * o.f + f,
    )

    fun pdf() = Matrix(a, b, c, d, e, f)

    companion object {
        val I = Aff(1f, 0f, 0f, 1f, 0f, 0f)
        fun T(x: Float, y: Float) = Aff(1f, 0f, 0f, 1f, x, y)
        fun S(x: Float, y: Float) = Aff(x, 0f, 0f, y, 0f, 0f)
        fun R(rad: Float): Aff {
            val c = cos(rad); val s = sin(rad)
            return Aff(c, s, -s, c, 0f, 0f)
        }
    }
}

object Geo {

    fun distPtSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax; val vy = by - ay
        val l2 = vx * vx + vy * vy
        var t = if (l2 > 0f) ((px - ax) * vx + (py - ay) * vy) / l2 else 0f
        t = t.coerceIn(0f, 1f)
        val qx = ax + t * vx - px; val qy = ay + t * vy - py
        return sqrt(qx * qx + qy * qy)
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    fun segSegDist(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, ex: Float, ey: Float,
    ): Float {
        val d1 = cross(ax, ay, bx, by, cx, cy)
        val d2 = cross(ax, ay, bx, by, ex, ey)
        val d3 = cross(cx, cy, ex, ey, ax, ay)
        val d4 = cross(cx, cy, ex, ey, bx, by)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return 0f
        return minOf(
            distPtSeg(cx, cy, ax, ay, bx, by),
            distPtSeg(ex, ey, ax, ay, bx, by),
            distPtSeg(ax, ay, cx, cy, ex, ey),
            distPtSeg(bx, by, cx, cy, ex, ey),
        )
    }

    /**
     * Radiert eigene Striche entlang des Radiersegments (a→b) mit Radius r (pt).
     * Gibt null zurück, wenn sich nichts geändert hat.
     */
    fun erase(items: List<Item>, ax: Float, ay: Float, bx: Float, by: Float, r: Float): List<Item>? {
        val minX = min(ax, bx) - r; val maxX = max(ax, bx) + r
        val minY = min(ay, by) - r; val maxY = max(ay, by) + r
        var changed = false
        val out = ArrayList<Item>(items.size + 4)
        for (it in items) {
            if (it is Fill) {
                // Füllungen werden geometrisch ausgeschnitten
                val fb = it.bounds
                if (fb.right < minX || fb.left > maxX || fb.bottom < minY || fb.top > maxY) { out.add(it); continue }
                val res = eraseFill(it, ax, ay, bx, by, r)
                if (res == null) out.add(it) else { changed = true; if (res.polys.isNotEmpty()) out.add(res) }
                continue
            }
            if (it !is Stroke) { out.add(it); continue }
            val hw = it.width / 2f
            val bb = it.bounds
            if (bb.right + hw < minX || bb.left - hw > maxX || bb.bottom + hw < minY || bb.top - hw > maxY) {
                out.add(it); continue
            }
            // Getroffene Linie wird zur gefüllten Kontur (gleichmäßige Breite) und wie eine Füllung
            // geschnitten: Schnittkanten folgen exakt der Radiererform.
            if (!touches(it, ax, ay, bx, by, r + hw)) { out.add(it); continue }
            val res = eraseFill(Fill(it.color, polysOf(strokeOutline(it))), ax, ay, bx, by, r)
            if (res == null) out.add(it) else { changed = true; if (res.polys.isNotEmpty()) out.add(res) }
        }
        return if (changed) out else null
    }

    private fun touches(s: Stroke, ax: Float, ay: Float, bx: Float, by: Float, rr: Float): Boolean {
        val p = s.pts
        if (p.size == 2) return distPtSeg(p[0], p[1], ax, ay, bx, by) <= rr
        var i = 0
        while (i + 3 < p.size) {
            if (segSegDist(p[i], p[i + 1], p[i + 2], p[i + 3], ax, ay, bx, by) <= rr) return true
            i += 2
        }
        return false
    }

    /** Umriss eines Strichs (runde Enden und Ecken) als Fläche. */
    fun strokeOutline(s: Stroke): android.graphics.Path {
        val dst = android.graphics.Path()
        val p = s.pts
        if (p.size == 2) {
            dst.addCircle(p[0], p[1], s.width / 2f, android.graphics.Path.Direction.CW)
            return dst
        }
        val paint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = s.width
            strokeCap = if (s.rs && s.re) android.graphics.Paint.Cap.ROUND else android.graphics.Paint.Cap.BUTT
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        paint.getFillPath(s.path, dst)
        // Überlappungen auflösen (einfache Konturen)
        val simple = android.graphics.Path()
        return if (simple.op(dst, android.graphics.Path(), android.graphics.Path.Op.UNION)) simple else dst
    }

    /** Radierkapsel als Vieleck, so fein unterteilt, dass die Bögen rund wirken (Abweichung ≤ 0,01 pt). */
    private fun capsulePath(ax: Float, ay: Float, bx: Float, by: Float, r: Float): android.graphics.Path {
        val path = android.graphics.Path()
        val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
        val seg = (Math.PI / kotlin.math.acos((1.0 - 0.01 / max(r, 0.02f)).coerceIn(-1.0, 1.0)).coerceAtLeast(1e-3)).toInt().coerceIn(8, 90)
        if (len < 1e-4f) {
            for (k in 0 until 2 * seg) {
                val t = Math.PI * k / seg
                val x = (bx + r * cos(t)).toFloat(); val y = (by + r * sin(t)).toFloat()
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        } else {
            val base = kotlin.math.atan2((by - ay).toDouble(), (bx - ax).toDouble())
            // Halbkreis um b (vorne), dann Halbkreis um a (hinten)
            for (k in 0..seg) {
                val t = base - Math.PI / 2 + Math.PI * k / seg
                val x = (bx + r * cos(t)).toFloat(); val y = (by + r * sin(t)).toFloat()
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (k in 0..seg) {
                val t = base + Math.PI / 2 + Math.PI * k / seg
                path.lineTo((ax + r * cos(t)).toFloat(), (ay + r * sin(t)).toFloat())
            }
        }
        path.close()
        return path
    }

    /** Radierkapsel aus der Füllung ausschneiden; null = unverändert. */
    private fun eraseFill(f: Fill, ax: Float, ay: Float, bx: Float, by: Float, r: Float): Fill? {
        // Schnelltest: berührt die Kapsel überhaupt eine Kante oder liegt sie innen?
        val cap = capsulePath(ax, ay, bx, by, r)
        val hit = android.graphics.Path()
        if (!hit.op(f.path, cap, android.graphics.Path.Op.INTERSECT) || hit.isEmpty) return null
        val outP = android.graphics.Path()
        if (!outP.op(f.path, cap, android.graphics.Path.Op.DIFFERENCE)) return null
        // Vereinfachen, winzige Reste verwerfen: die Punktzahl bleibt klein
        val polys = polysOf(outP).map { FillAlgo.simplify(it, 0.005f) }.filter { it.size >= 6 && abs(area(it)) > 0.05f }
        return Fill(f.color, polys, f.under)
    }

    private fun area(p: FloatArray): Float {
        var a = 0f
        val n = p.size / 2
        for (i in 0 until n) { val j = (i + 1) % n; a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1] }
        return a / 2f
    }

    /** Konturen eines Pfads als Polygone (Kurven angenähert). */
    fun polysOf(p: android.graphics.Path): List<FloatArray> {
        val a = p.approximate(0.02f)
        val out = ArrayList<FloatArray>()
        val cur = FList()
        var i = 0
        while (i + 2 < a.size) {
            val fr = a[i]; val x = a[i + 1]; val y = a[i + 2]
            // Sprung zur nächsten Kontur: zwei Punkte mit gleichem Anteil
            if (i > 0 && fr == a[i - 3] && cur.n > 0 && (x != a[i - 2] || y != a[i - 1])) {
                if (cur.points >= 3) out.add(cur.toArray())
                cur.clear()
            }
            if (cur.n == 0 || x != cur.a[cur.n - 2] || y != cur.a[cur.n - 1]) cur.add(x, y)
            i += 3
        }
        if (cur.points >= 3) out.add(cur.toArray())
        return out
    }

    /**
     * Bereich [t0, t1] eines Segments P(t) = (x0,y0) + t·(x1−x0, y1−y0), der in der Radierkapsel
     * (Abstand ≤ rr zur Strecke a–b) liegt. Analytisch: Kreise um a und b plus Rechteck dazwischen.
     */
    private fun capsuleInterval(x0: Float, y0: Float, x1: Float, y1: Float, ax: Float, ay: Float, bx: Float, by: Float, rr: Float): FloatArray? {
        val dx = (x1 - x0).toDouble(); val dy = (y1 - y0).toDouble()
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        fun circle(cx: Float, cy: Float) {
            val fx = (x0 - cx).toDouble(); val fy = (y0 - cy).toDouble()
            val A = dx * dx + dy * dy
            val B = 2 * (fx * dx + fy * dy)
            val C = fx * fx + fy * fy - rr.toDouble() * rr
            if (A < 1e-12) { if (C <= 0) { lo = min(lo, 0.0); hi = max(hi, 1.0) }; return }
            val disc = B * B - 4 * A * C
            if (disc < 0) return
            val sq = sqrt(disc)
            lo = min(lo, (-B - sq) / (2 * A)); hi = max(hi, (-B + sq) / (2 * A))
        }
        circle(ax, ay); circle(bx, by)
        // Rechteck im lokalen System der Kapsel: u ∈ [0, L], v ∈ [−rr, rr]
        val ex = (bx - ax).toDouble(); val ey = (by - ay).toDouble()
        val L = sqrt(ex * ex + ey * ey)
        if (L > 1e-9) {
            val ux = ex / L; val uy = ey / L
            val u0 = (x0 - ax) * ux + (y0 - ay) * uy; val du = dx * ux + dy * uy
            val v0 = -(x0 - ax) * uy + (y0 - ay) * ux; val dv = -dx * uy + dy * ux
            var t0 = -1e18; var t1 = 1e18
            fun clip(p0: Double, dp: Double, mn: Double, mx: Double): Boolean {
                if (abs(dp) < 1e-12) return p0 >= mn && p0 <= mx
                var a = (mn - p0) / dp; var b = (mx - p0) / dp
                if (a > b) { val t = a; a = b; b = t }
                t0 = max(t0, a); t1 = min(t1, b)
                return t0 <= t1
            }
            if (clip(u0, du, 0.0, L) && clip(v0, dv, -rr.toDouble(), rr.toDouble())) { lo = min(lo, t0); hi = max(hi, t1) }
        }
        if (lo > hi) return null
        val a = max(lo, 0.0); val b = min(hi, 1.0)
        // Berührung oder nur ein Hauch: unverändert lassen (verhindert Zerfasern am Schnittrand)
        val segLen = sqrt(dx * dx + dy * dy)
        if (b <= a || (b - a) * segLen < 0.005) return null
        return floatArrayOf(a.toFloat(), b.toFloat())
    }

    /**
     * Strich exakt am Rand der Radierkapsel (Abstand rr zur Mittellinie) schneiden.
     * Neue Enden sind gerade, wie der Schnitt durch eine Füllung.
     */
    private fun split(s: Stroke, ax: Float, ay: Float, bx: Float, by: Float, rr: Float): List<Stroke>? {
        val p = s.pts
        val n = p.size / 2
        if (n == 1) return if (distPtSeg(p[0], p[1], ax, ay, bx, by) <= rr + s.width / 2f) emptyList() else null
        val out = ArrayList<Stroke>()
        val run = FList(p.size + 8)
        var runRound = s.rs
        var removed = false
        fun flush(endRound: Boolean) {
            if (run.points >= 2) {
                val a = run.toArray()
                // Teilstücke ohne Länge verwerfen
                var len = 0f
                for (k in 0 until a.size / 2 - 1) len += sqrt((a[2 * k + 2] - a[2 * k]).let { it * it } + (a[2 * k + 3] - a[2 * k + 1]).let { it * it })
                if (len > 0.01f) out.add(Stroke(s.color, s.width, a, runRound, endRound))
            }
            run.clear()
        }
        for (i in 0 until n - 1) {
            val x0 = p[2 * i]; val y0 = p[2 * i + 1]
            val x1 = p[2 * i + 2]; val y1 = p[2 * i + 3]
            val iv = capsuleInterval(x0, y0, x1, y1, ax, ay, bx, by, rr)
            if (iv == null) {
                if (run.n == 0) run.add(x0, y0)
                run.add(x1, y1)
                continue
            }
            removed = true
            val t0 = iv[0]; val t1 = iv[1]
            if (t0 > 0f) {
                if (run.n == 0) run.add(x0, y0)
                run.add(x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0)
            }
            flush(false)
            runRound = false
            if (t1 < 1f) {
                run.add(x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1)
                run.add(x1, y1)
            }
        }
        if (!removed) return null
        flush(s.re)
        return out
    }
}
