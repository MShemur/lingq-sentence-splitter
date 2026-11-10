package com.mshemur.lingqfix

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken

/**
 * The lesson audio: one player for the whole app (keeps playing outside the reader, with the media notification
 * and lock screen controls through [PlaybackService]). Loops a stretch A–B for drilling, with a pause between repeats.
 * All calls on the main thread.
 */
object AudioEngine {
    /** One lesson of a "play all" queue. */
    class Track(val key: String, val url: String, val title: String, val image: String)

    /** Called when the queue moves on to the lesson with this key. */
    var onTrack: ((String) -> Unit)? = null
    /** Lessons in the queue and the one playing (1 = a single lesson). */
    var queueSize by mutableIntStateOf(1)
        private set
    var queueIndex by mutableIntStateOf(0)
        private set

    private var player: ExoPlayer? = null
    private var controller: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null

    /** "<lang>_<lessonId>" of the loaded audio, null = nothing loaded. */
    var key by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    /** Cover of the lesson playing ("" = none). */
    var image by mutableStateOf("")
        private set

    /** Sentence starts (ms) of the lesson playing, for "replay the sentence" (widget). */
    private var starts: List<Long> = emptyList()
    private var startsKey: String? = null

    fun setStarts(k: String, list: List<Long>) { startsKey = k; starts = list.filter { it >= 0 }.sorted() }

    /** Back to the start of the sentence sounding now (the previous one in its first half second). */
    fun replaySentence() {
        if (startsKey != key || starts.isEmpty()) { seekBy(-5000); return }
        val pos = player?.currentPosition ?: position
        val cur = starts.lastOrNull { it <= pos } ?: 0L
        val target = if (pos - cur < 500) starts.lastOrNull { it < cur } ?: 0L else cur
        seekTo(target)
        if (!playing) play()
    }
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Loop A–B in ms (both set and B > A = looping); repeats done; pause between repeats in ms. */
    var loopA by mutableLongStateOf(-1L)
        private set
    var loopB by mutableLongStateOf(-1L)
        private set
    var repeats by mutableIntStateOf(0)
        private set
    var gap by mutableLongStateOf(0L)
        private set
    val looping get() = loopA >= 0 && loopB > loopA

    private var waitingGap = false
    /** Stop once at this position (play one sentence), -1 = off. */
    private var stopAt = -1L
    private var lastSave = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("audio", Context.MODE_PRIVATE)

    fun savedPos(ctx: Context, k: String): Long = prefs(ctx).getLong("pos.$k", 0L)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun get(ctx: Context): ExoPlayer {
        player?.let { return it }
        val c = ctx.applicationContext
        appCtx = c
        val p = ExoPlayer.Builder(c)
            // loops jump back: keep the last 15 minutes in memory instead of downloading them again
            .setLoadControl(DefaultLoadControl.Builder().setBackBuffer(15 * 60_000, true).build())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true,
            )
            .setHandleAudioBecomingNoisy(true)       // headphones pulled out: pause
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) tick() else save()
                appCtx?.let { PlayerWidget.refresh(it) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (p.duration > 0) duration = p.duration
                if (state == Player.STATE_ENDED) {
                    position = p.currentPosition; AppLog.add("audio", "Конец аудио")
                    if (looping) { repeats++; p.seekTo(loopA); p.play() }     // the loop reached the very end
                }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val k = item?.mediaId ?: return
                queueIndex = p.currentMediaItemIndex
                if (k == key) return
                key?.let { old -> appCtx?.let { prefs(it).edit().putLong("pos.$old", 0L).apply() } }   // finished: next time from the start
                key = k
                title = item.mediaMetadata.title?.toString().orEmpty()
                image = item.mediaMetadata.artworkUri?.toString().orEmpty()
                appCtx?.let { PlayerWidget.refresh(it) }
                clearLoop(); stopAt = -1
                duration = 0
                AppLog.add("audio", "Дальше в очереди: $k")
                onTrack?.invoke(k)
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "Аудио: ${e.errorCodeName}"
                AppLog.error("audio", "Ошибка плеера", e)
            }
        })
        speed = prefs(c).getFloat("speed", 1f)
        gap = prefs(c).getLong("gap", 0L)
        p.setPlaybackSpeed(speed)
        player = p
        return p
    }

    /** The service keeps the player alive in the background; it is bound while a controller is connected. */
    private fun connect(ctx: Context) {
        if (controller != null) return
        val c = ctx.applicationContext
        controller = MediaController.Builder(c, SessionToken(c, ComponentName(c, PlaybackService::class.java))).buildAsync()
    }

    /** Loads the audio of a lesson (if it is not the one loaded), at [startMs]. */
    fun load(ctx: Context, k: String, url: String, lessonTitle: String, startMs: Long, cover: String = "") {
        if (key == k) return
        val p = get(ctx)
        save()
        key = k
        title = lessonTitle
        error = null
        clearLoop()
        stopAt = -1
        duration = 0
        position = startMs
        image = cover
        p.setMediaItem(item(Track(k, url, lessonTitle, cover)), startMs)
        queueSize = 1; queueIndex = 0
        p.prepare()
        connect(ctx)
        PlayerWidget.refresh(ctx)
        AppLog.add("audio", "Загружено аудио урока $k с ${fmtTime(startMs)}")
    }

    private fun item(t: Track): MediaItem = MediaItem.Builder().setUri(t.url).setMediaId(t.key)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(t.title).setArtist("SatzLoop")
                .apply { if (t.image.isNotBlank()) setArtworkUri(android.net.Uri.parse(t.image)) }.build(),
        )
        .build()

    /** "Слушать все": the lessons one after another, the first from [startMs]. */
    fun loadQueue(ctx: Context, tracks: List<Track>, startMs: Long) {
        if (tracks.isEmpty()) return
        val p = get(ctx)
        save()
        key = tracks[0].key
        title = tracks[0].title
        image = tracks[0].image
        error = null
        clearLoop(); stopAt = -1
        duration = 0
        position = startMs
        p.setMediaItems(tracks.map { item(it) }, 0, startMs)
        queueSize = tracks.size; queueIndex = 0
        p.prepare()
        connect(ctx)
        AppLog.add("audio", "Очередь: ${tracks.size} уроков")
    }

    fun nextTrack() { player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun prevTrack() { player?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() } }

    fun play() { val p = player ?: return; if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); waitingGap = false; p.play() }
    fun pause() { waitingGap = false; main.removeCallbacks(resume); player?.pause() }
    fun toggle() { if (playing || waitingGap) pause() else play() }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val t = ms.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        p.seekTo(t); position = t
    }

    fun seekBy(ms: Long) = seekTo((player?.currentPosition ?: position) + ms)

    /** Plays [from]..[to] once and stops (one sentence). */
    fun playOnce(from: Long, to: Long) { clearLoop(); stopAt = to; seekTo(from); play() }

    fun setSpeed(ctx: Context, v: Float) {
        speed = v; player?.setPlaybackSpeed(v)
        prefs(ctx).edit().putFloat("speed", v).apply()
    }

    fun setGap(ctx: Context, ms: Long) { gap = ms; prefs(ctx).edit().putLong("gap", ms).apply() }

    fun setLoop(a: Long, b: Long) {
        loopA = maxOf(0, a); loopB = b; repeats = 0; stopAt = -1
        AppLog.add("audio", "Повтор ${fmtTime(loopA)}–${fmtTime(loopB)}")
        seekTo(loopA); play()
    }

    /** Loop the whole lesson: playing goes on from where it is and starts over at the end. */
    fun loopWhole() {
        val d = duration
        if (d <= 0) return
        loopA = 0; loopB = maxOf(1, d - 200); repeats = 0; stopAt = -1
        AppLog.add("audio", "Повтор всего урока")
        play()
    }

    /** The loop covers the whole lesson. */
    val loopIsWhole get() = looping && loopA <= 0 && duration > 0 && loopB >= duration - 300

    /** "весь урок" or "0:13 – 0:16". */
    fun loopLabel() = if (loopIsWhole) "весь урок" else "${fmtTime(loopA)} – ${fmtTime(loopB)}"

    fun setA(a: Long) { loopA = maxOf(0, a); if (loopB <= loopA) loopB = -1; repeats = 0 }

    fun setB(b: Long) {
        if (loopA < 0 || b <= loopA) { loopA = -1 }
        loopB = b; repeats = 0
        if (looping) { AppLog.add("audio", "Повтор ${fmtTime(loopA)}–${fmtTime(loopB)}"); seekTo(loopA); play() }
    }

    fun clearLoop() { loopA = -1; loopB = -1; repeats = 0; waitingGap = false; main.removeCallbacks(resume) }

    private val resume = Runnable { waitingGap = false; seekTo(loopA); player?.play() }

    /** While playing: position for the screen, the loop and the one-sentence stop. */
    private fun tick() {
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            val pos = p.currentPosition
            if (kotlin.math.abs(pos - position) >= 240) position = pos       // the screen needs ~4 updates a second
            if (p.duration > 0) duration = p.duration
            if (stopAt >= 0 && pos >= stopAt) { stopAt = -1; p.pause() }
            if (looping && !waitingGap && (pos >= loopB || pos < loopA - 1500)) {
                repeats++
                if (gap > 0) { waitingGap = true; p.pause(); main.postDelayed(resume, gap) }
                else { p.seekTo(loopA); position = loopA }
            }
            if (System.currentTimeMillis() - lastSave > 5000) save()
            // exact stops need a fine step; otherwise a quarter second is plenty (battery)
            if (p.isPlaying) main.postDelayed(this, if (looping || stopAt >= 0) 50 else 250)
        }
    }

    private fun save() {
        val k = key ?: return
        val c = appCtx ?: return
        lastSave = System.currentTimeMillis()
        prefs(c).edit().putLong("pos.$k", player?.currentPosition ?: position).apply()
    }

    /** The service is gone: drop the player (a new one is made on the next play). */
    internal fun released() {
        save()
        main.removeCallbacks(ticker); main.removeCallbacks(resume)
        player?.release(); player = null
        controller?.let { MediaController.releaseFuture(it) }; controller = null
        key = null; playing = false; waitingGap = false
        appCtx?.let { PlayerWidget.refresh(it) }
    }

    /** Stop and unload (the notification goes away). */
    fun stop() {
        save()
        pause()
        player?.stop(); player?.clearMediaItems()
        controller?.let { MediaController.releaseFuture(it) }; controller = null
        key = null; clearLoop()
        appCtx?.let { PlayerWidget.refresh(it) }
        AppLog.add("audio", "Аудио остановлено")
    }
}

/** Background playback: media notification, lock screen, headphone buttons. */
class PlaybackService : MediaSessionService() {
    companion object { const val ACTION_PLAYER = "com.mshemur.lingqfix.PLAYER" }

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setAction(ACTION_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, AudioEngine.get(this)).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        AudioEngine.released()
        super.onDestroy()
    }
}

fun fmtTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private val SPEEDS = listOf(0.5f, 0.6f, 0.75f, 0.85f, 1f, 1.1f, 1.25f, 1.5f)

/** Player under the text: time line, play/pause, ±5 s, previous/next sentence, speed, loop. */
@Composable
fun AudioBar(s: ReaderSession) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val mine = AudioEngine.key == s.audioKey
    val dur = if (mine) AudioEngine.duration else 0L
    val pos = if (mine) AudioEngine.position else AudioEngine.savedPos(ctx, s.audioKey)
    var drag by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    if (speedMenu) SpeedSheet { speedMenu = false }
    var loopSheet by remember { mutableStateOf(false) }
    val digits = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(drag?.let { (it * dur).toLong() } ?: pos), style = digits, modifier = Modifier.width(48.dp))
                Slider(
                    value = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                    onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { AudioEngine.seekTo((it * dur).toLong()) }; drag = null },
                    enabled = mine && dur > 0,
                    modifier = Modifier.weight(1f).height(32.dp),
                )
                Text(if (dur > 0) fmtTime(dur) else "–:––", style = digits, modifier = Modifier.width(48.dp).padding(start = 6.dp))
            }
            // a running loop: a pill (tap = the loop sheet, x = stop at once)
            if (mine && AudioEngine.looping) Surface(
                shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable { loopSheet = true }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Repeat, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            " Повтор ${AudioEngine.loopLabel()}" +
                                (if (AudioEngine.repeats > 0) " · ${AudioEngine.repeats + 1}-й раз" else ""),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    IconButton(onClick = { AudioEngine.clearLoop() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Остановить повтор", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            if (mine && AudioEngine.queueSize > 1) Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { AudioEngine.prevTrack() }, enabled = AudioEngine.queueIndex > 0) { Text("‹ Урок") }
                Text(
                    "${AudioEngine.queueIndex + 1} из ${AudioEngine.queueSize}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { AudioEngine.nextTrack() }, enabled = AudioEngine.queueIndex < AudioEngine.queueSize - 1) { Text("Урок ›") }
            }
            if (mine) AudioEngine.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { speedMenu = true }) { Text("${fmtSpeed(AudioEngine.speed)}×") }
                IconButton(onClick = { s.prevSentence() }, enabled = mine) { Icon(AppIcons.SkipPrevious, contentDescription = "Предыдущее предложение") }
                IconButton(onClick = { AudioEngine.seekBy(-5000) }, enabled = mine) { Icon(AppIcons.Replay, contentDescription = "Назад на 5 секунд") }
                FilledIconButton(onClick = { s.togglePlay() }, modifier = Modifier.size(52.dp)) {
                    Icon(
                        if (mine && AudioEngine.playing) AppIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (mine && AudioEngine.playing) "Пауза" else "Слушать", modifier = Modifier.size(30.dp),
                    )
                }
                IconButton(onClick = { AudioEngine.seekBy(5000) }, enabled = mine) { Icon(AppIcons.Forward, contentDescription = "Вперёд на 5 секунд") }
                IconButton(onClick = { s.nextSentence() }, enabled = mine) { Icon(AppIcons.SkipNext, contentDescription = "Следующее предложение") }
                IconButton(
                    onClick = { loopSheet = true }, enabled = mine,
                    colors = if (mine && AudioEngine.looping) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
                ) { Icon(AppIcons.Repeat, contentDescription = "Повтор участка") }
            }
        }
    }
    if (loopSheet) LoopSheet(s) { loopSheet = false }
}

private fun fmtSpeed(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}.0" else "%.2f".format(v).replace(",", ".").trimEnd('0')

/** Loop sheet: a running loop and its stop first, then this sentence / whole lesson, a segment, own A–B, pause. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoopSheet(s: ReaderSession, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var back by remember { mutableStateOf(true) }     // segment: what just sounded / from here on
    var own by remember { mutableStateOf(AudioEngine.loopA >= 0 && !AudioEngine.looping) }
    val on = AudioEngine.looping
    fun segment(sec: Int) {
        val ms = sec * 1000L
        if (back) {
            val p = AudioEngine.position
            AudioEngine.setLoop(p - ms, p)
        } else {
            val p = s.sentenceStartAt(AudioEngine.position)
            val d = AudioEngine.duration
            AudioEngine.setLoop(p, if (d > 0) minOf(d, p + ms) else p + ms)
        }
        onClose()
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Повтор", style = MaterialTheme.typography.titleLarge)
            if (on) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Repeat, contentDescription = null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(
                                "Повторяется ${AudioEngine.loopLabel()}",
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                if (AudioEngine.repeats > 0) "${AudioEngine.repeats + 1}-й раз" else "первый раз",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                Button(onClick = { AudioEngine.clearLoop(); onClose() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Остановить повтор", style = MaterialTheme.typography.titleMedium)
                }
            }
            LoopTile(
                if (on) "Повторять текущее предложение" else "Повторять это предложение",
                if (!s.hasTimes) "в уроке нет разметки времени" else null, main = !on, enabled = s.hasTimes,
            ) { s.loopSentence(); onClose() }
            val d = AudioEngine.duration
            LoopTile("Повторять весь урок", if (d > 0) "${fmtTime(d)}, по кругу" else null, main = false, enabled = d > 0 && !AudioEngine.loopIsWhole) {
                AudioEngine.loopWhole(); onClose()
            }
            Label("Отрезок")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(back, { back = true }, SegmentedButtonDefaults.itemShape(0, 2), icon = {}) {
                    Text("Только что прозвучало", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                SegmentedButton(!back, { back = false }, SegmentedButtonDefaults.itemShape(1, 2), icon = {}) {
                    Text("Отсюда вперёд", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120, 300).forEach { sec ->
                    OutlinedButton(
                        onClick = { segment(sec) }, modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text(if (sec < 60) "$sec с" else "${sec / 60} мин") }
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { own = !own }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Свой участок A – B", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(if (own) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            if (own) {
                Text(
                    "Нажмите A в начале нужного места и B в конце (по границам предложений).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val aText = "A · " + if (AudioEngine.loopA >= 0) fmtTime(AudioEngine.loopA) else "начало"
                    val bText = "B · " + if (AudioEngine.loopB >= 0) fmtTime(AudioEngine.loopB) else "конец"
                    if (AudioEngine.loopA >= 0) FilledTonalButton(onClick = { s.setLoopA() }, modifier = Modifier.weight(1f)) { Text(aText) }
                    else OutlinedButton(onClick = { s.setLoopA() }, modifier = Modifier.weight(1f)) { Text(aText) }
                    val onB = { s.setLoopB(); if (AudioEngine.looping) onClose() }
                    if (AudioEngine.loopB >= 0) FilledTonalButton(onClick = onB, enabled = AudioEngine.loopA >= 0, modifier = Modifier.weight(1f)) { Text(bText) }
                    else OutlinedButton(onClick = onB, enabled = AudioEngine.loopA >= 0, modifier = Modifier.weight(1f)) { Text(bText) }
                }
            }
            Label("Пауза между повторами")
            val gaps = listOf(0L, 1000L, 2000L, 3000L, 5000L)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                gaps.forEachIndexed { n, g ->
                    SegmentedButton(AudioEngine.gap == g, { AudioEngine.setGap(ctx, g) }, SegmentedButtonDefaults.itemShape(n, gaps.size), icon = {}) {
                        Text(if (g == 0L) "нет" else "${g / 1000} с")
                    }
                }
            }
        }
    }
}

/** A big choice of the loop sheet; [main] = filled with the accent colour. */
@Composable
private fun LoopTile(title: String, sub: String?, main: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (main) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (main) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Repeat, contentDescription = null, Modifier.size(24.dp), tint = fg)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = fg)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
