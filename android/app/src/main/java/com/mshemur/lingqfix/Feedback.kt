package com.mshemur.lingqfix

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Feedback / tasks for Claude: each note is a file `feedback/<stamp>-android.json` on the `sync` branch of the
 * repo (the sync token already may write there). Claude reads them, works on them and writes back `status`
 * ("new" / "work" / "done" / "no"), `version` and `reply` into the same file; the app shows that.
 */
object Feedback {
    private const val API = "https://api.github.com/repos/MShemur/lingq-sentence-splitter"
    private const val BRANCH = "sync"

    class Item(val name: String, val time: Long, val device: String, val text: String, val status: String, val version: String, val reply: String)

    private fun req(token: String, url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun call(r: Request): Pair<Int, String> =
        LingqApi.http.newCall(r).execute().use { it.code to it.body?.string().orEmpty() }

    private fun fail(code: Int, text: String): Nothing {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
        throw ApiException(
            when (code) {
                401 -> "GitHub не принял токен"
                403, 404 -> "У токена нет права записи (Contents: Read and write) или нет ветки синхронизации — $msg"
                else -> "GitHub: HTTP $code $msg"
            },
        )
    }

    fun send(token: String, text: String, log: String?) {
        if (token.isBlank()) throw ApiException("Нет токена GitHub (⚙ Настройки → Обновления)")
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val o = JSONObject().put("t", now).put("device", "android").put("version", BuildConfig.VERSION_NAME)
            .put("text", text.trim()).put("status", "new")
        if (!log.isNullOrBlank()) o.put("log", log.takeLast(60_000))
        val body = JSONObject()
            .put("message", "feedback from android")
            .put("branch", BRANCH)
            .put("content", Base64.encodeToString(o.toString(2).toByteArray(), Base64.NO_WRAP))
        val (code, t) = call(req(token, "$API/contents/feedback/$stamp-android.json").put(body.toString().toRequestBody("application/json".toMediaType())).build())
        if (code !in 200..299) fail(code, t)
        AppLog.add("feedback", "Отзыв отправлен ($stamp)")
    }

    /** The latest notes (both devices), newest first. */
    fun list(token: String, max: Int = 30): List<Item> {
        if (token.isBlank()) return emptyList()
        val (code, t) = call(req(token, "$API/contents/feedback?ref=$BRANCH").build())
        if (code == 404) return emptyList()
        if (code != 200) fail(code, t)
        val a = JSONArray(t)
        val names = (0 until a.length()).map { a.getJSONObject(it).optString("name") }.filter { it.endsWith(".json") }
            .sortedDescending().take(max)
        return names.mapNotNull { n ->
            val (c, x) = call(req(token, "$API/contents/feedback/$n?ref=$BRANCH").header("Accept", "application/vnd.github.raw").build())
            if (c != 200) return@mapNotNull null
            runCatching {
                val o = JSONObject(x)
                Item(n, o.optLong("t"), o.optString("device"), o.optString("text"), o.optString("status", "new"),
                    o.optString("doneIn"), o.optString("reply"))
            }.getOrNull()
        }
    }
}

/** Settings → "Отзыв / задача": write a note for Claude, see what became of the earlier ones. */
@Composable
fun FeedbackScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var withLog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<Feedback.Item>?>(null) }
    fun reload() {
        scope.launch {
            items = try { withContext(Dispatchers.IO) { Feedback.list(state.ghToken) } } catch (e: Throwable) { error = e.message; emptyList() }
        }
    }
    LaunchedEffect(Unit) { reload() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Напишите, что поправить или добавить. Запись уходит в GitHub; Claude проверяет новые раз в час, " +
                "делает и отвечает здесь (с номером версии).",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp),
            placeholder = { Text("Например: в плеере кнопка ⏭ слишком близко к краю") }, enabled = !busy,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = withLog, onCheckedChange = { withLog = it }, enabled = !busy)
            Text("Приложить журнал действий (для ошибок)")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { Feedback.send(state.ghToken, text, if (withLog) AppLog.text() else null) }
                        text = ""; withLog = false
                        state.say("Отправлено")
                        reload()
                    } catch (e: Throwable) {
                        AppLog.error("feedback", "Отзыв не отправлен", e)
                        error = e.message ?: e.toString()
                    }
                    busy = false
                }
            },
            enabled = !busy && text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Отправить") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Отправленные", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { items = null; reload() }) { Text("Обновить") }
        }
        val list = items
        when {
            list == null -> CircularProgressIndicator(Modifier.size(24.dp))
            list.isEmpty() -> Text("Пока ничего", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> list.forEach { f -> FeedbackCard(f) }
        }
    }
}

@Composable
private fun FeedbackCard(f: Feedback.Item) {
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val st = when (f.status) {
                "work" -> "⏳ в работе"
                "done" -> "✅ готово" + if (f.version.isNotBlank()) " в ${f.version}" else ""
                "no" -> "✖ не делаю"
                else -> "🕓 ждёт"
            }
            Text(
                "${fmt.format(Date(f.time))} · ${if (f.device == "web") "iPad" else "телефон"} · $st",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
            )
            Text(f.text, style = MaterialTheme.typography.bodyMedium)
            if (f.reply.isNotBlank()) Text(
                "Claude: ${f.reply}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
