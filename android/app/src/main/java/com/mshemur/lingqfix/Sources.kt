package com.mshemur.lingqfix

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** A piece of text that becomes one LingQ lesson (or several, if it is long). */
class SourceText(val title: String, val text: String)

class Book(val title: String, val chapters: List<SourceText>, val cover: Pair<ByteArray, String>? = null)

object Sources {
    /** Reads a .txt/.md/.epub file. Returns the display name and its texts (one per EPUB chapter). */
    /** Also the cover picture of an EPUB (bytes, MIME type) when it has one. */
    fun read(ctx: Context, uri: Uri): Triple<String, List<SourceText>, Pair<ByteArray, String>?> {
        val name = displayName(ctx, uri) ?: (uri.lastPathSegment ?: "file")
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ApiException("файл недоступен")
        val isZip = bytes.size > 3 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        if (name.endsWith(".epub", true) || isZip) {
            val book = Epub.read(bytes)
            return Triple(book.title, book.chapters, book.cover)
        }
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return Triple(name, listOf(SourceText(name.substringBeforeLast('.'), text)), null)
    }

    fun name(ctx: Context, uri: Uri): String = displayName(ctx, uri) ?: (uri.lastPathSegment ?: "file")

    fun isPdf(ctx: Context, uri: Uri, name: String): Boolean = name.endsWith(".pdf", true) || runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { s -> String(ByteArray(4).also { s.read(it) }, Charsets.US_ASCII) == "%PDF" } == true
    }.getOrDefault(false)

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Splits a long text at paragraph boundaries into parts of about [maxWords] words. */
    fun splitLong(text: String, maxWords: Int): List<String> {
        if (maxWords <= 0 || countWords(text) <= maxWords) return listOf(text)
        val paras = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var words = 0
        for (p in paras) {
            val pw = countWords(p)
            if (words > 0 && words + pw > maxWords) {
                parts += cur.toString().trim(); cur.clear(); words = 0
            }
            cur.append(p).append("\n\n")
            words += pw
        }
        if (cur.isNotBlank()) parts += cur.toString().trim()
        return parts
    }
}

/** Minimal EPUB reader: OPF spine order, XHTML -> plain text with paragraphs. Images are skipped. */
object Epub {
    fun read(bytes: ByteArray): Book {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) files[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        val container = files["META-INF/container.xml"]?.let { String(it, Charsets.UTF_8) }
            ?: throw ApiException("это не EPUB (нет META-INF/container.xml)")
        val opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(container)?.groupValues?.get(1)
            ?: throw ApiException("EPUB без OPF")
        val opf = files[opfPath]?.let { String(it, Charsets.UTF_8) } ?: throw ApiException("EPUB: нет $opfPath")
        val base = opfPath.substringBeforeLast('/', "")

        val bookTitle = Regex("(?is)<dc:title[^>]*>(.*?)</dc:title>").find(opf)?.groupValues?.get(1)
            ?.let { clean(stripTags(it)) }?.ifBlank { null } ?: "Книга"

        val manifest = HashMap<String, String>()
        Regex("<item\\b[^>]*>").findAll(opf).forEach { m ->
            val id = attr(m.value, "id")
            val href = attr(m.value, "href")
            val type = attr(m.value, "media-type").orEmpty()
            if (id != null && href != null && type.contains("html")) manifest[id] = href
        }
        val spine = Regex("<itemref\\b[^>]*>").findAll(opf).mapNotNull { attr(it.value, "idref") }.toList()

        val chapters = spine.mapNotNull { manifest[it] }.mapNotNull { href ->
            val path = resolve(base, Uri.decode(href.substringBefore('#')))
            val html = files[path]?.let { String(it, Charsets.UTF_8) } ?: return@mapNotNull null
            val text = htmlToMarkedText(html)
            if (text.isBlank()) null else SourceText(chapterTitle(html), text)
        }
        if (chapters.isEmpty()) throw ApiException("в EPUB не найден текст")
        return Book(bookTitle, chapters, cover(opf, files, base))
    }

    /** EPUB 3 "cover-image", EPUB 2 <meta name="cover">, or an image called "cover". */
    private fun cover(opf: String, files: Map<String, ByteArray>, base: String): Pair<ByteArray, String>? {
        val items = Regex("<item\\b[^>]*>").findAll(opf).map { it.value }.toList()
        fun isImage(t: String) = attr(t, "media-type")?.startsWith("image") == true
        val metaId = Regex("<meta\\b[^>]*\\bname\\s*=\\s*[\"']cover[\"'][^>]*>").find(opf)?.value?.let { attr(it, "content") }
        val item = items.firstOrNull { attr(it, "properties")?.contains("cover-image") == true }
            ?: metaId?.let { id -> items.firstOrNull { attr(it, "id") == id && isImage(it) } }
            ?: items.firstOrNull { isImage(it) && ((attr(it, "id").orEmpty()) + attr(it, "href").orEmpty()).contains("cover", true) }
            ?: return null
        val href = attr(item, "href") ?: return null
        val bytes = files[resolve(base, Uri.decode(href))] ?: return null
        return bytes to (attr(item, "media-type") ?: "image/jpeg")
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*([\"'])(.*?)\\1").find(tag)?.groupValues?.get(2)

    private fun resolve(base: String, href: String): String {
        val out = ArrayDeque<String>()
        for (p in (if (base.isEmpty()) href else "$base/$href").split('/')) when (p) {
            "", "." -> {}
            ".." -> out.removeLastOrNull()
            else -> out.addLast(p)
        }
        return out.joinToString("/")
    }

    private fun chapterTitle(html: String): String {
        val h = Regex("(?is)<h[1-3]\\b[^>]*>(.*?)</h[1-3]>").find(html)?.groupValues?.get(1)
            ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?: ""
        return clean(stripTags(h))
    }

    private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), " ")
    private fun clean(s: String) = unescape(s).replace(Regex("[\\s\\u00A0]+"), " ").trim()

    private const val BLOCK = "p|div|h[1-6]|li|blockquote|tr|section|article|pre|dd|dt|figcaption"

    // --- bold / italic of HTML (EPUB, pasted text) -> **…** / *…*, the way the reader shows them ---

    private const val B_ON = "\uE000"
    private const val B_OFF = "\uE001"
    private const val I_ON = "\uE002"
    private const val I_OFF = "\uE003"
    private val VOID = setOf("br", "img", "hr", "meta", "link", "input", "source", "area", "col", "wbr", "base", "embed", "param", "track")

    /** Private-use marks where bold / italic starts and ends (tags b strong i em, inline font-weight / font-style). */
    private fun markEmphasis(html: String): String {
        if (!Regex("(?i)<(b|strong|i|em)\\b|font-(weight|style)").containsMatchIn(html)) return html
        class Open(val tag: String, val b: Boolean, val i: Boolean, val shut: String)
        val stack = ArrayList<Open>()
        val out = StringBuilder()
        var pos = 0
        for (m in Regex("<(/?)([a-zA-Z][a-zA-Z0-9:-]*)([^>]*)>").findAll(html)) {
            out.append(html, pos, m.range.first)
            pos = m.range.last + 1
            val tag = m.groupValues[2].lowercase()
            val rest = m.groupValues[3]
            if (m.groupValues[1] == "/") {
                val k = stack.indexOfLast { it.tag == tag }
                if (k >= 0) while (stack.size > k) out.append(stack.removeAt(stack.lastIndex).shut)
                out.append(m.value)
                continue
            }
            out.append(m.value)
            if (tag in VOID || rest.trimEnd().endsWith("/")) continue
            val b = stack.lastOrNull()?.b ?: false
            val i = stack.lastOrNull()?.i ?: false
            val st = attr(rest, "style").orEmpty().lowercase()
            val fw = Regex("font-weight\\s*:\\s*([a-z0-9]+)").find(st)?.groupValues?.get(1)
            val fs = Regex("font-style\\s*:\\s*([a-z]+)").find(st)?.groupValues?.get(1)
            // Google Docs wraps everything in <b style="font-weight:normal">
            val cb = if (fw != null) fw == "bold" || fw == "bolder" || (fw.toIntOrNull() ?: 0) >= 600 else if (tag == "b" || tag == "strong") true else b
            val ci = if (fs != null) fs == "italic" || fs == "oblique" else if (tag == "i" || tag == "em") true else i
            val open = (if (cb != b) (if (cb) B_ON else B_OFF) else "") + (if (ci != i) (if (ci) I_ON else I_OFF) else "")
            val shut = (if (ci != i) (if (ci) I_OFF else I_ON) else "") + (if (cb != b) (if (cb) B_OFF else B_ON) else "")
            out.append(open)
            stack += Open(tag, cb, ci, shut)
        }
        out.append(html, pos, html.length)
        return out.toString()
    }

    private val LN = Regex("[\\p{L}\\p{N}]")
    private val SENT_END = Regex("([.!?…]+[\"'»«“”„’)\\]]*)(\\s+)(?=\\S)")

    /** The marks become ** / * around whole words; at a sentence end the markers close and open again (LingQ splits there). */
    private fun marksToStars(t: String): String {
        if (t.none { it in '\uE000'..'\uE003' }) return t
        class Run(val s: StringBuilder, val b: Boolean, val i: Boolean)
        val runs = ArrayList<Run>()
        var b = 0; var i = 0
        for (ch in t) when (ch) {
            '\uE000' -> b++
            '\uE001' -> b = maxOf(0, b - 1)
            '\uE002' -> i++
            '\uE003' -> i = maxOf(0, i - 1)
            else -> {
                val nl = ch == '\n'
                val rb = !nl && b > 0; val ri = !nl && i > 0
                val last = runs.lastOrNull()
                if (last != null && last.b == rb && last.i == ri && !nl && last.s.toString() != "\n") last.s.append(ch)
                else runs += Run(StringBuilder().append(ch), rb, ri)
            }
        }
        // "<b>Das</b> <b>Haus</b>": the space between two runs of the same style joins them
        var k = 1
        while (k + 1 < runs.size) {
            val x = runs[k - 1]; val y = runs[k]; val z = runs[k + 1]
            if (!y.b && !y.i && y.s.isNotEmpty() && y.s.all { it == ' ' || it == '\t' } && x.b == z.b && x.i == z.i && (x.b || x.i)) {
                x.s.append(y.s).append(z.s); runs.removeAt(k); runs.removeAt(k)
            } else k++
        }
        val out = StringBuilder()
        runs.forEachIndexed { n, r ->
            val s = r.s.toString()
            if (!r.b && !r.i) { out.append(s); return@forEachIndexed }
            val lead = s.takeWhile { it.isWhitespace() }
            val rest = s.substring(lead.length)
            val trail = rest.takeLastWhile { it.isWhitespace() }
            val core = rest.substring(0, rest.length - trail.length)
            val next = runs.getOrNull(n + 1)?.s?.firstOrNull() ?: ' '
            val prev = out.lastOrNull() ?: ' '
            // only around letters, and never glued to a letter outside (mid-word bold would show its stars)
            if (!LN.containsMatchIn(core) || (lead.isEmpty() && LN.matches(prev.toString())) || (trail.isEmpty() && LN.matches(next.toString()))) {
                out.append(s); return@forEachIndexed
            }
            val mk = if (r.b && r.i) "***" else if (r.b) "**" else "*"
            out.append(lead).append(mk).append(core.replace(SENT_END, "\$1$mk\$2$mk")).append(mk).append(trail)
        }
        return out.toString()
    }

    /** HTML -> text with paragraphs, bold and italic kept as ** and *. */
    fun htmlToMarkedText(html: String): String = marksToStars(htmlToText(markEmphasis(html)))

    /** Pasted HTML -> text, only when it has bold / italic (else the plain clipboard text is better); null = none. */
    fun pastedEmphasis(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val marked = markEmphasis(html)
        if (marked.none { it in '\uE000'..'\uE003' }) return null
        val t = marksToStars(htmlToText(marked))
        return if ('*' in t) t else null
    }

    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<head\\b.*?</head>"), " ")
        s = s.replace(Regex("(?is)<(script|style)\\b.*?</\\1>"), " ")
        s = s.replace(Regex("\\s+"), " ")                                  // source line breaks are not paragraphs
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</?($BLOCK)\\b[^>]*>"), "\n\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = unescape(s)
        val lines = s.split('\n').map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        return lines.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "shy" to "",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»", "bdquo" to "„",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "middot" to "·",
    )

    fun unescape(s: String): String = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
        val g = m.groupValues[1]
        when {
            g.startsWith("#x") || g.startsWith("#X") ->
                g.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            g.startsWith("#") -> g.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED[g] ?: m.value
        }
    }
}


/** LingQ makes several lessons from one import above about this many words. */
const val LINGQ_MAX_WORDS = 6000

/** Sentences of a paragraph (LingQ's own split is not available for text added later; the fixer merges after it). */
fun splitSentences(p: String): List<String> =
    Regex("[^.!?…]+(?:[.!?…]+[\"«»“”')\\]]*|$)\\s*").findAll(p).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()

/**
 * Audio files the app keeps on the phone: downloaded podcast episodes, cut parts for recognition and import.
 * Files older than 30 days go at start; "Очистить кэш аудио" in the settings removes all. Lesson audio itself
 * is streamed from LingQ, so nothing is lost.
 */
object AudioCache {
    private fun dirs(ctx: android.content.Context) = listOfNotNull(
        java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "podcast"),
        java.io.File(ctx.filesDir, "podcast"),
        java.io.File(ctx.cacheDir, "stt"),
        java.io.File(ctx.cacheDir, "import"),
    )

    private fun files(ctx: android.content.Context) = dirs(ctx).flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() }

    fun size(ctx: android.content.Context): Long = files(ctx).sumOf { it.length() }

    fun clear(ctx: android.content.Context) {
        dirs(ctx).forEach { it.deleteRecursively() }
        AppLog.add("app", "Кэш аудио очищен")
    }

    fun purgeOld(ctx: android.content.Context, days: Int = 30) {
        val limit = System.currentTimeMillis() - days * 86_400_000L
        val old = files(ctx).filter { it.lastModified() < limit }
        old.forEach { it.delete() }
        if (old.isNotEmpty()) AppLog.add("app", "Кэш аудио: удалено старых файлов ${old.size}")
    }
}

/**
 * Markdown headings in LingQ paragraphs ("## Einleitung", e.g. from the AI cleaning): a sentence starting with
 * #..###### loses the marks and becomes its own paragraph with LingQ style "h1".."h6" (the reader draws it bold).
 * Paragraph indexes are renumbered. Returns the number of headings made.
 */
fun markdownHeadings(paras: org.json.JSONArray): Int {
    val mark = Regex("^(#{1,6})\\s*")
    var n = 0
    val out = org.json.JSONArray()
    for (i in 0 until paras.length()) {
        val p = paras.getJSONObject(i)
        val ss = p.optJSONArray("sentences")
        if (ss == null) { out.put(p); continue }
        var run = org.json.JSONArray()
        fun flush(style: String? = null) {
            if (run.length() == 0) return
            val q = org.json.JSONObject(p.toString()).put("sentences", run)
            if (style != null) q.put("style", style)
            out.put(q)
            run = org.json.JSONArray()
        }
        for (j in 0 until ss.length()) {
            val o = ss.getJSONObject(j)
            val m = mark.find(o.optString("text"))
            if (m == null) { run.put(o); continue }
            val t = o.optString("text").substring(m.range.last + 1).trim()
            if (t.isEmpty()) continue
            flush()
            o.put("text", t).put("cleanText", t)
            run.put(o)
            flush("h" + m.groupValues[1].length)
            n++
        }
        flush()
    }
    if (n == 0) return 0
    while (paras.length() > 0) paras.remove(paras.length() - 1)
    for (i in 0 until out.length()) paras.put(out.getJSONObject(i).put("index", i + 1))
    return n
}
