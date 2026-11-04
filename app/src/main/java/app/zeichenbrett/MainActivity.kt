package app.zeichenbrett

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity(), CanvasView.Host {

    companion object {
        private const val REQ_OPEN = 1
        private const val REQ_IMPORT = 2
        private const val REQ_SAVE_AS = 3
        private val A_MM = arrayOf(210f to 297f, 297f to 420f, 420f to 594f, 594f to 841f)
        private val A_NAMES = listOf("A4", "A3", "A2", "A1")
        private val DPIS = intArrayOf(75, 150, 300)
    }

    private val tools = ToolState()
    private val docs = ArrayList<PageDoc>()
    private var active: PageDoc? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var canvasView: CanvasView
    private lateinit var docBar: LinearLayout
    private lateinit var toolBar: LinearLayout
    private lateinit var setBar: LinearLayout
    private lateinit var imageBar: LinearLayout
    private lateinit var statusView: TextView

    private val toolBtns = ArrayList<Btn>()
    private lateinit var bScale: Btn
    private lateinit var bZoom: Btn
    private lateinit var bRuler: Btn
    private lateinit var bUndo: Btn
    private lateinit var bRedo: Btn
    private lateinit var bImport: Btn
    private lateinit var bSave: Btn
    private lateinit var bClose: Btn

    private var popup: View? = null
    private var catcher: View? = null
    private var popupFor = -1

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var ppiValue = 0f
    private var started = false
    private var untitled = 0
    private var btnSize = 0

    private var busyDialog: AlertDialog? = null
    private var busyCount = 0

    private class PendingSave(val doc: PageDoc, val dpi: Int, val after: (() -> Unit)?)
    private var pendingSave: PendingSave? = null
    private var importTarget: PageDoc? = null

    private val autosave = object : Runnable {
        override fun run() {
            autosaveAll()
            ui.postDelayed(this, 180_000)
        }
    }

    // ================================================================== Lebenszyklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        prefs = getSharedPreferences("zb", MODE_PRIVATE)
        ppiValue = prefs.getFloat("ppi", 0f)
        buildUi()
        hideSystemBars()
        ui.post {
            if (ppiValue <= 0f) showCalibration { startup() } else startup()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        super.onPause()
        autosaveAll()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (started) handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dismissPopup()
        ui.post { buildBars(); refreshUi() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (popup != null) dismissPopup() else moveTaskToBack(true)
    }

    private fun startup() {
        val work = File(filesDir, "work").apply { mkdirs() }
        val dirs = work.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val rec = dirs.filter { File(it, "state.json").exists() }
        dirs.filter { !File(it, "state.json").exists() }.forEach { it.deleteRecursively() }
        ui.postDelayed(autosave, 180_000)
        if (rec.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Wiederherstellen?")
                .setMessage("${rec.size} Dokument(e) mit ungespeicherten Änderungen gefunden.")
                .setCancelable(false)
                .setPositiveButton("Wiederherstellen") { _, _ -> restore(rec) }
                .setNegativeButton("Verwerfen") { _, _ ->
                    rec.forEach { it.deleteRecursively() }
                    afterStartup()
                }
                .show()
        } else afterStartup()
    }

    private fun afterStartup() {
        started = true
        if (!handleIntent(intent) && docs.isEmpty()) showStart()
    }

    private fun handleIntent(i: Intent?): Boolean {
        if (i?.action == Intent.ACTION_VIEW && i.data != null) {
            val uri = i.data!!
            i.data = null
            i.action = null
            openFile(uri, persist = false)
            return true
        }
        return false
    }

    private fun restore(dirs: List<File>) {
        showBusy()
        worker.execute {
            val restored = ArrayList<PageDoc>()
            for (dir in dirs.take(MAX_DOCS)) {
                try {
                    val d = Store.docFromDir(dir)
                    d.sourceUri?.let { d.writable = d.writable && isWritable(it) }
                    setupDoc(d, computeBg = false)
                    restored.add(d)
                } catch (e: Throwable) {
                    dir.deleteRecursively()
                }
            }
            ui.post {
                hideBusy()
                restored.forEach { addDoc(it) }
                afterStartup()
            }
        }
    }

    // ================================================================== Oberfläche aufbauen

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Pal.BACKGROUND)
        canvasView = CanvasView(this, tools, this)
        root.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        docBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        setBar = palette(this, true)
        toolBar = palette(this, false)
        imageBar = palette(this, true).apply { visibility = View.GONE }
        statusView = TextView(this).apply {
            background = roundBg(this@MainActivity, Pal.WHITE)
            elevation = dp(3f)
            setTextColor(Pal.BLACK)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(8f))
            visibility = View.GONE
        }
        val m = dpi(12f)
        root.addView(docBar, lp(Gravity.TOP or Gravity.START, m, m, 0, 0))
        root.addView(setBar, lp(Gravity.TOP or Gravity.END, 0, m, m, 0))
        root.addView(toolBar, lp(Gravity.BOTTOM or Gravity.START, m, 0, 0, m))
        root.addView(statusView, lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 0, 0, m))
        root.addView(imageBar, lp(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dpi(84f), 0, 0))
        setContentView(root)
        buildBars()
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) ui.post { buildBars(); refreshUi() }
        }
        refreshUi()
    }

    private fun lp(gravity: Int, l: Int, t: Int, r: Int, b: Int) =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.gravity = gravity
            setMargins(l, t, r, b)
        }

    private fun buildBars() {
        val dm = resources.displayMetrics
        val screenW = if (root.width > 0) root.width else dm.widthPixels
        // Einstellungspalette ≈ 1/3 der Breite: 6 Symbolbuttons + 2 breite Textbuttons (je 1,6)
        val unit = ((screenW / 3f - dp(8f)) / 9.2f).roundToInt()
        btnSize = unit.coerceIn(dpi(40f), dpi(56f))

        // Werkzeugpalette
        toolBar.removeAllViews(); toolBtns.clear()
        fun addTool(b: Btn) {
            toolBar.addView(b, LinearLayout.LayoutParams(btnSize, btnSize))
            toolBtns.add(b)
        }
        addTool(Btn(this).apply { icon = "fold"; onTap = { toolTap(0) } })
        for (i in 0 until 5) addTool(Btn(this).apply { icon = "pen"; iconFill = Pal.PENS[i]; onTap = { toolTap(i + 1) } })
        addTool(Btn(this).apply { icon = "eraser"; onTap = { toolTap(6) } })

        // Einstellungspalette
        setBar.removeAllViews()
        val wide = (btnSize * 1.6f).roundToInt()
        bScale = Btn(this).apply { text = "1:100"; onTap = { scaleDialog() } }
        bZoom = Btn(this).apply { text = "100 %"; onTap = { zoomTap() } }
        bRuler = Btn(this).apply { icon = "ruler"; onTap = { toggleRuler() } }
        bUndo = Btn(this).apply { icon = "undo"; onTap = { doUndo(true) } }
        bRedo = Btn(this).apply { icon = "redo"; onTap = { doUndo(false) } }
        bImport = Btn(this).apply { icon = "import"; onTap = { startImport() } }
        bSave = Btn(this).apply { icon = "save"; onTap = { active?.let { saveDoc(it, null) } } }
        bClose = Btn(this).apply { icon = "close"; onTap = { active?.let { closeDoc(it) } } }
        setBar.addView(bScale, LinearLayout.LayoutParams(wide, btnSize))
        setBar.addView(bZoom, LinearLayout.LayoutParams(wide, btnSize))
        for (b in listOf(bRuler, bUndo, bRedo, bImport, bSave, bClose)) setBar.addView(b, LinearLayout.LayoutParams(btnSize, btnSize))

        // Bildleiste
        imageBar.removeAllViews()
        imageBar.addView(Btn(this).apply { icon = "rotate"; onTap = { canvasView.rotateSelected() } }, LinearLayout.LayoutParams(btnSize, btnSize))
        imageBar.addView(Btn(this).apply { text = "Fertig"; textScale = 0.28f; onTap = { fixSelection() } }, LinearLayout.LayoutParams(wide, btnSize))
    }

    private fun fmtWidth(v: Float) = if (v < 1f) String.format(Locale.GERMANY, "%.2f", v) else String.format(Locale.GERMANY, "%.1f", v)

    private fun refreshUi() {
        if (!::bScale.isInitialized) return
        val d = active
        // Werkzeuge
        for ((k, b) in toolBtns.withIndex()) {
            b.active = when (k) {
                0 -> tools.tool == Tool.MEASURE
                6 -> tools.tool == Tool.ERASER
                else -> tools.tool == Tool.PEN && tools.pen == k - 1
            }
            b.text = when (k) {
                0 -> d?.unit?.label ?: "m"
                6 -> "${tools.eraserPct.roundToInt()} %"
                else -> fmtWidth(tools.penWidth[k - 1])
            }
        }
        bScale.text = "1:${d?.scale ?: 100}"
        val z = ((d?.zoom ?: 1f) * 100f).roundToInt()
        bZoom.text = "$z %"
        bZoom.badge = if (tools.zoomLocked) "lock" else null
        bRuler.active = tools.rulerOn
        bUndo.enabledBtn = d?.canUndo == true
        bRedo.enabledBtn = d?.canRedo == true
        for (b in listOf(bScale, bZoom, bImport, bSave, bClose)) b.enabledBtn = d != null
        imageBar.visibility = if (d?.selected() != null) View.VISIBLE else View.GONE
        rebuildDocBar()
        refreshStatus()
    }

    // ================================================================== Dokumentpalette

    private fun shortTitle(d: PageDoc): String {
        val t = if (d.title.length > 15) d.title.take(15) + "…" else d.title
        return if (d.dirty) "• $t" else t
    }

    private fun docLabel(d: PageDoc?, txt: String, isActive: Boolean, grey: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = txt
            textSize = 15f
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(dpi(14f), 0, dpi(14f), 0)
            background = roundBg(this@MainActivity, if (isActive) Pal.BLACK else Pal.WHITE, 8f)
            setTextColor(if (isActive) Pal.WHITE else if (grey) Pal.GREY else Pal.BLACK)
            elevation = dp(3f)
            setOnClickListener { onClick() }
            tag = d
        }

    private fun rebuildDocBar() {
        docBar.removeAllViews()
        val d = active ?: return
        val gap = dpi(6f)
        val h = (btnSize * 0.82f).roundToInt()
        val avail = root.width - setBar.width - dpi(12f) * 3 - gap
        val order = listOf(d) + docs.filter { it !== d }
        val labels = order.map { doc ->
            docLabel(doc, shortTitle(doc), doc === d, true) { activate(doc) }
        }
        labels.forEach { it.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)) }
        val plusW = h
        val canAdd = docs.size < MAX_DOCS
        val total = labels.sumOf { it.measuredWidth + gap } + (if (canAdd) plusW + gap else 0)
        fun add(v: View, w: Int) {
            docBar.addView(v, LinearLayout.LayoutParams(w, h).apply { marginEnd = gap })
        }
        if (avail <= 0 || total <= avail) {
            labels.forEach { add(it, ViewGroup.LayoutParams.WRAP_CONTENT) }
            if (canAdd) add(docLabel(null, "+", false, true) { showStart() }, plusW)
        } else {
            var used = 0
            for ((i, l) in labels.withIndex()) {
                val w = l.measuredWidth + gap
                if (i > 0 && used + w + plusW > avail) break
                add(l, ViewGroup.LayoutParams.WRAP_CONTENT)
                used += w
            }
            val arrow = FrameLayout(this).apply {
                background = roundBg(this@MainActivity, Pal.WHITE, 8f)
                elevation = dp(3f)
                addView(Btn(this@MainActivity).apply { icon = "chevron"; onTap = { showDocDropdown() } },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            add(arrow, plusW)
        }
    }

    private fun showDocDropdown() {
        val box = palette(this, false)
        val d = active
        val order = listOfNotNull(d) + docs.filter { it !== d }
        for (doc in order) {
            val tv = TextView(this).apply {
                text = (if (doc.dirty) "• " else "") + doc.title
                textSize = 16f
                setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
                background = roundBg(this@MainActivity, if (doc === d) Pal.BLACK else Pal.WHITE, 7f)
                setTextColor(if (doc === d) Pal.WHITE else Pal.BLACK)
                setOnClickListener { dismissPopup(); activate(doc) }
            }
            box.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (docs.size < MAX_DOCS) {
            val nb = TextView(this).apply {
                text = "Neues Dokument"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(12f))
                setTextColor(Pal.BLACK)
                setOnClickListener { dismissPopup(); showStart() }
            }
            box.addView(nb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val p = FrameLayout.LayoutParams(root.width / 3, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dpi(12f) + btnSize + dpi(8f)
        }
        showPopup(box, p, -2, withCatcher = true)
    }

    // ================================================================== Popups

    private fun showPopup(v: View, p: FrameLayout.LayoutParams, key: Int, withCatcher: Boolean) {
        dismissPopup()
        if (withCatcher) {
            val c = View(this)
            c.setOnClickListener { dismissPopup() }
            root.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            catcher = c
        }
        root.addView(v, p)
        popup = v
        popupFor = key
    }

    private fun dismissPopup() {
        popup?.let { root.removeView(it) }
        catcher?.let { root.removeView(it) }
        popup = null; catcher = null; popupFor = -1
    }

    // ================================================================== Werkzeuge

    private fun toolTap(k: Int) {
        val isActive = when (k) {
            0 -> tools.tool == Tool.MEASURE
            6 -> tools.tool == Tool.ERASER
            else -> tools.tool == Tool.PEN && tools.pen == k - 1
        }
        if (isActive) {
            if (popupFor == k) dismissPopup() else showToolPopup(k)
            return
        }
        dismissPopup()
        when (k) {
            0 -> { tools.tool = Tool.MEASURE; tools.rulerOn = false }
            6 -> tools.tool = Tool.ERASER
            else -> { tools.tool = Tool.PEN; tools.pen = k - 1 }
        }
        if (k != 0) canvasView.clearMeasure()
        canvasView.invalidate()
        refreshUi()
    }

    private fun showToolPopup(k: Int) {
        val box = palette(this, true)
        if (k == 0) {
            val d = active ?: return
            for (u in MUnit.values()) {
                val b = Btn(this)
                b.text = u.label
                b.active = d.unit == u
                b.onTap = {
                    d.unit = u; d.dirty = true
                    canvasView.refreshMeasure(); canvasView.invalidate()
                    showToolPopup(0)
                    refreshUi()
                }
                box.addView(b, LinearLayout.LayoutParams(btnSize, btnSize))
            }
        } else {
            val label = TextView(this).apply {
                textSize = 16f; setTextColor(Pal.BLACK); gravity = Gravity.CENTER
                minWidth = dpi(64f)
            }
            val slider: SliderView
            if (k == 6) {
                label.text = "${tools.eraserPct.roundToInt()} %"
                slider = SliderView(this, 1f, 100f, floatArrayOf(10f, 25f, 50f), tools.eraserPct) { v ->
                    tools.eraserPct = v.roundToInt().toFloat().coerceIn(1f, 100f)
                    label.text = "${tools.eraserPct.roundToInt()} %"
                    toolBtns[6].text = label.text.toString()
                }
            } else {
                val idx = k - 1
                label.text = fmtWidth(tools.penWidth[idx]) + " pt"
                slider = SliderView(this, 0.1f, 10f, floatArrayOf(0.10f, 0.18f, 0.25f, 0.35f, 0.50f, 1f, 5f), tools.penWidth[idx]) { v ->
                    tools.penWidth[idx] = (v * 100f).roundToInt() / 100f
                    label.text = fmtWidth(tools.penWidth[idx]) + " pt"
                    toolBtns[k].text = fmtWidth(tools.penWidth[idx])
                }
            }
            box.addView(slider, LinearLayout.LayoutParams(dpi(340f), btnSize))
            box.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, btnSize))
        }
        val btn = toolBtns[k]
        val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = toolBar.right + dpi(8f)
            topMargin = toolBar.top + toolBar.paddingTop + btn.top + btn.height / 2 - (btnSize + dpi(8f)) / 2
        }
        showPopup(box, p, k, withCatcher = false)
    }

    private fun toggleRuler() {
        tools.rulerOn = !tools.rulerOn
        if (tools.rulerOn && tools.tool == Tool.MEASURE) {
            tools.tool = Tool.PEN
            canvasView.clearMeasure()
        }
        canvasView.invalidate()
        refreshUi()
    }

    private var zoomTapPending: Runnable? = null

    private fun zoomTap() {
        val pend = zoomTapPending
        if (pend != null) {
            ui.removeCallbacks(pend)
            zoomTapPending = null
            canvasView.setZoom(1f)
            return
        }
        val r = Runnable {
            zoomTapPending = null
            tools.zoomLocked = !tools.zoomLocked
            refreshUi()
        }
        zoomTapPending = r
        ui.postDelayed(r, 280)
    }

    private fun doUndo(undo: Boolean) {
        val d = active ?: return
        val ok = if (undo) d.undo() else d.redo()
        if (ok) {
            if (d.selected() == null) d.selectedImage = null
            canvasView.invalidate()
            refreshUi()
        }
    }

    private fun fixSelection() {
        val d = active ?: return
        val id = d.selectedImage ?: return
        d.items = d.items.map { if (it is ImageItem && it.id == id) it.copy(fixed = true) else it }
        d.selectedImage = null
        canvasView.invalidate()
        refreshUi()
    }

    private fun fmtAngle(a: Float): String {
        val m = ((a % 180f) + 180f) % 180f
        return if (abs(m - m.roundToInt()) < 0.05f) "${m.roundToInt() % 180}°" else String.format(Locale.GERMANY, "%.1f°", m)
    }

    // ================================================================== CanvasView.Host

    override fun ppi(): Float = if (ppiValue > 0f) ppiValue else 243f

    override fun onDocEdited() = refreshUi()

    override fun onViewChanged() {
        if (::bZoom.isInitialized) bZoom.text = "${((active?.zoom ?: 1f) * 100f).roundToInt()} %"
    }

    override fun refreshStatus() {
        val t = when {
            tools.rulerOn -> fmtAngle(tools.rulerAngle)
            tools.tool == Tool.MEASURE -> canvasView.measureText
            else -> null
        }
        statusView.text = t ?: ""
        statusView.visibility = if (t == null) View.GONE else View.VISIBLE
    }

    override fun onUndoGesture(undo: Boolean) {
        doUndo(undo)
        (if (undo) bUndo else bRedo).flash()
    }

    override fun onPenDown() {
        if (popup != null && catcher == null) dismissPopup()
    }

    // ================================================================== Dokumente verwalten

    private fun newDoc(title: String): PageDoc {
        val id = UUID.randomUUID().toString()
        val dir = File(filesDir, "work/$id").apply { mkdirs() }
        return PageDoc(id, dir).also { it.title = title }
    }

    /** Auf dem Worker-Thread: Renderer, Übersicht, Bilder laden. */
    private fun setupDoc(d: PageDoc, computeBg: Boolean) {
        val r = PageRenderer(d.baseFile)
        d.renderer = r
        d.overview = r.submit { r.overview(d.pageW, d.pageH) }?.get()
        if (computeBg) d.overview?.let { d.bgColor = Img.brightest(it) }
        for (it in d.items) if (it is ImageItem) d.bitmaps[it.id] = Img.display(it)
    }

    private fun addDoc(d: PageDoc) {
        if (docs.size >= MAX_DOCS) {
            discard(d)
            toast("Maximal $MAX_DOCS Dokumente geöffnet.")
            return
        }
        docs.add(d)
        activate(d)
    }

    private fun activate(d: PageDoc) {
        dismissPopup()
        active = d
        canvasView.doc = d
        refreshUi()
    }

    private fun discard(d: PageDoc) {
        d.renderer?.close()
        d.overview?.recycle()
        d.bitmaps.values.forEach { it?.recycle() }
        worker.execute { d.workDir.deleteRecursively() }
    }

    private fun closeDoc(d: PageDoc) {
        if (!d.dirty) { reallyClose(d); return }
        AlertDialog.Builder(this)
            .setTitle("„${d.title}“ schließen")
            .setMessage("Die Änderungen sind nicht gespeichert.")
            .setPositiveButton("Speichern & schließen") { _, _ -> saveDoc(d) { reallyClose(d) } }
            .setNegativeButton("Ohne Speichern schließen") { _, _ -> reallyClose(d) }
            .setNeutralButton("Abbrechen", null)
            .show()
    }

    private fun reallyClose(d: PageDoc) {
        val idx = docs.indexOf(d)
        if (idx < 0) return
        docs.removeAt(idx)
        if (active === d) {
            if (docs.isEmpty()) {
                active = null
                canvasView.doc = null
                refreshUi()
            } else activate(docs[min(idx, docs.size - 1)])
        } else refreshUi()
        discard(d)
        if (docs.isEmpty()) showStart()
    }

    private fun autosaveAll() {
        for (d in docs) {
            val f = File(d.workDir, "state.json")
            if (d.dirty) {
                val json = try { Store.docToJson(d).toString() } catch (e: Exception) { continue }
                worker.execute { try { f.writeText(json) } catch (_: Exception) {} }
            } else if (f.exists()) f.delete()
        }
    }

    // ================================================================== Startdialog

    private fun showStart() {
        if (!started) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24f), dpi(20f), dpi(24f), dpi(8f))
        }
        var dlg: AlertDialog? = null
        fun bigButton(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            textSize = 17f
            setOnClickListener { dlg?.dismiss(); f() }
        }
        box.addView(TextView(this).apply {
            text = "Zeichenbrett"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Pal.BLACK)
            setPadding(0, 0, 0, dpi(12f))
        })
        val canNew = docs.size < MAX_DOCS
        box.addView(bigButton("Neues Dokument") { newBlank() }.apply { isEnabled = canNew })
        box.addView(bigButton("Neues Dokument aus Datei") { pickOpen() }.apply { isEnabled = canNew })
        val recent = Store.recent(prefs)
        if (recent.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "Zuletzt geöffnet"; textSize = 14f; setTextColor(Pal.GREY)
                setPadding(0, dpi(16f), 0, dpi(4f))
            })
            for (r in recent) {
                box.addView(TextView(this).apply {
                    text = r.name
                    textSize = 17f
                    setTextColor(Pal.BLACK)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setPadding(0, dpi(10f), 0, dpi(10f))
                    isEnabled = canNew
                    setOnClickListener { dlg?.dismiss(); openFile(r.uri, persist = false, fromRecent = true) }
                })
            }
        }
        dlg = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setCancelable(docs.isNotEmpty())
            .create()
        dlg.show()
    }

    private fun pickOpen() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/jpeg", "image/png"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_OPEN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = if (resultCode == RESULT_OK) data?.data else null
        when (requestCode) {
            REQ_OPEN -> if (uri != null) openFile(uri, persist = true) else if (docs.isEmpty()) showStart()
            REQ_IMPORT -> { val d = importTarget; importTarget = null; if (uri != null && d != null) importInto(d, uri) }
            REQ_SAVE_AS -> {
                val ps = pendingSave
                pendingSave = null
                if (uri != null && ps != null) {
                    takePermission(uri)
                    val d = ps.doc
                    d.sourceUri = uri
                    d.writable = true
                    d.fromMulti = false
                    d.backupDone = true
                    d.title = (displayName(uri) ?: d.title).removeSuffix(".pdf").removeSuffix(".PDF")
                    Store.addRecent(prefs, uri, d.title)
                    writeTo(d, uri, ps.dpi, backup = false, after = ps.after)
                }
            }
        }
    }

    // ================================================================== Einstellungsdialog

    private enum class Kind { BLANK, PDF, IMAGE }
    private class DocSettings(var format: Int, var landscape: Boolean, var dpi: Int, var scale: Int, var unit: MUnit)

    private fun radios(labels: List<String>, sel: Int, onSel: (Int) -> Unit): RadioGroup =
        RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            labels.forEachIndexed { i, l ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = l
                    textSize = 16f
                    setPadding(0, 0, dpi(12f), 0)
                    isChecked = i == sel
                    setOnCheckedChangeListener { _, c -> if (c) onSel(i) }
                })
            }
        }

    private fun caption(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Pal.GREY); setPadding(0, dpi(12f), 0, dpi(2f))
    }

    private fun settingsDialog(kind: Kind, title: String, info: String?, init: DocSettings, multi: Boolean, done: (DocSettings?, Boolean) -> Unit) {
        val s = init
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24f), dpi(8f), dpi(24f), 0)
        }
        if (info != null) box.addView(TextView(this).apply { text = info; textSize = 15f; setTextColor(Pal.BLACK) })
        var dpiGroup: RadioGroup? = null
        if (kind != Kind.PDF) {
            box.addView(caption("Format"))
            val names = if (kind == Kind.IMAGE) A_NAMES + "Bildgröße" else A_NAMES
            box.addView(radios(names, s.format) { i ->
                s.format = i
                dpiGroup?.let { g -> for (c in 0 until g.childCount) g.getChildAt(c).isEnabled = i == 4 }
            })
            box.addView(caption("Ausrichtung"))
            box.addView(radios(listOf("Hoch", "Quer"), if (s.landscape) 1 else 0) { s.landscape = it == 1 })
        }
        if (kind == Kind.IMAGE) {
            box.addView(caption("DPI (nur bei „Bildgröße“)"))
            val g = radios(DPIS.map { "$it" }, DPIS.indexOf(s.dpi).coerceAtLeast(0)) { s.dpi = DPIS[it] }
            for (c in 0 until g.childCount) g.getChildAt(c).isEnabled = s.format == 4
            dpiGroup = g
            box.addView(g)
        }
        box.addView(caption("Maßstab"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "1 : "; textSize = 18f; setTextColor(Pal.BLACK) })
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(s.scale.toString())
            textSize = 18f
            minWidth = dpi(120f)
            setSelectAllOnFocus(true)
        }
        row.addView(et)
        box.addView(row)
        box.addView(caption("Messeinheit"))
        box.addView(radios(MUnit.values().map { it.label }, s.unit.ordinal) { s.unit = MUnit.values()[it] })

        fun read(): DocSettings {
            s.scale = (et.text.toString().toIntOrNull() ?: s.scale).coerceIn(1, 100000)
            return s
        }
        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(box) })
            .setCancelable(false)
            .setPositiveButton(if (multi) "Seite anlegen" else "Anlegen") { _, _ -> done(read(), false) }
            .setNegativeButton("Abbrechen") { _, _ -> done(null, false) }
        if (multi) b.setNeutralButton("Alle Seiten anlegen") { _, _ -> done(read(), true) }
        b.show()
    }

    // ================================================================== Neu / Öffnen

    private fun newBlank() {
        settingsDialog(Kind.BLANK, "Neues Dokument", null, DocSettings(0, false, 150, 100, MUnit.M), false) { s, _ ->
            if (s == null) { if (docs.isEmpty()) showStart(); return@settingsDialog }
            val (wmm, hmm) = A_MM[s.format]
            val w = (if (s.landscape) hmm else wmm) * PT_PER_MM
            val h = (if (s.landscape) wmm else hmm) * PT_PER_MM
            untitled++
            val d = newDoc("Unbenannt $untitled")
            d.pageW = w; d.pageH = h; d.scale = s.scale; d.unit = s.unit; d.bgColor = Pal.PAPER
            runBusy({
                PdfIO.createBlank(d.baseFile, w, h)
                setupDoc(d, computeBg = false)
            }) { addDoc(d) }
        }
    }

    private fun openFile(uri: Uri, persist: Boolean, fromRecent: Boolean = false) {
        if (docs.size >= MAX_DOCS) { toast("Maximal $MAX_DOCS Dokumente geöffnet."); return }
        if (persist) takePermission(uri)
        showBusy()
        worker.execute {
            try {
                val name = displayName(uri) ?: "Dokument"
                val mime = mimeOf(uri, name)
                val tmp = File(cacheDir, "in_" + UUID.randomUUID())
                contentResolver.openInputStream(uri)!!.use { inp -> FileOutputStream(tmp).use { inp.copyTo(it) } }
                if (mime == "application/pdf") {
                    val pd = PDDocument.load(tmp)
                    val n = pd.numberOfPages
                    val zbs = (0 until n).map { PdfIO.peekZb(pd.getPage(it)) }
                    val sizes = (0 until n).map {
                        val p = pd.getPage(it)
                        val rot = ((p.rotation % 360) + 360) % 360
                        val w = p.cropBox.width; val h = p.cropBox.height
                        if (rot == 90 || rot == 270) h to w else w to h
                    }
                    val writable = isWritable(uri)
                    val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
                    ui.post {
                        hideBusy()
                        if (persist) Store.addRecent(prefs, uri, base)
                        askPdfPage(PdfOpen(uri, base, tmp, pd, n, zbs, sizes, writable), 0)
                    }
                } else if (mime == "image/jpeg" || mime == "image/png") {
                    val isJpeg = mime == "image/jpeg"
                    val (pw, ph) = Img.bounds(tmp, isJpeg)
                    val base = name.substringBeforeLast('.')
                    ui.post { hideBusy(); askImage(base, tmp, isJpeg, pw, ph) }
                } else {
                    tmp.delete()
                    ui.post { hideBusy(); toast("Dateityp wird nicht unterstützt."); if (docs.isEmpty()) showStart() }
                }
            } catch (e: Throwable) {
                ui.post {
                    hideBusy()
                    if (fromRecent) Store.removeRecent(prefs, uri)
                    errorDialog("Datei konnte nicht geöffnet werden", e) { if (docs.isEmpty()) showStart() }
                }
            }
        }
    }

    private class PdfOpen(
        val uri: Uri, val name: String, val tmp: File, val pd: PDDocument, val n: Int,
        val zbs: List<ZbInfo?>, val sizes: List<Pair<Float, Float>>, val writable: Boolean,
    ) { var created = 0 }

    private fun mmText(p: Pair<Float, Float>) =
        "${(p.first / PT_PER_MM).roundToInt()} × ${(p.second / PT_PER_MM).roundToInt()} mm"

    private fun askPdfPage(ctx: PdfOpen, i: Int) {
        if (i >= ctx.n) { finishPdfOpen(ctx); return }
        if (docs.size + ctx.created >= MAX_DOCS) {
            toast("Maximal $MAX_DOCS Dokumente geöffnet.")
            finishPdfOpen(ctx); return
        }
        val zb = ctx.zbs[i]
        val init = DocSettings(0, false, zb?.dpi ?: 150, zb?.scale ?: 100, zb?.unit ?: MUnit.M)
        val info = (if (ctx.n > 1) "Seite ${i + 1} von ${ctx.n} · " else "") + "Format aus PDF: " + mmText(ctx.sizes[i])
        settingsDialog(Kind.PDF, ctx.name, info, init, ctx.n > 1) { s, all ->
            if (s == null) { finishPdfOpen(ctx); return@settingsDialog }
            if (all) {
                for (k in i until ctx.n) {
                    if (docs.size + ctx.created >= MAX_DOCS) { toast("Maximal $MAX_DOCS Dokumente geöffnet."); break }
                    createPdfPage(ctx, k, s)
                }
                finishPdfOpen(ctx)
            } else {
                createPdfPage(ctx, i, s)
                askPdfPage(ctx, i + 1)
            }
        }
    }

    private fun createPdfPage(ctx: PdfOpen, k: Int, s: DocSettings) {
        ctx.created++
        val title = if (ctx.n > 1) "S${k + 1} ${ctx.name}" else ctx.name
        val d = newDoc(title)
        val scale = s.scale; val unit = s.unit
        runBusy({
            val info = PdfIO.extractPage(ctx.pd, k, d.baseFile, strip = true)
            d.pageW = info.w; d.pageH = info.h
            d.items = info.zb?.items ?: emptyList()
            d.scale = scale; d.unit = unit
            d.dpi = info.zb?.dpi ?: 150
            d.sourceUri = ctx.uri
            d.fromMulti = ctx.n > 1
            d.writable = ctx.writable && ctx.n == 1
            setupDoc(d, computeBg = true)
        }) { addDoc(d) }
    }

    private fun finishPdfOpen(ctx: PdfOpen) {
        worker.execute {
            try { ctx.pd.close() } catch (_: Exception) {}
            ctx.tmp.delete()
            ui.post { if (docs.isEmpty() && ctx.created == 0) showStart() }
        }
    }

    private fun askImage(name: String, tmp: File, isJpeg: Boolean, pw: Int, ph: Int) {
        val info = "Bild: $pw × $ph Pixel"
        settingsDialog(Kind.IMAGE, name, info, DocSettings(4, pw > ph, 150, 100, MUnit.M), false) { s, _ ->
            if (s == null) { tmp.delete(); if (docs.isEmpty()) showStart(); return@settingsDialog }
            val d = newDoc(name)
            d.scale = s.scale; d.unit = s.unit; d.dpi = s.dpi
            runBusy({
                val norm = Img.normalize(tmp, File(d.workDir, if (isJpeg) "src.jpg" else "src.png"), isJpeg)
                val w: Float; val h: Float
                if (s.format == 4) {
                    w = norm.w * 72f / s.dpi; h = norm.h * 72f / s.dpi
                } else {
                    val (wmm, hmm) = A_MM[s.format]
                    w = (if (s.landscape) hmm else wmm) * PT_PER_MM
                    h = (if (s.landscape) wmm else hmm) * PT_PER_MM
                }
                d.pageW = w; d.pageH = h
                PdfIO.createFromImage(d.baseFile, norm.file, norm.isJpeg, norm.w, norm.h, w, h)
                Img.decodeSampled(norm.file, 800)?.let { b -> d.bgColor = Img.brightest(b); b.recycle() }
                setupDoc(d, computeBg = false)
            }) { addDoc(d) }
        }
    }

    // ================================================================== Import

    private fun startImport() {
        val d = active ?: return
        importTarget = d
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/jpeg", "image/png"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun importInto(d: PageDoc, uri: Uri) {
        showBusy()
        worker.execute {
            try {
                val name = displayName(uri) ?: ""
                val mime = mimeOf(uri, name)
                val id = UUID.randomUUID().toString()
                val tmp = File(d.workDir, "imp_$id.src")
                contentResolver.openInputStream(uri)!!.use { inp -> FileOutputStream(tmp).use { inp.copyTo(it) } }
                val item: ImageItem = when (mime) {
                    "application/pdf" -> {
                        val out = File(d.workDir, "imp_$id.pdf")
                        val info = PDDocument.load(tmp).use { PdfIO.extractPage(it, 0, out, strip = false) }
                        tmp.delete()
                        ImageItem(id, true, false, out.path, info.w, info.h, 0f, 0f, 1f, 1f, 0, false)
                    }
                    "image/jpeg", "image/png" -> {
                        val isJpeg = mime == "image/jpeg"
                        val norm = Img.normalize(tmp, File(d.workDir, "imp_$id." + if (isJpeg) "jpg" else "png"), isJpeg)
                        ImageItem(id, false, norm.isJpeg, norm.file.path, norm.w.toFloat(), norm.h.toFloat(), 0f, 0f, 1f, 1f, 0, false)
                    }
                    else -> { tmp.delete(); throw IllegalArgumentException("Dateityp wird nicht unterstützt.") }
                }
                val sc = min(d.pageW * 0.5f / item.srcW, d.pageH * 0.5f / item.srcH)
                val w = item.srcW * sc; val h = item.srcH * sc
                val placed = item.copy(x = (d.pageW - w) / 2f, y = (d.pageH - h) / 2f, w = w, h = h)
                val bmp = Img.display(placed)
                ui.post {
                    hideBusy()
                    d.bitmaps[id] = bmp
                    d.pushUndo()
                    d.items = d.items + placed
                    d.selectedImage = id
                    d.dirty = true
                    canvasView.invalidate()
                    refreshUi()
                }
            } catch (e: Throwable) {
                ui.post { hideBusy(); errorDialog("Import fehlgeschlagen", e, null) }
            }
        }
    }

    // ================================================================== Speichern

    private fun saveDoc(d: PageDoc, after: (() -> Unit)?) {
        if (d.selectedImage != null) {
            if (active === d) fixSelection() else d.selectedImage = null
        }
        if (d.items.any { it is ImageItem }) {
            dpiDialog(d.dpi) { dpi -> d.dpi = dpi; saveDoc2(d, dpi, after) }
        } else saveDoc2(d, d.dpi, after)
    }

    private fun dpiDialog(current: Int, done: (Int) -> Unit) {
        var sel = DPIS.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Bildauflösung")
            .setSingleChoiceItems(DPIS.map { "$it DPI" }.toTypedArray(), sel) { _, w -> sel = w }
            .setPositiveButton("Speichern") { _, _ -> done(DPIS[sel]) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun saveDoc2(d: PageDoc, dpi: Int, after: (() -> Unit)?) {
        val uri = d.sourceUri
        if (uri == null || !d.writable || d.fromMulti) {
            pendingSave = PendingSave(d, dpi, after)
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
                putExtra(Intent.EXTRA_TITLE, d.title + ".pdf")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SAVE_AS)
        } else {
            writeTo(d, uri, dpi, backup = !d.backupDone, after = after)
        }
    }

    private fun writeTo(d: PageDoc, uri: Uri, dpi: Int, backup: Boolean, after: (() -> Unit)?) {
        val data = PdfIO.SaveData(d.items, d.pageH, d.scale, d.unit)
        val snapshot = d.items
        showBusy()
        worker.execute {
            try {
                if (backup) {
                    val dir = File(filesDir, "backups").apply { mkdirs() }
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
                    val safe = d.title.replace(Regex("[^A-Za-z0-9äöüÄÖÜß _-]"), "_")
                    contentResolver.openInputStream(uri)?.use { inp ->
                        FileOutputStream(File(dir, "${safe}_$stamp.pdf")).use { inp.copyTo(it) }
                    }
                }
                val bytes = ByteArrayOutputStream()
                PdfIO.save(d.baseFile, data, bytes, dpi)
                val os = try { contentResolver.openOutputStream(uri, "wt") } catch (e: Exception) { contentResolver.openOutputStream(uri, "w") }
                os!!.use { it.write(bytes.toByteArray()) }
                ui.post {
                    hideBusy()
                    d.backupDone = true
                    if (d.items === snapshot) {
                        d.dirty = false
                        File(d.workDir, "state.json").delete()
                    }
                    refreshUi()
                    toast("Gespeichert")
                    after?.invoke()
                }
            } catch (e: Throwable) {
                ui.post { hideBusy(); errorDialog("Speichern fehlgeschlagen", e, null) }
            }
        }
    }

    // ================================================================== Maßstab / Kalibrierung

    private fun scaleDialog() {
        val d = active ?: return
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(d.scale.toString())
            textSize = 20f
            setSelectAllOnFocus(true)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(24f), dpi(8f), dpi(24f), 0)
            addView(TextView(this@MainActivity).apply { text = "1 : "; textSize = 20f; setTextColor(Pal.BLACK) })
            addView(et, LinearLayout.LayoutParams(dpi(160f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Maßstab einstellen")
            .setView(row)
            .setPositiveButton("OK") { _, _ ->
                val v = et.text.toString().toIntOrNull()
                if (v != null && v > 0 && v != d.scale) {
                    d.scale = v.coerceAtMost(100000)
                    d.dirty = true
                    canvasView.refreshMeasure()
                    canvasView.invalidate()
                    refreshUi()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showCalibration(done: () -> Unit) {
        var ppi = 243f
        val label = TextView(this).apply { textSize = 16f; setTextColor(Pal.BLACK); gravity = Gravity.CENTER }
        val bar = CalibView(this) { ppi }
        fun upd() { label.text = String.format(Locale.GERMANY, "%.1f ppi", ppi); bar.invalidate() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((t, step) in listOf("−5" to -5f, "−0,5" to -0.5f, "+0,5" to 0.5f, "+5" to 5f)) {
            row.addView(Button(this).apply {
                text = t; isAllCaps = false
                setOnClickListener { ppi = (ppi + step).coerceIn(100f, 500f); upd() }
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(8f), dpi(16f), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Lege ein Lineal an den Balken und passe ihn an, bis er genau 100 mm lang ist. " +
                    "Der Wert wird gespeichert und lässt sich nur über das Zurücksetzen der App-Daten ändern."
                textSize = 15f; setTextColor(Pal.BLACK)
            })
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(90f)))
            addView(label)
            addView(row)
        }
        upd()
        val dlg = AlertDialog.Builder(this)
            .setTitle("Display kalibrieren")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Speichern") { _, _ ->
                ppiValue = ppi
                prefs.edit().putFloat("ppi", ppi).apply()
                done()
            }
            .create()
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).roundToInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // ================================================================== Hilfsfunktionen

    private fun takePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
    }

    private fun isWritable(uri: Uri): Boolean = try {
        val granted = checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        var flags = 0
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            if (it.moveToFirst()) flags = it.getInt(0)
        }
        granted && (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
    } catch (e: Exception) { false }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment
    } catch (e: Exception) { uri.lastPathSegment }

    private fun mimeOf(uri: Uri, name: String): String {
        val t = try { contentResolver.getType(uri) } catch (e: Exception) { null }
        if (t == "application/pdf" || t == "image/jpeg" || t == "image/png") return t
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".pdf") -> "application/pdf"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".png") -> "image/png"
            else -> t ?: ""
        }
    }

    private fun runBusy(work: () -> Unit, done: () -> Unit) {
        showBusy()
        worker.execute {
            try {
                work()
                ui.post { hideBusy(); done() }
            } catch (e: Throwable) {
                ui.post { hideBusy(); errorDialog("Fehler", e) { if (docs.isEmpty()) showStart() } }
            }
        }
    }

    private fun showBusy() {
        busyCount++
        if (busyDialog != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(24f), dpi(24f), dpi(24f), dpi(24f))
            addView(ProgressBar(this@MainActivity))
            addView(TextView(this@MainActivity).apply {
                text = "Bitte warten …"; textSize = 17f; setTextColor(Pal.BLACK); setPadding(dpi(16f), 0, 0, 0)
            })
        }
        busyDialog = AlertDialog.Builder(this).setView(box).setCancelable(false).create().also { it.show() }
    }

    private fun hideBusy() {
        busyCount = (busyCount - 1).coerceAtLeast(0)
        if (busyCount == 0) {
            busyDialog?.dismiss()
            busyDialog = null
        }
    }

    private fun errorDialog(title: String, e: Throwable, then: (() -> Unit)?) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(e.message ?: e.javaClass.simpleName)
            .setPositiveButton("OK") { _, _ -> then?.invoke() }
            .setCancelable(false)
            .show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
