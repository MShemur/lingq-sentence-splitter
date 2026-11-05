@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.mshemur.lingqfix

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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

// --- model ---------------------------------------------------------------------------------------

/** A piece of a sentence: a word (tappable) or the punctuation/space between words. */
class Token(val text: String, val isWord: Boolean, val index: Int)

/** One sentence of the lesson; [id] is unique in the lesson. */
class Sent(val id: Int, val text: String, val translations: Map<String, String>) {
    val tokens: List<Token> = tokenize(text)

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
    }
}

/** Word the user tapped: sentence + token. */
data class WordRef(val sent: Int, val token: Int)

/** Chunk for line breaking: tokens up to the next space ("(Abb." / "24)," stay together). */
private fun chunks(s: Sent): List<List<Token>> {
    val out = mutableListOf<MutableList<Token>>()
    var cur = mutableListOf<Token>()
    for (t in s.tokens) {
        if (!t.isWord && t.text.any { it.isWhitespace() }) {
            // split "x  y" separators: text before the space stays with the previous chunk
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

// --- session -------------------------------------------------------------------------------------

class ReaderSession(private val app: AppState, val lang: String, val lesson: Lesson) {
    var title by mutableStateOf(lesson.title)
    var paragraphs by mutableStateOf<List<List<Sent>>?>(null)
    var error by mutableStateOf<String?>(null)

    var inlineMode by mutableStateOf(app.prefs.readerMode == "inline")
    var font by mutableStateOf(app.prefs.readerFont)
    var allSentences by mutableStateOf(false)

    /** Sentence translations (shown for the ids in [shownSentences]). */
    val sentTr = mutableStateMapOf<Int, String>()
    val shownSentences = mutableStateMapOf<Int, Boolean>()

    /** Inline mode: the words marked green with their contextual translation ("" while loading). */
    val inline = mutableStateMapOf<WordRef, String>()

    /** Pop-up mode: the word whose window is open. */
    var popup by mutableStateOf<WordRef?>(null)
    var popupHints by mutableStateOf<List<String>?>(null)
    var popupBest by mutableStateOf<String?>(null)
    var popupError by mutableStateOf<String?>(null)

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
                title = t
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

    fun setMode(inline: Boolean) {
        inlineMode = inline
        app.prefs.readerMode = if (inline) "inline" else "popup"
    }

    fun changeFont(d: Int) {
        font = (font + d).coerceIn(13, 32)
        app.prefs.readerFont = font
    }

    // --- translations ---

    /** Translation of a sentence: LingQ's stored one if there is one, otherwise machine translation. */
    private suspend fun sentenceTranslation(id: Int): String {
        sentTr[id]?.takeIf { it.isNotEmpty() && !it.startsWith("⚠") }?.let { return it }
        val s = sentById.getValue(id)
        val t = s.translations[target] ?: withContext(Dispatchers.IO) { app.api.translate(lang, target, s.text) }
        sentTr[id] = t
        return t
    }

    fun toggleSentence(id: Int) {
        if (shownSentences[id] == true) { shownSentences.remove(id); return }
        shownSentences[id] = true
        app.scope.launch {
            try { sentenceTranslation(id) } catch (e: Throwable) { sentTr[id] = "⚠ ${e.message}" }
        }
    }

    fun toggleAllSentences() {
        allSentences = !allSentences
        if (!allSentences) { shownSentences.clear(); return }
        // load lazily as they appear (see ensureSentence)
    }

    fun ensureSentence(id: Int) {
        if (sentTr.containsKey(id) || !loadingSentences.add(id)) return
        app.scope.launch {
            try { sentenceTranslation(id) } catch (e: Throwable) { sentTr[id] = "⚠ ${e.message}" }
            loadingSentences.remove(id)
        }
    }

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
     * Picks the meaning that fits this sentence: the first popular meaning whose word stem
     * occurs in the sentence translation; otherwise the most popular one, otherwise machine translation.
     */
    private suspend fun contextual(ref: WordRef, hs: List<String>): String {
        val word = sentById.getValue(ref.sent).tokens[ref.token].text
        val tr = runCatching { sentenceTranslation(ref.sent) }.getOrDefault("").lowercase()
        val trWords = Regex("[\\p{L}]+").findAll(tr).map { it.value }.toList()
        fun fits(h: String) = Regex("[\\p{L}]+").findAll(h.lowercase()).any { m ->
            val w = m.value
            w.length >= 3 && run {
                val stem = w.take(maxOf(3, minOf(w.length - 2, 6)))
                trWords.any { it.startsWith(stem) }
            }
        }
        return hs.take(10).firstOrNull { fits(it) }
            ?: hs.firstOrNull()
            ?: withContext(Dispatchers.IO) { app.api.translate(lang, target, word) }
    }

    fun tap(ref: WordRef) {
        if (inlineMode) {
            if (inline.containsKey(ref)) { inline.remove(ref); return }
            inline[ref] = ""
            app.scope.launch {
                val w = sentById.getValue(ref.sent).tokens[ref.token].text
                inline[ref] = try { contextual(ref, hints(w)) } catch (e: Throwable) { "⚠" }
            }
        } else {
            popup = ref
            popupHints = null
            popupBest = null
            popupError = null
            app.scope.launch {
                try {
                    val w = sentById.getValue(ref.sent).tokens[ref.token].text
                    val h = hints(w)
                    popupHints = h
                    popupBest = contextual(ref, h)
                } catch (e: Throwable) {
                    popupError = e.message ?: e.toString()
                }
            }
        }
    }

    fun word(ref: WordRef) = sentById[ref.sent]?.tokens?.getOrNull(ref.token)?.text.orEmpty()
}

// --- UI ------------------------------------------------------------------------------------------

private val GREEN = Color(0xFF2E7D32)
private val GREEN_BG = Color(0x3343A047)

@Composable
fun ReaderActions(s: ReaderSession) {
    var menu by remember { mutableStateOf(false) }
    TextButton(onClick = { s.setMode(!s.inlineMode) }) { Text(if (s.inlineMode) "Аᵃ" else "▢") }
    TextButton(onClick = { menu = true }) { Text("⋮") }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(
            text = { Text((if (!s.inlineMode) "● " else "○ ") + "Перевод в окошке") },
            onClick = { s.setMode(false); menu = false },
        )
        DropdownMenuItem(
            text = { Text((if (s.inlineMode) "● " else "○ ") + "Перевод над словом") },
            onClick = { s.setMode(true); menu = false },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text((if (s.allSentences) "☑ " else "☐ ") + "Переводы всех предложений") },
            onClick = { s.toggleAllSentences(); menu = false },
        )
        if (s.inline.isNotEmpty()) DropdownMenuItem(
            text = { Text("Снять выделения (${s.inline.size})") },
            onClick = { s.inline.clear(); menu = false },
        )
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Шрифт крупнее  A+") }, onClick = { s.changeFont(+2) })
        DropdownMenuItem(text = { Text("Шрифт мельче  A−") }, onClick = { s.changeFont(-2) })
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
        paras == null -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        else -> {
            val listState = rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize(), state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text(
                        if (s.inlineMode) "Нажмите на слово — перевод появится над ним. Долгое нажатие — перевод предложения."
                        else "Нажмите на слово — откроется перевод. Долгое нажатие — перевод предложения.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
                itemsIndexed(paras) { _, para -> Paragraph(s, para) }
            }
        }
    }
    s.popup?.let { ref -> WordSheet(s, ref) }
}

@Composable
private fun Paragraph(s: ReaderSession, para: List<Sent>) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = s.font.sp, lineHeight = (s.font * 1.35).sp)
    val trStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (s.font * 0.62).sp, lineHeight = (s.font * 0.7).sp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (sent in para) {
                for (chunk in chunks(sent)) {
                    Row(Modifier.padding(end = (s.font * 0.3).dp), verticalAlignment = Alignment.Bottom) {
                        for (t in chunk) {
                            val ref = WordRef(sent.id, t.index)
                            val tr = if (t.isWord) s.inline[ref] else null
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (s.inlineMode && tr != null) Text(
                                    tr.ifEmpty { "…" }, style = trStyle, color = GREEN, maxLines = 2,
                                    modifier = Modifier.padding(horizontal = 1.dp),
                                )
                                val selected = tr != null || (!s.inlineMode && s.popup == ref)
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
        for (sent in para) {
            val shown = s.allSentences || s.shownSentences[sent.id] == true
            if (!shown) continue
            if (s.allSentences) LaunchedEffect(sent.id) { s.ensureSentence(sent.id) }
            val tr = s.sentTr[sent.id]
            Text(
                if (tr.isNullOrEmpty()) "…" else tr,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = (s.font * 0.8).sp),
                fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(8.dp)
                    .combinedClickable(onClick = { if (!s.allSentences) s.toggleSentence(sent.id) }),
            )
        }
    }
}

@Composable
private fun WordSheet(s: ReaderSession, ref: WordRef) {
    ModalBottomSheet(onDismissRequest = { s.popup = null }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(s.word(ref), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            val hints = s.popupHints
            val best = s.popupBest
            when {
                s.popupError != null -> Text("Ошибка: ${s.popupError}", color = MaterialTheme.colorScheme.error)
                hints == null -> CircularProgressIndicator()
                else -> {
                    if (best != null) Text(
                        "По смыслу: $best", style = MaterialTheme.typography.titleMedium, color = GREEN,
                    )
                    if (hints.isEmpty()) Text(
                        if (best == null) "Ищу перевод…" else "Вариантов от пользователей LingQ нет, это машинный перевод.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    ) else {
                        Text("Варианты LingQ:", style = MaterialTheme.typography.labelLarge)
                        hints.take(8).forEach { h ->
                            Text("• $h", style = MaterialTheme.typography.bodyLarge)
                        }
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
