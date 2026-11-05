package app.zeichenbrett

import kotlin.math.hypot
import kotlin.math.max

/**
 * Füllen geschlossener Flächen.
 *
 * Eingabe ist ein gerasterter Ausschnitt der eigenen Striche.
 * 1. Linien um r px verdicken → Lücken bis zur Toleranz gelten als geschlossen.
 * 2. Freie Fläche ab dem Startpunkt fluten. Berührt sie einen offenen Rand des Ausschnitts
 *    (= Ausschnitt liegt innerhalb der Seite), muss mit größerem Ausschnitt gesucht werden.
 *    Ränder an der Seitenkante sind geschlossen: offene Flächen werden bis zum Rand gefüllt.
 * 3. Fläche bis an die Linien und 2 px darunter wachsen lassen (die Linien liegen darüber).
 * 4. Umriss in geschlossene Polygone umwandeln, glätten und vereinfachen.
 */
object FillAlgo {

    const val LEAK = "leak"

    class Result(val polys: List<FloatArray>?, val error: String?)

    /** line: Linienpixel des Ausschnitts (w×h), r: Verdickung in px. */
    fun compute(
        line: BooleanArray, w: Int, h: Int, sx: Int, sy: Int, r: Int,
        openL: Boolean, openT: Boolean, openR: Boolean, openB: Boolean,
    ): Result {
        val n = w * h
        if (sx !in 0 until w || sy !in 0 until h) return Result(null, "Außerhalb der Zeichenfläche.")
        val closed = if (r > 0) dilate(line, w, h, r) else line
        if (closed[sy * w + sx]) return Result(null, "Hier ist keine freie Fläche.")

        // 2. Fluten (4er-Nachbarschaft, Scanline)
        val region = BooleanArray(n)
        var stack = IntArray(65536)
        var sp = 0
        stack[sp++] = sy * w + sx
        var leak = false
        while (sp > 0) {
            val p = stack[--sp]
            val y = p / w
            var x = p % w
            if (region[p] || closed[p]) continue
            while (x > 0 && !closed[y * w + x - 1] && !region[y * w + x - 1]) x--
            var upOpen = false; var downOpen = false
            while (x < w && !closed[y * w + x] && !region[y * w + x]) {
                val q = y * w + x
                region[q] = true
                if ((x == 0 && openL) || (x == w - 1 && openR) || (y == 0 && openT) || (y == h - 1 && openB)) leak = true
                if (y > 0) {
                    val u = q - w
                    val free = !closed[u] && !region[u]
                    if (free && !upOpen) { if (sp == stack.size) stack = stack.copyOf(sp * 2); stack[sp++] = u; upOpen = true } else if (!free) upOpen = false
                }
                if (y < h - 1) {
                    val dn = q + w
                    val free = !closed[dn] && !region[dn]
                    if (free && !downOpen) { if (sp == stack.size) stack = stack.copyOf(sp * 2); stack[sp++] = dn; downOpen = true } else if (!free) downOpen = false
                }
                x++
            }
            if (leak) break
        }
        if (leak) return Result(null, LEAK)

        // 3. Bis an die Linien wachsen lassen, dann 2 px unter die Linien
        var grown = if (r > 0) dilate(region, w, h, r) else region
        for (i in 0 until n) if (line[i]) grown[i] = false
        for (k in 0 until 2) {
            val g2 = dilate(grown, w, h, 1)
            for (i in 0 until n) g2[i] = g2[i] && (grown[i] || line[i])
            grown = g2
        }

        // 4. Umriss → Polygone
        val loops = trace(grown, w, h)
        if (loops.isEmpty()) return Result(null, "Hier ist keine freie Fläche.")
        return Result(loops.map { simplify(smooth(it), 0.35f) }.filter { it.size >= 6 }, null)
    }

    /** Quadratische Dilatation mit Radius r (getrennt nach Zeilen und Spalten). */
    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(m.size)
        for (y in 0 until h) {
            val row = y * w
            var cnt = 0
            for (x in 0 until minOf(r, w)) if (m[row + x]) cnt++
            for (x in 0 until w) {
                val add = x + r; if (add < w && m[row + add]) cnt++
                val rem = x - r - 1; if (rem >= 0 && m[row + rem]) cnt--
                tmp[row + x] = cnt > 0
            }
        }
        val out = BooleanArray(m.size)
        for (x in 0 until w) {
            var cnt = 0
            for (y in 0 until minOf(r, h)) if (tmp[y * w + x]) cnt++
            for (y in 0 until h) {
                val add = y + r; if (add < h && tmp[add * w + x]) cnt++
                val rem = y - r - 1; if (rem >= 0 && tmp[rem * w + x]) cnt--
                out[y * w + x] = cnt > 0
            }
        }
        return out
    }

    /** Treppenstufen glätten: gleitender Mittelwert, Verschiebung höchstens 0,6 px. */
    private fun smooth(p: FloatArray): FloatArray {
        val n = p.size / 2
        if (n < 8) return p
        var cur = p
        repeat(2) {
            val nx = FloatArray(cur.size)
            for (i in 0 until n) {
                var sx = 0f; var sy = 0f
                for (k in -2..2) { val j = ((i + k) % n + n) % n; sx += cur[2 * j]; sy += cur[2 * j + 1] }
                nx[2 * i] = sx / 5f; nx[2 * i + 1] = sy / 5f
            }
            cur = nx
        }
        val lim = 0.6f
        for (i in 0 until n) {
            val dx = cur[2 * i] - p[2 * i]; val dy = cur[2 * i + 1] - p[2 * i + 1]
            val d = hypot(dx, dy)
            if (d > lim) { cur[2 * i] = p[2 * i] + dx / d * lim; cur[2 * i + 1] = p[2 * i + 1] + dy / d * lim }
        }
        return cur
    }

    /**
     * Pixelkanten zwischen Fläche und Nicht-Fläche zu geschlossenen Schleifen verketten.
     * Rückgabe: Punktfolgen (x,y,…) im Pixelraster (Ecken der Pixel).
     */
    private fun trace(m: BooleanArray, w: Int, h: Int): List<FloatArray> {
        val W = w + 1
        val next = HashMap<Int, IntArray>()   // Ecke → bis zu 2 ausgehende Kanten
        fun add(a: Int, b: Int) {
            val arr = next.getOrPut(a) { intArrayOf(-1, -1) }
            if (arr[0] < 0) arr[0] = b else arr[1] = b
        }
        fun inM(x: Int, y: Int) = x in 0 until w && y in 0 until h && m[y * w + x]
        for (y in 0 until h) for (x in 0 until w) {
            if (!m[y * w + x]) continue
            if (!inM(x, y - 1)) add(y * W + x, y * W + x + 1)                       // oben →
            if (!inM(x + 1, y)) add(y * W + x + 1, (y + 1) * W + x + 1)             // rechts ↓
            if (!inM(x, y + 1)) add((y + 1) * W + x + 1, (y + 1) * W + x)           // unten ←
            if (!inM(x - 1, y)) add((y + 1) * W + x, y * W + x)                     // links ↑
        }
        val loops = ArrayList<FloatArray>()
        for (start in next.keys.toList()) {
            while (true) {
                val s = next[start] ?: break
                if (s[0] < 0 && s[1] < 0) break
                val pts = FList()
                var cur = start
                var guard = 0
                while (guard++ < 10_000_000) {
                    pts.add((cur % W).toFloat(), (cur / W).toFloat())
                    val arr = next[cur] ?: break
                    val nx = if (arr[0] >= 0) { val v = arr[0]; arr[0] = -1; v } else if (arr[1] >= 0) { val v = arr[1]; arr[1] = -1; v } else break
                    cur = nx
                    if (cur == start) break
                }
                if (pts.points >= 3) loops.add(pts.toArray())
            }
        }
        return loops
    }

    private fun removeCollinear(p: FloatArray): FloatArray {
        val n = p.size / 2
        val out = FList(p.size)
        for (i in 0 until n) {
            val a = (i - 1 + n) % n; val c = (i + 1) % n
            val ax = p[2 * a]; val ay = p[2 * a + 1]; val bx = p[2 * i]; val by = p[2 * i + 1]; val cx = p[2 * c]; val cy = p[2 * c + 1]
            val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            if (cross != 0f) out.add(bx, by)
        }
        return out.toArray()
    }

    /** Douglas-Peucker für geschlossene Polygone. */
    private fun simplify(p: FloatArray, tol: Float): FloatArray {
        val n = p.size / 2
        if (n < 8) return p
        // Startpunkt und am weitesten entfernter Punkt teilen die Schleife in zwei Ketten
        var far = 0; var fd = -1f
        for (i in 1 until n) {
            val d = hypot(p[2 * i] - p[0], p[2 * i + 1] - p[1])
            if (d > fd) { fd = d; far = i }
        }
        val keep = BooleanArray(n)
        keep[0] = true; keep[far] = true
        dp(p, 0, far, tol, keep, n)
        dp(p, far, n, tol, keep, n)
        val out = FList(p.size)
        for (i in 0 until n) if (keep[i]) out.add(p[2 * i], p[2 * i + 1])
        return out.toArray()
    }

    private fun dp(p: FloatArray, a: Int, b: Int, tol: Float, keep: BooleanArray, n: Int) {
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(a, b))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast().let { it[0] to it[1] }
            if (e - s < 2) continue
            val ax = p[2 * s]; val ay = p[2 * s + 1]
            val ei = e % n
            val bx = p[2 * ei]; val by = p[2 * ei + 1]
            var idx = -1; var md = tol
            for (i in s + 1 until e) {
                val d = Geo.distPtSeg(p[2 * i], p[2 * i + 1], ax, ay, bx, by)
                if (d > md) { md = d; idx = i }
            }
            if (idx >= 0) {
                keep[idx] = true
                stack.addLast(intArrayOf(s, idx))
                stack.addLast(intArrayOf(idx, e))
            }
        }
    }
}
