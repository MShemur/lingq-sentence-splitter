package com.mshemur.lingqfix

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

// "Run AI" of the podcast player: the episode is downloaded, recognised in pieces and its sentences get times.
// Runs in app.scope (Dispatchers.IO), so it goes on after leaving the player. The text appears piece by piece.

object PodAi {
    /** Keys of the episodes being recognised now (the player shows the progress). */
    val running: MutableState<Set<String>> = mutableStateOf(emptySet())

    private const val UA = "SatzLoop/1.0 (Android; podcast transcript)"
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(2, TimeUnit.MINUTES).build()

    fun isRunning(key: String?): Boolean = key != null && key in running.value

    /** Recognises the episode in the player (Pod.now) in the current lesson language; a run per episode. */
    fun run(app: AppState) {
        val ep = Pod.now.value ?: return
        if (isRunning(ep.key)) return
        val prov = sttProvider(app.prefs.sttProvider)
        val key = sttKeyFor(app.prefs, prov)
        if (key.isBlank()) {
            app.say("Нет ключа распознавания речи (⚙ Распознавание речи)")
            return
        }
        if (ep.audio.isBlank()) {
            app.say("У выпуска нет аудио")
            return
        }
        val model = app.prefs.sttModel(prov.id)
        val lang = app.lang
        val ext = Uri.parse(ep.audio).lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { e -> e.length in 2..4 && e.all { it.isLetterOrDigit() } } ?: "mp3"
        val file = File(File(app.ctx.cacheDir, "podai").apply { mkdirs() }, "${ep.key}.$ext")
        running.value = running.value + ep.key
        setStatus(ep.key, "Загрузка аудио…")

        app.scope.launch(Dispatchers.IO) {
            val all = mutableListOf<PodSent>()
            val allWords = mutableListOf<TWord>()
            var error: String? = null
            try {
                download(ep.audio, file) { m -> app.scope.launch { setStatus(ep.key, m) } }
                Stt.transcribe(
                    app.ctx, Uri.fromFile(file), "episode.$ext", lang, prov, key, model,
                    onPart = { words, part, parts ->
                        // all words so far, the sentences are cut again: a sentence across two parts stays whole
                        allWords += words
                        all.clear()
                        all += sentences(allWords)
                        Pod.saveTranscript(app.ctx, ep.key, all)
                        val snap = all.toList()
                        app.scope.launch {
                            if (Pod.now.value?.key == ep.key) {
                                Pod.sents.clear()
                                Pod.sents.addAll(snap)
                            }
                            setStatus(ep.key, "Расшифровка: часть $part из $parts")
                        }
                    },
                ) { m ->
                    val msg = when {
                        Regex("часть \\d+ из \\d+").containsMatchIn(m) ->
                            "Расшифровка: " + Regex("часть \\d+ из \\d+").find(m)!!.value
                        m.startsWith("Готовлю") -> "Расшифровка: готовлю аудио…"
                        else -> "Расшифровка: распознаю…"
                    }
                    app.scope.launch { setStatus(ep.key, msg) }
                }
                if (all.isEmpty()) error = "в аудио не нашлось речи"
            } catch (e: Throwable) {
                AppLog.error("podai", "Расшифровка выпуска", e)
                error = e.message ?: e.javaClass.simpleName
            } finally {
                file.delete()
            }
            withContext(Dispatchers.Main) {
                running.value = running.value - ep.key
                if (error == null) {
                    setStatus(ep.key, "")
                    app.say("Расшифровка готова")
                } else {
                    setStatus(ep.key, "Ошибка: $error")
                }
            }
        }
    }

    /** The status line shows only while the episode is the one in the player. */
    private fun setStatus(key: String, msg: String) {
        if (Pod.now.value?.key == key) Pod.status.value = msg
    }

    /** Downloads [url] into [out]; [progress] gets "Загрузка аудио: N %" (or MB when the size is unknown). */
    private fun download(url: String, out: File, progress: (String) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("аудио не скачалось (HTTP ${r.code})")
            val body = r.body ?: throw ApiException("аудио не скачалось: пустой ответ")
            val total = body.contentLength()
            var done = 0L
            var last = -1L
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = done * 100 / total
                            if (pct != last) { last = pct; progress("Загрузка аудио: $pct %") }
                        } else {
                            val mb = done / 1_000_000
                            if (mb != last) { last = mb; progress("Загрузка аудио: $mb МБ") }
                        }
                    }
                }
            }
        }
    }

    /** Sentences with times (ms) of the recognised words of one piece: split at . ! ? …, fragments merged as in the fixer. */
    private fun sentences(words: List<TWord>): List<PodSent> {
        val ws = words.filter { it.text.isNotBlank() }
        if (ws.isEmpty()) return emptyList()
        val merged = mutableListOf<String>()
        for (s in splitSentences(ws.joinToString(" ") { it.text.trim() })) {
            val last = merged.lastOrNull()
            if (last != null && Fixer.shouldMerge(last, s)) merged[merged.size - 1] = "$last $s" else merged += s
        }
        val times = Stt.align(merged, ws)
        return merged.mapIndexed { i, t ->
            PodSent(t, (times[i].first * 1000).toLong(), (times[i].second * 1000).toLong())
        }
    }
}
