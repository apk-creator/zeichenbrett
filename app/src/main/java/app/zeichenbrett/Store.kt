package app.zeichenbrett

import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

object Store {

    private fun ptsToString(p: FloatArray): String {
        val sb = StringBuilder(p.size * 7)
        for (i in p.indices) {
            if (i > 0) sb.append(',')
            sb.append(String.format(Locale.US, "%.3f", p[i]))
        }
        return sb.toString()
    }

    private fun ptsFromString(s: String): FloatArray {
        if (s.isEmpty()) return FloatArray(0)
        val parts = s.split(',')
        return FloatArray(parts.size) { parts[it].toFloat() }
    }

    fun itemsToJson(items: List<Item>): JSONArray {
        val a = JSONArray()
        for (it in items) {
            val o = JSONObject()
            when (it) {
                is Stroke -> {
                    o.put("t", "s"); o.put("c", it.color); o.put("w", it.width.toDouble()); o.put("p", ptsToString(it.pts))
                }
                is Cover -> {
                    o.put("t", "c"); o.put("c", it.color); o.put("w", it.width.toDouble()); o.put("p", ptsToString(it.pts))
                }
                is ImageItem -> {
                    o.put("t", "i"); o.put("id", it.id); o.put("pdf", it.isPdf); o.put("jpg", it.isJpeg)
                    o.put("f", it.file); o.put("sw", it.srcW.toDouble()); o.put("sh", it.srcH.toDouble())
                    o.put("x", it.x.toDouble()); o.put("y", it.y.toDouble())
                    o.put("w", it.w.toDouble()); o.put("h", it.h.toDouble())
                    o.put("a", it.angle.toDouble()); o.put("fx", it.fixed); o.put("dpi", it.srcDpi.toDouble()); o.put("ox", it.ox.toDouble()); o.put("oy", it.oy.toDouble())
                }
            }
            a.put(o)
        }
        return a
    }

    fun itemsFromJson(a: JSONArray?): List<Item> {
        if (a == null) return emptyList()
        val out = ArrayList<Item>(a.length())
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            when (o.optString("t")) {
                "s" -> out.add(Stroke(o.getInt("c"), o.getDouble("w").toFloat(), ptsFromString(o.getString("p"))))
                "c" -> out.add(Cover(o.getInt("c"), o.getDouble("w").toFloat(), ptsFromString(o.getString("p"))))
                "i" -> {
                    var x = o.getDouble("x").toFloat(); var y = o.getDouble("y").toFloat()
                    var w = o.getDouble("w").toFloat(); var h = o.getDouble("h").toFloat()
                    val angle: Float
                    if (o.has("a")) angle = o.getDouble("a").toFloat() else {
                        val r = o.optInt("r", 0)
                        angle = r * 90f
                        if (r % 2 == 1) { val cx = x + w / 2f; val cy = y + h / 2f; val t = w; w = h; h = t; x = cx - w / 2f; y = cy - h / 2f }
                    }
                    out.add(
                        ImageItem(
                            o.getString("id"), o.getBoolean("pdf"), o.getBoolean("jpg"), o.getString("f"),
                            o.getDouble("sw").toFloat(), o.getDouble("sh").toFloat(),
                            x, y, w, h, angle, o.getBoolean("fx"),
                            o.optDouble("dpi", 150.0).toFloat(),
                            o.optDouble("ox", (x + w / 2f).toDouble()).toFloat(),
                            o.optDouble("oy", (y + h / 2f).toDouble()).toFloat(),
                        )
                    )
                }
            }
        }
        return out
    }

    fun docToJson(d: PageDoc): JSONObject = JSONObject().apply {
        put("title", d.title)
        put("src", d.sourceUri?.toString() ?: "")
        put("writable", d.writable)
        put("multi", d.fromMulti)
        put("w", d.pageW.toDouble())
        put("h", d.pageH.toDouble())
        put("scale", d.scale)
        put("unit", d.unit.name)
        put("dpi", d.dpi)
        put("bg", d.bgColor)
        put("backup", d.backupDone)
        put("items", itemsToJson(d.items))
    }

    fun docFromDir(dir: File): PageDoc {
        val j = JSONObject(File(dir, "state.json").readText())
        val d = PageDoc(dir.name, dir)
        d.title = j.optString("title", "Unbenannt")
        d.sourceUri = j.optString("src").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        d.writable = j.optBoolean("writable")
        d.fromMulti = j.optBoolean("multi")
        d.pageW = j.getDouble("w").toFloat()
        d.pageH = j.getDouble("h").toFloat()
        d.scale = j.optInt("scale", 100)
        d.unit = runCatching { MUnit.valueOf(j.optString("unit")) }.getOrDefault(MUnit.M)
        d.dpi = j.optInt("dpi", 150)
        d.bgColor = j.optInt("bg", Pal.PAPER)
        d.backupDone = j.optBoolean("backup")
        d.items = itemsFromJson(j.optJSONArray("items"))
        d.dirty = true
        return d
    }

    // ---- Zuletzt geöffnet ----

    class Recent(val uri: Uri, val name: String)

    fun recent(p: SharedPreferences): List<Recent> {
        val a = try { JSONArray(p.getString("recent", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { val o = a.getJSONObject(it); Recent(Uri.parse(o.getString("u")), o.getString("n")) }
    }

    fun addRecent(p: SharedPreferences, uri: Uri, name: String) {
        val list = recent(p).filter { it.uri != uri }.toMutableList()
        list.add(0, Recent(uri, name))
        saveRecent(p, list.take(5))
    }

    fun removeRecent(p: SharedPreferences, uri: Uri) = saveRecent(p, recent(p).filter { it.uri != uri })

    private fun saveRecent(p: SharedPreferences, list: List<Recent>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("u", it.uri.toString()).put("n", it.name)) }
        p.edit().putString("recent", a.toString()).apply()
    }
}
