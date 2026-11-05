@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mshemur.lingqfix

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SuggestionChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Conversation with Gemini about the whole text or one sentence ([system] holds that context). */
class ChatSession(
    private val app: AppState, val title: String, private val system: String, val context: String,
    val wholeText: Boolean = false,
) {
    val messages = mutableStateListOf<Gemini.Msg>()
    var input by mutableStateOf("")
    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || sending) return
        input = ""
        error = null
        messages.add(Gemini.Msg(true, q))
        answer()
    }

    /** After an error: ask Gemini again for the last question (it is not added twice). */
    fun retry() {
        if (sending || messages.lastOrNull()?.user != true) return
        error = null
        answer()
    }

    private fun answer() {
        sending = true
        app.scope.launch {
            try {
                val history = messages.toList()
                val a = withContext(Dispatchers.IO) { app.gemini().generate(system, history) }
                messages.add(Gemini.Msg(false, a))
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            }
            sending = false
        }
    }
}

private val SUGGESTIONS = listOf("Разбери грамматику", "Объясни трудные слова", "Переведи дословно", "Приведи похожие примеры")

@Composable
fun ChatScreen(c: ChatSession) {
    val list = rememberLazyListState()
    LaunchedEffect(c.messages.size, c.sending) { if (c.messages.isNotEmpty()) list.animateScrollToItem(c.messages.size) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (c.wholeText) "Текст" else "Предложение",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        )
                        Text(c.context, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
                    }
                }
            }
            if (c.messages.isEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTIONS.forEach { q ->
                        SuggestionChip(onClick = { c.input = q; c.send() }, label = { Text(q) })
                    }
                }
            }
            items(c.messages) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.user) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(
                        color = if (m.user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = if (m.user) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                        modifier = Modifier.fillMaxWidth(if (m.user) 0.85f else 0.95f).wrapContentWidth(if (m.user) Alignment.End else Alignment.Start),
                    ) {
                        SelectionContainer {
                            if (m.user) Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                            else MarkdownText(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                }
            }
            item {
                if (c.sending) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Gemini пишет…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                c.error?.let {
                    Column {
                        Text("Ошибка: $it", color = MaterialTheme.colorScheme.error)
                        androidx.compose.material3.TextButton(onClick = { c.retry() }) { Text("Повторить") }
                    }
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    c.input, { c.input = it }, Modifier.weight(1f),
                    placeholder = { Text("Спросите о тексте…") }, maxLines = 5, shape = RoundedCornerShape(24.dp),
                )
                FilledIconButton(
                    onClick = { c.send() }, enabled = !c.sending && c.input.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp).size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                }
            }
        }
    }
}
