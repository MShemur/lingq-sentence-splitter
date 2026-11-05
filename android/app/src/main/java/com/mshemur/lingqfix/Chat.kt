package com.mshemur.lingqfix

import androidx.compose.foundation.background
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
class ChatSession(private val app: AppState, val title: String, private val system: String, val context: String) {
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

@Composable
fun ChatScreen(c: ChatSession) {
    val list = rememberLazyListState()
    LaunchedEffect(c.messages.size, c.sending) { if (c.messages.isNotEmpty()) list.animateScrollToItem(c.messages.size) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    c.context, maxLines = 4,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            items(c.messages) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.user) Alignment.CenterEnd else Alignment.CenterStart) {
                    SelectionContainer {
                        Text(
                            m.text,
                            Modifier.widthIn(max = 330.dp)
                                .background(
                                    if (m.user) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(10.dp),
                        )
                    }
                }
            }
            item {
                if (c.sending) CircularProgressIndicator(Modifier.padding(8.dp))
                c.error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    c.input, { c.input = it }, Modifier.weight(1f),
                    placeholder = { Text("Вопрос…") }, maxLines = 5,
                )
                FilledIconButton(onClick = { c.send() }, enabled = !c.sending && c.input.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                }
            }
        }
    }
}
