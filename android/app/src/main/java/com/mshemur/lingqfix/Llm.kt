package com.mshemur.lingqfix

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** A language model the reader talks to (Gemini or an OpenAI-compatible service). Blocking: use from Dispatchers.IO. */
abstract class Llm {
    /** Models of this key that can write text (for the pickers). */
    abstract fun models(): List<String>

    abstract fun generate(system: String?, messages: List<Gemini.Msg>, json: Boolean = false): String

    /** Runs one request and writes it to the journal (model, time, size of the answer or the error). */
    protected fun <T> logged(what: String, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        return try {
            block().also { r -> AppLog.add("ai", "$what ✓ ${System.currentTimeMillis() - t0} мс, ${r.toString().length} симв.") }
        } catch (e: Throwable) {
            AppLog.error("ai", "$what ✗ ${System.currentTimeMillis() - t0} мс", e); throw e
        }
    }

    /** The model's reasoning for the last answer, if it sends one (DeepSeek reasoner etc.). */
    @Volatile var lastReasoning: String? = null
        protected set

    fun word(prompt: String, lang: String, target: String, word: String, sentence: String): WordMeaning {
        val p = Prompts.fill(prompt, "lang" to lang, "target" to target, "word" to word, "sentence" to sentence)
        val raw = generate(null, listOf(Gemini.Msg(true, p)), json = true)
        val o = runCatching { JSONObject(raw.substringAfter("{", "").let { "{$it" }.substringBeforeLast("}") + "}") }
            .getOrElse { return WordMeaning(raw.lineSequence().first().take(60), emptyList(), "") }
        val opts = o.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return WordMeaning(o.optString("best"), opts.filter { it.isNotBlank() }, o.optString("note"), o.optString("pair").trim())
    }

    fun retranslate(prompt: String, lang: String, target: String, sentence: String, current: String): String =
        generate(null, listOf(Gemini.Msg(true, Prompts.fill(prompt, "lang" to lang, "target" to target, "sentence" to sentence, "current" to current))))

    /** Cleans a piece of copied text (see [Prompts.CLEAN]). */
    fun clean(prompt: String, lang: String, text: String): String =
        generate(null, listOf(Gemini.Msg(true, Prompts.fill(prompt, "lang" to lang, "text" to text)))).trim()

    /** CEFR level of a text: LingQ level 1..6 (A1..C2) and a short reason. */
    fun level(prompt: String, lang: String, text: String): Pair<Int, String> {
        val raw = generate(null, listOf(Gemini.Msg(true, Prompts.fill(prompt, "lang" to lang, "text" to text.take(6000)))), json = true)
        val o = runCatching { JSONObject("{" + raw.substringAfter("{", "").substringBeforeLast("}") + "}") }.getOrNull()
        val cefr = (o?.optString("cefr") ?: raw).uppercase()
        val i = Regex("[ABC][12]").find(cefr)?.value?.let { CEFR.indexOf(it) } ?: -1
        if (i < 0) throw ApiException("ИИ не назвал уровень: ${raw.take(120)}")
        return (i + 1) to o?.optString("why").orEmpty()
    }

    fun sentence(prompt: String, lang: String, target: String, sentence: String): String =
        generate(null, listOf(Gemini.Msg(true, Prompts.fill(prompt, "lang" to lang, "target" to target, "sentence" to sentence))))
}

/** LingQ lesson levels 1..6 and their CEFR names. */
val CEFR = listOf("A1", "A2", "B1", "B2", "C1", "C2")
val LINGQ_LEVELS = listOf("Beginner 1", "Beginner 2", "Intermediate 1", "Intermediate 2", "Advanced 1", "Advanced 2")

fun levelName(level: Int) = "${CEFR.getOrElse(level - 1) { "?" }} · ${LINGQ_LEVELS.getOrElse(level - 1) { "" }}"

/** Ready addresses of OpenAI-compatible services. */
val AI_PRESETS = listOf(
    "DeepSeek" to "https://api.deepseek.com",
    "Qwen" to "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
    "OpenRouter" to "https://openrouter.ai/api/v1",
    "Groq" to "https://api.groq.com/openai/v1",
    "Mistral" to "https://api.mistral.ai/v1",
)

/** Any service with the OpenAI chat API: DeepSeek, Qwen (DashScope), OpenRouter, Groq, Mistral… */
class OpenAiCompat(base: String, private val key: String, private val model: String) : Llm() {
    private val base = base.trim().trimEnd('/')

    private fun check() {
        if (base.isBlank()) throw ApiException("Не задан адрес сервиса ИИ (⚙ Настройки → Перевод)")
        if (key.isBlank()) throw ApiException("Не задан ключ сервиса ИИ (⚙ Настройки → Перевод)")
    }

    override fun models(): List<String> {
        check()
        val req = Request.Builder().url("$base/models").header("Authorization", "Bearer $key").build()
        val (code, text) = LingqApi.http.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
        if (code !in 200..299) throw ApiException("ИИ: HTTP $code ${text.take(200)}")
        val a = JSONObject(text).optJSONArray("data") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).optString("id") }
            .filter { it.isNotBlank() && !Regex("embed|whisper|tts|audio|image|vision-preview|moderation|rerank", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .sorted()
    }

    override fun generate(system: String?, messages: List<Gemini.Msg>, json: Boolean): String {
        check()
        if (model.isBlank()) throw ApiException("Не выбрана модель ИИ (⚙ Настройки → Перевод)")
        return logged("${AppLog.short(base)} $model") {
            try {
                request(system, messages, json)
            } catch (e: ApiException) {
                // not every service knows JSON mode: ask again without it (the prompt asks for JSON anyway)
                if (json && e.message.orEmpty().contains("HTTP 400")) request(system, messages, false) else throw e
            }
        }
    }

    private fun request(system: String?, messages: List<Gemini.Msg>, json: Boolean): String {
        val msgs = JSONArray()
        if (!system.isNullOrBlank()) msgs.put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { m -> msgs.put(JSONObject().put("role", if (m.user) "user" else "assistant").put("content", m.text)) }
        val body = JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.3)
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        val req = Request.Builder().url("$base/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        var attempt = 0
        while (true) {
            val (code, text) = LingqApi.http.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
            // overloaded / rate limited: wait 2, 4, 8 s and try again
            if ((code == 429 || code >= 500) && attempt < 3) { Thread.sleep(2000L shl attempt); attempt++; continue }
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw ApiException("ИИ: HTTP $code ${msg ?: text.take(200)}")
            }
            val m = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw ApiException("ИИ не дал ответа")
            lastReasoning = m.optString("reasoning_content").ifBlank { m.optString("reasoning") }.ifBlank { null }
            return m.optString("content").trim()
        }
    }
}
