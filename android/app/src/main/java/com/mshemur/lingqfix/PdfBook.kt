package com.mshemur.lingqfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import com.google.mlkit.vision.text.Text as MlText

/*
 * PDF book: the PDF pages are shown as they are (rendered pictures), an invisible layer of the lesson's words lies
 * over them. The pages are recognised once (ML Kit, on the phone, also for PDFs with a text layer) and kept as JSON;
 * the recognised text is uploaded to LingQ as an ordinary lesson. When the lesson is opened, its sentences are
 * matched word by word with the recognised words, so each word box on a page knows its (sentence, token).
 *
 * Files (filesDir/pdf):
 *   lessons.json            {"<lang>_<lessonId>": {"doc": "<doc id>", "pages": [0-based page numbers]}}
 *   <doc>/doc.pdf           the PDF itself (doc id = first 16 hex chars of its SHA-1)
 *   <doc>/page_<n>.json     {"v":1, "page":n, "aspect": height/width,
 *                            "words": [[text, x, y, w, h, line, block], ...]}   boxes as parts (0..1) of the page
 *
 * Since v1.137 a new PDF book is NOT uploaded to LingQ: it stays on the phone as a "local book" (lesson id < 0,
 * = localId(doc, pages)); old LingQ PDF lessons (lessons.json) keep working. Files of a local book (N = -id):
 *   books.json              {"<id>": {"doc", "lang", "title", "pages": [0-based], "t": created ms}}
 *   <doc>/book_N.json       {"v":1, "paragraphs": [[sentence, ...], ...]}  split on the phone + Fixer merges
 *   <doc>/notes_N.json      pins / chosen meanings / phrases (as filesDir/reader/<lang>_<id>.json for lessons)
 *   <doc>/tr_N_<target>.json  sentence -> AI translation
 *   <doc>/ai_N.json         {"w:sent:token" | "p:sent:from:to": {best, options, note, pair}}  AI word / phrase meanings
 *   <doc>/cover_N.jpg       cover (the page chosen in the picker)
 * Sentence ids = order of the sentences in book_N.json (0-based); words are matched to the page boxes on open
 * with the same aligner as for LingQ lessons (exact here, the text was made from those very words).
 */

/** One recognised word of a PDF page: its text and box as parts (0..1) of the page width / height. */
class PdfWord(val t: String, val x: Float, val y: Float, val w: Float, val h: Float, val line: Int, val block: Int)

class PdfPage(val index: Int, val aspect: Float, val words: List<PdfWord>)

/** A lesson made from a PDF book: the doc and the pages the lesson covers (0-based). */
class PdfLink(val doc: String, val pages: List<Int>)

/** A word of the lesson on a PDF page: its sentence / token and box (0..1 of the page). Hyphenated words have two. */
class PdfTok(val ref: WordRef, val x: Float, val y: Float, val w: Float, val h: Float)

/** The lesson's words on the pages of its PDF. */
class PdfMap(val pages: List<Int>, val aspect: Map<Int, Float>, val toks: Map<Int, List<PdfTok>>) {
    /** Sentences on each page (sorted ids). */
    val sents: Map<Int, List<Int>> = toks.mapValues { (_, l) -> l.map { it.ref.sent }.distinct().sorted() }

    /** Pager index of the page with sentence [id] (or the first one after it); -1 = none. */
    fun pageOf(id: Int): Int {
        val i = pages.indexOfFirst { p -> sents[p]?.contains(id) == true }
        return if (i >= 0) i else pages.indexOfFirst { p -> (sents[p]?.lastOrNull() ?: -1) >= id }
    }
}

object PdfBooks {
    private fun root(ctx: Context) = File(ctx.filesDir, "pdf")
    private fun dir(ctx: Context, doc: String) = File(root(ctx), doc)
    fun file(ctx: Context, doc: String) = File(dir(ctx, doc), "doc.pdf")
    private fun pageFile(ctx: Context, doc: String, i: Int) = File(dir(ctx, doc), "page_$i.json")
    private fun linksFile(ctx: Context) = File(root(ctx), "lessons.json")

    /** Copies the PDF into the app's storage; its id comes from the content, so the same file is recognised once. */
    fun importFile(ctx: Context, uri: Uri): String {
        val tmp = File(root(ctx).apply { mkdirs() }, "tmp_${System.nanoTime()}.pdf")
        val md = MessageDigest.getInstance("SHA-1")
        (ctx.contentResolver.openInputStream(uri) ?: throw ApiException("файл недоступен")).use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n); out.write(buf, 0, n)
                }
            }
        }
        val id = md.digest().take(8).joinToString("") { "%02x".format(it) }
        val f = file(ctx, id)
        f.parentFile?.mkdirs()
        if (f.exists()) tmp.delete() else if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
        return id
    }

    // --- recognition ---

    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private suspend fun recognize(img: InputImage): MlText = suspendCancellableCoroutine { cont ->
        client.process(img)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /** Recognises [pages] (0-based) of the doc, page by page; pages recognised before come from the cache. */
    suspend fun recognise(ctx: Context, doc: String, pages: List<Int>, onPage: (Int, Int) -> Unit): List<PdfPage> {
        val out = ArrayList<PdfPage>()
        val pfd = withContext(Dispatchers.IO) { ParcelFileDescriptor.open(file(ctx, doc), ParcelFileDescriptor.MODE_READ_ONLY) }
        pfd.use {
            PdfRenderer(pfd).use { r ->
                pages.forEachIndexed { n, i ->
                    onPage(n + 1, pages.size)
                    val cached = withContext(Dispatchers.IO) { load(ctx, doc, i) }
                    if (cached != null) { out += cached; return@forEachIndexed }
                    val bmp = withContext(Dispatchers.IO) {
                        val pg = r.openPage(i)
                        try {
                            // about 1800 px wide: small print is still read well, a page takes ~1 s
                            val w = 1800
                            val h = (pg.height * w.toFloat() / pg.width).toInt().coerceIn(1, 4000)
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(android.graphics.Color.WHITE)
                                pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        } finally { pg.close() }
                    }
                    val bw = bmp.width; val bh = bmp.height
                    val t = try { recognize(InputImage.fromBitmap(bmp, 0)) } finally { bmp.recycle() }
                    val p = fromMlKit(i, bw, bh, t)
                    withContext(Dispatchers.IO) { save(ctx, doc, p) }
                    out += p
                }
            }
        }
        return out
    }

    private fun fromMlKit(i: Int, w: Int, h: Int, t: MlText): PdfPage {
        val words = ArrayList<PdfWord>()
        var line = 0
        t.textBlocks.forEachIndexed { bi, b ->
            for (l in b.lines) {
                for (e in l.elements) {
                    val r = e.boundingBox ?: continue
                    val s = e.text.trim()
                    if (s.isEmpty()) continue
                    words += PdfWord(s, r.left / w.toFloat(), r.top / h.toFloat(), r.width() / w.toFloat(), r.height() / h.toFloat(), line, bi)
                }
                line++
            }
        }
        return PdfPage(i, h / w.toFloat(), words)
    }

    private fun r5(f: Float) = Math.round(f * 100000.0) / 100000.0

    private fun save(ctx: Context, doc: String, p: PdfPage) {
        val ws = JSONArray()
        p.words.forEach { w -> ws.put(JSONArray().put(w.t).put(r5(w.x)).put(r5(w.y)).put(r5(w.w)).put(r5(w.h)).put(w.line).put(w.block)) }
        pageFile(ctx, doc, p.index).writeText(JSONObject().put("v", 1).put("page", p.index).put("aspect", r5(p.aspect)).put("words", ws).toString())
    }

    fun load(ctx: Context, doc: String, i: Int): PdfPage? = runCatching {
        val o = JSONObject(pageFile(ctx, doc, i).readText())
        val a = o.getJSONArray("words")
        PdfPage(i, o.getDouble("aspect").toFloat(), List(a.length()) { k ->
            val w = a.getJSONArray(k)
            PdfWord(w.getString(0), w.getDouble(1).toFloat(), w.getDouble(2).toFloat(), w.getDouble(3).toFloat(), w.getDouble(4).toFloat(), w.getInt(5), w.getInt(6))
        })
    }.getOrNull()

    // --- lesson text and word matching ---

    private val HY = setOf('-', '­', '¬', '‐')
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:[-'’][\\p{L}\\p{N}]+)*")

    private class W(val page: PdfPage, val i: Int) { val w get() = page.words[i] }

    private fun flat(pages: List<PdfPage>): List<W> = pages.flatMap { p -> p.words.indices.map { W(p, it) } }

    /** Word [k] ends its line with a hyphen and goes on in the next word ("Ent-" / "wicklung"). */
    private fun joinsNext(ws: List<W>, k: Int): Boolean {
        val a = ws[k]; val b = ws.getOrNull(k + 1) ?: return false
        val t = a.w.t
        if (t.length < 2 || t.last() !in HY || !t[t.length - 2].isLetter() || !b.w.t.first().isLetter()) return false
        return !(a.page === b.page && a.w.line == b.w.line)
    }

    /** "Nord-" / "Süd": the hyphen stays before a capital letter, "Ent-" / "wicklung" loses it. */
    private fun keepsHyphen(next: String) = !next.first().isLowerCase()

    /** Lesson text of recognised pages: blocks become paragraphs unless a sentence goes on; line-end hyphens joined. */
    fun text(pages: List<PdfPage>): String {
        val ws = flat(pages)
        val sb = StringBuilder()
        var paraWords = 0
        for (k in ws.indices) {
            val a = ws[k]; val t = a.w.t
            val b = ws.getOrNull(k + 1)
            if (joinsNext(ws, k)) {
                sb.append(t.dropLast(1)); if (keepsHyphen(b!!.w.t)) sb.append('-')
                continue
            }
            sb.append(t); paraWords++
            if (b == null) break
            if (a.page === b.page && a.w.block == b.w.block) { sb.append(' '); continue }
            // a new block: a new paragraph, unless the sentence goes on (no end mark and a small letter or a long block)
            val ends = t.last() in ".!?…:;\"»«“”)]"
            val goesOn = !ends && (b.w.t.first().isLowerCase() || paraWords >= 12)
            if (goesOn) sb.append(' ') else { sb.append("\n\n"); paraWords = 0 }
        }
        return sb.toString().trim()
    }

    private fun norm(s: String) = s.lowercase().replace('’', '\'')

    private class Piece(val page: PdfPage, val word: Int, val from: Int, val to: Int)
    private class Wu(val norm: String, val parts: List<Piece>)

    /** The words of the pages as the lesson tokenises them; a word hyphenated at a line end is one unit with two parts. */
    private fun units(pages: List<PdfPage>): List<Wu> {
        val ws = flat(pages)
        val out = ArrayList<Wu>()
        var carry: Wu? = null
        for (k in ws.indices) {
            val a = ws[k]
            val ms = WORD.findAll(a.w.t).toList()
            val join = joinsNext(ws, k)
            ms.forEachIndexed { mi, m ->
                var u = Wu(norm(m.value), listOf(Piece(a.page, a.i, m.range.first, m.range.last + 1)))
                val c = carry
                if (mi == 0 && c != null) {
                    u = Wu(c.norm + (if (keepsHyphen(m.value)) "-" else "") + u.norm, c.parts + u.parts)
                    carry = null
                }
                if (join && mi == ms.lastIndex) carry = u else out += u
            }
            carry?.let { if (!join) { out += it; carry = null } }
        }
        carry?.let { out += it }
        return out
    }

    /** For each word of [b] the index of the same word in [a], or -1. Greedy, re-synchronising after gaps on both sides. */
    private fun align(a: List<String>, b: List<String>, start: Int = 0): IntArray {
        val ids = HashMap<String, Int>()
        val ai = IntArray(a.size) { ids.getOrPut(a[it]) { ids.size } }
        val bi = IntArray(b.size) { ids.getOrPut(b[it]) { ids.size } }
        val res = IntArray(b.size) { -1 }
        fun same(x: Int, y: Int) = x < ai.size && y < bi.size && ai[x] == bi[y]
        fun sure(x: Int, y: Int) = same(x, y) && (x + 1 >= ai.size || y + 1 >= bi.size || same(x + 1, y + 1))
        // the start: where the first words of [b] stand together in [a]
        var i = start
        run anchor@{
            for (j0 in 0 until minOf(8, bi.size)) for (x in start until ai.size) {
                if (sure(x, j0) && (j0 + 2 >= bi.size || same(x + 2, j0 + 2))) { i = maxOf(start, x - j0); return@anchor }
            }
        }
        var j = 0
        while (i < ai.size && j < bi.size) {
            if (ai[i] == bi[j]) { res[j] = i; i++; j++; continue }
            var hk = -1; var hl = -1
            search@ for (d in 1..MAX_D) {
                for (l in 0..minOf(d, MAX_B)) {
                    val k = d - l
                    if (k > MAX_A) continue
                    if (sure(i + k, j + l)) { hk = k; hl = l; break@search }
                }
            }
            if (hk < 0) {
                // no way back in sight: a misread word in the same place (OCR), if it looks alike
                if (similar(a[i], b[j])) res[j] = i
                i++; j++
            } else { i += hk; j += hl }
        }
        return res
    }

    private const val MAX_A = 200      // PDF words the lesson may lack (page headers, footnotes cleaned away)
    private const val MAX_B = 40       // lesson words the PDF may lack
    private const val MAX_D = 240

    private fun similar(x: String, y: String) = x.isNotEmpty() && y.isNotEmpty() && x[0] == y[0] && kotlin.math.abs(x.length - y.length) <= 2

    /** Before the upload: the pages each lesson text (in order) covers; null = not found. */
    fun ranges(pages: List<PdfPage>, texts: List<String>): List<List<Int>?> {
        val us = units(pages)
        val a = us.map { it.norm }
        var cursor = 0
        return texts.map { t ->
            val b = WORD.findAll(t).map { norm(it.value) }.toList()
            val res = align(a, b, cursor)
            val hit = res.filter { it >= 0 }
            if (hit.isEmpty()) null else {
                cursor = hit.max() + 1
                val ps = hit.flatMap { k -> us[k].parts.map { it.page.index } }.toSortedSet()
                // pages without words between them (pictures) belong to the lesson too
                pages.map { it.index }.filter { it in ps.first()..ps.last() }
            }
        }
    }

    /** The lesson's words on its pages ([sents] = the lesson's sentences as the reader has them). */
    fun map(ctx: Context, link: PdfLink, sents: List<Sent>): PdfMap {
        val pages = link.pages.mapNotNull { load(ctx, link.doc, it) }
        val us = units(pages)
        val refs = ArrayList<WordRef>()
        val b = ArrayList<String>()
        for (st in sents) for (t in st.tokens) if (t.isWord) { refs += WordRef(st.id, t.index); b += norm(t.text) }
        val res = align(us.map { it.norm }, b)
        val toks = HashMap<Int, ArrayList<PdfTok>>()
        res.forEachIndexed { j, k ->
            if (k < 0) return@forEachIndexed
            for (p in us[k].parts) {
                val w = p.page.words[p.word]
                val n = w.t.length.coerceAtLeast(1)
                toks.getOrPut(p.page.index) { ArrayList() } +=
                    PdfTok(refs[j], w.x + w.w * p.from / n, w.y, w.w * (p.to - p.from) / n, w.h)
            }
        }
        AppLog.add("pdf", "PDF-урок: слов ${b.size}, найдено на страницах ${res.count { it >= 0 }}")
        return PdfMap(pages.map { it.index }, pages.associate { it.index to it.aspect }, toks)
    }

    // --- which lessons are PDF books ---

    @Synchronized
    fun register(ctx: Context, lang: String, lesson: Int, doc: String, pages: List<Int>) {
        val f = linksFile(ctx)
        val o = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
        o.put("${lang}_$lesson", JSONObject().put("doc", doc).put("pages", JSONArray(pages)))
        f.parentFile?.mkdirs()
        f.writeText(o.toString())
        AppLog.add("pdf", "Урок $lesson — PDF-книга $doc, стр. ${pagesLabel(pages)}")
    }

    fun link(ctx: Context, lang: String, lesson: Int): PdfLink? = runCatching {
        if (lesson < 0) return localBook(ctx, lesson)?.let { PdfLink(it.doc, it.pages) }?.takeIf { file(ctx, it.doc).exists() }
        val f = linksFile(ctx)
        if (!f.exists()) return null
        val o = JSONObject(f.readText()).optJSONObject("${lang}_$lesson") ?: return null
        val a = o.getJSONArray("pages")
        PdfLink(o.getString("doc"), List(a.length()) { a.getInt(it) })
    }.getOrNull()?.takeIf { file(ctx, it.doc).exists() }

    // --- local books (since v1.137): no LingQ lesson, everything stays on the phone ---

    private fun booksFile(ctx: Context) = File(root(ctx), "books.json")
    private fun n(id: Int) = -id.toLong()

    /** Sentences of a local book: [[sentence, ...] per paragraph]. */
    private fun sentFile(ctx: Context, b: LocalPdfBook) = File(dir(ctx, b.doc), "book_${n(b.id)}.json")
    /** Pins / chosen meanings / phrases of the book (as LessonNotes keeps them for a LingQ lesson). */
    fun notesFile(ctx: Context, b: LocalPdfBook) = File(dir(ctx, b.doc), "notes_${n(b.id)}.json")
    /** Sentence translations: sentence -> translation, per target language. */
    fun trFile(ctx: Context, b: LocalPdfBook, target: String) = File(dir(ctx, b.doc), "tr_${n(b.id)}_$target.json")
    /** AI meanings of words ("w:sent:token") and phrases ("p:sent:from:to"), so nothing is asked twice. */
    fun aiFile(ctx: Context, b: LocalPdfBook) = File(dir(ctx, b.doc), "ai_${n(b.id)}.json")
    private fun coverFile(ctx: Context, b: LocalPdfBook) = File(dir(ctx, b.doc), "cover_${n(b.id)}.jpg")

    /** Id of a local book: negative (LingQ lesson ids are positive), the same for the same file and pages. */
    fun localId(doc: String, pages: List<Int>): Int = -(((doc + ":" + pages.joinToString(",")).hashCode() and 0x3fffffff) + 1)

    @Synchronized
    private fun readBooks(ctx: Context): JSONObject = runCatching { JSONObject(booksFile(ctx).readText()) }.getOrElse { JSONObject() }

    @Synchronized
    private fun writeBooks(ctx: Context, o: JSONObject) {
        booksFile(ctx).parentFile?.mkdirs()
        booksFile(ctx).writeText(o.toString())
    }

    private fun bookOf(ctx: Context, id: Int, o: JSONObject): LocalPdfBook {
        val a = o.optJSONArray("pages") ?: JSONArray()
        val b = LocalPdfBook(id, o.getString("doc"), o.optString("lang"), o.optString("title"), List(a.length()) { a.getInt(it) }, o.optLong("t"))
        return b.also { it.cover = coverFile(ctx, it).takeIf { f -> f.exists() }?.let { f -> Uri.fromFile(f).toString() }.orEmpty() }
    }

    fun localBook(ctx: Context, id: Int): LocalPdfBook? = runCatching {
        readBooks(ctx).optJSONObject(id.toString())?.let { bookOf(ctx, id, it) }
    }.getOrNull()

    /** Local books of a language, the newest first. */
    fun localBooks(ctx: Context, lang: String): List<LocalPdfBook> = runCatching {
        val o = readBooks(ctx)
        o.keys().asSequence().mapNotNull { k ->
            val id = k.toIntOrNull() ?: return@mapNotNull null
            o.optJSONObject(k)?.takeIf { it.optString("lang") == lang }?.let { bookOf(ctx, id, it) }
        }.sortedByDescending { it.created }.toList()
    }.getOrDefault(emptyList())

    /**
     * Sentences of the recognised text, split on the phone: paragraphs as in [text], sentences at . ! ? …, then the
     * fragments merged back with the rules LingQ's split is fixed with (Fixer: abbreviations, ordinals, small letters).
     */
    fun sentences(text: String): List<List<String>> {
        val paras = JSONArray()
        for (pt in text.split(Regex("\\n\\s*\\n"))) {
            val ss = splitSentences(pt.replace(Regex("\\s+"), " ").trim())
            if (ss.isEmpty()) continue
            val arr = JSONArray()
            ss.forEach { arr.put(JSONObject().put("text", it)) }
            paras.put(JSONObject().put("sentences", arr))
        }
        Fixer.fix(paras)
        return (0 until paras.length()).map { i ->
            val ss = paras.getJSONObject(i).getJSONArray("sentences")
            (0 until ss.length()).map { ss.getJSONObject(it).optString("text").trim() }.filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }
    }

    /** Saves the recognised pages as a local book (replaces one of the same file and pages, keeping its notes). */
    fun saveLocal(ctx: Context, lang: String, doc: String, pages: List<Int>, title: String, cover: ByteArray?): LocalPdfBook {
        val recognised = pages.mapNotNull { load(ctx, doc, it) }
        val paras = sentences(text(recognised))
        if (paras.isEmpty()) throw ApiException("на выбранных страницах не нашлось текста")
        val id = localId(doc, pages)
        val b = LocalPdfBook(id, doc, lang, title.trim().ifBlank { "PDF" }, pages, System.currentTimeMillis())
        writeSentences(ctx, b, paras)
        if (cover != null) runCatching { coverFile(ctx, b).writeBytes(cover) }
        synchronized(this) {
            val o = readBooks(ctx)
            o.put(id.toString(), JSONObject().put("doc", doc).put("lang", lang).put("title", b.title).put("pages", JSONArray(pages)).put("t", b.created))
            writeBooks(ctx, o)
        }
        AppLog.add("pdf", "PDF-книга на телефоне $id «${b.title}»: стр. ${pagesLabel(pages)}, предложений ${paras.sumOf { it.size }}")
        return localBook(ctx, id) ?: b
    }

    fun readSentences(ctx: Context, b: LocalPdfBook): List<List<String>> {
        val a = JSONObject(sentFile(ctx, b).readText()).getJSONArray("paragraphs")
        return (0 until a.length()).map { i -> val p = a.getJSONArray(i); (0 until p.length()).map { p.getString(it) } }
    }

    fun writeSentences(ctx: Context, b: LocalPdfBook, paras: List<List<String>>) {
        val a = JSONArray()
        paras.forEach { p -> a.put(JSONArray().also { arr -> p.forEach { arr.put(it) } }) }
        sentFile(ctx, b).parentFile?.mkdirs()
        sentFile(ctx, b).writeText(JSONObject().put("v", 1).put("paragraphs", a).toString())
    }

    fun renameLocal(ctx: Context, id: Int, title: String) = synchronized(this) {
        val o = readBooks(ctx)
        o.optJSONObject(id.toString())?.put("title", title.trim())
        writeBooks(ctx, o)
    }

    /** Removes a local book with its notes and translations; the PDF and its pages go too when nothing else uses them. */
    fun deleteLocal(ctx: Context, id: Int) {
        val b = localBook(ctx, id) ?: return
        synchronized(this) {
            val o = readBooks(ctx)
            o.remove(id.toString())
            writeBooks(ctx, o)
        }
        val d = dir(ctx, b.doc)
        d.listFiles().orEmpty().filter { f -> f.name.matches(Regex("(book|notes|ai|cover|tr)_${n(id)}([._].*)?")) }.forEach { it.delete() }
        val usedLocal = readBooks(ctx).let { o -> o.keys().asSequence().any { o.optJSONObject(it)?.optString("doc") == b.doc } }
        val usedLingq = runCatching {
            val o = JSONObject(linksFile(ctx).readText())
            o.keys().asSequence().any { o.optJSONObject(it)?.optString("doc") == b.doc }
        }.getOrDefault(false)
        if (!usedLocal && !usedLingq) d.deleteRecursively()
        AppLog.add("pdf", "PDF-книга $id «${b.title}» удалена с телефона")
    }
}

/** A PDF book kept only on the phone (no LingQ lesson); [id] < 0 is its lesson id in the reader. */
class LocalPdfBook(val id: Int, val doc: String, val lang: String, val title: String, val pages: List<Int>, val created: Long) {
    /** Cover picture (file uri) or "". */
    var cover: String = ""

    fun lesson() = Lesson(id, title, cover, courseTitle = "PDF-книга")
}

/** Recognised pages waiting at the import screen to be saved as a local book. */
class PdfDraft(val doc: String, val pages: List<Int>, val title: String, val words: Int)

/** AI meanings of words / phrases of a local book, kept on the phone. */
internal class MeaningStore(private val file: File) {
    fun load(): Map<String, WordMeaning> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { k ->
            val m = o.getJSONObject(k)
            val a = m.optJSONArray("options") ?: JSONArray()
            WordMeaning(m.optString("best"), List(a.length()) { a.getString(it) }, m.optString("note"), m.optString("pair"))
        }
    }.getOrDefault(emptyMap())

    fun save(all: Map<String, WordMeaning>) {
        val o = JSONObject()
        all.forEach { (k, m) ->
            o.put(k, JSONObject().put("best", m.best).put("options", JSONArray(m.options)).put("note", m.note).put("pair", m.pair))
        }
        val s = o.toString()
        DiskQueue.write { file.parentFile?.mkdirs(); file.writeText(s) }
    }
}

/** Draws PDF pages; one page at a time (PdfRenderer allows only one open page). */
private class PdfRender(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val r = PdfRenderer(pfd)
    private var closed = false

    fun render(i: Int, width: Int): Bitmap? = synchronized(this) {
        if (closed || width <= 0 || i !in 0 until r.pageCount) return null
        val p = r.openPage(i)
        try {
            val h = (p.height * width.toFloat() / p.width).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(android.graphics.Color.WHITE)
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            b
        } finally { p.close() }
    }

    /**
     * Draws the part [x], [y], [w], [h] (px of the page laid out [pw] px wide) at [k] bitmap px per layout px:
     * the whole page with x = y = 0, w x h = the page, or the visible part of a zoomed page.
     */
    fun renderPart(i: Int, pw: Float, k: Float, x: Float, y: Float, w: Float, h: Float): Bitmap? = synchronized(this) {
        if (closed || pw <= 0f || k <= 0f || w <= 0f || h <= 0f || i !in 0 until r.pageCount) return null
        val bw = kotlin.math.ceil(w * k).toInt().coerceAtLeast(1)
        val bh = kotlin.math.ceil(h * k).toInt().coerceAtLeast(1)
        val p = r.openPage(i)
        try {
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            b.eraseColor(android.graphics.Color.WHITE)
            val sc = pw * k / p.width
            val mx = android.graphics.Matrix().apply { setScale(sc, sc); postTranslate(-x * k, -y * k) }
            p.render(b, null, mx, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            b
        } finally { p.close() }
    }

    fun close() = synchronized(this) {
        if (!closed) { closed = true; runCatching { r.close() }; runCatching { pfd.close() } }
    }
}

/** Zoom and pan of one page: screen = offset + layout position × scale. */
private class PdfZoom {
    var scale by mutableFloatStateOf(1f)
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)
    /** View size and where the unzoomed page lies in it. */
    var vw = 0f; var vh = 0f; var left = 0f; var top = 0f; var pw = 0f; var ph = 0f

    /** Keeps the page in view (a page taller than the view is moved up to its edges, also at the base zoom). */
    fun clamp() {
        if (scale <= 1.001f) scale = 1f
        ox = fit(ox, vw, left, pw); oy = fit(oy, vh, top, ph)
    }

    /** The page sticks out of the view up and down (one finger moves it that way also unzoomed). */
    fun tallY() = ph * scale > vh + 1f

    private fun fit(o: Float, view: Float, start: Float, len: Float): Float {
        val size = len * scale
        return if (size <= view) (view - size) / 2 - start * scale
        else o.coerceIn(view - (start + len) * scale, -start * scale)
    }

    fun zoomBy(f: Float, c: Offset) {
        val ns = (scale * f).coerceIn(1f, 5f)
        val k = ns / scale
        ox = c.x - (c.x - ox) * k; oy = c.y - (c.y - oy) * k
        scale = ns
        clamp()
    }

    fun panBy(d: Offset) { ox += d.x; oy += d.y; clamp() }

    /** Double tap on the page (not on a word): zoom in there, or back to the base zoom keeping that place. */
    fun toggle(local: Offset) {
        val c = Offset(ox + (left + local.x) * scale, oy + (top + local.y) * scale)
        zoomBy(if (scale > 1.01f) 1f / scale else 2.5f, c)
    }
}

/** The lesson shown as its PDF pages: swipe = next page, pinch = zoom, the words work as in the text reader. */
@Composable
fun PdfReader(s: ReaderSession, link: PdfLink) {
    val ctx = LocalContext.current
    val sents = s.sentences
    val map by produceState<PdfMap?>(null, sents, link) {
        value = withContext(Dispatchers.Default) {
            runCatching { PdfBooks.map(ctx, link, sents) }.onFailure { AppLog.error("pdf", "PDF-урок", it) }.getOrNull()
        }
    }
    val doc = remember(link.doc) { runCatching { PdfRender(PdfBooks.file(ctx, link.doc)) }.getOrNull() }
    DisposableEffect(doc) { onDispose { doc?.close() } }
    val m = map
    if (m == null || doc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (doc == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Text("PDF не открылся", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { s.showPdf = false }) { androidx.compose.material3.Text("Показать текст") }
            } else CircularProgressIndicator()
        }
        return
    }
    val pages = m.pages
    if (pages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            OutlinedButton(onClick = { s.showPdf = false }) { androidx.compose.material3.Text("Страниц нет — показать текст") }
        }
        return
    }
    val pager = rememberPagerState { pages.size }
    val zooms = remember(m) { HashMap<Int, PdfZoom>() }
    fun zoomOf(i: Int) = zooms.getOrPut(i) { PdfZoom() }

    LaunchedEffect(pager, m) {
        snapshotFlow { pager.settledPage }.collect { i ->
            val ss = pages.getOrNull(i)?.let { m.sents[it] } ?: return@collect
            if (s.jump == null && ss.isNotEmpty()) s.reached(ss.first(), ss.last())
        }
    }
    LaunchedEffect(pager.currentPage, m) {
        val i = pager.currentPage
        s.visibleSents = pages.getOrNull(i)?.let { m.sents[it] }.orEmpty().toSet()
        s.textProgress = (i + 1f) / pages.size
        s.pageLabel = "${i + 1} / ${pages.size}"
        s.pageCount = pages.size
        s.pageNow = i
    }
    LaunchedEffect(s.goPage) {
        val i = s.goPage ?: return@LaunchedEffect
        if (i in pages.indices && i != pager.currentPage) pager.scrollToPage(i)
        s.goPage = null
    }
    LaunchedEffect(s.jump, m) {
        val id = s.jump ?: return@LaunchedEffect
        val i = m.pageOf(id)
        if (i >= 0) pager.scrollToPage(i)
        s.jump = null
    }
    LaunchedEffect(s.syncTo, m) {
        val id = s.syncTo ?: return@LaunchedEffect
        val i = m.pageOf(id)
        if (i >= 0 && i != pager.currentPage) pager.animateScrollToPage(i)
        s.syncTo = null
    }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1,
            userScrollEnabled = zoomOf(pager.currentPage).scale <= 1.001f,
        ) { i ->
            val p = pages[i]
            PdfPageView(s, doc, p, m.aspect[p] ?: 1.414f, m.toks[p].orEmpty(), zoomOf(i), i == pager.currentPage)
        }
        // sentence translations (long press on a word, or "Переводы" below) of the sentences on this page
        val onPage = pages.getOrNull(pager.currentPage)?.let { m.sents[it] }.orEmpty()
        val shown = if (s.allSentences) onPage else onPage.filter { s.shownSentences[it] == true }
        if (shown.isNotEmpty()) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), RoundedCornerShape(12.dp))
                .heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            shown.forEach { SentenceTranslation(s, it) }
        }
    }
}

/** The sharper picture of the visible part of a zoomed page: [x], [y], [w], [h] in px of the laid out page. */
private class PdfPart(val bmp: Bitmap, val x: Float, val y: Float, val w: Float, val h: Float, val k: Float)

private const val BASE_PX = 6_000_000f
private const val SIDE_PX = 800_000f
private const val DETAIL_PX = 6_000_000f

@Composable
private fun PdfPageView(s: ReaderSession, doc: PdfRender, page: Int, aspect: Float, toks: List<PdfTok>, z: PdfZoom, current: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        // base zoom: the page fills the screen width (a portrait page is then taller than the screen and is moved up
        // and down), or the whole page fits in (menu "Страница PDF целиком")
        val pw = if (s.pdfFit == "page") minOf(vw, vh / aspect) else vw
        val ph = pw * aspect
        z.vw = vw; z.vh = vh; z.pw = pw; z.ph = ph
        z.left = maxOf(0f, (vw - pw) / 2); z.top = maxOf(0f, (vh - ph) / 2)
        LaunchedEffect(vw, vh, pw) { z.clamp() }
        // the whole page at the screen's sharpness (a page beside the current one: coarser, it is seen only while
        // swiping); the old picture stays until the new one is ready
        val baseK = minOf(1f, kotlin.math.sqrt((if (current) BASE_PX else SIDE_PX) / maxOf(1f, pw * ph)))
        val bmp by produceState<Bitmap?>(null, page, pw.toInt(), baseK) {
            val b = withContext(Dispatchers.IO) { runCatching { doc.renderPart(page, pw, baseK, 0f, 0f, pw, ph) }.getOrNull() }
            if (b != null) value = b
        }
        // zoomed in, after the gesture settled: the visible part drawn again at zoom x screen sharpness over the base
        // picture; the previous part stays (it lies right, only blurrier) until the new one is ready
        var detail by remember { mutableStateOf<PdfPart?>(null) }
        LaunchedEffect(page, current, vw, vh, pw, baseK) {
            if (!current) { detail = null; return@LaunchedEffect }
            snapshotFlow { Triple(z.scale, z.ox, z.oy) }.collectLatest { (sc, ox, oy) ->
                delay(250)
                val x = maxOf(0f, kotlin.math.floor(-ox / sc - z.left))
                val y = maxOf(0f, kotlin.math.floor(-oy / sc - z.top))
                val w = minOf(pw, kotlin.math.ceil((vw - ox) / sc - z.left)) - x
                val h = minOf(ph, kotlin.math.ceil((vh - oy) / sc - z.top)) - y
                if (w <= 0f || h <= 0f) { detail = null; return@collectLatest }
                val k = minOf(sc, kotlin.math.sqrt(DETAIL_PX / maxOf(1f, w * h)))
                if (k <= baseK * 1.1f) { detail = null; return@collectLatest }
                val d = detail
                if (d != null && d.x == x && d.y == y && d.w == w && d.h == h && kotlin.math.abs(d.k - k) < 0.001f) return@collectLatest
                val b = withContext(Dispatchers.IO) { runCatching { doc.renderPart(page, pw, k, x, y, w, h) }.getOrNull() }
                if (b != null) detail = PdfPart(b, x, y, w, h, k)
            }
        }
        val density = LocalDensity.current
        val wDp = with(density) { pw.toDp() }
        val hDp = with(density) { ph.toDp() }
        Box(Modifier.fillMaxSize().pointerInput(z) { zoomGestures(z) }) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = z.scale; scaleY = z.scale
                    translationX = z.ox; translationY = z.oy
                },
            ) {
                Box(Modifier.offset { IntOffset(z.left.roundToInt(), z.top.roundToInt()) }.size(wDp, hDp).background(Color.White)) {
                    bmp?.let { Image(it.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    detail?.let { d ->
                        val img = remember(d) { d.bmp.asImageBitmap() }
                        Image(
                            img, contentDescription = null,
                            Modifier.offset { IntOffset(d.x.roundToInt(), d.y.roundToInt()) }
                                .size(with(density) { d.w.toDp() }, with(density) { d.h.toDp() }),
                            contentScale = ContentScale.FillBounds,
                        )
                    }
                    PdfWordLayer(s, toks, z)
                }
            }
        }
    }
}

/**
 * Pinch = zoom, one finger on a zoomed page = pan (after the touch slop, so taps on words still work). Unzoomed, a page
 * taller than the screen is moved up and down; a sideways drag is left to the pager (next page).
 */
private suspend fun PointerInputScope.zoomGestures(z: PdfZoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var panning = false
        var ignore = false
        var moved = Offset.Zero
        while (true) {
            val ev = awaitPointerEvent()
            val down = ev.changes.filter { it.pressed }
            if (down.isEmpty()) break
            if (down.size >= 2) {
                val f = ev.calculateZoom()
                val c = ev.calculateCentroid(useCurrent = true)
                val pan = ev.calculatePan()
                if (f != 1f && c != Offset.Unspecified) z.zoomBy(f, c)
                z.panBy(pan)
                ev.changes.forEach { it.consume() }
                panning = true
            } else if (!ignore && (z.scale > 1.001f || z.tallY())) {
                val ch = down[0]
                if (ch.isConsumed) continue
                val d = ch.positionChange()
                if (!panning) {
                    moved += d
                    if (moved.getDistance() > viewConfiguration.touchSlop) {
                        // unzoomed: only an up / down drag moves the page, sideways turns it
                        if (z.scale > 1.001f || kotlin.math.abs(moved.y) > kotlin.math.abs(moved.x)) panning = true
                        else { ignore = true; continue }
                    }
                }
                if (panning) { z.panBy(d); ch.consume() }
            }
        }
    }
}

/** The words over a page: highlights, translations above the words, taps and long presses. */
@Composable
private fun PdfWordLayer(s: ReaderSession, toks: List<PdfTok>, z: PdfZoom) {
    val haptic = LocalHapticFeedback.current
    val hl = LocalHighlight.current
    val selBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val labelBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    val grey = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    val minLabel = with(LocalDensity.current) { 9.dp.toPx() }
    val byRef = remember(toks) { toks.groupBy { it.ref } }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(toks) { pdfGestures(s, toks, z, haptic) }
            .drawBehind {
                val pw = size.width; val ph = size.height
                fun box(t: PdfTok, c: Color) {
                    val m = t.h * ph * 0.12f
                    drawRoundRect(c, Offset(t.x * pw - m, t.y * ph - m), Size(t.w * pw + 2 * m, t.h * ph + 2 * m), CornerRadius(m * 2))
                }
                fun label(text: String, cx: Float, top: Float, lineH: Float, color: Color) {
                    // sized by the word's line on the page and zoomed with it; tiny print still gets 9 dp unzoomed
                    val fontPx = maxOf(lineH * 0.65f, minLabel)
                    val lay = measurer.measure(
                        AnnotatedString(text), style = TextStyle(color = color, fontSize = fontPx.toSp()),
                        overflow = TextOverflow.Ellipsis, maxLines = 1,
                        constraints = Constraints(maxWidth = (pw * 0.7f).toInt().coerceAtLeast(1)),
                    )
                    val lw = lay.size.width.toFloat(); val lh = lay.size.height.toFloat()
                    val pad = fontPx * 0.25f
                    val x = (cx - lw / 2).coerceIn(pad, (pw - lw - pad).coerceAtLeast(pad))
                    val y = (top - lh - lineH * 0.08f).coerceAtLeast(0f)
                    drawRoundRect(labelBg, Offset(x - pad, y), Size(lw + 2 * pad, lh), CornerRadius(lh * 0.3f))
                    drawText(lay, topLeft = Offset(x, y))
                }
                // phrases translated together: highlight over all their words, one translation above the first line
                val phr = ArrayList<Triple<String, List<PdfTok>, String>>()
                for ((k, v) in s.phrasePins) {
                    val p = k.split(':')
                    if (p.size != 3) continue
                    val sent = p[0].toIntOrNull() ?: continue
                    val from = p[1].toIntOrNull() ?: continue
                    val to = p[2].toIntOrNull() ?: continue
                    val ts = toks.filter { it.ref.sent == sent && it.ref.token in from..to }
                    if (ts.isNotEmpty()) phr += Triple(k, ts, v)
                }
                val inPhrase = phr.flatMap { it.second }.toHashSet()
                for (t in toks) {
                    val ref = t.ref
                    val c = when {
                        s.inSelection(ref) -> selBg
                        t in inPhrase || s.pins.containsKey(ref) || s.popup == ref || s.pairHighlighted(ref) -> hl.bg
                        else -> null
                    }
                    if (c != null) box(t, c)
                }
                for ((ref, tr) in s.pins) {
                    val t = byRef[ref]?.firstOrNull() ?: continue
                    if (t in inPhrase || !s.trVisible(ref.key())) continue
                    label(tr.ifEmpty { "…" }, (t.x + t.w / 2) * pw, t.y * ph, t.h * ph, if (s.provisional[ref] == true) grey else hl.text)
                }
                for ((k, ts, tr) in phr) {
                    if (!s.trVisible(k)) continue
                    val first = ts.minWith(compareBy({ it.ref.sent }, { it.ref.token }))
                    val line = ts.filter { kotlin.math.abs(it.y - first.y) < first.h * 0.5f }
                    val l = line.minOf { it.x }; val r = line.maxOf { it.x + it.w }
                    label(tr.ifEmpty { "…" }, (l + r) / 2 * pw, first.y * ph, first.h * ph, hl.text)
                }
            },
    )
}

/**
 * Word gestures on a page, as in the text reader: tap = translation above the word, double tap = word window,
 * long press = sentence translation, long press and drag = several words together. Double tap beside the words = zoom.
 */
private suspend fun PointerInputScope.pdfGestures(s: ReaderSession, toks: List<PdfTok>, z: PdfZoom, haptic: HapticFeedback) {
    fun hit(p: Offset, sent: Int?, near: Float): WordRef? {
        val pw = size.width.toFloat(); val ph = size.height.toFloat()
        var best: WordRef? = null
        var bd = Float.MAX_VALUE
        for (t in toks) {
            if (sent != null && t.ref.sent != sent) continue
            val dx = maxOf(t.x * pw - p.x, 0f, p.x - (t.x + t.w) * pw)
            val dy = maxOf(t.y * ph - p.y, 0f, p.y - (t.y + t.h) * ph)
            val d = dx + dy * 3
            if (d < bd) { bd = d; best = t.ref }
        }
        return if (bd <= near) best else null
    }
    awaitEachGesture {
        val down = awaitFirstDown()
        val ref = hit(down.position, null, 6.dp.toPx() / z.scale)
        var ended = false
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation().also { ended = true }
        }
        if (ended && up == null) return@awaitEachGesture          // a swipe / pinch took the touch
        if (up != null) {
            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
            if (second == null) {
                if (ref != null) { up.consume(); s.tapWord(ref) }
            } else {
                second.consume(); waitForUpOrCancellation()?.consume()
                if (ref != null) s.openPopup(ref) else z.toggle(up.position)
            }
            return@awaitEachGesture
        }
        if (ref == null) return@awaitEachGesture
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        s.menuArea = "pdf"
        s.selectStart(ref)
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            ch.consume()
            if (!ch.pressed) break
            hit(ch.position, ref.sent, Float.MAX_VALUE)?.let { s.selectTo(it) }
        }
        val single = s.selFrom == s.selTo
        s.selectEnd()
        if (single) s.toggleSentence(ref.sent)
    }
}
