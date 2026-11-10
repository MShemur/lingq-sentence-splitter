@file:OptIn(ExperimentalFoundationApi::class)

package com.mshemur.lingqfix

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A sentence the user wants to come back to. [sent] = sentence number in the lesson (reader id); [tr] = its translation. */
data class Bookmark(
    val lang: String, val lessonId: Int, val lessonTitle: String,
    val sent: Int, val text: String, val time: Long, val tr: String = "",
    /** Audio bookmark: position in the lesson audio, ms (-1 = a text bookmark). */
    val ms: Long = -1,
) {
    val audio get() = ms >= 0
}

/** All bookmarks, kept on the phone (filesDir/bookmarks.json). */
class BookmarkStore(ctx: Context) {
    companion object {
        fun key(b: Bookmark) = if (b.ms >= 0) "${b.lessonId}_a${b.ms}" else "${b.lessonId}_${b.sent}"

        fun toJson(b: Bookmark): JSONObject =
            JSONObject().put("lang", b.lang).put("lessonId", b.lessonId).put("lessonTitle", b.lessonTitle)
                .put("sent", b.sent).put("text", b.text).put("time", b.time).put("tr", b.tr)
                .also { if (b.ms >= 0) it.put("ms", b.ms) }

        fun fromJson(o: JSONObject) = Bookmark(
            o.getString("lang"), o.getInt("lessonId"), o.optString("lessonTitle"),
            o.getInt("sent"), o.optString("text"), o.optLong("time"), o.optString("tr"), o.optLong("ms", -1L),
        )
    }

    private val file = File(ctx.filesDir, "bookmarks.json")
    private val delFile = File(ctx.filesDir, "bookmarks_deleted.json")
    val items = mutableStateListOf<Bookmark>().apply { addAll(read()) }

    /** Removed bookmarks with the time of removal, so the removal reaches the other device (sync). */
    val deleted = HashMap<String, Long>().apply {
        runCatching { val o = JSONObject(delFile.readText()); o.keys().forEach { put(it, o.getLong(it)) } }
    }

    private fun writeDeleted() { runCatching { delFile.writeText(JSONObject(deleted.toMap()).toString()) } }

    /** After sync: the merged lists. */
    fun replaceAll(list: List<Bookmark>, del: Map<String, Long>) {
        if (list != items.toList()) { items.clear(); items.addAll(list); write() }
        deleted.clear(); deleted.putAll(del); writeDeleted()
    }

    /** Lists show the full sentences instead of two lines. */
    var expanded by mutableStateOf(false)

    private fun read(): List<Bookmark> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i -> fromJson(a.getJSONObject(i)) }
    }.getOrDefault(emptyList())

    private fun write() {
        val all = jsonArrayOf(items.map { toJson(it) }).toString()
        DiskQueue.write { file.writeText(all) }
    }

    /** Text bookmark of a sentence. */
    private fun indexOf(lessonId: Int, sent: Int) = items.indexOfFirst { !it.audio && it.lessonId == lessonId && it.sent == sent }

    private fun indexOf(b: Bookmark) = items.indexOfFirst { key(it) == key(b) }

    fun has(lessonId: Int, sent: Int) = indexOf(lessonId, sent) >= 0

    fun forLesson(lessonId: Int) = items.filter { !it.audio && it.lessonId == lessonId }.sortedBy { it.sent }

    /** Audio bookmarks of a lesson, in audio order. */
    fun audioFor(lessonId: Int) = items.filter { it.audio && it.lessonId == lessonId }.sortedBy { it.ms }

    /** A place in the lesson audio (with the sentence sounding there). */
    fun addAudio(lang: String, lesson: Lesson, sent: Int, text: String, ms: Long) {
        // one mark per sentence is enough: a second tap there moves it
        val now = System.currentTimeMillis()
        items.filter { it.audio && it.lessonId == lesson.id && it.sent == sent && sent >= 0 }.forEach { deleted[key(it)] = now; items.remove(it) }
        items.add(Bookmark(lang, lesson.id, lesson.title, sent, text, now, ms = ms))
        deleted.remove("${lesson.id}_a$ms")
        write(); writeDeleted()
    }

    /** Adds or removes; returns true when the sentence is bookmarked now. */
    fun toggle(lang: String, lesson: Lesson, sent: Int, text: String, tr: String = ""): Boolean {
        val i = indexOf(lesson.id, sent)
        val now = if (i >= 0) { deleted[key(items[i])] = System.currentTimeMillis(); items.removeAt(i); false }
        else { items.add(Bookmark(lang, lesson.id, lesson.title, sent, text, System.currentTimeMillis(), tr)); true }
        write(); writeDeleted()
        return now
    }

    fun setTranslation(b: Bookmark, tr: String) {
        val i = indexOf(b)
        if (i >= 0) { items[i] = items[i].copy(tr = tr); write() }
    }

    fun remove(b: Bookmark) {
        val i = indexOf(b)
        if (i >= 0) { deleted[key(items[i])] = System.currentTimeMillis(); items.removeAt(i); write(); writeDeleted() }
    }
}

private suspend fun translateBookmark(state: AppState, b: Bookmark): String = withContext(Dispatchers.IO) {
    if (state.useGemini) state.geminiFast().sentence(state.prefs.promptSentence, b.lang, state.trLang, b.text)
    else state.api.translate(b.lang, state.trLang, b.text)
}

/** Top-bar button: full sentences / two lines. */
@Composable
fun BookmarksExpandButton(state: AppState) {
    val on = state.bookmarks.expanded
    IconButton(onClick = { state.bookmarks.expanded = !on }) {
        Icon(
            if (on) AppIcons.UnfoldLess else AppIcons.UnfoldMore,
            contentDescription = if (on) "Свернуть предложения" else "Развернуть предложения",
        )
    }
}

/** All bookmarks grouped by lesson (opened from the library). */
@Composable
fun BookmarksScreen(state: AppState) {
    val all = state.bookmarks.items
    if (all.isEmpty()) {
        Text(
            "Закладок пока нет. В уроке нажмите на значок закладки у предложения, чтобы вернуться к нему позже.",
            Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var kind by remember { mutableStateOf(0) }            // 0 all, 1 text, 2 audio
    val shown = all.filter { kind == 0 || (kind == 1) == !it.audio }
    val groups = shown.groupBy { it.lessonId }.values.sortedByDescending { g -> g.maxOf { it.time } }
    LazyColumn(Modifier.fillMaxSize()) {
        if (all.any { it.audio }) item(key = "filter") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                listOf("Все", "Текст", "Аудио").forEachIndexed { i, t ->
                    androidx.compose.material3.FilterChip(kind == i, onClick = { kind = i }, label = { Text(t) })
                }
            }
        }
        item {
            Text(
                "Нажатие — перевод предложения, долгое нажатие — перейти к источнику или удалить.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
        groups.forEach { g ->
            val first = g.first()
            item(key = "h${first.lessonId}") {
                Text(
                    first.lessonTitle.ifBlank { "Урок ${first.lessonId}" },
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(g.sortedWith(compareBy<Bookmark>({ it.audio }, { if (it.audio) it.ms else it.sent.toLong() })), key = { BookmarkStore.key(it) }) { b ->
                BookmarkRow(state, b, onOpen = { if (b.audio) state.openAudioBookmark(b) else state.openBookmark(b) })
            }
            item(key = "d${first.lessonId}") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
        }
    }
}

/** Tap: show/hide the translation; long press: menu with "go to source" and "delete". */
@Composable
private fun BookmarkRow(state: AppState, b: Bookmark, onOpen: () -> Unit) {
    var showTr by remember(BookmarkStore.key(b)) { mutableStateOf(false) }
    var loading by remember(BookmarkStore.key(b)) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val full = state.bookmarks.expanded || showTr
    Box {
        ListItem(
            headlineContent = {
                Text(
                    (if (b.audio) "${fmtTime(b.ms)} · " else "") + b.text,
                    maxLines = if (full) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = if (!showTr) null else {
                {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    else Text(
                        b.tr, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            leadingContent = {
                Icon(if (b.audio) AppIcons.Headphones else AppIcons.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            modifier = Modifier.combinedClickable(
                onClickLabel = "Перевод предложения",
                onLongClickLabel = "Меню закладки",
                onClick = {
                    showTr = !showTr
                    if (showTr && b.tr.isBlank() && !loading) {
                        loading = true
                        scope.launch {
                            try {
                                state.bookmarks.setTranslation(b, translateBookmark(state, b).trim())
                            } catch (e: Throwable) {
                                showTr = false
                                state.say(e.message ?: "Ошибка перевода")
                            }
                            loading = false
                        }
                    }
                },
                onLongClick = { menu = true },
            ),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (b.audio) "Слушать с этого места" else "Перейти к источнику") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                onClick = { menu = false; onOpen() },
            )
            DropdownMenuItem(
                text = { Text("Удалить закладку") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { menu = false; state.bookmarks.remove(b) },
            )
        }
    }
}

/** Bookmarks of the open lesson: same rows; "go to source" jumps to the sentence. */
@Composable
fun LessonBookmarksDialog(state: AppState, s: ReaderSession, onClose: () -> Unit) {
    val list = state.bookmarks.forLesson(s.lesson.id)
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Закладки урока", Modifier.weight(1f))
                if (list.isNotEmpty()) BookmarksExpandButton(state)
            }
        },
        text = {
            if (list.isEmpty()) Text("В этом уроке закладок нет.")
            else Box(Modifier.heightIn(max = 460.dp)) {
                LazyColumn {
                    items(list, key = { it.sent }) { b ->
                        BookmarkRow(state, b, onOpen = { s.jumpTo(b.sent); onClose() })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
        dismissButton = {
            TextButton(onClick = { onClose(); state.go(Screen.Bookmarks) }) { Text("Все закладки") }
        },
    )
}
