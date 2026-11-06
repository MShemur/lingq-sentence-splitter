package com.mshemur.lingqfix

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sync between the phone and the iPad (web): one JSON file `sync/data.json` on the branch `sync` of the private
 * repository, read and written with the GitHub token (Contents: read and write). Format (v 1):
 *
 * - bookmarks: {"<lessonId>_<sent>": bookmark}, deleted: {"<lessonId>_<sent>": time} — the later event wins;
 * - notes: {"<lang>_<lessonId>": {t, pins, words}} — the whole lesson, the later `t` wins;
 * - progress: {"<lang>_<lessonId>": {pos, t, read}} — pos by later `t`, read = max;
 * - stats: {"<device>": {"<date>": [seconds, words]}} — every device writes only its own part;
 * - seen: {"<lang>_<lessonId>": index} — max.
 */
class SyncService(private val app: AppState) {
    companion object {
        private const val REPO = "MShemur/lingq-sentence-splitter"
        private const val BRANCH = "sync"
        private const val PATH = "sync/data.json"
        private const val API = "https://api.github.com/repos/$REPO"
    }

    private val ctx: Context get() = app.ctx
    private val prefs get() = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)

    var enabled by mutableStateOf(prefs.getBoolean("on", false))
        private set
    var running by mutableStateOf(false)
        private set
    var lastSync by mutableLongStateOf(prefs.getLong("last", 0L))
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    private var lastTry = 0L

    /** This device in the stats part ("android-ab12cd"). */
    val device: String = prefs.getString("device", null) ?: ("android-" + java.util.UUID.randomUUID().toString().take(6)).also {
        prefs.edit().putString("device", it).apply()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        prefs.edit().putBoolean("on", on).apply()
        if (on) now()
    }

    /** On start / when leaving the app: at most once a minute. */
    fun auto() {
        if (!enabled || app.ghToken.isBlank()) return
        if (System.currentTimeMillis() - lastTry < 60_000) return
        now()
    }

    fun now() {
        if (running) return
        if (app.ghToken.isBlank()) { lastError = "Нет GitHub-токена"; return }
        running = true
        lastTry = System.currentTimeMillis()
        app.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // two tries: if the iPad wrote in between, read again and merge again
                    try { syncOnce() } catch (e: Conflict) { syncOnce() }
                }
                lastSync = System.currentTimeMillis()
                prefs.edit().putLong("last", lastSync).apply()
                lastError = null
                app.stats.reloadOthers()
            } catch (e: Throwable) {
                lastError = e.message ?: e.toString()
            }
            running = false
        }
    }

    private class Conflict : Exception()

    // --- GitHub ---

    private fun req(url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer ${app.ghToken}")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun call(r: Request): Pair<Int, String> =
        LingqApi.http.newCall(r).execute().use { it.code to it.body?.string().orEmpty() }

    private fun fail(code: Int, text: String): Nothing {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
        throw ApiException(
            when (code) {
                401 -> "GitHub не принял токен"
                403, 404 -> "У токена нет права записи (Contents: Read and write) — $msg"
                else -> "GitHub: HTTP $code $msg"
            },
        )
    }

    /** The branch for the sync file: made from main the first time. */
    private fun ensureBranch() {
        val (code, _) = call(req("$API/git/ref/heads/$BRANCH").build())
        if (code == 200) return
        val (c2, t2) = call(req("$API/git/ref/heads/main").build())
        if (c2 != 200) fail(c2, t2)
        val sha = JSONObject(t2).getJSONObject("object").getString("sha")
        val body = JSONObject().put("ref", "refs/heads/$BRANCH").put("sha", sha).toString()
        val (c3, t3) = call(req("$API/git/refs").post(body.toRequestBody("application/json".toMediaType())).build())
        if (c3 !in 200..299 && c3 != 422) fail(c3, t3)
    }

    /** Remote data and the file's sha (null: no file yet). */
    private fun download(): Pair<JSONObject, String?> {
        val (code, text) = call(req("$API/contents/$PATH?ref=$BRANCH").build())
        if (code == 404) { ensureBranch(); return JSONObject() to null }
        if (code != 200) fail(code, text)
        val o = JSONObject(text)
        val sha = o.getString("sha")
        var b64 = o.optString("content")
        if (b64.isBlank()) {   // over 1 MB: the contents API leaves it out, the blob has it
            val (c2, t2) = call(req("$API/git/blobs/$sha").build())
            if (c2 != 200) fail(c2, t2)
            b64 = JSONObject(t2).getString("content")
        }
        val json = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        return (runCatching { JSONObject(json) }.getOrDefault(JSONObject())) to sha
    }

    private fun upload(data: JSONObject, sha: String?) {
        val body = JSONObject()
            .put("message", "sync from $device [skip ci]")
            .put("content", Base64.encodeToString(data.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("branch", BRANCH)
        if (sha != null) body.put("sha", sha)
        val (code, text) = call(req("$API/contents/$PATH").put(body.toString().toRequestBody("application/json".toMediaType())).build())
        if (code == 409 || code == 422 && text.contains("sha")) throw Conflict()
        if (code !in 200..299) fail(code, text)
    }

    private fun syncOnce() {
        val local = exportLocal()
        val (remote, sha) = download()
        val merged = merge(local, remote)
        importLocal(merged)
        if (merged.toString() != remote.toString()) upload(merged.put("v", 1), sha)
    }

    // --- local data ---

    private fun exportLocal(): JSONObject {
        val o = JSONObject().put("v", 1)
        // bookmarks
        val bm = JSONObject()
        app.bookmarks.items.forEach { b -> bm.put(BookmarkStore.key(b), BookmarkStore.toJson(b)) }
        o.put("bookmarks", bm)
        o.put("deleted", JSONObject(app.bookmarks.deleted.toMap()))
        // lesson notes (pins, chosen meanings)
        val notes = JSONObject()
        File(ctx.filesDir, "reader").listFiles().orEmpty().filter { it.name.endsWith(".json") }.forEach { f ->
            runCatching { notes.put(f.name.removeSuffix(".json"), JSONObject(f.readText())) }
        }
        o.put("notes", notes)
        o.put("progress", app.progress.export())
        o.put("stats", JSONObject().put(device, app.stats.exportDays()))
        o.put("seen", app.stats.exportSeen())
        return o
    }

    private fun importLocal(m: JSONObject) {
        // bookmarks: the merged list replaces the local one
        val list = mutableListOf<Bookmark>()
        m.optJSONObject("bookmarks")?.let { b -> b.keys().forEach { k -> runCatching { list += BookmarkStore.fromJson(b.getJSONObject(k)) } } }
        val del = HashMap<String, Long>()
        m.optJSONObject("deleted")?.let { d -> d.keys().forEach { k -> del[k] = d.getLong(k) } }
        app.scope.launch { app.bookmarks.replaceAll(list.sortedBy { it.time }, del) }
        // notes: newer lessons overwrite
        val dir = File(ctx.filesDir, "reader").apply { mkdirs() }
        m.optJSONObject("notes")?.let { n ->
            n.keys().forEach { k ->
                val f = File(dir, "$k.json")
                val remote = n.getJSONObject(k)
                val localT = runCatching { JSONObject(f.readText()).optLong("t") }.getOrDefault(0L)
                if (remote.optLong("t") > localT || !f.exists()) runCatching { f.writeText(remote.toString()) }
            }
        }
        m.optJSONObject("progress")?.let { app.progress.import(it) }
        m.optJSONObject("stats")?.let { s ->
            val others = JSONObject()
            s.keys().forEach { d -> if (d != device) others.put(d, s.getJSONObject(d)) }
            app.stats.importOthers(others)
        }
        m.optJSONObject("seen")?.let { app.stats.importSeen(it) }
    }

    // --- merge ---

    private fun merge(a: JSONObject, b: JSONObject): JSONObject {
        val out = JSONObject().put("v", 1)
        // bookmarks / deletions: the later event wins
        val bmA = a.optJSONObject("bookmarks") ?: JSONObject()
        val bmB = b.optJSONObject("bookmarks") ?: JSONObject()
        val delA = a.optJSONObject("deleted") ?: JSONObject()
        val delB = b.optJSONObject("deleted") ?: JSONObject()
        val bm = JSONObject()
        val del = JSONObject()
        val keys = (bmA.keys().asSequence() + bmB.keys().asSequence() + delA.keys().asSequence() + delB.keys().asSequence()).toSet()
        for (k in keys) {
            val ia = bmA.optJSONObject(k)
            val ib = bmB.optJSONObject(k)
            val item = listOfNotNull(ia, ib).maxByOrNull { it.optLong("time") }
            val itemT = item?.optLong("time") ?: -1L
            val delT = maxOf(delA.optLong(k, -1L), delB.optLong(k, -1L))
            if (item != null && itemT >= delT) {
                // keep a translation from either side
                if (item.optString("tr").isBlank()) listOfNotNull(ia, ib).firstOrNull { it.optString("tr").isNotBlank() }
                    ?.let { item.put("tr", it.optString("tr")) }
                bm.put(k, item)
            } else if (delT >= 0) del.put(k, delT)
        }
        out.put("bookmarks", bm).put("deleted", del)
        // notes: whole lesson, later t
        out.put("notes", mergeBy(a.optJSONObject("notes"), b.optJSONObject("notes")) { x, y -> if (y.optLong("t") > x.optLong("t")) y else x })
        // progress: position by later t, read = max
        out.put("progress", mergeBy(a.optJSONObject("progress"), b.optJSONObject("progress")) { x, y ->
            val newer = if (y.optLong("t") > x.optLong("t")) y else x
            JSONObject(newer.toString()).put("read", maxOf(x.optDouble("read", 0.0), y.optDouble("read", 0.0)))
        })
        // stats: one part per device, each device's own part is the freshest on that device
        val st = JSONObject()
        listOf(b.optJSONObject("stats"), a.optJSONObject("stats")).forEach { s -> s?.keys()?.forEach { d -> st.put(d, s.getJSONObject(d)) } }
        out.put("stats", st)
        // seen: max
        val seen = JSONObject()
        listOf(a.optJSONObject("seen"), b.optJSONObject("seen")).forEach { s ->
            s?.keys()?.forEach { k -> seen.put(k, maxOf(seen.optInt(k, -1), s.getInt(k))) }
        }
        out.put("seen", seen)
        return out
    }

    private fun mergeBy(a: JSONObject?, b: JSONObject?, pick: (JSONObject, JSONObject) -> JSONObject): JSONObject {
        val out = JSONObject()
        a?.keys()?.forEach { k -> out.put(k, a.getJSONObject(k)) }
        b?.keys()?.forEach { k ->
            val y = b.getJSONObject(k)
            val x = out.optJSONObject(k)
            out.put(k, if (x == null) y else pick(x, y))
        }
        return out
    }
}

/** Settings → Синхронизация. */
@Composable
fun SettingsSync(state: AppState) {
    val s = state.sync
    val uri = LocalUriHandler.current
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Закладки, выбранные переводы слов, место чтения, «Прочитано N %» и статистика — общие для телефона и iPad. " +
                "Хранятся в вашем приватном репозитории GitHub (ветка sync). Ключи ИИ и LingQ не передаются.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Синхронизировать", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = s.enabled, onCheckedChange = { s.setEnabled(it) })
        }
        Text(
            when {
                s.running -> "Синхронизация…"
                s.lastError != null -> "Ошибка: ${s.lastError}"
                s.lastSync > 0 -> "Последняя синхронизация: ${fmt.format(Date(s.lastSync))}"
                else -> "Ещё не синхронизировалось"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (s.lastError != null && !s.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { s.now() }, enabled = !s.running && state.ghToken.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            if (s.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Синхронизировать сейчас")
        }
        Text("Сама запускается при открытии приложения и когда вы из него выходите.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Токен", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "Нужен тот же GitHub-токен, что для обновлений, но с правом записи. Один раз: github.com → Settings → " +
                "Developer settings → Fine-grained tokens → ваш токен → Repository permissions → Contents: «Read and write» → " +
                "Update. Сам токен менять в приложении не нужно." + if (state.ghToken.isBlank()) " Токена пока нет — вставьте его в «Обновлениях»." else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Row {
            TextButton(onClick = { uri.openUri("https://github.com/settings/personal-access-tokens") }) { Text("Открыть токены GitHub") }
            if (state.ghToken.isBlank()) TextButton(onClick = { state.go(Screen.SettingsUpdates) }) { Text("Вставить токен") }
        }
    }
}

/** Serialises arrays of the bookmark store for sync. */
internal fun jsonArrayOf(list: List<JSONObject>) = JSONArray().also { a -> list.forEach { a.put(it) } }
