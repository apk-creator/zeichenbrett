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
        dst.fillType = android.graphics.Path.FillType.WINDING
        return dst
    }

    /** Radierkapsel als Vieleck, so fein unterteilt, dass die Bögen rund wirken (Abweichung ≤ 0,01 pt). */
    fun capsulePath(ax: Float, ay: Float, bx: Float, by: Float, r: Float): android.graphics.Path {
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

    /**
     * Radierspur (Fläche in pt) aus eigenen Strichen und Füllungen schneiden – einmal am Ende des Radierens.
     * Getroffene Striche werden zur Umrissfläche (gleichmäßige Breite) und wie Füllungen geschnitten.
     * Ergebnisflächen gelten mit der Gerade-ungerade-Regel (so liefert sie Path.op).
     */
    /**
     * segs: Mittellinien der Radierkapseln (x0,y0,x1,y1,…), r: Radius – für einen genauen, schnellen Vortest,
     * damit nur wirklich getroffene Elemente geschnitten werden (nur eine Pfadoperation je Element).
     */
    fun cutWithSweep(items: List<Item>, sweep: android.graphics.Path, segs: FloatArray, r: Float): List<Item>? {
        val sb = android.graphics.RectF(); sweep.computeBounds(sb, true)
        var changed = false
        val out = ArrayList<Item>(items.size + 4)
        for (it in items) {
            val src: android.graphics.Path
            val color: Int; val under: Boolean
            when (it) {
                is Fill -> {
                    if (!android.graphics.RectF.intersects(it.bounds, sb) || !fillHit(it, sb, segs, r)) { out.add(it); continue }
                    src = it.path; color = it.color; under = it.under
                }
                is Stroke -> {
                    val hw = it.width / 2f
                    val bb = android.graphics.RectF(it.bounds); bb.inset(-hw, -hw)
                    if (!android.graphics.RectF.intersects(bb, sb) || !nearRect(it, sb, hw) || !strokeHit(it, sb, segs, r + hw)) { out.add(it); continue }
                    src = strokeOutline(it); color = it.color; under = false
                }
                else -> { out.add(it); continue }
            }
            val rest = android.graphics.Path()
            if (!rest.op(src, sweep, android.graphics.Path.Op.DIFFERENCE)) { out.add(it); continue }
            val polys = polysOf(rest).map { FillAlgo.simplify(it, 0.005f) }.filter { it.size >= 6 && abs(area(it)) > 0.05f }
            changed = true
            if (polys.isNotEmpty()) out.add(Fill(color, polys, under, evenOdd = true))
        }
        return if (changed) out else null
    }

    /** Liegt ein Strichsegment näher als rr an einer Radiermittellinie? */
    private fun strokeHit(s: Stroke, sb: android.graphics.RectF, segs: FloatArray, rr: Float): Boolean {
        val p = s.pts
        val l = sb.left - rr; val t = sb.top - rr; val ri = sb.right + rr; val b = sb.bottom + rr
        if (p.size == 2) {
            var k = 0
            while (k + 3 < segs.size) { if (distPtSeg(p[0], p[1], segs[k], segs[k + 1], segs[k + 2], segs[k + 3]) <= rr) return true; k += 4 }
            return false
        }
        var i = 0
        while (i + 3 < p.size) {
            val x0 = p[i]; val y0 = p[i + 1]; val x1 = p[i + 2]; val y1 = p[i + 3]
            i += 2
            if (max(x0, x1) < l || min(x0, x1) > ri || max(y0, y1) < t || min(y0, y1) > b) continue
            var k = 0
            while (k + 3 < segs.size) {
                if (segSegDist(x0, y0, x1, y1, segs[k], segs[k + 1], segs[k + 2], segs[k + 3]) <= rr) return true
                k += 4
            }
        }
        return false
    }

    /** Wird die Füllung getroffen? Kante in Reichweite oder Radierer liegt innen. */
    private fun fillHit(f: Fill, sb: android.graphics.RectF, segs: FloatArray, r: Float): Boolean {
        if (segs.size >= 2 && f.contains(segs[0], segs[1])) return true
        val l = sb.left; val t = sb.top; val ri = sb.right; val b = sb.bottom
        for (p in f.polys) {
            val n = p.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                val x0 = p[2 * i]; val y0 = p[2 * i + 1]; val x1 = p[2 * j]; val y1 = p[2 * j + 1]
                if (max(x0, x1) < l || min(x0, x1) > ri || max(y0, y1) < t || min(y0, y1) > b) continue
                var k = 0
                while (k + 3 < segs.size) {
                    if (segSegDist(x0, y0, x1, y1, segs[k], segs[k + 1], segs[k + 2], segs[k + 3]) <= r) return true
                    k += 4
                }
            }
        }
        return false
    }

    /** Berührt ein Segment des Strichs das (um hw erweiterte) Rechteck? Schneller Vortest. */
    private fun nearRect(s: Stroke, r: android.graphics.RectF, hw: Float): Boolean {
        val l = r.left - hw; val t = r.top - hw; val rr = r.right + hw; val b = r.bottom + hw
        val p = s.pts
        if (p.size == 2) return p[0] in l..rr && p[1] in t..b
        var i = 0
        while (i + 3 < p.size) {
            val x0 = p[i]; val y0 = p[i + 1]; val x1 = p[i + 2]; val y1 = p[i + 3]
            i += 2
            if (max(x0, x1) < l || min(x0, x1) > rr || max(y0, y1) < t || min(y0, y1) > b) continue
            // Liang-Barsky
            var t0 = 0f; var t1 = 1f
            val dx = x1 - x0; val dy = y1 - y0
            var ok = true
            for ((pp, q) in arrayOf(-dx to x0 - l, dx to rr - x0, -dy to y0 - t, dy to b - y0)) {
                if (pp == 0f) { if (q < 0f) { ok = false; break } }
                else {
                    val u = q / pp
                    if (pp < 0f) { if (u > t1) { ok = false; break }; if (u > t0) t0 = u }
                    else { if (u < t0) { ok = false; break }; if (u < t1) t1 = u }
                }
            }
            if (ok) return true
        }
        return false
    }

    private fun area(p: FloatArray): Float {
        var a = 0f
        val n = p.size / 2
        for (i in 0 until n) { val j = (i + 1) % n; a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1] }
        return a / 2f
    }

    /** Konturen eines Pfads als Polygone: jede Kontur einzeln (keine Verwechslung beim Übergang). */
    fun polysOf(p: android.graphics.Path): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        val pm = android.graphics.PathMeasure(p, false)
        do {
            val len = pm.length
            if (len <= 0f) continue
            val one = android.graphics.Path()
            if (!pm.getSegment(0f, len, one, true)) continue
            val a = one.approximate(0.02f)
            val cur = FList()
            var i = 0
            while (i + 2 < a.size) {
                val x = a[i + 1]; val y = a[i + 2]
                if (cur.n == 0 || x != cur.a[cur.n - 2] || y != cur.a[cur.n - 1]) cur.add(x, y)
                i += 3
            }
            // Schlusspunkt = Anfangspunkt nicht doppelt
            if (cur.points >= 2 && cur.a[0] == cur.a[cur.n - 2] && cur.a[1] == cur.a[cur.n - 1]) cur.n -= 2
            if (cur.points >= 3) out.add(cur.toArray())
        } while (pm.nextContour())
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
