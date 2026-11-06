package com.mshemur.lingqfix

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Default prompts; the user can change them in ⚙ Settings. Placeholders: {lang} {target} {word} {sentence} {text}. */
object Prompts {
    const val WORD = """Ты помогаешь читать текст на языке «{lang}». Переведи слово «{word}» на язык «{target}» именно в том значении, в котором оно употреблено в предложении:
«{sentence}»
Ответь строго JSON без пояснений вокруг:
{"best": "перевод по контексту, 1–3 слова", "options": ["другие возможные значения, до 5"], "note": "очень коротко: начальная форма, грамматика или идиома; пусто, если нечего сказать"}"""

    const val SENTENCE = """Переведи предложение с языка «{lang}» на язык «{target}» точно и естественно. Ответь только переводом, без кавычек и пояснений.

{sentence}"""

    const val RETRANSLATE = """Переведи предложение с языка «{lang}» на язык «{target}» заново. Текущий перевод неудачный:
«{current}»
Сделай другой, более точный и естественный перевод, который передаёт смысл в контексте. Ответь только новым переводом, без кавычек и пояснений.

{sentence}"""

    const val CHAT_SENTENCE = """Ты репетитор языка «{lang}». Ученик читает текст и спрашивает про это предложение:
«{sentence}»
Отвечай на языке «{target}», кратко и по делу: значения слов, грамматика, нюансы, примеры."""

    const val CLEAN = """Ниже текст на языке «{lang}», скопированный из книги, PDF, со скана или с сайта. Очисти его для чтения:
- убери номера страниц, колонтитулы, сноски и номера сносок, ссылки, подписи к картинкам, рекламу, меню и кнопки сайта («поделиться», «читать далее» и т. п.), даты и имена в комментариях и прочий мусор, который не относится к основному тексту;
- склей слова, разорванные переносом, и строки, разорванные посреди предложения; исправь явные ошибки распознавания букв;
- сохрани деление на абзацы и заголовки глав.
Сам текст не меняй: не переписывай, не сокращай, не переводи и не редактируй стиль. Ответь только очищенным текстом, без пояснений.

{text}"""

    const val CHAT_WORD = """Ты репетитор языка «{lang}». Ученик читает текст и спрашивает про «{word}» в этом предложении:
«{sentence}»
Объясняй именно «{word}» (не всё предложение): значение здесь и другие значения, начальная форма и грамматика,
устойчивые выражения, примеры. Отвечай на языке «{target}», кратко и по делу."""

    const val CHAT_TEXT = """Ты репетитор языка «{lang}». Ученик читает этот текст:

{text}

Отвечай на языке «{target}», кратко и по делу."""

    fun fill(p: String, vararg v: Pair<String, String>): String =
        v.fold(p) { acc, (k, value) -> acc.replace("{$k}", value) }
}

class WordMeaning(val best: String, val options: List<String>, val note: String)

/** Google Gemini API (key from aistudio.google.com/apikey). Blocking: call from Dispatchers.IO. */
class Gemini(private val key: String, private val model: String) {
    class Msg(val user: Boolean, val text: String)

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        private const val API = "https://generativelanguage.googleapis.com/v1beta"
        /** Model list of the key, fetched once when a fallback is needed. */
        @Volatile private var modelList: List<String>? = null
    }

    /** Models this key may use for text generation (for the picker in settings). */
    fun models(): List<String> {
        if (key.isBlank()) throw ApiException("Не задан ключ Gemini")
        val out = mutableListOf<String>()
        var token = ""
        do {
            val req = Request.Builder().url("$API/models?pageSize=200" + (if (token.isNotEmpty()) "&pageToken=$token" else ""))
                .header("x-goog-api-key", key).build()
            val (code, text) = LingqApi.http.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
            if (code !in 200..299) throw ApiException("Gemini: HTTP $code ${text.take(200)}")
            val o = JSONObject(text)
            val a = o.optJSONArray("models") ?: JSONArray()
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                val name = m.optString("name").removePrefix("models/")
                if ("generateContent" in methods && name.startsWith("gemini")) out += name
            }
            token = o.optString("nextPageToken")
        } while (token.isNotEmpty())
        return out.sortedDescending()
    }

    fun generate(system: String?, messages: List<Msg>, json: Boolean = false): String {
        if (key.isBlank()) throw ApiException("Не задан ключ Gemini (⚙ Настройки)")
        val m = model.ifBlank { DEFAULT_MODEL }
        fun run(model: String) = try {
            request(model, system, messages, json, lowThinking = true)
        } catch (e: ApiException) {
            // older/newer models may not know the thinking option: try once without it
            if (e.message.orEmpty().contains("HTTP 400") && e.message.orEmpty().contains("hink", ignoreCase = true))
                request(model, system, messages, json, lowThinking = false)
            else throw e
        }
        return try {
            run(m)
        } catch (e: ApiException) {
            // the chosen model is overloaded ("high demand"): answer with another one rather than fail
            if (!Regex("HTTP (503|429)").containsMatchIn(e.message.orEmpty())) throw e
            val other = fallbackModel(m) ?: throw e
            run(other)
        }
    }

    /** Another model while [busy] is overloaded: a flash model first (fast), newest first. */
    private fun fallbackModel(busy: String): String? {
        val all = modelList ?: runCatching { models() }.getOrNull()?.also { modelList = it } ?: return null
        val list = all.filter { it != busy && !Regex("preview|exp|image|tts|live|embed", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        return list.firstOrNull { "flash" in it && "lite" !in it } ?: list.firstOrNull { "flash" in it } ?: list.firstOrNull()
    }

    private fun request(m: String, system: String?, messages: List<Msg>, json: Boolean, lowThinking: Boolean): String {
        val body = JSONObject()
        if (!system.isNullOrBlank()) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        val contents = JSONArray()
        messages.forEach { msg ->
            contents.put(JSONObject().put("role", if (msg.user) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", msg.text))))
        }
        body.put("contents", contents)
        val cfg = JSONObject().put("temperature", 0.3)
        if (json) cfg.put("responseMimeType", "application/json")
        // Flash models answer much faster with little "thinking"
        if (lowThinking && m.contains("flash")) cfg.put(
            "thinkingConfig",
            if (m.startsWith("gemini-2")) JSONObject().put("thinkingBudget", 0) else JSONObject().put("thinkingLevel", "minimal"),
        )
        body.put("generationConfig", cfg)

        val req = Request.Builder()
            .url("$API/models/$m:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        var attempt = 0
        while (true) {
            val (code, text) = LingqApi.http.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
            // overloaded / rate limited: wait 2, 4, 8 s and try again
            if ((code == 429 || code >= 500) && attempt < 3) { Thread.sleep(2000L shl attempt); attempt++; continue }
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw ApiException("Gemini: HTTP $code ${msg ?: text.take(200)}")
            }
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: throw ApiException("Gemini не дал ответа")
            return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
        }
    }

    fun word(prompt: String, lang: String, target: String, word: String, sentence: String): WordMeaning {
        val p = Prompts.fill(prompt, "lang" to lang, "target" to target, "word" to word, "sentence" to sentence)
        val raw = generate(null, listOf(Msg(true, p)), json = true)
        val o = runCatching { JSONObject(raw.substringAfter("{", "").let { "{$it" }.substringBeforeLast("}") + "}") }
            .getOrElse { return WordMeaning(raw.lineSequence().first().take(60), emptyList(), "") }
        val opts = o.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return WordMeaning(o.optString("best"), opts.filter { it.isNotBlank() }, o.optString("note"))
    }

    fun retranslate(prompt: String, lang: String, target: String, sentence: String, current: String): String =
        generate(null, listOf(Msg(true, Prompts.fill(prompt, "lang" to lang, "target" to target, "sentence" to sentence, "current" to current))))

    /** Cleans a piece of copied text (see [Prompts.CLEAN]). */
    fun clean(prompt: String, lang: String, text: String): String =
        generate(null, listOf(Msg(true, Prompts.fill(prompt, "lang" to lang, "text" to text)))).trim()

    fun sentence(prompt: String, lang: String, target: String, sentence: String): String =
        generate(null, listOf(Msg(true, Prompts.fill(prompt, "lang" to lang, "target" to target, "sentence" to sentence))))
}
