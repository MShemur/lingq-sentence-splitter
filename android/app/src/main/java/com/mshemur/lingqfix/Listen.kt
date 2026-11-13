package com.mshemur.lingqfix

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.media3.session.MediaSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale

/**
 * Listening with headphones: a double press of the play/pause button pauses the audio, reads out the translation of
 * the sentence sounding now (text to speech) and plays on. Translations come from the lesson's translation cache
 * (filled ahead by "перевести все предложения" at the podcast import), LingQ's stored ones, or the translator.
 */
object ListenTranslate {
    private class Seg(val text: String, val start: Long, val lingq: String?)

    private val segs = HashMap<String, List<Seg>>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: (() -> Unit)? = null
    @Volatile var speaking = false
        private set
    private var resumeAfter = false
    private val main = Handler(Looper.getMainLooper())

    private fun app(ctx: Context): AppState =
        MainActivity.shared ?: AppState(ctx.applicationContext).also { MainActivity.shared = it }

    private fun parse(paragraphs: JSONArray, target: String): List<Seg> {
        val out = mutableListOf<Seg>()
        for (i in 0 until paragraphs.length()) {
            val ss = paragraphs.getJSONObject(i).optJSONArray("sentences") ?: continue
            for (j in 0 until ss.length()) {
                val s = ss.getJSONObject(j)
                val text = s.optString("text").trim()
                if (text.isEmpty()) continue
                val ts = s.optJSONArray("timestamp")
                val a = if (ts != null && ts.length() >= 2 && !ts.isNull(0)) (ts.optDouble(0, -1.0) * 1000).toLong() else -1L
                var lingq: String? = null
                s.optJSONArray("translations")?.let { ta ->
                    for (k in 0 until ta.length()) {
                        val o = ta.getJSONObject(k)
                        if (lingq == null && o.optString("language") == target) lingq = o.optString("text").trim().ifEmpty { null }
                    }
                }
                out += Seg(text, a, lingq)
            }
        }
        return out
    }

    private suspend fun sentences(app: AppState, lang: String, id: Int): List<Seg> {
        val k = "${lang}_$id"
        segs[k]?.let { return it }
        val o = withContext(Dispatchers.IO) { app.api.editorObj(lang, id) }
        return parse(o.optJSONArray("paragraphs") ?: JSONArray(), app.trLang).also { segs[k] = it }
    }

    private suspend fun translate(app: AppState, lang: String, text: String): String = withContext(Dispatchers.IO) {
        if (app.useGemini) app.geminiFast().sentence(app.prefs.promptSentence, lang, app.trLang, text)
        else app.api.translate(lang, app.trLang, text)
    }.trim()

    /** Double press: the sentence sounding now (the one before if it has only just begun). */
    fun speakCurrent(ctx: Context) {
        val key = AudioEngine.key ?: return
        val lang = key.substringBefore('_')
        val id = key.substringAfter('_').toIntOrNull() ?: return
        val pos = AudioEngine.nowMs()
        resumeAfter = AudioEngine.playing
        AudioEngine.pause()
        speaking = true
        val app = app(ctx)
        AppLog.add("listen", "Двойное нажатие: перевод предложения на ${pos / 1000} с")
        app.scope.launch {
            try {
                val list = sentences(app, lang, id).filter { it.start >= 0 }
                if (list.isEmpty()) { say(ctx, "В этом уроке нет времени предложений"); return@launch }
                var i = list.indexOfLast { it.start <= pos }.coerceAtLeast(0)
                if (i > 0 && pos - list[i].start < 1500) i--
                val s = list[i]
                val cache = TranslationCache(app.ctx, lang, id, app.trLang)
                val saved = withContext(Dispatchers.IO) { cache.load() }
                val tr = saved[s.text] ?: s.lingq ?: translate(app, lang, s.text).also { t ->
                    if (t.isNotBlank()) withContext(Dispatchers.IO) { cache.save(saved + (s.text to t)) }
                }
                say(ctx, tr.ifBlank { "Перевода нет" })
            } catch (e: Throwable) {
                AppLog.error("listen", "Перевод по двойному нажатию", e)
                say(ctx, "Перевод не получился")
            }
        }
    }

    /** Reads [text] in the translation language, then plays on if the audio was playing. */
    private fun say(ctx: Context, text: String) {
        main.post {
            val app = app(ctx)
            val go = {
                tts?.let { t ->
                    t.language = Locale.forLanguageTag(app.trLang)
                    t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tr")
                }
                Unit
            }
            val t = tts
            if (t == null) {
                pendingSpeak = go
                tts = TextToSpeech(ctx.applicationContext) { st ->
                    main.post {
                        ttsReady = st == TextToSpeech.SUCCESS
                        if (ttsReady) pendingSpeak?.invoke() else { AppLog.add("listen", "Синтез речи недоступен"); done() }
                        pendingSpeak = null
                    }
                }.apply {
                    setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { main.post { done() } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { main.post { done() } }
                    })
                }
            } else if (ttsReady) go() else pendingSpeak = go
        }
    }

    private fun done() {
        speaking = false
        if (resumeAfter) { resumeAfter = false; AudioEngine.play() }
    }

    /** A single press while reading: stop reading and play on. */
    fun stopSpeaking() { tts?.stop(); done() }

    /** Translates every sentence of the lesson ahead (kept in its translation cache, the reader uses it too). */
    suspend fun translateAll(app: AppState, lang: String, id: Int, onProgress: (Int, Int) -> Unit) {
        segs.remove("${lang}_$id")
        val list = sentences(app, lang, id)
        val cache = TranslationCache(app.ctx, lang, id, app.trLang)
        val saved = HashMap(withContext(Dispatchers.IO) { cache.load() })
        val todo = list.filter { it.lingq == null && saved[it.text] == null }.distinctBy { it.text }
        var n = 0
        val gate = Semaphore(4)
        coroutineScope {
            todo.map { s ->
                async {
                    gate.withPermit {
                        val t = runCatching { translate(app, lang, s.text) }.getOrDefault("")
                        val snap = synchronized(saved) {
                            if (t.isNotBlank()) saved[s.text] = t
                            n++
                            if (n % 20 == 0) HashMap(saved) else null
                        }
                        onProgress(n, todo.size)
                        if (snap != null) withContext(Dispatchers.IO) { cache.save(snap) }
                    }
                }
            }.awaitAll()
        }
        withContext(Dispatchers.IO) { cache.save(HashMap(saved)) }
        AppLog.add("listen", "Урок $id: переведено заранее ${todo.size} предложений")
    }
}

/**
 * Headphone play/pause button: one press = play / pause (after a short wait for a second press),
 * two presses = translation of the sentence sounding now ([ListenTranslate]). Other buttons as Media3 does them.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HeadsetKeys(private val ctx: Context) : MediaSession.Callback {
    private val main = Handler(Looper.getMainLooper())
    private var single: Runnable? = null

    override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val e = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        val code = e.keyCode
        if (code != KeyEvent.KEYCODE_HEADSETHOOK && code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            code != KeyEvent.KEYCODE_MEDIA_PLAY && code != KeyEvent.KEYCODE_MEDIA_PAUSE
        ) return false
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true
        val first = single
        if (first != null) {
            main.removeCallbacks(first); single = null
            if (!ListenTranslate.speaking) ListenTranslate.speakCurrent(ctx)
            return true
        }
        val r = Runnable {
            single = null
            when {
                ListenTranslate.speaking -> ListenTranslate.stopSpeaking()
                code == KeyEvent.KEYCODE_MEDIA_PLAY -> AudioEngine.play()
                code == KeyEvent.KEYCODE_MEDIA_PAUSE -> AudioEngine.pause()
                else -> AudioEngine.toggle()
            }
        }
        single = r
        main.postDelayed(r, 450)
        return true
    }
}
