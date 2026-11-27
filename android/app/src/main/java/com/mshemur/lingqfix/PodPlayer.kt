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

    /**
     * Starts the episode from its saved place in the normal player tab (the same player as for lessons).
     * A new episode gets its own session: it loads and plays when the player screen shows it (autoPlay).
     */
    fun play(app: AppState, ep: PodEpisode) {
        if (now.value?.key != ep.key) {
            sents.clear()
            loadTranscript(app.ctx, ep.key)?.let { sents.addAll(it) }
            status.value = ""
        }
        now.value = ep
        val cur = app.player
        if (cur != null && cur.audioKey == ep.key) {
            if (AudioEngine.key != ep.key || !AudioEngine.playing) cur.togglePlay()
        } else {
            app.player = podSession(app, ep).apply { sentenceAtBottom = true; autoPlay = true }
        }
        app.switchTab(Screen.Player)
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
