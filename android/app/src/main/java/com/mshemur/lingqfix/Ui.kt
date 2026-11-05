@file:OptIn(ExperimentalMaterial3Api::class)

package com.mshemur.lingqfix

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun App(state: AppState) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        val screen = state.screen
        BackHandler(enabled = state.stack.size > 1) { state.back() }
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                // reader: close cross, progress in the middle, no lesson title
                if (screen is Screen.Reader) CenterAlignedTopAppBar(
                    title = { ReaderProgress(screen.session) },
                    navigationIcon = {
                        IconButton(onClick = { state.back() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Закрыть урок")
                        }
                    },
                    actions = { ReaderActions(state, screen.session) },
                ) else TopAppBar(
                    title = { Text(titleOf(state, screen), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (state.stack.size > 1) IconButton(onClick = { state.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    actions = {
                        when (screen) {
                            Screen.Bookmarks -> { if (state.bookmarks.items.isNotEmpty()) BookmarksExpandButton(state) }
                            Screen.ReaderCourses -> {
                                IconButton(onClick = { state.go(Screen.Bookmarks) }) {
                                    Icon(AppIcons.Bookmark, contentDescription = "Закладки")
                                }
                                IconButton(onClick = { state.go(Screen.Settings) }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                                }
                            }
                            else -> {}
                        }
                    },
                )
            },
            bottomBar = { if (screen is Screen.Reader) ReaderBottomBar(screen.session) },
            floatingActionButton = {
                if (screen == Screen.ReaderCourses) ExtendedFloatingActionButton(
                    onClick = { state.go(Screen.Home) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Импорт") },
                )
            },
            snackbarHost = { SnackbarHost(state.snackbar) },
        ) { pad ->
            // edge-to-edge: the content must shrink above the keyboard itself, otherwise it covers text fields
            Box(Modifier.padding(pad).imePadding().fillMaxSize()) {
                when (screen) {
                    Screen.Home -> HomeScreen(state)
                    Screen.ReaderCourses -> CoursesScreen(state, library = true) { c -> state.go(Screen.Lessons(state.lang, c, reader = true)) }
                    is Screen.Reader -> ReaderScreen(screen.session)
                    is Screen.Chat -> ChatScreen(screen.session)
                    Screen.Bookmarks -> BookmarksScreen(state)
                    Screen.Settings -> SettingsRoot(state)
                    Screen.SettingsLingq -> SettingsLingq(state)
                    Screen.SettingsTranslation -> SettingsTranslation(state)
                    Screen.SettingsPrompts -> SettingsPrompts(state)
                    Screen.SettingsUpdates -> SettingsUpdates(state)
                    Screen.Courses -> CoursesScreen(state) { c -> state.go(Screen.Lessons(state.lang, c)) }
                    Screen.Backups -> BackupsScreen(state)
                    is Screen.Lessons -> LessonsScreen(state, screen)
                    is Screen.Check -> CheckScreen(screen.session)
                    is Screen.Import -> ImportScreen(state, screen.session)
                }
            }
        }
        // sentence mode lies over the whole reader, top bar included
        if (screen is Screen.Reader) SentenceOverlay(screen.session)
        }
        val u = state.update
        if (state.updateDialog && u != null) {
            AlertDialog(
                onDismissRequest = { state.updateDialog = false },
                title = { Text("Доступна версия ${u.versionName}") },
                text = { Text(u.notes.ifBlank { "Сейчас установлена ${BuildConfig.VERSION_NAME}." }, maxLines = 8) },
                confirmButton = {
                    TextButton(onClick = { state.updateDialog = false; state.installUpdate() }) { Text("Обновить") }
                },
                dismissButton = { TextButton(onClick = { state.updateDialog = false }) { Text("Позже") } },
            )
        }
    }
}

private fun titleOf(state: AppState, s: Screen): String = when (s) {
    Screen.Home -> "Импорт"
    Screen.ReaderCourses -> "Библиотека · ${state.lang}"
    is Screen.Reader -> s.session.title
    is Screen.Chat -> s.session.title
    Screen.Bookmarks -> "Закладки"
    Screen.Settings -> "Настройки"
    Screen.SettingsLingq -> "Аккаунт LingQ"
    Screen.SettingsTranslation -> "Перевод"
    Screen.SettingsPrompts -> "Промпты Gemini"
    Screen.SettingsUpdates -> "Обновления"
    Screen.Courses -> "Мои курсы · ${state.lang}"
    Screen.Backups -> "Резервные копии"
    is Screen.Lessons -> s.title
    is Screen.Check -> s.session.label
    is Screen.Import -> "Загрузка в LingQ"
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun ErrorBox(msg: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ошибка: $msg", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = retry) { Text("Повторить") }
    }
}

@Composable
private fun BigButton(title: String, sub: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- Import & fix --------------------------------------------------------------------------------

@Composable
private fun HomeScreen(state: AppState) {
    var link by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (state.apiKey.isBlank()) Card(
            Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Укажите API-ключ LingQ", Modifier.weight(1f))
                TextButton(onClick = { state.go(Screen.SettingsLingq) }) { Text("Настроить") }
            }
        }
        ListItem(
            leadingContent = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text("Загрузить в LingQ") },
            supportingContent = { Text("Файл .txt, .md, .epub или вставленный текст") },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            modifier = Modifier.clickable { state.go(Screen.Import(ImportSession(state))) },
        )
        ListItem(
            leadingContent = { Icon(Icons.Filled.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text("Исправить разбивку на предложения") },
            supportingContent = { Text("Курс или урок из ваших курсов (${state.lang})") },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            modifier = Modifier.clickable { state.go(Screen.Courses) },
        )
        HorizontalDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Исправить по ссылке", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                link, { link = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("Ссылка на урок или курс LingQ") }, singleLine = true,
                trailingIcon = {
                    if (link.isBlank()) TextButton(onClick = { clipboard.getText()?.text?.let { link = it } }) { Text("Вставить") }
                    else IconButton(onClick = { link = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Очистить") }
                },
            )
            Button(onClick = { state.openLink(link) }, enabled = link.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Проверить")
            }
        }
    }
}

// --- Courses / lessons ---------------------------------------------------------------------------

@Composable
private fun CoursesScreen(state: AppState, library: Boolean = false, onPick: (Course) -> Unit) {
    var q by remember { mutableStateOf("") }
    LaunchedEffect(state.lang) { state.loadCourses() }
    Column(Modifier.fillMaxSize()) {
        if (library) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.update?.let { u ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Доступна версия ${u.versionName}", Modifier.weight(1f))
                        val p = state.updateProgress
                        if (p != null) CircularProgressIndicator(progress = { p })
                        else Button(onClick = { state.installUpdate() }) { Text("Обновить") }
                    }
                }
            }
            if (state.apiKey.isBlank()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Укажите API-ключ LingQ", Modifier.weight(1f))
                    TextButton(onClick = { state.go(Screen.SettingsLingq) }) { Text("Настроить") }
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                q, { q = it }, Modifier.weight(1f),
                placeholder = { Text("Поиск курса") }, singleLine = true,
            )
            IconButton(onClick = { state.loadCourses(force = true) }) { Icon(Icons.Filled.Refresh, contentDescription = "Обновить") }
        }
        val err = state.coursesError
        val list = state.courses
        when {
            err != null -> ErrorBox(err) { state.loadCourses(force = true) }
            list == null -> Loading()
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list.filter { q.isBlank() || it.title.contains(q, ignoreCase = true) }, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.title) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { onPick(c) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LessonsScreen(state: AppState, s: Screen.Lessons) {
    LaunchedEffect(s) { if (s.lessons == null) s.load(state) }
    val err = s.error
    val list = s.lessons
    Column(Modifier.fillMaxSize()) {
        when {
            err != null -> ErrorBox(err) { s.load(state) }
            list == null -> Loading()
            s.reader -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { l ->
                    ListItem(
                        headlineContent = { Text(l.title) },
                        modifier = Modifier.clickable { state.go(Screen.Reader(ReaderSession(state, s.lang, l))) },
                    )
                    HorizontalDivider()
                }
            }
            else -> {
                Button(
                    onClick = { state.checkLessons(s.lang, list, s.title) },
                    enabled = list.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text("Проверить весь курс (${list.size} уроков)") }
                Text(
                    "или выберите один урок:", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { l ->
                        ListItem(
                            headlineContent = { Text(l.title) },
                            modifier = Modifier.clickable { state.checkLessons(s.lang, listOf(l), l.title) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// --- Check / apply -------------------------------------------------------------------------------

@Composable
private fun ChangeRow(c: Change) {
    val accent = MaterialTheme.colorScheme.primary
    val text: AnnotatedString = when (c.kind) {
        ChangeKind.MERGE -> buildAnnotatedString {
            append(Fixer.tail(c.a))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append("  ‖  ") }
            append(Fixer.head(c.b))
        }
        ChangeKind.DUP -> AnnotatedString("🗑 убран повтор: " + Fixer.head(c.a, 80))
        ChangeKind.CLEANUP -> AnnotatedString("🔧 исправлены символы: " + Fixer.head(c.a, 80))
    }
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
}

/** Opens Android's share sheet with [text] (send the report to yourself / to a chat). */
private fun shareText(ctx: android.content.Context, subject: String, text: String) {
    val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    ctx.startActivity(android.content.Intent.createChooser(i, "Отправить отчёт").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun CheckScreen(s: CheckSession) {
    var confirm by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        if (s.running || s.applying) {
            val p = if (s.running) s.progress else s.savedIds.size.toFloat() / maxOf(1, s.results.size)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            Text(
                s.status, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        s.error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    "Проверено уроков: ${s.checked} из ${s.total} · с правками: ${s.results.size} · правок: ${s.changeCount}",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (s.results.isNotEmpty() && !s.done) Text(
                    "‖ — место склейки: слева конец одного куска, справа начало следующего. " +
                        "Проверьте, что это части одного предложения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!s.running && s.results.isEmpty() && s.error == null) Text(
                    "Исправлять нечего ✅", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (!s.running && s.results.isNotEmpty()) TextButton(onClick = {
                    shareText(ctx, "LingQ Fix: ${s.label}", reportText("Проверка: ${s.label} (${s.lang})", s.results.toList()))
                }) { Text("📤 Поделиться отчётом") }
            }
            s.results.forEach { r ->
                item(key = "h${r.lesson.id}") {
                    Row(Modifier.padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.lesson.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(if (r.lesson.id in s.savedIds) "✅" else "${r.changes.size}", style = MaterialTheme.typography.labelLarge)
                    }
                    HorizontalDivider()
                }
                items(r.changes.size) { i -> ChangeRow(r.changes[i]) }
            }
        }
        if (!s.running && s.results.isNotEmpty()) {
            Surface(tonalElevation = 3.dp) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (s.done) Text("Записано ✅  Копии уроков до правки — в «Резервных копиях».")
                    else Button(
                        onClick = { confirm = true }, enabled = !s.applying, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Применить: ${s.changeCount} правок в ${s.results.size} уроках") }
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Записать в LingQ?") },
        text = { Text("Будет изменено уроков: ${s.results.size}. Перед записью сохраняется копия каждого урока.") },
        confirmButton = { TextButton(onClick = { confirm = false; s.apply() }) { Text("Записать") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
    )
}

// --- Import --------------------------------------------------------------------------------------

@Composable
private fun ImportScreen(state: AppState, s: ImportSession) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) s.loadFile(uri)
    }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var courseMenu by remember { mutableStateOf(false) }
    LaunchedEffect(state.lang) { state.loadCourses() }
    val courses = state.courses.orEmpty()
    LaunchedEffect(state.courses) {
        if (s.course == null) s.course = courses.firstOrNull()
        if (state.courses?.isEmpty() == true) s.newCourse = true   // no courses yet: offer to create one
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("text/plain", "text/markdown", "application/epub+zip", "application/octet-stream")) },
                enabled = !s.running,
            ) { Text("📄 Файл") }
            OutlinedButton(
                onClick = {
                    s.pasteMode = true
                    if (s.pasteText.isBlank()) clipboard.getText()?.text?.let { s.pasteText = it }
                },
                enabled = !s.running,
            ) { Text("📋 Вставить текст") }
        }
        if (s.loading) Loading()

        if (s.pasteMode) {
            OutlinedTextField(
                s.pasteTitle, { s.pasteTitle = it }, Modifier.fillMaxWidth(),
                label = { Text("Название урока") }, singleLine = true,
            )
            OutlinedTextField(
                s.pasteText, { s.pasteText = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                label = { Text("Текст") },
            )
            Text("${countWords(s.pasteText)} слов", style = MaterialTheme.typography.bodySmall)
        } else if (s.items.isNotEmpty()) {
            Text(s.sourceName, style = MaterialTheme.typography.titleMedium)
            if (s.items.size > 1) Row {
                TextButton(onClick = { s.items.forEach { it.selected = true } }) { Text("Выбрать все") }
                TextButton(onClick = { s.items.forEach { it.selected = false } }) { Text("Снять все") }
            }
            s.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.items.size > 1) Checkbox(checked = item.selected, onCheckedChange = { v -> item.selected = v })
                    Column(Modifier.weight(1f)) {
                        OutlinedTextField(
                            item.title, { v -> item.title = v }, Modifier.fillMaxWidth(),
                            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${item.words} слов", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        if (s.pasteMode || s.items.isNotEmpty()) {
            HorizontalDivider()
            Text("Курс в LingQ (язык: ${state.lang})", style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { courseMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (s.newCourse) "➕ Новый курс" else s.course?.title ?: if (state.courses == null) "Загрузка курсов…" else "Выберите курс",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = courseMenu, onDismissRequest = { courseMenu = false }) {
                    DropdownMenuItem(text = { Text("➕ Новый курс…") }, onClick = {
                        s.newCourse = true
                        if (s.newCourseTitle.isBlank()) {
                            s.newCourseTitle = (if (s.pasteMode) s.pasteTitle else s.sourceName.substringBeforeLast('.'))
                        }
                        courseMenu = false
                    })
                    HorizontalDivider()
                    courses.forEach { c ->
                        DropdownMenuItem(text = { Text(c.title) }, onClick = { s.course = c; s.newCourse = false; courseMenu = false })
                    }
                }
            }
            if (s.newCourse) {
                OutlinedTextField(
                    s.newCourseTitle, { s.newCourseTitle = it }, Modifier.fillMaxWidth(),
                    label = { Text("Название нового курса") }, singleLine = true, enabled = !s.running,
                )
            }
            state.coursesError?.let { Text("Курсы не загрузились: $it", color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = s.autoFix, onCheckedChange = { s.autoFix = it })
                Text("Сразу исправить разбивку на предложения")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Делить длинные тексты по", Modifier.weight(1f))
                OutlinedTextField(
                    s.splitWords, { v -> s.splitWords = v.filter { it.isDigit() } }, Modifier.width(96.dp),
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("  слов")
            }
            val n = s.selectedCount()
            Button(
                onClick = { s.run() }, enabled = !s.running && n > 0 && (if (s.newCourse) s.newCourseTitle.isNotBlank() else s.course != null),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (n == 1) "Загрузить урок" else "Загрузить уроков: $n") }
        }

        if (s.running || s.log.isNotEmpty()) {
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            s.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        s.error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
        if (s.finished) {
            HorizontalDivider()
            Text(
                if (s.fixes.isEmpty()) "Готово ✅ Разбивка проверена сразу при загрузке, исправлять было нечего."
                else "Готово ✅ Разбивка исправлена сразу при загрузке, ещё раз проверять не нужно. " +
                    "Ниже — что склеено (‖ — место склейки). Копии до правки — в «Резервных копиях».",
                style = MaterialTheme.typography.bodyMedium,
            )
            s.fixes.forEach { f ->
                Text(f.lesson.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                f.changes.forEach { ChangeRow(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.fixes.isNotEmpty()) OutlinedButton(onClick = {
                    shareText(ctx, "LingQ Fix: ${s.course?.title.orEmpty()}",
                        reportText("Загрузка в курс: ${s.course?.title.orEmpty()}", s.fixes.toList()))
                }) { Text("📤 Отчёт") }
                s.course?.let { c ->
                    OutlinedButton(onClick = { state.go(Screen.Lessons(state.lang, c)) }) { Text("Открыть курс") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// --- Backups -------------------------------------------------------------------------------------

@Composable
private fun BackupsScreen(state: AppState) {
    var list by remember { mutableStateOf<List<Backups.Entry>?>(null) }
    var ask by remember { mutableStateOf<Backups.Entry?>(null) }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { state.backups.list() } }
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    var askDeleteAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val l = list
    when {
        l == null -> Loading()
        l.isEmpty() -> Text(
            "Копий нет. Они появляются при каждой записи в LingQ и хранятся ${Backups.KEEP_DAYS} дня.",
            Modifier.padding(16.dp),
        )
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Копии хранятся ${Backups.KEEP_DAYS} дня", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                    OutlinedButton(onClick = { askDeleteAll = true }) { Text("🗑 Удалить все") }
                }
                HorizontalDivider()
            }
            items(l, key = { it.file.name }) { e ->
                ListItem(
                    headlineContent = { Text(e.title.ifBlank { "Урок ${e.id}" }) },
                    supportingContent = { Text("${fmt.format(Date(e.time))} · ${e.lang} · id ${e.id}") },
                    modifier = Modifier.clickable { ask = e },
                )
                HorizontalDivider()
            }
        }
    }
    ask?.let { e ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Вернуть урок?") },
            text = { Text("«${e.title}» вернётся к версии от ${fmt.format(Date(e.time))}.") },
            confirmButton = { TextButton(onClick = { ask = null; state.restore(e) }) { Text("Вернуть") } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Отмена") } },
        )
    }
    if (askDeleteAll) {
        AlertDialog(
            onDismissRequest = { askDeleteAll = false },
            title = { Text("Удалить все копии?") },
            text = { Text("Вернуть уроки к прежней разбивке после этого будет нельзя.") },
            confirmButton = {
                TextButton(onClick = {
                    askDeleteAll = false
                    scope.launch {
                        list = withContext(Dispatchers.IO) { state.backups.deleteAll(); state.backups.list() }
                        state.say("Копии удалены")
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { askDeleteAll = false }) { Text("Отмена") } },
        )
    }
}
