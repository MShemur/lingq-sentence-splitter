package com.mshemur.lingqfix

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

/** LingQ REST API (same endpoints as lingq_fix_splits.py). All calls are blocking: use from Dispatchers.IO. */
class LingqApi(private val key: String) {
    companion object {
        private const val BASE = "https://www.lingq.com/api"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    fun call(method: String, url: String, body: String? = null): String {
        if (key.isBlank()) throw ApiException("Не задан API-ключ LingQ (⚙ Настройки)")
        var attempt = 0
        val t0 = System.currentTimeMillis()
        while (true) {
            val req = Request.Builder().url(url)
                .header("Authorization", "Token $key")
                .header("Accept", "application/json")
                // Cloudflare rejects some default client signatures (error 1010)
                .header("User-Agent", "lingq-lesson-fixer/1.0")
                .method(method, body?.toRequestBody(JSON_TYPE))
                .build()
            val (code, text, retryAfter) = try {
                http.newCall(req).execute().use { r -> Triple(r.code, r.body?.string().orEmpty(), r.header("Retry-After")) }
            } catch (e: java.io.IOException) {
                AppLog.error("lingq", "$method ${AppLog.short(url)}", e); throw e
            }
            AppLog.add("lingq", "$method ${AppLog.short(url)} → $code (${System.currentTimeMillis() - t0} мс)")
            if ((code == 429 || code >= 500) && attempt < 4) {
                attempt++
                Thread.sleep(1000L * (retryAfter?.toLongOrNull() ?: (5L * attempt)))
                continue
            }
            if (code == 401) throw ApiException("LingQ не принял API-ключ (HTTP 401)")
            if (code !in 200..299) throw ApiException("LingQ: HTTP $code ${text.take(200)}")
            return text
        }
    }

    private fun obj(url: String) = JSONObject(call("GET", url))

    private fun pages(first: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var url: String? = first
        while (url != null) {
            val d = obj(url)
            val r = d.optJSONArray("results") ?: JSONArray()
            for (i in 0 until r.length()) out += r.getJSONObject(i)
            url = if (d.isNull("next")) null else d.getString("next")
        }
        return out
    }

    fun languages(): List<Language> = pages("$BASE/v2/contexts/").mapNotNull {
        val l = it.optJSONObject("language") ?: return@mapNotNull null
        Language(l.getString("code"), l.optString("title", l.getString("code")))
    }

    fun myCourses(lang: String): List<Course> =
        pages("$BASE/v3/$lang/collections/my/?page=1&page_size=100&sort=recentUsed")
            .map { Course(it.getInt("id"), it.optString("title"), it.optString("imageUrl")) }

    /** Lessons opened lately (LingQ's "my lessons" shelf, most recent first). */
    fun recentLessons(lang: String, count: Int = 20): List<Lesson> {
        val a = obj("$BASE/v3/$lang/search/?shelf=my_lessons&sortBy=recentlyOpened&page=1&page_size=$count")
            .optJSONArray("results") ?: JSONArray()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            lessonOf(o).copy(opened = parseTime(o.optString("openDate")))
        }
    }

    private fun parseTime(s: String): Long = if (s.isBlank()) 0L else runCatching {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    }.recoverCatching { java.time.Instant.parse(s).toEpochMilli() }
        .recoverCatching { java.time.LocalDateTime.parse(s).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
        .getOrDefault(0L)

    private fun lessonOf(o: JSONObject) = Lesson(
        o.getInt("id"), o.optString("title"), o.optString("imageUrl"), o.optString("level"),
        o.optInt("wordCount"), o.optInt("newWordsCount"), o.optString("collectionTitle"),
    )

    /** Course title (may be empty) and its lessons in course order. */
    fun lessons(lang: String, course: Int): Pair<String, List<Lesson>> {
        val all = pages("$BASE/v3/$lang/collections/$course/lessons/?page=1&page_size=100&sortBy=pos")
        val title = all.firstOrNull()?.optString("collectionTitle").orEmpty()
        return title to all.map { lessonOf(it) }
    }

    /**
     * After an import LingQ tokenizes the text for a while (lesson status "I", GET answers HTTP 400
     * "locked") and then re-splits the sentences, overwriting anything saved before. Wait until done.
     */
    fun waitProcessed(lang: String, id: Int, timeoutMs: Long = 180_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val status = try { obj("$BASE/v3/$lang/lessons/$id/").optString("status") } catch (e: ApiException) { "locked" }
            if (status != "I" && status != "locked") return
            Thread.sleep(3000)
        }
        throw ApiException("LingQ слишком долго обрабатывает урок $id — проверьте его позже через «Исправить курс»")
    }

    /** Popular user meanings of a word (most popular first), as in LingQ's word pop-up. */
    fun hints(lang: String, term: String, locale: String): List<String> {
        val url = "$BASE/v2/$lang/hints/search/".toHttpUrl().newBuilder()
            .addQueryParameter("term", term).addQueryParameter("locale", locale).build().toString()
        val a = JSONArray(call("GET", url))
        val out = LinkedHashMap<String, String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optBoolean("flagged")) continue
            val t = o.optString("text").trim()
            if (t.isNotEmpty()) out.putIfAbsent(t.lowercase(), t)
        }
        return out.values.toList()
    }

    /** Machine translation (LingQ's translate service). */
    fun translate(lang: String, target: String, text: String): String {
        val body = JSONObject().put("source", lang).put("target", target).put("text", text)
        val d = JSONObject(call("POST", "$BASE/v2/$lang/translate/", body.toString()))
        return d.optJSONArray("translations")?.optJSONObject(0)?.optString("translatedText").orEmpty()
    }

    /**
     * LingQ's "retranslate sentence" (ChatGPT): translates sentence [index] (1-based, as in the lesson)
     * again, stores it in the lesson and returns the new translations by sentence index.
     */
    fun retranslate(lang: String, lessonId: Int, index: Int, target: String): Map<Int, String> {
        val body = JSONObject().put("sentence", index).put("language", target)
            .put("method", "chatgpt").put("sentence_bulk", 1)
        val a = JSONArray(call("POST", "$BASE/v3/$lang/lessons/$lessonId/translation/", body.toString()))
        val out = HashMap<Int, String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val tr = o.optJSONArray("translations") ?: continue
            for (k in 0 until tr.length()) {
                val t = tr.getJSONObject(k)
                if (t.optString("language") == target) out[o.optInt("index")] = t.optString("text")
            }
        }
        return out
    }

    fun lesson(lang: String, id: Int): Lesson = obj("$BASE/v3/$lang/lessons/$id/").let { o ->
        Lesson(id, o.optString("title"), o.optString("imageUrl"), o.optString("level"), o.optInt("wordCount"),
            o.optInt("newWordsCount"), o.optString("collectionTitle"))
    }

    /** Lesson as the web editor sees it: paragraphs -> sentences (text, translations, ...). */
    /** The whole editor object: paragraphs, audio (URL or null), duration… */
    fun editorObj(lang: String, id: Int): JSONObject = obj("$BASE/v3/$lang/lessons/$id/editor/")

    fun editor(lang: String, id: Int): JSONArray =
        obj("$BASE/v3/$lang/lessons/$id/editor/").optJSONArray("paragraphs") ?: JSONArray()

    /** Saves the sentence structure exactly as sent (LingQ does not re-split it). */
    fun saveParagraphs(lang: String, id: Int, paragraphs: JSONArray) {
        call("PATCH", "$BASE/v3/$lang/lessons/$id/", JSONObject().put("paragraphs", paragraphs).toString())
    }

    /** Creates a new (private) course; returns it. */
    fun createCourse(lang: String, title: String): Course {
        val body = JSONObject().put("title", title.take(250)).put("description", "").put("level", 1)
        val d = JSONObject(call("POST", "$BASE/v3/$lang/collections/", body.toString()))
        return Course(d.getInt("id"), d.optString("title", title))
    }

    /** Creates a private lesson in [course]; returns its id. */
    fun importLesson(lang: String, course: Int, title: String, text: String): Int {
        val body = JSONObject()
            .put("title", title.take(250))
            .put("text", text)
            .put("collection", course)
            .put("status", "private")
            .put("save", true)
        return JSONObject(call("POST", "$BASE/v3/$lang/lessons/import/", body.toString())).getInt("id")
    }
}
