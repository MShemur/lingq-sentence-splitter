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
import androidx.compose.material.icons.filled.PlayArrow
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
    private var player: ExoPlayer? = null
    private var controller: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null

    /** "<lang>_<lessonId>" of the loaded audio, null = nothing loaded. */
    var key by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
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
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (p.duration > 0) duration = p.duration
                if (state == Player.STATE_ENDED) { position = p.currentPosition; AppLog.add("audio", "Конец аудио") }
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
    fun load(ctx: Context, k: String, url: String, lessonTitle: String, startMs: Long) {
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
        p.setMediaItem(
            MediaItem.Builder().setUri(url).setMediaId(k)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(lessonTitle).setArtist("LingQ Fix").build())
                .build(),
            startMs,
        )
        p.prepare()
        connect(ctx)
        AppLog.add("audio", "Загружено аудио урока $k с ${fmtTime(startMs)}")
    }

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
            position = pos
            if (p.duration > 0) duration = p.duration
            if (stopAt >= 0 && pos >= stopAt) { stopAt = -1; p.pause() }
            if (looping && !waitingGap && (pos >= loopB || pos < loopA - 1500)) {
                repeats++
                if (gap > 0) { waitingGap = true; p.pause(); main.postDelayed(resume, gap) }
                else { p.seekTo(loopA); position = loopA }
            }
            if (System.currentTimeMillis() - lastSave > 5000) save()
            if (p.isPlaying) main.postDelayed(this, 50)
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
    }

    /** Stop and unload (the notification goes away). */
    fun stop() {
        save()
        pause()
        player?.stop(); player?.clearMediaItems()
        controller?.let { MediaController.releaseFuture(it) }; controller = null
        key = null; clearLoop()
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
            if (mine && AudioEngine.looping) Text(
                "Повтор ${fmtTime(AudioEngine.loopA)} – ${fmtTime(AudioEngine.loopB)}" +
                    (if (AudioEngine.repeats > 0) " · ${AudioEngine.repeats + 1}-й раз" else ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            if (mine) AudioEngine.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { speedMenu = true }) { Text("${fmtSpeed(AudioEngine.speed)}×") }
                    DropdownMenu(speedMenu, { speedMenu = false }) {
                        SPEEDS.forEach { v ->
                            DropdownMenuItem(text = { Text("${fmtSpeed(v)}×") }, onClick = { AudioEngine.setSpeed(ctx, v); speedMenu = false })
                        }
                    }
                }
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

private fun fmtSpeed(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}.0" else v.toString().trimEnd('0')

/** Loop choices: this sentence, the last / next N minutes, own A–B, pause between repeats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoopSheet(s: ReaderSession, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Повтор участка", style = MaterialTheme.typography.titleMedium)
            if (AudioEngine.looping) Text(
                "Сейчас: ${fmtTime(AudioEngine.loopA)} – ${fmtTime(AudioEngine.loopB)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(onClick = { s.loopSentence(); onClose() }, enabled = s.hasTimes, modifier = Modifier.fillMaxWidth()) {
                Text("Это предложение")
            }
            Label("Последние (то, что только что прозвучало)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 5).forEach { m ->
                    FilterChip(false, onClick = {
                        val p = AudioEngine.position
                        AudioEngine.setLoop(p - m * 60_000L, p); onClose()
                    }, label = { Text("$m мин") })
                }
            }
            Label("Отсюда вперёд")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 5).forEach { m ->
                    FilterChip(false, onClick = {
                        val p = s.sentenceStartAt(AudioEngine.position)
                        val end = if (AudioEngine.duration > 0) minOf(AudioEngine.duration, p + m * 60_000L) else p + m * 60_000L
                        AudioEngine.setLoop(p, end); onClose()
                    }, label = { Text("$m мин") })
                }
            }
            Label("Свой участок (по началу / концу текущего предложения)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { s.setLoopA() }, modifier = Modifier.weight(1f)) {
                    Text("A: " + if (AudioEngine.loopA >= 0) fmtTime(AudioEngine.loopA) else "начало")
                }
                OutlinedButton(onClick = { s.setLoopB(); if (AudioEngine.looping) onClose() }, modifier = Modifier.weight(1f)) {
                    Text("B: " + if (AudioEngine.loopB >= 0) fmtTime(AudioEngine.loopB) else "конец")
                }
            }
            Label("Пауза между повторами")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0L, 1000L, 2000L, 3000L, 5000L).forEach { g ->
                    FilterChip(AudioEngine.gap == g, onClick = { AudioEngine.setGap(ctx, g) }, label = { Text(if (g == 0L) "нет" else "${g / 1000} с") })
                }
            }
            HorizontalDivider()
            Row {
                TextButton(onClick = { AudioEngine.clearLoop(); onClose() }, enabled = AudioEngine.looping || AudioEngine.loopA >= 0) {
                    Text("Выключить повтор")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { AudioEngine.stop(); onClose() }) { Text("Остановить аудио") }
            }
        }
    }
}

@Composable
private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
