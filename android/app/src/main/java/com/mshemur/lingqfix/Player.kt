package com.mshemur.lingqfix

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The player tab: a lesson's audio with its text. Collapsed: a window with the sentence sounding now (bold) and its
 * neighbours; a tap opens the whole text, which follows the audio. Words can be tapped for a translation, a long press
 * shows the sentence translation at the bottom.
 */
@Composable
fun PlayerScreen(state: AppState) {
    val s = state.player
    if (s == null) {
        AudioLessons(state, Modifier.fillMaxSize()) { lang, l -> state.playLesson(lang, l) }
        return
    }
    LaunchedEffect(s) { if (s.paragraphs == null && s.error == null) s.load() }
    LaunchedEffect(s) {
        snapshotFlow { if (AudioEngine.key == s.audioKey) AudioEngine.position else -1L }
            .collect { ms -> s.trackAudio(if (ms < 0) null else s.sentenceAt(ms)) }
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var marks by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BackHandler(expanded) { expanded = false }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
    Column(Modifier.fillMaxSize()) {
        if (expanded) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { expanded = false }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Свернуть текст") }
            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            PlayerActions(s, onMarks = { marks = true }, onPick = null)
        } else Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            // the title opens the list of lessons (like the playlist title in music apps)
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { picker = true }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Другой урок", modifier = Modifier.size(20.dp))
                }
                if (s.lesson.courseTitle.isNotBlank()) Text(
                    s.lesson.courseTitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            PlayerActions(s, onMarks = { marks = true }, onPick = null)
        }
        val paras = s.paragraphs
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                s.error != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ошибка: ${s.error}", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { s.load() }) { Text("Повторить") }
                }
                paras == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                s.audioUrl == null -> Text(
                    "У этого урока нет аудио на LingQ.", Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                else -> androidx.compose.animation.AnimatedContent(
                    targetState = expanded,
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                    label = "text",
                ) { full ->
                    if (full) FullText(s, paras)
                    else TextWindow(s, Modifier.fillMaxSize().padding(16.dp)) { expanded = true }
                }
            }
        }
        AnimatedVisibility(
            visible = s.bottomSent != null,
            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(),
        ) {
            val id = s.bottomSent
            if (id != null) BottomTranslation(s, id)
        }
        if (s.audioUrl != null) AudioBar(s)
    }
    WordMenuLayer(s, "player") { ref, close -> PlayerMenuItems(s, ref, close) }
    }
    if (marks) MarksSheet(s) { marks = false }
    s.popup?.let { ref -> WordSheet(s, ref) }
    s.phrase?.let { p -> PhraseSheet(s, p) }
    if (picker) PickerSheet(state) { picker = false }
}

/** Collapsed text: the previous, current (bold) and next sentence; a tap opens the whole text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextWindow(s: ReaderSession, modifier: Modifier, onOpen: () -> Unit) {
    val list = s.sentences
    val cur = s.playingSent ?: s.sentenceAt(AudioEngine.savedPos(s.appCtx, s.audioKey)) ?: list.firstOrNull()?.id
    val i = list.indexOfFirst { it.id == cur }.coerceAtLeast(0)
    // tap: the whole text; double tap on the left / right half: 5 s back / forward
    var flash by remember { mutableStateOf<Boolean?>(null) }
    var flashTick by remember { mutableStateOf(0) }
    LaunchedEffect(flashTick) { if (flashTick > 0) { kotlinx.coroutines.delay(650); flash = null } }
    Box(modifier) {
    Card(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { onOpen() },
                onDoubleTap = { o ->
                    val fwd = o.x > size.width / 2
                    AudioEngine.seekBy(if (fwd) 5000 else -5000)
                    flash = fwd; flashTick++
                },
            )
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        // the lines move up softly to the next sentence (down when going back) instead of jumping
        androidx.compose.animation.AnimatedContent(
            targetState = i,
            transitionSpec = {
                val up = targetState > initialState
                val d = 550
                (androidx.compose.animation.slideInVertically(tween(d, easing = FastOutSlowInEasing)) { h -> if (up) h / 3 else -h / 3 } +
                    fadeIn(tween(d))) togetherWith
                    (androidx.compose.animation.slideOutVertically(tween(d, easing = FastOutSlowInEasing)) { h -> if (up) -h / 3 else h / 3 } +
                        fadeOut(tween(d / 2)))
            },
            label = "sentence",
            modifier = Modifier.fillMaxSize(),
        ) { i ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            val size = s.font.toFloat()
            list.getOrNull(i - 1)?.let {
                Text(it.text, fontSize = (size * 1.15f).sp, lineHeight = (size * 1.5f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            list.getOrNull(i)?.let {
                Text(it.text, fontSize = (size * 1.55f).sp, lineHeight = (size * 2f).sp, fontWeight = FontWeight.Bold)
            }
            list.getOrNull(i + 1)?.let {
                Text(it.text, fontSize = (size * 1.15f).sp, lineHeight = (size * 1.5f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        }
    }
    flash?.let { fwd ->
        Surface(
            shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
            modifier = Modifier.align(if (fwd) Alignment.CenterEnd else Alignment.CenterStart).padding(24.dp),
        ) {
            Text(
                if (fwd) "5 с »" else "« 5 с", Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    }
}

/** The whole text; scrolls along with the audio unless the user scrolled a moment ago. */
@Composable
private fun FullText(s: ReaderSession, paras: List<List<Sent>>) {
    val list = rememberLazyListState()
    var userAt by remember { mutableStateOf(0L) }
    val conn = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) userAt = SystemClock.uptimeMillis()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(s.playingSent, paras) {
        val id = s.playingSent ?: return@LaunchedEffect
        if (SystemClock.uptimeMillis() - userAt < 6000) return@LaunchedEffect
        val p = paras.indexOfFirst { para -> para.any { it.id == id } }
        if (p < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val inView = visible.firstOrNull { it.index == p }?.let { info ->
            info.offset >= 0 && info.offset + info.size <= list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportSize.height / 4
        } ?: false
        if (!inView) list.animateScrollToItem(p)
    }
    LazyColumn(
        Modifier.fillMaxSize().nestedScroll(conn),
        state = list,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(paras, key = { _, p -> p.first().id }) { _, para -> SentenceFlow(s, para, area = "player") }
    }
}

/** Long press on a word: the translation of its sentence, at the bottom above the player. */
@Composable
private fun BottomTranslation(s: ReaderSession, id: Int) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TranslationText(
                s.sentTr[id], s.sentences.firstOrNull { it.id == id }?.text.orEmpty(),
                MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                MaterialTheme.colorScheme.onSecondaryContainer, Modifier.weight(1f),
            )
            IconButton(onClick = { s.bottomSent = null }) { Icon(Icons.Filled.Close, contentDescription = "Скрыть перевод") }
        }
    }
}

/** Long press on a word in the player text: 4 items right at the word. */
@Composable
private fun PlayerMenuItems(s: ReaderSession, ref: WordRef, close: () -> Unit) {
    DropdownMenuItem(text = { Text("Перевод слова") }, onClick = { close(); s.openPopup(ref) })
    DropdownMenuItem(text = { Text("Перевод предложения") }, onClick = { close(); s.bottomSent = null; s.toggleSentence(ref.sent) })
    if ((s.sentences.firstOrNull { it.id == ref.sent }?.start ?: -1L) >= 0) {
        DropdownMenuItem(text = { Text("Начало повтора") }, onClick = { close(); s.loopStartAt(ref.sent) })
        DropdownMenuItem(text = { Text("Конец повтора") }, onClick = { close(); s.loopEndAt(ref.sent) })
    }
}

/** Header buttons: bookmark this place; menu with the lesson's audio bookmarks and another lesson. */
@Composable
private fun PlayerActions(s: ReaderSession, onMarks: () -> Unit, onPick: (() -> Unit)?) {
    IconButton(onClick = { s.markAudio() }, enabled = s.audioUrl != null) {
        Icon(AppIcons.BookmarkBorder, contentDescription = "Закладка на это место")
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Аудиозакладки (${s.audioMarks().size})") },
                leadingIcon = { Icon(AppIcons.Bookmark, contentDescription = null) },
                onClick = { menu = false; onMarks() },
            )
            if (onPick != null) DropdownMenuItem(
                text = { Text("Другой урок") },
                leadingIcon = { Icon(AppIcons.Headphones, contentDescription = null) },
                onClick = { menu = false; onPick() },
            )
        }
    }
}

/** Audio bookmarks of the lesson: tap = listen from there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarksSheet(s: ReaderSession, onClose: () -> Unit) {
    val marks = s.audioMarks()
    ModalBottomSheet(onDismissRequest = onClose) {
        Text("Аудиозакладки", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        if (marks.isEmpty()) Text(
            "Пока нет. Значок закладки вверху плеера отмечает место, которое звучит сейчас.",
            Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else LazyColumn(Modifier.padding(bottom = 24.dp)) {
            itemsIndexed(marks, key = { _, b -> BookmarkStore.key(b) }) { _, b ->
                ListItem(
                    headlineContent = { Text(b.text.ifBlank { "—" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Text(fmtTime(b.ms), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        IconButton(onClick = { s.removeMark(b) }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить закладку") }
                    },
                    modifier = Modifier.clickable { onClose(); AudioEngine.seekTo(b.ms); AudioEngine.play() },
                )
            }
        }
    }
}

/** What to listen to: recent lessons with audio or a course; big cards with covers; "play all". */
@Composable
private fun AudioLessons(state: AppState, modifier: Modifier, onPick: (String, Lesson) -> Unit) {
    LaunchedEffect(state.lang) { state.loadListened(); state.loadCourses() }
    var sort by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }   // 0 own order, 1 new first, 2 by name
    var course by remember { mutableStateOf<Course?>(null) }
    var courseLessons by remember { mutableStateOf<List<Lesson>?>(null) }
    var courseError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(course) {
        val c = course ?: return@LaunchedEffect
        courseLessons = null; courseError = null
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { state.api.lessons(state.lang, c.id).second } }
            .onSuccess { courseLessons = it.filter { l -> l.duration > 0 || l.audio.isNotBlank() } }
            .onFailure { courseError = it.message }
    }
    val raw: List<Lesson>? = if (course == null) state.listened else courseLessons
    val list: List<Lesson>? = raw?.let { r ->
        when (sort) {
            1 -> r.sortedByDescending { it.id }          // lesson ids grow: the newest import first
            2 -> r.sortedWith { a, b -> naturalCompare(a.title, b.title) }
            else -> r
        }
    }
    Column(modifier) {
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FilterChip(course == null, onClick = { course = null }, label = { Text("Недавние") }) }
            state.courses.orEmpty().forEach { c ->
                item(key = c.id) {
                    FilterChip(course?.id == c.id, onClick = { course = c }, label = { Text(c.title.ifBlank { "Курс ${c.id}" }, maxLines = 1) })
                }
            }
        }
        when {
            courseError != null -> Text("Ошибка: $courseError", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            list == null -> Column(Modifier.padding(horizontal = 16.dp)) { repeat(4) { CardSkeleton() } }
            list.isEmpty() -> Text(
                if (course == null) "Здесь будут уроки, которые вы слушали или открывали с аудио. Выберите курс выше."
                else "В этом курсе нет уроков с аудио.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(course?.title ?: "Недавние", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val total = list.sumOf { it.duration }
                        Text(
                            "${list.size} " + plural(list.size, "урок", "урока", "уроков") + if (total > 0) " · ${fmtLength(total)}" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.FilledTonalButton(onClick = { state.playAll(state.lang, list) }, enabled = !state.queueLoading) {
                        if (state.queueLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("Слушать все", Modifier.padding(start = 6.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(if (course == null) "Недавно слушал" else "Как в курсе", "Новые сверху", "По имени").forEachIndexed { i, t ->
                        FilterChip(sort == i, onClick = { sort = i }, label = { Text(t) })
                    }
                }
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(list, key = { _, l -> l.id }) { _, l ->
                        AudioLessonCard(l, current = state.player?.lesson?.id == l.id) { onPick(state.lang, l) }
                    }
                }
            }
        }
    }
}

/** "Lektion 2" before "Lektion 10": runs of digits compare as numbers, the rest ignoring case. */
private fun naturalCompare(a: String, b: String): Int {
    val re = Regex("\\d+|\\D+")
    val x = re.findAll(a.lowercase()).map { it.value }.toList()
    val y = re.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]; val q = y[i]
        val c = if (p[0].isDigit() && q[0].isDigit()) {
            (p.trimStart('0').length - q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
        } else p.compareTo(q)
        if (c != 0) return c
    }
    return x.size - y.size
}

private fun fmtLength(sec: Int): String {
    val m = (sec + 30) / 60
    return if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"
}

/** A lesson in the player list: big cover, title, course, length. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioLessonCard(l: Lesson, current: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Cover(l.imageUrl, l.title, Modifier.size(96.dp))
                if (current) Surface(
                    shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(l.title.ifBlank { "Урок ${l.id}" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (l.courseTitle.isNotBlank()) Text(
                    l.courseTitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        if (l.duration > 0) fmtLength(l.duration) else null,
                        if (current) "сейчас в плеере" else null,
                    ).joinToString(" · ").ifEmpty { "аудио" },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            Icon(Icons.Filled.PlayArrow, contentDescription = "Слушать", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).size(28.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(state: AppState, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Text("Что слушать", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        AudioLessons(state, Modifier.fillMaxWidth().fillMaxHeight(0.85f)) { lang, l -> onClose(); state.playLesson(lang, l) }
    }
}
