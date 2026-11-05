package app.zeichenbrett

import com.tom_roush.pdfbox.util.Matrix
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
            val parts = split(it, ax, ay, bx, by, r + hw)
            if (parts == null) out.add(it) else { changed = true; out.addAll(parts) }
        }
        return if (changed) out else null
    }

    /** Radierkapsel (a→b, Radius r) aus der Füllung ausschneiden; null = unverändert. */
    private fun eraseFill(f: Fill, ax: Float, ay: Float, bx: Float, by: Float, r: Float): Fill? {
        val cap = android.graphics.Path()
        cap.addCircle(ax, ay, r, android.graphics.Path.Direction.CW)
        val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
        if (len > 1e-4f) {
            cap.addCircle(bx, by, r, android.graphics.Path.Direction.CW)
            val nx = -(by - ay) / len * r; val ny = (bx - ax) / len * r
            cap.moveTo(ax + nx, ay + ny); cap.lineTo(bx + nx, by + ny); cap.lineTo(bx - nx, by - ny); cap.lineTo(ax - nx, ay - ny); cap.close()
        }
        val src = f.path
        val hit = android.graphics.Path()
        if (!hit.op(src, cap, android.graphics.Path.Op.INTERSECT) || hit.isEmpty) return null
        val outP = android.graphics.Path()
        if (!outP.op(src, cap, android.graphics.Path.Op.DIFFERENCE)) return null
        return Fill(f.color, polysOf(outP), f.under)
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

    private fun split(s: Stroke, ax: Float, ay: Float, bx: Float, by: Float, rr: Float): List<Stroke>? {
        val p = s.pts
        val n = p.size / 2
        if (n == 1) return if (distPtSeg(p[0], p[1], ax, ay, bx, by) <= rr) emptyList() else null

        // Segmente in Radiernähe fein unterteilen
        val step = max(rr * 0.4f, 0.02f)
        val res = FList(p.size + 64)
        res.add(p[0], p[1])
        var anyNear = false
        for (i in 0 until n - 1) {
            val x0 = p[2 * i]; val y0 = p[2 * i + 1]
            val x1 = p[2 * i + 2]; val y1 = p[2 * i + 3]
            val near = segSegDist(x0, y0, x1, y1, ax, ay, bx, by) <= rr
            if (near) {
                anyNear = true
                val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
                val k = max(1, ceil(len / step).toInt())
                for (j in 1..k) {
                    val t = j.toFloat() / k
                    res.add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
                }
            } else {
                res.add(x1, y1)
            }
        }
        if (!anyNear) return null

        val out = ArrayList<Stroke>()
        val run = FList(res.n)
        var removed = false
        var i = 0
        while (i < res.n) {
            val x = res.a[i]; val y = res.a[i + 1]
            if (distPtSeg(x, y, ax, ay, bx, by) > rr) {
                run.add(x, y)
            } else {
                removed = true
                if (run.points >= 2) out.add(Stroke(s.color, s.width, run.toArray()))
                run.clear()
            }
            i += 2
        }
        if (!removed) return null
        if (run.points >= 2) out.add(Stroke(s.color, s.width, run.toArray()))
        return out
    }
}
