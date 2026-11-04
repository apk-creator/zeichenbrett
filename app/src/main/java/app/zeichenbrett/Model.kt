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
    val WHITE = Color.rgb(250, 250, 250)
    val GREY = Color.rgb(140, 140, 140)

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

enum class Tool { MEASURE, PEN, ERASER }

/** Werkzeugzustand, gilt für alle Dokumente. */
class ToolState {
    var tool = Tool.PEN
    var pen = 1
    val penWidth = floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f, 0.25f)
    var eraserPct = 10f
    var rulerOn = false
    var rulerPlaced = false
    var rulerX = 0f
    var rulerY = 0f
    var rulerAngle = 0f
    var zoomLocked = false
    var rotLocked = false
    var eraseBackground = true
}

sealed class Item

/** Eigener Strich. Koordinaten in pt, Ursprung oben links. */
class Stroke(val color: Int, val width: Float, val pts: FloatArray) : Item() {
    val bounds: RectF = boundsOf(pts)
    val path: Path by lazy { pathOf(pts) }
}

/** Abdeckung durch den Radierer (Vektorfläche in Zeichenflächenfarbe). */
class Cover(val color: Int, val width: Float, val pts: FloatArray) : Item() {
    val bounds: RectF = boundsOf(pts)
    val path: Path by lazy { pathOf(pts) }
}

/**
 * Importiertes Bild oder PDF. Box (x, y, w, h) in pt, achsparallel nach der Drehung.
 * srcW/srcH: Pixel (Bild) bzw. pt (PDF) des ungedrehten Inhalts.
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
    val rot: Int,
    val fixed: Boolean,
) : Item()

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
    var title = "Unbenannt"
    var sourceUri: Uri? = null
    var writable = false
    var fromMulti = false
    var pageW = 595f
    var pageH = 842f
    var scale = 100
    var unit = MUnit.M
    var dpi = 150
    var bgColor = Pal.PAPER
    var backupDone = false

    @Volatile var items: List<Item> = emptyList()
    private val undoStack = ArrayDeque<List<Item>>()
    private val redoStack = ArrayDeque<List<Item>>()
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
