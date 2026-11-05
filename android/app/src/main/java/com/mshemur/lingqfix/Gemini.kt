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

    const val CHAT_SENTENCE = """Ты репетитор языка «{lang}». Ученик читает текст и спрашивает про это предложение:
«{sentence}»
Отвечай на языке «{target}», кратко и по делу: значения слов, грамматика, нюансы, примеры."""

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

    fun generate(system: String?, messages: List<Msg>, json: Boolean = false): String {
        if (key.isBlank()) throw ApiException("Не задан ключ Gemini (⚙ Настройки)")
        val m = model.ifBlank { "gemini-2.5-flash" }
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
        // Flash models answer much faster without "thinking"
        if (m.contains("flash")) cfg.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        body.put("generationConfig", cfg)

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        var attempt = 0
        while (true) {
            val (code, text) = LingqApi.http.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
            if ((code == 429 || code >= 500) && attempt < 3) { attempt++; Thread.sleep(2000L * attempt); continue }
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

    fun sentence(prompt: String, lang: String, target: String, sentence: String): String =
        generate(null, listOf(Msg(true, Prompts.fill(prompt, "lang" to lang, "target" to target, "sentence" to sentence))))
}
