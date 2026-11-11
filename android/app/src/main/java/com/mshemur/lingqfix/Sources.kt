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
            val text = htmlToText(html)
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
    Regex("[^.!?…]+(?:[.!?…]+[\"»“”')\\]]*|$)\\s*").findAll(p).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()

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
