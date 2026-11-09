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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
        Column(Modifier.fillMaxSize()) {
            Text(
                "Что слушать", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            AudioLessons(state, Modifier.weight(1f)) { lang, l -> state.playLesson(lang, l) }
        }
        return
    }
    LaunchedEffect(s) { if (s.paragraphs == null && s.error == null) s.load() }
    LaunchedEffect(s) {
        snapshotFlow { if (AudioEngine.key == s.audioKey) AudioEngine.position else -1L }
            .collect { ms -> s.trackAudio(if (ms < 0) null else s.sentenceAt(ms)) }
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    BackHandler(expanded) { expanded = false }
    Column(Modifier.fillMaxSize()) {
        if (expanded) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { expanded = false }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Свернуть текст") }
            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (s.lesson.courseTitle.isNotBlank()) Text(
                    s.lesson.courseTitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { picker = true }) { Text("Другой урок") }
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
                expanded -> FullText(s, paras)
                else -> TextWindow(s, Modifier.fillMaxSize().padding(16.dp)) { expanded = true }
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
    s.wordMenu?.let { ref -> WordMenu(s, ref) }
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
    Card(
        onClick = onOpen, modifier = modifier,
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
                Text(it.text, fontSize = (size * 0.9f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            list.getOrNull(i)?.let {
                Text(it.text, fontSize = (size * 1.1f).sp, lineHeight = (size * 1.45f).sp, fontWeight = FontWeight.Bold)
            }
            list.getOrNull(i + 1)?.let {
                Text(it.text, fontSize = (size * 0.9f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "Нажмите, чтобы открыть весь текст", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.CenterHorizontally),
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
            val tr = s.sentTr[id]
            Text(
                if (tr.isNullOrEmpty()) "…" else tr, Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            IconButton(onClick = { s.bottomSent = null }) { Icon(Icons.Filled.Close, contentDescription = "Скрыть перевод") }
        }
    }
}

/** Long press on a word in the player text: translations and the loop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordMenu(s: ReaderSession, ref: WordRef) {
    val close = { s.wordMenu = null }
    val sent = s.sentences.firstOrNull { it.id == ref.sent }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                s.word(ref), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            sent?.let {
                Text(
                    it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            MenuRow(AppIcons.Translate, "Перевод слова") { close(); s.openPopup(ref) }
            MenuRow(AppIcons.Chat, "Перевод предложения") { close(); s.bottomSent = null; s.toggleSentence(ref.sent) }
            if ((sent?.start ?: -1L) >= 0) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                MenuRow(AppIcons.Repeat, "Повторять это предложение") { close(); s.loopSentenceId(ref.sent) }
                MenuRow(AppIcons.SkipPrevious, "Начало повтора", "с начала этого предложения") { close(); s.loopStartAt(ref.sent) }
                MenuRow(AppIcons.SkipNext, "Конец повтора", "до конца этого предложения") { close(); s.loopEndAt(ref.sent) }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { if (hint != null) Text(hint) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Lessons with audio among the recently opened ones. */
@Composable
private fun AudioLessons(state: AppState, modifier: Modifier, onPick: (String, Lesson) -> Unit) {
    LaunchedEffect(state.lang) { if (state.recent == null) state.loadRecent() }
    val all = state.recent
    val withAudio = all.orEmpty().filter { it.audio.isNotBlank() }
    Column(modifier) {
        when {
            all == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            withAudio.isEmpty() -> Text(
                "Среди последних уроков нет уроков с аудио. Откройте урок с аудио и выберите ⋮ → «Слушать в плеере».",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn {
                itemsIndexed(withAudio, key = { _, l -> l.id }) { n, l ->
                    if (n > 0) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(l.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(l.courseTitle.ifBlank { "Урок ${l.id}" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(AppIcons.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { if (state.player?.lesson?.id == l.id) Text("сейчас", color = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onPick(state.lang, l) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(state: AppState, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Text("Что слушать", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        AudioLessons(state, Modifier.fillMaxWidth().padding(bottom = 24.dp)) { lang, l -> onClose(); state.playLesson(lang, l) }
    }
}
