package com.mshemur.lingqfix

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

// --- skeletons: grey shapes that breathe while content loads ---

@Composable
fun Modifier.skeleton(shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.06f, 0.14f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "alpha")
    return this.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = a))
}

/** Placeholder of a cover tile with its title. */
@Composable
fun TileSkeleton(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).skeleton(RoundedCornerShape(16.dp)))
        Box(Modifier.fillMaxWidth(0.8f).height(14.dp).skeleton(RoundedCornerShape(7.dp)))
        Box(Modifier.fillMaxWidth(0.5f).height(12.dp).skeleton(RoundedCornerShape(6.dp)))
    }
}

/** Placeholder of a lesson card in the player list. */
@Composable
fun CardSkeleton() {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp).skeleton(RoundedCornerShape(16.dp)))
        Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.9f).height(16.dp).skeleton(RoundedCornerShape(8.dp)))
            Box(Modifier.fillMaxWidth(0.6f).height(12.dp).skeleton(RoundedCornerShape(6.dp)))
            Box(Modifier.fillMaxWidth(0.3f).height(12.dp).skeleton(RoundedCornerShape(6.dp)))
        }
    }
}

// --- home: greeting, today's minutes ring, streak ---

const val DAILY_GOAL_MIN = 20

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Доброе утро"
    in 12..17 -> "Добрый день"
    in 18..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}

/** Top of the library: today's reading/listening against the daily goal and the streak. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayCard(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val v = state.stats.version      // recompose when the minutes change
    val today = state.stats.day(LocalDate.now())
    val min = today[0] / 60
    val streak = state.stats.streak()
    Card(
        onClick = { state.go(Screen.Stats) },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { (min.toFloat() / DAILY_GOAL_MIN).coerceIn(0f, 1f) },
                    modifier = Modifier.size(76.dp), strokeWidth = 8.dp, strokeCap = StrokeCap.Round,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$min", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("мин", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(greeting(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (min >= DAILY_GOAL_MIN) "Цель на сегодня выполнена" else "Сегодня $min из $DAILY_GOAL_MIN мин" +
                        if (today[1] > 0) " · ${today[1]} слов" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (streak > 0) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(
                        "🔥 $streak " + plural(streak, "день", "дня", "дней") + " подряд",
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}

// --- mini player above the bottom tabs ---

/** Thin bar while audio is loaded and the player tab is not on screen: cover, title, progress, play/pause. */
@Composable
fun MiniPlayer(state: AppState) {
    if (AudioEngine.key == null) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().clickable { state.switchTab(Screen.Player) }) {
            val d = AudioEngine.duration
            LinearProgressIndicator(
                progress = { if (d > 0) (AudioEngine.position.toFloat() / d).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(AudioEngine.image, AudioEngine.title, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(AudioEngine.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        fmtTime(AudioEngine.position) + if (d > 0) " / ${fmtTime(d)}" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { AudioEngine.seekBy(-5000) }) { Icon(AppIcons.Replay, contentDescription = "Назад на 5 секунд") }
                IconButton(onClick = { AudioEngine.toggle() }) {
                    Icon(
                        if (AudioEngine.playing) AppIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (AudioEngine.playing) "Пауза" else "Слушать", modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }
}

// --- speed: a slider instead of a list ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var v by remember { mutableFloatStateOf(AudioEngine.speed) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Скорость", style = MaterialTheme.typography.titleMedium)
            Text(
                "%.2f×".format(v).replace(",", ".").replace(".00×", ".0×"),
                style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Slider(
                value = v, onValueChange = { v = (it * 20).toInt() / 20f; AudioEngine.setSpeed(ctx, v) },
                valueRange = 0.5f..2f, steps = 29,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0.6f, 0.75f, 0.85f, 1f, 1.25f, 1.5f).forEach { p ->
                    FilterChip(v == p, onClick = { v = p; AudioEngine.setSpeed(ctx, p) }, label = { Text(if (p == 1f) "1×" else "$p") })
                }
            }
        }
    }
}

// --- home screen widget of the player ---

object PlayerWidget {
    const val ACTION_TOGGLE = "com.mshemur.lingqfix.W_TOGGLE"
    const val ACTION_BACK = "com.mshemur.lingqfix.W_BACK"
    const val ACTION_SENTENCE = "com.mshemur.lingqfix.W_SENTENCE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastImage = ""
    @Volatile private var lastBitmap: android.graphics.Bitmap? = null

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        // read the player state on the main thread, draw on a worker
        val loaded = AudioEngine.key != null
        val title = AudioEngine.title
        val image = AudioEngine.image
        val playing = AudioEngine.playing
        scope.launch { runCatching { render(app, loaded, title, image, playing) } }
    }

    private fun pi(ctx: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, action.hashCode(), Intent(ctx, PlayerWidgetProvider::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    suspend fun render(ctx: Context, loaded: Boolean, title: String, image: String, playing: Boolean) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val v = RemoteViews(ctx.packageName, R.layout.widget_player)
        val open = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, MainActivity::class.java).setAction(PlaybackService.ACTION_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        v.setOnClickPendingIntent(R.id.root, open)
        if (!loaded) {
            v.setTextViewText(R.id.label, "SatzLoop")
            v.setTextViewText(R.id.title, "Ничего не играет — открыть плеер")
            v.setImageViewResource(R.id.cover, R.drawable.ic_launcher)
            v.setImageViewResource(R.id.toggle, R.drawable.wg_play)
            // without a player in memory the buttons open the app
            listOf(R.id.back, R.id.toggle, R.id.sentence).forEach { v.setOnClickPendingIntent(it, open) }
        } else {
            v.setTextViewText(R.id.label, if (playing) "Сейчас играет" else "На паузе")
            v.setTextViewText(R.id.title, title)
            v.setImageViewResource(R.id.toggle, if (playing) R.drawable.wg_pause else R.drawable.wg_play)
            v.setOnClickPendingIntent(R.id.back, pi(ctx, ACTION_BACK))
            v.setOnClickPendingIntent(R.id.toggle, pi(ctx, ACTION_TOGGLE))
            v.setOnClickPendingIntent(R.id.sentence, pi(ctx, ACTION_SENTENCE))
            val bmp = if (image.isBlank()) null else if (image == lastImage && lastBitmap != null) lastBitmap
            else ContinueWidget.cover(ctx, image)?.also { lastImage = image; lastBitmap = it }
            if (bmp != null) v.setImageViewBitmap(R.id.cover, bmp) else v.setImageViewResource(R.id.cover, R.drawable.ic_launcher)
        }
        mgr.updateAppWidget(ids, v)
    }
}

class PlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        PlayerWidget.refresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (AudioEngine.key == null) { PlayerWidget.refresh(ctx); return }   // the app was closed: show "open the player"
        when (intent.action) {
            PlayerWidget.ACTION_TOGGLE -> AudioEngine.toggle()
            PlayerWidget.ACTION_BACK -> AudioEngine.seekBy(-5000)
            PlayerWidget.ACTION_SENTENCE -> AudioEngine.replaySentence()
            else -> return
        }
        PlayerWidget.refresh(ctx)
    }
}
