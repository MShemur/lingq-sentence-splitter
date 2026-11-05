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
import androidx.compose.material3.Button
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
            (if (p.translator == "gemini") "Gemini · ${p.geminiModel}" else "LingQ") + " → ${state.trLang}",
        ) { state.go(Screen.SettingsTranslation) }
        SectionItem(Icons.Filled.Star, "Промпты Gemini", "Как Gemini переводит и отвечает в чате") {
            state.go(Screen.SettingsPrompts)
        }
        HorizontalDivider()
        SectionItem(Icons.Filled.Refresh, "Резервные копии", "Вернуть урок к версии до исправления") {
            state.go(Screen.Backups)
        }
        SectionItem(Icons.Filled.Build, "Обновления", "Версия ${BuildConfig.VERSION_NAME}") {
            state.go(Screen.SettingsUpdates)
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
private fun SecretField(value: String, label: String, onChange: (String) -> Unit) {
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
    var gModel by remember { mutableStateOf(p.geminiModel) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
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
            "gemini" to ("Gemini" to "Перевод слова по смыслу предложения, чат. Нужен ключ"),
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
        HorizontalDivider()
        Text("Gemini", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SecretField(gKey, "Ключ Gemini API") { gKey = it; state.applyPrefs { geminiKey = it } }
        TextButton(onClick = { uri.openUri("https://aistudio.google.com/apikey") }) { Text("Получить ключ бесплатно") }
        Box {
            OutlinedTextField(
                gModel, { gModel = it.trim(); state.applyPrefs { geminiModel = it } }, Modifier.fillMaxWidth(),
                label = { Text("Модель") }, singleLine = true,
                supportingText = { Text("flash — быстро; pro — умнее, медленнее") },
                trailingIcon = {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                models = withContext(Dispatchers.IO) { Gemini(gKey.trim(), gModel).models() }
                                modelMenu = true
                            } catch (e: Exception) {
                                state.say(e.message ?: "Ошибка")
                            }
                        }
                    }) { Text("Выбрать") }
                },
            )
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                models.forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = {
                        gModel = m; state.applyPrefs { geminiModel = m }; modelMenu = false
                    })
                }
            }
        }
    }
}

@Composable
fun SettingsPrompts(state: AppState) {
    val p = state.prefs
    var pWord by remember { mutableStateOf(p.promptWord) }
    var pSent by remember { mutableStateOf(p.promptSentence) }
    var pChatS by remember { mutableStateOf(p.promptChatSentence) }
    var pChatT by remember { mutableStateOf(p.promptChatText) }
    Page {
        Hint(
            "Подстановки: {lang} язык текста, {target} язык перевода, {word} слово, {sentence} предложение, " +
                "{text} весь текст. Промпт слова должен просить ответ JSON с полями best, options, note.",
        )
        OutlinedTextField(pWord, { pWord = it; p.promptWord = it }, Modifier.fillMaxWidth(), label = { Text("Перевод слова") }, minLines = 3)
        OutlinedTextField(pSent, { pSent = it; p.promptSentence = it }, Modifier.fillMaxWidth(), label = { Text("Перевод предложения") }, minLines = 3)
        OutlinedTextField(pChatS, { pChatS = it; p.promptChatSentence = it }, Modifier.fillMaxWidth(), label = { Text("Чат о предложении") }, minLines = 3)
        OutlinedTextField(pChatT, { pChatT = it; p.promptChatText = it }, Modifier.fillMaxWidth(), label = { Text("Чат о всём тексте") }, minLines = 3)
        OutlinedButton(onClick = {
            pWord = Prompts.WORD; pSent = Prompts.SENTENCE; pChatS = Prompts.CHAT_SENTENCE; pChatT = Prompts.CHAT_TEXT
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
            headlineContent = { Text("LingQ Fix ${BuildConfig.VERSION_NAME}") },
            supportingContent = { Text(if (state.update != null) "Доступна версия ${state.update?.versionName}" else "Проверяется при каждом запуске") },
        )
        Button(onClick = { state.checkUpdate(quiet = false) }, modifier = Modifier.fillMaxWidth()) { Text("Проверить обновления") }
        if (state.update != null) Button(onClick = { state.installUpdate() }, modifier = Modifier.fillMaxWidth()) {
            Text("Установить ${state.update?.versionName}")
        }
        HorizontalDivider()
        SecretField(token, "GitHub-токен (только чтение)") { token = it; state.applyPrefs { ghToken = it } }
        Hint(
            "Нужен, чтобы скачивать новые версии из приватного репозитория. GitHub → Settings → Developer settings → " +
                "Fine-grained tokens: репозиторий lingq-sentence-splitter, права Contents: Read-only.",
        )
    }
}
