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
import android.view.MotionEvent
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
        private val A_MM = arrayOf(105f to 148f, 148f to 210f, 210f to 297f, 297f to 420f, 420f to 594f, 594f to 841f, 841f to 1189f)
        private val A_NAMES = listOf("A6", "A5", "A4", "A3", "A2", "A1", "A0")
        private const val F_A4 = 2
        private const val F_IMAGE = 7
        private val DPIS = intArrayOf(75, 150, 300)
        private val PEN_ANCHORS = floatArrayOf(0.1f, 0.3f, 0.5f, 1f, 2.5f, 5f, 10f)
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
    private lateinit var stage: FrameLayout
    private lateinit var canvasView: CanvasView
    private lateinit var docBar: LinearLayout
    private lateinit var toolBar: LinearLayout
    private lateinit var setBar: LinearLayout
    private lateinit var imageBar: LinearLayout

    private val toolBtns = ArrayList<Btn>()
    private lateinit var bUndo: Btn
    private lateinit var bRedo: Btn
    private lateinit var bZoom: Btn
    private lateinit var bRot: Btn
    private lateinit var bScale: Btn
    private lateinit var bImport: Btn
    private lateinit var bBg: Btn
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
            active?.let { autosaveDoc(it) }
            ui.postDelayed(this, 60_000)
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
        ui.postDelayed(autosave, 60_000)
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
            dismissPopup()
            for (pn in panels.toList()) if (pn !== busyPanel) closePanel(pn)
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

    // Einheitliche Dialogbreiten: Standard / mehrstufige Abläufe / schmale Eingaben
    private val W_STD get() = dpi(460f)
    private val W_FLOW get() = dpi(640f)
    private val W_INPUT get() = dpi(420f)
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
        widthPx: Int = W_STD,
        opaque: Boolean = false,
        atTop: Boolean = false,
    ): Panel {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(if (opaque) Pal.BACKGROUND else Color.argb(40, 0, 0, 0))
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
        // Enter/OK der Tastatur löst die Hauptschaltfläche (rechts) aus
        buttons.lastOrNull()?.let { (_, action) ->
            fun hook(v: View) {
                if (v is EditText) {
                    v.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    v.isSingleLine = true
                    v.setOnEditorActionListener { _, id, ev ->
                        val enter = ev != null && ev.keyCode == android.view.KeyEvent.KEYCODE_ENTER && ev.action == android.view.KeyEvent.ACTION_DOWN
                        if (id == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || enter) {
                            if (panel.overlay.parent != null) { closePanel(panel); action() }
                            true
                        } else false
                    }
                } else if (v is ViewGroup) for (i in 0 until v.childCount) hook(v.getChildAt(i))
            }
            hook(inner)
        }
        val rw = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        overlay.addView(box, FrameLayout.LayoutParams(min(widthPx, rw - dpi(32f)), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            // Alle Dialoge mit gleicher Oberkante; die Bildschirmtastatur verschiebt nichts
            topMargin = (rh * 0.15f).roundToInt()
        })
        box.post {
            val top = (rh * 0.15f).roundToInt()
            val maxH = rh - top - dpi(24f)
            if (box.height > maxH) box.layoutParams = FrameLayout.LayoutParams(box.width, maxH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = top }
        }
        overlay.setOnClickListener { if (cancelable) { closePanel(panel); onCancel?.invoke() } }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panels.add(panel)
        updateBlur()
        return panel
    }

    private fun closePanel(p: Panel) {
        root.removeView(p.overlay)
        panels.remove(p)
        updateBlur()
    }

    /** Hintergrund hinter Dialogen mittelstark weichzeichnen (Android 12+). */
    private fun updateBlur() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val r = dp(10f)
        val effect = if (panels.isNotEmpty() || drag != null) android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP) else null
        stage.setRenderEffect(effect)
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
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(46f)).apply { if (i > 0) marginStart = dpi(4f) })
        }
        return row
    }

    /** Zwei beschriftete Blöcke in einer Zeile; der rechte Block beginnt bei leftW (in allen Zeilen gleich). */
    private fun pairRow(lc: String, lv: View, rc: String, rv: View, leftW: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(caption(lc)); addView(lv)
        }, LinearLayout.LayoutParams(leftW, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(caption(rc)); addView(rv)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        canvasView = CanvasView(this, tools, this)
        stage.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        docBar = palette(this, true)
        setBar = palette(this, true)
        setBar.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or2, _ ->
            if (r - l != or2 - ol) ui.post { rebuildDocBar() }
        }
        toolBar = palette(this, false)
        imageBar = palette(this, true).apply { visibility = View.GONE }
        val m = dpi(12f) - shadowInset(this)
        stage.addView(docBar, lp(Gravity.TOP or Gravity.START, m, m, 0, 0))
        stage.addView(setBar, lp(Gravity.TOP or Gravity.END, 0, m, m, 0))
        stage.addView(toolBar, lp(Gravity.CENTER_VERTICAL or Gravity.START, m, 0, 0, 0))
        stage.addView(imageBar, lp(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dpi(96f), 0, 0))
        setContentView(root)
        // Bildschirmtastatur und Systemleisten verändern das Layout nicht
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { _, _ -> WindowInsets.CONSUMED }
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
        // Einstellungspalette ≈ 1/3 der Breite: 5 Symbolbuttons (je 1,25) + 3 breite Textbuttons (je 1,6)
        val unit = ((screenW / 3f - dp(8f)) / 11.05f).roundToInt()
        btnSize = (unit * 1.25f).roundToInt().coerceIn(dpi(50f), dpi(70f))

        // Werkzeugpalette: Messen, Lineal, 5 Stifte, Radierer
        toolBar.removeAllViews(); toolBtns.clear()
        fun addTool(b: Btn) {
            toolBar.addView(b, LinearLayout.LayoutParams(btnSize, btnSize))
            toolBtns.add(b)
        }
        addTool(Btn(this).apply { icon = "dim"; onTap = { toolTap(T_MEASURE) }; onLong = { toolLong(T_MEASURE) } })
        addTool(Btn(this).apply { icon = "ruler"; onTap = { toolTap(T_RULER) } })
        for (i in 0 until 5) addTool(Btn(this).apply { icon = "pen"; iconFill = Pal.PENS[i]; onTap = { toolTap(T_PEN0 + i) }; onLong = { toolLong(T_PEN0 + i) } })
        addTool(Btn(this).apply { icon = "eraser"; onTap = { toolTap(T_ERASER) }; onLong = { toolLong(T_ERASER) } })

        // Einstellungspalette: Zurück, Vor, Zoom, Rotation, Maßstab, Import, Speichern, Schließen
        setBar.removeAllViews()
        // Hochformat: Einstellungsleiste schmaler, damit der Titel lesbar bleibt
        val portraitBars = (if (root.height > 0) root.height else dm.heightPixels) > screenW
        val wide = (btnSize * (if (portraitBars) 1.35f else 1.6f)).roundToInt()
        bUndo = Btn(this).apply { icon = "undo"; onTap = { doUndo(true) } }
        bRedo = Btn(this).apply { icon = "redo"; onTap = { doUndo(false) } }
        bZoom = Btn(this).apply {
            text = "100 %"; textSp = 16f; badgeSpace = true
            onTap = { delayedTap(this, { zoomDialog() }) { if (!tools.zoomLocked) { canvasView.zoom100Centered(); refreshUi() } } }
            onLong = { tools.zoomLocked = !tools.zoomLocked; refreshUi() }
        }
        bRot = Btn(this).apply {
            text = "0°"; textSp = 16f; badgeSpace = true
            onTap = { delayedTap(this, { rotDialog() }) { rotReset() } }
            onLong = { tools.rotLocked = !tools.rotLocked; refreshUi() }
        }
        bScale = Btn(this).apply { text = "1:100"; textSp = 16f; onTap = { scaleDialog() } }
        bImport = Btn(this).apply { icon = "import"; onTap = { startImport() } }
        bBg = Btn(this).apply {
            icon = "nobg"
            onTap = { tools.ignoreBackground = !tools.ignoreBackground; refreshUi() }
        }
        bSave = Btn(this).apply { icon = "save"; onTap = { this@MainActivity.active?.let { saveDoc(it, null) } } }
        bClose = Btn(this).apply { icon = "close"; onTap = { this@MainActivity.active?.let { closeDoc(it) } } }
        val iconW = (btnSize * (if (portraitBars) 0.9f else 1.1f)).roundToInt()
        val gapS = dpi(if (portraitBars) 1f else 4f)
        val all = listOf(bUndo, bRedo, bZoom, bRot, bScale, bBg, bImport, bSave, bClose)
        for ((i, b) in all.withIndex()) {
            val w = if (b === bZoom || b === bRot || b === bScale) wide else iconW
            setBar.addView(b, LinearLayout.LayoutParams(w, btnSize).apply { if (i < all.size - 1) marginEnd = gapS })
        }

        // Bildleiste
        imageBar.removeAllViews()
        for ((ic, f) in listOf<Pair<String, () -> Unit>>(
            "close" to { cancelSelection() },
            "rotate" to { canvasView.rotateSelected() },
            "fit" to { editSelection { d, im -> fitBox(d, im) } },
            "orig" to { editSelection { _, im -> origBox(im) } },
            "center" to { editSelection { d, im -> im.copy(x = (d.pageW - im.w) / 2f, y = (d.pageH - im.h) / 2f) } },
            "check" to { fixSelection() },
        )) imageBar.addView(Btn(this).apply {
            if (ic == "orig") { text = "1:1"; textSp = 16f } else icon = ic
            onTap = f
        }, LinearLayout.LayoutParams(iconW, btnSize))
    }

    private fun fmtWidth(v: Float) = if (v < 1f) String.format(Locale.US, "%.2f", v) else String.format(Locale.US, "%.1f", v)

    private fun fmtDeg(a: Float): String {
        val m = ((a % 360f) + 360f) % 360f
        return "${m.roundToInt() % 360}°"
    }

    private fun refreshUi() {
        if (!::bScale.isInitialized) return
        val d = active
        for ((k, b) in toolBtns.withIndex()) {
            b.active = when (k) {
                T_MEASURE -> tools.tool == Tool.MEASURE
                T_RULER -> tools.rulerOn
                T_ERASER -> tools.tool == Tool.ERASER
                else -> (tools.tool == Tool.PEN || tools.tool == Tool.FILL) && tools.pen == k - T_PEN0
            }
            if (k >= T_PEN0 && k < T_ERASER) b.icon = if (tools.fillMode[k - T_PEN0]) "bucket" else "pen"
            if (k == T_MEASURE) b.icon = if (tools.measureArea) "area" else "dim"
            b.text = when (k) {
                T_MEASURE -> (d?.unit?.label ?: "m") + (if (tools.measureArea) "²" else "")
                T_RULER -> fmtDeg(tools.rulerAngle)
                T_ERASER -> "${tools.eraserPct.roundToInt()} %"
                else -> if (tools.fillMode[k - T_PEN0]) "${tools.fillPct[k - T_PEN0].roundToInt()} %" else fmtWidth(tools.penWidth[k - T_PEN0])
            }
        }
        onViewChanged()
        bScale.text = "1:${d?.scale ?: 100}"
        bBg.active = tools.ignoreBackground
        bUndo.enabledBtn = d?.canUndo == true
        bRedo.enabledBtn = d?.canRedo == true
        for (b in listOf(bZoom, bRot, bScale, bImport, bSave, bClose)) b.enabledBtn = d != null
        imageBar.visibility = if (d?.selected() != null) View.VISIBLE else View.GONE
        rebuildDocBar()
        refreshStatus()
    }

    // ================================================================== Dokumentpalette

    private fun labelText(d: PageDoc) = if (d.dirty) "• ${d.title}" else d.title

    private var docArrow: Btn? = null
    private var docSlot = 0

    private var docMarkW = 0
    private var docBarSlots = 5

    private fun rebuildDocBar() {
        if (drag != null) return
        docBar.removeAllViews()
        docArrow = null
        val d = active
        if (d == null) { docBar.visibility = View.GONE; return }
        docBar.visibility = View.VISIBLE
        val gap = dpi(4f)
        val rw = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        val sw = if (setBar.width > 0) setBar.width else rw / 3
        // App-Zeichen links, dann 5 Label + 1 Button (Dropdown bzw. „+“) bis an die Einstellungspalette
        val markH = (btnSize * 0.68f).roundToInt()
        val markW = (markH * 52f / 62f).roundToInt()
        docBar.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_app_mark)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(markW, btnSize).apply { marginStart = dpi(6f); marginEnd = dpi(10f) })
        val avail = rw - sw - dpi(12f) * 3 - docBar.paddingLeft - docBar.paddingRight - markW - dpi(16f)
        val portrait = root.height > root.width
        val slot = if (portrait) (avail - btnSize - gap).coerceAtLeast(btnSize)
            else ((avail - btnSize - gap * 5) / 5).coerceAtLeast(btnSize)
        docSlot = slot
        docMarkW = markW
        docBarSlots = if (portrait) 1 else 5

        val idx = docs.indexOf(d)
        val shown: List<PageDoc> = when {
            portrait -> listOf(d)
            docs.size <= 5 -> docs.toList()
            idx < 5 -> docs.subList(0, 5).toList()
            else -> docs.subList(0, 4).toList() + d
        }
        for (doc in shown) {
            val b = Btn(this).apply {
                text = labelText(doc)
                textSp = 16f
                alignLeft = true
                active = doc === d
                onTap = { labelTap(doc) }
                onDragStart = { x, y -> startDrag(doc, true, x, y, this) }
                onDragMove = { x, y -> dragMove(x, y) }
                onDragEnd = { x, y, c -> dragEnd(x, y, c) }
            }
            docBar.addView(b, LinearLayout.LayoutParams(slot, btnSize).apply { marginEnd = gap })
        }
        if (docs.size <= 5 && !portrait) {
            if (docs.size < MAX_DOCS) {
                docBar.addView(Btn(this).apply { icon = "plus"; onTap = { showStart() } }, LinearLayout.LayoutParams(btnSize, btnSize))
            }
        } else {
            val arrow = Btn(this).apply { icon = "chevron"; onTap = { showDocDropdown() } }
            docArrow = arrow
            docBar.addView(arrow, LinearLayout.LayoutParams(btnSize, btnSize))
        }
    }

    // ---- Doppeltipp auf ein Titellabel = Umbenennen

    private var lastLabelDoc: PageDoc? = null
    private var lastLabelT = 0L

    private fun labelTap(doc: PageDoc) {
        val now = android.os.SystemClock.uptimeMillis()
        if (lastLabelDoc === doc && now - lastLabelT < 350) {
            lastLabelDoc = null
            renameDialog(doc)
        } else {
            lastLabelDoc = doc; lastLabelT = now
            if (active !== doc) activate(doc)
        }
    }

    // ---- Titel per Ziehen neu anordnen, auf „X“ ablegen = schließen

    private inner class Drag(val doc: PageDoc, val fromDropdown: Boolean) {
        val layer = FrameLayout(this@MainActivity)
        val bar = palette(this@MainActivity, true)
        val xPal = palette(this@MainActivity, true)
        val xBtn = Btn(this@MainActivity).apply { icon = "close" }
        val ghost = Btn(this@MainActivity)
        val slots = ArrayList<View>()
        var placeholder = -1
        var pending = -1
        var pendingRun: Runnable? = null
        var overX = false
        var inBar = false
        var hoverIdx = -1
        val origin = IntArray(2)
        var grabX = 0f
        var grabY = 0f
        var ghostW = 0
        var ghostH = 0
    }

    private var drag: Drag? = null

    /** Reihenfolge ohne das gezogene Dokument, mit Lücke an der Platzhalterstelle. */
    private fun fillDragBar(g: Drag) {
        g.bar.removeAllViews(); g.slots.clear()
        val gap = dpi(4f)
        g.bar.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_app_mark)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(docMarkW, btnSize).apply { marginStart = dpi(6f); marginEnd = dpi(10f) })
        val order = docs.filter { it !== g.doc }
        var k = 0
        for (i in 0 until docBarSlots) {
            val v: View = if (i == g.placeholder) View(this) else {
                val doc = order.getOrNull(k++)
                if (doc == null) View(this) else Btn(this).apply {
                    text = labelText(doc); textSp = 16f; alignLeft = true; active = doc === this@MainActivity.active && g.doc === this@MainActivity.active
                }
            }
            g.bar.addView(v, LinearLayout.LayoutParams(docSlot, btnSize).apply { marginEnd = gap })
            g.slots.add(v)
        }
        g.bar.addView(Btn(this).apply { icon = if (docs.size > docBarSlots) "chevron" else "plus" }, LinearLayout.LayoutParams(btnSize, btnSize))
    }

    private fun startDrag(doc: PageDoc, fromBar: Boolean, rawX: Float, rawY: Float, src: View) {
        if (drag != null) return
        lastLabelDoc = null
        val g = Drag(doc, !fromBar)
        drag = g
        root.getLocationOnScreen(g.origin)
        // Label bleibt zunächst an Ort und Stelle und wird von dort weggezogen
        val sl = IntArray(2); src.getLocationOnScreen(sl)
        g.grabX = rawX - sl[0]; g.grabY = rawY - sl[1]
        g.ghostW = src.width; g.ghostH = src.height
        if (fromBar) {
            val idx = docs.indexOf(doc)
            if (idx in 0 until docBarSlots) g.placeholder = idx
        }
        // Titelleiste als Kopie über der weichgezeichneten Bühne
        fillDragBar(g)
        g.layer.addView(g.bar, FrameLayout.LayoutParams(docBar.width, docBar.height).apply {
            gravity = Gravity.TOP or Gravity.START; leftMargin = docBar.left; topMargin = docBar.top
        })
        // „X“ der Einstellungspalette an derselben Stelle
        val bl = IntArray(2); val rl = IntArray(2)
        bClose.getLocationInWindow(bl); root.getLocationInWindow(rl)
        val pad = setBar.paddingTop
        g.xPal.addView(g.xBtn, LinearLayout.LayoutParams(bClose.width, bClose.height))
        g.layer.addView(g.xPal, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = bl[0] - rl[0] - pad; topMargin = bl[1] - rl[1] - pad
        })
        // Gezogenes Label
        g.ghost.text = labelText(doc); g.ghost.textSp = 16f; g.ghost.alignLeft = true; g.ghost.active = true
        val ins = shadowInset(this)
        val ghostBox = FrameLayout(this).apply {
            background = ShadowBg(this@MainActivity)
            setPadding(ins, ins, ins, ins)
            addView(g.ghost, FrameLayout.LayoutParams(g.ghostW, g.ghostH))
        }
        g.layer.addView(ghostBox, FrameLayout.LayoutParams(g.ghostW + 2 * ins, g.ghostH + 2 * ins).apply { gravity = Gravity.TOP or Gravity.START })
        root.addView(g.layer, root.indexOfChild(stage) + 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        docBar.alpha = 0f
        popup?.alpha = 0f
        updateBlur()
        dragMove(rawX, rawY)
    }

    private fun dragMove(rawX: Float, rawY: Float) {
        val g = drag ?: return
        val x = rawX - g.origin[0]; val y = rawY - g.origin[1]
        val gb = g.ghost.parent as View
        val ins = shadowInset(this)
        gb.translationX = x - g.grabX - ins
        gb.translationY = y - g.grabY - ins
        // Über dem „X“?
        val xl = IntArray(2); val rl = IntArray(2)
        g.xBtn.getLocationInWindow(xl); root.getLocationInWindow(rl)
        val m = dpi(12f)
        g.overX = x >= xl[0] - rl[0] - m && x <= xl[0] - rl[0] + g.xBtn.width + m &&
            y >= xl[1] - rl[1] - m && y <= xl[1] - rl[1] + g.xBtn.height + m
        g.xBtn.active = g.overX
        // Über der Titelleiste? Nächster Platz nach x
        g.inBar = !g.overX && y >= docBar.top - btnSize / 2 && y <= docBar.bottom + btnSize / 2 && x <= docBar.right + btnSize
        g.hoverIdx = -1
        if (g.inBar) {
            var best = 0; var bestD = Float.MAX_VALUE
            for ((i, v) in g.slots.withIndex()) {
                val cx = g.bar.left + v.left + v.width / 2f
                val dd = kotlin.math.abs(cx - x)
                if (dd < bestD) { bestD = dd; best = i }
            }
            g.hoverIdx = best
        }
        // Kurz verharren: Platz frei machen
        if (g.hoverIdx != g.pending) {
            g.pendingRun?.let { ui.removeCallbacks(it) }
            g.pending = g.hoverIdx
            if (g.hoverIdx >= 0 && g.hoverIdx != g.placeholder) {
                val r = Runnable {
                    if (drag === g && g.pending == g.hoverIdx) { g.placeholder = g.hoverIdx; fillDragBar(g) }
                }
                g.pendingRun = r
                ui.postDelayed(r, 250)
            }
        }
    }

    private fun dragEnd(rawX: Float, rawY: Float, cancelled: Boolean) {
        val g = drag ?: return
        dragMove(rawX, rawY)
        g.pendingRun?.let { ui.removeCallbacks(it) }
        root.removeView(g.layer)
        docBar.alpha = 1f
        drag = null
        updateBlur()
        if (g.fromDropdown) dismissPopup()
        if (!cancelled) {
            if (g.overX) {
                rebuildDocBar()
                closeDoc(g.doc)
                return
            }
            if (g.inBar) {
                val target = if (g.placeholder >= 0) g.placeholder else g.hoverIdx
                if (target >= 0) {
                    docs.remove(g.doc)
                    docs.add(target.coerceAtMost(docs.size), g.doc)
                    // Ein inaktives Dokument, das in die Leiste gezogen wurde, wird aktiv
                    if (g.doc !== active) { activate(g.doc); return }
                }
            }
        }
        rebuildDocBar()
    }

    private fun renameDialog(d: PageDoc) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(d.title)
            textSize = 18f
            setTextColor(Pal.BLACK)
            background = outlineBg(this@MainActivity)
            setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(10f))
            setSelectAllOnFocus(true)
        }
        showPanel(
            "Umbenennen",
            listOf(et),
            listOf(
                "Abbrechen" to {},
                "OK" to {
                    val t = et.text.toString().trim()
                    if (t.isNotEmpty() && t != d.title) {
                        d.title = t
                        d.dirty = true          // damit der neue Name auch wiederhergestellt wird
                        autosaveDoc(d)
                        refreshUi()
                    }
                },
            ),
            cancelable = true,
            widthPx = W_INPUT,
            atTop = true,
        )
    }

    private fun showDocDropdown() {
        val box = palette(this, false)
        val d = active
        var longest = 0
        val rowH = (btnSize * 0.8f).roundToInt()
        for (doc in docs) {
            val b = Btn(this).apply {
                text = labelText(doc)
                textSp = 16f
                alignLeft = true
                active = doc === d
                onTap = { dismissPopup(); activate(doc) }
                onDragStart = { x, y -> startDrag(doc, false, x, y, this) }
                onDragMove = { x, y -> dragMove(x, y) }
                onDragEnd = { x, y, c -> dragEnd(x, y, c) }
            }
            b.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.makeMeasureSpec(rowH, View.MeasureSpec.EXACTLY))
            longest = max(longest, b.measuredWidth)
            box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH))
        }
        val minW = docSlot + dpi(4f) + btnSize + box.paddingLeft + box.paddingRight
        val w = max(minW, longest + box.paddingLeft + box.paddingRight).coerceAtMost(docBar.right - dpi(12f))
        val top = docBar.bottom - 2 * shadowInset(this) + dpi(6f)
        var maxH = root.height - top - dpi(12f)
        if (root.height > root.width) maxH = min(maxH, rowH * 7 + box.paddingTop + box.paddingBottom)
        // Schatten und Rundung bleiben stehen, nur der Inhalt scrollt (auf die Rundung beschnitten)
        val ins = shadowInset(this)
        val inner = box.paddingLeft - ins
        box.background = null
        box.setPadding(inner, inner, inner, inner)
        val scroll = ScrollView(this).apply { addView(box); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val frame = FrameLayout(this).apply {
            background = ShadowBg(this@MainActivity)
            setPadding(ins, ins, ins, ins)
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        scroll.post { clipRound(scroll, dp(R_PANEL)) }
        box.measure(View.MeasureSpec.makeMeasureSpec(w - 2 * ins, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val p = FrameLayout.LayoutParams(w, min(box.measuredHeight + 2 * ins, maxH)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = docBar.right - w
            topMargin = top
        }
        showPopup(frame, p, -2, withCatcher = true)
        // Während das Menü offen ist, wird der Pfeil zum „+“
        if (docs.size < MAX_DOCS) {
            docArrow?.let { a ->
                a.icon = "plus"
                a.onTap = { dismissPopup(); showStart() }
                a.elevation = dp(30f)
                a.bringToFront()
            }
        }
    }

    // ================================================================== Popups an den Paletten

    private fun showPopup(v: View, p: FrameLayout.LayoutParams, key: Int, withCatcher: Boolean) {
        dismissPopup()
        if (withCatcher) {
            val c = View(this)
            c.setOnClickListener { dismissPopup() }
            stage.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            catcher = c
        }
        stage.addView(v, p)
        if (key == -2) docBar.bringToFront()
        popup = v
        popupFor = key
    }

    /** Werkzeug, dessen Regler beim aktuellen Antippen geschlossen wurde. */
    private var touchDismissed = -1

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchDismissed = -1
            val pv = popup
            // Offene Regler schließen sich bei jeder anderen Aktion (das Dokumentmenü hat seinen eigenen Fänger)
            if (pv != null && popupFor >= 0) {
                val r = android.graphics.Rect()
                if (!pv.getGlobalVisibleRect(r) || !r.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    touchDismissed = popupFor
                    dismissPopup()
                    canvasView.hideSizePreview()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun dismissPopup() {
        val wasDocs = popupFor == -2
        popup?.let { stage.removeView(it) }
        catcher?.let { stage.removeView(it) }
        popup = null; catcher = null; popupFor = -1
        if (wasDocs) rebuildDocBar()
    }

    // ================================================================== Werkzeuge

    private var toolTapK = -1
    private var toolTapT = 0L
    private var toolTapRun: Runnable? = null

    private fun toolTap(k: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        val btn = toolBtns[k]
        // Doppeltipp: Lineal auf 0° zurückdrehen bzw. Größe auf den Standardwert
        if (k == toolTapK && now - toolTapT < 300) {
            toolTapRun?.let { btn.removeCallbacks(it) }
            toolTapRun = null; toolTapK = -1
            if (k == T_RULER) rulerReset() else resetSize(k)
            return
        }
        toolTapRun?.let { toolBtns.forEach { b -> b.removeCallbacks(it) } }
        toolTapRun = null
        toolTapK = k; toolTapT = now
        fun later(f: () -> Unit) {
            val r = Runnable { toolTapRun = null; f() }
            toolTapRun = r
            btn.postDelayed(r, 280)
        }
        if (k == T_RULER) {
            dismissPopup()
            later {
                tools.rulerOn = !tools.rulerOn
                if (tools.rulerOn && tools.tool == Tool.MEASURE) {
                    tools.tool = if (tools.fillMode[tools.pen]) Tool.FILL else Tool.PEN
                    canvasView.clearMeasure()
                }
                canvasView.invalidate()
                refreshUi()
            }
            return
        }
        val isActive = when (k) {
            T_MEASURE -> tools.tool == Tool.MEASURE
            T_ERASER -> tools.tool == Tool.ERASER
            else -> (tools.tool == Tool.PEN || tools.tool == Tool.FILL) && tools.pen == k - T_PEN0
        }
        if (isActive && touchDismissed == k) { dismissPopup(); return }
        if (isActive && k == T_MEASURE) {
            // Aktives Messen: zwischen Länge und Fläche umschalten
            dismissPopup()
            later {
                tools.measureArea = !tools.measureArea
                canvasView.clearMeasure()
                canvasView.invalidate()
                refreshUi()
            }
            return
        }
        if (isActive && k >= T_PEN0 && k < T_ERASER) {
            // Aktiver Stift: zwischen Stift und Füllen umschalten
            dismissPopup()
            later {
                val i = k - T_PEN0
                tools.fillMode[i] = !tools.fillMode[i]
                if (tools.pen == i && tools.tool != Tool.MEASURE && tools.tool != Tool.ERASER)
                    tools.tool = if (tools.fillMode[i]) Tool.FILL else Tool.PEN
                canvasView.clearMeasure()
                canvasView.invalidate()
                refreshUi()
            }
            return
        }
        // Messen/Radierer: Antippen, wenn aktiv, bewirkt nichts (Einstellungen über Halten)
        if (isActive) { dismissPopup(); return }
        dismissPopup()
        when (k) {
            T_MEASURE -> { tools.tool = Tool.MEASURE; tools.rulerOn = false }
            T_ERASER -> tools.tool = Tool.ERASER
            else -> { tools.pen = k - T_PEN0; tools.tool = if (tools.fillMode[tools.pen]) Tool.FILL else Tool.PEN }
        }
        if (k != T_MEASURE) canvasView.clearMeasure()
        canvasView.invalidate()
        refreshUi()
    }

    /** Doppeltipp: Größe des Werkzeugs auf den Standardwert. */
    private fun resetSize(k: Int) {
        when {
            k == T_MEASURE && tools.measureArea -> { canvasView.removeLastAreaPoint(); return }
            k == T_MEASURE -> {
                val d = active ?: return
                d.unit = d.initUnit; d.dirty = true
                canvasView.refreshMeasure(); canvasView.invalidate()
            }
            k == T_ERASER -> tools.eraserPct = ToolState.DEF_ERASER_PCT
            k in T_PEN0 until T_ERASER -> {
                val i = k - T_PEN0
                if (tools.fillMode[i]) tools.fillPct[i] = ToolState.DEF_FILL_PCT else tools.penWidth[i] = ToolState.DEF_PEN_WIDTH
            }
            else -> return
        }
        val reopen = popupFor == k
        refreshUi()
        if (reopen) showToolPopup(k)
    }

    private var rulerAnim: android.animation.ValueAnimator? = null

    /** Lineal einblenden (falls aus) und sanft auf 0° zurückdrehen. */
    private fun rulerReset() {
        dismissPopup()
        if (!tools.rulerOn) {
            tools.rulerOn = true
            if (tools.tool == Tool.MEASURE) { tools.tool = if (tools.fillMode[tools.pen]) Tool.FILL else Tool.PEN; canvasView.clearMeasure() }
        }
        rulerAnim?.cancel()
        // Bis 180° zurück (minus) auf 0°, ab 181° vorwärts (plus) auf 360° = 0°
        val a0 = ((tools.rulerAngle % 360f) + 360f) % 360f
        val target = if (a0 <= 180f) 0f else 360f
        if (a0 == 0f) { tools.rulerAngle = 0f; canvasView.invalidate(); refreshUi(); return }
        rulerAnim = android.animation.ValueAnimator.ofFloat(a0, target).apply {
            duration = 250
            addUpdateListener {
                tools.rulerAngle = it.animatedValue as Float
                toolBtns[T_RULER].text = fmtDeg(tools.rulerAngle)
                canvasView.invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { tools.rulerAngle = 0f; refreshUi() }
            })
            start()
        }
    }

    private fun toolLong(k: Int) {
        if (k == T_RULER) {
            if (!tools.rulerOn) {
                tools.rulerOn = true
                if (tools.tool == Tool.MEASURE) { tools.tool = if (tools.fillMode[tools.pen]) Tool.FILL else Tool.PEN; canvasView.clearMeasure() }
                canvasView.invalidate(); refreshUi()
            }
            showToolPopup(k)
            return
        }
        when (k) {
            T_MEASURE -> { tools.tool = Tool.MEASURE; tools.rulerOn = false }
            T_ERASER -> tools.tool = Tool.ERASER
            else -> { tools.pen = k - T_PEN0; tools.tool = if (tools.fillMode[tools.pen]) Tool.FILL else Tool.PEN }
        }
        if (k != T_MEASURE) canvasView.clearMeasure()
        canvasView.invalidate()
        refreshUi()
        showToolPopup(k)
    }

    private fun showToolPopup(k: Int) {
        val box = palette(this, true)
        var prevX = 0f
        var prevBottom = 0f
        if (k == T_RULER) {
            // Lineal: orthogonales Zeichnen ein/aus
            val b = Btn(this)
            b.icon = "ortho"
            b.active = tools.rulerOrtho
            b.onTap = { tools.rulerOrtho = !tools.rulerOrtho; b.active = tools.rulerOrtho }
            box.addView(b, LinearLayout.LayoutParams(btnSize, btnSize))
        } else if (k == T_MEASURE) {
            val d = active ?: return
            for (u in MUnit.values()) {
                val b = Btn(this)
                b.text = u.label + (if (tools.measureArea) "²" else "")
                b.active = d.unit == u
                b.onTap = {
                    d.unit = u; d.dirty = true
                    canvasView.refreshMeasure(); canvasView.invalidate()
                    showToolPopup(T_MEASURE)
                    refreshUi()
                }
                b.minimumWidth = btnSize
                box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, btnSize))
            }
        } else {
            val label = Btn(this)
            val slider: SliderView
            if (k == T_ERASER) {
                label.text = "${tools.eraserPct.roundToInt()} %"
                slider = SliderView(this, ERASER_ANCHORS, tools.eraserPct, { v ->
                    tools.eraserPct = v.roundToInt().toFloat().coerceIn(1f, 100f)
                    label.text = "${tools.eraserPct.roundToInt()} %"
                    toolBtns[T_ERASER].text = label.text
                    canvasView.showSizePreview(null, canvasView.eraserPxFor(tools.eraserPct), prevX, prevBottom)
                }, { canvasView.hideSizePreview() })
            } else if (tools.fillMode[k - T_PEN0]) {
                val idx = k - T_PEN0
                // Toleranz in % (100 % = 10 pt), Fangpunkte wie beim Radierer; Vorschau in Originalgröße
                label.text = "${tools.fillPct[idx].roundToInt()} %"
                // 0–100 % = 0–0,5 pt, stufenlos ohne Fangpunkte
                slider = SliderView(this, floatArrayOf(0f, 100f), tools.fillPct[idx], { v ->
                    tools.fillPct[idx] = v.roundToInt().toFloat().coerceIn(0f, 100f)
                    label.text = "${tools.fillPct[idx].roundToInt()} %"
                    toolBtns[k].text = label.text
                }, { canvasView.hideSizePreview() }, snaps = false)
            } else {
                val idx = k - T_PEN0
                label.text = fmtWidth(tools.penWidth[idx]) + " pt"
                slider = SliderView(this, PEN_ANCHORS, tools.penWidth[idx], { v ->
                    tools.penWidth[idx] = v
                    label.text = fmtWidth(tools.penWidth[idx]) + " pt"
                    toolBtns[k].text = fmtWidth(tools.penWidth[idx])
                    canvasView.showSizePreview(Pal.PENS[idx], canvasView.penPx(tools.penWidth[idx]), prevX, prevBottom)
                }, { canvasView.hideSizePreview() }, discrete = true)
            }
            // Gleiche Abstände zwischen den Fangpunkten wie bei den Stiften (45 dp je Abschnitt)
            val segs = (when {
                k == T_ERASER -> ERASER_ANCHORS.size
                tools.fillMode[k - T_PEN0] -> 5
                else -> PEN_ANCHORS.size
            }) - 1
            box.addView(slider, LinearLayout.LayoutParams(dpi(45f * segs + 28f), btnSize))
        }
        val btn = toolBtns[k]
        val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = toolBar.right - 2 * shadowInset(this@MainActivity) + dpi(8f)
            topMargin = toolBar.top + btn.top + btn.height / 2 - (btnSize + toolBar.paddingTop * 2) / 2
        }
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        prevX = (p.leftMargin + box.measuredWidth + dpi(16f)).toFloat()
        prevBottom = (p.topMargin + box.measuredHeight / 2).toFloat()
        showPopup(box, p, k, withCatcher = false)
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

    private fun editSelection(f: (PageDoc, ImageItem) -> ImageItem) {
        val d = active ?: return
        val im = d.selected() ?: return
        d.pushUndo()
        val n = f(d, im)
        d.items = d.items.map { if (it is ImageItem && it.id == im.id) n else it }
        d.dirty = true
        canvasView.invalidate()
        refreshUi()
    }

    /** Größtmöglich proportional auf die Seite, zentriert; die Drehung bleibt. */
    private fun fitBox(d: PageDoc, im: ImageItem): ImageItem {
        val t = Math.toRadians(im.angle.toDouble())
        val c = kotlin.math.abs(kotlin.math.cos(t)).toFloat(); val sn = kotlin.math.abs(kotlin.math.sin(t)).toFloat()
        val bw = im.w * c + im.h * sn
        val bh = im.w * sn + im.h * c
        val k = min(d.pageW / bw, d.pageH / bh)
        val w = im.w * k; val h = im.h * k
        return im.copy(x = (d.pageW - w) / 2f, y = (d.pageH - h) / 2f, w = w, h = h)
    }

    /** 1:1: Originalgröße (Bilder nach DPI, PDFs in Seitengröße) an der Ursprungsposition, ungedreht. */
    private fun origBox(im: ImageItem): ImageItem {
        val w = if (im.isPdf) im.srcW else im.srcW / im.srcDpi * 72f
        val h = if (im.isPdf) im.srcH else im.srcH / im.srcDpi * 72f
        return im.copy(x = im.ox - w / 2f, y = im.oy - h / 2f, w = w, h = h, angle = 0f)
    }

    private fun cancelSelection() {
        val d = active ?: return
        val id = d.selectedImage ?: return
        d.pushUndo()
        d.items = d.items.filter { !(it is ImageItem && it.id == id) }
        d.selectedImage = null
        canvasView.invalidate()
        refreshUi()
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
        bZoom.active = tools.zoomLocked
        // Während eines Imports: Drehung des importierten Elements statt der Zeichenfläche
        val sel = d?.selected()
        bRot.text = fmtDeg(sel?.angle ?: d?.rot ?: 0f)
        bRot.badge = if (tools.rotLocked && sel == null) "lock" else null
        bRot.active = tools.rotLocked && sel == null
    }

    override fun refreshStatus() {
        if (toolBtns.size > T_RULER) toolBtns[T_RULER].text = fmtDeg(tools.rulerAngle)
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
        d.initUnit = d.unit
        docs.add(d)
        activate(d)
    }

    private fun activate(d: PageDoc) {
        dismissPopup()
        active?.let { if (it !== d) autosaveDoc(it) }
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
                "Speichern & schließen" to { saveDoc(d, { reallyClose(d) }, back = { closeDoc(d) }) },
            ),
            cancelable = true,
            widthPx = W_FLOW,
        )
    }

    private fun reallyClose(d: PageDoc) = removeDoc(d, showStartIfEmpty = true)

    private fun removeDoc(d: PageDoc, showStartIfEmpty: Boolean) {
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
        if (docs.isEmpty() && showStartIfEmpty) showStart()
    }

    private fun autosaveDoc(d: PageDoc) {
        val f = File(d.workDir, "state.json")
        if (d.dirty) {
            val json = try { Store.docToJson(d).toString() } catch (e: Exception) { return }
            worker.execute { try { f.writeText(json) } catch (_: Exception) {} }
        } else if (f.exists()) f.delete()
    }

    private fun autosaveAll() = docs.forEach { autosaveDoc(it) }

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
        content.add(row("Neues Dokument") { newBlank { showStart() } })
        content.add(row("Dokument öffnen") { pickOpen() })
        val recent = Store.recent(prefs)
        if (recent.isNotEmpty()) {
            content.add(caption("Zuletzt geöffnet"))
            for (r in recent) content.add(row(r.name) { openFile(r.uri, persist = false, fromRecent = true, back = { showStart() }) })
        }
        val cancelable = docs.isNotEmpty()
        val pnl = showPanel("Klemmbrett", content, if (cancelable) listOf("Abbrechen" to {}) else emptyList(), cancelable = cancelable, widthPx = W_FLOW)
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
            REQ_OPEN -> if (uri != null) openFile(uri, persist = true, back = { showStart() }) else if (docs.isEmpty()) showStart()
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
                    d.fileTitle = d.title
                    Store.addRecent(prefs, uri, d.title)
                    writeTo(d, uri, ps.dpi, backup = false, after = ps.after)
                }
            }
        }
    }

    // ================================================================== Einstellungsdialog

    private enum class Kind { BLANK, PDF, IMAGE }
    private class DocSettings(var format: Int, var landscape: Boolean, var dpi: Int, var scale: Int, var unit: MUnit, var name: String = "")

    private fun settingsDialog(kind: Kind, title: String, info: String?, init: DocSettings, multi: Boolean, back: (() -> Unit)? = null, done: (DocSettings?, Boolean) -> Unit) {
        val s = init
        val content = ArrayList<View>()
        if (info != null) content.add(bodyText(info))
        var nameField: EditText? = null
        if (kind == Kind.BLANK) {
            content.add(caption("Name"))
            val nf = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                setText(s.name)
                textSize = 18f
                setTextColor(Pal.BLACK)
                background = outlineBg(this@MainActivity)
                setPadding(dpi(14f), dpi(8f), dpi(14f), dpi(8f))
                setSelectAllOnFocus(true)
            }
            nameField = nf
            content.add(nf)
        }
        // Links Format / Maßstab, daneben (linksbündig untereinander) Ausrichtung / Maßeinheit
        val et = numberField(s.scale)
        val sRow = scaleRow(et)
        var fRow: View? = null
        if (kind != Kind.PDF) {
            val names = if (kind == Kind.IMAGE) A_NAMES + "Bildgröße" else A_NAMES
            fRow = choiceRow(names, s.format) { s.format = it }
        }
        val um = View.MeasureSpec.UNSPECIFIED
        val leftW = listOfNotNull(fRow, sRow).maxOf { it.measure(um, um); it.measuredWidth } + dpi(24f)
        if (fRow != null) content.add(pairRow(
            "Format", fRow,
            "Ausrichtung", choiceRow(listOf("Portrait", "Landschaft"), if (s.landscape) 1 else 0) { s.landscape = it == 1 },
            leftW,
        ))
        content.add(pairRow(
            "Maßstab", sRow,
            "Maßeinheit", choiceRow(MUnit.values().map { it.label }, s.unit.ordinal) { s.unit = MUnit.values()[it] },
            leftW,
        ))

        fun read(): DocSettings {
            s.scale = (et.text.toString().toIntOrNull() ?: s.scale).coerceIn(1, 100000)
            nameField?.let { s.name = it.text.toString().trim() }
            return s
        }
        val buttons = ArrayList<Pair<String, () -> Unit>>()
        if (back != null) buttons.add("Zurück" to back) else buttons.add("Abbrechen" to { done(null, false) })
        if (multi) buttons.add("Alle Seiten anlegen" to { done(read(), true) })
        buttons.add((if (multi) "Seite anlegen" else "Anlegen") to { done(read(), false) })
        showPanel(title, content, buttons, widthPx = when (kind) { Kind.IMAGE -> dpi(740f); Kind.PDF -> dpi(if (multi) 540f else 460f); else -> W_FLOW })
    }

    // ================================================================== Neu / Öffnen

    private fun newBlank(back: (() -> Unit)? = null) {
        val defName = "Dokument ${untitled + 1}"
        settingsDialog(Kind.BLANK, "Neues Dokument", null, DocSettings(F_A4, false, 150, 100, MUnit.M, defName), false, back) { s, _ ->
            if (s == null) { if (docs.isEmpty()) showStart(); return@settingsDialog }
            val (wmm, hmm) = A_MM[s.format]
            val w = (if (s.landscape) hmm else wmm) * PT_PER_MM
            val h = (if (s.landscape) wmm else hmm) * PT_PER_MM
            untitled++
            val d = newDoc(s.name.ifEmpty { defName })
            d.pageW = w; d.pageH = h; d.scale = s.scale; d.unit = s.unit; d.bgColor = Pal.PAPER
            runBusy({
                PdfIO.createBlank(d.baseFile, w, h)
                setupDoc(d, computeBg = false)
            }) { addDoc(d) }
        }
    }

    private fun openFile(uri: Uri, persist: Boolean, fromRecent: Boolean = false, back: (() -> Unit)? = null) {
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
                        askPdfPage(PdfOpen(uri, base, tmp, pd, n, zbs, sizes, writable).also { it.back = back }, 0)
                    }
                } else if (mime == "image/jpeg" || mime == "image/png") {
                    val isJpeg = mime == "image/jpeg"
                    val (pw, ph) = Img.bounds(tmp, isJpeg)
                    val dpi = Img.readDpi(tmp, isJpeg)
                    val base = name.substringBeforeLast('.')
                    ui.post { hideBusy(); askImage(base, tmp, isJpeg, pw, ph, dpi, back) }
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
    ) {
        var created = 0
        var back: (() -> Unit)? = null
        val pageDocs = HashMap<Int, PageDoc>()
    }

    private fun mmText(p: Pair<Float, Float>) =
        "${(p.first / PT_PER_MM).roundToInt()} × ${(p.second / PT_PER_MM).roundToInt()} mm"

    private fun askPdfPage(ctx: PdfOpen, i: Int) {
        if (i >= ctx.n) { finishPdfOpen(ctx); return }
        if (docs.size + ctx.created >= MAX_DOCS) {
            toast("Maximal $MAX_DOCS Dokumente geöffnet.")
            finishPdfOpen(ctx); return
        }
        val zb = ctx.zbs[i]
        val init = DocSettings(F_A4, false, zb?.dpi ?: 150, zb?.scale ?: 100, zb?.unit ?: MUnit.M)
        val info = (if (ctx.n > 1) "Seite ${i + 1} von ${ctx.n} · " else "") + "Format aus PDF: " + mmText(ctx.sizes[i])
        val back: (() -> Unit)? = when {
            i > 0 -> { { undoPdfPage(ctx, i - 1); askPdfPage(ctx, i - 1) } }
            ctx.back != null -> { { closePdfOpen(ctx); ctx.back?.invoke() } }
            else -> null
        }
        settingsDialog(Kind.PDF, ctx.name, info, init, ctx.n > 1, back) { s, all ->
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
            d.fileTitle = if (ctx.n == 1) ctx.name else null
            setupDoc(d, computeBg = true)
        }) { addDoc(d); ctx.pageDocs[k] = d }
    }

    private fun undoPdfPage(ctx: PdfOpen, k: Int) {
        val d = ctx.pageDocs.remove(k) ?: return
        ctx.created = (ctx.created - 1).coerceAtLeast(0)
        if (docs.contains(d)) removeDoc(d, showStartIfEmpty = false)
    }

    private fun closePdfOpen(ctx: PdfOpen) {
        worker.execute {
            try { ctx.pd.close() } catch (_: Exception) {}
            ctx.tmp.delete()
        }
    }

    private fun finishPdfOpen(ctx: PdfOpen) {
        worker.execute {
            try { ctx.pd.close() } catch (_: Exception) {}
            ctx.tmp.delete()
            ui.post { if (docs.isEmpty() && ctx.created == 0) showStart() }
        }
    }

    private fun askImage(name: String, tmp: File, isJpeg: Boolean, pw: Int, ph: Int, fileDpi: Int?, back: (() -> Unit)? = null) {
        val dpi = fileDpi ?: 300
        val info = "Bild: $pw × $ph Pixel" + (if (fileDpi != null) " · $fileDpi DPI" else "")
        settingsDialog(Kind.IMAGE, name, info, DocSettings(F_IMAGE, pw > ph, dpi, 100, MUnit.M), false, back?.let { b -> { tmp.delete(); b() } }) { s, _ ->
            if (s == null) { tmp.delete(); if (docs.isEmpty()) showStart(); return@settingsDialog }
            val d = newDoc(name)
            d.scale = s.scale; d.unit = s.unit; d.dpi = dpi
            runBusy({
                val norm = Img.normalize(tmp, File(d.workDir, if (isJpeg) "src.jpg" else "src.png"), isJpeg)
                val w: Float; val h: Float
                if (s.format == F_IMAGE) {
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
                        ImageItem(id, true, false, out.path, info.w, info.h, 0f, 0f, 1f, 1f, 0f, false)
                    }
                    "image/jpeg", "image/png" -> {
                        val isJpeg = mime == "image/jpeg"
                        val fileDpi = Img.readDpi(tmp, isJpeg) ?: 300
                        val norm = Img.normalize(tmp, File(d.workDir, "imp_$id." + if (isJpeg) "jpg" else "png"), isJpeg)
                        ImageItem(id, false, norm.isJpeg, norm.file.path, norm.w.toFloat(), norm.h.toFloat(), 0f, 0f, 1f, 1f, 0f, false, fileDpi.toFloat())
                    }
                    else -> { tmp.delete(); throw IllegalArgumentException("Dateityp wird nicht unterstützt.") }
                }
                val sc = min(d.pageW * 0.5f / item.srcW, d.pageH * 0.5f / item.srcH)
                val w = item.srcW * sc; val h = item.srcH * sc
                val placed = item.copy(x = (d.pageW - w) / 2f, y = (d.pageH - h) / 2f, w = w, h = h, ox = d.pageW / 2f, oy = d.pageH / 2f)
                val bmp = Img.display(placed)
                ui.post {
                    hideBusy()
                    d.bitmaps[id] = bmp
                    d.pushUndo()
                    d.items = d.items + placed
                    d.selectedImage = id
                    d.dirty = true
                    if (canvasView.doc === d) canvasView.showWholePage()
                    canvasView.invalidate()
                    refreshUi()
                }
            } catch (e: Throwable) {
                ui.post { hideBusy(); errorDialog("Import fehlgeschlagen", e, null) }
            }
        }
    }

    // ================================================================== Speichern

    private fun saveDoc(d: PageDoc, after: (() -> Unit)?, back: (() -> Unit)? = null) {
        val canOverwrite = d.sourceUri != null && d.writable && !d.fromMulti && d.title == d.fileTitle
        fun go(asNew: Boolean) {
            if (d.items.any { it is ImageItem }) {
                dpiDialog(d.dpi, { saveDoc(d, after, back) }) { dpi -> d.dpi = dpi; saveDoc2(d, dpi, after, asNew) }
            } else saveDoc2(d, d.dpi, after, asNew)
        }
        val buttons = ArrayList<Pair<String, () -> Unit>>()
        if (back != null) buttons.add("Zurück" to back) else buttons.add("Abbrechen" to {})
        if (canOverwrite) buttons.add("Überschreiben" to { go(false) })
        buttons.add("Speichern unter" to { go(true) })
        showPanel(
            "„${d.title}“ speichern",
            listOf(bodyText(when {
                canOverwrite -> "Die geöffnete Datei überschreiben oder als neue Datei speichern?"
                d.sourceUri != null && d.writable && !d.fromMulti -> "Das Dokument wurde umbenannt. Es wird als neue Datei „${d.title}.pdf“ gespeichert."
                else -> "Diese Seite hat noch keine eigene Datei. Sie wird als neue PDF gespeichert."
            })),
            buttons,
            cancelable = true,
            widthPx = W_FLOW,
        )
    }

    private fun dpiDialog(current: Int, back: () -> Unit, done: (Int) -> Unit) {
        var sel = DPIS.indexOf(current).coerceAtLeast(0)
        showPanel(
            "Bildauflösung",
            listOf(
                bodyText("Eingebettete Bilder werden beim Speichern höchstens mit dieser Auflösung abgelegt."),
                choiceRow(DPIS.map { "$it DPI" }, sel) { sel = it },
            ),
            listOf("Zurück" to back, "Speichern" to { done(DPIS[sel]) }),
            cancelable = true,
            widthPx = W_FLOW,
        )
    }

    private fun saveDoc2(d: PageDoc, dpi: Int, after: (() -> Unit)?, asNew: Boolean) {
        val uri = d.sourceUri
        if (asNew || uri == null || !d.writable || d.fromMulti) {
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
            widthPx = W_INPUT,
            atTop = true,
        )
    }

    /** Zahleneingabe mit Einheit (Zoom in %, Rotation in °). */
    private fun valueDialog(title: String, value: Int, unit: String, apply: (Int) -> Unit) {
        val et = numberField(value)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(et, LinearLayout.LayoutParams(dpi(160f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@MainActivity).apply { text = " $unit"; textSize = 18f; setTextColor(Pal.BLACK) })
        }
        showPanel(title, listOf(row), listOf(
            "Abbrechen" to {},
            "OK" to { et.text.toString().toIntOrNull()?.let(apply) },
        ), cancelable = true, widthPx = W_INPUT, atTop = true)
    }

    private fun zoomDialog() {
        val d = active ?: return
        valueDialog("Zoom einstellen", (d.zoom * 100f).roundToInt(), "%") { v ->
            val z = (v / 100f).coerceIn(canvasView.minZoom(d), 30f)
            canvasView.setZoom(z)
            canvasView.settle()
        }
    }

    private var pendingTap: Runnable? = null
    private var pendingTapBtn: View? = null

    /** Einfacher Tipp erst nach kurzer Wartezeit, falls kein zweiter Tipp folgt; Doppeltipp sofort. */
    private fun delayedTap(b: View, single: () -> Unit, double: () -> Unit) {
        val p = pendingTap
        if (p != null && pendingTapBtn === b) {
            b.removeCallbacks(p)
            pendingTap = null; pendingTapBtn = null
            double()
            return
        }
        p?.let { pendingTapBtn?.removeCallbacks(it) }
        val r = Runnable { pendingTap = null; pendingTapBtn = null; single() }
        pendingTap = r; pendingTapBtn = b
        b.postDelayed(r, 280)
    }

    /** Doppeltipp auf Rotation: Import bzw. Plan auf 0°. */
    private fun rotReset() {
        val d = active ?: return
        val sel = d.selected()
        if (sel != null) {
            if (sel.angle == 0f) return
            d.pushUndo()
            d.items = d.items.map { if (it is ImageItem && it.id == sel.id) it.copy(angle = 0f) else it }
            d.dirty = true
            canvasView.invalidate()
            refreshUi()
        } else if (!tools.rotLocked) {
            canvasView.setPlanRotation(0f)
            canvasView.settle()
            refreshUi()
        }
    }

    private fun rotDialog() {
        val d = active ?: return
        val sel = d.selected()
        val cur = (sel?.angle ?: d.rot).roundToInt()
        valueDialog(if (sel != null) "Drehung des Imports" else "Rotation einstellen", cur, "°") { v ->
            val a = (((v % 360) + 360) % 360).toFloat()
            if (sel != null) {
                d.pushUndo()
                d.items = d.items.map { if (it is ImageItem && it.id == sel.id) it.copy(angle = a) else it }
                d.dirty = true
                canvasView.invalidate()
                refreshUi()
            } else {
                canvasView.setPlanRotation(a)
                canvasView.settle()
            }
        }
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
            opaque = true,
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

    override fun onMessage(text: String) = toast(text)

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
