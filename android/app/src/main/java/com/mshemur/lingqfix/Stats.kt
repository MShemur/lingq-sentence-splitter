package com.mshemur.lingqfix

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Reading time and words read per day, plus how far each lesson was read (for counting new words only once). */
class ReadingStats private constructor(ctx: Context) {
    companion object {
        @Volatile private var one: ReadingStats? = null
        /** One per process: the app and the Snipd listener write the same file. */
        fun get(ctx: Context): ReadingStats = one ?: synchronized(this) { one ?: ReadingStats(ctx.applicationContext).also { one = it } }
    }

    private val file = File(ctx.filesDir, "stats.json")
    private val days = HashMap<String, IntArray>()     // "2026-11-06" -> [seconds, words, listening seconds]
    private val seen = HashMap<String, Int>()          // "de_123" -> highest sentence index counted
    /** Days of the other devices (from sync): device -> date -> [seconds, words, listening seconds]. */
    private val others = HashMap<String, HashMap<String, IntArray>>()
    private val othersFile = File(ctx.filesDir, "stats_others.json")
    /** Bumped on every change so screens showing the numbers recompose. */
    var version by mutableIntStateOf(0)
        private set

    init {
        runCatching {
            val o = JSONObject(file.readText())
            o.optJSONObject("days")?.let { d -> d.keys().forEach { k -> days[k] = dayOf(d.getJSONArray(k)) } }
            o.optJSONObject("seen")?.let { d -> d.keys().forEach { k -> seen[k] = d.getInt(k) } }
        }
        reloadOthers()
    }

    // --- sync ---

    fun exportDays(): JSONObject = JSONObject().also { d -> days.forEach { (k, v) -> d.put(k, org.json.JSONArray().put(v[0]).put(v[1]).put(v[2])) } }

    /** [seconds, words] of old data or [seconds, words, listening seconds]. */
    private fun dayOf(a: org.json.JSONArray) = intArrayOf(a.optInt(0), a.optInt(1), a.optInt(2))

    fun exportSeen(): JSONObject = JSONObject().also { s -> seen.forEach { (k, v) -> s.put(k, v) } }

    fun importSeen(o: JSONObject) {
        var changed = false
        o.keys().forEach { k -> val v = o.getInt(k); if (v > (seen[k] ?: -1)) { seen[k] = v; changed = true } }
        if (changed) save()
    }

    fun importOthers(o: JSONObject) { runCatching { othersFile.writeText(o.toString()) } }

    fun reloadOthers() {
        others.clear()
        runCatching {
            val o = JSONObject(othersFile.readText())
            o.keys().forEach { dev ->
                val d = o.getJSONObject(dev)
                others[dev] = HashMap<String, IntArray>().apply {
                    d.keys().forEach { k -> put(k, dayOf(d.getJSONArray(k))) }
                }
            }
        }
        version++
    }

    private fun save() {
        val d = JSONObject()
        days.forEach { (k, v) -> d.put(k, org.json.JSONArray().put(v[0]).put(v[1]).put(v[2])) }
        val s = JSONObject()
        seen.forEach { (k, v) -> s.put(k, v) }
        val all = JSONObject().put("days", d).put("seen", s).toString()
        DiskQueue.write { file.writeText(all) }
        version++
    }

    private fun today() = days.getOrPut(LocalDate.now().toString()) { intArrayOf(0, 0, 0) }

    /** [listening]: the time was lesson audio (counted in the total too). */
    @Synchronized fun addTime(sec: Int, listening: Boolean = false) {
        val t = today()
        t[0] += sec
        if (listening) t[2] += sec
        save()
    }

    /** Sentences [from]..[to] (indices in the lesson) are on screen: words not counted before are added to today. */
    fun read(lang: String, lesson: Int, from: Int, to: Int, words: (Int) -> Int) {
        val k = "${lang}_$lesson"
        val start = maxOf(from, (seen[k] ?: -1) + 1)
        if (start > to) return
        var n = 0
        for (i in start..to) n += words(i)
        seen[k] = to
        today()[1] += n
        save()
    }

    /** Totals of a day over all devices: [seconds, words, listening seconds]. */
    fun day(d: LocalDate): IntArray {
        val k = d.toString()
        val out = (days[k] ?: intArrayOf(0, 0, 0)).copyOf()
        others.values.forEach { m -> m[k]?.let { out[0] += it[0]; out[1] += it[1]; out[2] += it[2] } }
        out[2] = minOf(out[2], out[0])
        return out
    }

    /** Totals [seconds, words, listening seconds] from [from] to today. */
    fun since(from: LocalDate): IntArray {
        val out = intArrayOf(0, 0, 0)
        var d = from
        val now = LocalDate.now()
        while (!d.isAfter(now)) { val v = day(d); out[0] += v[0]; out[1] += v[1]; out[2] += v[2]; d = d.plusDays(1) }
        return out
    }

    /** Days in a row with at least a minute of reading, up to today (today may still be empty). */
    fun streak(): Int {
        var d = LocalDate.now()
        if (day(d)[0] < 60) d = d.minusDays(1)
        var n = 0
        while (day(d)[0] >= 60) { n++; d = d.minusDays(1) }
        return n
    }
}

/**
 * While a lesson is open and the app is in front: every 15 s counts as reading time, unless the screen
 * was not touched for 3 minutes (the phone was put aside).
 */
@Composable
fun ReadingTimer(state: AppState) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            // listening to lesson audio, else reading (touched lately); both count in the total
            val reading = state.screen is Screen.Reader && state.resumed && SystemClock.uptimeMillis() - state.lastTouch < 180_000
            if (AudioEngine.playing) state.stats.addTime(15, listening = true)
            else if (reading) state.stats.addTime(15)
        }
    }
}

fun formatTime(sec: Int): String {
    val m = sec / 60
    return if (m < 60) "$m мин" else "${m / 60} ч ${"%02d".format(m % 60)} мин"
}

private fun words(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> "слово"
        m10 in 2..4 && m100 !in 12..14 -> "слова"
        else -> "слов"
    }
    return "${n.toString().reversed().chunked(3).joinToString(" ").reversed()} $w"
}

private fun days(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return "$n " + when {
        m10 == 1 && m100 != 11 -> "день"
        m10 in 2..4 && m100 !in 12..14 -> "дня"
        else -> "дней"
    }
}

/** Statistics: today, this week / month / year, streak, minutes per day for the last 14 days. */
@Composable
fun StatsScreen(state: AppState) {
    val st = state.stats
    @Suppress("UNUSED_VARIABLE") val v = st.version         // recompose on change
    val now = LocalDate.now()
    val today = st.day(now)
    val week = st.since(now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
    val month = st.since(now.withDayOfMonth(1))
    val year = st.since(now.withDayOfYear(1))
    val streak = st.streak()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Сегодня", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(formatTime(today[0]), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(words(today[1]), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Split(AppIcons.Book, "Чтение", today[0] - today[2], MaterialTheme.colorScheme.onPrimaryContainer)
                    Split(AppIcons.Headphones, "Аудио", today[2], MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(
                    if (streak > 0) "Серия: ${days(streak)} подряд" else "Почитайте хотя бы минуту — начнётся серия дней",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PeriodCard("Неделя", week, Modifier.weight(1f))
            PeriodCard("Месяц", month, Modifier.weight(1f))
            PeriodCard("Год", year, Modifier.weight(1f))
        }
        DaysChart(st, now)
        SnipdCard(state)
        Text(
            "Чтение считается, пока открыт урок и вы касаетесь экрана; если 3 минуты ничего не происходит, счёт стоит. " +
                "Аудио — пока играет урок в плеере и, если включено, пока играет Snipd. Общее время — сумма. " +
                "Слова — новые предложения, которые вы впервые увидели в уроке.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PeriodCard(title: String, v: IntArray, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(v[0]), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(words(v[1]), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "чтение ${formatTime(v[0] - v[2])}", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("аудио ${formatTime(v[2])}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Reading or listening part of the time: icon, name, time. */
@Composable
private fun Split(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, sec: Int, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = color)
        Text(" $name ", style = MaterialTheme.typography.bodyMedium, color = color)
        Text(formatTime(sec), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** Legend entry of the chart. */
@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
        Text(" $name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Minutes per day, last 14 days: stacked bars reading (bottom) + audio (top); tap a bar for its numbers. */
@Composable
private fun DaysChart(st: ReadingStats, now: LocalDate) {
    val dates = (13 downTo 0).map { now.minusDays(it.toLong()) }
    val vals = dates.map { st.day(it) }
    val mins = vals.map { it[0] / 60f }
    val listenMins = vals.map { it[2] / 60f }
    val max = maxOf(mins.maxOrNull() ?: 0f, 10f)
    var picked by remember { mutableStateOf(dates.size - 1) }
    val readColor = MaterialTheme.colorScheme.primary
    val listenColor = MaterialTheme.colorScheme.tertiary
    val gapColor = MaterialTheme.colorScheme.surfaceContainerLow
    val base = MaterialTheme.colorScheme.outlineVariant
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Минуты за 2 недели", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot(readColor, "Чтение")
                LegendDot(listenColor, "Аудио")
            }
            val d = dates[picked]
            val dv = vals[picked]
            Text(
                "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale("ru"))}: ${formatTime(dv[0])} · ${words(dv[1])}\n" +
                    "чтение ${formatTime(dv[0] - dv[2])} · аудио ${formatTime(dv[2])}",
                Modifier.padding(top = 4.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.fillMaxWidth().height(120.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val slot = size.width / dates.size
                    val w = minOf(slot * 0.6f, 24.dp.toPx())
                    val r = 4.dp.toPx()
                    mins.forEachIndexed { i, m ->
                        val h = if (m <= 0f) 0f else maxOf(size.height * m / max, 3.dp.toPx())
                        if (h > 0f) {
                            val left = slot * i + (slot - w) / 2
                            val top = size.height - h
                            val a = if (i == picked) 1f else 0.45f
                            // listening is the top part of the bar; the whole bar is rounded at the data end only
                            val lh = if (m > 0f) h * (listenMins[i] / m) else 0f
                            val p = Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        left, top, left + w, size.height,
                                        topLeftCornerRadius = CornerRadius(r, r), topRightCornerRadius = CornerRadius(r, r),
                                    ),
                                )
                            }
                            drawPath(p, readColor.copy(alpha = a), style = Fill)
                            if (lh > 0.5f) {
                                clipRect(left, top, left + w, top + lh) {
                                    drawPath(p, gapColor, style = Fill)
                                    drawPath(p, listenColor.copy(alpha = a), style = Fill)
                                }
                                // 2 dp gap between the two parts
                                if (lh < h - 1f) drawRect(gapColor, Offset(left, top + lh - 1.dp.toPx()), Size(w, 2.dp.toPx()))
                            }
                        }
                    }
                    drawRect(base, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
                }
                // tap targets: the whole column of each day
                Row(Modifier.fillMaxSize()) {
                    dates.indices.forEach { i -> Box(Modifier.weight(1f).fillMaxSize().clickable { picked = i }) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                dates.forEachIndexed { i, dt ->
                    Text(
                        if (i % 2 == 1 || i == dates.size - 1) "${dt.dayOfMonth}" else "",
                        Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Snipd time: on / off and the button to the system switch "Доступ к уведомлениям". */
@Composable
private fun SnipdCard(state: AppState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(SnipdWatch.enabled(ctx)) }
    // the switch is in Android's settings: look again while this screen is shown
    LaunchedEffect(Unit) { while (true) { delay(1500); on = SnipdWatch.enabled(ctx) } }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Время в Snipd", style = MaterialTheme.typography.titleSmall)
            Text(
                if (on) "Включено: пока Snipd играет (и свёрнутый), время идёт в «Аудио»."
                else "Выключено. Чтобы считать, сколько вы слушаете подкасты в Snipd, дайте SatzLoop «Доступ к уведомлениям»: " +
                    "приложение смотрит только, играет ли Snipd, уведомления не читает и никуда не отправляет.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.OutlinedButton(onClick = { SnipdWatch.openSettings(ctx) }) {
                Text(if (on) "Выключить в настройках Android" else "Включить")
            }
        }
    }
}
