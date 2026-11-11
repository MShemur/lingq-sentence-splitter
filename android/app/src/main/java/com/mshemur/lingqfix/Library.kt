package com.mshemur.lingqfix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

private val TILE = RoundedCornerShape(16.dp)

/** Cover picture; while it loads (or without one) a colour gradient with the first letter. */
@Composable
fun Cover(url: String, title: String, modifier: Modifier = Modifier, ratio: Float = 1f) {
    val hue = ((title.hashCode() and 0x7fffffff) % 360).toFloat()
    val c1 = Color.hsv(hue, 0.45f, 0.75f)
    val c2 = Color.hsv((hue + 40f) % 360f, 0.55f, 0.55f)
    Box(
        modifier.aspectRatio(ratio).clip(TILE).background(Brush.linearGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
            style = MaterialTheme.typography.displaySmall, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold,
        )
        if (url.isNotBlank()) AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Course: square cover, title under it. */
@Composable
internal fun CourseTile(state: AppState, c: Course, onClick: () -> Unit) {
    LaunchedEffect(c.id) { state.ensureCourseWords(state.lang, c.id) }
    Column(Modifier.clip(TILE).clickable(onClick = onClick).padding(bottom = 6.dp)) {
        Cover(c.imageUrl, c.title, Modifier.fillMaxWidth())
        Text(
            c.title.ifBlank { "Курс ${c.id}" }, Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        state.courseWords[c.id]?.takeIf { it > 0 }?.let { w ->
            Text(
                "${groupDigits(w)} ${plural(w, "слово", "слова", "слов")}", Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 12345 -> "12 345". */
private fun groupDigits(n: Int) = n.toString().reversed().chunked(3).joinToString("\u00A0").reversed()

/**
 * Lesson: cover with the level and a thin reading-progress bar, title and a short line under it
 * ([subtitle]: course name in "continue", word counts in a course).
 */
@Composable
fun LessonTile(state: AppState, lang: String, l: Lesson, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val read = state.progress.read(lang, l.id)
    Column(modifier.clip(TILE).clickable(onClick = onClick).padding(bottom = 6.dp)) {
        Box {
            Cover(l.imageUrl, l.title, Modifier.fillMaxWidth(), ratio = 4f / 3f)
            if (l.level.isNotBlank() && l.level != "null") Surface(
                shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Text(
                    l.level, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color.White,
                )
            }
            if (l.audio.isNotBlank() || l.duration > 0) Surface(
                shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        AppIcons.Headphones, contentDescription = "Есть аудио", tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    if (l.duration > 0) Text(
                        " ${(l.duration + 30) / 60} мин", style = MaterialTheme.typography.labelSmall, color = Color.White,
                    )
                }
            }
            if (read > 0f) LinearProgressIndicator(
                progress = { read }, drawStopIndicator = {}, gapSize = 0.dp,
                color = Color(0xFF43A047),
                trackColor = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                    .height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        Text(
            l.title, Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val line = listOfNotNull(
            subtitle.takeIf { it.isNotBlank() },
            if (read > 0f) "${(read * 100).toInt()} %" else null,
        ).joinToString(" · ")
        if (line.isNotEmpty()) Text(
            line, Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun SectionTitle(t: String) {
    Text(t, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private val GRID_MIN: Dp = 150.dp

/** Library: "continue" row with recently opened lessons, then the courses as covers. */
@Composable
fun LibraryScreen(state: AppState) {
    var q by remember { mutableStateOf("") }
    LaunchedEffect(state.lang) { state.loadCourses() }
    LaunchedEffect(Unit) { state.loadRecent() }
    val err = state.coursesError
    val list = state.visibleCourses()
    val recent = state.recent.orEmpty()
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // the update card is added above the top card: the grid would keep the old top in view and hide it
    LaunchedEffect(state.update?.code) { if (state.update != null && grid.firstVisibleItemIndex <= 1) grid.animateScrollToItem(0) }
    LazyVerticalGrid(
        GridCells.Adaptive(GRID_MIN), Modifier.fillMaxSize(), state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.update?.let { u ->
            fullWidth("update") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Доступна версия ${u.versionName}", Modifier.weight(1f))
                        val p = state.updateProgress
                        if (p != null) CircularProgressIndicator(progress = { p })
                        else Button(onClick = { state.installUpdate() }) { Text("Обновить") }
                    }
                }
            }
        }
        if (state.apiKey.isBlank()) fullWidth("key") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Укажите API-ключ LingQ", Modifier.weight(1f))
                    TextButton(onClick = { state.go(Screen.SettingsLingq) }) { Text("Настроить") }
                }
            }
        }
        if (q.isBlank()) fullWidth("today") { TodayCard(state) }
        fullWidth("search") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    q, { q = it }, Modifier.weight(1f),
                    placeholder = { Text("Поиск курса") }, singleLine = true, shape = RoundedCornerShape(28.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                )
                IconButton(onClick = { state.loadCourses(force = true); state.loadRecent() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                }
            }
        }
        if (q.isBlank() && state.bgJobs.any { it.target != null }) fullWidth("jobs") { JobsRow(state) }
        if (q.isBlank() && state.recent == null) {
            fullWidth("recentTitle") { SectionTitle("Продолжить") }
            fullWidth("recentSkeleton") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(3) { TileSkeleton(Modifier.width(168.dp)) }
                }
            }
        }
        if (q.isBlank() && recent.isNotEmpty()) {
            fullWidth("recentTitle") { SectionTitle("Продолжить") }
            fullWidth("recent") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.id }) { l ->
                        LessonTile(state, state.lang, l, l.courseTitle, Modifier.width(168.dp)) {
                            state.go(Screen.Reader(ReaderSession(state, state.lang, l)))
                        }
                    }
                }
            }
        }
        fullWidth("coursesTitle") { SectionTitle("Мои курсы") }
        when {
            err != null -> fullWidth("err") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ошибка: $err", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { state.loadCourses(force = true) }) { Text("Повторить") }
                }
            }
            list == null -> items(6, key = { "sk$it" }) { TileSkeleton() }
            else -> items(list.filter { q.isBlank() || it.title.contains(q, ignoreCase = true) }, key = { it.id }) { c ->
                CourseTile(state, c) { state.go(Screen.Lessons(state.lang, c, reader = true)) }
            }
        }
    }
}

/** Lessons of a course as covers, the course cover and name on top. */
@Composable
fun LessonsGrid(state: AppState, s: Screen.Lessons, all: List<Lesson>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ui = remember { ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    // 0 course order, 1 newest first, 2 oldest first, 3 by name (remembered)
    var sort by remember { androidx.compose.runtime.mutableIntStateOf(ui.getInt("lessonSort", 0)) }
    val list = remember(all, sort) {
        when (sort) {
            1 -> all.sortedByDescending { it.id }        // lesson ids grow with every import
            2 -> all.sortedBy { it.id }
            3 -> all.sortedWith { a, b -> naturalCompare(a.title, b.title) }
            else -> all
        }
    }
    LazyVerticalGrid(
        GridCells.Adaptive(GRID_MIN), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        fullWidth("head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(s.course.imageUrl, s.title, Modifier.size(88.dp))
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${list.size} ${plural(list.size, "урок", "урока", "уроков")}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        fullWidth("sort") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Как в курсе", "Новые сверху", "Старые сверху", "По имени").forEachIndexed { i, t ->
                    androidx.compose.material3.FilterChip(
                        sort == i, onClick = { sort = i; ui.edit().putInt("lessonSort", i).apply() }, label = { Text(t) },
                    )
                }
            }
        }
        items(list, key = { it.id }) { l ->
            val sub = if (l.words > 0) "${groupDigits(l.words)} ${plural(l.words, "слово", "слова", "слов")}" else ""
            LessonTile(state, s.lang, l, sub) { state.go(Screen.Reader(ReaderSession(state, s.lang, l))) }
        }
    }
}

internal fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}
