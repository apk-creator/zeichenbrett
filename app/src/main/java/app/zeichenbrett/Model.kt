package app.zeichenbrett

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import java.io.File

const val PT_PER_MM = 72f / 25.4f
const val MAX_DOCS = 15
const val MAX_UNDO = 100

object Pal {
    val BACKGROUND = Color.rgb(215, 215, 215)
    val PAPER = Color.rgb(250, 250, 250)
    val BLACK = Color.rgb(5, 5, 5)
    val WHITE = Color.rgb(245, 245, 245)
    val GREY = Color.rgb(140, 140, 140)
    /** Aktivierte Buttons/Labels: dunkelgrau gefüllt, weiße Schrift. */
    val ACTIVE_BG = Color.rgb(120, 120, 120)
    val ACTIVE_FG = Color.rgb(250, 250, 250)

    /** Weiß, Schwarz, Rot, Grün, Blau – Reihenfolge der Werkzeugpalette. */
    val PENS = intArrayOf(
        Color.rgb(250, 250, 250),
        Color.rgb(5, 5, 5),
        Color.rgb(170, 0, 0),
        Color.rgb(50, 100, 50),
        Color.rgb(0, 0, 170),
    )
}

enum class MUnit(val label: String, val mm: Double) {
    M("m", 1000.0), CM("cm", 10.0), MM("mm", 1.0)
}

enum class Tool { MEASURE, PEN, FILL, ERASER }

/** Werkzeugzustand, gilt für alle Dokumente. */
class ToolState {
    var tool = Tool.PEN
    var pen = 1
    val penWidth = floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f, 0.3f)
    var eraserPct = 10f
    var rulerOn = false
    var rulerPlaced = false
    var rulerX = 0f
    var rulerY = 0f
    var rulerAngle = 0f
    var zoomLocked = false
    var rotLocked = false
    /** Hintergrund (PDF, Importe) ignorieren: Radierer und Füllen wirken nur auf eigene Elemente. */
    var ignoreBackground = true
    /** Lineal: auch rechtwinklig zum Lineal zeichnen. */
    var rulerOrtho = false
    /** Messen: Fläche statt Länge. */
    var measureArea = false
    /** Je Stift: Füllen statt Zeichnen (bleibt beim Werkzeugwechsel erhalten). */
    val fillMode = BooleanArray(5)
    /** Füllen: Toleranz in % (0 % = keine, 100 % = 0,5 pt); Öffnungen bis zu dieser Breite gelten als geschlossen. */
    val fillPct = FloatArray(5) { DEF_FILL_PCT }
    /** 0 % = keine Toleranz, 1–100 % = 0,1–0,5 pt (feiner lässt sich nicht sinnvoll unterscheiden). */
    fun fillTol(i: Int) = if (fillPct[i] <= 0f) 0.0001f else 0.01f + 0.49f * (fillPct[i] - 1f) / 99f

    companion object {
        const val DEF_PEN_WIDTH = 0.3f
        const val DEF_FILL_PCT = 0f
        const val DEF_ERASER_PCT = 10f
    }
}

sealed class Item

/** Eigener Strich. Koordinaten in pt, Ursprung oben links. */
/** rs/re: Anfang/Ende rund; beim Radieren entstehen gerade abgeschnittene Enden (wie bei Füllungen). */
class Stroke(val color: Int, val width: Float, val pts: FloatArray, val rs: Boolean = true, val re: Boolean = true) : Item() {
    val bounds: RectF = boundsOf(pts)
    val path: Path by lazy { pathOf(pts) }
}

/** Abdeckung durch den Radierer (Vektorfläche in Zeichenflächenfarbe). */
class Cover(val color: Int, val width: Float, val pts: FloatArray) : Item() {
    val bounds: RectF = boundsOf(pts)
    val path: Path by lazy { pathOf(pts) }
}

/** Füllung einer geschlossenen Fläche: Polygone (gerade-ungerade-Regel), Koordinaten in pt. */
/**
 * under = true: liegt unter der Hintergrund-PDF (deren Linien und Text bleiben darüber sichtbar),
 * sonst über dem Hintergrund in der normalen Reihenfolge.
 */
class Fill(val color: Int, val polys: List<FloatArray>, val under: Boolean = false) : Item() {
    val bounds: RectF = RectF().also { r -> polys.forEachIndexed { i, p -> if (i == 0) r.set(boundsOf(p)) else r.union(boundsOf(p)) } }
    val path: Path by lazy {
        Path().also { path ->
            path.fillType = Path.FillType.WINDING
            for (p in polys) { path.addPath(pathOf(p)); path.close() }
        }
    }

    /** Liegt (x, y) in der Fläche? */
    fun contains(x: Float, y: Float): Boolean {
        if (!bounds.contains(x, y)) return false
        var inside = false
        for (p in polys) {
            val n = p.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = p[2 * i]; val yi = p[2 * i + 1]; val xj = p[2 * j]; val yj = p[2 * j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }
}

/**
 * Importiertes Bild oder PDF. (x, y, w, h) = ungedrehter Inhalt in pt, gedreht um [angle] Grad
 * (im Uhrzeigersinn) um seine Mitte. srcW/srcH: Pixel (Bild) bzw. pt (PDF).
 * ox/oy: Mittelpunkt beim Import (für „1:1“).
 */
data class ImageItem(
    val id: String,
    val isPdf: Boolean,
    val isJpeg: Boolean,
    val file: String,
    val srcW: Float,
    val srcH: Float,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val angle: Float,
    val fixed: Boolean,
    /** Bildauflösung aus den Metadaten (für „Originalgröße“). */
    val srcDpi: Float = 150f,
    val ox: Float = 0f,
    val oy: Float = 0f,
) : Item() {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f

    /** Ecken (oben links, oben rechts, unten rechts, unten links) im Dokument. */
    fun corners(): FloatArray {
        val m = android.graphics.Matrix()
        m.setRotate(angle, cx, cy)
        val c = floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h)
        m.mapPoints(c)
        return c
    }
}

fun boundsOf(p: FloatArray): RectF {
    if (p.size < 2) return RectF()
    var l = p[0]; var t = p[1]; var r = p[0]; var b = p[1]
    var i = 2
    while (i + 1 < p.size) {
        val x = p[i]; val y = p[i + 1]
        if (x < l) l = x
        if (x > r) r = x
        if (y < t) t = y
        if (y > b) b = y
        i += 2
    }
    return RectF(l, t, r, b)
}

fun pathOf(p: FloatArray): Path {
    val path = Path()
    if (p.size < 2) return path
    path.moveTo(p[0], p[1])
    var i = 2
    while (i + 1 < p.size) {
        path.lineTo(p[i], p[i + 1]); i += 2
    }
    return path
}

/** Ein Tab = eine Seite = ein Dokument. */
class PageDoc(val id: String, val workDir: File) {
    var title = "Dokument"
    var sourceUri: Uri? = null
    /** Name der zugehörigen Datei (ohne .pdf); weicht der Titel ab, entfällt „Überschreiben“. */
    var fileTitle: String? = null
    var writable = false
    var fromMulti = false
    var pageW = 595f
    var pageH = 842f
    var scale = 100
    var unit = MUnit.M
    /** Maßeinheit beim Öffnen/Anlegen (Doppeltipp auf „Messen“ setzt zurück). */
    var initUnit = MUnit.M
    var dpi = 150
    var bgColor = Pal.PAPER
    var backupDone = false

    @Volatile var items: List<Item> = emptyList()
    val undoStack = ArrayDeque<List<Item>>()
    val redoStack = ArrayDeque<List<Item>>()
    var dirty = false

    // Ansicht
    var zoom = 1f
    var rot = 0f
    var offX = 0f
    var offY = 0f
    var viewInit = false

    var selectedImage: String? = null
    val bitmaps = HashMap<String, Bitmap?>()
    var overview: Bitmap? = null
    var renderer: PageRenderer? = null

    val baseFile: File get() = File(workDir, "base.pdf")

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun pushUndo() {
        undoStack.addLast(items)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Letzten Undo-Schritt verwerfen (wenn eine Aktion nichts verändert hat). */
    fun dropUndo() {
        if (undoStack.isNotEmpty()) undoStack.removeLast()
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.addLast(items)
        items = undoStack.removeLast()
        dirty = true
        return true
    }

    /** Letzten Redo-Eintrag verwerfen (nach einem intern zurückgenommenen Schritt). */
    fun redoClearLast() {
        if (redoStack.isNotEmpty()) redoStack.removeLast()
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.addLast(items)
        items = redoStack.removeLast()
        dirty = true
        return true
    }

    fun selected(): ImageItem? {
        val id = selectedImage ?: return null
        return items.firstOrNull { it is ImageItem && it.id == id } as ImageItem?
    }
}

/** Wachsende Float-Liste für Punktfolgen x,y,x,y,… */
class FList(cap: Int = 256) {
    var a = FloatArray(cap)
    var n = 0
    fun add(x: Float, y: Float) {
        if (n + 2 > a.size) a = a.copyOf(maxOf(a.size * 2, n + 2))
        a[n++] = x; a[n++] = y
    }
    fun clear() { n = 0 }
    fun toArray(): FloatArray = a.copyOf(n)
    val points get() = n / 2
}
