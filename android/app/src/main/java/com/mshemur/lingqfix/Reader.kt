@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.mshemur.lingqfix

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
class Sent(val id: Int, val text: String, val translations: Map<String, String>, val lingqIndex: Int = id + 1) {
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

/** Several words of one sentence selected together: tokens [from]..[to]. */
data class Phrase(val sent: Int, val from: Int, val to: Int, val text: String)

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

class ReaderSession(private val app: AppState, val lang: String, val lesson: Lesson, startSent: Int? = null) {
    /** Sentence to scroll to (bookmark); consumed by the screen. */
    var jump by mutableStateOf(startSent)

    fun jumpTo(id: Int) {
        jump = id
        page = sentences.indexOfFirst { it.id == id }.coerceAtLeast(0)
    }

    fun isBookmarked(id: Int) = app.bookmarks.has(lesson.id, id)

    fun toggleBookmark(id: Int) {
        val s = sentById[id] ?: return
        val tr = sentTr[id]?.takeIf { it.isNotEmpty() && !it.startsWith("⚠") } ?: s.translations[target].orEmpty()
        val on = app.bookmarks.toggle(lang, Lesson(lesson.id, title), id, s.text, tr)
        app.say(if (on) "Предложение в закладках" else "Закладка удалена")
    }

    // --- several words: long press on a word and drag over the others ---

    /** Where each word is on screen, per view ("text" pages, "sent" sentence mode); for hit tests while dragging. */
    val wordCoords = HashMap<Pair<String, WordRef>, LayoutCoordinates>()
    /** Selection being dragged / the phrase whose window is open. */
    var selSent by mutableStateOf(-1)
    var selFrom by mutableStateOf(-1)
    var selTo by mutableStateOf(-1)
    private var selAnchor = -1

    fun inSelection(ref: WordRef) = ref.sent == selSent && ref.token in selFrom..selTo

    fun selectStart(ref: WordRef) { selSent = ref.sent; selAnchor = ref.token; selFrom = ref.token; selTo = ref.token }

    fun selectTo(ref: WordRef) {
        if (ref.sent != selSent) return
        selFrom = minOf(selAnchor, ref.token); selTo = maxOf(selAnchor, ref.token)
    }

    fun clearSelection() { selSent = -1; selFrom = -1; selTo = -1 }

    /** The word of sentence [sent] under the finger ([root] = position in the window), or the nearest one. */
    fun wordAt(area: String, sent: Int, root: Offset): WordRef? {
        var best: WordRef? = null
        var bestD = Float.MAX_VALUE
        for ((k, c) in wordCoords) {
            if (k.first != area || k.second.sent != sent || !c.isAttached) continue
            val r = c.boundsInRoot()
            if (r.contains(root)) return k.second
            val dx = maxOf(r.left - root.x, 0f, root.x - r.right)
            val dy = maxOf(r.top - root.y, 0f, root.y - r.bottom)
            val d = dx + dy * 3            // another line counts more than a few letters aside
            if (d < bestD) { bestD = d; best = k.second }
        }
        return best
    }

    /** Long press released: several words -> their translation; one word -> sentence translation (as before). */
    fun selectEnd() {
        val sent = sentById[selSent]
        val words = sent?.tokens?.subList(selFrom, selTo + 1)?.count { it.isWord } ?: 0
        if (sent == null || words < 2) { val id = selSent; clearSelection(); if (sent != null) toggleSentence(id); return }
        openPhrase(sent.id, selFrom, selTo)
    }

    var phrase by mutableStateOf<Phrase?>(null)
    var phraseBest by mutableStateOf<String?>(null)
    var phraseOptions by mutableStateOf<List<String>>(emptyList())
    var phraseNote by mutableStateOf("")
    var phraseLoading by mutableStateOf(false)
    var phraseError by mutableStateOf<String?>(null)

    /** Contextual translation of several words together (Gemini, or LingQ's meanings + machine translation). */
    fun openPhrase(sent: Int, from: Int, to: Int) {
        val st = sentById[sent] ?: return
        val text = st.tokens.subList(from, to + 1).joinToString("") { it.text }.trim()
        val p = Phrase(sent, from, to, text)
        focusSent = sent
        selSent = sent; selFrom = from; selTo = to
        phrase = p
        phraseBest = chosen[text.lowercase()]
        phraseOptions = emptyList(); phraseNote = ""; phraseError = null; phraseLoading = true
        app.scope.launch {
            try {
                if (app.useGemini) {
                    val prompt = app.prefs.promptWord + "\n\nВажно: «{word}» — не одно слово, а словосочетание. " +
                        "Переведи его целиком как единое целое по смыслу в этом предложении; best может быть длиннее трёх слов."
                    val m = withContext(Dispatchers.IO) { app.gemini().word(prompt, lang, target, text, st.text) }
                    if (phrase != p) return@launch
                    phraseBest = chosen[text.lowercase()] ?: m.best
                    phraseOptions = dedupe(listOf(m.best) + m.options)
                    phraseNote = m.note
                } else {
                    val h = runCatching { hints(text) }.getOrDefault(emptyList())
                    val t = withContext(Dispatchers.IO) { app.api.translate(lang, target, text) }
                    if (phrase != p) return@launch
                    phraseBest = chosen[text.lowercase()] ?: t
                    phraseOptions = dedupe(listOf(t) + h.take(8))
                }
            } catch (e: Throwable) {
                if (phrase == p) phraseError = e.message ?: e.toString()
                app.geminiFailed(e) { if (phrase == p) openPhrase(sent, from, to) }
            }
            if (phrase == p) phraseLoading = false
        }
    }

    fun choosePhrase(meaning: String) {
        val p = phrase ?: return
        val m = meaning.trim().ifEmpty { return }
        chosen[p.text.lowercase()] = m
        phraseBest = m
        persist()
    }

    fun closePhrase() { phrase = null; clearSelection() }

    private fun dedupe(l: List<String>): List<String> {
        val seen = HashSet<String>()
        return l.map { it.trim() }.filter { it.isNotEmpty() && seen.add(norm(it)) }
    }

    /** Last sentence the user worked with (tapped word, long press); sentence mode opens there. */
    private var focusSent: Int? = null
    /** Paragraphs on screen in the text view, and how far the reader got (top-bar progress). */
    var visibleSents: Set<Int> = emptySet()
    var textProgress by mutableFloatStateOf(0f)
    var pageLabel by mutableStateOf("")
    /** First sentence of the page being read: the page is kept when pages are rebuilt (pins, font). */
    var anchor: Int? = null
    /** After sentence mode is collapsed: sentence the text view should show. */
    var syncTo by mutableStateOf<Int?>(null)

    /** Sentence mode slides up over the text, at the selected word's sentence or the first one on screen. */
    fun openSentenceMode() {
        if (sentences.isEmpty()) return
        val id = focusSent?.takeIf { it in visibleSents } ?: visibleSents.minOrNull()
        page = sentences.indexOfFirst { it.id == id }.coerceAtLeast(0)
        sentenceMode = true
    }

    fun closeSentenceMode() {
        sentenceMode = false
        sentences.getOrNull(page)?.let { focusSent = it.id; syncTo = it.id }
    }

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
    var popupNote by mutableStateOf("")
    var popupAi by mutableStateOf<List<String>>(emptyList())
    private val aiCache = HashMap<WordRef, WordMeaning>()

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
                jump?.let { id -> page = sentences.indexOfFirst { it.id == id }.coerceAtLeast(0) }
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
                val sent = Sent(id, text, tr, s.optInt("index", id + 1))
                id++
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
        val t = s.translations[target] ?: withContext(Dispatchers.IO) {
            if (app.useGemini) app.gemini().sentence(app.prefs.promptSentence, lang, target, s.text)
            else app.api.translate(lang, target, s.text)
        }
        sentTr[id] = t
        return t
    }

    fun ensureSentence(id: Int) {
        if (!sentTr[id].isNullOrEmpty() || !loadingSentences.add(id)) return
        app.scope.launch {
            try { sentenceTranslation(id) } catch (e: Throwable) {
                sentTr[id] = "⚠ ${e.message}"
                app.geminiFailed(e) { sentTr.remove(id); ensureSentence(id) }
            }
            loadingSentences.remove(id)
        }
    }

    /** Sentence ids whose translation is being redone right now. */
    val retranslating = mutableStateMapOf<Int, Boolean>()

    /**
     * "Translate again": Gemini makes a different translation (told the current one is poor);
     * without Gemini LingQ's ChatGPT retranslation is used (it is stored in the lesson on LingQ too).
     */
    fun retranslate(id: Int) {
        val s = sentById[id] ?: return
        if (retranslating[id] == true) return
        retranslating[id] = true
        app.scope.launch {
            try {
                val current = sentTr[id]?.takeIf { it.isNotEmpty() && !it.startsWith("⚠") } ?: s.translations[target].orEmpty()
                val t = withContext(Dispatchers.IO) {
                    if (app.useGemini) app.gemini().retranslate(app.prefs.promptRetranslate, lang, target, s.text, current)
                    else app.api.retranslate(lang, lesson.id, s.lingqIndex, target).let { r -> r[s.lingqIndex] ?: r.values.firstOrNull() }
                }
                if (t.isNullOrBlank()) app.say("Переводчик не вернул перевод") else sentTr[id] = t.trim()
            } catch (e: Throwable) {
                app.say(e.message ?: "Ошибка перевода")
                app.geminiFailed(e) { retranslate(id) }
            }
            retranslating.remove(id)
        }
    }

    fun toggleSentence(id: Int) {
        focusSent = id
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
    /** Gemini: meaning of the word in this very sentence (cached). */
    private suspend fun ai(ref: WordRef): WordMeaning {
        aiCache[ref]?.let { return it }
        val s = sentById.getValue(ref.sent)
        val m = withContext(Dispatchers.IO) { app.gemini().word(app.prefs.promptWord, lang, target, word(ref), s.text) }
        aiCache[ref] = m
        return m
    }

    /**
     * Instant guess from LingQ's meanings: the one whose stem occurs in a sentence translation that is
     * already at hand (nothing slow is fetched), else the most popular one.
     */
    private fun quickGuess(ref: WordRef, hs: List<String>): String? {
        chosen[word(ref).lowercase()]?.let { return it }
        val s = sentById[ref.sent] ?: return hs.firstOrNull()
        val tr = (sentTr[ref.sent]?.takeIf { it.isNotEmpty() } ?: s.translations[target]).orEmpty().lowercase()
        return pickByTranslation(hs, tr) ?: hs.firstOrNull()
    }

    private fun pickByTranslation(hs: List<String>, tr: String): String? {
        if (tr.isBlank()) return null
        val trWords = Regex("\\p{L}+").findAll(tr).map { it.value }.toList()
        fun fits(h: String) = Regex("\\p{L}+").findAll(h.lowercase()).any { m ->
            val w = m.value
            w.length >= 3 && trWords.any { it.startsWith(w.take(maxOf(3, minOf(w.length - 2, 6)))) }
        }
        return hs.take(10).firstOrNull { fits(it) }
    }

    private suspend fun contextual(ref: WordRef, hs: List<String>): String {
        val word = word(ref)
        chosen[word.lowercase()]?.let { return it }
        if (app.useGemini) runCatching { ai(ref).best }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
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

    /** One tap: translation pinned above the word (tap again removes it). */
    fun tap(ref: WordRef) {
        focusSent = ref.sent
        if (pins.containsKey(ref)) { pins.remove(ref); provisional.remove(ref); persist(); return }
        pins[ref] = ""
        val saved = chosen[word(ref).lowercase()]
        if (saved != null) { pins[ref] = saved; persist(); return }
        if (!app.useGemini) {
            app.scope.launch {
                pins[ref] = try { contextual(ref, hints(word(ref))) } catch (e: Throwable) { "⚠" }
                persist()
            }
            return
        }
        // Gemini: show LingQ's most likely meaning at once (grey), Gemini's answer replaces it (green)
        provisional[ref] = true
        app.scope.launch {
            val quick = runCatching { quickGuess(ref, hints(word(ref))) }.getOrNull()
            if (provisional[ref] == true && !quick.isNullOrBlank() && pins.containsKey(ref)) pins[ref] = quick
        }
        app.scope.launch {
            val best = runCatching { ai(ref).best }
                .onFailure { e -> app.geminiFailed(e) { pins.remove(ref); provisional.remove(ref); tap(ref) } }.getOrNull()
            if (!pins.containsKey(ref)) return@launch            // removed meanwhile
            provisional.remove(ref)
            when {
                !best.isNullOrBlank() -> pins[ref] = best
                pins[ref].isNullOrEmpty() -> pins[ref] = "⚠"
            }
            persist()
        }
    }

    /** Pins that show LingQ's quick guess while Gemini is still thinking. */
    val provisional = mutableStateMapOf<WordRef, Boolean>()
    /** Window: Gemini is still thinking. */
    var popupThinking by mutableStateOf(false)

    /** Double tap: window with all meanings to choose from. */
    fun openPopup(ref: WordRef) {
        run {
            focusSent = ref.sent
            popup = ref
            popupHints = null
            popupBest = null
            popupError = null
            popupNote = ""
            popupAi = emptyList()
            app.scope.launch {
                try {
                    // LingQ's meanings and a quick guess first, Gemini adds its answer when ready
                    val h = runCatching { hints(word(ref)) }.getOrDefault(emptyList())
                    popupHints = h
                    if (app.useGemini) {
                        popupBest = quickGuess(ref, h)
                        popupThinking = true
                        val m = runCatching { ai(ref) }
                            .onFailure { e -> app.geminiFailed(e) { if (popup == ref) openPopup(ref) } }.getOrNull()
                        popupThinking = false
                        if (popup != ref) return@launch
                        if (m != null) {
                            popupBest = chosen[word(ref).lowercase()] ?: m.best.ifBlank { popupBest }
                            popupAi = listOf(m.best) + m.options
                            popupNote = m.note
                        }
                    } else popupBest = contextual(ref, h)
                } catch (e: Throwable) {
                    popupThinking = false
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

    /**
     * Meanings for the window, without duplicates: the chosen one, the contextual one, LingQ's popular
     * meanings, and the parts of the two most popular ones ("уважаемый, почтенный" -> both words too).
     */
    fun options(ref: WordRef): List<String> {
        val hs = popupHints.orEmpty()
        val parts = hs.take(2).flatMap { h -> h.split(',', ';', '/', '|').map { it.trim() } }.filter { it.isNotEmpty() }
        val all = listOfNotNull(chosen[word(ref).lowercase()], popupBest) + popupAi + hs.take(8) + parts
        val seen = HashSet<String>()
        return all.map { it.trim().trim('.', ' ') }.filter { it.isNotEmpty() && seen.add(norm(it)) }
    }

    private fun norm(t: String) = t.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // --- chat with Gemini ---

    fun chatText() {
        val text = paragraphs.orEmpty().joinToString("\n\n") { p -> p.joinToString(" ") { it.text } }
        val sys = Prompts.fill(app.prefs.promptChatText, "lang" to lang, "target" to target, "text" to text)
        app.go(Screen.Chat(ChatSession(app, "Чат о тексте", sys, title, wholeText = true)))
    }

    fun chatSentence(id: Int) {
        val s = sentById[id] ?: return
        popup = null
        val sys = Prompts.fill(app.prefs.promptChatSentence, "lang" to lang, "target" to target, "sentence" to s.text)
        app.go(Screen.Chat(ChatSession(app, "Чат о предложении", sys, s.text)))
    }

    fun word(ref: WordRef) = sentById[ref.sent]?.tokens?.getOrNull(ref.token)?.text.orEmpty()
}

// --- UI ------------------------------------------------------------------------------------------

private val GREEN = Color(0xFF2E7D32)
private val GREEN_BG = Color(0x3343A047)

/** Top bar: font size and extras. */
@Composable
fun ReaderActions(state: AppState, s: ReaderSession) {
    var menu by remember { mutableStateOf(false) }
    var marks by remember { mutableStateOf(false) }
    IconButton(onClick = { marks = true }) { Icon(AppIcons.Bookmark, contentDescription = "Закладки урока") }
    if (marks) LessonBookmarksDialog(state, s) { marks = false }
    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(
            text = { Text("Чат о тексте") },
            leadingIcon = { Icon(AppIcons.Chat, contentDescription = null) },
            onClick = { menu = false; s.chatText() },
        )
        HorizontalDivider()
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

/** Bottom bar of the reader, like LingQ's: whole text / sentence by sentence / sentence translations. */
@Composable
fun ReaderBottomBar(s: ReaderSession) {
    NavigationBar {
        NavigationBarItem(
            selected = false, onClick = { s.openSentenceMode() },
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }, label = { Text("По предложениям") },
        )
        NavigationBarItem(
            selected = s.allSentences, onClick = { s.allSentences = !s.allSentences },
            icon = { Icon(AppIcons.Translate, contentDescription = null) },
            label = { Text("Переводы") },
        )
        NavigationBarItem(
            selected = false, onClick = { s.chatText() },
            icon = { Icon(AppIcons.Chat, contentDescription = null) }, label = { Text("Чат") },
        )
    }
}

/** Rounded progress bar in the middle of the reader's top bar. */
@Composable
fun ProgressPill(p: Float) {
    LinearProgressIndicator(
        progress = { p.coerceIn(0f, 1f) },
        modifier = Modifier.width(150.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
        strokeCap = StrokeCap.Round,
    )
}

@Composable
fun ReaderProgress(s: ReaderSession) {
    if (s.paragraphs != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ProgressPill(s.textProgress)
        if (s.pageLabel.isNotEmpty()) Text(
            s.pageLabel, Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        )
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
        else -> PagedText(s, paras)
    }
    s.popup?.let { ref -> WordSheet(s, ref) }
    s.phrase?.let { p -> PhraseSheet(s, p) }
}

/** Part of a paragraph that lies on one page. */
private class Block(val sents: List<Sent>)

/**
 * Heights of text pieces as [Paragraph] / [SentenceFlow] / [SentenceTranslation] will draw them, computed from
 * text sizes only (composing the whole lesson just to measure it runs out of memory on long lessons).
 * Keep in step with those composables.
 */
private class TextMetrics(
    private val m: TextMeasurer, private val d: Density, private val s: ReaderSession, private val width: Int,
    textStyle: TextStyle, trStyle: TextStyle, sentTrStyle: TextStyle,
    /** Text widths, kept between rebuilds while the font is the same. */
    private val widths: HashMap<String, Int>,
) {
    private val size = s.font.toFloat()
    private val text = textStyle.copy(fontSize = size.sp, lineHeight = (size * 1.35).sp)
    private val tr = trStyle.copy(fontSize = (size * 0.6).sp, lineHeight = (size * 0.68).sp)
    private val sentTr = sentTrStyle.copy(fontSize = (s.font * 0.8f).sp, fontStyle = FontStyle.Italic)
    private fun px(dp: Float) = with(d) { dp.dp.roundToPx() }
    private val lineH = m.measure("Ag", text, maxLines = 1).size.height
    private val chunkGap = px(size * 0.3f)
    private val icon = px(size * 0.9f)

    private fun w(t: String) = widths.getOrPut(t) { m.measure(t, text, maxLines = 1, softWrap = false).size.width }

    /** Width and height of one chunk (words that stay together), translations above words included. */
    private fun chunk(sent: Sent, chunk: List<Token>): Pair<Int, Int> {
        var cw = 0
        var ch = lineH
        val pinned = chunk.any { it.isWord && s.pins[WordRef(sent.id, it.index)] != null }
        if (!pinned) cw = w(chunk.joinToString("") { it.text })
        else for (t in chunk) {
            val pin = if (t.isWord) s.pins[WordRef(sent.id, t.index)] else null
            var tw = w(t.text)
            if (pin != null) {
                val r = m.measure(pin.ifEmpty { "…" }, tr, maxLines = 2, constraints = Constraints(maxWidth = width))
                tw = maxOf(tw, r.size.width + px(2f))
                ch = maxOf(ch, lineH + r.size.height)
            }
            cw += tw
        }
        return (cw + chunkGap) to ch
    }

    /** FlowRow of sentences: items wrap to the next line, lines 2 dp apart. */
    private fun flow(sents: List<Sent>): Int {
        var total = 0
        var lineW = 0
        var lineMax = 0
        var lines = 0
        fun put(iw: Int, ih: Int) {
            if (lineW > 0 && lineW + iw > width) { total += lineMax; lines++; lineW = 0; lineMax = 0 }
            lineW += iw; lineMax = maxOf(lineMax, ih)
        }
        for (sent in sents) {
            if (s.isBookmarked(sent.id)) put(icon + px(2f), icon + px(4f))
            for (c in sent.chunks) { val (cw, ch) = chunk(sent, c); put(cw, ch) }
        }
        if (lineW > 0) { total += lineMax; lines++ }
        return total + px(2f) * maxOf(0, lines - 1)
    }

    private fun translationRow(id: Int): Int {
        val t = s.sentTr[id].let { if (it.isNullOrEmpty()) "…" else it }
        val tw = (width - px(12f + 2f + 3 * 36f)).coerceAtLeast(1)
        val th = m.measure(t, sentTr, constraints = Constraints(maxWidth = tw)).size.height
        return maxOf(th, px(36f)) + px(8f)
    }

    /** Height of [Paragraph] for these sentences. */
    fun paragraph(sents: List<Sent>): Int {
        val parts = mutableListOf<Int>()
        var run = mutableListOf<Sent>()
        for (sent in sents) {
            run += sent
            if (s.sentenceShown(sent.id)) { parts += flow(run); parts += translationRow(sent.id); run = mutableListOf() }
        }
        if (run.isNotEmpty()) parts += flow(run)
        return parts.sum() + px(6f) * maxOf(0, parts.size - 1)
    }
}

/**
 * The text split into pages that fit the screen, turned with a swipe like a book. A paragraph that does
 * not fit is split at a sentence boundary. Pages are rebuilt when pins, translations or the font change.
 */
@Composable
private fun PagedText(s: ReaderSession, paras: List<List<Sent>>) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.bodyLarge
    val trStyle = MaterialTheme.typography.labelSmall
    val sentTrStyle = MaterialTheme.typography.bodyMedium
    val widths = remember(s.font, density) { HashMap<String, Int>() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padH = with(density) { 16.dp.roundToPx() }
        val padV = with(density) { 12.dp.roundToPx() }
        val gap = with(density) { 14.dp.roundToPx() }
        val w = (constraints.maxWidth - 2 * padH).coerceAtLeast(1)
        // a little spare room: the estimate may be a few pixels off
        val h = if (constraints.hasBoundedHeight) ((constraints.maxHeight - 2 * padV) * 0.96f).toInt() else Int.MAX_VALUE
        val metrics = TextMetrics(measurer, density, s, w, textStyle, trStyle, sentTrStyle, widths)

        val pages = mutableListOf<List<Block>>()
        var cur = mutableListOf<Block>()
        var used = 0
        fun need(x: Int) = (if (cur.isEmpty()) 0 else gap) + x
        fun flush() {
            if (cur.isNotEmpty()) pages += cur
            cur = mutableListOf(); used = 0
        }
        paras.forEach { para ->
            var from = 0
            while (from < para.size) {
                val rest = para.subList(from, para.size)
                val hRest = metrics.paragraph(rest)
                if (used + need(hRest) <= h) { used += need(hRest); cur += Block(rest); break }
                // the longest run of sentences that still fits on this page
                var k = from
                var hk = 0
                while (k + 1 < para.size) {
                    val hh = metrics.paragraph(para.subList(from, k + 1))
                    if (used + need(hh) > h) break
                    k++; hk = hh
                }
                when {
                    k > from -> { used += need(hk); cur += Block(para.subList(from, k)); from = k; flush() }
                    cur.isNotEmpty() -> flush()
                    else -> { cur += Block(listOf(para[from])); from++; flush() }   // taller than a page
                }
            }
        }
        flush()
        TextPager(s, pages)
    }
}

@Composable
private fun TextPager(s: ReaderSession, pages: List<List<Block>>) {
    val pager = rememberPagerState { pages.size }
    val latest by rememberUpdatedState(pages)
    val sig = pages.map { p -> p.first().sents.first().id }   // changes only when the split changes
    fun pageOf(id: Int) = latest.indexOfFirst { p -> p.any { b -> b.sents.any { it.id == id } } }

    // same text stays on screen when pages are rebuilt
    LaunchedEffect(sig) {
        val i = s.anchor?.let { pageOf(it) } ?: -1
        if (i >= 0 && i != pager.currentPage) pager.scrollToPage(i)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { i ->
            latest.getOrNull(i)?.let { s.anchor = it.first().sents.first().id }
        }
    }
    LaunchedEffect(pager.currentPage, sig) {
        val p = latest.getOrNull(pager.currentPage) ?: return@LaunchedEffect
        s.visibleSents = p.flatMap { b -> b.sents.map { it.id } }.toSet()
        s.textProgress = (pager.currentPage + 1f) / latest.size
        s.pageLabel = "${pager.currentPage + 1} / ${latest.size}"
    }
    // bookmark: go to its page
    LaunchedEffect(s.jump, sig) {
        val id = s.jump ?: return@LaunchedEffect
        val i = pageOf(id)
        if (i >= 0) { s.anchor = id; pager.scrollToPage(i) }
        s.jump = null
    }
    // sentence mode collapsed: show the page of the sentence it ended on
    LaunchedEffect(s.syncTo, sig) {
        val id = s.syncTo ?: return@LaunchedEffect
        val i = pageOf(id)
        if (i >= 0 && i != pager.currentPage) pager.animateScrollToPage(i)
        s.syncTo = null
    }
    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            latest.getOrNull(i)?.forEach { b -> Paragraph(s, b.sents) }
        }
    }
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
private fun SentenceFlow(s: ReaderSession, sents: List<Sent>, scale: Float = 1f, area: String = "text") {
    val size = s.font * scale
    val haptic = LocalHapticFeedback.current
    val selBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp, lineHeight = (size * 1.35).sp)
    val trStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (size * 0.6).sp, lineHeight = (size * 0.68).sp)
    FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (sent in sents) {
            if (s.isBookmarked(sent.id)) Icon(
                AppIcons.Bookmark, contentDescription = "Закладка", tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Bottom).padding(end = 2.dp, bottom = 4.dp).size((size * 0.9).dp),
            )
            for (chunk in sent.chunks) {
                // bottom-aligned: a translation above one word pushes the whole line down
                Row(Modifier.align(Alignment.Bottom).padding(end = (size * 0.3).dp), verticalAlignment = Alignment.Bottom) {
                    for (t in chunk) {
                        val ref = WordRef(sent.id, t.index)
                        val tr = if (t.isWord) s.pins[ref] else null
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (tr != null) Text(
                                tr.ifEmpty { "…" }, style = trStyle, maxLines = 2,
                                color = if (s.provisional[ref] == true) MaterialTheme.colorScheme.outline else GREEN,
                                modifier = Modifier.padding(horizontal = 1.dp),
                            )
                            val selected = tr != null || s.popup == ref
                            val bg = when {
                                s.inSelection(ref) -> selBg
                                selected -> GREEN_BG
                                else -> Color.Transparent
                            }
                            if (t.isWord) DisposableEffect(area, ref) { onDispose { s.wordCoords.remove(area to ref) } }
                            Text(
                                t.text, style = textStyle,
                                modifier = if (!t.isWord) Modifier.background(
                                    if (s.inSelection(ref)) selBg else Color.Transparent,
                                ) else Modifier
                                    .background(bg, RoundedCornerShape(4.dp))
                                    .onGloballyPositioned { s.wordCoords[area to ref] = it }
                                    .pointerInput(ref, area) { wordGestures(s, area, ref, haptic) },
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
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable { if (!s.allSentences) s.toggleSentence(id) }
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (tr.isNullOrEmpty()) "…" else tr,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = (s.font * 0.8f * scale).sp),
            fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        BookmarkButton(s, id)
        RetranslateButton(s, id)
        IconButton(onClick = { s.chatSentence(id) }, modifier = Modifier.size(36.dp)) {
            Icon(AppIcons.Chat, contentDescription = "Чат о предложении", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BookmarkButton(s: ReaderSession, id: Int) {
    val on = s.isBookmarked(id)
    IconButton(onClick = { s.toggleBookmark(id) }, modifier = Modifier.size(36.dp)) {
        Icon(
            if (on) AppIcons.Bookmark else AppIcons.BookmarkBorder,
            contentDescription = if (on) "Убрать из закладок" else "В закладки",
            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Translate the sentence again (Gemini, or LingQ's ChatGPT). */
@Composable
private fun RetranslateButton(s: ReaderSession, id: Int) {
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        if (s.retranslating[id] == true) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else IconButton(onClick = { s.retranslate(id) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "Перевести заново", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Sentence mode lies over the text: slides up from the bottom, the arrow collapses it back. */
@Composable
fun SentenceOverlay(s: ReaderSession) {
    AnimatedVisibility(
        visible = s.sentenceMode && s.sentences.isNotEmpty(),
        enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(480, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(480)),
    ) {
        BackHandler { s.closeSentenceMode() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            SentencePager(s)
        }
    }
}

/** Sentence mode: one sentence with its translation per page, swipe for the next one. */
@Composable
private fun SentencePager(s: ReaderSession) {
    val list = s.sentences
    val pager = rememberPagerState(initialPage = s.page.coerceIn(0, maxOf(0, list.size - 1))) { list.size }
    LaunchedEffect(pager.currentPage) { s.page = pager.currentPage }
    LaunchedEffect(s.jump) {
        val id = s.jump ?: return@LaunchedEffect
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) pager.scrollToPage(i)
        s.jump = null
    }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
            IconButton(onClick = { s.closeSentenceMode() }, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Свернуть", modifier = Modifier.size(32.dp))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                ProgressPill((pager.currentPage + 1f) / maxOf(1, list.size))
                Text(
                    "${pager.currentPage + 1} / ${list.size}", Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            list.getOrNull(pager.currentPage)?.let {
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { BookmarkButton(s, it.id) }
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
            val sent = list[i]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                SentenceFlow(s, listOf(sent), scale = 1.15f, area = "sent")
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
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val word = s.word(ref)
            val saved = s.chosen[word.lowercase()]
            val best = s.popupBest
            // word and its meaning here
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(word, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    val main = saved ?: best
                    if (main != null) Text(
                        main, style = MaterialTheme.typography.titleMedium,
                        color = if (saved == null && s.popupThinking) MaterialTheme.colorScheme.outline else GREEN,
                    )
                }
                if (s.popupThinking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (saved != null || s.pins.containsKey(ref)) IconButton(onClick = { s.unpin(ref) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Убрать перевод с этого слова")
                }
            }
            if (s.popupNote.isNotBlank()) Text(
                s.popupNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val hints = s.popupHints
            when {
                s.popupError != null -> Text("Ошибка: ${s.popupError}", color = MaterialTheme.colorScheme.error)
                hints == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.options(ref).forEach { h ->
                            val isSaved = h.equals(saved, ignoreCase = true)
                            FilterChip(
                                selected = isSaved, onClick = { s.choose(ref, h) },
                                label = { Text(h) },
                                leadingIcon = if (isSaved) { { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp)) } } else null,
                            )
                        }
                    }
                    OutlinedTextField(
                        own, { own = it }, Modifier.fillMaxWidth(),
                        placeholder = { Text("Свой вариант") }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            IconButton(onClick = { s.choose(ref, own) }, enabled = own.isNotBlank()) {
                                Icon(Icons.Filled.Check, contentDescription = "Сохранить")
                            }
                        },
                    )
                }
            }
            HorizontalDivider()
            // the sentence: translation and actions
            val tr = s.sentTr[ref.sent]
            if (!tr.isNullOrEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                RetranslateButton(s, ref.sent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tr.isNullOrEmpty()) AssistChip(
                    onClick = { s.toggleSentence(ref.sent) }, label = { Text("Перевод") },
                    leadingIcon = { Icon(AppIcons.Translate, contentDescription = null, Modifier.size(18.dp)) },
                )
                val marked = s.isBookmarked(ref.sent)
                AssistChip(
                    onClick = { s.toggleBookmark(ref.sent) }, label = { Text(if (marked) "В закладках" else "Закладка") },
                    leadingIcon = {
                        Icon(if (marked) AppIcons.Bookmark else AppIcons.BookmarkBorder, contentDescription = null, Modifier.size(18.dp))
                    },
                )
                AssistChip(
                    onClick = { s.chatSentence(ref.sent) }, label = { Text("Спросить") },
                    leadingIcon = { Icon(AppIcons.Chat, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/**
 * Word gestures: tap = translation above the word, double tap = word window, long press = sentence translation,
 * long press and drag over other words of the sentence = translate those words together.
 * Nothing is consumed before the long press, so a swipe still turns the page.
 */
private suspend fun PointerInputScope.wordGestures(s: ReaderSession, area: String, ref: WordRef, haptic: HapticFeedback) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var ended = false
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation().also { ended = true }
        }
        if (ended && up == null) return@awaitEachGesture          // a swipe took the touch
        if (up != null) {
            up.consume()
            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
            if (second == null) s.tap(ref)
            else { second.consume(); waitForUpOrCancellation()?.consume(); s.openPopup(ref) }
            return@awaitEachGesture
        }
        // long press: select from this word, follow the finger
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        s.selectStart(ref)
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            ch.consume()
            if (!ch.pressed) break
            val own = s.wordCoords[area to ref]
            if (own == null || !own.isAttached) continue
            s.wordAt(area, ref.sent, own.localToRoot(ch.position))?.let { s.selectTo(it) }
        }
        s.selectEnd()
    }
}

/** Window for several words translated together. */
@Composable
private fun PhraseSheet(s: ReaderSession, p: Phrase) {
    var own by remember(p) { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { s.closePhrase() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(p.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            s.phraseBest?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = GREEN) }
            if (s.phraseLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.phraseError?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
            if (s.phraseNote.isNotBlank()) Text(
                s.phraseNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val saved = s.chosen[p.text.lowercase()]
            if (s.phraseOptions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.phraseOptions.forEach { h ->
                    val isSaved = h.equals(saved, ignoreCase = true)
                    FilterChip(
                        selected = isSaved, onClick = { s.choosePhrase(h) }, label = { Text(h) },
                        leadingIcon = if (isSaved) { { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp)) } } else null,
                    )
                }
            }
            if (!s.phraseLoading) OutlinedTextField(
                own, { own = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("Свой вариант") }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    IconButton(onClick = { s.choosePhrase(own); own = "" }, enabled = own.isNotBlank()) {
                        Icon(Icons.Filled.Check, contentDescription = "Сохранить")
                    }
                },
            )
            HorizontalDivider()
            val tr = s.sentTr[p.sent]
            if (!tr.isNullOrEmpty()) Text(
                tr, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tr.isNullOrEmpty()) AssistChip(
                    onClick = { s.ensureSentence(p.sent) }, label = { Text("Перевод предложения") },
                    leadingIcon = { Icon(AppIcons.Translate, contentDescription = null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { s.closePhrase(); s.chatSentence(p.sent) }, label = { Text("Спросить") },
                    leadingIcon = { Icon(AppIcons.Chat, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}
