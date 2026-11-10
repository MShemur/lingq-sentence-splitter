package com.mshemur.lingqfix

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

// Speech recognition: an audio file (podcast, recording) becomes the text of a lesson, with the time of every word.

/** One recognised word with its time in the audio, s. */
data class TWord(val text: String, val start: Double, val end: Double)

/** A piece of recognised speech (a sentence, a Whisper segment or a paragraph); [newPara] = a paragraph starts here. */
data class TSeg(val text: String, val start: Double, val end: Double, val newPara: Boolean = false)

/** Result of recognition: text in paragraphs and the words with times. */
class Transcript(val text: String, val words: List<TWord>)

class SttProvider(
    val id: String, val name: String, val note: String, val keyUrl: String, val priceUrl: String,
    /** Models (the first is the default) and their price, US$ per hour of audio. */
    val prices: List<Pair<String, Double>>,
    /** Larger files are converted and sent in pieces. */
    val maxBytes: Long,
) {
    val models get() = prices.map { it.first }
    fun price(model: String): Double? = prices.firstOrNull { it.first == model }?.second
}

/** When the prices below were checked on the services' sites. */
const val STT_PRICES_CHECKED = "10.11.2026"

val STT_PROVIDERS = listOf(
    SttProvider(
        "groq", "Groq", "Whisper large-v3: самый дешёвый и быстрый. Есть бесплатный лимит.",
        "https://console.groq.com/keys", "https://console.groq.com/docs/speech-to-text", listOf("whisper-large-v3-turbo" to 0.04, "whisper-large-v3" to 0.111), 24_000_000,
    ),
    SttProvider(
        "openai", "OpenAI", "Whisper. Время слов есть только у whisper-1.",
        "https://platform.openai.com/api-keys", "https://platform.openai.com/docs/pricing", listOf("whisper-1" to 0.36), 24_000_000,
    ),
    SttProvider(
        "deepgram", "Deepgram", "Nova-3: точный, быстрый, с абзацами. При регистрации 200 $ бесплатно.",
        "https://console.deepgram.com/", "https://deepgram.com/pricing", listOf("nova-3" to 0.258), 500_000_000,
    ),
    SttProvider(
        "assemblyai", "AssemblyAI", "Точный, с абзацами. При регистрации 50 $ бесплатно.",
        "https://www.assemblyai.com/dashboard", "https://www.assemblyai.com/pricing", listOf("universal-2" to 0.15, "universal-3-5-pro" to 0.21), 500_000_000,
    ),
    SttProvider(
        "elevenlabs", "ElevenLabs", "Scribe: очень точный. В бесплатном тарифе 4,5 часа.",
        "https://elevenlabs.io/app/settings/api-keys", "https://elevenlabs.io/pricing/api", listOf("scribe_v2" to 0.22, "scribe_v1" to 0.40), 500_000_000,
    ),
)

/** "≈ 0,7 цента", "≈ 12 центов", "≈ 1,20 $" for [minutes] of audio; null when the price is unknown. */
fun sttCost(p: SttProvider, model: String, minutes: Double): String? {
    val usd = (p.price(model.ifBlank { p.models.first() }) ?: return null) * minutes / 60
    val cents = usd * 100
    return when {
        cents < 0.1 -> "меньше 0,1 цента"
        cents < 10 -> "≈ " + "%.1f".format(cents).replace('.', ',') + " цента"
        cents < 100 -> cents.toInt().let { "≈ $it " + plural(it, "цент", "цента", "центов") }
        else -> "≈ " + "%.2f".format(usd).replace('.', ',') + " $"
    }
}

/** "0,04 $ за час". */
fun sttHourPrice(v: Double) = "%.3f".format(v).trimEnd('0').trimEnd('.', ',').replace('.', ',') + " $ за час"

fun sttProvider(id: String) = STT_PROVIDERS.firstOrNull { it.id == id } ?: STT_PROVIDERS[0]

/** The key for [p]; Groq falls back to the key of the translation AI when that one is Groq. */
fun sttKeyFor(prefs: Prefs, p: SttProvider): String =
    prefs.sttKey(p.id).ifBlank { if (p.id == "groq" && prefs.aiBase.contains("groq.com")) prefs.aiKey else "" }

/** Request body that streams a file picked by the user (re-opened on every attempt). */
class UriBody(private val ctx: Context, private val uri: Uri) : RequestBody() {
    private val type = (
        ctx.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.path.orEmpty().substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        ).toMediaTypeOrNull()
    private val length = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L

    override fun contentType(): MediaType? = type
    override fun contentLength(): Long = if (length >= 0) length else -1L
    override fun writeTo(sink: BufferedSink) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw ApiException("файл недоступен")
        input.source().use { sink.writeAll(it) }
    }
}

object Stt {
    /** Whisper services accept these formats as they are. */
    private val WHISPER_TYPES = setOf("flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "wav", "webm")

    private val http = LingqApi.http.newBuilder()
        .readTimeout(15, TimeUnit.MINUTES).writeTimeout(15, TimeUnit.MINUTES).build()

    /** Recognises [uri] (file [name]) in language [lang]; [status] reports progress. */
    suspend fun transcribe(
        ctx: Context, uri: Uri, name: String, lang: String, p: SttProvider, key: String, model: String,
        status: (String) -> Unit,
    ): Transcript {
        if (key.isBlank()) throw ApiException("не задан ключ ${p.name} (⚙ Настройки → Распознавание речи)")
        val m = model.ifBlank { p.models.first() }
        val size = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val whisper = p.id == "groq" || p.id == "openai"
        val direct = size in 1..p.maxBytes && (!whisper || ext in WHISPER_TYPES)
        val t0 = System.currentTimeMillis()
        val segs = mutableListOf<TSeg>()
        val words = mutableListOf<TWord>()
        if (direct) {
            status("Распознаю: ${p.name}…")
            val (s, w) = withContext(Dispatchers.IO) { send(p, key, m, lang, UriBody(ctx, uri), name) }
            segs += s; words += w
        } else {
            // too big for one request (or a format the service does not take): 16 kHz mono WAV pieces of ~8 min
            val dir = File(ctx.cacheDir, "stt").apply { deleteRecursively(); mkdirs() }
            try {
                val pcm = File(dir, "audio.pcm")
                val total = withContext(Dispatchers.Default) {
                    AudioPrep.decode(ctx, uri, pcm) { f -> status("Готовлю аудио… ${(f * 100).toInt()} %") }
                }
                val cuts = withContext(Dispatchers.IO) { AudioPrep.cuts(pcm, total) }
                for (i in 0 until cuts.size - 1) {
                    status("Распознаю: ${p.name}, часть ${i + 1} из ${cuts.size - 1}…")
                    val wav = File(dir, "part$i.wav")
                    val offset = cuts[i].toDouble() / AudioPrep.RATE
                    val (s, w) = withContext(Dispatchers.IO) {
                        AudioPrep.wav(pcm, cuts[i], cuts[i + 1], wav)
                        try { send(p, key, m, lang, wav.asRequestBody("audio/wav".toMediaType()), "part$i.wav") } finally { wav.delete() }
                    }
                    segs += s.map { it.copy(start = it.start + offset, end = it.end + offset) }
                    words += w.map { it.copy(start = it.start + offset, end = it.end + offset) }
                }
            } finally {
                dir.deleteRecursively()
            }
        }
        val clean = dropHallucinations(segs)
        val text = paragraphs(clean)
        AppLog.add("stt", "${p.name} $m: ${words.size} слов, ${(System.currentTimeMillis() - t0) / 1000} с" + if (direct) "" else " (по частям)")
        return Transcript(text, words)
    }

    private fun send(p: SttProvider, key: String, model: String, lang: String, body: RequestBody, name: String): Pair<List<TSeg>, List<TWord>> =
        when (p.id) {
            "openai" -> whisper("https://api.openai.com/v1", key, model, lang, body, name)
            "deepgram" -> deepgram(key, model, lang, body)
            "assemblyai" -> assembly(key, model, lang, body)
            "elevenlabs" -> eleven(key, model, lang, body, name)
            else -> whisper("https://api.groq.com/openai/v1", key, model, lang, body, name)
        }

    /** Runs a request (again after 2, 4, 8 s when the service is busy); the answer as JSON. */
    private fun call(req: Request, what: String): JSONObject {
        var attempt = 0
        val t0 = System.currentTimeMillis()
        while (true) {
            val (code, text) = try {
                http.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
            } catch (e: java.io.IOException) {
                AppLog.error("stt", "$what ✗", e); throw e
            }
            AppLog.add("stt", "$what → $code (${System.currentTimeMillis() - t0} мс)")
            if ((code == 429 || code >= 500) && attempt < 3) { Thread.sleep(2000L shl attempt); attempt++; continue }
            if (code == 401 || code == 403) throw ApiException("$what не принял ключ (HTTP $code)")
            if (code !in 200..299) {
                val o = runCatching { JSONObject(text) }.getOrNull()
                val msg = o?.optJSONObject("error")?.optString("message")
                    ?: o?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: o?.optJSONObject("detail")?.optString("message")
                    ?: o?.optString("err_msg")?.takeIf { it.isNotBlank() }
                    ?: text.take(200)
                throw ApiException("$what: HTTP $code $msg")
            }
            return JSONObject(text)
        }
    }

    private fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }

    /** Groq / OpenAI: Whisper segments (text with punctuation) and words (times). */
    private fun whisper(base: String, key: String, model: String, lang: String, body: RequestBody, name: String): Pair<List<TSeg>, List<TWord>> {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, body)
            .addFormDataPart("model", model)
            .addFormDataPart("language", lang)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("temperature", "0")
            .build()
        val req = Request.Builder().url("$base/audio/transcriptions").header("Authorization", "Bearer $key").post(form).build()
        val o = call(req, AppLog.short(base))
        val words = arr(o.optJSONArray("words")).map { TWord(it.optString("word").trim(), it.optDouble("start"), it.optDouble("end")) }
        var segs = arr(o.optJSONArray("segments")).map { TSeg(it.optString("text").trim(), it.optDouble("start"), it.optDouble("end")) }
        if (segs.isEmpty()) segs = listOf(TSeg(o.optString("text").trim(), 0.0, words.lastOrNull()?.end ?: 0.0))
        return segs to words
    }

    /** Deepgram: sentences inside paragraphs, words with punctuation. */
    private fun deepgram(key: String, model: String, lang: String, body: RequestBody): Pair<List<TSeg>, List<TWord>> {
        val url = "https://api.deepgram.com/v1/listen?model=$model&language=$lang&smart_format=true&punctuate=true&paragraphs=true"
        val req = Request.Builder().url(url).header("Authorization", "Token $key").post(body).build()
        val alt = call(req, "Deepgram").optJSONObject("results")?.optJSONArray("channels")?.optJSONObject(0)
            ?.optJSONArray("alternatives")?.optJSONObject(0) ?: throw ApiException("Deepgram не дал ответа")
        val words = arr(alt.optJSONArray("words")).map {
            TWord(it.optString("punctuated_word").ifBlank { it.optString("word") }, it.optDouble("start"), it.optDouble("end"))
        }
        val segs = mutableListOf<TSeg>()
        arr(alt.optJSONObject("paragraphs")?.optJSONArray("paragraphs")).forEach { para ->
            arr(para.optJSONArray("sentences")).forEachIndexed { i, s ->
                segs += TSeg(s.optString("text"), s.optDouble("start"), s.optDouble("end"), newPara = i == 0)
            }
        }
        if (segs.isEmpty()) segs += TSeg(alt.optString("transcript"), 0.0, words.lastOrNull()?.end ?: 0.0)
        return segs to words
    }

    /** AssemblyAI: upload, start, wait; paragraphs and words (ms). */
    private fun assembly(key: String, model: String, lang: String, body: RequestBody): Pair<List<TSeg>, List<TWord>> {
        val base = "https://api.assemblyai.com/v2"
        fun req(url: String) = Request.Builder().url(url).header("Authorization", key)
        val up = call(req("$base/upload").post(body).build(), "AssemblyAI").optString("upload_url")
        val job = JSONObject().put("audio_url", up).put("language_code", lang).put("punctuate", true).put("format_text", true)
        if (model.isNotBlank()) job.put("speech_model", model)
        val id = call(
            req("$base/transcript").post(job.toString().toRequestBody("application/json".toMediaType())).build(), "AssemblyAI",
        ).optString("id")
        val deadline = System.currentTimeMillis() + 60 * 60_000L
        var t: JSONObject
        while (true) {
            Thread.sleep(3000)
            t = call(req("$base/transcript/$id").get().build(), "AssemblyAI")
            when (t.optString("status")) {
                "completed" -> break
                "error" -> throw ApiException("AssemblyAI: ${t.optString("error")}")
            }
            if (System.currentTimeMillis() > deadline) throw ApiException("AssemblyAI не успел за час")
        }
        val words = arr(t.optJSONArray("words")).map { TWord(it.optString("text"), it.optDouble("start") / 1000, it.optDouble("end") / 1000) }
        val paras = runCatching { call(req("$base/transcript/$id/paragraphs").get().build(), "AssemblyAI") }.getOrNull()
        val segs = arr(paras?.optJSONArray("paragraphs")).map {
            TSeg(it.optString("text"), it.optDouble("start") / 1000, it.optDouble("end") / 1000, newPara = true)
        }.ifEmpty { listOf(TSeg(t.optString("text"), 0.0, words.lastOrNull()?.end ?: 0.0)) }
        return segs to words
    }

    /** ElevenLabs Scribe: words and spaces with times; sentences are cut at . ! ? */
    private fun eleven(key: String, model: String, lang: String, body: RequestBody, name: String): Pair<List<TSeg>, List<TWord>> {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, body)
            .addFormDataPart("model_id", model)
            .addFormDataPart("language_code", lang)
            .addFormDataPart("timestamps_granularity", "word")
            .addFormDataPart("tag_audio_events", "false")
            .build()
        val req = Request.Builder().url("https://api.elevenlabs.io/v1/speech-to-text").header("xi-api-key", key).post(form).build()
        val o = call(req, "ElevenLabs")
        val tokens = arr(o.optJSONArray("words")).filter { it.optString("type") != "audio_event" }
        val words = tokens.filter { it.optString("type", "word") == "word" }
            .map { TWord(it.optString("text"), it.optDouble("start"), it.optDouble("end")) }
        val segs = mutableListOf<TSeg>()
        val sb = StringBuilder()
        var start = -1.0
        for (tk in tokens) {
            val txt = tk.optString("text")
            if (start < 0 && txt.isNotBlank()) start = tk.optDouble("start")
            sb.append(txt)
            if (tk.optString("type", "word") == "word" && txt.trimEnd().lastOrNull()?.let { it in ".!?…" } == true) {
                segs += TSeg(sb.toString().trim(), start, tk.optDouble("end")); sb.clear(); start = -1.0
            }
        }
        if (sb.isNotBlank()) segs += TSeg(sb.toString().trim(), start.coerceAtLeast(0.0), words.lastOrNull()?.end ?: 0.0)
        if (segs.isEmpty()) segs += TSeg(o.optString("text"), 0.0, 0.0)
        return segs to words
    }

    /** A ready transcript (podcast VTT / SRT / JSON) cut to [fromMs, toMs): times of words spread over each piece. */
    fun fromSegments(all: List<TSeg>, fromMs: Long, toMs: Long?): Transcript {
        val a = fromMs / 1000.0
        val b = toMs?.let { it / 1000.0 } ?: Double.MAX_VALUE
        val segs = all.filter { it.end > a && it.start < b }
            .map { it.copy(start = maxOf(0.0, it.start - a), end = maxOf(0.0, it.end - a)) }
        val words = segs.flatMap { s ->
            val ws = s.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val total = ws.sumOf { it.length }.coerceAtLeast(1)
            var t = s.start
            ws.map { w -> val d = (s.end - s.start) * w.length / total; TWord(w, t, t + d).also { t += d } }
        }
        return Transcript(paragraphs(segs), words)
    }

    /** Whisper sometimes "hears" subtitle credits in silence, or repeats a line: such pieces are dropped. */
    private fun dropHallucinations(segs: List<TSeg>): List<TSeg> {
        val junk = Regex("Untertitel (der|im Auftrag|von)|Amara\\.org|Copyright WDR|Vielen Dank fürs Zuschauen", RegexOption.IGNORE_CASE)
        val out = mutableListOf<TSeg>()
        var repeats = 0
        for (s in segs) {
            if (s.text.isBlank() || junk.containsMatchIn(s.text)) continue
            repeats = if (out.isNotEmpty() && out.last().text == s.text) repeats + 1 else 0
            if (repeats >= 2) continue
            out += s
        }
        return out
    }

    /** Text with paragraphs: where the service marked them, otherwise at a sentence end after a pause or ~90 words. */
    fun paragraphs(segs: List<TSeg>): String {
        val given = segs.drop(1).any { it.newPara }
        val out = StringBuilder()
        var words = 0
        var prev: TSeg? = null
        for (s in segs) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            val p = prev
            if (p != null) {
                val sentenceEnd = p.text.trimEnd().lastOrNull()?.let { it in ".!?…»“\"" } == true
                val brk = if (given) s.newPara else sentenceEnd && (s.start - p.end >= 1.2 || words >= 90)
                if (brk) { out.append("\n\n"); words = 0 } else out.append(' ')
            }
            out.append(t)
            words += t.split(' ').size
            prev = s
        }
        return out.toString()
    }

    /**
     * Times of LingQ sentences from the recognised words: [start, end] in s per sentence. The words are matched in
     * order (a few words of look-ahead), so small edits of the text are fine; unmatched sentences get the gap between
     * their neighbours.
     */
    fun align(sentences: List<String>, words: List<TWord>): List<Pair<Double, Double>> {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val ws = words.map { norm(it.text) }
        var j = 0
        val raw = sentences.map { s ->
            var first = -1
            var last = -1
            for (t in s.split(Regex("\\s+")).map(::norm).filter { it.isNotEmpty() }) {
                var k = j
                val lim = min(ws.size, j + 8)
                while (k < lim && ws[k] != t) k++
                if (k < lim) { if (first < 0) first = k; last = k; j = k + 1 }
            }
            if (first < 0) null else words[first].start to words[last].end
        }
        val end = words.lastOrNull()?.end ?: 0.0
        return raw.mapIndexed { i, r ->
            r ?: run {
                val a = (i - 1 downTo 0).firstNotNullOfOrNull { raw[it] }?.second ?: 0.0
                val b = (i + 1 until raw.size).firstNotNullOfOrNull { raw[it] }?.first ?: end
                a to maxOf(a, b)
            }
        }
    }
}

/** A part of an audio file without re-encoding (MP3 frames as they are, AAC / Vorbis / Opus remuxed). */
object AudioCut {
    /** Length of the audio in ms (0 = unknown). */
    fun duration(ctx: Context, uri: Uri): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(ctx, uri, null)
            val f = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (f != null && f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
        } catch (e: Exception) {
            0L
        } finally {
            ex.release()
        }
    }

    /** Writes [fromMs, toMs) of [uri] into [dir]; returns the new file (name ends with the right extension). */
    fun cut(ctx: Context, uri: Uri, fromMs: Long, toMs: Long, dir: File, base: String): File {
        dir.mkdirs()
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        try {
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw ApiException("в файле нет звука")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
            val muxFormat = when {
                mime == "audio/mpeg" -> -1
                mime == "audio/mp4a-latm" -> android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                mime == "audio/vorbis" -> android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                mime == "audio/opus" && android.os.Build.VERSION.SDK_INT >= 29 -> android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else -> null
            }
            if (muxFormat == null) {
                // other codecs (FLAC, AMR…): decoded and written as 16 kHz WAV
                ex.release()
                val pcm = File(dir, "cut.pcm")
                try {
                    val total = AudioPrep.decode(ctx, uri, pcm) {}
                    val out = File(dir, "$base.wav")
                    val a = (fromMs * AudioPrep.RATE / 1000).coerceIn(0, total)
                    val b = (toMs * AudioPrep.RATE / 1000).coerceIn(a, total)
                    AudioPrep.wav(pcm, a, b, out)
                    return out
                } finally {
                    pcm.delete()
                }
            }
            val ext = when (muxFormat) { -1 -> "mp3"; android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 -> "m4a"; else -> "webm" }
            val out = File(dir, "$base.$ext")
            val size = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = java.nio.ByteBuffer.allocate(maxOf(size, 1 shl 20))
            val fromUs = fromMs * 1000
            val toUs = toMs * 1000
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (muxFormat == -1) {
                // MP3: the frames one after another are a valid MP3 file
                BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                    val bytes = ByteArray(buf.capacity())
                    while (true) {
                        buf.clear()
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime >= toUs) break
                        buf.position(0); buf.limit(n); buf.get(bytes, 0, n)
                        os.write(bytes, 0, n)
                        ex.advance()
                    }
                }
            } else {
                val mux = android.media.MediaMuxer(out.path, muxFormat)
                try {
                    val t = mux.addTrack(fmt)
                    mux.start()
                    val info = MediaCodec.BufferInfo()
                    var first = -1L
                    while (true) {
                        buf.clear()
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime >= toUs) break
                        if (first < 0) first = ex.sampleTime
                        val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        info.set(0, n, ex.sampleTime - first, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        mux.writeSampleData(t, buf, info)
                        ex.advance()
                    }
                    mux.stop()
                } finally {
                    mux.release()
                }
            }
            if (out.length() == 0L) throw ApiException("в выбранном куске нет звука")
            return out
        } finally {
            runCatching { ex.release() }
        }
    }
}

/** "1:05:30" / "12:30" / "90" (minutes) -> ms; null if it cannot be read. */
fun parseClock(s: String): Long? {
    val p = s.trim().split(':').map { it.trim() }
    if (p.any { it.isEmpty() || it.toLongOrNull() == null }) return if (s.isBlank()) 0L else null
    val n = p.map { it.toLong() }
    return when (n.size) {
        1 -> n[0] * 60_000
        2 -> (n[0] * 60 + n[1]) * 1000
        3 -> ((n[0] * 60 + n[1]) * 60 + n[2]) * 1000
        else -> null
    }
}

/** ms -> "12:30" or "1:05:30". */
fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Audio files -> 16 kHz mono PCM, cut into pieces at quiet places, written as WAV. */
object AudioPrep {
    const val RATE = 16000
    /** Samples per piece: 8 min ≈ 15 MB of WAV (Whisper services take up to 25 MB). */
    private const val PIECE = 8L * 60 * RATE

    /** Decodes [uri] into [out] (16-bit little-endian, 16 kHz, mono); returns the number of samples. */
    fun decode(ctx: Context, uri: Uri, out: File, progress: (Float) -> Unit): Long {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { ex.release(); throw ApiException("в файле нет звука") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rs = Resampler(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        var float = false
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var count = 0L
        var lastReport = -1
        val os = BufferedOutputStream(FileOutputStream(out), 1 shl 16)
        try {
            while (true) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rs = Resampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val frames = info.size / (if (float) 4 else 2) / channels
                    val mono = FloatArray(frames)
                    if (float) {
                        val fb = buf.asFloatBuffer()
                        for (k in 0 until frames) { var s = 0f; repeat(channels) { s += fb.get() }; mono[k] = s / channels * 32767f }
                    } else {
                        val sb = buf.asShortBuffer()
                        for (k in 0 until frames) { var s = 0f; repeat(channels) { s += sb.get() }; mono[k] = s / channels }
                    }
                    count += rs.push(mono, os)
                    codec.releaseOutputBuffer(o, false)
                    if (dur > 0) {
                        val pct = (info.presentationTimeUs * 100 / dur).toInt().coerceIn(0, 100)
                        if (pct != lastReport) { lastReport = pct; progress(pct / 100f) }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            os.close()
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        if (count == 0L) throw ApiException("не удалось прочитать звук")
        return count
    }

    /** Linear resampling to 16 kHz with a simple low-pass in front (less aliasing). */
    private class Resampler(src: Int) {
        private val step = src.toDouble() / RATE
        private val alpha = if (src > RATE) (1 - exp(-2 * PI * 7000.0 / src)).toFloat() else 1f
        private var lp1 = 0f
        private var lp2 = 0f
        private var carry = 0f
        private var pos = 1.0

        fun push(x: FloatArray, os: OutputStream): Int {
            val n = x.size
            if (n == 0) return 0
            val a = FloatArray(n + 1)
            a[0] = carry
            for (i in 0 until n) { lp1 += alpha * (x[i] - lp1); lp2 += alpha * (lp1 - lp2); a[i + 1] = lp2 }
            var out = 0
            while (pos < n) {
                val i = pos.toInt()
                val f = (pos - i).toFloat()
                val s = (a[i] * (1 - f) + a[i + 1] * f).roundToInt().coerceIn(-32768, 32767)
                os.write(s and 0xff); os.write((s shr 8) and 0xff)
                out++
                pos += step
            }
            pos -= n
            carry = a[n]
            return out
        }
    }

    /** Piece borders (samples): about every 8 minutes, in the quietest 0.1 s of the 30 s before. */
    fun cuts(pcm: File, total: Long): List<Long> {
        val out = mutableListOf(0L)
        val win = RATE / 10
        RandomAccessFile(pcm, "r").use { f ->
            while (total - out.last() > PIECE) {
                val target = out.last() + PIECE
                val from = target - 30L * RATE
                val buf = ByteArray(((target - from) * 2).toInt())
                f.seek(from * 2); f.readFully(buf)
                var best = target
                var bestE = Long.MAX_VALUE
                var w = 0
                while ((w + 1) * win * 2 <= buf.size) {
                    var e = 0L
                    for (k in 0 until win) {
                        val idx = (w * win + k) * 2
                        e += abs(((buf[idx].toInt() and 0xff) or (buf[idx + 1].toInt() shl 8)).toShort().toInt())
                    }
                    if (e < bestE) { bestE = e; best = from + w * win + win / 2 }
                    w++
                }
                out += best
            }
        }
        out += total
        return out
    }

    /** Samples [from, to) of [pcm] as a WAV file. */
    fun wav(pcm: File, from: Long, to: Long, out: File) {
        val bytes = (to - from) * 2
        RandomAccessFile(pcm, "r").use { f ->
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                fun i32(v: Long) { for (k in 0 until 4) os.write(((v shr (8 * k)) and 0xff).toInt()) }
                fun i16(v: Int) { os.write(v and 0xff); os.write((v shr 8) and 0xff) }
                os.write("RIFF".toByteArray()); i32(36 + bytes); os.write("WAVE".toByteArray())
                os.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE.toLong()); i32(RATE * 2L); i16(2); i16(16)
                os.write("data".toByteArray()); i32(bytes)
                f.seek(from * 2)
                val buf = ByteArray(1 shl 16)
                var left = bytes
                while (left > 0) {
                    val n = f.read(buf, 0, min(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    os.write(buf, 0, n); left -= n
                }
            }
        }
    }
}

/** Settings → Распознавание речи: service, key, model. */
@Composable
fun SettingsSpeech(state: AppState) {
    val p = state.prefs
    var prov by remember { mutableStateOf(sttProvider(p.sttProvider).id) }
    val sp = sttProvider(prov)
    var key by remember(prov) { mutableStateOf(p.sttKey(prov)) }
    var model by remember(prov) { mutableStateOf(p.sttModel(prov).ifBlank { sp.models.first() }) }
    val uri = LocalUriHandler.current
    Page {
        Hint(
            "Аудио без текста (подкаст, запись) превращается в урок: текст распознаётся, аудио загружается в LingQ вместе с ним, " +
                "у каждого предложения своё время. Импорт → Аудио.",
        )
        Text("Сервис", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        STT_PROVIDERS.forEach { s ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = prov == s.id, role = Role.RadioButton) { prov = s.id; p.sttProvider = s.id }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = prov == s.id, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(s.name + if (p.sttKey(s.id).isNotBlank()) " · ключ задан" else "", style = MaterialTheme.typography.bodyLarge)
                    Hint("от ${sttHourPrice(s.prices.minOf { it.second })} · ${s.note}")
                }
            }
        }
        HorizontalDivider()
        SecretField(key, "Ключ ${sp.name}") { key = it; p.setSttKey(prov, it) }
        if (prov == "groq" && key.isBlank() && p.aiBase.contains("groq.com") && p.aiKey.isNotBlank())
            Hint("Пусто: будет взят ключ Groq из настроек перевода.")
        TextButton(onClick = { uri.openUri(sp.keyUrl) }) { Text("Получить ключ ${sp.name}") }
        Text("Модель", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sp.models.forEach { m ->
                FilterChip(selected = model == m, label = { Text(m) }, onClick = { model = m; p.setSttModel(prov, m) })
            }
        }
        OutlinedTextField(
            model, { model = it.trim(); p.setSttModel(prov, it) }, Modifier.fillMaxWidth(),
            label = { Text("Модель") }, singleLine = true,
        )
        sp.price(model)?.let { Hint("$model: ${sttHourPrice(it)}, 10 минут ${sttCost(sp, model, 10.0)}. Цены проверены $STT_PRICES_CHECKED.") }
        TextButton(onClick = { uri.openUri(sp.priceUrl) }) { Text("Цены ${sp.name} на сайте") }
        Hint("Ключи синхронизируются между устройствами зашифрованными, если задан пароль синхронизации.")
    }
}

/** An audio file waiting for the choice of its part. */
class AudioPick(val uri: Uri, val name: String, val durationMs: Long, val transcript: List<TSeg>? = null)

/** Which part of the audio to recognise: start and length (10 min by default) or the whole file. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AudioPartPicker(s: ImportSession, pk: AudioPick) {
    var start by remember(pk) { mutableStateOf("0:00") }
    var len by remember(pk) { mutableStateOf<Int?>(10) }   // minutes; null = to the end
    var useTr by remember(pk) { mutableStateOf(pk.transcript != null) }
    val from = parseClock(start)
    val dur = pk.durationMs
    val to = len?.let { (from ?: 0L) + it * 60_000L }?.let { if (dur > 0) minOf(it, dur) else it } ?: dur.takeIf { it > 0 }
    val bad = from == null || (dur > 0 && from >= dur)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = { s.audioPick = null }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Какую часть распознать?", style = MaterialTheme.typography.titleLarge)
            Hint(pk.name + if (dur > 0) " · ${clock(dur)}" else "")
            OutlinedTextField(
                start, { start = it }, Modifier.fillMaxWidth(), singleLine = true, isError = bad,
                label = { Text("Начало, мин:сек") },
                supportingText = { if (bad) Text(if (from == null) "Например 12:30 или 1:05:00" else "Это дальше конца записи") },
            )
            Text("Сколько взять", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 20, 30).forEach { m ->
                    FilterChip(selected = len == m, label = { Text("$m мин") }, onClick = { len = m })
                }
                FilterChip(selected = len == null, label = { Text("До конца") }, onClick = { len = null })
            }
            if (!bad) Hint(
                "Будет распознан кусок ${clock(from ?: 0)}–${to?.let { clock(it) } ?: "конец"}" +
                    (to?.let { " (${((it - (from ?: 0)) / 60_000).coerceAtLeast(1)} мин)" } ?: "") +
                    ". Он же загрузится в урок как аудио.",
            )
            val prov = sttProvider(s.sttProviderId())
            val model = s.sttModelName(prov)
            fun cost(ms: Long?): String =
                if (useTr) "бесплатно, готовая расшифровка"
                else ms?.takeIf { it > 0 }?.let { sttCost(prov, model, it / 60_000.0) }?.let { "${prov.name}: $it" } ?: prov.name
            if (!bad) Text(
                cost(to?.let { it - (from ?: 0) } ?: (dur - (from ?: 0)).takeIf { dur > 0 }),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
            if (pk.transcript != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Готовая расшифровка подкаста")
                    Hint(if (useTr) "Бесплатно, без распознавания" else "Выключено: распознает сервис из настроек")
                }
                androidx.compose.material3.Switch(checked = useTr, onCheckedChange = { useTr = it })
            }
            androidx.compose.material3.Button(
                onClick = { s.transcribePart(from ?: 0, if (len == null) null else to, useTr) }, enabled = !bad,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (useTr) "Взять кусок" else "Распознать") }
            TextButton(onClick = { s.transcribePart(0, null, useTr) }, Modifier.fillMaxWidth()) {
                Text("Весь файл" + if (dur > 0) " (${clock(dur)}, ${cost(dur)})" else "")
            }
        }
    }
}
