@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mshemur.lingqfix

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.SoftReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * German word forms -> base form ("bin", "ist", "war" -> "sein"; "Häusern" -> "Haus"), offline.
 * Data: lemmatization-lists by Michal Měchura (Open Database License), packed gzipped in assets/lemma-de.dat (not ".gz": the build unpacks those) as
 * "lemma<TAB>form<TAB>form…" per line. Loaded only while counting; kept as a soft reference.
 */
class Lemmatizer private constructor(private val forms: HashMap<String, String>, private val lemmas: HashSet<String>) {
    companion object {
        private var cache: SoftReference<Lemmatizer>? = null

        /** Only German has a dictionary; null for other languages. */
        fun load(ctx: Context, lang: String): Lemmatizer? {
            if (lang != "de") return null
            cache?.get()?.let { return it }
            val forms = HashMap<String, String>(420_000)
            val lemmas = HashSet<String>(100_000)
            GZIPInputStream(ctx.assets.open("lemma-de.dat")).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    val lm = p[0]
                    lemmas += lm
                    for (i in 1 until p.size) forms[p[i]] = lm
                }
            }
            return Lemmatizer(forms, lemmas).also { cache = SoftReference(it) }
        }
    }

    private fun direct(w: String): String? = forms[w] ?: w.takeIf { it in lemmas }

    /** Base form and whether the dictionary knew it (compounds: by their last part, "Bahnhofsuhren" -> "Bahnhofsuhr"). */
    fun lemma(w: String): Pair<String, Boolean> {
        direct(w)?.let { return it to true }
        val low = w.lowercase()
        if (low != w) direct(low)?.let { return it to true }
        // compound word: the longest known ending decides the form
        for (i in 1..w.length - 4) {
            val tail = w.substring(i)
            val cap = tail.replaceFirstChar { it.uppercaseChar() }
            val lm = direct(tail) ?: direct(cap) ?: continue
            return (w.substring(0, i) + lm.lowercase()) to true
        }
        // not in the dictionary (und, zu, names…): "Und" at a sentence start and "und" are one word
        return low to false
    }
}

/** One lesson (chapter) of the count. */
class WsChapter(val id: Int, val title: String, val words: Int, val newCount: Int, val total: Int, val top: List<String>)

/** One base form of the course: where it first appears (lesson index), how often in all, whether the dictionary knew it. */
class WsLemma(val word: String, val first: Int, val count: Int, val known: Boolean)

class WsResult(
    val t: Long, val chapters: List<WsChapter>, val unique: Int, val words: Int, val unknown: Int, val lemmatized: Boolean,
    /** All base forms (empty in results counted before v1.98: the "rare words" slider needs a recount). */
    val lemmas: List<WsLemma> = emptyList(),
) {
    /** Words met at most [min] times in the whole course left out: numbers per lesson again, and how many were cut. */
    fun without(min: Int): Pair<WsResult, Int> {
        if (min <= 0 || lemmas.isEmpty()) return this to 0
        val keep = lemmas.filter { it.count > min }
        val byFirst = keep.groupBy { it.first }
        var total = 0
        val ch = chapters.mapIndexed { i, c ->
            val fresh = byFirst[i].orEmpty()
            total += fresh.size
            WsChapter(c.id, c.title, c.words, fresh.size, total, fresh.sortedByDescending { it.count }.take(20).map { it.word })
        }
        return WsResult(t, ch, keep.size, words, keep.count { !it.known }, lemmatized, keep) to (lemmas.size - keep.size)
    }

    fun toJson(): JSONObject = JSONObject().put("t", t).put("unique", unique).put("words", words).put("unknown", unknown)
        .put("lemmatized", lemmatized)
        .put("lemmas", JSONArray().also { a -> lemmas.forEach { l -> a.put(l.word).put(l.first).put(l.count).put(if (l.known) 1 else 0) } })
        .put("chapters", JSONArray().also { a ->
            chapters.forEach { c ->
                a.put(JSONObject().put("id", c.id).put("title", c.title).put("words", c.words).put("new", c.newCount)
                    .put("total", c.total).put("top", JSONArray(c.top)))
            }
        })

    companion object {
        fun of(o: JSONObject): WsResult {
            val a = o.getJSONArray("chapters")
            val ch = (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                val top = c.optJSONArray("top") ?: JSONArray()
                WsChapter(c.getInt("id"), c.optString("title"), c.optInt("words"), c.optInt("new"), c.optInt("total"),
                    (0 until top.length()).map { top.getString(it) })
            }
            val la = o.optJSONArray("lemmas") ?: JSONArray()
            val lemmas = (0 until la.length() / 4).map { k ->
                WsLemma(la.getString(k * 4), la.getInt(k * 4 + 1), la.getInt(k * 4 + 2), la.getInt(k * 4 + 3) == 1)
            }
            return WsResult(o.optLong("t"), ch, o.optInt("unique"), o.optInt("words"), o.optInt("unknown"), o.optBoolean("lemmatized", true), lemmas)
        }
    }
}

/** Results kept per course on the phone. */
object WsStore {
    private fun file(ctx: Context, lang: String, course: Int) = File(File(ctx.filesDir, "wordstats").apply { mkdirs() }, "${lang}_$course.json")
    fun load(ctx: Context, lang: String, course: Int): WsResult? = runCatching { WsResult.of(JSONObject(file(ctx, lang, course).readText())) }.getOrNull()
    fun save(ctx: Context, lang: String, course: Int, r: WsResult) { runCatching { file(ctx, lang, course).writeText(r.toJson().toString()) } }
}

/** "Слова по главам": [course] null = choose a course first. */
class WordStatsScreenState(val lang: String, val course: Course?) {
    var result by mutableStateOf<WsResult?>(null)
    var running by mutableStateOf(false)
    var progress by mutableStateOf("")
    var fraction by mutableStateOf(0f)
    var error by mutableStateOf<String?>(null)

    fun start(app: AppState) {
        val c = course ?: return
        if (running) return
        running = true; error = null; fraction = 0f; progress = "Загружаю список уроков…"
        AppLog.add("wordstats", "Подсчёт слов: курс ${c.id} «${c.title}»")
        app.scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { count(app, c) }
                WsStore.save(app.ctx, lang, c.id, r)
                result = r
                AppLog.add("wordstats", "Готово: ${r.chapters.size} уроков, ${r.unique} уникальных слов")
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
                AppLog.error("wordstats", "Подсчёт слов не удался", e)
            }
            running = false
        }
    }

    private suspend fun count(app: AppState, c: Course): WsResult {
        val lessons = app.api.lessons(lang, c.id).second
        if (lessons.isEmpty()) throw IllegalStateException("В курсе нет уроков")
        progress = "Загружаю словарь…"
        val lem = Lemmatizer.load(app.ctx, lang)
        var done = 0
        val sem = Semaphore(3)
        val texts = coroutineScope {
            lessons.map { l ->
                async {
                    sem.withPermit {
                        val paras = app.api.editor(lang, l.id)
                        val sb = StringBuilder()
                        for (i in 0 until paras.length()) {
                            val ss = paras.getJSONObject(i).optJSONArray("sentences") ?: continue
                            for (k in 0 until ss.length()) sb.append(ss.getJSONObject(k).optString("text")).append(' ')
                            sb.append('\n')
                        }
                        synchronized(this@WordStatsScreenState) { done++ }
                        progress = "Загружаю тексты: $done из ${lessons.size}"
                        fraction = done * 0.9f / lessons.size
                        sb.toString()
                    }
                }
            }.awaitAll()
        }
        progress = "Считаю…"
        val seen = HashSet<String>()
        val unknown = HashSet<String>()
        val firstAt = HashMap<String, Int>()
        val totals = HashMap<String, Int>()
        var allWords = 0
        val out = ArrayList<WsChapter>()
        lessons.forEachIndexed { i, l ->
            val counts = HashMap<String, Int>()
            var words = 0
            for (t in Sent.tokenize(texts[i])) {
                if (!t.isWord || t.text.none { it.isLetter() }) continue
                words++
                val (lm, known) = lem?.lemma(t.text) ?: (t.text.lowercase() to true)
                if (!known) unknown += lm
                counts[lm] = (counts[lm] ?: 0) + 1
            }
            val fresh = counts.keys.filter { it !in seen }
            seen += fresh
            fresh.forEach { firstAt[it] = i }
            counts.forEach { (k, v) -> totals[k] = (totals[k] ?: 0) + v }
            allWords += words
            val top = fresh.sortedByDescending { counts[it] ?: 0 }.take(20)
            out += WsChapter(l.id, l.title, words, fresh.size, seen.size, top)
        }
        fraction = 1f
        val lemmas = firstAt.map { (w, f) -> WsLemma(w, f, totals[w] ?: 0, w !in unknown) }.sortedBy { it.first }
        return WsResult(System.currentTimeMillis(), out, seen.size, allWords, unknown.size, lem != null, lemmas)
    }
}

private fun num(n: Int) = n.toString().reversed().chunked(3).joinToString(" ").reversed()

@Composable
fun WordStatsScreen(state: AppState, s: WordStatsScreenState) {
    if (s.course == null) { CoursePicker(state, s.lang); return }
    LaunchedEffect(s.course.id) { if (s.result == null) s.result = withContext(Dispatchers.IO) { WsStore.load(state.ctx, s.lang, s.course.id) } }
    val r = s.result
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(s.course.title, style = MaterialTheme.typography.titleMedium)
        if (s.running) {
            Text(s.progress, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { s.fraction }, Modifier.fillMaxWidth())
        }
        s.error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
        if (r == null) {
            if (!s.running) {
                Text(
                    "Приложение загрузит тексты всех уроков курса и посчитает, сколько новых слов в каждом уроке " +
                        "(слова, которых не было в предыдущих), и сколько уникальных слов всего. Формы слова считаются как одно: " +
                        "bin, ist, war → sein; Häuser → Haus. Считается на телефоне, без ИИ.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { s.start(state) }) { Text("Посчитать") }
            }
            return@Column
        }
        RareSlider(state, r)
        val (v, cut) = remember(r, state.wsMin) { r.without(state.wsMin) }
        Summary(v, cut, state.wsMin)
        var picked by remember(r) { mutableStateOf(0) }
        NewWordsChart(v, picked) { picked = it }
        ChapterDetails(v, picked)
        TotalChart(v, picked) { picked = it }
        OutlinedButton(onClick = { s.start(state) }, enabled = !s.running) { Text("Пересчитать") }
        Text(
            "Урок курса = глава. Новое слово — начальная форма, которой не было в предыдущих уроках. " +
                (if (r.lemmatized) "Слова, которых нет в словаре (имена, редкие слова), считаются как написаны. " +
                    "Словарь форм: lemmatization-lists, Michal Měchura, Open Database License."
                else "Для этого языка словаря форм нет: каждая форма считается отдельным словом."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Slider: words met at most N times in the whole course are not counted (typos, names, one-off words). */
@Composable
private fun RareSlider(state: AppState, r: WsResult) {
    Column {
        Text(
            if (state.wsMin == 0) "Считаются все слова" else "Не считать слова, которые встретились ${state.wsMin} ${times(state.wsMin)} и реже",
            style = MaterialTheme.typography.titleSmall,
        )
        androidx.compose.material3.Slider(
            value = state.wsMin.toFloat(),
            onValueChange = { val n = Math.round(it).coerceIn(0, 10); if (n != state.wsMin) state.setWsMin(n) },
            valueRange = 0f..10f, steps = 9, enabled = r.lemmas.isNotEmpty(),
        )
        if (r.lemmas.isEmpty()) Text(
            "Чтобы ползунок работал, нажмите «Пересчитать» внизу (этот курс посчитан старой версией).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun times(n: Int): String {
    val m10 = n % 10; val m100 = n % 100
    return if (m10 in 2..4 && m100 !in 12..14) "раза" else "раз"
}

@Composable
private fun Summary(r: WsResult, cut: Int, min: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            val on = MaterialTheme.colorScheme.onPrimaryContainer
            Text("Уникальных слов", style = MaterialTheme.typography.labelLarge, color = on)
            Text(num(r.unique), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = on)
            Text("всего слов в тексте ${num(r.words)} · уроков ${r.chapters.size}", style = MaterialTheme.typography.bodyMedium, color = on)
            if (cut > 0) Text(
                "отсечено редких слов (встретились $min ${times(min)} и реже): ${num(cut)}",
                style = MaterialTheme.typography.bodyMedium, color = on,
            )
            r.chapters.firstOrNull()?.let {
                Text("в первом уроке ${num(it.newCount)} новых", style = MaterialTheme.typography.bodyMedium, color = on)
            }
            if (r.lemmatized && r.unknown > 0) Text(
                "не найдено в словаре: ${num(r.unknown)} (имена, редкие слова)",
                style = MaterialTheme.typography.bodySmall, color = on,
            )
            Text(
                "посчитано " + SimpleDateFormat("d.MM.yyyy HH:mm", Locale("ru")).format(Date(r.t)),
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = on,
            )
        }
    }
}

/** Width of one chapter's column: the charts scroll sideways when there are many chapters. */
@Composable
private fun slotWidth(n: Int, avail: Dp): Dp = maxOf(avail / n, 14.dp)

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/** New words per chapter: one bar per chapter, tap a bar for its numbers. */
@Composable
private fun NewWordsChart(r: WsResult, picked: Int, pick: (Int) -> Unit) {
    val n = r.chapters.size
    val max = maxOf(r.chapters.maxOf { it.newCount }, 1)
    val color = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    ChartCard("Новые слова по урокам") {
        Text("макс. ${num(max)}", Modifier.padding(top = 2.dp, bottom = 6.dp), style = style, color = label)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val slot = slotWidth(n, maxWidth)
            val scroll = rememberScrollState()
            Canvas(
                Modifier.horizontalScroll(scroll).width(slot * n).height(160.dp)
                    .pointerInput(n, slot) { detectTapGestures { p -> pick((p.x / slot.toPx()).toInt().coerceIn(0, n - 1)) } },
            ) {
                val sw = slot.toPx()
                val labelH = 16.dp.toPx()
                val h = size.height - labelH
                val w = minOf(sw * 0.7f, 28.dp.toPx())
                val every = maxOf(1, (40.dp.toPx() / sw).toInt())
                r.chapters.forEachIndexed { i, c ->
                    val bh = if (c.newCount <= 0) 0f else maxOf(h * c.newCount / max, 2.dp.toPx())
                    val left = sw * i + (sw - w) / 2
                    if (bh > 0f) {
                        val p = Path().apply {
                            addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    left, h - bh, left + w, h,
                                    topLeftCornerRadius = CornerRadius(4.dp.toPx()), topRightCornerRadius = CornerRadius(4.dp.toPx()),
                                ),
                            )
                        }
                        drawPath(p, color.copy(alpha = if (i == picked) 1f else 0.45f))
                    }
                    if (i % every == 0 || i == picked) {
                        val t = measurer.measure("${i + 1}", style.copy(color = if (i == picked) color else label))
                        drawText(t, topLeft = Offset(sw * i + (sw - t.size.width) / 2, h + 2.dp.toPx()))
                    }
                }
                drawRect(base, Offset(0f, h - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
            }
        }
    }
}

/** Unique words so far after each chapter: a line, the picked chapter marked. */
@Composable
private fun TotalChart(r: WsResult, picked: Int, pick: (Int) -> Unit) {
    val n = r.chapters.size
    val max = maxOf(r.unique, 1)
    val color = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    ChartCard("Всего уникальных слов") {
        Text("после урока ${picked + 1}: ${num(r.chapters[picked].total)} из ${num(r.unique)}", Modifier.padding(top = 2.dp, bottom = 6.dp), style = style, color = label)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val slot = slotWidth(n, maxWidth)
            Canvas(
                Modifier.horizontalScroll(rememberScrollState()).width(slot * n).height(130.dp)
                    .pointerInput(n, slot) { detectTapGestures { p -> pick((p.x / slot.toPx()).toInt().coerceIn(0, n - 1)) } },
            ) {
                val sw = slot.toPx()
                val top = 6.dp.toPx()
                val h = size.height - top
                fun pt(i: Int) = Offset(sw * i + sw / 2, top + h - h * r.chapters[i].total / max)
                val line = Path().apply { r.chapters.indices.forEach { i -> val p = pt(i); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                val area = Path().apply {
                    addPath(line)
                    lineTo(pt(n - 1).x, size.height); lineTo(pt(0).x, size.height); close()
                }
                drawPath(area, color.copy(alpha = 0.12f))
                drawPath(line, color, style = Stroke(2.dp.toPx()))
                drawRect(base, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
                val p = pt(picked)
                drawCircle(surface, 6.dp.toPx(), p)
                drawCircle(color, 4.dp.toPx(), p)
            }
        }
    }
}

/** Numbers of the picked chapter and its most frequent new words. */
@Composable
private fun ChapterDetails(r: WsResult, i: Int) {
    val c = r.chapters[i]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Урок ${i + 1}. ${c.title}", style = MaterialTheme.typography.titleSmall)
        val share = if (c.words > 0) c.newCount * 100 / c.words else 0
        Text(
            "новых слов ${num(c.newCount)} · слов в тексте ${num(c.words)} · новых на 100 слов: $share · всего уникальных к этому уроку ${num(c.total)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (c.top.isNotEmpty()) {
            Text("Самые частые новые:", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                c.top.forEach { w -> SuggestionChip(onClick = {}, label = { Text(w) }) }
            }
        }
    }
}

/** Library ⋮ → "Слова по главам": choose the course. */
@Composable
private fun CoursePicker(state: AppState, lang: String) {
    LaunchedEffect(Unit) { if (state.courses == null) state.loadCourses() }
    val list = state.visibleCourses()
    if (list == null) { Text("Загружаю курсы…", Modifier.padding(16.dp)); return }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Выберите курс: для каждого урока посчитается, сколько в нём новых слов, и сколько уникальных слов во всём курсе.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(list) { c ->
            val done = remember(c.id) { WsStore.load(state.ctx, lang, c.id) }
            Column(
                Modifier.fillMaxWidth().clickable { state.go(Screen.WordStats(WordStatsScreenState(lang, c))) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(c.title.ifBlank { "Курс ${c.id}" }, style = MaterialTheme.typography.bodyLarge)
                if (done != null) Text(
                    "${num(done.unique)} уникальных слов · ${done.chapters.size} уроков",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider()
        }
    }
}
