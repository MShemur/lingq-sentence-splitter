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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.layoutId
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
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
class Token(val text: String, val isWord: Boolean, val index: Int, val b: Boolean = false, val i: Boolean = false)

/** Bold / italic span of a lesson sentence: markers at [at, cs) and [ce, end). */
class Emph(val at: Int, val cs: Int, val ce: Int, val end: Int, val mk: String)

/** One sentence of the lesson; [id] is unique in the lesson. */
class Sent(
    val id: Int, val text: String, val translations: Map<String, String>, val lingqIndex: Int = id + 1,
    /** Where it sounds in the lesson audio, ms (-1 = unknown). */
    val start: Long = -1, val end: Long = -1,
    /** In a heading paragraph (LingQ style "h1".."h6"): drawn bold. Inside the text bold / italic are ** / * (kept by LingQ). */
    val heading: Boolean = false,
    /** PDF book: in a caption ("c") / box ("o") paragraph, labelled in the text view; "" = body. */
    val aside: String = "",
) {
    val tokens: List<Token> = styledTokens(text)
    /** The text as shown (without the ** / * markers): for translations, the AI, bookmarks. */
    val plain: String = tokens.joinToString("") { it.text }
    val chunks: List<List<Token>> = chunks(tokens)

    companion object {
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:[-'’][\\p{L}\\p{N}]+)*")
        private val SPACES = Regex("\\s+")
        /** Markdown emphasis kept in the lesson text: ***both***, **bold** / __bold__, *italic* / _italic_. */
        private val EMPH = Regex("(\\*\\*\\*|\\*\\*|__|\\*|_)([^\\s*_](?:[\\s\\S]*?[^\\s*_])?)\\1")
        private val NEAR = Regex("[\\p{L}\\p{N}*_]")

        /** Emphasis spans; not glued to a letter on the outside ("3 * 4", snake_case stay). */
        fun emphSpans(text: String): List<Emph> {
            if (text.none { it == '*' || it == '_' }) return emptyList()
            fun near(k: Int) = k in text.indices && NEAR.matches(text[k].toString())
            val out = mutableListOf<Emph>()
            for (m in EMPH.findAll(text)) {
                val at = m.range.first; val mk = m.groupValues[1]; val end = m.range.last + 1
                if (near(at - 1) || near(end)) continue
                out += Emph(at, at + mk.length, end - mk.length, end, mk)
            }
            return out
        }

        /** Tokens with the emphasis markers hidden (token indexes stay those of the raw text), words flagged bold / italic. */
        private fun styledTokens(text: String): List<Token> {
            val raw = tokenize(text)
            val spans = emphSpans(text)
            if (spans.isEmpty()) return raw
            val hide = BooleanArray(text.length)
            val style = IntArray(text.length)            // 1 bold, 2 italic
            for (x in spans) {
                for (k in x.at until x.cs) hide[k] = true
                for (k in x.ce until x.end) hide[k] = true
                val st = when (x.mk.length) { 3 -> 3; 2 -> 1; else -> 2 }
                for (k in x.cs until x.ce) style[k] = style[k] or st
            }
            var pos = 0
            return raw.map { t ->
                val a = pos
                pos += t.text.length
                var st = style[a]
                var t2 = t.text
                if (!t.isWord) {
                    val sb = StringBuilder(); st = 0
                    for (k in a until pos) if (!hide[k]) { sb.append(text[k]); if (st == 0 && !text[k].isWhitespace()) st = style[k] }
                    t2 = sb.toString()
                }
                if (st != 0 || t2 != t.text) Token(t2, t.isWord, t.index, (st and 1) != 0, (st and 2) != 0) else t
            }
        }

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

/**
 * Added to the word prompt: the word may be one part of a bigger unit in this sentence: a verb whose prefix stands
 * apart ("fing … an"), a reflexive verb with its pronoun ("freut sich"), a fixed phrase ("nimmt … in Kauf").
 */
private const val SEPARABLE = "\n\nУчитывай грамматику всего предложения. Если «{word}» — часть единого целого в этом предложении: " +
    "глагол с отделяемой приставкой, стоящей отдельно (нем. «Er fing gestern an» → anfangen, «Sie kommt morgen zurück» → zurückkommen), " +
    "возвратный глагол с местоимением (sich, mich, dich, uns, euch: «Er freut sich darauf» → sich freuen auf), " +
    "устойчивое выражение или глагол с управлением («Sie nimmt das in Kauf» → in Kauf nehmen, «Es kommt darauf an» → ankommen auf), " +
    "переводи всё целое, а в note укажи его словарную форму (инфинитив с приставкой, sich, предлогом). " +
    "Если «{word}» — приставка, sich или другое слово такого целого, переводи всё целое так же. " +
    "Добавь в JSON поле \"pair\": остальные слова этого целого ровно так, как они написаны в предложении, через пробел " +
    "(без самого «{word}»); пустая строка, если «{word}» — просто отдельное слово. " +
    "В pair — только слова из этого предложения в той форме, как они написаны (спрягаемый глагол, а не инфинитив): " +
    "для «an» в «Ich rufe dich morgen an» pair = «rufe», для «entgegen» в «So tritt er ihm entgegen» pair = «tritt». " +
    "Будь внимательен: одно и то же слово может стоять в предложении несколько раз в разной роли (нем. «um» — предлог " +
    "в «erweitert sich um den Aspekt» и приставка в «treibt jeden Menschen um» → umtreiben). Если слово из pair " +
    "встречается в предложении больше одного раза, допиши к нему номер нужного вхождения, считая слева, в квадратных скобках: «um[2]»."

/** For the chat about the text: how the AI marks words in the lesson (see [ChatSession] onMark). */
private const val MARK_HELP = "\n\nТы можешь отмечать слова и фразы прямо в тексте урока — над ними появится перевод, " +
    "как будто пользователь отметил их сам. Делай это, когда пользователь просит выделить, отметить, подписать или " +
    "показать перевод слов в тексте (например «отметь все глаголы», «подпиши трудные слова»). Для этого в конце ответа " +
    "добавь отдельную строку: [[отметить: слово = перевод; другое слово = перевод; фраза из слов подряд = перевод]]. " +
    "Пиши слова ровно так, как они написаны в тексте, перевод — коротко, по смыслу в этом тексте. " +
    "Снять отметки: [[снять: слово; слово]]. Не пиши такие строки, если пользователь об этом не просил."

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
private class LessonNotes(private val file: File) {
    constructor(ctx: Context, lang: String, id: Int) : this(File(File(ctx.filesDir, "reader").apply { mkdirs() }, "${lang}_$id.json"))

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
internal class TranslationCache(private val file: File) {
    constructor(ctx: Context, lang: String, id: Int, target: String) :
        this(File(File(ctx.filesDir, "trcache").apply { mkdirs() }, "${lang}_${id}_$target.json"))

    fun load(): Map<String, String> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrElse { emptyMap() }

    fun save(m: Map<String, String>) {
        val all = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }.toString()
        DiskQueue.write { file.writeText(all) }
    }
}

/** PDF book on the phone without an AI key. */
internal const val NO_AI_LOCAL = "Для переводов в PDF-книге нужен ключ Gemini (или другого ИИ): Настройки → Перевод и ИИ"

private val LETTERS = Regex("\\p{L}+")
private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

// --- session -------------------------------------------------------------------------------------

class ReaderSession(private val app: AppState, val lang: String, val lesson: Lesson, startSent: Int? = null) {
    /** PDF book page at the base zoom: "width" (fills the screen width) or "page" (whole page). */
    val pdfFit: String get() = app.pdfFit
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
        val on = app.bookmarks.toggle(lang, Lesson(lesson.id, title), id, s.plain, tr)
        app.say(if (on) "Предложение в закладках" else "Закладка удалена")
    }

    // --- tablet: docked word panel; sentence mode translation fold ---

    /** Wide screen (tablet): page-turn buttons and the docked word panel are available. */
    var wide by mutableStateOf(false)
    /** Book mode: full screen, the top bar stays in place above the text. */
    val book get() = app.book
    var docked by mutableStateOf(app.prefs.dockPanel)
    val panelActive get() = wide && docked

    fun setDock(on: Boolean) {
        docked = on
        app.prefs.dockPanel = on
        if (!on) { popup = null; closePhrase() }
    }

    var smTranslation by mutableStateOf(app.prefs.smTranslation)

    fun toggleSmTranslation() { smTranslation = !smTranslation; app.prefs.smTranslation = smTranslation }
    /** ⋮ → "Отзыв / задача" (back returns to the lesson). */
    fun openFeedback() = app.go(Screen.Feedback)

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
        val sys = Prompts.fill(app.prefs.promptChatWord, "lang" to lang, "target" to target, "word" to word, "sentence" to st.text) + markedForAi()
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
        if (noAi()) { clearSelection(); return }
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
                    val mk = "p:$key"
                    val m = meanings[mk] ?: withContext(Dispatchers.IO) { app.geminiFast().word(prompt, lang, target, text, st.text) }
                        .also { keepMeaning(mk, it) }
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
            if (phrase == p) {
                phraseLoading = false
                // the translation is there: the selection colour goes, the phrase keeps the usual highlight
                if (selSent == sent && selFrom == from && selTo == to) clearSelection()
            }
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
    /** PDF book lesson (see PdfBook.kt): its PDF pages with the words over them; null = an ordinary lesson. */
    val pdfLink: PdfLink? = runCatching { PdfBooks.link(app.ctx, lang, lesson.id) }.getOrNull()
    /** The PDF pages are shown (else the text as usual). */
    var showPdf by mutableStateOf(pdfLink != null)
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
    /**
     * A word that is part of a bigger unit in its sentence (split verb + prefix, reflexive verb + sich, fixed phrase):
     * word -> the other words of the unit, from the AI's answer.
     */
    val pairs = mutableStateMapOf<WordRef, List<WordRef>>()

    /** The other words of the unit are highlighted with the word while its window is open or the word is pinned. */
    fun pairHighlighted(ref: WordRef): Boolean = pairs.entries.any { (w, p) -> ref in p && (popup == w || pins.containsKey(w)) }

    private fun markPair(ref: WordRef, pair: String) {
        // "um[2]" = the second "um" of the sentence (the AI numbers a word that occurs more than once)
        val words = pair.lowercase().split(Regex("\\s+")).mapNotNull { raw ->
            val nth = Regex("\\[(\\d+)]").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            val w = raw.replace(Regex("\\[\\d+]"), "").trim { !it.isLetterOrDigit() }
            if (w.isEmpty()) null else w to nth
        }
        if (words.isEmpty() || words.size > 6) return
        val toks = sentById[ref.sent]?.tokens ?: return
        val pos = toks.indexOfFirst { it.index == ref.token }
        val tapped = toks.getOrNull(pos)?.text?.lowercase().orEmpty()
        val used = mutableSetOf(ref.token)
        // the nearest unused one, but a split-off prefix stands AFTER its verb at the end of a clause
        // (before , . ; or "und"), so such a place counts as much nearer
        fun nearest(cands: List<Token>): Token? = cands.filter { it.index !in used }.minByOrNull { t ->
            val i = toks.indexOf(t)
            val next = toks.drop(i + 1).firstOrNull { it.text.isNotBlank() }
            val clauseEnd = next == null || !next.isWord || next.text.lowercase() in setOf("und", "oder", "aber")
            kotlin.math.abs(i - pos) - (if (i > pos && clauseEnd) 100 else 0)
        }
        val hits = words.mapNotNull { (w, nth) ->
            val all = toks.filter { it.isWord && it.text.lowercase() == w }
            val numbered = nth?.let { all.getOrNull(it - 1) }?.takeIf { it.index !in used }
            val found = numbered ?: if (all.isNotEmpty()) nearest(all) else {
                // no exact match: "entgegentreten" with "entgegen" tapped -> "treten"; then a stem match ("tritt" for "treten")
                val w2 = if (w.startsWith(tapped) && w.length > tapped.length) w.substring(tapped.length) else w
                val stem = when {
                    w2.endsWith("en") && w2.length - 2 >= 3 -> w2.dropLast(2)
                    w2.endsWith("n") && w2.length - 1 >= 3 -> w2.dropLast(1)
                    else -> w2
                }
                if (stem.length < 3) null else nearest(toks.filter { t ->
                    val x = t.text.lowercase()
                    t.isWord && (x.startsWith(stem) || (x.length >= 3 && x.take(2) == stem.take(2) && editDistance(x, stem) <= 2))
                })
            }
            found?.also { used += it.index }
        }
        if (hits.isNotEmpty()) pairs[ref] = hits.map { WordRef(ref.sent, it.index) }
    }

    /** Levenshtein distance between two words (a verb with a changed stem: "tritt" vs "treten"). */
    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
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

    /** A PDF book kept on the phone only (PdfBook.kt, id < 0): no LingQ at all, translations straight from the AI. */
    val local = lesson.id < 0
    private val localBook: LocalPdfBook? = if (local) PdfBooks.localBook(app.ctx, lesson.id) else null
    private val notes = localBook?.let { LessonNotes(PdfBooks.notesFile(app.ctx, it)) } ?: LessonNotes(app.ctx, lang, lesson.id)
    private val trCache by lazy {
        localBook?.let { TranslationCache(PdfBooks.trFile(app.ctx, it, target)) } ?: TranslationCache(app.ctx, lang, lesson.id, target)
    }
    /** Local book: AI meanings of words and phrases kept on the phone ("w:sent:token", "p:sent:from:to"). */
    private val meaningStore = localBook?.let { MeaningStore(PdfBooks.aiFile(app.ctx, it)) }
    private val meanings = HashMap<String, WordMeaning>()

    private fun keepMeaning(key: String, m: WordMeaning) {
        val st = meaningStore ?: return
        meanings[key] = m
        st.save(HashMap(meanings))
    }

    /** Local book without an AI key: nothing can be translated (no LingQ to fall back to). */
    private fun noAi(): Boolean {
        if (!local || app.useGemini) return false
        app.say(NO_AI_LOCAL)
        return true
    }
    /** Sentence text -> translation made here (not LingQ's stored one). */
    private val trSaved = HashMap<String, String>()

    /** A sentence translation was made: keep it unless it is LingQ's own one or an error. */
    private fun keepTr(id: Int, t: String) {
        val s = sentById[id] ?: return
        if (t.isBlank() || t.startsWith("⚠") || t == s.translations[target]) return
        trSaved[s.plain] = t
        trCache.save(HashMap(trSaved))
    }
    /** "Обработать текст с ИИ": "2 из 5" while running, null otherwise; [askAiClean] shows the confirmation. */
    var aiClean by mutableStateOf<String?>(null)
    var askAiClean by mutableStateOf(false)

    /**
     * The lesson's text cleaned by the AI like at the import (page numbers, headers, footnotes, broken lines) and
     * written back to LingQ: backup on the phone first; sentences that stay the same keep their translation and audio
     * time; then the sentence split is fixed as after an import and the lesson is read again.
     */
    fun cleanWithAi() {
        if (!app.useGemini) return app.say("Для обработки с ИИ включите ИИ: Настройки → Перевод и ИИ")
        if (aiClean != null) return
        aiClean = "…"
        AppLog.add("reader", "Урок ${lesson.id}: обработка текста с ИИ")
        app.scope.launch {
            try {
                val (before, after, copyId) = withContext(Dispatchers.IO) {
                    val eo = app.api.editorObj(lang, lesson.id)
                    val paras = eo.optJSONArray("paragraphs") ?: JSONArray()
                    val courseId = eo.opt("collection").let { c -> if (c is JSONObject) c.optInt("id") else c?.toString()?.toIntOrNull() ?: 0 }
                    val old = HashMap<String, JSONObject>()
                    var tmpl: JSONObject? = null
                    var pTmpl: JSONObject? = null
                    var count = 0
                    val parts = mutableListOf<String>()
                    for (i in 0 until paras.length()) {
                        val p = paras.getJSONObject(i)
                        val ss = p.optJSONArray("sentences") ?: continue
                        val texts = mutableListOf<String>()
                        for (j in 0 until ss.length()) {
                            val o = ss.getJSONObject(j)
                            val t = o.optString("text").trim()
                            if (t.isEmpty()) continue
                            if (tmpl == null) { tmpl = o; pTmpl = p }
                            old.putIfAbsent(t, o)
                            texts += t
                            count++
                        }
                        if (texts.isNotEmpty()) parts += texts.joinToString(" ")
                    }
                    val t0 = tmpl ?: throw ApiException("урок пустой")
                    val src = TextCleaner.clean(parts.joinToString("\n\n")).first
                    val pieces = TextCleaner.chunks(src)
                    val cleaned = pieces.mapIndexed { i, piece ->
                        aiClean = "${i + 1} из ${pieces.size}"
                        app.geminiFast().clean(app.prefs.promptClean, lang, piece).ifBlank { piece }
                    }.joinToString("\n\n").let(TextCleaner::spaceQuotes)
                    val out = JSONArray()
                    var idx = 0
                    for (pt in cleaned.split(Regex("\\n\\s*\\n"))) {
                        val ss = splitSentences(pt.trim())
                        if (ss.isEmpty()) continue
                        // LingQ answers 500 when paragraphs repeat an index or a timestamp is not [a, b]
                        val p = JSONObject(pTmpl.toString()).put("index", out.length() + 1)
                        if (p.has("style")) p.put("style", "p")
                        val arr = JSONArray()
                        ss.forEach { t ->
                            val o = old[t]?.let { JSONObject(it.toString()) } ?: JSONObject(t0.toString())
                                .put("text", t).put("cleanText", t)
                                .put("translations", JSONArray()).put("phrases", JSONArray()).put("notes", JSONArray())
                                .put("timestamp", JSONArray().put(JSONObject.NULL).put(JSONObject.NULL))
                            arr.put(o.put("index", ++idx))
                        }
                        p.put("sentences", arr)
                        out.put(p)
                    }
                    if (idx == 0) throw ApiException("ИИ вернул пустой текст, урок не изменён")
                    markdownHeadings(out)
                    app.backups.save(lang, Lesson(lesson.id, title), JSONArray(paras.toString()))
                    Fixer.fix(out)
                    try {
                        app.api.saveParagraphs(lang, lesson.id, out)
                        Triple(count, idx, 0)
                    } catch (e: Throwable) {
                        // LingQ refused to overwrite: a new lesson "… (исправлено)" in the same course, the old one stays
                        AppLog.error("reader", "Урок ${lesson.id}: LingQ не сохранил обработанный текст, делаю новый урок", e)
                        if (courseId <= 0) throw e
                        val text = (0 until out.length()).joinToString("\n\n") { i ->
                            val ss = out.getJSONObject(i).getJSONArray("sentences")
                            (0 until ss.length()).joinToString(" ") { ss.getJSONObject(it).optString("text") }
                        }
                        Triple(count, idx, app.api.importLesson(lang, courseId, "$title (исправлено)", text))
                    }
                }
                if (copyId > 0) {
                    AppLog.add("reader", "Урок ${lesson.id}: обработанный текст — новый урок $copyId")
                    app.sayLong("LingQ не дал перезаписать урок — сделан новый урок «$title (исправлено)» в том же курсе")
                } else {
                    AppLog.add("reader", "Урок ${lesson.id}: ИИ обработал текст, было $before предложений, стало $after")
                    app.sayLong("Текст обработан: было $before предложений, стало $after. Копия старого — в резервных копиях")
                    load()
                }
            } catch (e: Throwable) {
                AppLog.error("reader", "Обработать текст с ИИ", e)
                app.sayLong("Обработка не получилась: ${e.message}")
            }
            aiClean = null
        }
    }

    /** "Перевести весь урок": "12 из 80" while running, null otherwise. */
    var trAll by mutableStateOf<String?>(null)

    /** Every sentence translated ahead (kept like the other translations; headphone translation uses them too). */
    fun translateAll() {
        if (trAll != null || noAi()) return
        trAll = "…"
        app.say("Перевожу весь урок…")
        if (local) {
            // local book: sentence by sentence with the AI, each kept like a single translation
            app.scope.launch {
                val todo = sentences.filter { sentTr[it.id].let { t -> t.isNullOrEmpty() || t.startsWith("⚠") } }
                var failed = 0
                todo.forEachIndexed { i, st ->
                    trAll = "${i + 1} из ${todo.size}"
                    try { sentTr.remove(st.id); sentenceTranslation(st.id) } catch (e: Throwable) {
                        failed++
                        if (isGeminiLimit(e)) { app.geminiFailed(e, fast = true) { translateAll() }; trAll = null; return@launch }
                    }
                }
                app.sayLong(if (failed == 0) "Вся книга переведена" else "Переведено ${todo.size - failed} из ${todo.size}; не удалось $failed")
                trAll = null
            }
            return
        }
        app.scope.launch {
            try {
                val (all, failed) = ListenTranslate.translateAll(app, lang, lesson.id) { n, all -> trAll = "$n из $all" }
                val saved = withContext(Dispatchers.IO) { trCache.load() }
                trSaved.putAll(saved)
                sentences.forEach { st -> (saved[st.plain] ?: saved[st.text])?.let { sentTr[st.id] = it } }
                app.sayLong(if (failed == 0) "Весь урок переведён" else "Переведено ${all - failed} из $all; не удалось $failed")
            } catch (e: Throwable) {
                AppLog.error("reader", "Перевести весь урок", e)
                app.sayLong("Перевод урока не получился: ${e.message}")
            }
            trAll = null
        }
    }
    private val hintsCache = HashMap<String, List<String>>()
    private val loadingSentences = HashSet<Int>()
    private val sentById = HashMap<Int, Sent>()

    val target get() = app.trLang

    /** Local book: sentences from the phone, no LingQ call. */
    private fun loadLocal() {
        app.scope.launch {
            try {
                val b = localBook ?: throw ApiException("PDF-книга не найдена на телефоне (удалена?)")
                val paras = withContext(Dispatchers.IO) {
                    var id = 0
                    val kinds = PdfBooks.readKinds(app.ctx, b)
                    PdfBooks.readSentences(app.ctx, b).mapIndexed { pi, p ->
                        val aside = kinds.getOrElse(pi) { "" }
                        p.map { t -> Sent(id, t, emptyMap(), id + 1, aside = aside).also { sentById[it.id] = it; id++ } }
                    }.filter { it.isNotEmpty() }
                }
                audioUrl = null
                val (p, w, ph) = withContext(Dispatchers.IO) { notes.load() }
                pins.putAll(p); chosen.putAll(w); phrasePins.putAll(ph)
                val saved = withContext(Dispatchers.IO) { trCache.load() }
                trSaved.putAll(saved)
                paras.forEach { para -> para.forEach { st -> (saved[st.plain] ?: saved[st.text])?.let { sentTr[st.id] = it } } }
                val ms = withContext(Dispatchers.IO) { meaningStore?.load().orEmpty() }
                meanings.putAll(ms)
                ms.forEach { (k, m) ->
                    val q = k.split(':')
                    if (q.size == 3 && q[0] == "w") {
                        val ref = WordRef(q[1].toIntOrNull() ?: return@forEach, q[2].toIntOrNull() ?: return@forEach)
                        aiCache[ref] = m
                        if (m.pair.isNotBlank()) markPair(ref, m.pair)
                    }
                }
                title = b.title
                app.lessonOpened(lang, b.lesson())
                sentences = paras.flatten()
                jump?.let { id -> page = sentences.indexOfFirst { it.id == id }.coerceAtLeast(0) }
                paragraphs = paras
                if (!app.useGemini) app.sayLong(NO_AI_LOCAL)
            } catch (e: Throwable) {
                AppLog.error("reader", "PDF-книга ${lesson.id} не открылась", e)
                error = e.message ?: e.toString()
            }
        }
    }

    fun load() {
        error = null
        if (local) return loadLocal()
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
                paras.forEach { para -> para.forEach { st -> (saved[st.plain] ?: saved[st.text])?.let { sentTr[st.id] = it } } }
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
            val heading = a.getJSONObject(i).optString("style").matches(Regex("h[1-6]"))
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
                val sent = Sent(id, text, tr, s.optInt("index", id + 1), start, end, heading)
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
        if (local && !app.useGemini) throw ApiException(NO_AI_LOCAL)
        val t = s.translations[target] ?: withContext(Dispatchers.IO) {
            if (app.useGemini) app.geminiFast().sentence(app.prefs.promptSentence, lang, target, s.plain)
            else app.api.translate(lang, target, s.plain)
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
        if (retranslating[id] == true || noAi()) return
        retranslating[id] = true
        app.scope.launch {
            try {
                val current = sentTr[id]?.takeIf { it.isNotEmpty() && !it.startsWith("⚠") } ?: s.translations[target].orEmpty()
                val t = withContext(Dispatchers.IO) {
                    if (app.useGemini) app.gemini().retranslate(app.prefs.promptRetranslate, lang, target, s.plain, current)
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
        if (local) return emptyList()          // local book: no LingQ meanings, the AI only
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
            if (local && !app.useGemini) throw ApiException(NO_AI_LOCAL)
            val m = withContext(Dispatchers.IO) { app.geminiFast().word(app.prefs.promptWord + SEPARABLE, lang, target, word(ref), s.plain) }
            aiCache[ref] = m
            keepMeaning("w:${ref.key()}", m)
            markPair(ref, m.pair)
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
        if (local) return ai(ref).best           // local book: the AI only, never LingQ
        if (app.useGemini) runCatching { ai(ref).best }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        val tr = runCatching { sentenceTranslation(ref.sent) }.getOrDefault("").lowercase()
        return pickByTranslation(hs, tr)
            ?: hs.firstOrNull()
            ?: withContext(Dispatchers.IO) { app.api.translate(lang, target, word) }
    }

    // --- translations above words hidden for a while (to test oneself) ---

    /** The words stay marked, their translations are invisible; a tap shows one for 5 s. */
    var hideTr by mutableStateOf(false)
    /** Translations shown right now while hidden: WordRef.key() or phrase key -> token of that showing. */
    val peek = mutableStateMapOf<String, Long>()

    fun toggleHideTr() { hideTr = !hideTr; peek.clear(); AppLog.add("reader", if (hideTr) "Переводы скрыты" else "Переводы показаны") }

    private fun reveal(key: String) {
        val t = System.nanoTime()
        peek[key] = t
        app.scope.launch { kotlinx.coroutines.delay(5000); if (peek[key] == t) peek.remove(key) }
    }

    fun trVisible(key: String) = !hideTr || peek.containsKey(key)
    /** Settings: gap between a word and its translation, %. */
    val trGap get() = app.trGap
    /** Settings: highlight colour key and "word on a coloured background" (false = «Без цвета»). */
    val highlightKey get() = app.highlight
    val wordBg get() = app.wordBg
    /** Settings: line spacing, % of the usual one. */
    val lineSpacing get() = app.lineSpacing
    /** Space between paragraphs as set (usual: none in book mode, 14 dp otherwise). */
    val paraGap: Dp get() = if (app.paraGap >= 0) app.paraGap.dp else if (book) 0.dp else 14.dp
    val justify get() = app.justify

    /** Words and phrases with a translation in this lesson, in text order, each word once: (text, translation, sentence). */
    fun markedWords(): List<Triple<String, String, Int>> {
        val order = HashMap<Int, Int>().also { m -> sentences.forEachIndexed { i, s -> m[s.id] = i } }
        val out = ArrayList<Pair<Long, Triple<String, String, Int>>>()
        val seen = HashSet<String>()
        pins.entries.sortedWith(compareBy({ order[it.key.sent] ?: Int.MAX_VALUE }, { it.key.token })).forEach { (ref, tr) ->
            if (tr.isBlank() || tr.startsWith("⚠")) return@forEach
            val w = word(ref).ifEmpty { return@forEach }
            if (!seen.add(w.lowercase())) return@forEach
            out += ((order[ref.sent] ?: Int.MAX_VALUE).toLong() * 10_000 + ref.token) to Triple(w, tr, ref.sent)
        }
        phrasePins.forEach { (k, tr) ->
            val p = k.split(':')
            val sent = p.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val from = p.getOrNull(1)?.toIntOrNull() ?: return@forEach
            val to = p.getOrNull(2)?.toIntOrNull() ?: return@forEach
            if (tr.isBlank() || tr.startsWith("⚠")) return@forEach
            val toks = sentById[sent]?.tokens ?: return@forEach
            val text = toks.subList(from.coerceIn(0, toks.size), (to + 1).coerceIn(0, toks.size)).joinToString("") { it.text }.trim()
            if (text.isEmpty() || !seen.add(text.lowercase())) return@forEach
            out += ((order[sent] ?: Int.MAX_VALUE).toLong() * 10_000 + from) to Triple(text, tr, sent)
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** The marked words for the AI chats ("" when there are none). */
    private fun markedForAi(): String {
        val m = markedWords()
        if (m.isEmpty()) return ""
        return "\n\nСлова и фразы, которые пользователь отметил в этом уроке (с переводом, который он выбрал или видел):\n" +
            m.take(300).joinToString("\n") { "- ${it.first} — ${it.second}" }
    }

    /** One tap: translation pinned above the word (tap again removes it). */
    fun tap(ref: WordRef) {
        AppLog.add("reader", "Нажатие «${word(ref)}» (предл. ${ref.sent})")
        focusSent = ref.sent
        if (hideTr) {
            // hidden translations: a tap on a marked word shows its translation for 5 s, nothing is removed
            phraseKeyAt(ref)?.let { reveal(it); return }
            if (pins.containsKey(ref)) { reveal(ref.key()); return }
            reveal(ref.key())
        }
        phraseKeyAt(ref)?.let { phrasePins.remove(it); persist(); return }
        if (pins.containsKey(ref)) { pins.remove(ref); provisional.remove(ref); persist(); return }
        if (chosen[word(ref).lowercase()] == null && noAi()) return
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
        if (noAi()) return
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
                            .onFailure { e ->
                                if (local && popup == ref) popupError = e.message ?: e.toString()
                                app.geminiFailed(e, fast = true) { if (popup == ref) openPopup(ref) }
                            }.getOrNull()
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
        val text = paragraphs.orEmpty().joinToString("\n\n") { p -> p.joinToString(" ") { it.plain } }
        val sys = Prompts.fill(app.prefs.promptChatText, "lang" to lang, "target" to target, "text" to text) + markedForAi() + MARK_HELP
        app.go(Screen.Chat(ChatSession(app, "Чат о тексте", sys, title, wholeText = true, onMark = ::markFromAi)))
    }

    /**
     * From the chat: words / phrases (as written in the text) get their translation above them, every place they
     * occur (at most 50 each), like a tap by hand; [mark] false removes them. A phrase = words one after another.
     */
    fun markFromAi(mark: Boolean, items: List<Pair<String, String>>): String {
        var n = 0
        val missing = mutableListOf<String>()
        for ((w, tr) in items) {
            val want = w.split(NON_WORD).filter { it.isNotEmpty() }.map { it.lowercase() }
            if (want.isEmpty()) continue
            var hit = 0
            for (st in sentences) {
                if (hit >= 50) break
                val words = st.tokens.filter { it.isWord }
                var i = 0
                while (i + want.size <= words.size && hit < 50) {
                    if (want.indices.all { words[i + it].text.lowercase() == want[it] }) {
                        hit++
                        if (want.size == 1) {
                            val ref = WordRef(st.id, words[i].index)
                            if (!mark) pins.remove(ref)
                            else if (tr.isNotEmpty()) pins[ref] = tr
                            else if (pins[ref].isNullOrEmpty()) {
                                // no translation given: the reader's own, as after a tap
                                pins[ref] = ""
                                app.scope.launch {
                                    pins[ref] = try { contextual(ref, hints(word(ref))) } catch (e: Throwable) { "⚠" }
                                    persist()
                                }
                            }
                        } else {
                            val key = phraseKey(st.id, words[i].index, words[i + want.size - 1].index)
                            if (mark) phrasePins[key] = tr.ifEmpty { phrasePins[key].orEmpty() } else phrasePins.remove(key)
                        }
                        i += want.size
                    } else i++
                }
            }
            if (hit == 0) missing += w else n++
        }
        persist()
        val what = if (mark) "Отмечено в тексте" else "Снято"
        return "$what: $n" + if (missing.isNotEmpty()) " · не нашёл в тексте: ${missing.take(5).joinToString(", ")}" else ""
    }

    fun chatSentence(id: Int) {
        val s = sentById[id] ?: return
        popup = null
        val sys = Prompts.fill(app.prefs.promptChatSentence, "lang" to lang, "target" to target, "sentence" to s.plain) + markedForAi()
        app.go(Screen.Chat(ChatSession(app, "Чат о предложении", sys, s.plain)))
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
        if (!app.useGemini) { app.say("Нужен Gemini или другой ИИ: Настройки → Перевод и ИИ"); return }
        val text = sentences.joinToString(" ") { it.plain }
        if (text.isBlank()) return
        levelBusy = true
        app.say("ИИ оценивает уровень…")
        app.scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    val r = app.geminiFast().level(app.prefs.promptLevel, lang, text)
                    if (!local) app.api.setLevel(lang, lesson.id, r.first)
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
                writeSentence(s, newText, newTr)
                editing = null
                app.say("Предложение сохранено")
            } catch (e: Throwable) {
                AppLog.error("reader", "Правка предложения $id", e)
                editError = e.message ?: e.toString()
            }
            editSaving = false
        }
    }

    /** Reads the lesson again, keeps a backup, changes only sentence [s] (text, translation), saves the structure as is. */
    private suspend fun writeSentence(s: Sent, newText: String, newTr: String) {
        val b = localBook
        if (b != null) {
            // local book: the sentence file on the phone; the translation goes to the book's translation cache
            withContext(Dispatchers.IO) {
                val paras = PdfBooks.readSentences(app.ctx, b)
                var k = 0
                val out = paras.map { p -> p.map { t -> (if (k++ == s.id && t == s.text) newText else t) } }
                if (out == paras && newText != s.text) throw ApiException("Предложение не найдено в книге")
                PdfBooks.writeSentences(app.ctx, b, out)
            }
            replaceSentence(s, newText, "")
            if (newTr.isNotEmpty()) { sentTr[s.id] = newTr; keepTr(s.id, newTr) }
            return
        }
        withContext(Dispatchers.IO) {
            val paras = app.api.editor(lang, lesson.id)
            val js = findSentence(paras, s) ?: throw ApiException("Предложение не найдено в уроке на LingQ — обновите урок")
            app.backups.save(lang, Lesson(lesson.id, title), JSONArray(paras.toString()))
            if (newText != s.text) {
                js.put("text", newText)
                // LingQ's own cleanText drops the bold markers (** __) and keeps the italic ones
                js.put("cleanText", newText.replace(Regex("\\*\\*|__"), ""))
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
    }

    /** Whether every word of tokens [from]..[to] of sentence [id] is bold. */
    fun isBold(id: Int, from: Int, to: Int): Boolean {
        val ws = sentById[id]?.tokens.orEmpty().filter { it.isWord && it.index in from..to }
        return ws.isNotEmpty() && ws.all { it.b }
    }

    private var boldBusy = false

    /**
     * Word / words [from]..[to] of sentence [id] made bold (**…** in the lesson text on LingQ) or, if all are bold already,
     * plain again. Bold spans touching the words are taken in, so markers never nest.
     */
    fun toggleBold(id: Int, from: Int, to: Int) {
        val s = sentById[id] ?: return
        if (boldBusy) return
        var pos = 0; var a = -1; var e = -1
        for (t in Sent.tokenize(s.text)) {
            if (t.index == from) a = pos
            pos += t.text.length
            if (t.index == to) e = pos
        }
        if (a < 0 || e < 0) return
        val on = !isBold(id, from, to)
        val del = HashSet<Int>()
        var aa = a; var ee = e
        for (x in Sent.emphSpans(s.text)) {
            if (x.mk.length == 1 || x.ce <= a || x.cs >= e) continue
            // *** keeps one star each side (italic stays)
            for (k in 0 until 2) { del += x.at + k; del += x.end - 1 - k }
            aa = minOf(aa, x.cs); ee = maxOf(ee, x.ce)
        }
        val out = StringBuilder()
        for (k in 0..s.text.length) {
            if (on && k == aa) out.append("**")
            if (on && k == ee) out.append("**")
            if (k < s.text.length && k !in del) out.append(s.text[k])
        }
        boldBusy = true
        AppLog.add("reader", "${if (on) "Жирный" else "Без жирного"}: предложение $id")
        app.scope.launch {
            try {
                writeSentence(s, out.toString(), shownTranslation(id))
                app.say(if (on) "Выделено жирным" else "Жирный снят")
            } catch (err: Throwable) {
                AppLog.error("reader", "Жирный", err)
                app.say("Не сохранилось: ${err.message ?: err}")
            }
            boldBusy = false
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

    /** The edited sentence replaces the old one on screen; its word pins move with the words or are dropped. */
    private fun replaceSentence(old: Sent, text: String, tr: String) {
        val trs = HashMap(old.translations).apply { if (tr.isNotEmpty()) put(target, tr) }
        val n = Sent(old.id, text, trs, old.lingqIndex, old.start, old.end, old.heading, old.aside)
        sentById[n.id] = n
        paragraphs = paragraphs?.map { p -> p.map { if (it.id == n.id) n else it } }
        sentences = sentences.map { if (it.id == n.id) n else it }
        if (tr.isNotEmpty()) sentTr[n.id] = tr else sentTr.remove(n.id)
        // the same words (only ** added or removed): pins stay, moved to the new token positions
        val ow = old.tokens.filter { it.isWord }
        val nw = n.tokens.filter { it.isWord }
        if (text != old.text && ow.size == nw.size && ow.indices.all { ow[it].text == nw[it].text }) {
            val m = ow.indices.associate { ow[it].index to nw[it].index }
            if (m.all { it.key == it.value }) return
            fun mv(r: WordRef) = if (r.sent != n.id) r else m[r.token]?.let { WordRef(r.sent, it) }
            pins.filterKeys { it.sent == n.id }.also { p -> p.keys.forEach { pins.remove(it) } }
                .forEach { (k, v) -> mv(k)?.let { pins[it] = v } }
            provisional.filterKeys { it.sent == n.id }.also { p -> p.keys.forEach { provisional.remove(it) } }
                .forEach { (k, v) -> mv(k)?.let { provisional[it] = v } }
            phrasePins.filterKeys { it.startsWith("${n.id}:") }.also { p -> p.keys.forEach { phrasePins.remove(it) } }
                .forEach { (k, v) ->
                    val q = k.split(':').drop(1).map { m[it.toIntOrNull() ?: -1] }
                    if (q.all { it != null }) phrasePins[(listOf(n.id) + q).joinToString(":")] = v
                }
            aiCache.keys.filter { it.sent == n.id }.forEach { aiCache.remove(it) }
            popup?.let { if (it.sent == n.id) popup = mv(it) }
            persist()
            return
        }
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
        title = { Text("Исправить предложение") },
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
                    (if (s.local) "Изменения сохранятся в книге на телефоне. "
                    else "Изменения сохранятся в уроке на LingQ (копия урока до правки — в «Резервных копиях»). ") +
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
    var delete by remember { mutableStateOf(false) }
    if (delete) LessonDeleteDialog(state, s) { delete = false }
    var words by remember { mutableStateOf(false) }
    if (words) MarkedWordsDialog(s) { words = false }
    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
    // stays inside the screen and scrolls (book mode hides the system bars, the window is then the whole screen)
    DropdownMenu(
        expanded = menu, onDismissRequest = { menu = false },
        modifier = Modifier.heightIn(max = menuMaxHeight()),
    ) {
        val hasPins = s.pins.isNotEmpty() || s.phrasePins.isNotEmpty()
        MenuGroup("Текст")
        FontSizeRow(s)
        DropdownMenuItem(
            text = { Text("Книжный режим") },
            leadingIcon = { Icon(AppIcons.Book, contentDescription = null) },
            trailingIcon = { androidx.compose.material3.Checkbox(checked = state.book, onCheckedChange = null) },
            onClick = { menu = false; state.applyPrefs { readerBook = !state.book } },
        )
        if (s.pdfLink != null) DropdownMenuItem(
            text = { Text(if (s.showPdf) "Показать текстом" else "Показать страницы PDF") },
            leadingIcon = { Icon(AppIcons.Description, contentDescription = null) },
            onClick = { menu = false; s.showPdf = !s.showPdf },
        )
        if (s.pdfLink != null && s.showPdf) DropdownMenuItem(
            text = { Text("Страница PDF целиком") },
            leadingIcon = { Icon(AppIcons.Description, contentDescription = null) },
            trailingIcon = { androidx.compose.material3.Checkbox(checked = state.pdfFit == "page", onCheckedChange = null) },
            onClick = { menu = false; state.applyPrefs { pdfFit = if (state.pdfFit == "page") "width" else "page" } },
        )
        DropdownMenuItem(
            text = { Text("Настройки чтения…") },
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = { menu = false; state.go(Screen.SettingsReading) },
        )
        HorizontalDivider()
        MenuGroup("Переводы")
        DropdownMenuItem(
            text = { Text(s.trAll?.let { "Перевожу урок: $it" } ?: "Перевести весь урок") },
            leadingIcon = { Icon(AppIcons.Translate, contentDescription = null) },
            enabled = s.trAll == null,
            onClick = { menu = false; s.translateAll() },
        )
        if (hasPins) DropdownMenuItem(
            text = { Text("Слова с переводом (${s.markedWords().size})") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            onClick = { menu = false; words = true },
        )
        if (hasPins || s.hideTr) DropdownMenuItem(
            text = { Text(if (s.hideTr) "Показать переводы над словами" else "Временно скрыть переводы") },
            onClick = { menu = false; s.toggleHideTr() },
        )
        if (hasPins) DropdownMenuItem(
            text = { Text("Снять переводы над словами (${s.pins.size + s.phrasePins.size})") },
            onClick = { s.clearPins(); menu = false },
        )
        HorizontalDivider()
        MenuGroup("Урок")
        if (s.audioUrl != null) DropdownMenuItem(
            text = { Text("Слушать в плеере") },
            leadingIcon = { Icon(AppIcons.Headphones, contentDescription = null) },
            onClick = { menu = false; state.playLesson(s.lang, Lesson(s.lesson.id, s.title, s.lesson.imageUrl, courseTitle = s.lesson.courseTitle)) },
        )
        // LingQ lesson only (a PDF book on the phone has no lesson on LingQ)
        if (!s.local) DropdownMenuItem(
            text = { Text(if (s.levelBusy) "Определяю уровень…" else "Определить уровень (ИИ)") },
            leadingIcon = { Icon(AppIcons.BarChart, contentDescription = null) },
            enabled = !s.levelBusy,
            onClick = { menu = false; s.detectLevel() },
        )
        if (!s.local) DropdownMenuItem(
            text = { Text(s.aiClean?.let { "ИИ обрабатывает текст: $it" } ?: "Обработать текст с ИИ…") },
            leadingIcon = { Icon(AppIcons.AutoFix, contentDescription = null) },
            enabled = s.aiClean == null,
            onClick = { menu = false; s.askAiClean = true },
        )
        if (!s.local) DropdownMenuItem(
            text = { Text("Исправить разбивку…") },
            leadingIcon = { Icon(Icons.Filled.Build, contentDescription = null) },
            onClick = { menu = false; state.checkLessons(s.lang, listOf(Lesson(s.lesson.id, s.title)), s.title) },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Как пользоваться") },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = { menu = false; help = true },
        )
        DropdownMenuItem(
            text = { Text("Отзыв / задача") },
            leadingIcon = { Icon(AppIcons.Chat, contentDescription = null) },
            onClick = { menu = false; s.openFeedback() },
        )
        // the only action that cannot be undone: last, apart, in red
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (s.local) "Удалить книгу…" else "Удалить урок…", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { menu = false; delete = true },
        )
    }
}

/** Max height of a ⋮ menu: the screen minus room for the top bar, so a long menu scrolls instead of leaving the screen. */
@Composable
internal fun menuMaxHeight(): androidx.compose.ui.unit.Dp =
    (LocalConfiguration.current.screenHeightDp - 96).coerceAtLeast(200).dp

/** Words and phrases of the lesson with a translation: word — translation; a tap goes to its sentence. */
@Composable
private fun MarkedWordsDialog(s: ReaderSession, close: () -> Unit) {
    val list = remember(s.pins.size, s.phrasePins.size) { s.markedWords() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = close,
        title = { Text("Слова с переводом (${list.size})") },
        text = {
            if (list.isEmpty()) Text("Пока нет слов с переводом. Нажмите на слово в тексте — его перевод появится над ним и попадёт сюда.")
            else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(list.size) { i ->
                    val (w, tr, sent) = list[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { close(); s.jumpTo(sent) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(w, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            tr, Modifier.weight(1f).padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyMedium, color = LocalHighlight.current.text,
                        )
                    }
                    if (i < list.size - 1) HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Закрыть") } },
    )
}

/** Moves a translation above a word closer to it: [gap] % of the normal gap (100 = unchanged). */
internal fun Modifier.trPull(fontSp: Float, gap: Int): Modifier = if (gap >= 100) this else this.then(
    Modifier.layout { m, c ->
        val p = m.measure(c)
        val px = ((100 - gap) / 100f * fontSp * 0.45f).sp.roundToPx()
        layout(p.width, (p.height - px).coerceAtLeast(0)) { p.place(0, px) }
    },
)

/** Text size in a menu as one row "Размер текста  A−  18  A+"; the menu stays open for several taps. */
@Composable
private fun FontSizeRow(s: ReaderSession) {
    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Размер текста", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { s.changeFont(-2) }) { Text("A−") }
        Text("${s.font}", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { s.changeFont(+2) }) { Text("A+") }
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
        "PDF-книга" to listOf(
            "PDF-книга хранится только на телефоне (в LingQ не загружается) и показывает страницы PDF как есть; " +
                "слова на них работают как в тексте: касание, двойное касание, долгое нажатие.",
            "Переводы слов, фраз и предложений делает Gemini (ваш ключ, лёгкая модель); они запоминаются в книге и второй раз не запрашиваются.",
            "Страница открывается по ширине экрана: вверх-вниз — двигать страницу, влево-вправо — листать; " +
                "⋮ → «Страница PDF целиком» — вся страница на экране.",
            "Два пальца — увеличить страницу, одним пальцем — двигать; двойное касание мимо слов — увеличить / вернуть.",
            "⋮ → «Показать текстом» — обычный текст урока, «Показать страницы PDF» — обратно.",
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
            "⋮ → «Настройки чтения…»: фон страницы, цвет выделения слов, расстояния, сколько экран не гаснет.",
            "⋮ → «Текст»: размер текста (A− / A+), книжный режим. «Переводы»: перевести весь урок, слова с переводом, снять переводы. «Урок»: слушать в плеере, уровень, обработать текст с ИИ, исправить разбивку. Удалить урок — в самом низу.",
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
    if (s.askAiClean) androidx.compose.material3.AlertDialog(
        onDismissRequest = { s.askAiClean = false },
        icon = { Icon(AppIcons.AutoFix, contentDescription = null) },
        title = { Text("Обработать текст с ИИ?") },
        text = {
            Text(
                "ИИ почистит текст урока, как при импорте: уберёт номера страниц, колонтитулы, сноски и мусор, " +
                    "склеит разорванные строки. Урок на LingQ перезапишется. Неизменённые предложения сохранят перевод " +
                    "и время аудио, у изменённых их не будет. Копия старого урока останется на телефоне (резервные копии).",
            )
        },
        confirmButton = { TextButton(onClick = { s.askAiClean = false; s.cleanWithAi() }) { Text("Обработать") } },
        dismissButton = { TextButton(onClick = { s.askAiClean = false }) { Text("Отмена") } },
    )
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
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val pdf = s.pdfLink
                if (pdf != null && s.showPdf) PdfReader(s, pdf) else PagedText(s, paras)
            }
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

/**
 * Part of a paragraph that lies on one page: the first sentence from chunk [first], the last one up to chunk
 * [last] (exclusive; -1 = to its end). A sentence that does not fit is split at a line, its rest starts the next page.
 */
private class Block(val sents: List<Sent>, val first: Int = 0, val last: Int = -1, val start: Boolean = false)

/** Key of a chunk in the hyphenation map: sentence id + chunk index. */
private fun splitKey(sent: Int, chunk: Int) = (sent.toLong() shl 20) or chunk.toLong()

/** Book pages: words hyphenated at a line end by the page layout ([TextMetrics.splits]). */
private val LocalSplits = androidx.compose.runtime.staticCompositionLocalOf<Map<Long, Int>> { emptyMap() }

/** Layout ids of a hyphenated chunk in [HyphFlow]: the whole word, its first part with the hyphen, the rest. */
private const val HY_FULL = "hyFull"
private const val HY_HEAD = "hyHead"
private const val HY_TAIL = "hyTail"

/**
 * Book mode text flow (like FlowRow with [JustifyLoose], items at the line bottom, lines 2 dp apart). A hyphenated
 * chunk comes as three items [HY_FULL], [HY_HEAD], [HY_TAIL]: the whole word if it fits the line, else its first part
 * ends the line and the rest starts the next one, else the whole word goes to the next line.
 */
@Composable
private fun HyphFlow(justify: Boolean, continues: Boolean, content: @Composable () -> Unit) {
    val vGap = with(LocalDensity.current) { 2.dp.roundToPx() }
    androidx.compose.ui.layout.Layout(content) { ms, c ->
        val maxW = if (c.hasBoundedWidth) c.maxWidth else Int.MAX_VALUE
        val ps = ms.map { it.measure(Constraints(maxWidth = maxW)) }
        val ids = ms.map { it.layoutId }
        val lines = mutableListOf(mutableListOf<Int>())
        var lw = 0
        fun put(i: Int) {
            if (lines.last().isNotEmpty() && lw + ps[i].width > maxW) { lines += mutableListOf<Int>(); lw = 0 }
            lines.last() += i; lw += ps[i].width
        }
        var i = 0
        while (i < ps.size) {
            if (ids[i] == HY_FULL && i + 2 < ps.size) {
                if (lines.last().isNotEmpty() && lw + ps[i].width > maxW && lw + ps[i + 1].width <= maxW) {
                    lines.last() += i + 1
                    lines += mutableListOf(i + 2); lw = ps[i + 2].width
                } else put(i)
                i += 3
            } else { put(i); i++ }
        }
        val width = if (maxW != Int.MAX_VALUE) maxW else lines.maxOf { l -> l.sumOf { ps[it].width } }
        val heights = lines.map { l -> l.maxOfOrNull { ps[it].height } ?: 0 }
        val total = heights.sum() + vGap * maxOf(0, lines.size - 1)
        layout(width, total) {
            var y = 0
            lines.forEachIndexed { li, l ->
                val free = width - l.sumOf { ps[it].width }
                val gaps = l.size - 1
                val last = li == lines.lastIndex && !continues
                val step = if (justify && !last && gaps > 0 && free > 0 && free <= width * 0.2f) free.toFloat() / gaps else 0f
                var x = 0f
                l.forEach { k -> ps[k].place(Math.round(x), y + heights[li] - ps[k].height); x += ps[k].width + step }
                y += heights[li] + vGap
            }
        }
    }
}

/** Line height in font sizes: book mode leaves room for the translations above the words. */
private fun lineMul(book: Boolean, pct: Int = 100) = (if (book) 1.55f else 1.35f) * pct / 100f

/** First-line indent of a paragraph in book mode, in font sizes. */
private const val INDENT_EM = 1.5f

/** Label above a caption / box paragraph of a PDF book. */
private val ASIDE_LABEL_STYLE = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)

/**
 * Heights of text pieces as [Paragraph] / [SentenceFlow] will draw them, computed from text sizes only
 * (composing the whole lesson just to measure it runs out of memory on long lessons).
 * Keep in step with those composables. Not thread-safe: used under one lock.
 */
private class TextMetrics(
    private val m: TextMeasurer, private val d: Density, font: Int, private val width: Int, textStyle: TextStyle,
    /** Text widths, kept between rebuilds while the font is the same. */
    private val widths: HashMap<String, Int>,
    /** Book mode: taller lines (room for the translations above words), first line of a paragraph indented. */
    book: Boolean = false,
    /** Line spacing, % of the usual one. */
    spacing: Int = 100,
    /** Lesson language: its hyphenation rules (book mode). */
    lang: String = "de",
) {
    private val size = font.toFloat()
    private val text = textStyle.copy(fontSize = size.sp, lineHeight = (size * lineMul(book, spacing)).sp)
    /** Book mode: words hyphenated at a line end, [splitKey] -> letters of the word left on the line (see [HyphFlow]). */
    val splits = HashMap<Long, Int>()
    private val hyph = book
    private val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = with(d) { size.sp.toPx() }
        textLocale = java.util.Locale.forLanguageTag(lang)
    }

    /**
     * Book mode: chunk [c] does not fit into the [room] px left on the line: how many letters of its word stay there
     * with a hyphen (or at a hyphen of the word itself), -1 = none. Break points from Android's hyphenator.
     */
    private fun hyphenAt(c: List<Token>, room: Int, bold: Boolean): Int {
        val wi = c.indices.singleOrNull { c[it].isWord } ?: return -1
        val word = c[wi].text
        if (word.length < 5) return -1
        val pre = c.take(wi).joinToString("") { it.text }
        var avail = room - (if (pre.isEmpty()) 0 else w(pre, bold))
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        repeat(3) {
            if (avail < lineH) return -1
            val l = android.text.StaticLayout.Builder.obtain(word, 0, word.length, paint, avail)
                .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_FULL)
                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE).setIncludePad(false).build()
            if (l.lineCount < 2) return -1
            val n = l.getLineEnd(0)
            if (n < 2 || word.length - n < 2) return -1
            val dash = word[n - 1] == '-'
            // a hyphen drawn at the line end makes the line wider than its letters (a forced break inside a word does not)
            if (!dash && l.getLineWidth(0) < paint.measureText(word, 0, n) + paint.measureText("-") * 0.5f) return -1
            // the app draws with Compose: check the width there, else try a shorter piece
            val over = w(pre + word.take(n) + (if (dash) "" else "-"), bold) - room
            if (over <= 0) return n
            avail -= over + 1
        }
        return -1
    }
    /** Width of the first-line indent of a paragraph (book mode), 0 = none. */
    val indent = if (book) px(size * INDENT_EM) else 0
    private fun px(dp: Float) = with(d) { dp.dp.roundToPx() }
    private val lineH = m.measure("Ag", text, maxLines = 1).size.height
    /** Height of the caption / box label above such a paragraph (PDF book) with the 6 dp gap below it. */
    val labelH = m.measure("Подпись", ASIDE_LABEL_STYLE, maxLines = 1).size.height + px(6f)
    private val chunkGap = px(size * 0.3f)
    private val lineGap = px(2f)

    private val bold = text.copy(fontWeight = FontWeight.Bold)
    private fun w(t: String, heading: Boolean = false) =
        if (heading) widths.getOrPut("$t") { m.measure(t, bold, maxLines = 1, softWrap = false).size.width }
        else widths.getOrPut(t) { m.measure(t, text, maxLines = 1, softWrap = false).size.width }

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
                val cw = w(c.joinToString("") { it.text }, sent.heading || c.any { it.b }) + chunkGap
                if (lineW > 0 && lineW + cw > width) { total += lineMax; lines++; lineW = 0; lineMax = 0 }
                lineW += cw; lineMax = maxOf(lineMax, lineH)
            }
            val open = if (lineW > 0) 1 else 0
            out[i] = total + (if (open == 1) lineMax else 0) + lineGap * maxOf(0, lines + open - 1)
        }
        return out
    }

    /**
     * Same flow, chunk by chunk: result[i][c] = height of everything up to chunk c of sents[i]; sents[0] starts at
     * chunk [start]. A sentence without chunks gets one entry (the height so far).
     */
    fun chunkBottoms(sents: List<Sent>, start: Int, indent: Int = 0): List<IntArray> {
        var total = 0
        var lineW = indent
        var lineMax = 0
        var lines = 0
        fun now() = total + (if (lineW > 0) lineMax + lineGap * lines else maxOf(0, lineGap * (lines - 1)))
        return sents.mapIndexed { i, sent ->
            val cs = if (i == 0) sent.chunks.drop(start) else sent.chunks
            if (cs.isEmpty()) intArrayOf(now())
            else IntArray(cs.size) { k ->
                val bold = sent.heading || cs[k].any { it.b }
                val key = splitKey(sent.id, if (i == 0) k + start else k)
                val cw = w(cs[k].joinToString("") { it.text }, bold) + chunkGap
                var split = -1
                if (lineW > 0 && lineW + cw > width) {
                    // book mode: a long word is hyphenated instead of leaving the line end empty
                    if (hyph) split = hyphenAt(cs[k], width - lineW - chunkGap, bold)
                    total += lineMax; lines++; lineW = 0; lineMax = 0
                }
                if (split > 0) {
                    splits[key] = split
                    val wi = cs[k].indexOfFirst { it.isWord }
                    lineW = w(cs[k][wi].text.drop(split) + cs[k].drop(wi + 1).joinToString("") { it.text }, bold) + chunkGap
                } else {
                    splits.remove(key)
                    lineW += cw
                }
                lineMax = maxOf(lineMax, lineH)
                now()
            }
        }
    }
}

/**
 * Pages from the bare text: a paragraph is split between pages at a sentence boundary; only a sentence taller
 * than a page is split at a line.
 */
private suspend fun paginate(paras: List<List<Sent>>, metrics: TextMetrics, h: Int, gap: Int, cut: Boolean = false): List<List<Block>> {
    // book mode: a paragraph starts with an indent (not a heading, not the rest of a paragraph from the last page)
    fun ind(para: List<Sent>, from: Int, fromChunk: Int) =
        if (from == 0 && fromChunk == 0 && !para[0].heading && para[0].aside.isEmpty()) metrics.indent else 0
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
        var fromChunk = 0
        while (from < para.size) {
            // heights up to every chunk of para[from..], in one pass
            val start = from == 0 && fromChunk == 0
            val cb0 = metrics.chunkBottoms(para.subList(from, para.size), fromChunk, ind(para, from, fromChunk))
            // PDF book: a caption / box paragraph has a small label above its start
            val lab = if (start && para[0].aside.isNotEmpty()) metrics.labelH else 0
            val cb = if (lab == 0) cb0 else cb0.map { a -> IntArray(a.size) { a[it] + lab } }
            val hRest = cb.last().last()
            if (used + need(hRest) <= h) { used += need(hRest); cur += Block(para.subList(from, para.size), fromChunk, start = start); break }
            // the last chunk that still fits on this page
            var bj = -1
            var bc = -1
            var bh = 0
            run {
                cb.forEachIndexed { j, arr ->
                    arr.forEachIndexed { c, hh -> if (used + need(hh) > h) return@run; bj = j; bc = c; bh = hh }
                }
            }
            if (bj < 0) {
                if (cur.isNotEmpty()) { flush(); continue }
                bj = 0; bc = 0; bh = cb[0][0]                                  // one chunk taller than a page
            }
            val sent = para[from + bj]
            val end = (if (bj == 0) fromChunk else 0) + bc + 1              // chunks of that sentence on this page
            if (end < sent.chunks.size && !cut) {
                // a sentence is not cut (the user's wish; book mode [cut] lets it go on on the next page like in a book):
                // it goes to the next page whole, unless it alone is taller than a page
                if (bj > 0) {
                    used += need(cb[bj - 1].last())
                    cur += Block(para.subList(from, from + bj), fromChunk, start = start)
                    from += bj; fromChunk = 0
                    flush(); continue
                }
                if (cur.isNotEmpty()) { flush(); continue }
            }
            used += need(bh)
            if (end >= sent.chunks.size) {
                cur += Block(para.subList(from, from + bj + 1), fromChunk, start = start)
                from += bj + 1; fromChunk = 0
            } else {
                cur += Block(para.subList(from, from + bj + 1), fromChunk, end, start = start)
                from += bj; fromChunk = end
            }
            flush()
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
    val widths = remember(s.font, density, s.lineSpacing) { HashMap<String, Int>() }
    val lock = remember { Mutex() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padH = with(density) { sidePad(s).roundToPx() }
        val padV = with(density) { 12.dp.roundToPx() }
        val book = s.book
        val gap = with(density) { s.paraGap.roundToPx() }
        val spacing = s.lineSpacing
        val w = (constraints.maxWidth - 2 * padH).coerceAtLeast(1)
        val h = if (constraints.hasBoundedHeight) ((constraints.maxHeight - 2 * padV) * 0.99f).toInt() else Int.MAX_VALUE
        val font = s.font
        // the old pages stay on screen until the new ones are ready
        val pages by produceState<Pair<List<List<Block>>, Map<Long, Int>>?>(null, paras, font, w, h, gap, density, book, spacing) {
            value = withContext(Dispatchers.Default) {
                lock.withLock {
                    val m = TextMetrics(measurer, density, font, w, textStyle, widths, book, spacing, s.lang)
                    paginate(paras, m, h, gap, cut = book) to HashMap(m.splits)
                }
            }
        }
        pages?.let { (p, sp) -> androidx.compose.runtime.CompositionLocalProvider(LocalSplits provides sp) { TextPager(s, p) } }
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
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(horizontal = sidePad(s), vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(s.paraGap),
            ) {
                latest.getOrNull(i)?.forEach { b -> Paragraph(s, b.sents, b.first, b.last, b.start && s.book, label = b.start) }
            }
        }
        if (s.wide && !s.book) TurnButtons(pager)
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

/**
 * A paragraph flows as one text; a shown sentence translation is inserted right after its sentence (after its end:
 * a sentence cut at the page bottom gets it on the next page). [first] / [last]: chunk range, see [Block].
 */
@Composable
private fun Paragraph(s: ReaderSession, para: List<Sent>, first: Int = 0, last: Int = -1, indent: Boolean = false, label: Boolean = false) {
    val aside = para.firstOrNull()?.aside.orEmpty()
    val ind = if (indent && para.firstOrNull()?.heading == false && aside.isEmpty()) (s.font * INDENT_EM).dp else 0.dp
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // PDF book: what this paragraph is (display only; measured in [TextMetrics.labelH])
        if (label && aside.isNotEmpty()) Text(
            if (aside == "c") "Подпись к картинке" else "Врезка", style = ASIDE_LABEL_STYLE,
            color = MaterialTheme.colorScheme.outline, maxLines = 1,
        )
        var run = mutableListOf<Sent>()
        var runStart = 0
        para.forEachIndexed { i, sent ->
            run += sent
            val cut = i == para.lastIndex && last >= 0
            if (!cut && s.sentenceShown(sent.id)) {
                SentenceFlow(s, run, from = if (runStart == 0) first else 0, justify = s.justify, indent = if (runStart == 0) ind else 0.dp)
                SentenceTranslation(s, sent.id)
                run = mutableListOf(); runStart = i + 1
            }
        }
        if (run.isNotEmpty()) SentenceFlow(
            s, run, from = if (runStart == 0) first else 0, to = last, justify = s.justify,
            continues = last >= 0, indent = if (runStart == 0) ind else 0.dp,
        )
    }
}

@Composable
internal fun SentenceFlow(
    s: ReaderSession, sents: List<Sent>, scale: Float = 1f, area: String = "text", center: Boolean = false,
    /** First sentence from this chunk, last one up to this chunk (exclusive, -1 = all): a page holds part of it. */
    from: Int = 0, to: Int = -1,
    /** Words spread over each line; the last line stays left unless the text [continues] on the next page. */
    justify: Boolean = false, continues: Boolean = false,
    /** First-line indent (book mode). */
    indent: Dp = 0.dp,
) {
    val size = s.font * scale
    val haptic = LocalHapticFeedback.current
    val selBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val playColor = MaterialTheme.colorScheme.primary
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp, lineHeight = (size * lineMul(s.book && area == "text", s.lineSpacing)).sp)
    val trStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (size * 0.6).sp, lineHeight = (size * 0.68).sp)
    // book pages: long words hyphenated at the line end
    val hy = s.book && area == "text" && !center
    val splits = LocalSplits.current
    val items: @Composable (Modifier) -> Unit = { bottom ->
        if (indent > 0.dp) androidx.compose.foundation.layout.Spacer(Modifier.width(indent))
        sents.forEachIndexed { si, sent ->
            val lo = if (si == 0) from.coerceIn(0, sent.chunks.size) else 0
            val hi = if (si == sents.lastIndex && to >= 0) to.coerceIn(lo, sent.chunks.size) else sent.chunks.size
            if (lo == 0 && s.isBookmarked(sent.id)) Icon(
                AppIcons.Bookmark, contentDescription = "Закладка", tint = MaterialTheme.colorScheme.primary,
                modifier = bottom.padding(end = 2.dp, bottom = 4.dp).size((size * 0.9).dp),
            )
            val phr = s.phrasesIn(sent.id)
            val sounding = s.playingSent == sent.id && AudioEngine.key == s.audioKey
            val ts = if (sounding || sent.heading) textStyle.copy(fontWeight = FontWeight.Bold) else textStyle
            // a chunk belongs to a phrase by its words, or by a dash standing strictly inside it ("Nürnberg – Wiege")
            fun phraseOf(c: List<Token>) = phr.firstOrNull { (r, _) ->
                c.any { if (it.isWord) it.index in r else it.index > r.first && it.index < r.last }
            }
            var i = 0
            val chunks = sent.chunks.subList(lo, hi)
            while (i < chunks.size) {
                val ph = phraseOf(chunks[i])
                if (ph == null) {
                    val c = chunks[i]
                    val pad = bottom.padding(end = (size * 0.3).dp)
                    val n = if (hy) splits[splitKey(sent.id, lo + i)] ?: -1 else -1
                    val wi = c.indexOfFirst { it.isWord }
                    if (n > 0 && wi >= 0 && n < c[wi].text.length) {
                        // hyphenated by the page layout: whole word, first part with a hyphen, rest ([HyphFlow] picks)
                        val wt = c[wi]
                        val dash = wt.text[n - 1] == '-'
                        val head = c.take(wi) + Token(wt.text.take(n) + (if (dash) "" else "-"), true, wt.index, wt.b, wt.i)
                        val tail = listOf(Token(wt.text.drop(n), true, wt.index, wt.b, wt.i)) + c.drop(wi + 1)
                        ChunkRow(s, sent, c, area, ts, trStyle, selBg, haptic, null, pad.layoutId(HY_FULL))
                        ChunkRow(s, sent, head, area, ts, trStyle, selBg, haptic, null, pad.layoutId(HY_HEAD))
                        ChunkRow(s, sent, tail, area, ts, trStyle, selBg, haptic, null, pad.layoutId(HY_TAIL), noTr = true)
                    } else ChunkRow(s, sent, c, area, ts, trStyle, selBg, haptic, null, pad)
                    i++
                    continue
                }
                // several words translated together: one translation above all of them
                var j = i
                while (j < chunks.size && phraseOf(chunks[j]) == ph) j++
                val group = chunks.subList(i, j)
                val pk = "${sent.id}:${ph.first.first}:${ph.first.last}"
                val hlPhrase = LocalHighlight.current.bg
                Column(
                    bottom.padding(end = (size * 0.3).dp)
                        // one highlight over the translation and all the words (as for a single word)
                        .drawBehind { if (s.trVisible(pk)) drawRoundRect(hlPhrase, cornerRadius = CornerRadius(4.dp.toPx())) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val pa by animateFloatAsState(if (s.trVisible(pk)) 1f else 0f, tween(600), label = "phraseTr")
                    Text(
                        ph.second.ifEmpty { "…" }, style = trStyle, maxLines = 3, textAlign = TextAlign.Center,
                        color = LocalHighlight.current.text,
                        modifier = Modifier.trPull(size, s.trGap).graphicsLayer { alpha = pa }.padding(horizontal = 1.dp),
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
    if (hy) HyphFlow(justify, continues) { items(Modifier) }
    else FlowRow(
        horizontalArrangement = if (center) Arrangement.Center else if (justify) JustifyLoose else Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(Modifier.align(Alignment.Bottom))
        // justified: the last line stays left (a weighted empty box takes the rest of that line)
        if (justify && !continues) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
    }
}

/**
 * Justified lines like an e-book reader: the words are spread over the line, but a line with much room left
 * (two or three long words) stays left instead of getting huge gaps.
 */
private object JustifyLoose : Arrangement.Horizontal {
    override fun androidx.compose.ui.unit.Density.arrange(
        totalSize: Int, sizes: IntArray, layoutDirection: androidx.compose.ui.unit.LayoutDirection, outPositions: IntArray,
    ) {
        val free = totalSize - sizes.sum()
        val gaps = sizes.size - 1
        val step = if (gaps > 0 && free > 0 && free <= totalSize * 0.2f) free.toFloat() / gaps else 0f
        var x = 0f
        sizes.forEachIndexed { i, sz -> outPositions[i] = Math.round(x); x += sz + step }
    }
}

/** Style of a token: bold / italic from ** / * in the lesson text. */
private fun TextStyle.emph(t: Token): TextStyle = when {
    t.b && t.i -> copy(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
    t.b -> copy(fontWeight = FontWeight.Bold)
    t.i -> copy(fontStyle = FontStyle.Italic)
    else -> this
}

/** One chunk of a sentence (a word with its punctuation); [phrase] = token range of a pinned phrase it belongs to. */
@Composable
private fun ChunkRow(
    s: ReaderSession, sent: Sent, chunk: List<Token>, area: String,
    textStyle: TextStyle, trStyle: TextStyle, selBg: Color, haptic: HapticFeedback, phrase: IntRange?, modifier: Modifier,
    /** Rest of a hyphenated word: highlighted, but its translation is shown above the first part only. */
    noTr: Boolean = false,
) {
    // bottom-aligned: a translation above one word pushes the whole line down
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        for (t in chunk) {
            val ref = WordRef(sent.id, t.index)
            val inPhrase = phrase != null && t.index in phrase
            val tr = if (t.isWord && !inPhrase) s.pins[ref] else null
            // a phrase with its translation shown has one highlight over the whole block (drawn by the caller)
            val block = phrase != null && s.trVisible("${sent.id}:${phrase.first}:${phrase.last}")
            val pinned = tr != null || (inPhrase && !block)
            val trShown = if (noTr) null else tr
            val hl = LocalHighlight.current.bg
            if (t.isWord) DisposableEffect(area, ref) { onDispose { s.wordCoords.remove(area to ref) } }
            // the highlight is drawn: dragging a selection or opening the word window only redraws
            val mark = Modifier.drawBehind {
                val c = when {
                    s.inSelection(ref) -> selBg
                    t.isWord && (pinned || s.popup == ref || s.pairHighlighted(ref)) -> hl
                    !t.isWord && !block && phrase != null && t.index > phrase.first && t.index < phrase.last -> hl
                    else -> null
                }
                if (c != null) {
                    if (t.isWord) drawRoundRect(c, cornerRadius = CornerRadius(4.dp.toPx())) else drawRect(c)
                }
            }
            if (s.book && area == "text" && trShown != null) {
                // book mode: the translation lies in the gap above the line, the lines do not move apart
                val a by animateFloatAsState(if (s.trVisible(ref.key())) 1f else 0f, tween(600), label = "wordTr")
                val lift = with(LocalDensity.current) { (textStyle.fontSize.value * 0.24f).sp.roundToPx() }
                val trColor = if (s.provisional[ref] == true) MaterialTheme.colorScheme.outline else LocalHighlight.current.text
                androidx.compose.ui.layout.Layout(content = {
                    Text(
                        t.text, style = textStyle.emph(t),
                        modifier = mark.onPlaced { s.wordCoords[area to ref] = it }
                            .pointerInput(ref, area) { wordGestures(s, area, ref, haptic) },
                    )
                    Text(
                        trShown.ifEmpty { "…" }, style = trStyle.copy(fontSize = trStyle.fontSize * 0.92f), maxLines = 1,
                        softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = trColor,
                        modifier = Modifier.graphicsLayer { alpha = a },
                    )
                }) { ms, c ->
                    val w = ms[0].measure(c)
                    val tw = ms[1].measure(Constraints(maxWidth = maxOf(w.width * 3, c.maxWidth / 2).coerceAtMost(c.maxWidth)))
                    layout(w.width, w.height) {
                        w.place(0, 0)
                        tw.place((w.width - tw.width) / 2, lift - tw.height)
                    }
                }
            } else {
            // a word with its translation above: one highlight over both (as on the iPad); hidden translations: the word only
            val withTr = trShown != null && s.trVisible(ref.key())
            Column(if (withTr) mark else Modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                if (trShown != null) {
                    val a by animateFloatAsState(if (s.trVisible(ref.key())) 1f else 0f, tween(600), label = "wordTr")
                    Text(
                        trShown.ifEmpty { "…" }, style = trStyle, maxLines = 2,
                        color = if (s.provisional[ref] == true) MaterialTheme.colorScheme.outline else LocalHighlight.current.text,
                        modifier = Modifier.trPull(textStyle.fontSize.value, s.trGap).graphicsLayer { alpha = a }.padding(horizontal = 1.dp),
                    )
                }
                val own = if (withTr) Modifier else mark
                Text(
                    t.text, style = textStyle.emph(t),
                    modifier = if (!t.isWord) own else own
                        .onPlaced { s.wordCoords[area to ref] = it }
                        .pointerInput(ref, area) { wordGestures(s, area, ref, haptic) },
                )
            }
            }
        }
    }
}

@Composable
internal fun SentenceTranslation(
    s: ReaderSession, id: Int, scale: Float = 1f, center: Boolean = false, area: String = "text",
    /** Tap on the translation; default: hide it again (not when all sentences are shown). */
    onTap: (() -> Unit)? = null,
) {
    LaunchedEffect(id) { s.ensureSentence(id) }
    val tr = s.sentTr[id]
    Row(
        Modifier.fillMaxWidth()
            .onPlaced { s.trCoords[area to id] = it }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable { if (onTap != null) onTap() else if (!s.allSentences) s.toggleSentence(id) }
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
    // book mode: a double tap opens this menu, so the word window is here too
    if (s.book) DropdownMenuItem(
        text = { Text("Перевод слова") },
        leadingIcon = { Icon(AppIcons.Translate, contentDescription = null) },
        onClick = { close(); s.openPopup(ref) },
    )
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
    if (s.book) DropdownMenuItem(
        text = { Text(if (s.isBold(id, ref.token, ref.token)) "Убрать жирный" else "Сделать жирным") },
        leadingIcon = { Text("Ж", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) },
        onClick = { close(); s.toggleBold(id, ref.token, ref.token) },
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
    // a swipe down (from the top bar, or on the sentence when it is not scrolled) collapses the mode
    val closeAt = with(LocalDensity.current) { 110.dp.toPx() }
    val pullDown = remember(s) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            var pull = 0f
            override fun onPostScroll(
                consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): Offset {
                if (available.y > 0f) pull += available.y else if (consumed.y != 0f || available.y < 0f) pull = 0f
                return Offset.Zero
            }
            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                if (pull > closeAt) s.closeSentenceMode()
                pull = 0f
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        var headPull by remember { mutableStateOf(0f) }
        Box(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp).pointerInput(s) {
                detectVerticalDragGestures(
                    onDragStart = { headPull = 0f },
                    onDragEnd = { if (headPull > closeAt * 0.6f) s.closeSentenceMode(); headPull = 0f },
                ) { _, dy -> headPull += dy }
            },
        ) {
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
            Box(Modifier.weight(1f).fillMaxHeight().nestedScroll(pullDown)) {
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
                                SentenceFlow(s, listOf(sent), area = "sent", center = true)
                                SmTranslationToggle(s)
                                AnimatedVisibility(
                                    visible = s.smTranslation,
                                    enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                                    exit = shrinkVertically(tween(300)) + fadeOut(tween(300)),
                                ) {
                                    // a tap on the translation folds it (as "Скрыть перевод")
                                    SentenceTranslation(s, sent.id, center = true, area = "sent", onTap = { s.toggleSmTranslation() })
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
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = menuMaxHeight())) {
            DropdownMenuItem(
                text = { Text("Исправить предложение") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { open = false; s.startEdit(id) },
            )
            if (s.hasTime(id)) DropdownMenuItem(
                text = { Text("Слушать отсюда") },
                leadingIcon = { Icon(AppIcons.Headphones, contentDescription = null) },
                onClick = { open = false; s.listenFrom(id) },
            )
            HorizontalDivider()
            FontSizeRow(s)
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Отзыв / задача") },
                leadingIcon = { Icon(AppIcons.Chat, contentDescription = null) },
                onClick = { open = false; s.openFeedback() },
            )
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                BoldChip(s, ref.sent, ref.token, ref.token)
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
            else {
                second.consume(); waitForUpOrCancellation()?.consume()
                // book mode: the word menu (word / sentence translation…) instead of the word window
                if (s.book && area == "text") { s.menuArea = area; s.wordMenu = ref } else s.openPopup(ref)
            }
            return@awaitEachGesture
        }
        if (s.book && area == "text") return@awaitEachGesture      // book mode: a long press does nothing
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

/** "Жирным" / "Убрать жирный": ** around the words in the lesson text on LingQ. */
@Composable
private fun BoldChip(s: ReaderSession, id: Int, from: Int, to: Int) {
    val on = s.isBold(id, from, to)
    AssistChip(
        onClick = { s.toggleBold(id, from, to) },
        label = { Text(if (on) "Убрать жирный" else "Жирным", fontWeight = if (on) null else FontWeight.Bold) },
    )
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tr.isNullOrEmpty()) AssistChip(
                    onClick = { s.ensureSentence(p.sent) }, label = { Text("Перевод предложения") },
                    leadingIcon = { Icon(AppIcons.Translate, contentDescription = null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { s.chatWord(p.sent, p.text) }, label = { Text("Спросить") },
                    leadingIcon = { Icon(AppIcons.Chat, contentDescription = null, Modifier.size(18.dp)) },
                )
                BoldChip(s, p.sent, p.from, p.to)
            }
        }
}
