@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mshemur.lingqfix

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
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
    /** "text", "sentence" or "word": card label and suggestions. */
    val kind: String = if (wholeText) "text" else "sentence",
    /**
     * The AI may mark words of the lesson (translation above them, as by hand): its "[[отметить: …]]" /
     * "[[снять: …]]" lines go here (mark, list of word / phrase to translation; "" when removing) and are taken
     * out of the shown answer. Returns what was done, shown under the answer.
     */
    private val onMark: ((Boolean, List<Pair<String, String>>) -> String)? = null,
) {
    val messages = mutableStateListOf<Gemini.Msg>()
    var input by mutableStateOf("")
    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** Microphone: dictation language ("ru" or the lesson language), kept in settings. */
    var micLang by mutableStateOf(app.prefs.micLang)
    val lessonLang get() = app.lang

    fun setMic(l: String) { micLang = l; app.prefs.micLang = l }

    /** Dictated text is sent at once (added to what is already typed). */
    fun dictated(t: String) {
        AppLog.add("chat", "Надиктовано (${micLang}): ${t.length} симв.")
        input = (input.trim() + " " + t.trim()).trim()
        fastNext = true
        send()
    }

    fun noRecognizer() = app.say("На телефоне нет распознавания речи (нужно приложение Google)")

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

    /** The next answer comes from the light (fast) model: a dictated question wants a quick reply. */
    private var fastNext = false

    private fun applyMarks(a: String): String {
        val f = onMark ?: return a
        val re = Regex("""\[\[\s*(отметить|снять)\s*:(.*?)]]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val done = mutableListOf<String>()
        re.findAll(a).forEach { m ->
            val mark = m.groupValues[1].lowercase() == "отметить"
            val items = m.groupValues[2].split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { x ->
                val w = x.substringBefore('=').trim().trim('«', '»', '"', '\'')
                w to (if (x.contains('=')) x.substringAfter('=').trim() else "")
            }.filter { it.first.isNotEmpty() }
            if (items.isNotEmpty()) done += f(mark, items)
        }
        if (done.isEmpty()) return a
        AppLog.add("chat", "ИИ: " + done.joinToString("; "))
        return re.replace(a, "").trimEnd() + "\n\n✓ " + done.joinToString("\n✓ ")
    }

    private fun answer() {
        sending = true
        val fast = fastNext.also { fastNext = false }
        app.scope.launch {
            try {
                val history = messages.toList()
                val llm = if (fast) app.geminiFast() else app.gemini()
                val a = withContext(Dispatchers.IO) { llm.generate(system, history) }
                messages.add(Gemini.Msg(false, applyMarks(a), llm.lastReasoning))
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
                app.geminiFailed(e) { retry() }
            }
            sending = false
        }
    }
}

/** The model's thoughts before the answer: folded, a tap opens them. */
@Composable
private fun Reasoning(text: String) {
    var open by androidx.compose.runtime.remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 8.dp)) {
        androidx.compose.material3.TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
            Text(
                if (open) "Скрыть рассуждения ▴" else "Как модель рассуждала ▾",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
        if (open) Text(
            text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        )
    }
}

private val SUGGESTIONS = listOf("Разбери грамматику", "Объясни трудные слова", "Переведи дословно", "Приведи похожие примеры")
private val WORD_SUGGESTIONS = listOf("Что значит здесь?", "Другие значения", "Начальная форма и грамматика", "Примеры употребления", "Синонимы")

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
                            when (c.kind) { "text" -> "Текст"; "word" -> "Слово"; else -> "Предложение" },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        )
                        Text(c.context, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
                    }
                }
            }
            if (c.messages.isEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (c.kind == "word") WORD_SUGGESTIONS else SUGGESTIONS).forEach { q ->
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
                            else Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                m.reasoning?.let { Reasoning(it) }
                                MarkdownText(m.text)
                            }
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
                MicButton(c)
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

/**
 * Microphone in the chat: tap = Android's speech recognition, the text is sent at once;
 * long press = choose the language (Russian / the lesson's language). The label under it shows the language.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MicButton(c: ChatSession) {
    var pick by androidx.compose.runtime.remember { mutableStateOf(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { r ->
        val t = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrBlank()) c.dictated(t)
    }
    fun listen() {
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, c.micLang)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, if (c.micLang == "ru") "Говорите по-русски" else "Sprechen Sie (${c.micLang})")
        try { launcher.launch(i) } catch (e: android.content.ActivityNotFoundException) { c.noRecognizer() }
    }
    Box {
        Column(
            Modifier.padding(start = 6.dp).size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .combinedClickable(enabled = !c.sending, onClick = { listen() }, onLongClick = { pick = true }),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(AppIcons.Mic, contentDescription = "Надиктовать", tint = MaterialTheme.colorScheme.primary)
            Text(c.micLang.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.material3.DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
            listOf("ru" to "Говорить по-русски", c.lessonLang to "Говорить на языке урока (${c.lessonLang.uppercase()})")
                .distinctBy { it.first }.forEach { (l, name) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { pick = false; c.setMic(l); listen() },
                    )
                }
        }
    }
}
