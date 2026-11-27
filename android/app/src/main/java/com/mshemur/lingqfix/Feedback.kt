package com.mshemur.lingqfix

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Feedback / tasks for Claude: each note is a file `feedback/<stamp>-android.json` on the `sync` branch of the
 * repo (the sync token already may write there). Claude reads them, works on them and writes back `status`
 * ("new" / "work" / "done" / "no"), `version` and `reply` into the same file; the app shows that.
 * Pictures go next to it as `feedback/<stamp>-android-<n>.jpg` and are listed in its `images` array.
 */
object Feedback {
    private const val API = "https://api.github.com/repos/MShemur/lingq-sentence-splitter"
    private const val BRANCH = "sync"
    private const val MAX_PICTURES = 4
    private const val MAX_SIDE = 1600

    class Item(
        val name: String, val time: Long, val device: String, val text: String, val status: String, val version: String,
        val reply: String, val images: List<String>,
    )

    private fun req(token: String, url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun call(r: Request): Pair<Int, String> =
        LingqApi.http.newCall(r).execute().use { it.code to it.body?.string().orEmpty() }

    private fun fail(code: Int, text: String): Nothing {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
        throw ApiException(
            when (code) {
                401 -> "GitHub не принял токен"
                403, 404 -> "У токена нет права записи (Contents: Read and write) или нет ветки синхронизации — $msg"
                else -> "GitHub: HTTP $code $msg"
            },
        )
    }

    /** Creates one file on the sync branch (base64 content, no sha: a new file). */
    private fun putFile(token: String, path: String, bytes: ByteArray, message: String) {
        val body = JSONObject().put("message", message).put("branch", BRANCH)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val (code, t) = call(req(token, "$API/contents/$path").put(body.toString().toRequestBody("application/json".toMediaType())).build())
        if (code !in 200..299) fail(code, t)
    }

    /** Decodes a picked picture, turns it upright (API 28+) and scales it to MAX_SIDE, JPEG q80. Run off the main thread. */
    private fun shrink(cr: ContentResolver, uri: Uri): ByteArray {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
                val long = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (long / (sample * 2) >= MAX_SIDE) sample *= 2
                dec.setTargetSampleSize(sample)
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw ApiException("нет доступа к файлу")
            val long = maxOf(bounds.outWidth, bounds.outHeight)
            if (long <= 0) throw ApiException("файл не картинка")
            var sample = 1
            while (long / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: throw ApiException("нет доступа к файлу")
        }
        val k = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val small = if (k < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).roundToInt().coerceAtLeast(1), (bmp.height * k).roundToInt().coerceAtLeast(1), true)
        } else bmp
        val out = ByteArrayOutputStream()
        val ok = small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (small !== bmp) small.recycle()
        bmp.recycle()
        if (!ok) throw ApiException("не удалось сжать картинку")
        return out.toByteArray()
    }

    /**
     * Sends the note with its pictures. Returns the numbers (1..4) of pictures that did not upload;
     * the note is sent anyway, with only the pictures that did upload in "images".
     */
    fun send(token: String, text: String, log: String?, cr: ContentResolver, pictures: List<Uri>): List<Int> {
        if (token.isBlank()) throw ApiException("Нет токена GitHub (⚙ Настройки → Синхронизация)")
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val images = mutableListOf<String>()
        val failed = mutableListOf<Int>()
        pictures.take(MAX_PICTURES).forEachIndexed { i, uri ->
            val n = i + 1
            val path = "feedback/$stamp-android-$n.jpg"
            try {
                putFile(token, path, shrink(cr, uri), "feedback image from android")
                images += path
            } catch (e: Throwable) {
                AppLog.error("feedback", "Картинка $n не отправлена", e)
                failed += n
            }
        }
        val o = JSONObject().put("t", now).put("device", "android").put("version", BuildConfig.VERSION_NAME)
            .put("text", text.trim()).put("status", "new").put("images", JSONArray(images))
        if (!log.isNullOrBlank()) o.put("log", log.takeLast(60_000))
        putFile(token, "feedback/$stamp-android.json", o.toString(2).toByteArray(), "feedback from android")
        AppLog.add("feedback", "Отзыв отправлен ($stamp)" + if (images.size > 0) ", картинок: ${images.size}" else "")
        return failed
    }

    /** The latest notes (both devices), newest first. */
    fun list(token: String, max: Int = 30): List<Item> {
        if (token.isBlank()) return emptyList()
        val (code, t) = call(req(token, "$API/contents/feedback?ref=$BRANCH").build())
        if (code == 404) return emptyList()
        if (code != 200) fail(code, t)
        val a = JSONArray(t)
        val names = (0 until a.length()).map { a.getJSONObject(it).optString("name") }.filter { it.endsWith(".json") }
            .sortedDescending().take(max)
        return names.mapNotNull { n ->
            val (c, x) = call(req(token, "$API/contents/feedback/$n?ref=$BRANCH").header("Accept", "application/vnd.github.raw").build())
            if (c != 200) return@mapNotNull null
            runCatching {
                val o = JSONObject(x)
                val arr = o.optJSONArray("images")
                val images: List<String> = if (arr == null) emptyList<String>() else (0 until arr.length()).map { arr.optString(it) }
                Item(n, o.optLong("t"), o.optString("device"), o.optString("text"), o.optString("status", "new"),
                    o.optString("doneIn"), o.optString("reply"), images)
            }.getOrNull()
        }
    }
}

/** Settings → "Отзыв / задача": write a note for Claude, see what became of the earlier ones. */
@Composable
fun FeedbackScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var withLog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<Feedback.Item>?>(null) }
    var pics by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    fun reload() {
        scope.launch {
            items = try { withContext(Dispatchers.IO) { Feedback.list(state.ghToken) } } catch (e: Throwable) { error = e.message; emptyList() }
        }
    }
    LaunchedEffect(Unit) { reload() }
    // photo picker (images only); the list is capped at 4 pictures
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(4),
    ) { uris -> pics = (pics + uris).distinct().take(4) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Напишите, что поправить или добавить. Запись уходит в GitHub; Claude проверяет новые раз в час, " +
                "делает и отвечает здесь (с номером версии).",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // dictation: Google's voice window (with its own "listening" animation); the words are added to the note
        var noMic by remember { mutableStateOf(false) }
        val mic = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { r ->
            val t = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!t.isNullOrBlank()) text = if (text.isBlank()) t else text.trimEnd() + " " + t
        }
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp),
            placeholder = { Text("Например: в плеере кнопка ⏭ слишком близко к краю") }, enabled = !busy,
            trailingIcon = {
                androidx.compose.material3.IconButton(enabled = !busy, onClick = {
                    val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru")
                        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Говорите — текст добавится в отзыв")
                    try { mic.launch(i) } catch (e: android.content.ActivityNotFoundException) { noMic = true }
                }) { androidx.compose.material3.Icon(AppIcons.Mic, contentDescription = "Надиктовать") }
            },
        )
        if (noMic) Text("На телефоне нет распознавания речи Google", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        // pictures: thumbnails with ✕, up to 4
        OutlinedButton(
            onClick = { picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = !busy && pics.size < 4,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Прикрепить картинку")
            }
        }
        if (pics.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pics.forEachIndexed { i, uri ->
                Box(Modifier.size(72.dp)) {
                    AsyncImage(
                        model = uri, contentDescription = "Картинка ${i + 1}", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    IconButton(
                        onClick = { pics = pics - uri }, enabled = !busy,
                        modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                    ) { Icon(Icons.Filled.Close, contentDescription = "Убрать", modifier = Modifier.size(16.dp)) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = withLog, onCheckedChange = { withLog = it }, enabled = !busy)
            Text("Приложить журнал действий (для ошибок)")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val failed = withContext(Dispatchers.IO) {
                            Feedback.send(state.ghToken, text, if (withLog) AppLog.text() else null, ctx.contentResolver, pics)
                        }
                        text = ""; withLog = false; pics = emptyList()
                        if (failed.isEmpty()) state.say("Отправлено")
                        else state.say("Отправлено, но не загрузились картинки: ${failed.joinToString(", ")}")
                        reload()
                    } catch (e: Throwable) {
                        AppLog.error("feedback", "Отзыв не отправлен", e)
                        error = e.message ?: e.toString()
                    }
                    busy = false
                }
            },
            enabled = !busy && text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Отправить") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Отправленные", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { items = null; reload() }) { Text("Обновить") }
        }
        val list = items
        when {
            list == null -> CircularProgressIndicator(Modifier.size(24.dp))
            list.isEmpty() -> Text("Пока ничего", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> list.forEach { f -> FeedbackCard(f) }
        }
    }
}

@Composable
private fun FeedbackCard(f: Feedback.Item) {
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val st = when (f.status) {
                "work" -> "⏳ в работе"
                "done" -> "✅ готово" + if (f.version.isNotBlank()) " в ${f.version}" else ""
                "no" -> "✖ не делаю"
                else -> "🕓 ждёт"
            }
            Text(
                "${fmt.format(Date(f.time))} · ${if (f.device == "web") "iPad" else "телефон"} · $st",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
            )
            Text(f.text, style = MaterialTheme.typography.bodyMedium)
            if (f.images.isNotEmpty()) Text(
                "📎 ${f.images.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
            )
            if (f.reply.isNotBlank()) Text(
                "Claude: ${f.reply}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
