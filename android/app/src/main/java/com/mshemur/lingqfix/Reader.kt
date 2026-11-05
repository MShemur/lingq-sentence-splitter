@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.mshemur.lingqfix

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// --- model ---------------------------------------------------------------------------------------

/** A piece of a sentence: a word (tappable) or the punctuation/space between words. */
class Token(val text: String, val isWord: Boolean, val index: Int)

/** One sentence of the lesson; [id] is unique in the lesson. */
class Sent(val id: Int, val text: String, val translations: Map<String, String>) {
    val tokens: List<Token> = tokenize(text)
    val chunks: List<List<Token>> = chunks(tokens)

    companion object {
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:[-'’][\\p{L}\\p{N}]+)*")

        fun tokenize(t: String): List<Token> {
            val out = mutableListOf<Token>()
            var pos = 0
            var i = 0
            for (m in WORD.findAll(t)) {
                if (m.range.first > pos) out += Token(t.substring(pos, m.range.first), false, i++)
                out += Token(m.value, true, i++)
                pos = m.range.last + 1
            }
            if (pos < t.length) out += Token(t.substring(pos), false, i++)
            return out
        }

        /** Groups tokens up to the next space, so "(Abb." or "24)," never break apart. */
        private fun chunks(tokens: List<Token>): List<List<Token>> {
            val out = mutableListOf<List<Token>>()
            var cur = mutableListOf<Token>()
            for (t in tokens) {
                if (!t.isWord && t.text.any { it.isWhitespace() }) {
                    val before = t.text.takeWhile { !it.isWhitespace() }
                    val after = t.text.takeLastWhile { !it.isWhitespace() }
                    if (before.isNotEmpty()) cur += Token(before, false, t.index)
                    if (cur.isNotEmpty()) out += cur
                    cur = mutableListOf()
                    if (after.isNotEmpty()) cur += Token(after, false, t.index)
                } else cur += t
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }
    }
}

/** Word the user tapped: sentence + token. */
data class WordRef(val sent: Int, val token: Int) {
    fun key() = "$sent:$token"

    companion object {
        fun of(k: String) = k.split(':').let { WordRef(it[0].toInt(), it[1].toInt()) }
    }
}

/** Meanings pinned in one lesson (kept on the phone): per word position and per word. */
private class LessonNotes(ctx: Context, lang: String, id: Int) {
    private val file = File(File(ctx.filesDir, "reader").apply { mkdirs() }, "${lang}_$id.json")

    fun load(): Pair<Map<WordRef, String>, Map<String, String>> = runCatching {
        val o = JSONObject(file.readText())
        val pins = o.optJSONObject("pins") ?: JSONObject()
        val words = o.optJSONObject("words") ?: JSONObject()
        pins.keys().asSequence().associate { WordRef.of(it) to pins.getString(it) } to
            words.keys().asSequence().associateWith { words.getString(it) }
    }.getOrElse { emptyMap<WordRef, String>() to emptyMap() }

    fun save(pins: Map<WordRef, String>, words: Map<String, String>) {
        val p = JSONObject(); pins.forEach { (k, v) -> if (v.isNotEmpty() && !v.startsWith("⚠")) p.put(k.key(), v) }
        val w = JSONObject(); words.forEach { (k, v) -> w.put(k, v) }
        file.writeText(JSONObject().put("pins", p).put("words", w).toString())
    }
}

// --- session -------------------------------------------------------------------------------------

class ReaderSession(private val app: AppState, val lang: String, val lesson: Lesson) {
    var title by mutableStateOf(lesson.title)
    var paragraphs by mutableStateOf<List<List<Sent>>?>(null)
    var sentences by mutableStateOf<List<Sent>>(emptyList())
    var error by mutableStateOf<String?>(null)

    var inlineMode by mutableStateOf(app.prefs.readerMode == "inline")
    var sentenceMode by mutableStateOf(false)
    var allSentences by mutableStateOf(false)
    var font by mutableStateOf(app.prefs.readerFont)
    var page by mutableStateOf(0)

    /** Sentence translations, and the sentences whose translation is shown. */
    val sentTr = mutableStateMapOf<Int, String>()
    val shownSentences = mutableStateMapOf<Int, Boolean>()

    /** Translations pinned above words ("" while loading); kept for this lesson. */
    val pins = mutableStateMapOf<WordRef, String>()
    /** Meaning chosen for a word (lower case) in this lesson. */
    val chosen = mutableStateMapOf<String, String>()

    /** Pop-up mode: the word whose window is open. */
    var popup by mutableStateOf<WordRef?>(null)
    var popupHints by mutableStateOf<List<String>?>(null)
    var popupBest by mutableStateOf<String?>(null)
    var popupError by mutableStateOf<String?>(null)

    private val notes = LessonNotes(app.ctx, lang, lesson.id)
    private val hintsCache = HashMap<String, List<String>>()
    private val loadingSentences = HashSet<Int>()
    private val sentById = HashMap<Int, Sent>()

    val target get() = app.trLang

    fun load() {
        error = null
        app.scope.launch {
            try {
                val (t, paras) = withContext(Dispatchers.IO) {
                    val l = if (lesson.title.isBlank()) app.api.lesson(lang, lesson.id) else lesson
                    l.title to parse(app.api.editor(lang, lesson.id))
                }
                val (p, w) = withContext(Dispatchers.IO) { notes.load() }
                pins.putAll(p); chosen.putAll(w)
                title = t
                sentences = paras.flatten()
                paragraphs = paras
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            }
        }
    }

    private fun parse(a: JSONArray): List<List<Sent>> {
        var id = 0
        val out = mutableListOf<List<Sent>>()
        for (i in 0 until a.length()) {
            val ss = a.getJSONObject(i).optJSONArray("sentences") ?: continue
            val para = mutableListOf<Sent>()
            for (j in 0 until ss.length()) {
                val s = ss.getJSONObject(j)
                val text = s.optString("text").trim()
                if (text.isEmpty()) continue
                val tr = HashMap<String, String>()
                s.optJSONArray("translations")?.let { ta ->
                    for (k in 0 until ta.length()) {
                        val o = ta.getJSONObject(k)
                        val v = o.optString("text").trim()
                        if (v.isNotEmpty()) tr.putIfAbsent(o.optString("language"), v)
                    }
                }
                val sent = Sent(id++, text, tr)
                sentById[sent.id] = sent
                para += sent
            }
            if (para.isNotEmpty()) out += para
        }
        return out
    }

    private fun persist() {
        val p = pins.toMap(); val w = chosen.toMap()
        app.scope.launch(Dispatchers.IO) { runCatching { notes.save(p, w) } }
    }

    fun setMode(inline: Boolean) {
        inlineMode = inline
        app.prefs.readerMode = if (inline) "inline" else "popup"
    }

    fun changeFont(d: Int) {
        font = (font + d).coerceIn(13, 34)
        app.prefs.readerFont = font
    }

    fun clearPins() { pins.clear(); persist() }

    // --- translations ---

    /** Translation of a sentence: LingQ's stored one if there is one, otherwise machine translation. */
    private suspend fun sentenceTranslation(id: Int): String {
        sentTr[id]?.takeIf { it.isNotEmpty() && !it.startsWith("⚠") }?.let { return it }
        val s = sentById.getValue(id)
        val t = s.translations[target] ?: withContext(Dispatchers.IO) { app.api.translate(lang, target, s.text) }
        sentTr[id] = t
        return t
    }

    fun ensureSentence(id: Int) {
        if (!sentTr[id].isNullOrEmpty() || !loadingSentences.add(id)) return
        app.scope.launch {
            try { sentenceTranslation(id) } catch (e: Throwable) { sentTr[id] = "⚠ ${e.message}" }
            loadingSentences.remove(id)
        }
    }

    fun toggleSentence(id: Int) {
        if (shownSentences[id] == true) shownSentences.remove(id)
        else { shownSentences[id] = true; ensureSentence(id) }
    }

    fun sentenceShown(id: Int) = allSentences || shownSentences[id] == true

    private suspend fun hints(word: String): List<String> {
        hintsCache[word]?.let { return it }
        val h = withContext(Dispatchers.IO) {
            app.api.hints(lang, word, target).ifEmpty {
                if (word != word.lowercase()) app.api.hints(lang, word.lowercase(), target) else emptyList()
            }
        }
        hintsCache[word] = h
        return h
    }

    /**
     * The meaning that fits this sentence: the one chosen earlier in this lesson, else the first popular
     * meaning whose stem occurs in the sentence translation, else the most popular one, else machine translation.
     */
    private suspend fun contextual(ref: WordRef, hs: List<String>): String {
        val word = word(ref)
        chosen[word.lowercase()]?.let { return it }
        val tr = runCatching { sentenceTranslation(ref.sent) }.getOrDefault("").lowercase()
        val trWords = Regex("\\p{L}+").findAll(tr).map { it.value }.toList()
        fun fits(h: String) = Regex("\\p{L}+").findAll(h.lowercase()).any { m ->
            val w = m.value
            w.length >= 3 && trWords.any { it.startsWith(w.take(maxOf(3, minOf(w.length - 2, 6)))) }
        }
        return hs.take(10).firstOrNull { fits(it) }
            ?: hs.firstOrNull()
            ?: withContext(Dispatchers.IO) { app.api.translate(lang, target, word) }
    }

    fun tap(ref: WordRef) {
        if (inlineMode) {
            if (pins.containsKey(ref)) { pins.remove(ref); persist(); return }
            pins[ref] = ""
            app.scope.launch {
                pins[ref] = try { contextual(ref, hints(word(ref))) } catch (e: Throwable) { "⚠" }
                persist()
            }
        } else {
            popup = ref
            popupHints = null
            popupBest = null
            popupError = null
            app.scope.launch {
                try {
                    val h = hints(word(ref))
                    popupHints = h
                    popupBest = contextual(ref, h)
                } catch (e: Throwable) {
                    popupError = e.message ?: e.toString()
                }
            }
        }
    }

    /** Pop-up: the user picked [meaning] for the word: keep it for this lesson and pin it above the word. */
    fun choose(ref: WordRef, meaning: String) {
        val m = meaning.trim()
        if (m.isEmpty()) return
        chosen[word(ref).lowercase()] = m
        pins[ref] = m
        // other places where this word is already pinned get the chosen meaning too
        pins.keys.filter { word(it).equals(word(ref), ignoreCase = true) }.forEach { pins[it] = m }
        popup = null
        persist()
    }

    fun unpin(ref: WordRef) {
        pins.remove(ref)
        chosen.remove(word(ref).lowercase())
        popup = null
        persist()
    }

    fun word(ref: WordRef) = sentById[ref.sent]?.tokens?.getOrNull(ref.token)?.text.orEmpty()
}

// --- UI ------------------------------------------------------------------------------------------

private val GREEN = Color(0xFF2E7D32)
private val GREEN_BG = Color(0x3343A047)

/** Top bar: font size and extras. */
@Composable
fun ReaderActions(s: ReaderSession) {
    var menu by remember { mutableStateOf(false) }
    TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text("Шрифт крупнее  A+") }, onClick = { s.changeFont(+2) })
        DropdownMenuItem(text = { Text("Шрифт мельче  A−") }, onClick = { s.changeFont(-2) })
        if (s.pins.isNotEmpty()) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Снять все переводы над словами (${s.pins.size})") },
                onClick = { s.clearPins(); menu = false },
            )
        }
    }
}

/** Bottom bar of the reader, like LingQ's: page / sentence mode, how words are translated, sentence translations. */
@Composable
fun ReaderBottomBar(s: ReaderSession) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarButton(if (s.sentenceMode) "📄" else "📑", if (s.sentenceMode) "Весь текст" else "По предложениям") {
                s.sentenceMode = !s.sentenceMode
            }
            BarButton(if (s.inlineMode) "🔤" else "💬", if (s.inlineMode) "Над словом" else "В окошке") {
                s.setMode(!s.inlineMode)
            }
            if (!s.sentenceMode) BarButton(if (s.allSentences) "☑" else "☐", "Перевод предлож.") {
                s.allSentences = !s.allSentences
            }
        }
    }
}

@Composable
private fun BarButton(icon: String, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ReaderScreen(s: ReaderSession) {
    LaunchedEffect(s) { if (s.paragraphs == null) s.load() }
    val paras = s.paragraphs
    val err = s.error
    when {
        err != null -> Column(Modifier.padding(16.dp)) {
            Text("Ошибка: $err", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { s.load() }) { Text("Повторить") }
        }
        paras == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        s.sentenceMode -> SentencePager(s)
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "Нажмите на слово — перевод " + (if (s.inlineMode) "появится над ним" else "в окошке") +
                        ". Долгое нажатие — перевод предложения.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            items(paras) { para -> Paragraph(s, para) }
        }
    }
    s.popup?.let { ref -> WordSheet(s, ref) }
}

/** A paragraph flows as one text; a shown sentence translation is inserted right after its sentence. */
@Composable
private fun Paragraph(s: ReaderSession, para: List<Sent>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var run = mutableListOf<Sent>()
        for (sent in para) {
            run += sent
            if (s.sentenceShown(sent.id)) {
                SentenceFlow(s, run)
                SentenceTranslation(s, sent.id)
                run = mutableListOf()
            }
        }
        if (run.isNotEmpty()) SentenceFlow(s, run)
    }
}

@Composable
private fun SentenceFlow(s: ReaderSession, sents: List<Sent>, scale: Float = 1f) {
    val size = s.font * scale
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp, lineHeight = (size * 1.35).sp)
    val trStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (size * 0.6).sp, lineHeight = (size * 0.68).sp)
    FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (sent in sents) {
            for (chunk in sent.chunks) {
                // bottom-aligned: a translation above one word pushes the whole line down
                Row(Modifier.align(Alignment.Bottom).padding(end = (size * 0.3).dp), verticalAlignment = Alignment.Bottom) {
                    for (t in chunk) {
                        val ref = WordRef(sent.id, t.index)
                        val tr = if (t.isWord) s.pins[ref] else null
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (tr != null) Text(
                                tr.ifEmpty { "…" }, style = trStyle, color = GREEN, maxLines = 2,
                                modifier = Modifier.padding(horizontal = 1.dp),
                            )
                            val selected = tr != null || s.popup == ref
                            Text(
                                t.text, style = textStyle,
                                modifier = if (!t.isWord) Modifier else Modifier
                                    .background(if (selected) GREEN_BG else Color.Transparent, RoundedCornerShape(4.dp))
                                    .combinedClickable(
                                        onClick = { s.tap(ref) },
                                        onLongClick = { s.toggleSentence(sent.id) },
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SentenceTranslation(s: ReaderSession, id: Int, scale: Float = 1f) {
    LaunchedEffect(id) { s.ensureSentence(id) }
    val tr = s.sentTr[id]
    Text(
        if (tr.isNullOrEmpty()) "…" else tr,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = (s.font * 0.8f * scale).sp),
        fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable { if (!s.allSentences) s.toggleSentence(id) }
            .padding(8.dp),
    )
}

/** Sentence mode: one sentence with its translation per page, swipe for the next one. */
@Composable
private fun SentencePager(s: ReaderSession) {
    val list = s.sentences
    val pager = rememberPagerState(initialPage = s.page.coerceIn(0, maxOf(0, list.size - 1))) { list.size }
    LaunchedEffect(pager.currentPage) { s.page = pager.currentPage }
    Column(Modifier.fillMaxSize()) {
        Text(
            "${pager.currentPage + 1} / ${list.size}   ·   свайп ← → для следующего предложения",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        )
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
            val sent = list[i]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                SentenceFlow(s, listOf(sent), scale = 1.15f)
                SentenceTranslation(s, sent.id, scale = 1.1f)
            }
        }
    }
}

@Composable
private fun WordSheet(s: ReaderSession, ref: WordRef) {
    var own by remember(ref) { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { s.popup = null }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val word = s.word(ref)
            val saved = s.chosen[word.lowercase()]
            Text(word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            val hints = s.popupHints
            val best = s.popupBest
            when {
                s.popupError != null -> Text("Ошибка: ${s.popupError}", color = MaterialTheme.colorScheme.error)
                hints == null -> CircularProgressIndicator()
                else -> {
                    Text(
                        "Нажмите на перевод, чтобы выбрать его для этого текста:",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                    val options = (listOfNotNull(saved, best) + hints.take(8)).distinctBy { it.lowercase() }
                    options.forEach { h ->
                        val isSaved = h.equals(saved, ignoreCase = true)
                        val isBest = h.equals(best, ignoreCase = true)
                        Row(
                            Modifier.fillMaxWidth()
                                .background(
                                    if (isSaved) GREEN_BG else Color.Transparent, RoundedCornerShape(8.dp),
                                )
                                .clickable { s.choose(ref, h) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(h, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (isSaved) Text("✓ выбрано", color = GREEN, style = MaterialTheme.typography.labelMedium)
                            else if (isBest) Text("по смыслу", color = GREEN, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            own, { own = it }, Modifier.weight(1f),
                            placeholder = { Text("Свой вариант") }, singleLine = true,
                        )
                        TextButton(onClick = { s.choose(ref, own) }, enabled = own.isNotBlank()) { Text("Сохранить") }
                    }
                    if (saved != null || s.pins.containsKey(ref)) TextButton(onClick = { s.unpin(ref) }) {
                        Text("Убрать перевод с этого слова")
                    }
                }
            }
            HorizontalDivider()
            val tr = s.sentTr[ref.sent]
            if (!tr.isNullOrEmpty()) Text(tr, fontStyle = FontStyle.Italic)
            else TextButton(onClick = { s.toggleSentence(ref.sent) }) { Text("Перевести предложение") }
        }
    }
}
