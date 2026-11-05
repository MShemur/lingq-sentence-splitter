package com.mshemur.lingqfix

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A sentence the user wants to come back to. [sent] = sentence number in the lesson (reader id). */
data class Bookmark(
    val lang: String, val lessonId: Int, val lessonTitle: String,
    val sent: Int, val text: String, val time: Long,
)

/** All bookmarks, kept on the phone (filesDir/bookmarks.json). */
class BookmarkStore(ctx: Context) {
    private val file = File(ctx.filesDir, "bookmarks.json")
    val items = mutableStateListOf<Bookmark>().apply { addAll(read()) }

    private fun read(): List<Bookmark> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Bookmark(
                o.getString("lang"), o.getInt("lessonId"), o.optString("lessonTitle"),
                o.getInt("sent"), o.optString("text"), o.optLong("time"),
            )
        }
    }.getOrDefault(emptyList())

    private fun write() {
        val a = JSONArray()
        items.forEach { b ->
            a.put(
                JSONObject().put("lang", b.lang).put("lessonId", b.lessonId).put("lessonTitle", b.lessonTitle)
                    .put("sent", b.sent).put("text", b.text).put("time", b.time),
            )
        }
        runCatching { file.writeText(a.toString()) }
    }

    fun has(lessonId: Int, sent: Int) = items.any { it.lessonId == lessonId && it.sent == sent }

    fun forLesson(lessonId: Int) = items.filter { it.lessonId == lessonId }.sortedBy { it.sent }

    /** Adds or removes; returns true when the sentence is bookmarked now. */
    fun toggle(lang: String, lesson: Lesson, sent: Int, text: String): Boolean {
        val i = items.indexOfFirst { it.lessonId == lesson.id && it.sent == sent }
        val now = if (i >= 0) { items.removeAt(i); false }
        else { items.add(Bookmark(lang, lesson.id, lesson.title, sent, text, System.currentTimeMillis())); true }
        write()
        return now
    }

    fun remove(b: Bookmark) { items.remove(b); write() }
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
    val groups = all.groupBy { it.lessonId }.values.sortedByDescending { g -> g.maxOf { it.time } }
    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { g ->
            val first = g.first()
            item(key = "h${first.lessonId}") {
                Text(
                    first.lessonTitle.ifBlank { "Урок ${first.lessonId}" },
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                )
            }
            items(g.sortedBy { it.sent }, key = { "${it.lessonId}:${it.sent}" }) { b ->
                BookmarkRow(b, onOpen = { state.openBookmark(b) }, onDelete = { state.bookmarks.remove(b) })
            }
            item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun BookmarkRow(b: Bookmark, onOpen: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(b.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(AppIcons.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Удалить закладку") }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

/** Bookmarks of the open lesson: tap jumps to the sentence. */
@Composable
fun LessonBookmarksDialog(state: AppState, s: ReaderSession, onClose: () -> Unit) {
    val list = state.bookmarks.forLesson(s.lesson.id)
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(AppIcons.Bookmark, contentDescription = null) },
        title = { Text("Закладки урока") },
        text = {
            if (list.isEmpty()) Text("В этом уроке закладок нет.")
            else Box(Modifier.heightIn(max = 440.dp)) {
                LazyColumn {
                    items(list, key = { it.sent }) { b ->
                        BookmarkRow(b, onOpen = { s.jumpTo(b.sent); onClose() }, onDelete = { state.bookmarks.remove(b) })
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
