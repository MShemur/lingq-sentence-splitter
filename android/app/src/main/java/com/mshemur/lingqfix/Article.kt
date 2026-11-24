package com.mshemur.lingqfix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

// A web article as plain text: port of web/src/importer/article.ts (extractArticle) and web/functions/article.ts (fetch rules).
// The app downloads the page itself (honest User-Agent, public http(s) hosts only, at most 5 redirects, HTML only, 5 MB).
// Keep the rules in step with the web version.
object Article {
    data class Result(val title: String, val text: String)

    /** A refusal or failure; [code] is "401"/"403"/"404"/… (the HTTP status), or "refused", "not-html", "too-large", "private", "bad-url", "redirect". */
    class ArticleException(val code: String) : Exception(code)

    private const val MAX_BYTES = 5 * 1024 * 1024
    private const val MAX_HOPS = 5
    private const val UA = "SatzLoop/1.0 (article import by one reader)"
    private const val BLOCK = "p,h2,h3,h4,li,blockquote"
    private const val SKIP = "nav,aside,footer,figure,figcaption,form,button,script,style,noscript,[aria-hidden=\"true\"]"
    private const val NOTE = "style,script,noscript,template,.mw-editsection,sup.reference,a[href^=\"#cite\"],a[role=\"doc-noteref\"]"
    private const val REFUSED_MSG = "Сайт не даёт загрузить статью автоматически. Выделите текст в браузере и нажмите «Поделиться» → SatzLoop."

    private val WS = Regex("""[\s ]+""")
    private val NOISE = Regex(
        """\bcomments?\b|\bkommentare?\b|disqus|share|social|related|newsletter|teaser|advert|promo|byline|caption|breadcrumb|\breferences\b|reflist|navbox|infobox|\btoc\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PAYWALL = Regex("paywall", RegexOption.IGNORE_CASE)
    private val CITE = Regex("""#(cite|fn|note)""")
    private val TITLE_TAIL = Regex("""\s+[|–—-]\s+[^|–—-]{1,40}$""")
    private val CHARSET = Regex("""charset=["']?\s*([\w-]+)""", RegexOption.IGNORE_CASE)
    private val HTML_TYPE = Regex("""text/html|application/xhtml\+xml""", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
    private val URL_LINE = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // --- extraction (the same rules as extractArticle in the web version) ---

    /** The article's title and text (paragraphs separated by an empty line). */
    fun extract(doc: Document): Result {
        // title: og:title, else the first h1, else the page title (without a " | Site name" suffix)
        var title = clean(doc.selectFirst("meta[property=\"og:title\"]")?.attr("content"))
        var fromPage = title.isNotEmpty()
        if (title.isEmpty()) title = clean(doc.selectFirst("h1")?.text())
        if (title.isEmpty()) { title = clean(doc.title()); fromPage = true }
        if (fromPage) {
            val cut = title.replace(TITLE_TAIL, "")
            if (cut.length >= 10) title = cut
        }

        var paras: List<String> = emptyList()
        // the first container with enough text
        for (sel in listOf("[itemprop=\"articleBody\"]", "article", "main", "[role=\"main\"]")) {
            for (el in doc.select(sel)) {
                val p = collect(el)
                if (total(p) >= 300) { paras = p; break }
            }
            if (paras.isNotEmpty()) break
        }
        if (paras.isEmpty()) {
            // else the element whose own paragraphs carry the most text
            var best: Element? = null
            var bestN = 0
            for (el in doc.select("div,section,article,main,td")) {
                var n = 0
                for (c in el.children()) if (c.tagName() == "p") n += textOf(c).length
                if (n > bestN) { bestN = n; best = el }
            }
            if (best != null) paras = collect(best)
        }

        // news sites often ship the whole article in JSON-LD (articleBody): taken when the page's own text is much shorter
        var body = ""
        for (s in doc.select("script[type=\"application/ld+json\"]")) {
            try {
                val j = JSONTokener(s.data()).nextValue()
                val graph = (j as? JSONObject)?.opt("@graph")
                for (o in items(j) + items(graph)) {
                    if (o is JSONObject) {
                        val b = o.opt("articleBody")
                        if (b is String && b.length > body.length) body = b
                    }
                }
            } catch (e: Exception) {
                // not JSON
            }
        }
        if (body.length > 500) {
            // the text may carry HTML entities and line breaks: decoded as a fragment (tags dropped)
            val jp = ArrayList<String>()
            for (line in body.split(Regex("""\r?\n+"""))) {
                val t = clean(Jsoup.parse(line).text())
                if (t.isNotEmpty()) jp.add(t)
            }
            if (total(paras) < total(jp) * 0.8) paras = jp
        }

        return Result(title, paras.joinToString("\n\n"))
    }

    private fun clean(s: String?): String = (s ?: "").replace(WS, " ").trim()

    private fun total(xs: List<String>): Int = xs.sumOf { it.length }

    private fun items(v: Any?): List<Any?> = when (v) {
        is JSONArray -> (0 until v.length()).map { v.opt(it) }
        null -> emptyList()
        else -> listOf(v)
    }

    private fun own(e: Element): String = e.attr("class") + " " + e.id()

    private fun isNote(e: Element): Boolean {
        if (e.`is`(NOTE)) return true
        if (e.tagName() != "sup" || e.select("a").size != 1) return false
        return CITE.containsMatchIn(e.selectFirst("a")?.attr("href") ?: "")
    }

    /** The text of [el] as textContent gives it, without the notes, footnote marks and edit links. */
    private fun textOf(el: Element): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            for (c in n.childNodes()) {
                if (c is TextNode) sb.append(c.wholeText)
                else if (c is Element && !isNote(c)) walk(c)
            }
        }
        walk(el)
        return clean(sb.toString())
    }

    private fun hasArticleAncestor(e: Element?): Boolean {
        var x: Element? = e
        while (x != null) {
            if (x.`is`("article")) return true
            x = x.parent()
        }
        return false
    }

    /** The text blocks of [root] in document order, without the noise and the repeats. */
    private fun collect(root: Element): List<String> {
        val out = ArrayList<String>()
        var last = ""
        for (el in root.select(BLOCK).filter { it !== root }) {
            var skip = PAYWALL.containsMatchIn(own(el))
            // the walk stops at the container: its own classes do not count
            var a: Element? = el
            while (a != null && a !== root && !skip) {
                if (a.`is`(SKIP) || NOISE.containsMatchIn(own(a))) skip = true
                // a header inside an article is its lead (kept); a site header is not
                else if (a.tagName() == "header" && !hasArticleAncestor(a.parent())) skip = true
                // a block inside another block (p in li) is taken with the outer one
                else if (a !== el && a.`is`(BLOCK)) skip = true
                a = a.parent()
            }
            if (skip) continue
            val text = textOf(el)
            if (text.isEmpty()) continue
            if (el.tagName() !in setOf("h2", "h3", "h4")) {
                // too short, or only a link (a teaser)
                if (text.length < 20) continue
                var linked = 0
                for (x in el.select("a")) linked += textOf(x).length
                if (linked >= text.length * 0.8) continue
            }
            if (text == last) continue
            out.add(text)
            last = text
        }
        return out
    }

    // --- download (the same rules as web/functions/article.ts) ---

    /** A host that is not public: this machine, the local network, link-local, carrier NAT, reserved, names without a dot. */
    private fun privateHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return true
        // IPv6 literal: not used here
        if (':' in h) return true
        val m = IPV4.matchEntire(h)
        if (m != null) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
        }
        return !h.contains('.')
    }

    private fun request(u: HttpUrl): Request = Request.Builder()
        .url(u)
        .header("User-Agent", UA)
        .header("Accept", "text/html")
        .get()
        .build()

    /** Opens the page, following redirects one by one (each address checked). Returns the response and its final address. */
    private fun open(start: HttpUrl): Pair<Response, HttpUrl> {
        var current = start
        var hops = 0
        while (true) {
            val r = client.newCall(request(current)).execute()
            val location = r.header("Location")
            if (r.code !in 300..399 || location == null) return Pair(r, current)
            r.close()
            if (hops >= MAX_HOPS) throw ArticleException("redirect")
            hops++
            val next = current.resolve(location) ?: throw ArticleException("redirect")
            if (privateHost(next.host)) throw ArticleException("private")
            current = next
        }
    }

    /** The body as bytes, at most [MAX_BYTES]. */
    private fun readCapped(r: Response): ByteArray {
        val input = r.body?.byteStream() ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) throw ArticleException("too-large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The article of a web page (downloaded here). Throws [ArticleException] or a network error. */
    suspend fun fetch(url: String): Result = withContext(Dispatchers.IO) {
        val start = url.trim().toHttpUrlOrNull() ?: throw ArticleException("bad-url")
        if (privateHost(start.host)) throw ArticleException("private")
        val (response, last) = open(start)
        response.use { r ->
            if (r.code !in 200..299) throw ArticleException(r.code.toString())
            val type = r.header("Content-Type").orEmpty()
            if (!HTML_TYPE.containsMatchIn(type)) throw ArticleException("not-html")
            val bytes = readCapped(r)
            // the charset of the content type; else the page's <meta charset> (Jsoup), else utf-8
            val charset = CHARSET.find(type)?.groupValues?.get(1)
            val doc = try {
                Jsoup.parse(ByteArrayInputStream(bytes), charset, last.toString())
            } catch (e: IllegalArgumentException) {
                Jsoup.parse(ByteArrayInputStream(bytes), null, last.toString())
            }
            val a = extract(doc)
            if (a.text.split(WS).count { it.isNotEmpty() } < 80) throw ArticleException("refused")
            a
        }
    }

    /** A short Russian message for the user. */
    fun message(e: Throwable): String {
        if (e is ArticleException) return when (e.code) {
            "404" -> "Страница не найдена."
            "not-html" -> "По ссылке не веб-страница."
            "too-large" -> "Страница слишком большая."
            "private", "bad-url" -> "Неверная ссылка."
            "redirect" -> "Не удалось загрузить страницу: слишком много переадресаций."
            // a site's refusal (any other HTTP status) or no article: the hint to share the text from the browser
            else -> REFUSED_MSG
        }
        if (e is java.io.IOException) return "Нет связи с сайтом. Проверьте интернет или выделите текст в браузере и поделитесь им."
        return "Не удалось загрузить страницу: ${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * The text is a link to a page: the whole text is one http(s) address, or a short title line
     * (up to 200 characters, no other address) comes before it, as Chrome's share sends "Title\nhttps://…".
     */
    fun bareUrl(s: String): String? {
        val lines = s.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val last = lines.last()
        if (!URL_LINE.matches(last)) return null
        val title = lines.dropLast(1)
        if (title.sumOf { it.length } > 200) return null
        if (title.any { it.contains("http://", ignoreCase = true) || it.contains("https://", ignoreCase = true) }) return null
        return if (last.toHttpUrlOrNull() != null) last else null
    }

    /** The bookmarklet's text: "SatzLoop: <title>", an empty line, the text. null if it is not such a text. */
    fun marker(s: String): Result? {
        val t = s.trimStart()
        val m = MARK.find(t) ?: return null
        val title = m.groupValues[1].trim()
        val text = t.substring(m.range.last + 1).trim()
        return Result(title, text)
    }

    private val MARK = Regex("""^SatzLoop:[ \t]*([^\r\n]*)(?:\r?\n|$)""")
}
