package com.mshemur.lingqfix

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal of the last actions (screens, taps, requests, errors) for bug reports: kept on the phone for
 * [KEEP_HOURS] hours / [MAX] lines, then dropped. Never contains keys or tokens (only hosts, paths and status codes).
 */
/** Small file writes off the main thread, one after another in the order they were asked for. */
object DiskQueue {
    private val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
    fun write(block: () -> Unit) = ex.execute { runCatching(block) }
}

object AppLog {
    private const val KEEP_HOURS = 48
    private const val MAX = 3000

    private class Line(val t: Long, val text: String)

    private val lines = ArrayDeque<Line>()
    private var file: File? = null
    private var added = 0
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.ROOT)

    /** Changes on every new line (the log screen refreshes). */
    @Volatile var version = 0
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "applog.txt")
        file = f
        runCatching {
            f.readLines().forEach { l ->
                val t = l.substringBefore('\t').toLongOrNull() ?: return@forEach
                lines.addLast(Line(t, l.substringAfter('\t')))
            }
        }
        prune(rewrite = true)
        add("app", "Запуск ${BuildConfig.VERSION_NAME}")
    }

    @Synchronized
    fun add(tag: String, msg: String) {
        val now = System.currentTimeMillis()
        val text = "${fmt.format(Date(now))} [$tag] ${msg.replace('\n', ' ').replace('\t', ' ').take(500)}"
        lines.addLast(Line(now, text))
        version++
        val f = file
        if (f != null) DiskQueue.write { f.appendText("$now\t$text\n") }
        if (++added % 200 == 0) prune(rewrite = true) else if (lines.size > MAX) lines.removeFirst()
    }

    fun error(tag: String, msg: String, e: Throwable) =
        add(tag, "$msg: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}")

    private fun prune(rewrite: Boolean) {
        val limit = System.currentTimeMillis() - KEEP_HOURS * 3600_000L
        while (lines.isNotEmpty() && (lines.first().t < limit || lines.size > MAX)) lines.removeFirst()
        val f = file ?: return
        if (rewrite) { val all = lines.joinToString("") { "${it.t}\t${it.text}\n" }; DiskQueue.write { f.writeText(all) } }
    }

    @Synchronized
    fun clear() {
        lines.clear(); version++
        val f = file
        if (f != null) DiskQueue.write { f.writeText("") }
    }

    /** The whole journal with a header (version, phone) — what is copied. */
    @Synchronized
    fun text(): String =
        "SatzLoop ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Журнал за последние $KEEP_HOURS ч (${lines.size} строк)\n\n" + lines.joinToString("\n") { it.text }

    @Synchronized
    fun last(n: Int): List<String> = lines.toList().takeLast(n).map { it.text }

    /** Path of a URL without the query (no keys ever get into the journal). */
    fun short(url: String): String = url.substringBefore('?').substringAfter("://").substringAfter('/').let { "/$it" }.take(160)
}

/** Settings → Журнал действий: copy or share it for a bug report. */
@Composable
fun LogScreen(state: AppState) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // refresh while the screen is open
        var seen = -1
        while (true) {
            if (AppLog.version != seen) { seen = AppLog.version; tick++ }
            kotlinx.coroutines.delay(1000)
        }
    }
    val shown = remember(tick) { AppLog.last(400).reversed() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Что происходило в приложении за последние 2 дня: экраны, нажатия, запросы, ошибки. Ключей и токенов здесь нет. " +
                "Когда что-то пошло не так — скопируйте и пришлите в чат с Claude.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                AppLog.add("log", "Журнал скопирован")
                clipboard.setText(AnnotatedString(AppLog.text()))
                state.say("Журнал скопирован")
            }) { Text("Копировать") }
            OutlinedButton(onClick = {
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "SatzLoop: журнал")
                    .putExtra(android.content.Intent.EXTRA_TEXT, AppLog.text())
                ctx.startActivity(android.content.Intent.createChooser(i, "Отправить журнал"))
            }) { Text("Поделиться") }
            TextButton(onClick = { AppLog.clear(); tick++ }) { Text("Очистить") }
        }
        Text("Новые сверху:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                shown.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}
