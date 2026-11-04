package app.zeichenbrett

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ZbInfo(val scale: Int?, val unit: MUnit?, val dpi: Int?, val items: List<Item>?)
class PageInfo(val w: Float, val h: Float, val zb: ZbInfo?)

/**
 * PDF lesen und schreiben.
 *
 * Jede Seite wird beim Öffnen in eine eigene „Basis-PDF“ normalisiert (Rotation 0, Ursprung 0/0).
 * Eigene Striche/Abdeckungen landen beim Speichern in einem markierten Inhaltsstrom (/ZBDraw BMC),
 * zusätzlich als JSON im Seitenwörterbuch (/ZBData). Beim erneuten Öffnen wird der Strom
 * entfernt und die Striche werden wieder editierbar – sofern die Prüfsumme passt.
 */
object PdfIO {
    private val ZB_DATA: COSName = COSName.getPDFName("ZBData")
    private const val MARK = "ZBDraw"

    fun createBlank(out: File, w: Float, h: Float) {
        PDDocument().use { d ->
            val p = PDPage(PDRectangle(w, h))
            d.addPage(p)
            PDPageContentStream(d, p).use { }
            d.save(out)
        }
    }

    fun createFromImage(out: File, img: File, isJpeg: Boolean, pxW: Int, pxH: Int, pageW: Float, pageH: Float) {
        PDDocument().use { d ->
            val p = PDPage(PDRectangle(pageW, pageH))
            d.addPage(p)
            val x = loadImage(d, img, isJpeg)
            val s = min(pageW / pxW, pageH / pxH)
            val w = pxW * s; val h = pxH * s
            PDPageContentStream(d, p).use { cs -> cs.drawImage(x, (pageW - w) / 2f, (pageH - h) / 2f, w, h) }
            d.save(out)
        }
    }

    private fun loadImage(d: PDDocument, img: File, isJpeg: Boolean): PDImageXObject =
        if (isJpeg) FileInputStream(img).use { JPEGFactory.createFromStream(d, it) }
        else {
            val b = BitmapFactory.decodeFile(img.path)
            try { LosslessFactory.createFromImage(d, b) } finally { b.recycle() }
        }

    /** Liest nur die gespeicherten Einstellungen (ohne etwas zu verändern). */
    fun peekZb(page: PDPage): ZbInfo? {
        val ds = page.cosObject.getDictionaryObject(ZB_DATA) as? COSStream ?: return null
        val json = try { JSONObject(String(ds.createInputStream().use { it.readBytes() }, Charsets.UTF_8)) } catch (e: Exception) { return null }
        return ZbInfo(
            json.optInt("scale", 0).takeIf { it > 0 },
            runCatching { MUnit.valueOf(json.optString("unit")) }.getOrNull(),
            json.optInt("dpi", 0).takeIf { it > 0 },
            null,
        )
    }

    /**
     * Normalisiert Seite [index] von [src] als einseitige PDF in [out].
     * strip = true: eigene Striche werden aus dem Inhalt entfernt und als Items zurückgegeben.
     */
    fun extractPage(src: PDDocument, index: Int, out: File, strip: Boolean): PageInfo {
        val page = src.getPage(index)
        val zb = if (strip) readAndStrip(page) else null
        val crop = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val w = crop.width
        val h = crop.height
        val dw = if (rot == 90 || rot == 270) h else w
        val dh = if (rot == 90 || rot == 270) w else h
        PDDocument().use { d ->
            val np = PDPage(PDRectangle(dw, dh))
            d.addPage(np)
            val form = LayerUtility(d).importPageAsForm(src, index)
            form.cosObject.removeItem(COSName.MATRIX)
            form.setBBox(PDRectangle(crop.lowerLeftX, crop.lowerLeftY, w, h))
            PDPageContentStream(d, np).use { cs ->
                cs.saveGraphicsState()
                cs.transform(displayAff(rot, crop.lowerLeftX, crop.lowerLeftY, w, h).pdf())
                cs.drawForm(form)
                cs.restoreGraphicsState()
            }
            d.save(out)
        }
        return PageInfo(dw, dh, zb)
    }

    private fun displayAff(rot: Int, llx: Float, lly: Float, w: Float, h: Float): Aff {
        val r = when (rot) {
            90 -> Aff(0f, -1f, 1f, 0f, 0f, w)
            180 -> Aff(-1f, 0f, 0f, -1f, w, h)
            270 -> Aff(0f, 1f, -1f, 0f, h, 0f)
            else -> Aff.I
        }
        return r * Aff.T(-llx, -lly)
    }

    private fun readAndStrip(page: PDPage): ZbInfo? {
        val dict = page.cosObject
        val ds = dict.getDictionaryObject(ZB_DATA) as? COSStream ?: return null
        dict.removeItem(ZB_DATA)
        val json = try { JSONObject(String(ds.createInputStream().use { it.readBytes() }, Charsets.UTF_8)) } catch (e: Exception) { return null }
        val crc = json.optLong("crc", -1L)
        var items: List<Item>? = null
        // Nur der letzte Inhaltsstrom kann der editierbare Strichstrom sein (nach dem letzten Bild).
        if (crc >= 0) {
            when (val contents = dict.getDictionaryObject(COSName.CONTENTS)) {
                is COSArray -> {
                    val last = contents.size() - 1
                    val s = if (last >= 0) contents.getObject(last) as? COSStream else null
                    if (s != null && matches(s, crc)) {
                        contents.remove(last)
                        items = parseItems(json)
                    }
                }
                is COSStream -> if (matches(contents, crc)) {
                    dict.removeItem(COSName.CONTENTS)
                    items = parseItems(json)
                }
                else -> {}
            }
        }
        return ZbInfo(
            json.optInt("scale", 0).takeIf { it > 0 },
            runCatching { MUnit.valueOf(json.optString("unit")) }.getOrNull(),
            json.optInt("dpi", 0).takeIf { it > 0 },
            items,
        )
    }

    private fun matches(s: COSStream, crc: Long): Boolean {
        val bytes = s.createInputStream().use { it.readBytes() }
        return isMarked(bytes) && crcOf(bytes) == crc
    }

    private fun parseItems(json: JSONObject): List<Item>? =
        try { Store.itemsFromJson(json.optJSONArray("items")) } catch (e: Exception) { null }

    private fun isMarked(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, min(bytes.size, 64), Charsets.ISO_8859_1).trimStart()
        return head.startsWith("/$MARK BMC")
    }

    private fun crcOf(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    class SaveData(val items: List<Item>, val pageH: Float, val scale: Int, val unit: MUnit)

    /** Schreibt Basis + Bilder + eigene Striche als Vektor-PDF. */
    fun save(base: File, data: SaveData, out: OutputStream, dpi: Int) {
        val keep = ArrayList<PDDocument>()
        try {
            PDDocument.load(base).use { pd ->
                val page = pd.getPage(0)
                val H = data.pageH
                // Elemente in ihrer Reihenfolge schreiben: Bildgruppen unmarkiert, Strichgruppen markiert.
                // Nur die letzte Strichgruppe (nach dem letzten Bild) bleibt nach dem Wiederöffnen editierbar.
                val runs = ArrayList<MutableList<Item>>()
                for (it in data.items) {
                    val img = it is ImageItem
                    val lastRun = runs.lastOrNull()
                    if (lastRun != null && (lastRun[0] is ImageItem) == img) lastRun.add(it) else runs.add(mutableListOf(it))
                }
                val json = JSONObject()
                    .put("v", 2)
                    .put("scale", data.scale)
                    .put("unit", data.unit.name)
                    .put("dpi", dpi)
                    .put("crc", -1L)
                for ((ri, run) in runs.withIndex()) {
                    PDPageContentStream(pd, page, PDPageContentStream.AppendMode.APPEND, true, false).use { cs ->
                        if (run[0] is ImageItem) {
                            for (im in run) drawImageItem(pd, cs, im as ImageItem, H, dpi, keep)
                        } else {
                            cs.beginMarkedContent(COSName.getPDFName(MARK))
                            cs.setLineCapStyle(1)
                            cs.setLineJoinStyle(1)
                            for (it in run) {
                                when (it) {
                                    is Stroke -> polyline(cs, it.pts, it.width, it.color, H)
                                    is Cover -> polyline(cs, it.pts, it.width, it.color, H)
                                    else -> {}
                                }
                            }
                            cs.endMarkedContent()
                        }
                    }
                    if (ri == runs.size - 1 && run[0] !is ImageItem) {
                        val bytes = lastContent(page)?.createInputStream()?.use { it.readBytes() }
                        if (bytes != null) {
                            json.put("crc", crcOf(bytes))
                            json.put("items", Store.itemsToJson(run))
                        }
                    }
                }
                val ds = pd.document.createCOSStream()
                ds.createOutputStream().use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                page.cosObject.setItem(ZB_DATA, ds)
                pd.save(out)
            }
        } finally {
            keep.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    private fun lastContent(page: PDPage): COSStream? =
        when (val c = page.cosObject.getDictionaryObject(COSName.CONTENTS)) {
            is COSArray -> if (c.size() > 0) c.getObject(c.size() - 1) as? COSStream else null
            is COSStream -> c
            else -> null
        }

    private fun color(c: Int) = PDColor(
        floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f),
        PDDeviceRGB.INSTANCE,
    )

    private fun polyline(cs: PDPageContentStream, p: FloatArray, w: Float, c: Int, H: Float) {
        if (p.size < 2) return
        cs.setStrokingColor(color(c))
        cs.setLineWidth(w)
        cs.moveTo(p[0], H - p[1])
        if (p.size == 2) {
            cs.lineTo(p[0], H - p[1])
        } else {
            var i = 2
            while (i + 1 < p.size) { cs.lineTo(p[i], H - p[i + 1]); i += 2 }
        }
        cs.stroke()
    }

    private fun drawImageItem(pd: PDDocument, cs: PDPageContentStream, im: ImageItem, H: Float, dpi: Int, keep: MutableList<PDDocument>) {
        val cw = im.w
        val ch = im.h
        val cx = im.cx
        val cy = H - im.cy
        val place = Aff.T(cx, cy) * Aff.R(Math.toRadians(-im.angle.toDouble()).toFloat()) * Aff.T(-cw / 2f, -ch / 2f)
        cs.saveGraphicsState()
        if (im.isPdf) {
            val src = PDDocument.load(File(im.file))
            keep.add(src)
            val form = LayerUtility(pd).importPageAsForm(src, 0)
            form.cosObject.removeItem(COSName.MATRIX)
            form.setBBox(PDRectangle(0f, 0f, im.srcW, im.srcH))
            cs.transform((place * Aff.S(cw / im.srcW, ch / im.srcH)).pdf())
            cs.drawForm(form)
        } else {
            val img = makeImage(pd, im, cw, dpi)
            cs.transform((place * Aff.S(cw, ch)).pdf())
            cs.drawImage(img, 0f, 0f, 1f, 1f)
        }
        cs.restoreGraphicsState()
    }

    /** Bild einbetten, bei Bedarf auf die gewählte DPI herunterrechnen. */
    private fun makeImage(pd: PDDocument, im: ImageItem, cwPt: Float, dpi: Int): PDImageXObject {
        val file = File(im.file)
        val eff = im.srcW / (cwPt / 72f)
        if (eff <= dpi * 1.02f) return loadImage(pd, file, im.isJpeg)
        val f = dpi / eff
        val opts = BitmapFactory.Options()
        var sample = 1
        while (sample * 2 * f <= 1f) sample *= 2
        opts.inSampleSize = sample
        val b = BitmapFactory.decodeFile(file.path, opts)
        val tw = max(1, (im.srcW * f).roundToInt())
        val th = max(1, (im.srcH * f).roundToInt())
        val sb = Bitmap.createScaledBitmap(b, tw, th, true)
        if (sb !== b) b.recycle()
        try {
            return if (im.isJpeg) JPEGFactory.createFromImage(pd, sb, 0.9f) else LosslessFactory.createFromImage(pd, sb)
        } finally {
            sb.recycle()
        }
    }
}
