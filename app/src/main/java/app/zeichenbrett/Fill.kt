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

    class Result(val polys: List<FloatArray>?, val error: String?, val under: Boolean = false)

    /**
     * under: je Pixel 0 = frei, sonst 1 + Anzahl Pixel, die die Füllung unter diese Linie reichen darf (bis zur Mitte).
     * r: Verdickung der Linien in px (Toleranz).
     */
    fun compute(
        under: ByteArray, w: Int, h: Int, sx: Int, sy: Int, r0: Int,
        openL: Boolean, openT: Boolean, openR: Boolean, openB: Boolean,
    ): Result {
        val n = w * h
        val line = BooleanArray(n) { under[it].toInt() != 0 }
        if (sx !in 0 until w || sy !in 0 until h) return Result(null, "Außerhalb der Zeichenfläche.")
        val start = sy * w + sx
        if (line[start]) return Result(null, "Hier ist keine freie Fläche.")
        var r = r0
        var closed = if (r > 0) dilate(line, w, h, r) else line
        var small: BooleanArray? = null
        if (closed[start]) {
            // Kleine Fläche, die ganz im Toleranzband liegt: vollständig von Linien umschlossen?
            small = smallArea(line, closed, w, h, start, openL, openT, openR, openB)
            if (small == null) {
                // Sonst Toleranz verringern, bis der Startpunkt frei ist
                while (r > 0 && closed[start]) { r--; closed = if (r > 0) dilate(line, w, h, r) else line }
            }
        }
        val region = small ?: BooleanArray(n)
        if (small == null) {

        // 2. Fluten (4er-Nachbarschaft, Scanline)
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
        }

        // 3a. Bis an die Linien wachsen lassen (ergibt in Öffnungen die halbe Toleranz)
        val grown = if (r > 0 && small == null) dilate(region, w, h, r) else region.copyOf()
        for (i in 0 until n) if (line[i]) grown[i] = false
        // 3b. Spitze Winkel: freie Reste, die vollständig im verdickten Linienband liegen und an die Fläche grenzen,
        //     gehören dazu (z. B. die Spitze eines spitzen Winkels). Reste mit Verbindung nach außen nicht.
        if (r > 0 && small == null) {
            val seen = BooleanArray(n)
            var st = IntArray(4096)
            var comp = IntArray(4096)
            for (s0 in 0 until n) {
                if (seen[s0] || line[s0] || grown[s0]) continue
                var sp2 = 0; var cn = 0
                var thin = true; var touches = false
                st[sp2++] = s0; seen[s0] = true
                while (sp2 > 0) {
                    val p = st[--sp2]
                    if (cn == comp.size) comp = comp.copyOf(cn * 2)
                    comp[cn++] = p
                    if (!closed[p]) thin = false
                    val x = p % w; val y = p / w
                    if ((x == 0 && openL) || (x == w - 1 && openR) || (y == 0 && openT) || (y == h - 1 && openB)) thin = false
                    for (k in 0 until 4) {
                        val q = when (k) { 0 -> if (x > 0) p - 1 else -1; 1 -> if (x < w - 1) p + 1 else -1; 2 -> if (y > 0) p - w else -1; else -> if (y < h - 1) p + w else -1 }
                        if (q < 0) continue
                        if (grown[q]) { touches = true; continue }
                        if (line[q] || seen[q]) continue
                        seen[q] = true
                        if (sp2 == st.size) st = st.copyOf(sp2 * 2)
                        st[sp2++] = q
                    }
                }
                if (thin && touches) for (i in 0 until cn) grown[comp[i]] = true
            }
        }
        // 3c. Unter die Linien bis zu ihrer Mitte (die Linien liegen darüber und decken die Kante ab)
        var maxU = 0
        for (i in 0 until n) { val u = under[i].toInt() and 0xFF; if (u > maxU) maxU = u }
        var cur = grown
        for (k in 1 until maxU) {
            val g2 = dilate(cur, w, h, 1)
            var any = false
            for (i in 0 until n) {
                if (g2[i] && !cur[i]) {
                    if (line[i] && (under[i].toInt() and 0xFF) - 1 >= k) any = true else g2[i] = false
                }
            }
            cur = g2
            if (!any) break
        }
        val filled = cur

        // 4. Umriss → Polygone
        val loops = trace(filled, w, h)
        if (loops.isEmpty()) return Result(null, "Hier ist keine freie Fläche.")
        // Vereinfachen erst nach dem Einrasten auf die Linienmitten (CanvasView)
        return Result(loops.map { smooth(it) }.filter { it.size >= 6 }, null)
    }

    /** Freie Pixel um den Start, wenn sie alle im Linienband liegen und keinen offenen Rand berühren; sonst null. */
    private fun smallArea(
        line: BooleanArray, closed: BooleanArray, w: Int, h: Int, start: Int,
        openL: Boolean, openT: Boolean, openR: Boolean, openB: Boolean,
    ): BooleanArray? {
        val out = BooleanArray(line.size)
        var st = IntArray(1024)
        var sp = 0
        st[sp++] = start; out[start] = true
        while (sp > 0) {
            val p = st[--sp]
            if (!closed[p]) return null
            val x = p % w; val y = p / w
            if ((x == 0 && openL) || (x == w - 1 && openR) || (y == 0 && openT) || (y == h - 1 && openB)) return null
            for (k in 0 until 4) {
                val q = when (k) { 0 -> if (x > 0) p - 1 else -1; 1 -> if (x < w - 1) p + 1 else -1; 2 -> if (y > 0) p - w else -1; else -> if (y < h - 1) p + w else -1 }
                if (q < 0 || line[q] || out[q]) continue
                out[q] = true
                if (sp == st.size) st = st.copyOf(sp * 2)
                st[sp++] = q
            }
        }
        return out
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
        val lim = 0.5f
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
    fun simplify(p: FloatArray, tol: Float): FloatArray {
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
