package app.zeichenbrett

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object Img {
    class Norm(val file: File, val isJpeg: Boolean, val w: Int, val h: Int)

    private fun exifDegrees(f: File): Int = try {
        when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) { 0 }

    /** Pixelmaße unter Berücksichtigung der EXIF-Drehung. */
    fun bounds(f: File, isJpeg: Boolean): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        val deg = if (isJpeg) exifDegrees(f) else 0
        return if (deg == 90 || deg == 270) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }

    /** Kopiert das Bild nach [out] und dreht es gemäß EXIF aufrecht. */
    fun normalize(src: File, out: File, isJpeg: Boolean): Norm {
        val deg = if (isJpeg) exifDegrees(src) else 0
        if (deg == 0) {
            if (!src.renameTo(out)) { src.copyTo(out, true); src.delete() }
            val (w, h) = bounds(out, false)
            return Norm(out, isJpeg, w, h)
        }
        val b = BitmapFactory.decodeFile(src.path)
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        FileOutputStream(out).use { r.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val res = Norm(out, true, r.width, r.height)
        r.recycle()
        src.delete()
        return res
    }

    /** Anzeige-Bitmap (max. [maxSide] Pixel) für ein importiertes Element. */
    fun display(it: ImageItem, maxSide: Int = 2048): Bitmap? = try {
        if (it.isPdf) {
            ParcelFileDescriptor.open(File(it.file), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    r.openPage(0).use { p ->
                        val s = maxSide / max(p.width, p.height).toFloat()
                        val bw = max(1, (p.width * s).roundToInt())
                        val bh = max(1, (p.height * s).roundToInt())
                        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.TRANSPARENT)
                        val m = Matrix().apply { setScale(bw / p.width.toFloat(), bh / p.height.toFloat()) }
                        p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } else decodeSampled(File(it.file), maxSide)
    } catch (e: Throwable) { null }

    fun decodeSampled(f: File, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Hellster Bildpunkt (transparente Bereiche zählen als Weiß). */
    fun brightest(b: Bitmap): Int {
        val w = b.width; val h = b.height
        val row = IntArray(w)
        val sy = max(1, h / 500); val sx = max(1, w / 500)
        var best = -1; var br = 255; var bg = 255; var bb = 255
        var y = 0
        while (y < h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                val a = Color.alpha(c) / 255f
                val r = (Color.red(c) * a + 255 * (1 - a)).roundToInt()
                val g = (Color.green(c) * a + 255 * (1 - a)).roundToInt()
                val bl = (Color.blue(c) * a + 255 * (1 - a)).roundToInt()
                val lum = r * 299 + g * 587 + bl * 114
                if (lum > best) { best = lum; br = r; bg = g; bb = bl }
                x += sx
            }
            y += sy
        }
        return Color.rgb(br, bg, bb)
    }
}
