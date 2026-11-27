package com.mshemur.lingqfix

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.Locale

// Podcasts: search (Apple Podcasts catalogue) or an RSS link, episodes, download; then the audio import takes over.

data class Podcast(val title: String, val author: String, val feed: String, val image: String)

data class Episode(
    val title: String, val date: Long, val audio: String, val type: String, val duration: Int,
    val description: String, val transcript: String, val transcriptType: String,
    /** A transcript without times (plain text or HTML), "" = none. */
    val textUrl: String = "",
    /** The feed's unique id of the episode ("" = none; the audio address then stands in). */
    val guid: String = "",
)

object PodcastApi {
    /** File extension of an episode's audio. */
    fun ext(ep: Episode): String {
        val e = ep.audio.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (e in setOf("mp3", "m4a", "aac", "ogg", "opus", "mp4", "wav", "flac")) return e
        return when {
            ep.type.contains("mp4") || ep.type.contains("m4a") -> "m4a"
            ep.type.contains("ogg") -> "ogg"
            else -> "mp3"
        }
    }

    private const val UA = "SatzLoop/1.0 (Android; podcast import)"

    private fun get(url: String) = Request.Builder().url(url).header("User-Agent", UA).build()

    /** Apple Podcasts catalogue: free, no key. */
    fun search(term: String, lang: String): List<Podcast> {
        val country = when (lang) { "de" -> "DE"; "fr" -> "FR"; "es" -> "ES"; "it" -> "IT"; "nl" -> "NL"; "pt" -> "PT"; "ru" -> "RU"; else -> "US" }
        val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=40&country=$country&term=" +
            java.net.URLEncoder.encode(term, "UTF-8")
        val text = LingqApi.http.newCall(get(url)).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("поиск: HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val a = JSONObject(text).optJSONArray("results") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("feedUrl").isNotBlank() }.map {
            Podcast(it.optString("collectionName"), it.optString("artistName"), it.optString("feedUrl"),
                it.optString("artworkUrl600").ifBlank { it.optString("artworkUrl100") })
        }
    }

    fun feed(url: String): Pair<Podcast, List<Episode>> =
        LingqApi.http.newCall(get(url)).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("лента: HTTP ${r.code}")
            parse(r.body!!.byteStream(), url)
        }

    private fun parse(xml: InputStream, url: String): Pair<Podcast, List<Episode>> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(xml, null)
        val path = ArrayList<String>()
        var title = ""
        var author = ""
        var image = ""
        val eps = mutableListOf<Episode>()
        var cur: HashMap<String, String>? = null
        fun text(): String {
            val t = runCatching { p.nextText() }.getOrDefault("")
            path.removeAt(path.lastIndex)
            return t.trim()
        }
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    val n = p.name
                    val parent = path.lastOrNull()
                    path += n
                    if (n == "item") cur = HashMap()
                    val c = cur
                    if (c != null && parent == "item") when (n) {
                        "enclosure" -> if (c["audio"] == null) {
                            c["audio"] = p.getAttributeValue(null, "url").orEmpty()
                            c["type"] = p.getAttributeValue(null, "type").orEmpty()
                        }
                        "transcript" -> {
                            val t = p.getAttributeValue(null, "type").orEmpty().lowercase()
                            val u = p.getAttributeValue(null, "url").orEmpty()
                            if (trRank(t) > trRank(c["trType"].orEmpty())) { c["tr"] = u; c["trType"] = t }
                            else if (trRank(t) == 0 && u.isNotBlank() && (c["txt"] == null || t.contains("plain"))) c["txt"] = u
                        }
                        "title", "pubDate", "duration", "description", "summary", "encoded", "guid" -> {
                            val v = text()
                            if (c[n].isNullOrBlank()) c[n] = v
                        }
                    } else if (c == null && parent == "channel") when (n) {
                        "title" -> title = title.ifBlank { text() }
                        "author" -> author = author.ifBlank { text() }
                        "image" -> p.getAttributeValue(null, "href")?.let { if (it.isNotBlank()) image = it }
                    } else if (c == null && parent == "image" && n == "url" && image.isBlank()) image = text()
                }
                XmlPullParser.END_TAG -> {
                    if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                    if (p.name == "item") {
                        cur?.let { c ->
                            val audio = c["audio"].orEmpty()
                            if (audio.isNotBlank()) eps += Episode(
                                c["title"].orEmpty(), date(c["pubDate"].orEmpty()), audio, c["type"].orEmpty(),
                                duration(c["duration"].orEmpty()),
                                Epub.htmlToText(c["description"].orEmpty().ifBlank { c["summary"].orEmpty() }.ifBlank { c["encoded"].orEmpty() })
                                    .replace(Regex("\\s+"), " ").take(400),
                                c["tr"].orEmpty(), c["trType"].orEmpty(), c["txt"].orEmpty(), c["guid"].orEmpty(),
                            )
                        }
                        cur = null
                    }
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        if (title.isBlank() && eps.isEmpty()) throw ApiException("это не лента подкаста")
        return Podcast(title, author, url, image) to eps.sortedByDescending { it.date }
    }

    /** Transcripts with times are useful; plain text / HTML are not. */
    private fun trRank(t: String) = when {
        t.contains("vtt") || t.contains("srt") || t.contains("subrip") -> 3
        t.contains("json") -> 2
        else -> 0
    }

    private val DATES = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm Z", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy")

    private fun date(s: String): Long {
        val t = s.trim()
        for (f in DATES) runCatching { return java.text.SimpleDateFormat(f, Locale.US).parse(t)!!.time }
        return 0L
    }

    /** "1:02:03", "62:03" or seconds. */
    private fun duration(s: String): Int {
        val p = s.trim().split(':').map { it.trim().toDoubleOrNull() ?: return 0 }
        return p.fold(0.0) { a, v -> a * 60 + v }.toInt()
    }

    /** Downloads [url] into [out]; [progress] gets (bytes done, total or -1). Cancellable. */
    suspend fun download(url: String, out: File, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        LingqApi.http.newCall(get(url)).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("скачивание: HTTP ${r.code}")
            val body = r.body ?: throw ApiException("пустой ответ")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().buffered(1 shl 16).use { os ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        if (done - last > 256_000) { last = done; progress(done, total) }
                    }
                    progress(done, total)
                }
            }
        }
    }

    /**
     * Downloads [url] into [out] with the system download manager: it goes on while the app is in the background
     * (a frozen app loses its own connections), resumes after a dropped connection and shows a notification.
     * [progress] gets (bytes done, total or -1). Cancelling removes the download.
     */
    suspend fun downloadSystem(ctx: Context, url: String, title: String, out: File, progress: (Long, Long) -> Unit) {
        val dm = ctx.getSystemService(android.app.DownloadManager::class.java)
        out.delete()
        val req = android.app.DownloadManager.Request(Uri.parse(url))
            .setDestinationUri(Uri.fromFile(out))
            .addRequestHeader("User-Agent", UA)
            .setTitle(title)
            .setDescription("SatzLoop: выпуск подкаста")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val id = dm.enqueue(req)
        try {
            while (true) {
                kotlinx.coroutines.delay(500)
                val q = dm.query(android.app.DownloadManager.Query().setFilterById(id)) ?: continue
                val (status, done, total, reason) = q.use { c ->
                    if (!c.moveToFirst()) throw ApiException("скачивание отменено")
                    fun col(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
                    listOf(
                        col(android.app.DownloadManager.COLUMN_STATUS), col(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                        col(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES), col(android.app.DownloadManager.COLUMN_REASON),
                    )
                }
                when (status.toInt()) {
                    android.app.DownloadManager.STATUS_SUCCESSFUL -> { progress(done, total); return }
                    android.app.DownloadManager.STATUS_FAILED -> {
                        dm.remove(id)
                        throw ApiException(if (reason.toInt() in 400..599) "сервер ответил HTTP $reason" else "ошибка загрузки (код $reason)")
                    }
                    else -> progress(done, total)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            dm.remove(id); throw e
        }
    }

    /** Bytes of a small file (cover picture); null on any error. */
    fun bytes(url: String): Pair<ByteArray, String>? = runCatching {
        LingqApi.http.newCall(get(url)).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.let { b -> b.bytes() to (b.contentType()?.toString() ?: "image/jpeg") }
        }
    }.getOrNull()

    /** A podcast transcript (WebVTT, SRT or Podcasting 2.0 JSON) as timed pieces. */
    fun transcript(url: String, type: String): List<TSeg> {
        val text = LingqApi.http.newCall(get(url)).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("расшифровка: HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        if (type.contains("json") || text.trimStart().startsWith("{")) {
            val a = JSONObject(text).optJSONArray("segments") ?: JSONArray()
            return (0 until a.length()).map { a.getJSONObject(it) }.map {
                TSeg(it.optString("body").trim(), it.optDouble("startTime"), it.optDouble("endTime"))
            }.filter { it.text.isNotBlank() }
        }
        // VTT / SRT: "00:01:02.500 --> 00:01:05.000" then text lines up to a blank line
        val out = mutableListOf<TSeg>()
        val lines = text.replace("\r", "").split('\n')
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.contains("-->")) {
                val (a, b) = l.split("-->").map { clockSec(it.trim().substringBefore(' ')) }
                val sb = StringBuilder()
                i++
                while (i < lines.size && lines[i].isNotBlank()) { sb.append(lines[i].trim()).append(' '); i++ }
                val t = sb.toString().replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
                if (t.isNotEmpty() && a >= 0) out += TSeg(Epub.unescape(t), a, maxOf(a, b))
            }
            i++
        }
        return out
    }

    /** A transcript without times (plain text or HTML) as text with paragraphs. */
    fun plainText(url: String): String {
        val t = LingqApi.http.newCall(get(url)).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("текст выпуска: HTTP ${r.code}")
            r.body?.string().orEmpty()
        }.removePrefix("\uFEFF")
        val html = url.substringBefore('?').endsWith(".html") || t.trimStart().startsWith("<")
        return (if (html) Epub.htmlToText(t) else t).replace("\r", "").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** "01:02:03.500", "02:03,500" -> s (-1 if unreadable). */
    private fun clockSec(s: String): Double {
        val p = s.replace(',', '.').split(':').map { it.toDoubleOrNull() ?: return -1.0 }
        return p.fold(0.0) { a, v -> a * 60 + v }
    }
}

/** OPML subscription lists, the format other podcast apps export and import. */
object Opml {
    /** (title, feed address) of every outline with an xmlUrl, at any depth. */
    fun read(ctx: Context, uri: Uri): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val input = ctx.contentResolver.openInputStream(uri) ?: throw ApiException("файл не открылся")
        input.use { stream ->
            val p = Xml.newPullParser()
            p.setInput(stream, null)
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG && p.name.equals("outline", ignoreCase = true)) {
                    val attrs = (0 until p.attributeCount).associate { p.getAttributeName(it).lowercase() to p.getAttributeValue(it).orEmpty() }
                    val url = attrs["xmlurl"].orEmpty().trim()
                    if (url.startsWith("http", ignoreCase = true)) out += (attrs["text"] ?: attrs["title"]).orEmpty().trim() to url
                }
            }
        }
        return out
    }

    /** The subscriptions as an OPML document. */
    fun build(subs: List<PodcastSubs.Sub>): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n")
            append("<head><title>SatzLoop: подкасты</title></head>\n<body>\n")
            subs.forEach { s ->
                val t = esc(s.podcast.title.ifBlank { s.podcast.feed })
                append("<outline type=\"rss\" text=\"$t\" title=\"$t\" xmlUrl=\"${esc(s.podcast.feed)}\"/>\n")
            }
            append("</body>\n</opml>\n")
        }
    }
}

/**
 * Subscribed podcasts and when each was last looked at (newer episodes are "new").
 * Synced with the iPad as "podcasts": {feed: {title, author, image, seen, t}} or {del: true, t} for a removed one;
 * t = when it was added / removed, the later change wins.
 */
class PodcastSubs(ctx: Context) {
    private val p = ctx.getSharedPreferences("podcasts", Context.MODE_PRIVATE)

    class Sub(val podcast: Podcast, val seen: Long, val t: Long = 0L)

    fun list(): List<Sub> = runCatching {
        val a = JSONArray(p.getString("subs", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Sub(Podcast(it.optString("title"), it.optString("author"), it.optString("feed"), it.optString("image")), it.optLong("seen"), it.optLong("t"))
        }
    }.getOrDefault(emptyList())

    private fun save(l: List<Sub>) {
        val a = JSONArray()
        l.forEach { s ->
            a.put(JSONObject().put("title", s.podcast.title).put("author", s.podcast.author).put("feed", s.podcast.feed)
                .put("image", s.podcast.image).put("seen", s.seen).put("t", s.t))
        }
        p.edit().putString("subs", a.toString()).apply()
    }

    /** Removed feeds: feed -> when. */
    private fun removed(): JSONObject = runCatching { JSONObject(p.getString("removed", "{}")!!) }.getOrDefault(JSONObject())

    fun has(feed: String) = list().any { it.podcast.feed == feed }
    fun add(pod: Podcast, seen: Long) {
        if (has(pod.feed)) return
        p.edit().putString("removed", removed().apply { remove(pod.feed) }.toString()).apply()
        save(list() + Sub(pod, seen, System.currentTimeMillis()))
    }
    fun remove(feed: String) {
        p.edit().putString("removed", removed().put(feed, System.currentTimeMillis()).toString()).apply()
        save(list().filter { it.podcast.feed != feed })
    }
    fun seen(feed: String, t: Long) = save(list().map { if (it.podcast.feed == feed && t > it.seen) Sub(it.podcast, t, it.t) else it })

    fun export(): JSONObject {
        val o = JSONObject()
        list().forEach { s ->
            o.put(s.podcast.feed, JSONObject().put("title", s.podcast.title).put("author", s.podcast.author)
                .put("image", s.podcast.image).put("seen", s.seen).put("t", s.t))
        }
        val r = removed()
        r.keys().forEach { f -> if (!o.has(f)) o.put(f, JSONObject().put("del", true).put("t", r.optLong(f))) }
        return o
    }

    /** The merged list from sync replaces the local one (local order first, new ones at the end). */
    fun import(o: JSONObject) {
        val mine = list().associateBy { it.podcast.feed }
        val out = mutableListOf<Sub>()
        val r = JSONObject()
        val keys = o.keys().asSequence().toList().sortedBy { f -> mine.keys.indexOf(f).let { if (it < 0) Int.MAX_VALUE else it } }
        keys.forEach { f ->
            val x = o.getJSONObject(f)
            if (x.optBoolean("del")) { r.put(f, x.optLong("t")); return@forEach }
            val m = mine[f]
            out += Sub(
                Podcast(x.optString("title").ifBlank { m?.podcast?.title.orEmpty() }, x.optString("author"), f, x.optString("image")),
                maxOf(x.optLong("seen"), m?.seen ?: 0L), x.optLong("t"),
            )
        }
        save(out)
        p.edit().putString("removed", r.toString()).apply()
    }
}

class PodcastSession(val app: AppState, private val imp: ImportSession) {
    /** "В LingQ" sheet of the episode being downloaded in the background. */
    var sheet by mutableStateOf<BgJob?>(null)

    /** Tap on an episode: its download starts at once in the background, the sheet asks where it goes. */
    fun startJob(ep: Episode) {
        val j = BgJob(app, ep, podcast)
        app.bgJobs.add(0, j)
        sheet = j
    }

    private val subsStore = PodcastSubs(app.ctx)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Podcast>?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val subs = mutableStateListOf<PodcastSubs.Sub>().apply { addAll(subsStore.list()) }
    /** New episodes per subscribed feed (filled in the background). */
    val newCount = mutableStateMapOf<String, Int>()

    // the open feed
    var podcast by mutableStateOf<Podcast?>(null)
    var episodes by mutableStateOf<List<Episode>?>(null)
    var subscribed by mutableStateOf(false)
    /** Episodes newer than this were not seen before opening the feed. */
    var seenBefore = 0L

    // download
    var downloading by mutableStateOf<Episode?>(null)
    var progress by mutableStateOf(0f)
    var progressText by mutableStateOf("")
    private var job: Job? = null

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) { results = null; return }
        if (q.startsWith("http", true)) return openFeed(Podcast("", "", q, ""))
        busy = true; error = null
        app.scope.launch {
            try {
                results = withContext(Dispatchers.IO) { PodcastApi.search(q, app.lang) }
            } catch (e: Throwable) {
                AppLog.error("podcast", "Поиск", e); error = "Поиск не удался: ${e.message}"
            }
            busy = false
        }
    }

    fun refreshNew() {
        val lim = Semaphore(3)
        subs.forEach { s ->
            if (newCount.containsKey(s.podcast.feed)) return@forEach
            app.scope.launch {
                lim.withPermit {
                    runCatching { withContext(Dispatchers.IO) { PodcastApi.feed(s.podcast.feed) } }.getOrNull()?.let { (_, eps) ->
                        newCount[s.podcast.feed] = eps.count { it.date > s.seen }
                    }
                }
            }
        }
    }

    fun openFeed(p: Podcast) {
        podcast = p; episodes = null; error = null
        subscribed = subsStore.has(p.feed)
        seenBefore = subs.firstOrNull { it.podcast.feed == p.feed }?.seen ?: Long.MAX_VALUE
        app.go(Screen.PodcastFeed(this))
        app.scope.launch {
            try {
                val (info, eps) = withContext(Dispatchers.IO) { PodcastApi.feed(p.feed) }
                podcast = Podcast(info.title.ifBlank { p.title }, info.author.ifBlank { p.author }, p.feed, info.image.ifBlank { p.image })
                episodes = eps
                val newest = eps.maxOfOrNull { it.date } ?: 0L
                if (subscribed) { subsStore.seen(p.feed, newest); newCount[p.feed] = 0; reloadSubs() }
            } catch (e: Throwable) {
                AppLog.error("podcast", "Лента ${p.feed}", e); error = "Не удалось открыть ленту: ${e.message}"
            }
        }
    }

    fun reloadSubs() { subs.clear(); subs.addAll(subsStore.list()) }

    fun toggleSub() {
        val p = podcast ?: return
        if (subscribed) subsStore.remove(p.feed) else subsStore.add(p, episodes?.maxOfOrNull { it.date } ?: 0L)
        subscribed = !subscribed
        reloadSubs()
    }

    /** OPML line of the Podcasts tab: import progress or the export result ("" = none). */
    var opmlText by mutableStateOf("")

    /** Subscribes to every feed of an OPML file not subscribed yet; the feeds are fetched 3 at a time for title and cover. */
    fun importOpml(uri: Uri) {
        app.scope.launch {
            val feeds = try {
                withContext(Dispatchers.IO) { Opml.read(app.ctx, uri) }
            } catch (e: Throwable) {
                AppLog.error("podcast", "OPML: чтение", e); app.say("Не удалось прочитать файл: ${e.message}"); return@launch
            }
            val todo = feeds.distinctBy { it.second }.filter { !subsStore.has(it.second) }
            if (todo.isEmpty()) { app.say("Новых подписок нет (в файле: ${feeds.size})"); return@launch }
            var added = 0
            var done = 0
            val lim = Semaphore(3)
            opmlText = "Импорт: 0 из ${todo.size}"
            coroutineScope {
                todo.forEach { (outlineTitle, url) ->
                    launch {
                        val fetched = lim.withPermit {
                            runCatching { withContext(Dispatchers.IO) { PodcastApi.feed(url) } }.getOrNull()
                        }
                        // back on the main thread (the scope's): the store is written from here only
                        if (!subsStore.has(url)) {
                            val pod = fetched?.first?.let { info ->
                                Podcast(info.title.ifBlank { outlineTitle }, info.author, url, info.image)
                            } ?: Podcast(outlineTitle, "", url, "")
                            // like a normal subscribe: the episodes that exist now are not "new"
                            val seen = fetched?.second?.maxOfOrNull { it.date } ?: System.currentTimeMillis()
                            subsStore.add(pod, seen)
                            added++
                            reloadSubs()
                        }
                        done++
                        opmlText = "Импорт: $done из ${todo.size}"
                    }
                }
            }
            opmlText = ""
            app.say("Подписок добавлено: $added")
        }
    }

    /** Writes the subscriptions as OPML to [uri] (chosen with "save as"). */
    fun exportOpml(uri: Uri) {
        app.scope.launch {
            val subsNow = subsStore.list()
            try {
                val text = Opml.build(subsNow)
                withContext(Dispatchers.IO) {
                    app.ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw ApiException("файл не открылся для записи")
                }
                app.say("Подписок сохранено: ${subsNow.size}")
            } catch (e: Throwable) {
                AppLog.error("podcast", "OPML: запись", e); app.say("Не удалось сохранить: ${e.message}")
            }
        }
    }

    fun cancel() { job?.cancel(); job = null; downloading = null }

    /** Downloads the episode (and its transcript and the cover), then the audio import asks which part to take. */
    fun pick(ep: Episode) {
        if (downloading != null) return
        downloading = ep; progress = 0f; progressText = "Скачиваю…"
        val pod = podcast
        job = app.scope.launch {
            try {
                // app's own folder on the shared storage: the system download manager writes there, no permission needed
                val dir = File(app.ctx.getExternalFilesDir(null) ?: app.ctx.filesDir, "podcast").apply { deleteRecursively(); mkdirs() }
                val ext = ext(ep)
                val f = File(dir, "episode.$ext")
                progressText = "Скачиваю… Можно свернуть приложение, загрузка продолжится"
                PodcastApi.downloadSystem(app.ctx, ep.audio, ep.title.ifBlank { "Выпуск" }, f) { done, total ->
                    progress = if (total > 0) done.toFloat() / total else 0f
                    progressText = "Скачиваю: ${done / 1_000_000} из ${if (total > 0) "${total / 1_000_000} МБ" else "? МБ"} · можно свернуть приложение"
                }
                val tr = if (ep.transcript.isBlank()) null else runCatching {
                    withContext(Dispatchers.IO) { PodcastApi.transcript(ep.transcript, ep.transcriptType) }
                }.onFailure { AppLog.error("podcast", "Расшифровка", it) }.getOrNull()?.takeIf { it.isNotEmpty() }
                val text = if (tr != null || ep.textUrl.isBlank()) null else runCatching {
                    withContext(Dispatchers.IO) { PodcastApi.plainText(ep.textUrl) }
                }.onFailure { AppLog.error("podcast", "Текст выпуска", it) }.getOrNull()?.takeIf { it.length > 200 }
                val cover = pod?.image?.takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.IO) { PodcastApi.bytes(it) } }
                AppLog.add("podcast", "«${ep.title.take(60)}»: ${f.length() / 1_000_000} МБ, расшифровка: ${if (tr != null) "с временем" else if (text != null) "текст без времени" else "нет"}")
                downloading = null
                while (app.stack.size > 1 && app.stack.last() !is Screen.Import) app.back()
                imp.pickAudio(Uri.fromFile(f), "${ep.title.ifBlank { "Выпуск" }}.$ext", tr, text)
                imp.setCover(cover, pod?.title.orEmpty())   // after pickAudio, which clears the old cover
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.error("podcast", "Скачивание", e)
                error = "Не удалось скачать: ${e.message}"
                downloading = null
            }
        }
    }

    private fun ext(ep: Episode): String {
        val e = ep.audio.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (e in setOf("mp3", "m4a", "aac", "ogg", "opus", "mp4", "wav", "flac")) return e
        return when {
            ep.type.contains("mp4") || ep.type.contains("m4a") -> "m4a"
            ep.type.contains("ogg") -> "ogg"
            else -> "mp3"
        }
    }
}

private fun fmtDate(t: Long) = if (t <= 0) "" else java.text.SimpleDateFormat("d MMM yyyy", Locale("ru")).format(java.util.Date(t))

private fun fmtDur(s: Int) = when {
    s <= 0 -> ""
    s >= 3600 -> "${s / 3600} ч ${s / 60 % 60} мин"
    else -> "${(s + 30) / 60} мин"
}

/** A podcast as a square cover tile; [badge] > 0 (new episodes) = a green dot at the top-right corner of the cover. */
@Composable
private fun PodTile(p: Podcast, badge: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth()) {
            Cover(p.image, p.title, Modifier.fillMaxWidth())
            if (badge > 0) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(12.dp).clip(CircleShape).background(Color(0xFF43A047)),
            )
        }
        Text(
            p.title, Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Search, subscriptions as cover tiles (tap = the feed). */
@Composable
fun PodcastsScreen(state: AppState, s: PodcastSession) {
    LaunchedEffect(Unit) { s.reloadSubs(); s.refreshNew() }
    val pickOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) s.importOpml(uri) }
    val saveOpml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri -> if (uri != null) s.exportOpml(uri) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    s.query, { s.query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                    label = { Text("Название подкаста или ссылка на RSS") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { s.search() }),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ClearButton(s.query) { s.query = ""; s.results = null }
                            TextButton(onClick = { s.search() }) { Text("Найти") }
                        }
                    },
                )
                if (s.busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Ищу…", Modifier.padding(start = 10.dp))
                }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickOpml.launch(arrayOf("text/xml", "application/xml", "text/x-opml", "*/*")) }) { Text("Импорт подписок (OPML)") }
                    TextButton(onClick = { saveOpml.launch("satzloop-podcasts.opml") }) { Text("Экспорт") }
                    if (s.opmlText.isNotEmpty()) Text(s.opmlText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val res = s.results
        if (res != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Найдено: ${res.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { s.results = null; s.query = "" }) { Text("Мои подкасты") }
                }
            }
            gridItems(res, key = { "r" + it.feed }) { p -> PodTile(p, 0) { s.openFeed(p) } }
        } else {
            if (s.subs.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Пока нет подписок", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            gridItems(s.subs, key = { "s" + it.podcast.feed }) { sub ->
                PodTile(sub.podcast, s.newCount[sub.podcast.feed] ?: 0) { s.openFeed(sub.podcast) }
            }
        }
    }
}

/** Episodes of one podcast. */
@Composable
fun PodcastFeedScreen(s: PodcastSession) {
    val p = s.podcast ?: return
    s.sheet?.let { j -> EpisodeSheet(s.app, j) { s.sheet = null } }
    FeedBody(s, p)
}

@Composable
private fun FeedBody(s: PodcastSession, p: Podcast) {
    val ctx = s.app.ctx
    val eps = s.episodes
    // Pod keys of the episodes; the AI badge = a transcript saved for the episode (read once per list, off the main thread)
    val keys = remember(eps, p.feed) { eps.orEmpty().map { Pod.key(p.feed, it.guid.ifBlank { it.audio }) } }
    val aiKeys by produceState(emptySet<String>(), keys) {
        value = withContext(Dispatchers.IO) { keys.filter { Pod.loadTranscript(ctx, it) != null }.toSet() }
    }
    val playing = Pod.now.value?.key
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Cover(p.image, p.title, Modifier.fillMaxWidth(0.55f))
                Text(
                    p.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (p.author.isNotBlank()) Text(
                    p.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (s.subscribed) OutlinedButton(onClick = { s.toggleSub() }) { Text("Вы подписаны ✓") }
                else FilledTonalButton(onClick = { s.toggleSub() }) { Text("Подписаться") }
            }
        }
        s.downloading?.let { ep ->
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(ep.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.progressText, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { s.cancel() }) { Text("Отмена") }
                        }
                    }
                }
            }
        }
        s.error?.let { e -> item { Text(e, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
        if (eps == null && s.error == null) item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Загружаю выпуски…", Modifier.padding(start = 10.dp))
            }
        }
        if (eps != null) {
            itemsIndexed(eps, key = { _, ep -> ep.audio }) { i, ep ->
                val k = keys.getOrElse(i) { "" }
                val on = k.isNotEmpty() && k == playing
                val isNew = ep.date > s.seenBefore
                val meta = listOf(fmtDate(ep.date), fmtDur(ep.duration)).filter { it.isNotBlank() }.joinToString(" · ")
                Row(
                    Modifier.fillMaxWidth().clickable { Pod.play(s.app, Pod.of(ep, p)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            ep.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (on) FontWeight.SemiBold else null,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (k in aiKeys) Box(
                                Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) { Text("AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                        }
                    }
                    if (isNew) Box(Modifier.padding(start = 10.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
                HorizontalDivider()
            }
        }
    }
}
