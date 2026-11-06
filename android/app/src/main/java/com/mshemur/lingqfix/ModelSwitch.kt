package com.mshemur.lingqfix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gemini refused because of limits/overload; [retry] repeats what failed once another model is chosen. */
class ModelSwitch(val model: String, val message: String, val apply: (String) -> Unit, val retry: () -> Unit)

/** Limit / overload errors of Gemini (after its own retries and fallback). */
fun isGeminiLimit(e: Throwable): Boolean {
    val m = e.message.orEmpty()
    return Regex("HTTP (429|503)|RESOURCE_EXHAUSTED|quota|high demand|overloaded", RegexOption.IGNORE_CASE).containsMatchIn(m)
}

private val NOT_TEXT = Regex("image|tts|embed|live|audio|veo|imagen", RegexOption.IGNORE_CASE)

/** "Limit reached" window: explains it and lets the user pick another model; the failed request is repeated. */
@Composable
fun ModelSwitchDialog(state: AppState) {
    val sw = state.modelSwitch ?: return
    var picking by remember(sw) { mutableStateOf(false) }
    var models by remember(sw) { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    fun close() { state.modelSwitch = null }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(if (picking) "Другая модель" else "${state.aiName}: лимит исчерпан") },
        text = {
            when {
                !picking -> Text(
                    "Модель ${sw.model} сейчас не отвечает: закончился лимит запросов или она перегружена. " +
                        "Выберите другую модель — запрос повторится с ней.\n\n${sw.message.take(240)}",
                )
                models == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(models!!) { m ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = false, role = Role.RadioButton) {
                                    sw.apply(m)
                                    close()
                                    state.say("Модель: $m")
                                    sw.retry()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = false, onClick = null)
                            Text(m, Modifier.padding(start = 12.dp))
                            if ("flash" in m) Text(
                                "  быстрая", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!picking) TextButton(onClick = {
                picking = true
                scope.launch {
                    try {
                        models = withContext(Dispatchers.IO) { state.gemini().models() }
                            .filter { it != sw.model && !NOT_TEXT.containsMatchIn(it) }
                    } catch (e: Throwable) {
                        close()
                        state.say(e.message ?: "Не удалось получить список моделей")
                    }
                }
            }) { Text("Попробовать другую модель") }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("Отмена") } },
    )
}
