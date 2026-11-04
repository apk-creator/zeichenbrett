package app.zeichenbrett

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import kotlin.math.max
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
        private val PEN_ANCHORS = floatArrayOf(0.10f, 0.18f, 0.25f, 0.35f, 0.50f, 1f, 2.5f, 5f, 10f)
        private val ERASER_ANCHORS = floatArrayOf(1f, 10f, 25f, 50f, 100f)

        // Werkzeugpalette
        private const val T_MEASURE = 0
        private const val T_RULER = 1
        private const val T_PEN0 = 2
        private const val T_ERASER = 7
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
    private lateinit var bUndo: Btn
    private lateinit var bRedo: Btn
    private lateinit var bZoom: Btn
    private lateinit var bRot: Btn
    private lateinit var bScale: Btn
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

    private var busyPanel: Panel? = null
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
        root.post {
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
        val top = panels.lastOrNull()
        when {
            top != null -> if (top.cancelable) { closePanel(top); top.onCancel?.invoke() }
            popup != null -> dismissPopup()
            else -> moveTaskToBack(true)
        }
    }

    private fun startup() {
        val work = File(filesDir, "work").apply { mkdirs() }
        val dirs = work.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val rec = dirs.filter { File(it, "state.json").exists() }
        dirs.filter { !File(it, "state.json").exists() }.forEach { it.deleteRecursively() }
        ui.postDelayed(autosave, 180_000)
        if (rec.isNotEmpty()) {
            showPanel(
                "Wiederherstellen?",
                listOf(bodyText("${rec.size} Dokument(e) mit ungespeicherten Änderungen gefunden.")),
                listOf(
                    "Verwerfen" to { rec.forEach { it.deleteRecursively() }; afterStartup() },
                    "Wiederherstellen" to { restore(rec) },
                ),
            )
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

    // ================================================================== Panels (Dialoge im Palettenstil)

    private class Panel(val overlay: FrameLayout, val cancelable: Boolean, val onCancel: (() -> Unit)?)
    private val panels = ArrayList<Panel>()

    private fun bodyText(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Pal.BLACK)
        setPadding(0, dpi(4f), 0, dpi(4f))
    }

    private fun caption(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Pal.GREY); setPadding(0, dpi(14f), 0, dpi(4f))
    }

    private fun showPanel(
        title: String?,
        content: List<View>,
        buttons: List<Pair<String, () -> Unit>>,
        cancelable: Boolean = false,
        onCancel: (() -> Unit)? = null,
        widthPx: Int = dpi(460f),
    ): Panel {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(70, 0, 0, 0))
            isClickable = true
            elevation = dp(20f)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(this@MainActivity, Pal.WHITE, 22f)
            elevation = dp(8f)
            isClickable = true
            setPadding(dpi(22f), dpi(20f), dpi(22f), dpi(16f))
        }
        if (title != null) {
            box.addView(TextView(this).apply {
                text = title; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Pal.BLACK)
                setPadding(0, 0, 0, dpi(8f))
            })
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (v in content) {
            val lpv = v.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            inner.addView(v, lpv)
        }
        val rh = if (root.height > 0) root.height else resources.displayMetrics.heightPixels
        val scroll = ScrollView(this).apply { addView(inner) }
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val panel = Panel(overlay, cancelable, onCancel)
        if (buttons.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            for ((label, action) in buttons) {
                val b = Btn(this).apply {
                    text = label; textSp = 16f
                    onTap = { closePanel(panel); action() }
                }
                row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, max(btnSize, dpi(48f))).apply { marginStart = dpi(6f) })
            }
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14f) })
        }
        val rw = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        overlay.addView(box, FrameLayout.LayoutParams(min(widthPx, rw - dpi(32f)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        box.post {
            val maxH = rh - dpi(48f)
            if (box.height > maxH) box.layoutParams = FrameLayout.LayoutParams(box.width, maxH, Gravity.CENTER)
        }
        overlay.setOnClickListener { if (cancelable) { closePanel(panel); onCancel?.invoke() } }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panels.add(panel)
        return panel
    }

    private fun closePanel(p: Panel) {
        root.removeView(p.overlay)
        panels.remove(p)
    }

    /** Auswahlzeile aus Buttons; die gewählte Option ist invertiert. */
    private fun choiceRow(labels: List<String>, sel: Int, onSel: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btns = ArrayList<Btn>()
        labels.forEachIndexed { i, l ->
            val b = Btn(this)
            b.text = l; b.textSp = 16f; b.active = i == sel
            b.onTap = {
                btns.forEachIndexed { k, o -> o.active = k == i }
                onSel(i)
            }
            btns.add(b)
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(46f)).apply { marginEnd = dpi(4f) })
        }
        return row
    }

    private fun numberField(v: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(v.toString())
        textSize = 18f
        setTextColor(Pal.BLACK)
        background = outlineBg(this@MainActivity)
        setPadding(dpi(14f), dpi(8f), dpi(14f), dpi(8f))
        minWidth = dpi(140f)
        setSelectAllOnFocus(true)
    }

    private fun scaleRow(et: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply { text = "1 : "; textSize = 18f; setTextColor(Pal.BLACK) })
        addView(et, LinearLayout.LayoutParams(dpi(160f), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ================================================================== Oberfläche aufbauen

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Pal.BACKGROUND)
        canvasView = CanvasView(this, tools, this)
        root.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        docBar = palette(this, true)
        setBar = palette(this, true)
        toolBar = palette(this, false)
        imageBar = palette(this, true).apply { visibility = View.GONE }
        statusView = TextView(this).apply {
            background = roundBg(this@MainActivity, Pal.WHITE)
            elevation = dp(3f)
            setTextColor(Pal.BLACK)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpi(18f), dpi(10f), dpi(18f), dpi(10f))
            visibility = View.GONE
        }
        val m = dpi(12f)
        root.addView(docBar, lp(Gravity.TOP or Gravity.START, m, m, 0, 0))
        root.addView(setBar, lp(Gravity.TOP or Gravity.END, 0, m, m, 0))
        root.addView(toolBar, lp(Gravity.CENTER_VERTICAL or Gravity.START, m, 0, 0, 0))
        root.addView(statusView, lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 0, 0, m))
        root.addView(imageBar, lp(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dpi(96f), 0, 0))
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
        // Einstellungspalette ≈ 1/3 der Breite: 5 Symbolbuttons + 3 breite Textbuttons (je 1,6)
        val unit = ((screenW / 3f - dp(8f)) / 9.8f).roundToInt()
        btnSize = unit.coerceIn(dpi(40f), dpi(56f))

        // Werkzeugpalette: Messen, Lineal, 5 Stifte, Radierer
        toolBar.removeAllViews(); toolBtns.clear()
        fun addTool(b: Btn) {
            toolBar.addView(b, LinearLayout.LayoutParams(btnSize, btnSize))
            toolBtns.add(b)
        }
        addTool(Btn(this).apply { icon = "dim"; onTap = { toolTap(T_MEASURE) } })
        addTool(Btn(this).apply { icon = "ruler"; onTap = { toolTap(T_RULER) } })
        for (i in 0 until 5) addTool(Btn(this).apply { icon = "pen"; iconFill = Pal.PENS[i]; onTap = { toolTap(T_PEN0 + i) } })
        addTool(Btn(this).apply { icon = "eraser"; onTap = { toolTap(T_ERASER) } })

        // Einstellungspalette: Zurück, Vor, Zoom, Rotation, Maßstab, Import, Speichern, Schließen
        setBar.removeAllViews()
        val wide = (btnSize * 1.6f).roundToInt()
        bUndo = Btn(this).apply { icon = "undo"; onTap = { doUndo(true) } }
        bRedo = Btn(this).apply { icon = "redo"; onTap = { doUndo(false) } }
        bZoom = Btn(this).apply { text = "100 %"; onTap = { zoomTap() } }
        bRot = Btn(this).apply { text = "0°"; onTap = { rotTap() } }
        bScale = Btn(this).apply { text = "1:100"; onTap = { scaleDialog() } }
        bImport = Btn(this).apply { icon = "import"; onTap = { startImport() } }
        bSave = Btn(this).apply { icon = "save"; onTap = { this@MainActivity.active?.let { saveDoc(it, null) } } }
        bClose = Btn(this).apply { icon = "close"; onTap = { this@MainActivity.active?.let { closeDoc(it) } } }
        for (b in listOf(bUndo, bRedo)) setBar.addView(b, LinearLayout.LayoutParams(btnSize, btnSize))
        for (b in listOf(bZoom, bRot, bScale)) setBar.addView(b, LinearLayout.LayoutParams(wide, btnSize))
        for (b in listOf(bImport, bSave, bClose)) setBar.addView(b, LinearLayout.LayoutParams(btnSize, btnSize))

        // Bildleiste
        imageBar.removeAllViews()
        imageBar.addView(Btn(this).apply { icon = "rotate"; onTap = { canvasView.rotateSelected() } }, LinearLayout.LayoutParams(btnSize, btnSize))
        imageBar.addView(Btn(this).apply { text = "Fertig"; onTap = { fixSelection() } }, LinearLayout.LayoutParams(wide, btnSize))
    }

    private fun fmtWidth(v: Float) = if (v < 1f) String.format(Locale.US, "%.2f", v) else String.format(Locale.US, "%.1f", v)

    private fun fmtDeg(a: Float): String {
        val m = ((a % 360f) + 360f) % 360f
        return if (abs(m - m.roundToInt()) < 0.05f) "${m.roundToInt() % 360}°" else String.format(Locale.US, "%.1f°", m)
    }

    private fun refreshUi() {
        if (!::bScale.isInitialized) return
        val d = active
        for ((k, b) in toolBtns.withIndex()) {
            b.active = when (k) {
                T_MEASURE -> tools.tool == Tool.MEASURE
                T_RULER -> tools.rulerOn
                T_ERASER -> tools.tool == Tool.ERASER
                else -> tools.tool == Tool.PEN && tools.pen == k - T_PEN0
            }
            b.text = when (k) {
                T_MEASURE -> d?.unit?.label ?: "m"
                T_RULER -> null
                T_ERASER -> "${tools.eraserPct.roundToInt()} %"
                else -> fmtWidth(tools.penWidth[k - T_PEN0])
            }
        }
        onViewChanged()
        bScale.text = "1:${d?.scale ?: 100}"
        bUndo.enabledBtn = d?.canUndo == true
        bRedo.enabledBtn = d?.canRedo == true
        for (b in listOf(bZoom, bRot, bScale, bImport, bSave, bClose)) b.enabledBtn = d != null
        imageBar.visibility = if (d?.selected() != null) View.VISIBLE else View.GONE
        rebuildDocBar()
        refreshStatus()
    }

    // ================================================================== Dokumentpalette

    private fun labelText(d: PageDoc) = if (d.dirty) "• ${d.title}" else d.title

    private fun rebuildDocBar() {
        docBar.removeAllViews()
        val d = active
        if (d == null) { docBar.visibility = View.GONE; return }
        docBar.visibility = View.VISIBLE
        val gap = dpi(4f)
        val rw = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        // 4 Label + 1 Button (Dropdown bzw. „+“) bis zur Bildschirmmitte
        val avail = rw / 2 - dpi(12f) - docBar.paddingLeft - docBar.paddingRight
        val slot = ((avail - btnSize - gap * 4) / 4).coerceAtLeast(btnSize)

        val idx = docs.indexOf(d)
        val shown: List<PageDoc> = when {
            docs.size <= 4 -> docs.toList()
            idx < 4 -> docs.subList(0, 4).toList()
            else -> docs.subList(0, 3).toList() + d
        }
        for (doc in shown) {
            val b = Btn(this).apply {
                text = labelText(doc)
                multiLine = true
                active = doc === d
                onTap = { activate(doc) }
            }
            docBar.addView(b, LinearLayout.LayoutParams(slot, btnSize).apply { marginEnd = gap })
        }
        if (docs.size <= 4) {
            if (docs.size < MAX_DOCS) {
                docBar.addView(Btn(this).apply { icon = "plus"; iconScale = 1.5f; onTap = { showStart() } }, LinearLayout.LayoutParams(btnSize, btnSize))
            }
        } else {
            docBar.addView(Btn(this).apply { icon = "chevron"; onTap = { showDocDropdown() } }, LinearLayout.LayoutParams(btnSize, btnSize))
        }
    }

    private fun showDocDropdown() {
        if (popupFor == -2) { dismissPopup(); return }
        val box = palette(this, false)
        val d = active
        for (doc in docs) {
            val b = Btn(this).apply {
                text = labelText(doc)
                alignLeft = true
                active = doc === d
                onTap = { dismissPopup(); activate(doc) }
            }
            box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, btnSize))
        }
        if (docs.size < MAX_DOCS) {
            box.addView(Btn(this).apply {
                text = "Neues Dokument"; alignLeft = true; bold = true
                onTap = { dismissPopup(); showStart() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, btnSize))
        }
        val p = FrameLayout.LayoutParams(docBar.width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = docBar.left
            topMargin = docBar.bottom + dpi(8f)
        }
        showPopup(box, p, -2, withCatcher = true)
    }

    // ================================================================== Popups an den Paletten

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
        if (k == T_RULER) {
            dismissPopup()
            tools.rulerOn = !tools.rulerOn
            if (tools.rulerOn && tools.tool == Tool.MEASURE) {
                tools.tool = Tool.PEN
                canvasView.clearMeasure()
            }
            canvasView.invalidate()
            refreshUi()
            return
        }
        val isActive = when (k) {
            T_MEASURE -> tools.tool == Tool.MEASURE
            T_ERASER -> tools.tool == Tool.ERASER
            else -> tools.tool == Tool.PEN && tools.pen == k - T_PEN0
        }
        if (isActive) {
            if (popupFor == k) dismissPopup() else showToolPopup(k)
            return
        }
        dismissPopup()
        when (k) {
            T_MEASURE -> { tools.tool = Tool.MEASURE; tools.rulerOn = false }
            T_ERASER -> tools.tool = Tool.ERASER
            else -> { tools.tool = Tool.PEN; tools.pen = k - T_PEN0 }
        }
        if (k != T_MEASURE) canvasView.clearMeasure()
        canvasView.invalidate()
        refreshUi()
    }

    private fun showToolPopup(k: Int) {
        val box = palette(this, true)
        if (k == T_MEASURE) {
            val d = active ?: return
            for (u in MUnit.values()) {
                val b = Btn(this)
                b.text = u.label
                b.active = d.unit == u
                b.onTap = {
                    d.unit = u; d.dirty = true
                    canvasView.refreshMeasure(); canvasView.invalidate()
                    showToolPopup(T_MEASURE)
                    refreshUi()
                }
                box.addView(b, LinearLayout.LayoutParams(btnSize, btnSize))
            }
        } else {
            val label = Btn(this)
            val slider: SliderView
            if (k == T_ERASER) {
                label.text = "${tools.eraserPct.roundToInt()} %"
                slider = SliderView(this, ERASER_ANCHORS, tools.eraserPct) { v ->
                    tools.eraserPct = v.roundToInt().toFloat().coerceIn(1f, 100f)
                    label.text = "${tools.eraserPct.roundToInt()} %"
                    toolBtns[T_ERASER].text = label.text
                }
            } else {
                val idx = k - T_PEN0
                label.text = fmtWidth(tools.penWidth[idx]) + " pt"
                slider = SliderView(this, PEN_ANCHORS, tools.penWidth[idx]) { v ->
                    tools.penWidth[idx] = (v * 100f).roundToInt() / 100f
                    label.text = fmtWidth(tools.penWidth[idx]) + " pt"
                    toolBtns[k].text = fmtWidth(tools.penWidth[idx])
                }
            }
            box.addView(slider, LinearLayout.LayoutParams(dpi(360f), btnSize))
            box.addView(label, LinearLayout.LayoutParams((btnSize * 1.8f).roundToInt(), btnSize))
            if (k == T_ERASER) {
                val bg = Btn(this)
                bg.text = "Hintergrund"
                bg.active = tools.eraseBackground
                bg.onTap = { tools.eraseBackground = !tools.eraseBackground; bg.active = tools.eraseBackground }
                box.addView(bg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, btnSize))
            }
        }
        val btn = toolBtns[k]
        val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = toolBar.right + dpi(8f)
            topMargin = toolBar.top + btn.top + btn.height / 2 - (btnSize + toolBar.paddingTop * 2) / 2
        }
        showPopup(box, p, k, withCatcher = false)
    }

    private var zoomTapPending: Runnable? = null
    private var rotTapPending: Runnable? = null

    private fun zoomTap() {
        val pend = zoomTapPending
        if (pend != null) {
            ui.removeCallbacks(pend)
            zoomTapPending = null
            if (!tools.zoomLocked) canvasView.setZoom(1f)
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

    private fun rotTap() {
        val pend = rotTapPending
        if (pend != null) {
            ui.removeCallbacks(pend)
            rotTapPending = null
            if (!tools.rotLocked) canvasView.setPlanRotation(0f)
            return
        }
        val r = Runnable {
            rotTapPending = null
            tools.rotLocked = !tools.rotLocked
            refreshUi()
        }
        rotTapPending = r
        ui.postDelayed(r, 280)
    }

    private fun doUndo(undo: Boolean) {
        val d = active ?: return
        val ok = if (undo) d.undo() else d.redo()
        if (ok) {
            if (d.selected() == null) d.selectedImage = null
            canvasView.itemsChanged()
            refreshUi()
        }
    }

    private fun fixSelection() {
        val d = active ?: return
        val id = d.selectedImage ?: return
        d.items = d.items.map { if (it is ImageItem && it.id == id) it.copy(fixed = true) else it }
        d.selectedImage = null
        canvasView.itemsChanged()
        refreshUi()
    }

    // ================================================================== CanvasView.Host

    override fun ppi(): Float = if (ppiValue > 0f) ppiValue else 243f

    override fun onDocEdited() = refreshUi()

    override fun onViewChanged() {
        if (!::bZoom.isInitialized) return
        val d = active
        bZoom.text = "${((d?.zoom ?: 1f) * 100f).roundToInt()} %"
        bZoom.badge = if (tools.zoomLocked) "lock" else null
        bRot.text = fmtDeg(d?.rot ?: 0f)
        bRot.badge = if (tools.rotLocked) "lock" else null
    }

    override fun refreshStatus() {
        val t = when {
            tools.rulerOn -> fmtDeg(((tools.rulerAngle % 180f) + 180f) % 180f)
            tools.tool == Tool.MEASURE -> canvasView.measureText
            else -> null
        }
        statusView.text = t ?: ""
        statusView.visibility = if (t == null) View.GONE else View.VISIBLE
    }

    override fun onUndoGesture() {
        doUndo(true)
        bUndo.flash()
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
        showPanel(
            "„${d.title}“ schließen",
            listOf(bodyText("Die Änderungen sind nicht gespeichert.")),
            listOf(
                "Abbrechen" to {},
                "Ohne Speichern schließen" to { reallyClose(d) },
                "Speichern & schließen" to { saveDoc(d) { reallyClose(d) } },
            ),
            cancelable = true,
            widthPx = dpi(600f),
        )
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
        if (panels.any { it.overlay.tag == "start" }) return
        val canNew = docs.size < MAX_DOCS
        val content = ArrayList<View>()
        var panel: Panel? = null
        fun row(t: String, f: () -> Unit) = Btn(this).apply {
            text = t; textSp = 17f; alignLeft = true; enabledBtn = canNew
            onTap = { panel?.let { closePanel(it) }; f() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(52f))
        }
        content.add(row("Neues Dokument") { newBlank() })
        content.add(row("Neues Dokument aus Datei") { pickOpen() })
        val recent = Store.recent(prefs)
        if (recent.isNotEmpty()) {
            content.add(caption("Zuletzt geöffnet"))
            for (r in recent) content.add(row(r.name) { openFile(r.uri, persist = false, fromRecent = true) })
        }
        val cancelable = docs.isNotEmpty()
        val pnl = showPanel("Zeichenbrett", content, if (cancelable) listOf("Abbrechen" to {}) else emptyList(), cancelable = cancelable)
        pnl.overlay.tag = "start"
        panel = pnl
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

    private fun settingsDialog(kind: Kind, title: String, info: String?, init: DocSettings, multi: Boolean, done: (DocSettings?, Boolean) -> Unit) {
        val s = init
        val content = ArrayList<View>()
        if (info != null) content.add(bodyText(info))
        if (kind != Kind.PDF) {
            content.add(caption("Format"))
            val names = if (kind == Kind.IMAGE) A_NAMES + "Bildgröße" else A_NAMES
            content.add(choiceRow(names, s.format) { s.format = it })
            content.add(caption("Ausrichtung"))
            content.add(choiceRow(listOf("Hoch", "Quer"), if (s.landscape) 1 else 0) { s.landscape = it == 1 })
        }
        content.add(caption("Maßstab"))
        val et = numberField(s.scale)
        content.add(scaleRow(et))
        content.add(caption("Messeinheit"))
        content.add(choiceRow(MUnit.values().map { it.label }, s.unit.ordinal) { s.unit = MUnit.values()[it] })

        fun read(): DocSettings {
            s.scale = (et.text.toString().toIntOrNull() ?: s.scale).coerceIn(1, 100000)
            return s
        }
        val buttons = ArrayList<Pair<String, () -> Unit>>()
        buttons.add("Abbrechen" to { done(null, false) })
        if (multi) buttons.add("Alle Seiten anlegen" to { done(read(), true) })
        buttons.add((if (multi) "Seite anlegen" else "Anlegen") to { done(read(), false) })
        showPanel(title, content, buttons, widthPx = dpi(560f))
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
                    val dpi = Img.readDpi(tmp, isJpeg)
                    val base = name.substringBeforeLast('.')
                    ui.post { hideBusy(); askImage(base, tmp, isJpeg, pw, ph, dpi) }
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

    private fun askImage(name: String, tmp: File, isJpeg: Boolean, pw: Int, ph: Int, fileDpi: Int?) {
        val dpi = fileDpi ?: 150
        val info = "Bild: $pw × $ph Pixel · " + (if (fileDpi != null) "$fileDpi DPI" else "keine DPI-Angabe, 150 DPI angenommen")
        settingsDialog(Kind.IMAGE, name, info, DocSettings(4, pw > ph, dpi, 100, MUnit.M), false) { s, _ ->
            if (s == null) { tmp.delete(); if (docs.isEmpty()) showStart(); return@settingsDialog }
            val d = newDoc(name)
            d.scale = s.scale; d.unit = s.unit; d.dpi = dpi
            runBusy({
                val norm = Img.normalize(tmp, File(d.workDir, if (isJpeg) "src.jpg" else "src.png"), isJpeg)
                val w: Float; val h: Float
                if (s.format == 4) {
                    w = norm.w * 72f / dpi; h = norm.h * 72f / dpi
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
        showPanel(
            "Bildauflösung",
            listOf(
                bodyText("Eingebettete Bilder werden beim Speichern höchstens mit dieser Auflösung abgelegt."),
                choiceRow(DPIS.map { "$it DPI" }, sel) { sel = it },
            ),
            listOf("Abbrechen" to {}, "Speichern" to { done(DPIS[sel]) }),
            cancelable = true,
        )
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
        val et = numberField(d.scale)
        showPanel(
            "Maßstab einstellen",
            listOf(scaleRow(et)),
            listOf(
                "Abbrechen" to {},
                "OK" to {
                    val v = et.text.toString().toIntOrNull()
                    if (v != null && v > 0 && v != d.scale) {
                        d.scale = v.coerceAtMost(100000)
                        d.dirty = true
                        canvasView.refreshMeasure()
                        canvasView.invalidate()
                        refreshUi()
                    }
                },
            ),
            cancelable = true,
        )
    }

    private fun showCalibration(done: () -> Unit) {
        var ppi = 243f
        val label = TextView(this).apply { textSize = 16f; setTextColor(Pal.BLACK); gravity = Gravity.CENTER }
        val bar = CalibView(this) { ppi }
        bar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(90f))
        fun upd() { label.text = String.format(Locale.US, "%.1f ppi", ppi); bar.invalidate() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((t, step) in listOf("−5" to -5f, "−0.5" to -0.5f, "+0.5" to 0.5f, "+5" to 5f)) {
            row.addView(Btn(this).apply {
                text = t; textSp = 17f
                onTap = { ppi = (ppi + step).coerceIn(100f, 500f); upd() }
            }, LinearLayout.LayoutParams(dpi(80f), dpi(48f)))
        }
        upd()
        showPanel(
            "Display kalibrieren",
            listOf(
                bodyText("Lege ein Lineal an den Balken und passe ihn an, bis er genau 100 mm lang ist. " +
                    "Der Wert wird gespeichert und lässt sich nur über das Zurücksetzen der App-Daten ändern."),
                bar,
                label,
                row,
            ),
            listOf("Speichern" to {
                ppiValue = ppi
                prefs.edit().putFloat("ppi", ppi).apply()
                done()
            }),
            widthPx = (resources.displayMetrics.widthPixels * 0.9f).roundToInt(),
        )
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
        if (busyPanel != null) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ProgressBar(this@MainActivity).apply { indeterminateTintList = ColorStateList.valueOf(Pal.BLACK) })
            addView(TextView(this@MainActivity).apply {
                text = "Bitte warten …"; textSize = 17f; setTextColor(Pal.BLACK); setPadding(dpi(16f), 0, 0, 0)
            })
        }
        busyPanel = showPanel(null, listOf(row), emptyList(), widthPx = dpi(300f))
    }

    private fun hideBusy() {
        busyCount = (busyCount - 1).coerceAtLeast(0)
        if (busyCount == 0) {
            busyPanel?.let { closePanel(it) }
            busyPanel = null
        }
    }

    private fun errorDialog(title: String, e: Throwable, then: (() -> Unit)?) {
        showPanel(title, listOf(bodyText(e.message ?: e.javaClass.simpleName)), listOf("OK" to { then?.invoke() }))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
