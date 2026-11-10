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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
)

object PodcastApi {
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
                            if (trRank(t) > trRank(c["trType"].orEmpty())) {
                                c["tr"] = p.getAttributeValue(null, "url").orEmpty(); c["trType"] = t
                            }
                        }
                        "title", "pubDate", "duration", "description", "summary", "encoded" -> {
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
                                c["tr"].orEmpty(), c["trType"].orEmpty(),
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

    /** "01:02:03.500", "02:03,500" -> s (-1 if unreadable). */
    private fun clockSec(s: String): Double {
        val p = s.replace(',', '.').split(':').map { it.toDoubleOrNull() ?: return -1.0 }
        return p.fold(0.0) { a, v -> a * 60 + v }
    }
}

/** Subscribed podcasts and when each was last looked at (newer episodes are "new"). */
class PodcastSubs(ctx: Context) {
    private val p = ctx.getSharedPreferences("podcasts", Context.MODE_PRIVATE)

    class Sub(val podcast: Podcast, val seen: Long)

    fun list(): List<Sub> = runCatching {
        val a = JSONArray(p.getString("subs", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Sub(Podcast(it.optString("title"), it.optString("author"), it.optString("feed"), it.optString("image")), it.optLong("seen"))
        }
    }.getOrDefault(emptyList())

    private fun save(l: List<Sub>) {
        val a = JSONArray()
        l.forEach { s ->
            a.put(JSONObject().put("title", s.podcast.title).put("author", s.podcast.author).put("feed", s.podcast.feed)
                .put("image", s.podcast.image).put("seen", s.seen))
        }
        p.edit().putString("subs", a.toString()).apply()
    }

    fun has(feed: String) = list().any { it.podcast.feed == feed }
    fun add(pod: Podcast, seen: Long) { if (!has(pod.feed)) save(list() + Sub(pod, seen)) }
    fun remove(feed: String) = save(list().filter { it.podcast.feed != feed })
    fun seen(feed: String, t: Long) = save(list().map { if (it.podcast.feed == feed && t > it.seen) Sub(it.podcast, t) else it })
}

class PodcastSession(private val app: AppState, private val imp: ImportSession) {
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

    private fun reloadSubs() { subs.clear(); subs.addAll(subsStore.list()) }

    fun toggleSub() {
        val p = podcast ?: return
        if (subscribed) subsStore.remove(p.feed) else subsStore.add(p, episodes?.maxOfOrNull { it.date } ?: 0L)
        subscribed = !subscribed
        reloadSubs()
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
                val cover = pod?.image?.takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.IO) { PodcastApi.bytes(it) } }
                AppLog.add("podcast", "«${ep.title.take(60)}»: ${f.length() / 1_000_000} МБ, расшифровка: ${if (tr != null) "есть" else "нет"}")
                downloading = null
                while (app.stack.size > 1 && app.stack.last() !is Screen.Import) app.back()
                imp.pickAudio(Uri.fromFile(f), "${ep.title.ifBlank { "Выпуск" }}.$ext", tr)
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

@Composable
private fun PodcastRow(p: Podcast, badge: String?, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Cover(p.image, p.title, Modifier.size(56.dp)) },
        headlineContent = { Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { if (p.author.isNotBlank()) Text(p.author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (badge != null) {
            {
                Box(
                    Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(badge, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
            }
        } else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Search, subscriptions, suggestions. */
@Composable
fun PodcastsScreen(state: AppState, s: PodcastSession) {
    LaunchedEffect(Unit) { s.refreshNew() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    s.query, { s.query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                    label = { Text("Название подкаста или ссылка на RSS") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { s.search() }),
                    trailingIcon = { TextButton(onClick = { s.search() }) { Text("Найти") } },
                )
                if (s.busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Ищу…", Modifier.padding(start = 10.dp))
                }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        val res = s.results
        if (res != null) {
            item { Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Найдено: ${res.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { s.results = null; s.query = "" }) { Text("Мои подкасты") }
            } }
            items(res, key = { "r" + it.feed }) { p -> PodcastRow(p, null) { s.openFeed(p) } }
        } else {
            item {
                Text(
                    "Мои подкасты", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                )
            }
            if (s.subs.isEmpty()) item {
                Text(
                    "Пока нет. Найдите подкаст и нажмите «Подписаться» — новые выпуски будут отмечены здесь.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(s.subs, key = { "s" + it.podcast.feed }) { sub ->
                val n = s.newCount[sub.podcast.feed] ?: 0
                PodcastRow(sub.podcast, if (n > 0) "$n нов." else null) { s.openFeed(sub.podcast) }
            }
        }
    }
}

/** Episodes of one podcast. */
@Composable
fun PodcastFeedScreen(s: PodcastSession) {
    val p = s.podcast ?: return
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(p.image, p.title, Modifier.width(88.dp))
                Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (p.author.isNotBlank()) Hint(p.author)
                    if (s.subscribed) OutlinedButton(onClick = { s.toggleSub() }) { Text("Вы подписаны ✓") }
                    else FilledTonalButton(onClick = { s.toggleSub() }) { Text("Подписаться") }
                }
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
        val eps = s.episodes
        if (eps == null && s.error == null) item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Загружаю выпуски…", Modifier.padding(start = 10.dp))
            }
        }
        if (eps != null) {
            item {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Hint("Выпусков: ${eps.size}. Нажмите на выпуск: он скачается, потом выберете, какую часть взять.")
                }
            }
            items(eps, key = { it.audio }) { ep ->
                val isNew = ep.date > s.seenBefore
                ListItem(
                    leadingContent = if (isNew) {
                        { Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }
                    } else null,
                    headlineContent = { Text(ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text(
                                listOf(fmtDate(ep.date), fmtDur(ep.duration), if (ep.transcript.isNotBlank()) "есть расшифровка" else "")
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                            )
                            if (ep.description.isNotBlank()) Text(ep.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    modifier = Modifier.clickable(enabled = s.downloading == null) { s.pick(ep) },
                )
                HorizontalDivider()
            }
        }
    }
}
