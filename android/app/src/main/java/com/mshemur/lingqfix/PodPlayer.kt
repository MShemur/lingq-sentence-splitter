package com.mshemur.lingqfix

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.security.MessageDigest

// Podcast player: the episodes of the subscribed feeds play here, not as LingQ lessons (no "recent" entry).
// A later task fills in the transcript ("Run AI", PodAi.kt); the transcript is saved per episode in filesDir/pod/.

/** A timed sentence of the transcript (ms). */
data class PodSent(val text: String, val start: Long, val end: Long)

/** An episode in the podcast player. */
data class PodEpisode(
    val key: String, val feedUrl: String, val feedTitle: String, val cover: String, val guid: String,
    val title: String, val audio: String, val date: String = "", val durationS: Int = 0,
)

/** Keys of podcast episodes ("pod_…"); lesson keys are "<lang>_<id>". */
fun isPodKey(k: String?): Boolean = k?.startsWith("pod_") == true

object Pod {
    /** Episode in the player. */
    val now: MutableState<PodEpisode?> = mutableStateOf(null)
    /** Transcript shown ([] = none yet). */
    val sents: SnapshotStateList<PodSent> = mutableStateListOf()
    /** "" or «Расшифровка: часть 2 из 9». */
    val status: MutableState<String> = mutableStateOf("")
    /** "В LingQ" sheet of an episode sent to a lesson from the player (null = none). */
    val sheet: MutableState<BgJob?> = mutableStateOf(null)

    /** The full feed records by key (for "Загрузить как урок"). */
    private val sources = HashMap<String, Pair<Episode, Podcast>>()

    fun key(feedUrl: String, guid: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("$feedUrl\n$guid".toByteArray(Charsets.UTF_8))
        return "pod_" + d.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** The player's episode for a feed episode of [pod] (its record is kept for "Загрузить как урок"). */
    fun of(ep: Episode, pod: Podcast): PodEpisode {
        val g = ep.guid.ifBlank { ep.audio }
        val k = key(pod.feed, g)
        sources[k] = ep to pod
        val date = if (ep.date > 0) java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("ru")).format(java.util.Date(ep.date)) else ""
        return PodEpisode(k, pod.feed, pod.title, pod.image, g, ep.title.ifBlank { "Выпуск" }, ep.audio, date, ep.duration)
    }

    /** Starts the episode from its saved place and opens the player. */
    fun play(app: AppState, ep: PodEpisode) {
        if (now.value?.key != ep.key) {
            sents.clear()
            loadTranscript(app.ctx, ep.key)?.let { sents.addAll(it) }
            status.value = ""
        }
        now.value = ep
        AudioEngine.load(
            app.ctx, ep.key, ep.audio, ep.title, AudioEngine.savedPos(app.ctx, ep.key), ep.cover,
            ep.feedTitle.ifBlank { "SatzLoop" },
        )
        AudioEngine.play()
        if (app.screen !is Screen.PodPlayer) app.go(Screen.PodPlayer)
    }

    /** The episode in the player: the one played, or (after a restart) the one the audio engine still holds; null = none. */
    fun current(): PodEpisode? {
        val k = AudioEngine.key?.takeIf { isPodKey(it) } ?: return null
        return now.value?.takeIf { it.key == k } ?: PodEpisode(k, "", "", AudioEngine.image, "", AudioEngine.title, "")
    }

    fun saveTranscript(ctx: Context, key: String, s: List<PodSent>) {
        val a = org.json.JSONArray()
        s.forEach { a.put(org.json.JSONObject().put("t", it.text).put("s", it.start).put("e", it.end)) }
        val dir = File(ctx.filesDir, "pod").apply { mkdirs() }
        File(dir, "$key.json").writeText(a.toString())
    }

    fun loadTranscript(ctx: Context, key: String): List<PodSent>? {
        val f = File(File(ctx.filesDir, "pod"), "$key.json")
        if (!f.exists()) return null
        return runCatching {
            val a = org.json.JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { PodSent(it.optString("t"), it.optLong("s"), it.optLong("e")) }
        }.getOrNull()
    }

    /** The episode as a feed record (or a bare one for a restored player) and its podcast. */
    private fun asEpisode(ep: PodEpisode): Pair<Episode, Podcast> =
        sources[ep.key] ?: (
            Episode(
                title = ep.title, date = 0L, audio = ep.audio, type = "", duration = ep.durationS, description = "",
                transcript = "", transcriptType = "", textUrl = "", guid = ep.guid,
            ) to Podcast(ep.feedTitle, "", ep.feedUrl, ep.cover)
        )

    /** "Загрузить как урок": the episode goes to LingQ through the "В LingQ" sheet, as from the feed. */
    fun toLesson(app: AppState, ep: PodEpisode) {
        val (e, p) = asEpisode(ep)
        // a transcript already saved in the player (Run AI) goes in as it is: no second recognition
        val saved = loadTranscript(app.ctx, ep.key)?.takeIf { it.isNotEmpty() }
            ?.map { TSeg(it.text, it.start / 1000.0, maxOf(it.start, it.end) / 1000.0) }
        val j = BgJob(app, e, p, saved = saved)
        app.bgJobs.add(0, j)
        sheet.value = j
    }
}

/** Speeds of the podcast player (the speed button steps through them). */
private val POD_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun speedLabel(v: Float): String =
    (if (v == v.toInt().toFloat()) "${v.toInt()}" else "%.2f".format(v).replace(",", ".").trimEnd('0')) + "×"

/** ⋮ of the podcast player's top bar. */
@Composable
fun PodPlayerMenu(state: AppState) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Загрузить как урок") },
                onClick = {
                    menu = false
                    Pod.current()?.takeIf { it.audio.isNotBlank() }?.let { Pod.toLesson(state, it) }
                },
            )
            DropdownMenuItem(
                text = { Text("Открыть подкаст") },
                onClick = {
                    menu = false
                    Pod.current()?.takeIf { it.feedUrl.isNotBlank() }?.let { ep ->
                        state.podcastTab().openFeed(Podcast(ep.feedTitle, "", ep.feedUrl, ep.cover))
                    }
                },
            )
        }
    }
}

/** The podcast player: cover, title, controls; the transcript (current sentence highlighted, tap = seek) or "Run AI". */
@Composable
fun PodPlayerScreen(state: AppState) {
    val ep = Pod.current()
    if (ep == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Сейчас ничего не играет из подкастов.", style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        PodBody(state, ep)
    }
}

@Composable
private fun PodBody(state: AppState, ep: PodEpisode) {
    val ctx = LocalContext.current
    LaunchedEffect(ep.key) {
        if (Pod.sents.isEmpty()) Pod.loadTranscript(ctx, ep.key)?.let { Pod.sents.addAll(it) }
    }
    Pod.sheet.value?.let { j -> EpisodeSheet(state, j) { Pod.sheet.value = null } }

    val mine = AudioEngine.key == ep.key
    val canPlay = mine || ep.audio.isNotBlank()
    val dur = if (mine && AudioEngine.duration > 0) AudioEngine.duration else ep.durationS * 1000L
    val pos = if (mine) AudioEngine.position else AudioEngine.savedPos(ctx, ep.key)
    val cur = if (mine) AudioEngine.nowMs() else pos
    var drag by remember(ep.key) { mutableStateOf<Float?>(null) }
    val sents = Pod.sents
    val hasText = sents.isNotEmpty()
    val busy = PodAi.isRunning(ep.key)
    val digits = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")

    // a place in the episode; the episode starts first when it is not the one loaded
    fun jump(ms: Long) {
        if (!mine) Pod.play(state, ep)
        AudioEngine.seekTo(ms.coerceAtLeast(0L))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Cover(ep.cover, ep.title, Modifier.fillMaxWidth(if (hasText) 0.4f else 0.75f))
        Text(
            ep.title, Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        if (ep.feedTitle.isNotBlank()) Text(
            ep.feedTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmtTime(drag?.let { (it * dur).toLong() } ?: pos), style = digits, modifier = Modifier.width(52.dp))
            Slider(
                value = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { jump((it * dur).toLong()) }; drag = null },
                enabled = canPlay && dur > 0,
                modifier = Modifier.weight(1f),
            )
            Text(if (dur > 0) fmtTime(dur) else "–:––", style = digits, modifier = Modifier.width(52.dp))
        }
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                val i = POD_SPEEDS.indexOfFirst { kotlin.math.abs(it - AudioEngine.speed) < 0.01f }
                AudioEngine.setSpeed(ctx, POD_SPEEDS[(i + 1) % POD_SPEEDS.size])
            }) { Text(speedLabel(AudioEngine.speed)) }
            IconButton(onClick = { jump(cur - 15_000) }, enabled = canPlay) {
                Icon(AppIcons.Replay, contentDescription = "Назад на 15 секунд")
            }
            FilledIconButton(
                onClick = { if (mine) AudioEngine.toggle() else Pod.play(state, ep) },
                enabled = canPlay, modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    if (mine && AudioEngine.playing) AppIcons.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (mine && AudioEngine.playing) "Пауза" else "Слушать",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { jump(cur + 30_000) }, enabled = canPlay) {
                Icon(AppIcons.Forward, contentDescription = "Вперёд на 30 секунд")
            }
        }

        if (hasText) {
            val idx = sents.indexOfLast { it.start <= cur }
            val list = rememberLazyListState()
            LaunchedEffect(idx) { if (idx >= 0) list.animateScrollToItem(maxOf(0, idx - 1)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            if (Pod.status.value.isNotBlank()) Text(
                Pod.status.value, Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 12.dp)) {
                itemsIndexed(sents) { i, s ->
                    val on = i == idx
                    Text(
                        s.text,
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = canPlay) { jump(s.start) }
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (on) FontWeight.SemiBold else null,
                    )
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Button(
                    onClick = { PodAi.run(state) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White),
                ) { Text("Run AI", style = MaterialTheme.typography.titleMedium) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                if (Pod.status.value.isNotBlank()) Text(
                    Pod.status.value, Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
