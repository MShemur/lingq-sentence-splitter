package com.mshemur.lingqfix

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun read(k: String, d: String = "") = p.getString(k, d) ?: d
    private fun write(k: String, v: String) = p.edit().putString(k, v).apply()

    var apiKey: String
        get() = read("apiKey")
        set(v) = write("apiKey", v.trim())
    var lang: String
        get() = read("lang", "de")
        set(v) = write("lang", v.trim().ifBlank { "de" })
    var ghToken: String
        get() = read("ghToken")
        set(v) = write("ghToken", v.trim())
}

/** Copies of lessons taken before every write, so a lesson can be put back. */
class Backups(ctx: Context) {
    private val dir = File(ctx.filesDir, "backups").apply { mkdirs() }

    class Entry(val file: File, val lang: String, val id: Int, val title: String, val time: Long)

    fun save(lang: String, lesson: Lesson, paragraphs: JSONArray) {
        val t = System.currentTimeMillis()
        val o = JSONObject().put("id", lesson.id).put("lang", lang).put("title", lesson.title)
            .put("time", t).put("paragraphs", paragraphs)
        File(dir, "${t}_${lesson.id}.json").writeText(o.toString())
    }

    fun list(): List<Entry> = dir.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") }
        .mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                Entry(f, o.getString("lang"), o.getInt("id"), o.optString("title"), o.optLong("time"))
            }.getOrNull()
        }
        .sortedByDescending { it.time }

    fun paragraphs(e: Entry): JSONArray = JSONObject(e.file.readText()).getJSONArray("paragraphs")
}
