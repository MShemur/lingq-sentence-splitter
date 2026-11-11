package com.mshemur.lingqfix

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where a background podcast import goes. [course] null = a new course named [newTitle]. */
class JobTarget(
    val course: Course?, val newTitle: String, val lenMin: Int?,
    val useTranscript: Boolean, val useText: Boolean, val cover: Boolean,
)

/**
 * Podcast episode -> LingQ in the background ("Актуальные загрузки" in the library): the download starts at once,
 * the destination is chosen meanwhile; then a headless [ImportSession] recognises (or takes the transcript),
 * uploads, fixes, sets the times and the level. An error stays in the row until the user closes it.
 */
class BgJob(private val app: AppState, val ep: Episode, val pod: Podcast?, restored: org.json.JSONObject? = null) {
    val id = restored?.optLong("id") ?: System.nanoTime()
    val lang = restored?.optString("lang") ?: app.lang
    var status by mutableStateOf("Скачиваю…")
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
    var done by mutableStateOf(false)
    var lessons by mutableStateOf<List<Lesson>>(emptyList())
    var target by mutableStateOf<JobTarget?>(null)
    val title get() = ep.title.ifBlank { "Выпуск" }

    private class Got(val file: File, val ext: String, val tr: List<TSeg>?, val text: String?, val cover: Pair<ByteArray, String>?)
    private val downloaded = CompletableDeferred<Got>()
    private var job: Job? = null

    /** For the saved list (a job survives the app being closed as a row with its result). */
    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("id", id).put("lang", lang).put("title", title)
        .put("status", status).put("error", error).put("done", done)
        .put("lessons", org.json.JSONArray().apply { lessons.forEach { put(org.json.JSONObject().put("id", it.id).put("title", it.title)) } })

    private fun step(s: String) { status = s; AppLog.add("podcast", "«${title.take(50)}»: $s") }

    init {
        if (restored != null) {
            status = restored.optString("status")
            done = restored.optBoolean("done")
            error = restored.optString("error").takeIf { it.isNotBlank() && it != "null" }
            lessons = restored.optJSONArray("lessons")?.let { a -> (0 until a.length()).map { Lesson(a.getJSONObject(it).getInt("id"), a.getJSONObject(it).optString("title")) } }.orEmpty()
            target = JobTarget(null, "", null, false, false, false)
            if (!done && error == null) {
                error = "Прервано: Android закрыл приложение. Нажмите на выпуск ещё раз"
                AppLog.add("podcast", "«${title.take(50)}»: прервано (приложение было закрыто)")
            }
            downloaded.cancel()
        } else job = app.scope.launch {
            try {
                val dir = File(app.ctx.getExternalFilesDir(null) ?: app.ctx.filesDir, "podcast").apply { mkdirs() }
                val ext = PodcastApi.ext(ep)
                val f = File(dir, "job$id.$ext")
                PodcastApi.downloadSystem(app.ctx, ep.audio, title, f) { d, t ->
                    progress = if (t > 0) d.toFloat() / t else 0f
                    if (target == null || status.startsWith("Скачиваю"))
                        status = "Скачиваю: ${d / 1_000_000} из ${if (t > 0) "${t / 1_000_000}" else "?"} МБ"
                }
                val tr = if (ep.transcript.isBlank()) null else runCatching {
                    withContext(Dispatchers.IO) { PodcastApi.transcript(ep.transcript, ep.transcriptType) }
                }.onFailure { AppLog.error("podcast", "Расшифровка", it) }.getOrNull()?.takeIf { it.isNotEmpty() }
                val text = if (tr != null || ep.textUrl.isBlank()) null else runCatching {
                    withContext(Dispatchers.IO) { PodcastApi.plainText(ep.textUrl) }
                }.onFailure { AppLog.error("podcast", "Текст выпуска", it) }.getOrNull()?.takeIf { it.length > 200 }
                val cover = pod?.image?.takeIf { it.isNotBlank() }?.let { runCatching { withContext(Dispatchers.IO) { PodcastApi.bytes(it) } }.getOrNull() }
                AppLog.add(
                    "podcast",
                    "«${title.take(60)}»: ${f.length() / 1_000_000} МБ скачано (фоновая загрузка), расшифровка: " +
                        if (tr != null) "с временем" else if (text != null) "текст без времени" else "нет",
                )
                if (target == null) status = "Скачано (${f.length() / 1_000_000} МБ), выберите курс"
                downloaded.complete(Got(f, ext, tr, text, cover))
                target?.let { work(it) }
            } catch (e: CancellationException) {
                downloaded.cancel(e); throw e
            } catch (e: Throwable) {
                AppLog.error("podcast", "Фоновая загрузка: скачивание", e)
                error = "Не удалось скачать: ${e.message}"
                if (target != null) ImportService.finished(app.ctx, title, error.orEmpty(), ok = false)
                downloaded.completeExceptionally(e)
            }
        }
    }

    /** The destination was chosen; the work goes on after the download. */
    fun start(t: JobTarget) {
        target = t
        if (downloaded.isCompleted && error == null) app.scope.launch { work(t) }
    }

    private suspend fun waitWhile(cond: () -> Boolean) { while (cond()) delay(300) }

    private suspend fun work(t: JobTarget) {
        try {
            val g = downloaded.await()
            val imp = ImportSession(app)
            step("Открываю аудио…")
            imp.pickAudio(Uri.fromFile(g.file), "$title.${g.ext}", g.tr, g.text)
            waitWhile { imp.loading && imp.audioPick == null && imp.error == null }
            val pk = imp.audioPick ?: throw ApiException(imp.error ?: "аудио не открылось")
            if (t.cover) imp.setCover(g.cover, pod?.title.orEmpty())
            val to = t.lenMin?.let { m -> (m * 60_000L).let { if (pk.durationMs > 0) minOf(it, pk.durationMs) else it } }
            step(if (t.useTranscript && g.tr != null) "Беру расшифровку подкаста…" else "Распознаю речь…")
            imp.transcribePart(0, to, t.useTranscript && g.tr != null, t.useText && g.text != null)
            delay(100)
            waitWhile { imp.loading.also { if (it && imp.status.isNotBlank()) status = imp.status } }
            imp.error?.let { throw ApiException(it) }
            if (t.course != null) { imp.course = t.course; imp.newCourse = false } else { imp.newCourse = true; imp.newCourseTitle = t.newTitle }
            imp.coverToCourse = t.cover && imp.cover != null
            step("Загружаю в LingQ…")
            imp.run()
            delay(100)
            waitWhile {
                imp.running.also {
                    imp.log.lastOrNull()?.let { l -> status = l.replace(Regex("^[^\\p{L}\\p{N}]+"), "").take(120) }
                    progress = imp.progress
                }
            }
            imp.error?.let { throw ApiException(it) }
            val bad = imp.log.filter { it.startsWith("❌") }
            lessons = imp.uploaded.toList()
            if (lessons.isEmpty()) throw ApiException(bad.firstOrNull()?.removePrefix("❌")?.trim() ?: "урок не загрузился")
            done = true; progress = 1f
            status = "Готово: урок в курсе «${imp.course?.title ?: t.newTitle}»" + (if (bad.isNotEmpty()) " · ошибок: ${bad.size}" else "") +
                (imp.textNote?.let { " · $it" } ?: if (t.useTranscript && g.tr != null) " · готовая расшифровка подкаста" else "")
            AppLog.add("podcast", "Фоновая загрузка «${title.take(60)}»: $status")
            ImportService.finished(app.ctx, title, status, ok = true)
            g.file.delete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.error("podcast", "Фоновая загрузка «${title.take(60)}»", e)
            error = e.message ?: e.toString()
            ImportService.finished(app.ctx, title, error.orEmpty(), ok = false)
        }
    }

    /** The cross: stops a running download (a running upload finishes on its own) and removes the row. */
    fun close() {
        if (!downloaded.isCompleted) job?.cancel()
        app.bgJobs.remove(this)
    }
}

private val LENGTHS = listOf("Весь выпуск" to null, "10 мин" to 10, "20 мин" to 20, "30 мин" to 30)

/** "В LingQ": where the episode goes; the download is already running underneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeSheet(state: AppState, j: BgJob, onClose: () -> Unit) {
    LaunchedEffect(Unit) { state.loadCourses() }
    val list = state.visibleCourses().orEmpty()
    val podTitle = j.pod?.title.orEmpty()
    val same = list.firstOrNull { it.title.trim().equals(podTitle.trim(), ignoreCase = true) }
    var course by remember(same?.id) { mutableStateOf(same) }
    var newTitle by remember { mutableStateOf(podTitle) }
    var len by remember { mutableStateOf<Int?>(null) }
    val hasTr = j.ep.transcript.isNotBlank()
    val hasText = !hasTr && j.ep.textUrl.isNotBlank()
    var useTr by remember { mutableStateOf(hasTr) }
    var useText by remember { mutableStateOf(hasText) }
    var cover by remember(same?.id) { mutableStateOf(same == null) }
    var pickCourse by remember { mutableStateOf(false) }
    val prov = sttProvider(state.prefs.sttProvider)
    val needKey = !(hasTr && useTr) && sttKeyFor(state.prefs, prov).isBlank()
    val min = len?.toDouble() ?: (if (j.ep.duration > 0) j.ep.duration / 60.0 else 0.0)
    val price = if (useTr && hasTr) "Бесплатно: готовая расшифровка"
        else if (min > 0) "${prov.name}: ${sttCost(prov, state.prefs.sttModel(prov.id), min) ?: ""}" else prov.name
    val ok = course != null || newTitle.isNotBlank()
    fun cancel() { j.close(); onClose() }
    val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                state.ctx, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    ModalBottomSheet(onDismissRequest = { cancel() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("В LingQ", style = MaterialTheme.typography.titleLarge)
            Text(j.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (j.error == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    j.error?.let { "Ошибка: $it" } ?: j.status, Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (j.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Курс", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Box {
                OutlinedButton(onClick = { pickCourse = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(course?.title?.ifBlank { "Курс ${course?.id}" } ?: "➕ Новый курс", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = pickCourse, onDismissRequest = { pickCourse = false }) {
                    DropdownMenuItem(text = { Text("➕ Новый курс…") }, onClick = { course = null; cover = true; pickCourse = false })
                    list.forEach { c ->
                        DropdownMenuItem(text = { Text(c.title.ifBlank { "Курс ${c.id}" }) }, onClick = { course = c; cover = false; pickCourse = false })
                    }
                }
            }
            if (course == null) OutlinedTextField(newTitle, { newTitle = it }, Modifier.fillMaxWidth(), label = { Text("Название нового курса") }, singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LENGTHS.forEach { (t, v) -> FilterChip(len == v, onClick = { len = v }, label = { Text(t) }) }
            }
            if (hasTr) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Готовая расшифровка подкаста")
                    Hint(if (useTr) "Бесплатно, без распознавания" else "Распознает сервис из настроек")
                }
                Switch(useTr, { useTr = it })
            }
            if (hasText) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Текст подкаста")
                    Hint(if (useText) "Текст от авторов, время предложений даст распознавание" else "И текст, и время из распознавания")
                }
                Switch(useText, { useText = it })
            }
            if (!j.pod?.image.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(cover, { cover = it })
                Text("Обложка подкаста для курса")
            }
            Hint(price)
            if (needKey) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Нужен ключ распознавания (${prov.name}).", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { cancel(); state.go(Screen.SettingsSpeech) }) { Text("Настроить") }
            }
            Button(
                onClick = {
                    j.start(JobTarget(course, newTitle.trim(), len, useTr, useText, cover))
                    state.say("Загружается в фоне — ход виден в библиотеке")
                    onClose()
                },
                enabled = ok && !needKey && j.error == null, modifier = Modifier.fillMaxWidth(),
            ) { Text("Загрузить в фоне") }
            TextButton(onClick = { cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
            Hint("Расшифровку показывать не буду: урок сам появится в курсе. Пока он готовится, можно читать и слушать.")
        }
    }
}

/** Library: imports running in the background; a finished one opens its lesson, an error stays until closed. */
@Composable
fun JobsRow(state: AppState) {
    val list = state.bgJobs.filter { it.target != null }
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Актуальные загрузки", style = MaterialTheme.typography.titleMedium)
        list.forEach { j ->
            val err = j.error
            val ls = j.lessons
            val open = j.done && ls.isNotEmpty()
            Card(
                Modifier.fillMaxWidth().clickable(enabled = open) { state.go(Screen.Reader(ReaderSession(state, j.lang, ls.first()))) },
                colors = CardDefaults.cardColors(
                    containerColor = if (err != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(j.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            err?.let { "Ошибка: $it" } ?: j.status, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            color = if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (err == null && !j.done) LinearProgressIndicator(progress = { j.progress }, Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = { j.close() }) {
                        Icon(Icons.Filled.Close, contentDescription = if (j.done || err != null) "Убрать" else "Остановить")
                    }
                }
            }
        }
    }
}
