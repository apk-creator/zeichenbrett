package app.zeichenbrett

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.roundToInt

/** Rendert die Basisseite eines Dokuments auf einem eigenen Thread. */
class PageRenderer(file: File) {
    private val exec = Executors.newSingleThreadExecutor()
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    @Volatile private var closed = false

    /** Nur auf dem Render-Thread aufrufen. */
    fun render(bmp: Bitmap, m: Matrix) {
        renderer.openPage(0).use { p ->
            p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    /** Nur auf dem Render-Thread aufrufen. */
    fun overview(pageW: Float, pageH: Float, maxSide: Int = 2048): Bitmap {
        val s = maxSide / max(pageW, pageH)
        val bw = max(1, (pageW * s).roundToInt())
        val bh = max(1, (pageH * s).roundToInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.setScale(bw / pageW, bh / pageH)
        render(bmp, m)
        return bmp
    }

    fun <T> submit(task: () -> T): Future<T>? =
        if (closed) null else exec.submit<T> { task() }

    fun execute(task: () -> Unit) {
        if (closed) return
        exec.execute { if (!closed) task() }
    }

    fun close() {
        if (closed) return
        closed = true
        exec.execute {
            try { renderer.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
        exec.shutdown()
    }
}
