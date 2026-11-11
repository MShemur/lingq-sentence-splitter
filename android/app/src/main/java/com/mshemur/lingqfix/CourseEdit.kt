package com.mshemur.lingqfix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** A picture from the gallery as a JPEG of at most 1200 px (LingQ course cover). */
internal fun coverFromUri(ctx: android.content.Context, uri: Uri): Pair<ByteArray, String>? = runCatching {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val k = 1200f / maxOf(bmp.width, bmp.height)
    val b = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
    val out = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, 88, out)
    out.toByteArray() to "image/jpeg"
}.getOrNull()

/** Course screen ⋮: edit (title, cover), archive, delete. */
@Composable
fun CourseMenu(state: AppState, s: Screen.Lessons) {
    var open by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val archived = s.course.id in state.archived
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Изменить курс") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { open = false; edit = true },
            )
            DropdownMenuItem(
                text = { Text(if (archived) "Вернуть из архива" else "В архив") },
                onClick = {
                    open = false
                    state.setArchived(s.course.id, !archived)
                    state.say(if (archived) "Курс снова в библиотеке" else "Курс убран в архив (на LingQ он остался)")
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Удалить курс…", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; delete = true },
            )
        }
    }
    if (edit) CourseEditDialog(state, s) { edit = false }
    if (delete) CourseDeleteDialog(state, s) { delete = false }
}

@Composable
private fun CourseEditDialog(state: AppState, s: Screen.Lessons, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(s.title) }
    var cover by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            cover = coverFromUri(ctx, uri)
            if (cover == null) error = "Картинку не удалось открыть"
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Изменить курс") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, enabled = !busy)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
                        Text(if (cover == null) "Своя обложка…" else "Другая картинка…")
                    }
                    if (cover != null) Text("  картинка выбрана", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && title.isNotBlank() && (title.trim() != s.title || cover != null),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val t = title.trim()
                            var img: String? = null
                            withContext(Dispatchers.IO) {
                                if (t != s.title) state.api.renameCourse(s.lang, s.course.id, t)
                                cover?.let { img = state.api.setCourseImage(s.lang, s.course.id, it) }
                            }
                            state.courseChanged(s.course.copy(title = t, imageUrl = img ?: s.course.imageUrl))
                            state.say("Курс изменён")
                            onClose()
                        } catch (e: Throwable) {
                            AppLog.error("course", "Изменить курс", e)
                            error = e.message ?: e.toString()
                        }
                        busy = false
                    }
                },
            ) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Отмена") } },
    )
}

@Composable
private fun CourseDeleteDialog(state: AppState, s: Screen.Lessons, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val n = s.lessons?.size
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Удалить курс?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "«${s.title}»" + (if (n != null) " и ${n} ${plural(n, "урок", "урока", "уроков")} в нём" else "") +
                        " будут удалены на LingQ. Отменить это нельзя.",
                )
                Text(
                    "Если курс просто мешает в библиотеке — уберите его в архив: он скроется только здесь.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { state.api.deleteCourse(s.lang, s.course.id) }
                            AppLog.add("course", "Курс ${s.course.id} «${s.title}» удалён")
                            state.forgetCourse(s.lang, s.course.id, s.lessons.orEmpty().map { it.id })
                            onClose()
                            if (state.stack.lastOrNull() === s) state.back()
                            state.say("Курс удалён")
                        } catch (e: Throwable) {
                            AppLog.error("course", "Удалить курс", e)
                            error = e.message ?: e.toString()
                        }
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row {
                if (s.course.id !in state.archived) TextButton(onClick = {
                    state.setArchived(s.course.id, true); state.say("Курс убран в архив"); onClose()
                }, enabled = !busy) { Text("В архив") }
                TextButton(onClick = onClose, enabled = !busy) { Text("Отмена") }
            }
        },
    )
}

/** Reader ⋮ → "Удалить урок…": confirmation, then DELETE on LingQ and back. */
@Composable
fun LessonDeleteDialog(state: AppState, s: ReaderSession, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Удалить урок?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("«${s.title}» будет удалён на LingQ вместе с переводами и аудио. Отменить это нельзя.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { state.api.deleteLesson(s.lang, s.lesson.id) }
                            AppLog.add("reader", "Урок ${s.lesson.id} «${s.title}» удалён")
                            state.forgetLesson(s.lang, s.lesson.id)
                            onClose()
                            state.back()
                            state.say("Урок удалён")
                        } catch (e: Throwable) {
                            AppLog.error("reader", "Удалить урок", e)
                            error = e.message ?: e.toString()
                        }
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Отмена") } },
    )
}

/** Library ⋮ (top right): the course archive. */
@Composable
fun LibraryMenu(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Архив курсов (${state.archivedCourses().size})") },
                onClick = { open = false; state.go(Screen.Archive) },
            )
        }
    }
}

/** Archived courses: hidden in the library and the player on this device; LingQ is not touched. */
@Composable
fun ArchiveScreen(state: AppState) {
    val list = state.archivedCourses()
    if (list.isEmpty()) {
        Text(
            "Архив пуст. Курс убирается сюда из его меню ⋮ → «В архив»: он скрывается в библиотеке и плеере " +
                "только на этом устройстве, на LingQ ничего не меняется.",
            Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(list, key = { it.id }) { c ->
            Column {
                CourseTile(state, c) { state.go(Screen.Lessons(state.lang, c, reader = true)) }
                TextButton(onClick = { state.setArchived(c.id, false); state.say("«${c.title}» снова в библиотеке") }) { Text("Вернуть") }
            }
        }
    }
}
