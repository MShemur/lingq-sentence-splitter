@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mshemur.lingqfix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Settings in the usual Android way: a list of sections, each opens its own page.
// Every change is saved immediately (no "Save" button).

@Composable
private fun SectionItem(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary, maxLines = 1) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun SettingsRoot(state: AppState) {
    val p = state.prefs
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionItem(
            Icons.Filled.AccountCircle, "Аккаунт LingQ",
            (if (state.apiKey.isBlank()) "Ключ не задан" else "Ключ задан") + " · язык ${state.lang}",
        ) { state.go(Screen.SettingsLingq) }
        SectionItem(
            Icons.Filled.Create, "Перевод",
            when (p.translator) { "gemini" -> "Gemini · ${p.geminiModel}"; "openai" -> "ИИ · ${p.aiModel}"; else -> "LingQ" } + " → ${state.trLang}",
        ) { state.go(Screen.SettingsTranslation) }
        SectionItem(
            Icons.Filled.Info, "Чтение",
            (readerThemeOf(state.readerTheme)?.name ?: "Фон как в системе") + " · " +
                (HIGHLIGHTS.firstOrNull { it.key == state.highlight }?.name ?: "") + " · " +
                (KEEP_AWAKE.firstOrNull { it.first == state.keepAwake }?.second?.lowercase() ?: ""),
        ) { state.go(Screen.SettingsReading) }
        SectionItem(Icons.Filled.Star, "Промпты Gemini", "Как Gemini переводит и отвечает в чате") {
            state.go(Screen.SettingsPrompts)
        }
        SectionItem(
            Icons.Filled.Refresh, "Синхронизация",
            if (state.sync.enabled) "Телефон и iPad · включена" else "Телефон и iPad · выключена",
        ) { state.go(Screen.SettingsSync) }
        HorizontalDivider()
        SectionItem(Icons.Filled.Refresh, "Резервные копии", "Вернуть урок к версии до исправления") {
            state.go(Screen.Backups)
        }
        SectionItem(Icons.Filled.Build, "Обновления", "Версия ${BuildConfig.VERSION_NAME}") {
            state.go(Screen.SettingsUpdates)
        }
        SectionItem(Icons.Filled.Info, "Журнал действий", "Скопировать для сообщения об ошибке · хранится 2 дня") {
            state.go(Screen.Log)
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Text field for a key/token: hidden, with "Показать". */
@Composable
internal fun SecretField(value: String, label: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Скрыть" else "Показать") } },
    )
}

@Composable
fun SettingsLingq(state: AppState) {
    var key by remember { mutableStateOf(state.apiKey) }
    var lang by remember { mutableStateOf(state.lang) }
    var langs by remember { mutableStateOf<List<Language>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    Page {
        SecretField(key, "API-ключ LingQ") { key = it; state.applyPrefs { apiKey = it } }
        TextButton(onClick = { uri.openUri("https://www.lingq.com/accounts/apikey/") }) { Text("Где взять ключ") }
        Box {
            OutlinedTextField(
                lang, { lang = it.trim(); state.applyPrefs { this.lang = it } }, Modifier.fillMaxWidth(),
                label = { Text("Изучаемый язык") }, singleLine = true,
                trailingIcon = {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                langs = withContext(Dispatchers.IO) { LingqApi(key.trim()).languages() }
                                menu = true
                            } catch (e: Exception) {
                                state.say(e.message ?: "Ошибка")
                            }
                        }
                    }) { Text("Выбрать") }
                },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                langs.forEach { l ->
                    DropdownMenuItem(
                        text = { Text("${l.title} (${l.code})") },
                        onClick = { lang = l.code; state.applyPrefs { this.lang = l.code }; menu = false },
                    )
                }
            }
        }
        Hint("Код языка LingQ, например de. Курсы в библиотеке показываются для этого языка.")
    }
}

@Composable
fun SettingsTranslation(state: AppState) {
    val p = state.prefs
    var trLang by remember { mutableStateOf(state.trLang) }
    var translator by remember { mutableStateOf(p.translator) }
    var gKey by remember { mutableStateOf(p.geminiKey) }
    var aBase by remember { mutableStateOf(p.aiBase) }
    var aKey by remember { mutableStateOf(p.aiKey) }
    // main / light model of the service in use
    var mainModel by remember(translator) { mutableStateOf(if (translator == "openai") p.aiModel else p.geminiModel) }
    var fastModel by remember(translator) { mutableStateOf(if (translator == "openai") p.aiFastModel else p.geminiFastModel) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelFor by remember { mutableStateOf<String?>(null) }
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    Page {
        OutlinedTextField(
            trLang, { trLang = it.trim(); state.applyPrefs { this.trLang = it } }, Modifier.fillMaxWidth(),
            label = { Text("Переводить на язык") }, singleLine = true, supportingText = { Text("ru, en, uk …") },
        )
        Text("Переводчик", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        listOf(
            "lingq" to ("LingQ" to "Варианты пользователей LingQ, машинный перевод предложений"),
            "gemini" to ("Gemini" to "Перевод слова по смыслу предложения, чат, чистка текста. Нужен ключ Google"),
            "openai" to ("Другой ИИ" to "DeepSeek, Qwen, OpenRouter, Groq, Mistral — любой сервис с API как у OpenAI"),
        ).forEach { (id, t) ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = translator == id, role = Role.RadioButton) {
                        translator = id; state.applyPrefs { this.translator = id }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = translator == id, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(t.first, style = MaterialTheme.typography.bodyLarge)
                    Hint(t.second)
                }
            }
        }
        if (translator == "lingq") return@Page
        HorizontalDivider()
        if (translator == "gemini") {
            Text("Gemini", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SecretField(gKey, "Ключ Gemini API") { gKey = it; state.applyPrefs { geminiKey = it } }
            TextButton(onClick = { uri.openUri("https://aistudio.google.com/apikey") }) { Text("Получить ключ бесплатно") }
        } else {
            Text("Сервис", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AI_PRESETS.forEach { (name, url) ->
                    FilterChip(
                        selected = aBase.trimEnd('/') == url, label = { Text(name) },
                        onClick = { aBase = url; state.applyPrefs { aiBase = url } },
                    )
                }
            }
            OutlinedTextField(
                aBase, { aBase = it.trim(); state.applyPrefs { aiBase = it } }, Modifier.fillMaxWidth(),
                label = { Text("Адрес API") }, singleLine = true,
            )
            SecretField(aKey, "Ключ API") { aKey = it; state.applyPrefs { aiKey = it } }
            Hint("Ключ берётся на сайте сервиса (platform.deepseek.com, openrouter.ai, console.groq.com…). Тексты уходят на серверы этого сервиса.")
        }
        // two models: a light one for the many word / sentence requests, the main one for chat and retranslation
        fun pick(target: String) {
            if (!state.useGemini) { state.say("Сначала вставьте ключ"); return }
            modelFor = target
            models = emptyList()
            scope.launch {
                try {
                    models = withContext(Dispatchers.IO) { state.gemini().models() }
                } catch (e: Exception) {
                    modelFor = null
                    state.say(e.message ?: "Ошибка")
                }
            }
        }
        ListItem(
            headlineContent = { Text("Модель для чата и повторного перевода") },
            supportingContent = { Text(mainModel.ifBlank { "не выбрана" }) },
            trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.clickable { pick("main") },
        )
        ListItem(
            headlineContent = { Text("Модель для слов, предложений и чистки") },
            supportingContent = { Text(fastModel.ifBlank { "та же, что для чата" }) },
            trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.clickable { pick("fast") },
        )
        Hint(
            if (translator == "openai") "Для слов и предложений — быстрая дешёвая модель (deepseek-chat, qwen-flash…). " +
                "«Думающие» модели (reasoner, r1) — только для чата: они медленные, их рассуждения видны в чате."
            else "Слова и предложения — это много мелких запросов: возьмите для них лёгкую модель (flash-lite), " +
                "у неё больше бесплатный лимит. Для чата — flash или pro.",
        )
        val target = modelFor
        if (target != null) AlertDialog(
            onDismissRequest = { modelFor = null },
            title = { Text(if (target == "fast") "Модель для слов и предложений" else "Модель для чата") },
            text = {
                val current = if (target == "fast") fastModel else mainModel
                fun choose(m: String) {
                    val other = translator == "openai"
                    if (target == "fast") { fastModel = m; state.applyPrefs { if (other) aiFastModel = m else geminiFastModel = m } }
                    else { mainModel = m; state.applyPrefs { if (other) aiModel = m else geminiModel = m } }
                    modelFor = null
                }
                if (models.isEmpty()) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                } else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    val options = (if (target == "fast") listOf("") else emptyList()) + models
                    items(options) { m ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = m == current, role = Role.RadioButton) { choose(m) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = m == current, onClick = null)
                            Text(m.ifBlank { "Та же, что для чата" }, Modifier.padding(start = 12.dp))
                            if ("lite" in m || "flash" in m) Text(
                                "  быстрая", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { modelFor = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
fun SettingsPrompts(state: AppState) {
    val p = state.prefs
    var pWord by remember { mutableStateOf(p.promptWord) }
    var pSent by remember { mutableStateOf(p.promptSentence) }
    var pRetr by remember { mutableStateOf(p.promptRetranslate) }
    var pChatS by remember { mutableStateOf(p.promptChatSentence) }
    var pChatT by remember { mutableStateOf(p.promptChatText) }
    var pChatW by remember { mutableStateOf(p.promptChatWord) }
    var pClean by remember { mutableStateOf(p.promptClean) }
    Page {
        Hint(
            "Подстановки: {lang} язык текста, {target} язык перевода, {word} слово, {sentence} предложение, " +
                "{text} весь текст. Промпт слова должен просить ответ JSON с полями best, options, note.",
        )
        OutlinedTextField(pWord, { pWord = it; p.promptWord = it }, Modifier.fillMaxWidth(), label = { Text("Перевод слова") }, minLines = 3)
        OutlinedTextField(pSent, { pSent = it; p.promptSentence = it }, Modifier.fillMaxWidth(), label = { Text("Перевод предложения") }, minLines = 3)
        OutlinedTextField(pRetr, { pRetr = it; p.promptRetranslate = it }, Modifier.fillMaxWidth(), label = { Text("Повторный перевод ({current} — текущий)") }, minLines = 3)
        OutlinedTextField(pChatS, { pChatS = it; p.promptChatSentence = it }, Modifier.fillMaxWidth(), label = { Text("Чат о предложении") }, minLines = 3)
        OutlinedTextField(pClean, { pClean = it; p.promptClean = it }, Modifier.fillMaxWidth(), label = { Text("Очистка текста при импорте") }, minLines = 3)
        OutlinedTextField(pChatW, { pChatW = it; p.promptChatWord = it }, Modifier.fillMaxWidth(), label = { Text("Чат о слове") }, minLines = 3)
        OutlinedTextField(pChatT, { pChatT = it; p.promptChatText = it }, Modifier.fillMaxWidth(), label = { Text("Чат о всём тексте") }, minLines = 3)
        OutlinedButton(onClick = {
            pWord = Prompts.WORD; pSent = Prompts.SENTENCE; pChatS = Prompts.CHAT_SENTENCE; pChatT = Prompts.CHAT_TEXT
            pRetr = Prompts.RETRANSLATE; p.promptRetranslate = pRetr
            pChatW = Prompts.CHAT_WORD; p.promptChatWord = pChatW
            pClean = Prompts.CLEAN; p.promptClean = pClean
            p.promptWord = pWord; p.promptSentence = pSent; p.promptChatSentence = pChatS; p.promptChatText = pChatT
            state.say("Стандартные промпты восстановлены")
        }) { Text("Вернуть стандартные") }
    }
}

@Composable
fun SettingsUpdates(state: AppState) {
    var token by remember { mutableStateOf(state.ghToken) }
    Page {
        ListItem(
            leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
            headlineContent = { Text("SatzLoop ${BuildConfig.VERSION_NAME}") },
            supportingContent = { Text(if (state.update != null) "Доступна версия ${state.update?.versionName}" else "Проверяется при каждом запуске") },
        )
        Button(onClick = { state.checkUpdate(quiet = false) }, modifier = Modifier.fillMaxWidth()) { Text("Проверить обновления") }
        if (state.update != null) Button(onClick = { state.installUpdate() }, modifier = Modifier.fillMaxWidth()) {
            Text("Установить ${state.update?.versionName}")
        }
        HorizontalDivider()
        SecretField(token, "GitHub-токен") { token = it; state.applyPrefs { ghToken = it } }
        Hint(
            "Нужен, чтобы скачивать новые версии из приватного репозитория. GitHub → Settings → Developer settings → " +
                "Fine-grained tokens: репозиторий lingq-sentence-splitter, права Contents: Read-only " +
                "(для синхронизации — Read and write).",
        )
    }
}
