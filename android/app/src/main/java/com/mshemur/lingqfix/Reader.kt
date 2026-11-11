@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.mshemur.lingqfix

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.produceState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.material.icons.filled.PlayArrow
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
import kotlinx.coroutines.CompletableDeferred
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
class Sent(
    val id: Int, val text: String, val translations: Map<String, String>, val lingqIndex: Int = id + 1,
    /** Where it sounds in the lesson audio, ms (-1 = unknown). */
    val start: Long = -1, val end: Long = -1,
) {
    val tokens: List<Token> = tokenize(text)
    val chunks: List<List<Token>> = chunks(tokens)

    companion object {
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:[-'’][\\p{L}\\p{N}]+)*")
        private val SPACES = Regex("\\s+")

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
                    // " – " : the part before the space ends a chunk, the part after starts the next,
                    // anything between spaces (a dash) is a chunk of its own
                    val parts = t.text.split(SPACES)
                    if (parts.first().isNotEmpty()) cur += Token(parts.first(), false, t.index)
                    if (cur.isNotEmpty()) out += cur
                    cur = mutableListOf()
                    for (m in parts.subList(1, maxOf(1, parts.size - 1))) if (m.isNotEmpty()) out += listOf(Token(m, false, t.index))
                    if (parts.size > 1 && parts.last().isNotEmpty()) cur += Token(parts.last(), false, t.index)
                } else cur += t
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }
    }
}

/** Added to the word prompt: German-style verbs whose prefix stands apart ("fing … an"). */
private const val SEPARABLE = "\n\nУчитывай грамматику всего предложения. Если «{word}» — глагол с отделяемой приставкой " +
    "(или часть составного глагола), а приставка стоит отдельно дальше в предложении (нем. «Er fing gestern an» → anfangen, " +
    "«Sie kommt morgen zurück» → zurückkommen), переводи глагол вместе с этой приставкой, а в note укажи инфинитив с приставкой. " +
    "Если «{word}» — сама отделённая приставка, переводи весь глагол так же."

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

    fun load(): Triple<Map<WordRef, String>, Map<String, String>, Map<String, String>> = runCatching {
        val o = JSONObject(file.readText())
        val pins = o.optJSONObject("pins") ?: JSONObject()
        val words = o.optJSONObject("words") ?: JSONObject()
        val phr = o.optJSONObject("phrases") ?: JSONObject()
        Triple(
            pins.keys().asSequence().associate { WordRef.of(it) to pins.getString(it) },
            words.keys().asSequence().associateWith { words.getString(it) },
            phr.keys().asSequence().associateWith { phr.getString(it) },
        )
    }.getOrElse { Triple(emptyMap(), emptyMap(), emptyMap()) }

    fun save(pins: Map<WordRef, String>, words: Map<String, String>, phrases: Map<String, String>) {
        val p = JSONObject(); pins.forEach { (k, v) -> if (v.isNotEmpty() && !v.startsWith("⚠")) p.put(k.key(), v) }
        val w = JSONObject(); words.forEach { (k, v) -> w.put(k, v) }
        val ph = JSONObject(); phrases.forEach { (k, v) -> if (v.isNotEmpty() && !v.startsWith("⚠")) ph.put(k, v) }
        file.writeText(JSONObject().put("pins", p).put("words", w).put("phrases", ph).put("t", System.currentTimeMillis()).toString())
    }
}

/**
 * Machine / AI sentence translations of a lesson kept on the phone (not synced: it would bloat the sync file),
 * sentence text -> translation, so a lesson opened again does not ask for them again.
 */
private class TranslationCache(ctx: Context, lang: String, id: Int, target: String) {
    private val file = File(File(ctx.filesDir, "trcache").apply { mkdirs() }, "${lang}_${id}_$target.json")

    fun load(): Map<String, String> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrElse { emptyMap() }

    fun save(m: Map<String, String>) {
        val all = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }.toString()
        DiskQueue.write { file.writeText(all) }
    }
}

private val LETTERS = Regex("\\p{L}+")
private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

// --- session -------------------------------------------------------------------------------------

class ReaderSession(private val app: AppState, val lang: String, val lesson: Lesson, startSent: Int? = null) {
    /** Sentence to scroll to (bookmark); consumed by the screen. */
    var jump by mutableStateOf(startSent ?: app.progress.pos(lang, lesson.id))

    /** A page / sentence is on screen: remember the place and how far the lesson is read. */
    fun reached(first: Int, last: Int) {
        if (sentences.isEmpty()) return
        val i = sentences.indexOfFirst { it.id == last }
        app.progress.save(lang, lesson.id, first, (i + 1f) / sentences.size)
        val f = sentences.indexOfFirst { it.id == first }
        if (f >= 0 && i >= f) app.stats.read(lang, lesson.id, f, i) { k -> sentences[k].tokens.count { it.isWord } }
    }

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

    // --- tablet: docked word panel; sentence mode translation fold ---

    /** Wide screen (tablet): page-turn buttons and the docked word panel are available. */
    var wide by mutableStateOf(false)
    var docked by mutableStateOf(app.prefs.dockPanel)
    val panelActive get() = wide && docked

    fun setDock(on: Boolean) {
        docked = on
        app.prefs.dockPanel = on
        if (!on) { popup = null; closePhrase() }
    }

    var smTranslation by mutableStateOf(app.prefs.smTranslation)

    fun toggleSmTranslation() { smTranslation = !smTranslation; app.prefs.smTranslation = smTranslation }

    /** A tap: translation above the word; with the docked panel the word also shows there. */
    fun tapWord(ref: WordRef) {
        if (sentenceAtBottom) { seekToSentence(ref.sent); return }     // player: back to the start of this sentence
        tap(ref)
        if (panelActive) openPopup(ref)
    }

    /** Chat about one word / several words in their sentence. */
    fun chatWord(sentId: Int, word: String) {
        val st = sentById[sentId] ?: return
        popup = null
        closePhrase()
        val sys = Prompts.fill(app.prefs.promptChatWord, "lang" to lang, "target" to target, "word" to word, "sentence" to st.text)
        app.go(Screen.Chat(ChatSession(app, "Чат о слове «$word»", sys, "$word\n\n${st.text}", kind = "word")))
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

    /** Where each shown sentence translation row is (per view), to put the long-press menu under it. */
    val trCoords = HashMap<Pair<String, Int>, LayoutCoordinates>()
    /** Sentence whose translation the current long press showed by itself (hidden again if it turns into a drag). */
    private var autoShown: Int? = null

    fun selectStart(ref: WordRef) {
        selSent = ref.sent; selAnchor = ref.token; selFrom = ref.token; selTo = ref.token
        // reader text: the sentence translation appears while the finger is still down
        autoShown = null
        if (menuArea == "text" && !sentenceAtBottom && !allSentences && shownSentences[ref.sent] != true) {
            shownSentences[ref.sent] = true; ensureSentence(ref.sent); autoShown = ref.sent
        }
    }

    fun selectTo(ref: WordRef) {
        if (ref.sent != selSent) return
        selFrom = minOf(selAnchor, ref.token); selTo = maxOf(selAnchor, ref.token)
        if (selTo > selFrom) autoShown?.let { shownSentences.remove(it); autoShown = null }
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
        if (sent == null || words < 2) {
            val id = selSent; val tok = selFrom
            clearSelection()
            // reader text: long press = sentence translation only (shown already while pressing, or toggled);
            // the player keeps its menu at the word
            if (sent != null) when (menuArea) {
                "player" -> wordMenu = WordRef(id, tok)
                "text" -> if (autoShown != id) toggleSentence(id)
            }
            return
        }
        openPhrase(sent.id, selFrom, selTo)
    }

    var phrase by mutableStateOf<Phrase?>(null)

    /** Translations of several words shown above them as one: "sent:from:to" -> text ("" while loading). */
    val phrasePins = mutableStateMapOf<String, String>()

    private fun phraseKey(sent: Int, from: Int, to: Int) = "$sent:$from:$to"

    /** Phrases pinned in sentence [sent]: token ranges and their translation. */
    fun phrasesIn(sent: Int): List<Pair<IntRange, String>> = phrasePins.mapNotNull { (k, v) ->
        val p = k.split(':')
        if (p.size == 3 && p[0].toIntOrNull() == sent) (p[1].toInt()..p[2].toInt()) to v else null
    }

    private fun phraseKeyAt(ref: WordRef): String? = phrasePins.keys.firstOrNull { k ->
        val p = k.split(':')
        p.size == 3 && p[0].toIntOrNull() == ref.sent && ref.token in p[1].toInt()..p[2].toInt()
    }

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
        AppLog.add("reader", "Словосочетание «${text.take(80)}» (предл. $sent)")
        focusSent = sent
        selSent = sent; selFrom = from; selTo = to
        popup = null
        phrase = p
        phraseBest = chosen[text.lowercase()]
        phraseOptions = emptyList(); phraseNote = ""; phraseError = null; phraseLoading = true
        // the translation goes above the words as one; overlapping phrases of this sentence give way
        val key = phraseKey(sent, from, to)
        phrasePins.keys.filter { k ->
            val q = k.split(':'); q.size == 3 && q[0].toIntOrNull() == sent && q[1].toInt() <= to && q[2].toInt() >= from && k != key
        }.forEach { phrasePins.remove(it) }
        phrasePins[key] = phraseBest ?: phrasePins[key].orEmpty()
        app.scope.launch {
            try {
                if (app.useGemini) {
                    val prompt = app.prefs.promptWord + "\n\nВажно: «{word}» — не одно слово, а словосочетание. " +
                        "Переведи его целиком как единое целое по смыслу в этом предложении; best может быть длиннее трёх слов. " +
                        "Если в нём есть глагол, чья отделяемая приставка стоит дальше в предложении, переводи глагол вместе с приставкой."
                    val m = withContext(Dispatchers.IO) { app.geminiFast().word(prompt, lang, target, text, st.text) }
                    if (phrase != p) return@launch
                    phraseBest = chosen[text.lowercase()] ?: m.best
                    phraseOptions = dedupe(listOf(m.best) + m.options)
                    phraseNote = m.note
                    if (phrasePins.containsKey(key)) phrasePins[key] = phraseBest.orEmpty()
                } else {
                    val h = runCatching { hints(text) }.getOrDefault(emptyList())
                    val t = withContext(Dispatchers.IO) { app.api.translate(lang, target, text) }
                    if (phrase != p) return@launch
                    phraseBest = chosen[text.lowercase()] ?: t
                    phraseOptions = dedupe(listOf(t) + h.take(8))
                    if (phrasePins.containsKey(key)) phrasePins[key] = phraseBest.orEmpty()
                }
                persist()
            } catch (e: Throwable) {
                if (phrase == p) phraseError = e.message ?: e.toString()
                if (phrasePins[key].isNullOrEmpty()) phrasePins.remove(key)
                app.geminiFailed(e, fast = true) { if (phrase == p) openPhrase(sent, from, to) }
            }
            if (phrase == p) phraseLoading = false
        }
    }

    fun choosePhrase(meaning: String) {
        val p = phrase ?: return
        val m = meaning.trim().ifEmpty { return }
        chosen[p.text.lowercase()] = m
        phraseBest = m
        phrasePins[phraseKey(p.sent, p.from, p.to)] = m
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
    /** Pages of the text view (for the slider in the top bar) and the page the slider asks for. */
    var pageCount by mutableStateOf(0)
    var pageNow by mutableStateOf(0)
    var goPage by mutableStateOf<Int?>(null)
    /** After sentence mode is collapsed: sentence the text view should show. */
    var syncTo by mutableStateOf<Int?>(null)

    /** Sentence mode slides up over the text, at the selected word's sentence or the first one on screen. */
    fun openSentenceMode() {
        AppLog.add("reader", "Режим по предложениям")
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
    private val trCache by lazy { TranslationCache(app.ctx, lang, lesson.id, target) }
    /** Sentence text -> translation made here (not LingQ's stored one). */
    private val trSaved = HashMap<String, String>()

    /** A sentence translation was made: keep it unless it is LingQ's own one or an error. */
    private fun keepTr(id: Int, t: String) {
        val s = sentById[id] ?: return
        if (t.isBlank() || t.startsWith("⚠") || t == s.translations[target]) return
        trSaved[s.text] = t
        trCache.save(HashMap(trSaved))
    }
    private val hintsCache = HashMap<String, List<String>>()
    private val loadingSentences = HashSet<Int>()
    private val sentById = HashMap<Int, Sent>()

    val target get() = app.trLang

    fun load() {
        error = null
        app.scope.launch {
            try {
                var lvl = ""
                val (t, paras, au) = withContext(Dispatchers.IO) {
                    val l = if (lesson.title.isBlank()) app.api.lesson(lang, lesson.id) else lesson
                    val o = app.api.editorObj(lang, lesson.id)
                    lvl = LINGQ_LEVELS.getOrNull(o.optInt("level") - 1).orEmpty()   // 1..6; the AI may have set it after the import
                    val audio = o.optString("audio").takeIf { !o.isNull("audio") && it.startsWith("http") }
                    Triple(l.title, parse(o.optJSONArray("paragraphs") ?: JSONArray()), audio)
                }
                audioUrl = au
                // player: sentence starts for "replay the sentence" (widget)
                if (sentenceAtBottom && au != null) AudioEngine.setStarts(audioKey, paras.flatten().map { it.start })
                if (autoPlay && au != null) { autoPlay = false; togglePlay() }
                val (p, w, ph) = withContext(Dispatchers.IO) { notes.load() }
                pins.putAll(p); chosen.putAll(w); phrasePins.putAll(ph)
                val saved = withContext(Dispatchers.IO) { trCache.load() }
                trSaved.putAll(saved)
                paras.forEach { para -> para.forEach { st -> saved[st.text]?.let { sentTr[st.id] = it } } }
                title = t
                app.lessonOpened(lang, lesson.copy(title = t, audio = au.orEmpty(), level = lvl.ifBlank { lesson.level }))
                sentences = paras.flatten()
                jump?.let { id -> page = sentences.indexOfFirst { it.id == id }.coerceAtLeast(0) }
                paragraphs = paras
            } catch (e: Throwable) {
                AppLog.error("reader", "Урок ${lesson.id} не открылся", e)
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
                val ts = s.optJSONArray("timestamp")
                val start = if (ts != null && ts.length() >= 2 && !ts.isNull(0)) (ts.optDouble(0, -1.0) * 1000).toLong() else -1L
                val end = if (ts != null && ts.length() >= 2 && !ts.isNull(1)) (ts.optDouble(1, -1.0) * 1000).toLong() else -1L
                val sent = Sent(id, text, tr, s.optInt("index", id + 1), start, end)
                id++
                sentById[sent.id] = sent
                para += sent
            }
            if (para.isNotEmpty()) out += para
        }
        return out
    }

    private fun persist() {
        val p = pins.toMap(); val w = chosen.toMap(); val ph = phrasePins.toMap()
        app.scope.launch(Dispatchers.IO) { runCatching { notes.save(p, w, ph) } }
    }

    fun changeFont(d: Int) {
        font = (font + d).coerceIn(13, 34)
        app.prefs.readerFont = font
    }

    fun clearPins() { pins.clear(); phrasePins.clear(); persist() }

    // --- translations ---

    /** Translation of a sentence: LingQ's stored one if there is one, otherwise machine translation. */
    private suspend fun sentenceTranslation(id: Int): String {
        sentTr[id]?.takeIf { it.isNotEmpty() && !it.startsWith("⚠") }?.let { return it }
        val s = sentById.getValue(id)
        val t = s.translations[target] ?: withContext(Dispatchers.IO) {
            if (app.useGemini) app.geminiFast().sentence(app.prefs.promptSentence, lang, target, s.text)
            else app.api.translate(lang, target, s.text)
        }
        sentTr[id] = t
        keepTr(id, t)
        return t
    }

    fun ensureSentence(id: Int) {
        if (!sentTr[id].isNullOrEmpty() || !loadingSentences.add(id)) return
        app.scope.launch {
            try { sentenceTranslation(id) } catch (e: Throwable) {
                sentTr[id] = "⚠ ${e.message}"
                app.geminiFailed(e, fast = true) { sentTr.remove(id); ensureSentence(id) }
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
                if (t.isNullOrBlank()) app.say("Переводчик не вернул перевод") else { sentTr[id] = t.trim(); keepTr(id, t.trim()) }
            } catch (e: Throwable) {
                app.say(e.message ?: "Ошибка перевода")
                app.geminiFailed(e) { retranslate(id) }
            }
            retranslating.remove(id)
        }
    }

    fun toggleSentence(id: Int) {
        if (sentenceAtBottom) {
            bottomSent = if (bottomSent == id) null else id
            if (bottomSent != null) { focusSent = id; ensureSentence(id) }
            return
        }
        AppLog.add("reader", "Перевод предложения $id")
        focusSent = id
        if (shownSentences[id] == true) shownSentences.remove(id)
        else { shownSentences[id] = true; ensureSentence(id) }
    }

    fun sentenceShown(id: Int) = allSentences || shownSentences[id] == true

    /** Requests still running: a pin and the word window that ask at the same time share one answer. */
    private val hintsPending = HashMap<String, CompletableDeferred<List<String>>>()
    private val aiPending = HashMap<WordRef, CompletableDeferred<WordMeaning>>()

    private suspend fun hints(word: String): List<String> {
        hintsCache[word]?.let { return it }
        hintsPending[word]?.let { return it.await() }
        val d = CompletableDeferred<List<String>>()
        hintsPending[word] = d
        try {
            val h = withContext(Dispatchers.IO) {
                app.api.hints(lang, word, target).ifEmpty {
                    if (word != word.lowercase()) app.api.hints(lang, word.lowercase(), target) else emptyList()
                }
            }
            hintsCache[word] = h
            d.complete(h)
            return h
        } catch (e: Throwable) {
            d.completeExceptionally(e); throw e
        } finally {
            hintsPending.remove(word)
        }
    }

    /** Gemini: meaning of the word in this very sentence (cached). */
    private suspend fun ai(ref: WordRef): WordMeaning {
        aiCache[ref]?.let { return it }
        aiPending[ref]?.let { return it.await() }
        val d = CompletableDeferred<WordMeaning>()
        aiPending[ref] = d
        try {
            val s = sentById.getValue(ref.sent)
            val m = withContext(Dispatchers.IO) { app.geminiFast().word(app.prefs.promptWord + SEPARABLE, lang, target, word(ref), s.text) }
            aiCache[ref] = m
            d.complete(m)
            return m
        } catch (e: Throwable) {
            d.completeExceptionally(e); throw e
        } finally {
            aiPending.remove(ref)
        }
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
        val trWords = LETTERS.findAll(tr).map { it.value }.toList()
        fun fits(h: String) = LETTERS.findAll(h.lowercase()).any { m ->
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
        return pickByTranslation(hs, tr)
            ?: hs.firstOrNull()
            ?: withContext(Dispatchers.IO) { app.api.translate(lang, target, word) }
    }

    /** One tap: translation pinned above the word (tap again removes it). */
    fun tap(ref: WordRef) {
        AppLog.add("reader", "Нажатие «${word(ref)}» (предл. ${ref.sent})")
        focusSent = ref.sent
        phraseKeyAt(ref)?.let { phrasePins.remove(it); persist(); return }
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
                .onFailure { e -> app.geminiFailed(e, fast = true) { pins.remove(ref); provisional.remove(ref); tap(ref) } }.getOrNull()
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
        AppLog.add("reader", "Окно слова «${word(ref)}» (предл. ${ref.sent})")
        run {
            if (phrase != null) closePhrase()
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
                            .onFailure { e -> app.geminiFailed(e, fast = true) { if (popup == ref) openPopup(ref) } }.getOrNull()
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
        if (!panelActive) popup = null
        persist()
    }

    fun unpin(ref: WordRef) {
        pins.remove(ref)
        chosen.remove(word(ref).lowercase())
        if (!panelActive) popup = null
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

    private fun norm(t: String) = t.lowercase().replace('ё', 'е').replace(NON_WORD, " ").trim()

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

    // --- lesson audio ---

    /** MP3 of the lesson on LingQ (null = the lesson has no audio). */
    var audioUrl by mutableStateOf<String?>(null)
    val audioKey get() = "${lang}_${lesson.id}"
    /** Sentence sounding now (highlighted). */
    var playingSent by mutableStateOf<Int?>(null)
    /** Sentences have times in the audio. */
    val hasTimes get() = sentences.any { it.start >= 0 }

    /** Play from here (audio bookmark) instead of where listening stopped; -1 = no. */
    var startAt = -1L

    private fun ensureAudio(): Boolean {
        val url = audioUrl ?: return false
        val at = startAt
        startAt = -1
        if (AudioEngine.key != audioKey) AudioEngine.load(
            app.ctx, audioKey, url, title, if (at >= 0) at else AudioEngine.savedPos(app.ctx, audioKey), lesson.imageUrl,
        )
        else if (at >= 0) AudioEngine.seekTo(at)
        return true
    }

    /** Audio bookmark at the place sounding now (its sentence start). */
    fun markAudio() {
        if (!ensureAudio()) return
        val pos = AudioEngine.position
        val id = sentenceAt(pos)
        val st = id?.let { sentById[it] }
        app.bookmarks.addAudio(lang, Lesson(lesson.id, title), id ?: -1, st?.text.orEmpty(), st?.start?.takeIf { it >= 0 } ?: pos)
        AppLog.add("audio", "Аудиозакладка ${fmtTime(pos)}")
        app.say("Закладка ${fmtTime(st?.start?.takeIf { it >= 0 } ?: pos)}")
    }

    fun audioMarks() = app.bookmarks.audioFor(lesson.id)

    fun removeMark(b: Bookmark) = app.bookmarks.remove(b)

    fun togglePlay() {
        if (AudioEngine.key != audioKey) { if (ensureAudio()) AudioEngine.play() } else AudioEngine.toggle()
    }

    /** "▶" at a sentence: listen from its beginning on. */
    fun playFrom(id: Int) {
        val st = sentById[id] ?: return
        if (st.start < 0 || !ensureAudio()) return
        AppLog.add("audio", "Слушать с предложения $id")
        AudioEngine.clearLoop()
        AudioEngine.seekTo(st.start); AudioEngine.play()
    }

    /** Sentence sounding at [ms]: the last one that starts before it. */
    fun sentenceAt(ms: Long): Int? {
        var best: Sent? = null
        for (x in sentences) if (x.start in 0..ms && (best == null || x.start >= best.start)) best = x
        return best?.id
    }

    fun sentenceStartAt(ms: Long): Long = sentenceAt(ms)?.let { sentById[it]?.start } ?: ms

    private fun currentForLoop(): Sent? =
        (if (sentenceMode) sentences.getOrNull(page) else null)
            ?: sentById[sentenceAt(AudioEngine.position) ?: -1] ?: focusSent?.let { sentById[it] }

    fun loopSentence() {
        val st = currentForLoop() ?: return
        if (st.start < 0 || !ensureAudio()) return
        AudioEngine.setLoop(st.start - 100, loopEnd(st))
    }

    /** End of a sentence for a loop: its end time + a little, else the next sentence's start, else 5 s. */
    private fun loopEnd(st: Sent): Long {
        if (st.end > st.start) return st.end + 150
        val next = sentences.firstOrNull { it.start > st.start }?.start ?: -1L
        return if (next > 0) next else st.start + 5000
    }

    fun setLoopA() { AudioEngine.setA(sentenceStartAt(AudioEngine.position) - 100) }

    fun setLoopB() {
        val st = sentById[sentenceAt(AudioEngine.position) ?: -1]
        AudioEngine.setB(if (st != null && st.end > st.start) st.end + 150 else AudioEngine.position)
    }

    fun prevSentence() {
        val pos = AudioEngine.position
        val cur = sentById[sentenceAt(pos) ?: -1]
        // more than 1.5 s into the sentence: back to its start, else to the one before
        val target = if (cur != null && pos - cur.start > 1500) cur.start
        else sentences.filter { it.start in 0 until (cur?.start ?: pos) }.maxByOrNull { it.start }?.start ?: 0L
        AudioEngine.seekTo(target)
    }

    fun nextSentence() {
        val pos = AudioEngine.position
        sentences.filter { it.start > pos + 50 }.minByOrNull { it.start }?.let { AudioEngine.seekTo(it.start) }
    }

    /** Menu of a long-pressed word, and the view it was pressed in ("text", "sent", "player"). */
    var wordMenu by mutableStateOf<WordRef?>(null)
    var menuArea = "text"

    /** Reader: listen from this sentence in the player tab. */
    fun listenFrom(id: Int) {
        val st = sentById[id] ?: return
        if (st.start < 0) return
        app.listenAt(lang, Lesson(lesson.id, title, lesson.imageUrl, courseTitle = lesson.courseTitle), st.start)
    }

    fun hasTime(id: Int) = audioUrl != null && (sentById[id]?.start ?: -1L) >= 0

    /** Player tab: play from the start of sentence [id]. */
    fun seekToSentence(id: Int) {
        val st = sentById[id] ?: return
        if (st.start < 0 || !ensureAudio()) return
        AppLog.add("audio", "К началу предложения $id")
        AudioEngine.seekTo(st.start)
    }

    fun loopStartAt(id: Int) {
        val st = sentById[id] ?: return
        if (st.start < 0 || !ensureAudio()) return
        AudioEngine.setA(st.start - 100)
        app.say(if (AudioEngine.looping) "Повтор ${fmtTime(AudioEngine.loopA)} – ${fmtTime(AudioEngine.loopB)}"
            else "Начало повтора ${fmtTime(AudioEngine.loopA)} — теперь выберите конец")
    }

    fun loopEndAt(id: Int) {
        val st = sentById[id] ?: return
        if (st.start < 0 || !ensureAudio()) return
        AudioEngine.setB(loopEnd(st))
        app.say(if (AudioEngine.looping) "Повтор ${fmtTime(AudioEngine.loopA)} – ${fmtTime(AudioEngine.loopB)}"
            else "Конец повтора задан — выберите начало")
    }

    /** Player tab: the audio is at sentence [id] (shown bold). */
    fun trackAudio(id: Int?) { if (id != playingSent) playingSent = id }

    val appCtx get() = app.ctx

    /** Player tab: a long press shows the sentence translation at the bottom, not under the sentence. */
    var sentenceAtBottom = false
    var bottomSent by mutableStateOf<Int?>(null)
    /** Start playing as soon as the lesson (and its audio) is loaded. */
    var autoPlay = false

    // --- editing a sentence (saved to the lesson on LingQ) ---

    /** Sentence whose edit window is open. */
    var editing by mutableStateOf<Int?>(null)

    // --- level of the lesson by the AI ---

    var levelBusy by mutableStateOf(false)
    /** Result for the dialog: level 1..6 and the reason. */
    var levelResult by mutableStateOf<Pair<Int, String>?>(null)

    fun detectLevel() {
        if (levelBusy) return
        if (!app.useGemini) { app.say("Нужен Gemini или другой ИИ: Настройки → Перевод"); return }
        val text = sentences.joinToString(" ") { it.text }
        if (text.isBlank()) return
        levelBusy = true
        app.say("ИИ оценивает уровень…")
        app.scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    val r = app.geminiFast().level(app.prefs.promptLevel, lang, text)
                    app.api.setLevel(lang, lesson.id, r.first)
                    r
                }
                AppLog.add("reader", "Уровень урока ${lesson.id}: ${levelName(r.first)}")
                levelResult = r
            } catch (e: Throwable) {
                AppLog.error("reader", "Уровень урока ${lesson.id}", e)
                app.say(e.message ?: "Не удалось определить уровень")
                app.geminiFailed(e, fast = true) { detectLevel() }
            }
            levelBusy = false
        }
    }
    var editSaving by mutableStateOf(false)
    var editError by mutableStateOf<String?>(null)

    fun startEdit(id: Int) { editError = null; editSaving = false; editing = id }

    /** Current translation shown for the sentence (for the edit window). */
    fun shownTranslation(id: Int): String =
        sentTr[id]?.takeIf { it.isNotEmpty() && !it.startsWith("⚠") } ?: sentById[id]?.translations?.get(target).orEmpty()

    /**
     * Writes the new text (and translation) of sentence [id] into the lesson on LingQ: the lesson is read again,
     * a backup is kept on the phone, only this sentence is changed, the structure is saved as is.
     */
    fun saveEdit(id: Int, text: String, translation: String) {
        val s = sentById[id] ?: return
        val newText = text.trim().replace(Regex("\\s+"), " ")
        val newTr = translation.trim()
        if (newText.isEmpty()) { editError = "Предложение не может быть пустым"; return }
        if (newText == s.text && newTr == shownTranslation(id)) { editing = null; return }
        editSaving = true; editError = null
        AppLog.add("reader", "Правка предложения $id")
        app.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val paras = app.api.editor(lang, lesson.id)
                    val js = findSentence(paras, s) ?: throw ApiException("Предложение не найдено в уроке на LingQ — обновите урок")
                    app.backups.save(lang, Lesson(lesson.id, title), JSONArray(paras.toString()))
                    if (newText != s.text) {
                        js.put("text", newText)
                        js.put("cleanText", newText)
                    }
                    if (newTr.isNotEmpty() && newTr != s.translations[target]) {
                        val ta = js.optJSONArray("translations") ?: JSONArray().also { js.put("translations", it) }
                        val own = (0 until ta.length()).map { ta.getJSONObject(it) }
                            .firstOrNull { it.optString("language") == target }
                        if (own != null) own.put("text", newTr)
                        else ta.put(JSONObject().put("language", target).put("text", newTr))
                    }
                    app.api.saveParagraphs(lang, lesson.id, paras)
                }
                replaceSentence(s, newText, newTr)
                editing = null
                app.say("Предложение сохранено")
            } catch (e: Throwable) {
                AppLog.error("reader", "Правка предложения $id", e)
                editError = e.message ?: e.toString()
            }
            editSaving = false
        }
    }

    /** The sentence in LingQ's paragraphs: by its index, else by the same order as [parse] (non-empty sentences). */
    private fun findSentence(paras: JSONArray, s: Sent): JSONObject? {
        val all = mutableListOf<JSONObject>()
        for (i in 0 until paras.length()) {
            val ss = paras.getJSONObject(i).optJSONArray("sentences") ?: continue
            for (j in 0 until ss.length()) all += ss.getJSONObject(j)
        }
        all.firstOrNull { it.optInt("index", -1) == s.lingqIndex && it.optString("text").trim() == s.text }?.let { return it }
        val nonEmpty = all.filter { it.optString("text").trim().isNotEmpty() }
        return nonEmpty.getOrNull(s.id)?.takeIf { it.optString("text").trim() == s.text }
            ?: all.firstOrNull { it.optInt("index", -1) == s.lingqIndex }
    }

    /** The edited sentence replaces the old one on screen; its word pins are dropped (the words moved). */
    private fun replaceSentence(old: Sent, text: String, tr: String) {
        val trs = HashMap(old.translations).apply { if (tr.isNotEmpty()) put(target, tr) }
        val n = Sent(old.id, text, trs, old.lingqIndex, old.start, old.end)
        sentById[n.id] = n
        paragraphs = paragraphs?.map { p -> p.map { if (it.id == n.id) n else it } }
        sentences = sentences.map { if (it.id == n.id) n else it }
        if (tr.isNotEmpty()) sentTr[n.id] = tr else sentTr.remove(n.id)
        if (text != old.text) {
            pins.keys.filter { it.sent == n.id }.forEach { pins.remove(it) }
            phrasePins.keys.filter { it.startsWith("${n.id}:") }.forEach { phrasePins.remove(it) }
            aiCache.keys.filter { it.sent == n.id }.forEach { aiCache.remove(it) }
            persist()
        }
    }
}

/** Window to correct a sentence and its translation; saving writes them into the lesson on LingQ. */
@Composable
fun SentenceEditDialog(s: ReaderSession) {
    val id = s.editing ?: return
    val sent = s.sentences.firstOrNull { it.id == id } ?: return
    var text by remember(id) { mutableStateOf(sent.text) }
    var tr by remember(id) { mutableStateOf(s.shownTranslation(id)) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!s.editSaving) s.editing = null },
        icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        title = { Text("Редактировать предложение") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Предложение") },
                    minLines = 2, enabled = !s.editSaving,
                )
                OutlinedTextField(
                    tr, { tr = it }, Modifier.fillMaxWidth(), label = { Text("Перевод") },
                    minLines = 2, enabled = !s.editSaving,
                )
                Text(
                    "Изменения сохранятся в уроке на LingQ (копия урока до правки — в «Резервных копиях»). " +
                        "Переводы слов этого предложения сбросятся, если изменить текст.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                s.editError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { s.saveEdit(id, text, tr) }, enabled = !s.editSaving && text.isNotBlank()) {
                if (s.editSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = { s.editing = null }, enabled = !s.editSaving) { Text("Отмена") } },
    )
}

// --- UI ------------------------------------------------------------------------------------------



/** Top bar: font size and extras. */
@Composable
fun ReaderActions(state: AppState, s: ReaderSession) {
    var menu by remember { mutableStateOf(false) }
    var marks by remember { mutableStateOf(false) }
    IconButton(onClick = { marks = true }) { Icon(AppIcons.Bookmark, contentDescription = "Закладки урока") }
    if (marks) LessonBookmarksDialog(state, s) { marks = false }
    var help by remember { mutableStateOf(false) }
    if (help) ReaderHelp { help = false }
    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        MenuGroup("Урок")
        if (s.audioUrl != null) DropdownMenuItem(
            text = { Text("Слушать в плеере") },
            leadingIcon = { Icon(AppIcons.Headphones, contentDescription = null) },
            onClick = { menu = false; state.playLesson(s.lang, Lesson(s.lesson.id, s.title, s.lesson.imageUrl, courseTitle = s.lesson.courseTitle)) },
        )
        DropdownMenuItem(
            text = { Text(if (s.levelBusy) "Определяю уровень…" else "Определить уровень (ИИ)") },
            leadingIcon = { Icon(AppIcons.BarChart, contentDescription = null) },
            enabled = !s.levelBusy,
            onClick = { menu = false; s.detectLevel() },
        )
        HorizontalDivider()
        MenuGroup("Вид")
        DropdownMenuItem(text = { Text("Шрифт крупнее  A+") }, onClick = { s.changeFont(+2) })
        DropdownMenuItem(text = { Text("Шрифт мельче  A−") }, onClick = { s.changeFont(-2) })
        DropdownMenuItem(
            text = { Text("Оформление и экран") },
            onClick = { menu = false; state.go(Screen.SettingsReading) },
        )
        if (s.pins.isNotEmpty() || s.phrasePins.isNotEmpty()) DropdownMenuItem(
            text = { Text("Снять переводы над словами (${s.pins.size + s.phrasePins.size})") },
            onClick = { s.clearPins(); menu = false },
        )
        HorizontalDivider()
        MenuGroup("Помощь")
        DropdownMenuItem(
            text = { Text("Как пользоваться") },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = { menu = false; help = true },
        )
    }
}

/** Small grey heading inside a menu. */
@Composable
private fun MenuGroup(t: String) = Text(
    t, Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp),
    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
)

/** Reader help: gestures and buttons (menu ⋮ → "Как пользоваться"). */
@Composable
private fun ReaderHelp(onClose: () -> Unit) {
    val items = listOf(
        "Слова" to listOf(
            "Касание слова — перевод над словом (касание ещё раз — убрать).",
            "Двойное касание — окно слова: варианты значений, свой вариант, «Спросить» (чат об этом слове), закладка.",
            "Выбранное значение запоминается для этого урока и ставится над словом везде в тексте.",
            "Долгое нажатие и, не отпуская, провести по соседним словам — перевод нескольких слов вместе.",
        ),
        "Предложения" to listOf(
            "Долгое нажатие на слово — перевод предложения (ещё раз — скрыть). У перевода — чат о предложении.",
            "В режиме «По предложениям» ⋮ — исправить предложение (сохраняется в уроке на LingQ), слушать отсюда " +
                "(если у урока есть аудио), размер текста; значок закладки — в закладки.",
            "У перевода предложения — ↻ «перевести заново»; касание перевода скрывает его.",
            "Внизу «Переводы» — показать переводы всех предложений сразу.",
        ),
        "Страницы и режимы" to listOf(
            "Свайп влево / вправо — следующая / предыдущая страница. Если страница выросла от переводов — её можно прокрутить.",
            "Ползунок вверху — быстро перейти к любой странице урока.",
            "«По предложениям» — по одному предложению на экране, свайп — следующее; стрелка ⌄ слева сворачивает обратно к тексту.",
            "«Скрыть / Показать перевод» под предложением — сворачивает перевод для всех предложений.",
            "Урок открывается там, где вы остановились; прочитанный процент виден в списке уроков.",
        ),
        "Аудио — во вкладке «Плеер»" to listOf(
            "Плеер — вкладка внизу главного экрана (рядом с «Библиотекой»). Урок с аудио: ⋮ → «Слушать в плеере».",
            "В плеере окно с текстом: звучащее предложение жирным. Нажатие на окно — весь текст, он прокручивается вслед за звуком.",
            "В тексте плеера: нажатие на слово — звук с начала этого предложения; долгое нажатие — меню у слова: перевод слова, " +
                "перевод предложения, начало / конец повтора. Протянуть по словам — перевод словосочетания.",
            "Значок закладки вверху плеера — аудиозакладка на звучащее место; список — ⋮ → «Аудиозакладки» и общий экран закладок.",
            "⏮ ⏭ — к предыдущему / следующему предложению, ↺ ↻ — на 5 секунд, скорость — слева.",
            "🔁 — повтор участка: это предложение, последние или следующие 1 / 2 / 3 / 5 минут, свой участок A–B, пауза между повторами.",
            "Играет и в фоне: управление в шторке, на экране блокировки и кнопками наушников.",
        ),
        "Меню и кнопки" to listOf(
            "⋮ → «Определить уровень (ИИ)» — ИИ оценивает текст (A1–C2) и записывает уровень урока в LingQ.",
            "Закладка вверху — закладки этого урока; все закладки — в библиотеке.",
            "«Чат» внизу — разговор с ИИ о всём тексте.",
            "⋮ → «Оформление и экран»: фон страницы, цвет выделения слов, сколько экран не гаснет.",
            "⋮ → шрифт крупнее / мельче, снять все переводы над словами.",
        ),
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Как пользоваться") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { (head, lines) ->
                    Text(head, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    lines.forEach { l ->
                        Row {
                            Text("•  ", style = MaterialTheme.typography.bodyMedium)
                            Text(l, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Понятно") } },
    )
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

/** Page slider in the reader's top bar: dragging it turns the pages. */
@Composable
private fun PageSlider(s: ReaderSession) {
    val n = s.pageCount
    if (n < 2) { ProgressPill(s.textProgress); return }
    androidx.compose.material3.Slider(
        value = s.pageNow.toFloat(),
        onValueChange = { v -> val i = Math.round(v).coerceIn(0, n - 1); if (i != s.pageNow) { s.pageNow = i; s.goPage = i } },
        valueRange = 0f..(n - 1).toFloat(),
        modifier = Modifier.width(170.dp).height(24.dp),
    )
}

@Composable
fun ReaderProgress(s: ReaderSession) {
    if (s.paragraphs != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PageSlider(s)
        if (s.pageLabel.isNotEmpty()) Text(
            s.pageLabel, Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
fun ReaderScreen(s: ReaderSession) {
    LaunchedEffect(s) { if (s.paragraphs == null) s.load() }
    SentenceEditDialog(s)
    s.levelResult?.let { (lvl, why) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { s.levelResult = null },
            title = { Text("Уровень: ${levelName(lvl)}") },
            text = { Text((if (why.isNotBlank()) "$why\n\n" else "") + "Сохранено в уроке на LingQ.") },
            confirmButton = { TextButton(onClick = { s.levelResult = null }) { Text("OK") } },
        )
    }
    val wide = LocalConfiguration.current.screenWidthDp > 600
    SideEffect { s.wide = wide }
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
        else -> Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { PagedText(s, paras) }
            if (s.panelActive) {
                VerticalDivider()
                DockedPanel(s, Modifier.width(PANEL_WIDTH).fillMaxHeight())
            }
        }
    }
    if (!s.sentenceMode) WordMenuLayer(s, "text") { ref, close -> ReaderMenuItems(s, ref, close) }
    if (!s.panelActive) {
        s.popup?.let { ref -> WordSheet(s, ref) }
        s.phrase?.let { p -> PhraseSheet(s, p) }
    }
}

/** Part of a paragraph that lies on one page. */
private class Block(val sents: List<Sent>)

/**
 * Heights of text pieces as [Paragraph] / [SentenceFlow] will draw them, computed from text sizes only
 * (composing the whole lesson just to measure it runs out of memory on long lessons).
 * Keep in step with those composables. Not thread-safe: used under one lock.
 */
private class TextMetrics(
    private val m: TextMeasurer, private val d: Density, font: Int, private val width: Int, textStyle: TextStyle,
    /** Text widths, kept between rebuilds while the font is the same. */
    private val widths: HashMap<String, Int>,
) {
    private val size = font.toFloat()
    private val text = textStyle.copy(fontSize = size.sp, lineHeight = (size * 1.35).sp)
    private fun px(dp: Float) = with(d) { dp.dp.roundToPx() }
    private val lineH = m.measure("Ag", text, maxLines = 1).size.height
    private val chunkGap = px(size * 0.3f)
    private val lineGap = px(2f)

    private fun w(t: String) = widths.getOrPut(t) { m.measure(t, text, maxLines = 1, softWrap = false).size.width }

    /**
     * FlowRow of sentences (chunks wrap to the next line, lines 2 dp apart) in one pass:
     * result[i] = height of sents[0..i].
     */
    fun bottoms(sents: List<Sent>): IntArray {
        val out = IntArray(sents.size)
        var total = 0
        var lineW = 0
        var lineMax = 0
        var lines = 0
        sents.forEachIndexed { i, sent ->
            for (c in sent.chunks) {
                val cw = w(c.joinToString("") { it.text }) + chunkGap
                if (lineW > 0 && lineW + cw > width) { total += lineMax; lines++; lineW = 0; lineMax = 0 }
                lineW += cw; lineMax = maxOf(lineMax, lineH)
            }
            val open = if (lineW > 0) 1 else 0
            out[i] = total + (if (open == 1) lineMax else 0) + lineGap * maxOf(0, lines + open - 1)
        }
        return out
    }
}

/** Pages from the bare text: a paragraph that does not fit is split at a sentence boundary. */
private suspend fun paginate(paras: List<List<Sent>>, metrics: TextMetrics, h: Int, gap: Int): List<List<Block>> {
    val pages = mutableListOf<List<Block>>()
    var cur = mutableListOf<Block>()
    var used = 0
    fun need(x: Int) = (if (cur.isEmpty()) 0 else gap) + x
    fun flush() {
        if (cur.isNotEmpty()) pages += cur
        cur = mutableListOf(); used = 0
    }
    for (para in paras) {
        kotlinx.coroutines.yield()          // a newer layout (font, width) cancels this one
        var from = 0
        while (from < para.size) {
            // heights of para[from..k] for every k, in one pass
            val hs = metrics.bottoms(para.subList(from, para.size))
            val hRest = hs.last()
            if (used + need(hRest) <= h) { used += need(hRest); cur += Block(para.subList(from, para.size)); break }
            // the longest run of sentences that still fits on this page
            var k = from
            var hk = 0
            while (k + 1 < para.size) {
                val hh = hs[k - from]
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
    return pages
}

/**
 * The text split into pages that fit the screen, turned with a swipe like a book. Pages are laid out in the
 * background from the text, font and width only (pins, translations, marks make a page scroll instead).
 */
@Composable
private fun PagedText(s: ReaderSession, paras: List<List<Sent>>) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.bodyLarge
    val widths = remember(s.font, density) { HashMap<String, Int>() }
    val lock = remember { Mutex() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padH = with(density) { sidePad(s).roundToPx() }
        val padV = with(density) { 12.dp.roundToPx() }
        val gap = with(density) { 14.dp.roundToPx() }
        val w = (constraints.maxWidth - 2 * padH).coerceAtLeast(1)
        val h = if (constraints.hasBoundedHeight) ((constraints.maxHeight - 2 * padV) * 0.99f).toInt() else Int.MAX_VALUE
        val font = s.font
        // the old pages stay on screen until the new ones are ready
        val pages by produceState<List<List<Block>>?>(null, paras, font, w, h, gap, density) {
            value = withContext(Dispatchers.Default) {
                lock.withLock { paginate(paras, TextMetrics(measurer, density, font, w, textStyle, widths), h, gap) }
            }
        }
        pages?.let { TextPager(s, it) }
    }
}

@Composable
private fun TextPager(s: ReaderSession, pages: List<List<Block>>) {
    val pager = rememberPagerState { pages.size }
    val latest by rememberUpdatedState(pages)
    val sig = pages.map { p -> p.first().sents.first().id }   // changes only when the split changes
    fun pageOf(id: Int) = latest.indexOfFirst { p -> p.any { b -> b.sents.any { it.id == id } } }

    // pages these settle events belong to: until the rebuilt pages are put on the anchor's page, a settle
    // (page index clamped by the new page count) is not a page turn and must not move the anchor
    val placed = remember { arrayOfNulls<Any>(1) }
    // same text stays on screen when pages are rebuilt
    LaunchedEffect(sig) {
        val i = s.anchor?.let { pageOf(it) } ?: -1
        if (i >= 0 && i != pager.currentPage) pager.scrollToPage(i)
        placed[0] = latest
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { i ->
            if (placed[0] !== latest) return@collect
            latest.getOrNull(i)?.let { p ->
                // the anchor (sentence reached) stays while it is on this page, else it becomes the page's first one
                if (s.anchor == null || p.none { b -> b.sents.any { it.id == s.anchor } }) s.anchor = p.first().sents.first().id
                if (s.jump == null) s.reached(p.first().sents.first().id, p.last().sents.last().id)
            }
        }
    }
    // page slider in the top bar
    LaunchedEffect(s.goPage) {
        val i = s.goPage ?: return@LaunchedEffect
        if (i in latest.indices && i != pager.currentPage) pager.scrollToPage(i)
        s.goPage = null
    }
    LaunchedEffect(pager.currentPage, sig) {
        val p = latest.getOrNull(pager.currentPage) ?: return@LaunchedEffect
        s.visibleSents = p.flatMap { b -> b.sents.map { it.id } }.toSet()
        s.textProgress = (pager.currentPage + 1f) / latest.size
        s.pageLabel = "${pager.currentPage + 1} / ${latest.size}"
        s.pageCount = latest.size
        s.pageNow = pager.currentPage
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
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = sidePad(s), vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                latest.getOrNull(i)?.forEach { b -> Paragraph(s, b.sents) }
            }
        }
        if (s.wide) TurnButtons(pager)
    }
}

private val PANEL_WIDTH = 340.dp

/** Text side padding: room for the page-turn buttons on a tablet. */
private fun sidePad(s: ReaderSession): Dp = if (s.wide) 64.dp else 16.dp

/** Round translucent ‹ › buttons at the sides (tablet); hidden on the first / last page. */
@Composable
private fun BoxScope.TurnButtons(pager: PagerState) {
    val scope = rememberCoroutineScope()
    if (pager.currentPage > 0) TurnButton(
        Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Предыдущая страница",
        Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
    ) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
    if (pager.currentPage < pager.pageCount - 1) TurnButton(
        Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующая страница",
        Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
    ) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
}

@Composable
private fun TurnButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, modifier: Modifier, onClick: () -> Unit) {
    // no background: a faint arrow only (a ripple while pressed)
    IconButton(onClick = onClick, modifier = modifier.size(44.dp)) {
        Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
    }
}

/** Tablet: the word / phrase window docked at the right; ✕ undocks it. */
@Composable
private fun DockedPanel(s: ReaderSession, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.phrase != null) "Фраза" else "Слово", Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = { s.setDock(false) }) { Icon(Icons.Filled.Close, contentDescription = "Открепить панель") }
        }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            val p = s.phrase
            val ref = s.popup
            when {
                p != null -> PhraseContent(s, p)
                ref != null -> WordContent(s, ref)
                else -> Text(
                    "Нажмите на слово — здесь появятся его значения. Зажмите слово и проведите по соседним — " +
                        "здесь будет перевод этих слов вместе.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Pin button of the word / phrase window: dock it at the right (tablet only). */
@Composable
private fun DockButton(s: ReaderSession) {
    if (s.wide && !s.docked) IconButton(onClick = { s.setDock(true) }) {
        Icon(AppIcons.PushPin, contentDescription = "Закрепить справа")
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
internal fun SentenceFlow(s: ReaderSession, sents: List<Sent>, scale: Float = 1f, area: String = "text", center: Boolean = false) {
    val size = s.font * scale
    val haptic = LocalHapticFeedback.current
    val selBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val playColor = MaterialTheme.colorScheme.primary
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp, lineHeight = (size * 1.35).sp)
    val trStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (size * 0.6).sp, lineHeight = (size * 0.68).sp)
    FlowRow(
        horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (sent in sents) {
            if (s.isBookmarked(sent.id)) Icon(
                AppIcons.Bookmark, contentDescription = "Закладка", tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Bottom).padding(end = 2.dp, bottom = 4.dp).size((size * 0.9).dp),
            )
            val phr = s.phrasesIn(sent.id)
            val sounding = s.playingSent == sent.id && AudioEngine.key == s.audioKey
            val ts = if (sounding) textStyle.copy(fontWeight = FontWeight.Bold) else textStyle
            // a chunk belongs to a phrase by its words, or by a dash standing strictly inside it ("Nürnberg – Wiege")
            fun phraseOf(c: List<Token>) = phr.firstOrNull { (r, _) ->
                c.any { if (it.isWord) it.index in r else it.index > r.first && it.index < r.last }
            }
            var i = 0
            val chunks = sent.chunks
            while (i < chunks.size) {
                val ph = phraseOf(chunks[i])
                if (ph == null) {
                    ChunkRow(s, sent, chunks[i], area, ts, trStyle, selBg, haptic, null,
                        Modifier.align(Alignment.Bottom).padding(end = (size * 0.3).dp))
                    i++
                    continue
                }
                // several words translated together: one translation above all of them
                var j = i
                while (j < chunks.size && phraseOf(chunks[j]) == ph) j++
                val group = chunks.subList(i, j)
                Column(
                    Modifier.align(Alignment.Bottom).padding(end = (size * 0.3).dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        ph.second.ifEmpty { "…" }, style = trStyle, maxLines = 3, textAlign = TextAlign.Center,
                        color = LocalHighlight.current.text, modifier = Modifier.padding(horizontal = 1.dp),
                    )
                    FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        group.forEachIndexed { n, c ->
                            ChunkRow(s, sent, c, area, ts, trStyle, selBg, haptic, ph.first,
                                if (n < group.size - 1) Modifier.padding(end = (size * 0.3).dp) else Modifier)
                        }
                    }
                }
                i = j
            }
        }
    }
}

/** One chunk of a sentence (a word with its punctuation); [phrase] = token range of a pinned phrase it belongs to. */
@Composable
private fun ChunkRow(
    s: ReaderSession, sent: Sent, chunk: List<Token>, area: String,
    textStyle: TextStyle, trStyle: TextStyle, selBg: Color, haptic: HapticFeedback, phrase: IntRange?, modifier: Modifier,
) {
    // bottom-aligned: a translation above one word pushes the whole line down
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        for (t in chunk) {
            val ref = WordRef(sent.id, t.index)
            val inPhrase = phrase != null && t.index in phrase
            val tr = if (t.isWord && !inPhrase) s.pins[ref] else null
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (tr != null) Text(
                    tr.ifEmpty { "…" }, style = trStyle, maxLines = 2,
                    color = if (s.provisional[ref] == true) MaterialTheme.colorScheme.outline else LocalHighlight.current.text,
                    modifier = Modifier.padding(horizontal = 1.dp),
                )
                val pinned = tr != null || inPhrase
                val hl = LocalHighlight.current.bg
                if (t.isWord) DisposableEffect(area, ref) { onDispose { s.wordCoords.remove(area to ref) } }
                // the highlight is drawn: dragging a selection or opening the word window only redraws
                val mark = Modifier.drawBehind {
                    val c = when {
                        s.inSelection(ref) -> selBg
                        t.isWord && (pinned || s.popup == ref) -> hl
                        !t.isWord && phrase != null && t.index > phrase.first && t.index < phrase.last -> hl
                        else -> null
                    }
                    if (c != null) {
                        if (t.isWord) drawRoundRect(c, cornerRadius = CornerRadius(4.dp.toPx())) else drawRect(c)
                    }
                }
                Text(
                    t.text, style = textStyle,
                    modifier = if (!t.isWord) mark else mark
                        .onPlaced { s.wordCoords[area to ref] = it }
                        .pointerInput(ref, area) { wordGestures(s, area, ref, haptic) },
                )
            }
        }
    }
}

@Composable
private fun SentenceTranslation(s: ReaderSession, id: Int, scale: Float = 1f, center: Boolean = false, area: String = "text") {
    LaunchedEffect(id) { s.ensureSentence(id) }
    val tr = s.sentTr[id]
    Row(
        Modifier.fillMaxWidth()
            .onPlaced { s.trCoords[area to id] = it }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable { if (!s.allSentences) s.toggleSentence(id) }
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TranslationText(
            tr, s.sentences.firstOrNull { it.id == id }?.text.orEmpty(),
            MaterialTheme.typography.bodyMedium.copy(fontSize = (s.font * 0.8f * scale).sp, fontStyle = FontStyle.Italic),
            MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f),
            busy = s.retranslating[id] == true, align = if (center) TextAlign.Center else TextAlign.Start,
        )
        IconButton(onClick = { s.chatSentence(id) }, modifier = Modifier.size(36.dp)) {
            Icon(AppIcons.Chat, contentDescription = "Чат о предложении", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
        RetranslateButton(s, id)
    }
}

/**
 * Layer over a view ([area]) that shows the long-press menu right under the pressed word.
 * [items] gets the word and "close".
 */
@Composable
internal fun WordMenuLayer(s: ReaderSession, area: String, items: @Composable (WordRef, () -> Unit) -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val ref = s.wordMenu
        if (ref != null && s.menuArea == area) {
            val r = s.wordCoords[area to ref]?.takeIf { it.isAttached }?.boundsInRoot()
            // the sentence translation shown by the long press: the menu goes under it
            val t = s.trCoords[area to ref.sent]?.takeIf { it.isAttached }?.boundsInRoot()
            val bottom = if (r != null && t != null && t.top >= r.top && t.top - r.bottom < r.height * 12) t.bottom else r?.bottom
            val at = if (r != null && bottom != null) IntOffset((r.left - origin.x).toInt(), (bottom - origin.y).toInt()) else IntOffset.Zero
            Box(Modifier.offset { at }) {
                DropdownMenu(expanded = true, onDismissRequest = { s.wordMenu = null }) {
                    items(ref) { s.wordMenu = null }
                }
            }
        }
    }
}

/** Reader: what to do with the sentence of a long-pressed word. */
@Composable
private fun ReaderMenuItems(s: ReaderSession, ref: WordRef, close: () -> Unit) {
    val id = ref.sent
    DropdownMenuItem(
        text = { Text(if (s.shownSentences[id] == true) "Скрыть перевод предложения" else "Перевод предложения") },
        leadingIcon = { Icon(AppIcons.Translate, contentDescription = null) },
        onClick = { close(); s.toggleSentence(id) },
    )
    DropdownMenuItem(
        text = { Text(if (s.isBookmarked(id)) "Убрать закладку" else "В закладки") },
        leadingIcon = { Icon(if (s.isBookmarked(id)) AppIcons.Bookmark else AppIcons.BookmarkBorder, contentDescription = null) },
        onClick = { close(); s.toggleBookmark(id) },
    )
    DropdownMenuItem(
        text = { Text("Исправить предложение") },
        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        onClick = { close(); s.startEdit(id) },
    )
    if (s.hasTime(id)) DropdownMenuItem(
        text = { Text("Слушать отсюда") },
        leadingIcon = { Icon(AppIcons.Headphones, contentDescription = null) },
        onClick = { close(); s.listenFrom(id) },
    )
    DropdownMenuItem(
        text = { Text("Чат о предложении") },
        leadingIcon = { Icon(AppIcons.Chat, contentDescription = null) },
        onClick = { close(); s.chatSentence(id) },
    )
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
            WordMenuLayer(s, "sent") { ref, close -> ReaderMenuItems(s, ref, close) }
        }
    }
}

/** Sentence mode: one sentence with its translation per page, swipe for the next one. */
@Composable
private fun SentencePager(s: ReaderSession) {
    val list = s.sentences
    val pager = rememberPagerState(initialPage = s.page.coerceIn(0, maxOf(0, list.size - 1))) { list.size }
    LaunchedEffect(pager.currentPage) {
        s.page = pager.currentPage
        list.getOrNull(pager.currentPage)?.let { s.reached(it.id, it.id) }
    }
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
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                    BookmarkButton(s, it.id)
                    SentenceMenu(s, it.id)
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
                    val sent = list[i]
                    // sentence and translation in the middle of the page; long ones scroll
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val minH = maxHeight
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = minH)
                                    .padding(horizontal = if (s.wide) 64.dp else 20.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                SentenceFlow(s, listOf(sent), scale = 1.15f, area = "sent", center = true)
                                SmTranslationToggle(s)
                                AnimatedVisibility(
                                    visible = s.smTranslation,
                                    enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                                    exit = shrinkVertically(tween(300)) + fadeOut(tween(300)),
                                ) {
                                    SentenceTranslation(s, sent.id, scale = 1.1f, center = true, area = "sent")
                                }
                            }
                        }
                    }
                }
                if (s.wide) TurnButtons(pager)
            }
            if (s.panelActive) {
                VerticalDivider()
                DockedPanel(s, Modifier.width(PANEL_WIDTH).fillMaxHeight())
            }
        }
    }
}

/** Sentence mode ⋮: edit the sentence, listen from it, text size. */
@Composable
private fun SentenceMenu(s: ReaderSession, id: Int) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Редактировать предложение") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { open = false; s.startEdit(id) },
            )
            if (s.hasTime(id)) DropdownMenuItem(
                text = { Text("Слушать отсюда") },
                leadingIcon = { Icon(AppIcons.Headphones, contentDescription = null) },
                onClick = { open = false; s.listenFrom(id) },
            )
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Крупнее  A+") }, onClick = { s.changeFont(+2) })
            DropdownMenuItem(text = { Text("Мельче  A−") }, onClick = { s.changeFont(-2) })
        }
    }
}

/** "Скрыть перевод ⌃" / "Показать перевод ⌄" under the sentence; the choice holds for all sentences. */
@Composable
private fun SmTranslationToggle(s: ReaderSession) {
    val rot by animateFloatAsState(if (s.smTranslation) 180f else 0f, label = "chevron")
    TextButton(onClick = { s.toggleSmTranslation() }) {
        Text(if (s.smTranslation) "Скрыть перевод" else "Показать перевод")
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.rotate(rot))
    }
}

@Composable
internal fun WordSheet(s: ReaderSession, ref: WordRef) {
    ModalBottomSheet(onDismissRequest = { s.popup = null }) {
        Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            WordContent(s, ref)
        }
    }
}

/** Word window contents: in the pop-up sheet (phone) or in the docked panel (tablet). */
@Composable
private fun WordContent(s: ReaderSession, ref: WordRef) {
    var own by remember(ref) { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        color = if (saved == null && s.popupThinking) MaterialTheme.colorScheme.outline else LocalHighlight.current.text,
                    )
                }
                if (s.popupThinking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (saved != null || s.pins.containsKey(ref)) IconButton(onClick = { s.unpin(ref) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Убрать перевод с этого слова")
                }
                DockButton(s)
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
                                label = { Text(h, Modifier.padding(vertical = 6.dp)) },
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
                    onClick = { s.chatWord(ref.sent, word) }, label = { Text("Спросить") },
                    leadingIcon = { Icon(AppIcons.Chat, contentDescription = null, Modifier.size(18.dp)) },
                )
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
            if (second == null) s.tapWord(ref)
            else { second.consume(); waitForUpOrCancellation()?.consume(); s.openPopup(ref) }
            return@awaitEachGesture
        }
        // long press: select from this word, follow the finger
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        s.menuArea = area
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
internal fun PhraseSheet(s: ReaderSession, p: Phrase) {
    ModalBottomSheet(onDismissRequest = { s.closePhrase() }) {
        Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            PhraseContent(s, p)
        }
    }
}

/** Phrase window contents: in the pop-up sheet or in the docked panel. */
@Composable
private fun PhraseContent(s: ReaderSession, p: Phrase) {
    var own by remember(p) { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.text, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                DockButton(s)
            }
            s.phraseBest?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = LocalHighlight.current.text) }
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
                        selected = isSaved, onClick = { s.choosePhrase(h) }, label = { Text(h, Modifier.padding(vertical = 6.dp)) },
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
                    onClick = { s.chatWord(p.sent, p.text) }, label = { Text("Спросить") },
                    leadingIcon = { Icon(AppIcons.Chat, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        }
}
