package com.mshemur.lingqfix

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

// Text to speech: the system voice (free) or a cloud voice (Gemini, OpenAI, ElevenLabs) with a file cache.
// Settings → Озвучка (Settings.kt). Keys are the ones of the other screens: Gemini key, the speech-recognition keys.

class TtsProvider(val id: String, val name: String, val label: String)

val TTS_PROVIDERS = listOf(
    TtsProvider("system", "системный голос", "Системный голос — бесплатно"),
    TtsProvider("gemini", "Gemini", "Gemini"),
    TtsProvider("openai", "OpenAI", "OpenAI"),
    TtsProvider("elevenlabs", "ElevenLabs", "ElevenLabs"),
    TtsProvider("openrouter", "OpenRouter", "OpenRouter — много моделей озвучки"),
)

val TTS_VOICES_GEMINI = listOf("Kore", "Puck", "Charon", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr")
val TTS_VOICES_OPENAI = listOf("alloy", "ash", "coral", "echo", "nova", "onyx", "sage", "shimmer")
val TTS_MODELS_OPENAI = listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd")

/** Defaults of the prefs ttsModel.<id> / ttsVoice.<id>. Gemini's model is resolved from the key's list when blank. */
val TTS_MODEL_DEFAULT = mapOf(
    "openai" to "gpt-4o-mini-tts", "elevenlabs" to "eleven_multilingual_v2", "openrouter" to "google/gemini-3.8-flash-tts",
)
val TTS_VOICE_DEFAULT = mapOf(
    "gemini" to "Kore", "openai" to "alloy", "elevenlabs" to "JBFqnCBsd6RMkjVDRZzb", "openrouter" to "Kore",
)

/** OpenRouter key: the own one (prefs ttsKey.openrouter), else the key of «Другой ИИ» when that base is OpenRouter. */
fun openRouterKey(p: Prefs): String =
    p.ttsKey("openrouter").ifBlank { if (p.aiBase.contains("openrouter.ai")) p.aiKey else "" }

/** Speed goes to the server for OpenAI, and for OpenRouter models that list a speed parameter; else the phone does it. */
fun ttsOnServer(p: Prefs, provider: String): Boolean = when (provider) {
    "openai" -> true
    "openrouter" -> OpenRouterTts.known(p.ttsModel("openrouter").ifBlank { TTS_MODEL_DEFAULT["openrouter"].orEmpty() })?.speed == true
    else -> false
}

/** Line under the OpenRouter provider in Озвучка: which key is used, or that none is set. */
fun openRouterKeyNote(p: Prefs): String = when {
    p.ttsKey("openrouter").isNotBlank() -> "Используется ключ OpenRouter, введённый здесь."
    openRouterKey(p).isNotBlank() -> "Используется ключ из «Перевод и ИИ» (Другой ИИ — OpenRouter), пока поле ниже пустое."
    else -> "Ключ OpenRouter не задан: введите его ниже или в «Перевод и ИИ» (Другой ИИ — OpenRouter)."
}

/**
 * One speech model of OpenRouter. voices, formats and speed come from the speech models list;
 * perMinute = estimated price of a minute of speech in USD (null: no price known).
 */
class OrSpeechModel(
    val id: String,
    val name: String,
    val voices: List<String>,
    val formats: List<String>,
    val speed: Boolean,
    val perMinute: Double? = null,
)

/** Free: a ":free" id, or a price of 0. */
fun speechFree(m: OrSpeechModel): Boolean = m.id.endsWith(":free") || m.perMinute == 0.0

/** Price part of a chip: «бесплатно», «~X ¢/мин» (cents per minute), «<0,1 ¢/мин», or "" when the price is unknown. */
fun speechPriceNote(m: OrSpeechModel): String {
    if (speechFree(m)) return "бесплатно"
    val p = m.perMinute ?: return ""
    val cents = p * 100
    if (cents < 0.05) return "<0,1 ¢/мин"
    return "~" + "%.1f".format(cents).replace('.', ',') + " ¢/мин"
}

/** Chip label: the name, then the price note. */
fun speechLabel(m: OrSpeechModel): String {
    val note = speechPriceNote(m)
    return if (note.isEmpty()) m.name else m.name + "  · " + note
}

/** Free models first, then by price ascending; models without a known price last. */
fun sortSpeechModels(list: List<OrSpeechModel>): List<OrSpeechModel> = list.sortedWith(
    compareBy<OrSpeechModel>(
        { !speechFree(it) && it.perMinute == null },
        { if (speechFree(it)) 0.0 else it.perMinute ?: 0.0 },
    ),
)

/** OpenRouter's catalogue of speech models (public lists, no key). Use from Dispatchers.IO. */
object OpenRouterTts {
    private const val LIST = "https://openrouter.ai/api/v1/audio/speech/models"
    /** Speech-output models with ids (incl. ":free" variants) and prices. */
    private const val CATALOGUE = "https://openrouter.ai/api/v1/models?output_modalities=speech"
    @Volatile private var cache: List<OrSpeechModel>? = null

    /**
     * The model set is the catalogue's (it has the ":free" ids and prices); voices, formats and speed come from the
     * speech list by id. If the catalogue call fails, the speech list is the set. Kept for the session unless [refresh].
     */
    fun models(refresh: Boolean = false): List<OrSpeechModel> {
        if (!refresh) cache?.let { return it }
        val speech = dataOf(getJson(LIST)).mapNotNull { parse(it) }
        val byId = speech.associateBy { it.id }
        val catalogue = runCatching { dataOf(getJson(CATALOGUE)) }.getOrDefault(emptyList())
        val list = if (catalogue.isEmpty()) speech else catalogue.mapNotNull { fromCatalogue(it, byId) }
        if (list.isNotEmpty()) cache = list
        return list
    }

    /** The model from the list (loads the list when it is not there yet). Not for the main thread. */
    fun find(id: String): OrSpeechModel? = (cache ?: models()).firstOrNull { it.id == id }

    /** The model from the list already loaded, or null (no network). */
    fun known(id: String): OrSpeechModel? = cache?.firstOrNull { it.id == id }

    /** Answer format asked from OpenRouter: mp3 if the model has it, else wav, else pcm (wrapped into WAV). */
    fun format(m: OrSpeechModel?): String = when {
        m == null || m.formats.isEmpty() -> "mp3"
        "mp3" in m.formats -> "mp3"
        "wav" in m.formats -> "wav"
        "pcm" in m.formats -> "pcm"
        else -> m.formats[0]
    }

    /** Sample rate of raw pcm answers: 44.1 kHz for heygen models, else 24 kHz. */
    fun pcmRate(id: String): Int = if (id.startsWith("heygen/")) 44100 else 24000

    private fun parse(o: JSONObject): OrSpeechModel? {
        val id = o.optString("id").trim().takeIf { it.isNotEmpty() } ?: return null
        val mods = o.optJSONArray("output_modalities") ?: JSONArray()
        val sp = (0 until mods.length()).mapNotNull { mods.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "speech" }?.optJSONObject("supported_parameters") ?: JSONObject()
        val voices = sp.optJSONObject("voice")?.optJSONArray("values")?.let { strings(it) }.orEmpty()
        val formats = sp.optJSONObject("response_format")?.optJSONArray("values")?.let { strings(it) }.orEmpty()
        return OrSpeechModel(id, o.optString("name").trim().ifBlank { id }, voices, formats, sp.has("speed"))
    }

    /** The speech entry for a catalogue id: the same id, else without ":free", then without a trailing "-free". */
    private fun speechFor(id: String, byId: Map<String, OrSpeechModel>): OrSpeechModel? {
        byId[id]?.let { return it }
        return byId[id.removeSuffix(":free").removeSuffix("-free")]
    }

    /** One catalogue entry: name and price from /models, the rest from the speech entry (empty when there is none). */
    private fun fromCatalogue(o: JSONObject, byId: Map<String, OrSpeechModel>): OrSpeechModel? {
        val id = o.optString("id").trim().takeIf { it.isNotEmpty() } ?: return null
        val base = speechFor(id, byId)
        val pr = o.optJSONObject("pricing")
        val prompt = pr?.optString("prompt")?.toDoubleOrNull()
        val completion = pr?.optString("completion")?.toDoubleOrNull()
        // audio-token models (completion > 0): ~1500 audio tokens and ~250 text tokens per minute; else per-minute price in prompt
        val minute: Double? = when {
            prompt == null && completion == null -> null
            (completion ?: 0.0) > 0.0 -> (completion ?: 0.0) * 1500 + (prompt ?: 0.0) * 250
            else -> (prompt ?: 0.0) * 1000
        }
        return OrSpeechModel(
            id,
            o.optString("name").trim().ifBlank { id },
            base?.voices.orEmpty(),
            base?.formats.orEmpty(),
            base?.speed == true,
            minute,
        )
    }

    private fun dataOf(root: Any): List<JSONObject> {
        val a = (root as? JSONObject)?.optJSONArray("data") ?: (root as? JSONArray) ?: JSONArray()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }
    }

    private fun getJson(url: String): Any {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        val (code, text) = LingqApi.http.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
        if (code !in 200..299) throw ApiException("OpenRouter: HTTP $code ${text.take(200)}")
        return JSONTokener(text).nextValue()
    }

    private fun strings(a: JSONArray): List<String> =
        (0 until a.length()).mapNotNull { i -> a.optString(i).trim().takeIf { it.isNotEmpty() && it != "null" } }
}

fun ttsProviderName(id: String): String = TTS_PROVIDERS.firstOrNull { it.id == id }?.name ?: id

/** Root row of Settings: «Системный голос · 1,0×» or «Gemini · ключ задан · 1,0×». */
fun ttsSummary(p: Prefs): String {
    val speed = "%.1f×".format(p.ttsRate).replace('.', ',')
    val id = p.ttsProvider
    if (id == "system") return "Системный голос · $speed"
    val key = if (Tts.keyOf(p, id).isBlank()) " · ключ не задан" else " · ключ задан"
    return ttsProviderName(id) + key + " · " + speed
}

object Tts {
    private const val GEMINI_API = "https://generativelanguage.googleapis.com/v1beta"
    private const val CACHE_LIMIT = 50L * 1024 * 1024
    private const val OPENROUTER_SPEECH = "https://openrouter.ai/api/v1/audio/speech"
    private val CLOUD = setOf("gemini", "openai", "elevenlabs", "openrouter")
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy { LingqApi.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build() }

    private val _speaking = mutableStateOf(false)
    /** true while something is being spoken (incl. loading). */
    val speaking: State<Boolean> = _speaking

    /** Changes on every stop: callbacks of an older speech are ignored. */
    private var gen = 0
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var sys: TextToSpeech? = null
    private var sysReady = false
    private var sysPending: (() -> Unit)? = null

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Key of [provider] as the other screens store it (Gemini: translation key; OpenAI / ElevenLabs: speech keys). */
    fun keyOf(p: Prefs, provider: String): String = when (provider) {
        "gemini" -> p.geminiKey
        "openai" -> sttKeyFor(p, sttProvider("openai"))
        "elevenlabs" -> sttKeyFor(p, sttProvider("elevenlabs"))
        "openrouter" -> openRouterKey(p)
        else -> ""
    }

    /** Model list pick: the first "tts" + "flash" model of the key, else the preview model. */
    fun pickGeminiModel(models: List<String>): String =
        models.firstOrNull { "tts" in it && "flash" in it } ?: "gemini-2.5-flash-preview-tts"

    /** Speaks [text] in [lang] (BCP 47 tag, e.g. "de"). Stops anything playing first; strips * markup. */
    fun speak(app: AppState, text: String, lang: String) {
        val clean = text.replace("*", "").trim()
        onMain {
            stop()
            if (clean.isEmpty()) return@onMain
            _speaking.value = true
            if (AudioEngine.playing) AudioEngine.pause()
            val g = gen
            val p = app.prefs
            val provider = p.ttsProvider
            if (provider !in CLOUD) { speakSystem(app, clean, lang, g); return@onMain }
            val name = ttsProviderName(provider)
            val key = keyOf(p, provider)
            if (key.isBlank()) {
                app.say("Нет ключа для озвучки $name — читаю системным голосом")
                speakSystem(app, clean, lang, g)
                return@onMain
            }
            val rate = p.ttsRate
            job = app.scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) { cloudFile(app, provider, key, clean, rate) }
                    if (g != gen) return@launch
                    play(app, file, provider, rate, clean, lang, g)
                } catch (e: Exception) {
                    if (e is CancellationException || g != gen) return@launch
                    releasePlayer()
                    AppLog.error("tts", "Озвучка $name", e)
                    app.say("Озвучка $name не сработала: ${e.message ?: "ошибка"}. Читаю системным голосом")
                    speakSystem(app, clean, lang, g)
                }
            }
        }
    }

    fun stop() = onMain {
        gen++
        job?.cancel()
        job = null
        sysPending = null
        releasePlayer()
        runCatching { sys?.stop() }
        _speaking.value = false
    }

    private fun finish() {
        releasePlayer()
        _speaking.value = false
    }

    private fun releasePlayer() {
        val mp = player ?: return
        player = null
        runCatching { mp.stop() }
        mp.release()
    }

    // ---- system voice ----

    private fun speakSystem(app: AppState, text: String, lang: String, g: Int) {
        val rate = app.prefs.ttsRate
        val go: () -> Unit = {
            val t = sys
            if (t != null && sysReady && g == gen) {
                if (t.setLanguage(Locale.forLanguageTag(lang)) < TextToSpeech.LANG_AVAILABLE) {
                    AppLog.add("tts", "Нет системного голоса для языка $lang")
                }
                t.setSpeechRate(rate)
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts$g")
            }
        }
        val t = sys
        if (t == null) {
            sysReady = false
            sysPending = go
            sys = TextToSpeech(app.ctx.applicationContext) { st ->
                main.post {
                    sysReady = st == TextToSpeech.SUCCESS
                    val pending = sysPending
                    sysPending = null
                    if (sysReady) {
                        pending?.invoke()
                    } else {
                        // a failed engine is dropped, the next speech creates a new one
                        sys?.shutdown()
                        sys = null
                        AppLog.add("tts", "Системный синтез речи недоступен")
                        app.say("Системный синтез речи недоступен")
                        _speaking.value = false
                    }
                }
            }.apply {
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { main.post { if (utteranceId == "tts$gen") finish() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { if (utteranceId == "tts$gen") finish() } }
                })
            }
        } else if (sysReady) go() else sysPending = go
    }

    // ---- cloud voices ----

    /** The audio file of [text] (from the cache, or fetched and cached). Runs on Dispatchers.IO. */
    private fun cloudFile(app: AppState, provider: String, key: String, text: String, rate: Float): File {
        val p = app.prefs
        val model = when (provider) {
            "gemini" -> geminiModel(p, key)
            "openai" -> p.ttsModel("openai").ifBlank { TTS_MODEL_DEFAULT["openai"].orEmpty() }
            "openrouter" -> p.ttsModel("openrouter").ifBlank { TTS_MODEL_DEFAULT["openrouter"].orEmpty() }
            else -> TTS_MODEL_DEFAULT["elevenlabs"].orEmpty()
        }
        val voice = p.ttsVoice(provider).ifBlank { TTS_VOICE_DEFAULT[provider].orEmpty() }
        val dir = File(app.ctx.filesDir, "tts").apply { mkdirs() }
        // OpenAI and OpenRouter get the speed in the request, so the speed is part of their cache key
        val id = sha1(
            when (provider) {
                "openrouter" -> "$provider|$model|$voice|$rate|$text"
                "openai" -> "$provider|$model|$voice|$text|$rate"
                else -> "$provider|$model|$voice|$text"
            },
        )
        val orModel = if (provider == "openrouter") OpenRouterTts.find(model) else null
        val ext = when (provider) {
            "gemini" -> "wav"
            "openrouter" -> if (OpenRouterTts.format(orModel) in setOf("wav", "pcm")) "wav" else "mp3"
            else -> "mp3"
        }
        val f = File(dir, "$id.$ext")
        if (f.length() > 0) { f.setLastModified(System.currentTimeMillis()); return f }
        val bytes = when (provider) {
            "gemini" -> geminiSpeech(key, model, voice, text)
            "openai" -> openAiSpeech(key, model, voice, text, rate)
            "openrouter" -> openRouterSpeech(key, orModel, model, voice, text, rate)
            else -> elevenSpeech(key, voice, text)
        }
        val tmp = File(dir, "$id.part")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
        prune(dir)
        return f
    }

    private fun geminiModel(p: Prefs, key: String): String {
        p.ttsModel("gemini").takeIf { it.isNotBlank() }?.let { return it }
        return pickGeminiModel(Gemini(key, "").models()).also { p.setTtsModel("gemini", it) }
    }

    /** Gemini TTS: the answer holds the audio as base64 PCM (s16le mono, 24 kHz unless mimeType says rate=). */
    private fun geminiSpeech(key: String, model: String, voice: String, text: String): ByteArray {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put(
                "generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig", JSONObject().put(
                            "voiceConfig", JSONObject().put(
                                "prebuiltVoiceConfig", JSONObject().put("voiceName", voice),
                            ),
                        ),
                    ),
            )
        val resp = JSONObject(String(post("$GEMINI_API/models/$model:generateContent", mapOf("x-goog-api-key" to key), body.toString(), "Gemini"), Charsets.UTF_8))
        val parts = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        var data = ""
        var mime = ""
        for (i in 0 until parts.length()) {
            val inl = parts.optJSONObject(i)?.let { it.optJSONObject("inlineData") ?: it.optJSONObject("inline_data") } ?: continue
            val d = inl.optString("data")
            if (d.isNotEmpty()) {
                data = d
                mime = inl.optString("mimeType").ifEmpty { inl.optString("mime_type") }
                break
            }
        }
        if (data.isEmpty()) throw ApiException("Gemini не вернул звук (${resp.toString().take(160)})")
        val raw = Base64.decode(data, Base64.DEFAULT)
        if (mime.contains("wav", ignoreCase = true)) return raw
        val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
        return wav(raw, rate)
    }

    private fun openAiSpeech(key: String, model: String, voice: String, text: String, rate: Float): ByteArray {
        val json = JSONObject().put("model", model).put("voice", voice).put("input", text)
            .put("response_format", "mp3").put("speed", rate.toDouble())
        return post("https://api.openai.com/v1/audio/speech", mapOf("Authorization" to "Bearer $key"), json.toString(), "OpenAI")
    }

    /** OpenRouter speech: the answer is the audio itself (mp3, or wav for Gemini models); errors come as JSON. */
    private fun openRouterSpeech(key: String, m: OrSpeechModel?, model: String, voice: String, text: String, rate: Float): ByteArray {
        val format = OpenRouterTts.format(m)
        val json = JSONObject().put("model", model).put("input", text).put("voice", voice).put("response_format", format)
        // speed only when the model lists it; otherwise the phone speeds the voice up
        if (m?.speed == true) json.put("speed", rate.toDouble())
        val bytes = post(
            OPENROUTER_SPEECH,
            mapOf("Authorization" to "Bearer $key", "X-OpenRouter-Title" to "SatzLoop"),
            json.toString(),
            "OpenRouter",
        )
        // audio never starts with "{": a JSON body here is an error that came with status 200
        if (bytes.isEmpty() || bytes[0] == '{'.code.toByte()) throw ApiException("OpenRouter вернул не звук")
        // raw pcm has no header: it is wrapped into a WAV so the player can read it
        return if (format == "pcm") wav(bytes, OpenRouterTts.pcmRate(model)) else bytes
    }

    private fun elevenSpeech(key: String, voice: String, text: String): ByteArray {
        val url = "https://api.elevenlabs.io/v1/text-to-speech/" + URLEncoder.encode(voice, "UTF-8") + "?output_format=mp3_44100_128"
        val json = JSONObject().put("text", text).put("model_id", TTS_MODEL_DEFAULT["elevenlabs"].orEmpty())
        return post(url, mapOf("xi-api-key" to key, "Accept" to "audio/mpeg"), json.toString(), "ElevenLabs")
    }

    private fun post(url: String, headers: Map<String, String>, json: String, what: String): ByteArray {
        val b = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        val (code, bytes) = client.newCall(b.build()).execute().use { r -> r.code to (r.body?.bytes() ?: ByteArray(0)) }
        if (code !in 200..299) throw ApiException("$what: HTTP $code ${errorText(bytes)}")
        return bytes
    }

    /** The message of a JSON error ({"error":{"message":...}} or {"message":...}), else the body's start. */
    private fun errorText(bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8)
        val o = runCatching { JSONObject(text) }.getOrNull()
        val msg = o?.let { it.optJSONObject("error")?.optString("message") ?: it.optString("message") }
        return (if (msg.isNullOrBlank()) text else msg).take(200)
    }

    /** Wraps 16-bit mono PCM into a WAV file (44-byte header). */
    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return ByteArrayOutputStream(44 + pcm.size).apply { write(h.array()); write(pcm) }.toByteArray()
    }

    /** Keeps the cache at most [CACHE_LIMIT]: the least recently used files go first. */
    private fun prune(dir: File) {
        val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_LIMIT) break
            total -= f.length()
            f.delete()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun play(app: AppState, file: File, provider: String, rate: Float, text: String, lang: String, g: Int) {
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        mp.setDataSource(file.absolutePath)
        mp.setOnPreparedListener { m ->
            if (g != gen) return@setOnPreparedListener
            // OpenAI and OpenRouter already speak at the chosen speed; the others are sped up here
            if (!ttsOnServer(app.prefs, provider) && rate != 1f) runCatching { m.setPlaybackParams(PlaybackParams().setSpeed(rate).setPitch(1f)) }
            m.start()
        }
        mp.setOnCompletionListener { if (g == gen) finish() }
        mp.setOnErrorListener { _, what, _ ->
            AppLog.add("tts", "Плеер: ошибка $what")
            if (g == gen) {
                releasePlayer()
                speakSystem(app, text, lang, g)
            }
            true
        }
        mp.prepareAsync()
    }
}
