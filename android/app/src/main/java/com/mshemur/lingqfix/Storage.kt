package com.mshemur.lingqfix

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where reading stopped in each lesson, and how far it got (0..1, never goes down). */
class ReadingProgress(ctx: Context) {
    private val p = ctx.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun pos(lang: String, id: Int): Int? = p.getInt("pos.${lang}_$id", -1).takeIf { it >= 0 }

    fun read(lang: String, id: Int): Float = p.getFloat("read.${lang}_$id", 0f)

    fun save(lang: String, id: Int, first: Int, read: Float) {
        val e = p.edit().putInt("pos.${lang}_$id", first)
        if (read > read(lang, id)) e.putFloat("read.${lang}_$id", read.coerceAtMost(1f))
        e.apply()
    }
}

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
    /** Language the reader translates into. */
    var trLang: String
        get() = read("trLang", "ru")
        set(v) = write("trLang", v.trim().ifBlank { "ru" })
    /** Reader: "popup" = window with translations, "inline" = translation above the word. */
    var readerMode: String
        get() = read("readerMode", "popup")
        set(v) = write("readerMode", v)
    /** "lingq" or "gemini": who translates words and sentences in the reader. */
    var translator: String
        get() = read("translator", "lingq")
        set(v) = write("translator", v)
    var geminiKey: String
        get() = read("geminiKey")
        set(v) = write("geminiKey", v.trim())
    var geminiModel: String
        // gemini-2.5-* are retired for new users: move old settings to the current default
        get() = read("geminiModel", Gemini.DEFAULT_MODEL).let { if (it.startsWith("gemini-2.5")) Gemini.DEFAULT_MODEL else it }
        set(v) = write("geminiModel", v.trim().ifBlank { Gemini.DEFAULT_MODEL })
    /** Model for words, phrases and sentence translations (many small requests); blank = the main model. */
    var geminiFastModel: String
        get() = read("geminiFastModel", "")
        set(v) = write("geminiFastModel", v.trim())
    var promptWord: String
        get() = read("promptWord", Prompts.WORD).ifBlank { Prompts.WORD }
        set(v) = p.edit().putString("promptWord", v).apply()
    var promptSentence: String
        get() = read("promptSentence", Prompts.SENTENCE).ifBlank { Prompts.SENTENCE }
        set(v) = p.edit().putString("promptSentence", v).apply()
    var promptRetranslate: String
        get() = read("promptRetranslate", Prompts.RETRANSLATE).ifBlank { Prompts.RETRANSLATE }
        set(v) = p.edit().putString("promptRetranslate", v).apply()
    var promptChatSentence: String
        get() = read("promptChatSentence", Prompts.CHAT_SENTENCE).ifBlank { Prompts.CHAT_SENTENCE }
        set(v) = p.edit().putString("promptChatSentence", v).apply()
    /** Reader page colours ("system" or a key of READER_THEMES), word highlight colour, screen timeout. */
    var readerTheme: String
        get() = read("readerTheme", "system")
        set(v) = write("readerTheme", v)
    var highlight: String
        get() = read("highlight", "green")
        set(v) = write("highlight", v)
    var keepAwake: String
        get() = read("keepAwake", "10")
        set(v) = write("keepAwake", v)
    var promptClean: String
        get() = read("promptClean", Prompts.CLEAN).ifBlank { Prompts.CLEAN }
        set(v) = p.edit().putString("promptClean", v).apply()
    /** Import: quick clean right after text is pasted / read. */
    var autoClean: Boolean
        get() = read("autoClean", "1") == "1"
        set(v) = write("autoClean", if (v) "1" else "0")
    var promptChatWord: String
        get() = read("promptChatWord", Prompts.CHAT_WORD).ifBlank { Prompts.CHAT_WORD }
        set(v) = p.edit().putString("promptChatWord", v).apply()
    /** Tablet: word window docked at the right of the reader. */
    var dockPanel: Boolean
        get() = read("dockPanel", "0") == "1"
        set(v) = write("dockPanel", if (v) "1" else "0")
    /** Sentence mode shows the translation under the sentence. */
    var smTranslation: Boolean
        get() = read("smTranslation", "1") == "1"
        set(v) = write("smTranslation", if (v) "1" else "0")
    var promptChatText: String
        get() = read("promptChatText", Prompts.CHAT_TEXT).ifBlank { Prompts.CHAT_TEXT }
        set(v) = p.edit().putString("promptChatText", v).apply()
    var readerFont: Int
        get() = p.getInt("readerFont", 19)
        set(v) = p.edit().putInt("readerFont", v).apply()
}

/** Copies of lessons taken before every write, so a lesson can be put back. Kept [KEEP_DAYS] days. */
class Backups(ctx: Context) {
    companion object { const val KEEP_DAYS = 3 }
    private val dir = File(ctx.filesDir, "backups").apply { mkdirs() }

    private fun files() = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }

    /** File names start with the save time in ms. */
    private fun savedAt(f: File) = f.name.substringBefore('_').toLongOrNull() ?: f.lastModified()

    fun prune() {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        files().filter { savedAt(it) < limit }.forEach { it.delete() }
    }

    fun deleteAll() = files().forEach { it.delete() }

    class Entry(val file: File, val lang: String, val id: Int, val title: String, val time: Long)

    fun save(lang: String, lesson: Lesson, paragraphs: JSONArray) {
        val t = System.currentTimeMillis()
        val o = JSONObject().put("id", lesson.id).put("lang", lang).put("title", lesson.title)
            .put("time", t).put("paragraphs", paragraphs)
        File(dir, "${t}_${lesson.id}.json").writeText(o.toString())
    }

    fun list(): List<Entry> = prune().let { files() }
        .mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                Entry(f, o.getString("lang"), o.getInt("id"), o.optString("title"), o.optLong("time"))
            }.getOrNull()
        }
        .sortedByDescending { it.time }

    fun paragraphs(e: Entry): JSONArray = JSONObject(e.file.readText()).getJSONArray("paragraphs")
}
