package com.mshemur.lingqfix

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
        while (true) {
            val req = Request.Builder().url(url)
                .header("Authorization", "Token $key")
                .header("Accept", "application/json")
                // Cloudflare rejects some default client signatures (error 1010)
                .header("User-Agent", "lingq-lesson-fixer/1.0")
                .method(method, body?.toRequestBody(JSON_TYPE))
                .build()
            val (code, text, retryAfter) = http.newCall(req).execute().use { r ->
                Triple(r.code, r.body?.string().orEmpty(), r.header("Retry-After"))
            }
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
            .map { Course(it.getInt("id"), it.optString("title")) }

    /** Course title (may be empty) and its lessons in course order. */
    fun lessons(lang: String, course: Int): Pair<String, List<Lesson>> {
        val all = pages("$BASE/v3/$lang/collections/$course/lessons/?page=1&page_size=100&sortBy=pos")
        val title = all.firstOrNull()?.optString("collectionTitle").orEmpty()
        return title to all.map { Lesson(it.getInt("id"), it.optString("title")) }
    }

    fun lesson(lang: String, id: Int): Lesson = Lesson(id, obj("$BASE/v3/$lang/lessons/$id/").optString("title"))

    /** Lesson as the web editor sees it: paragraphs -> sentences (text, translations, ...). */
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
