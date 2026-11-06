package com.mshemur.lingqfix

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Image loader for covers (app and widget): LingQ's CDN wants a non-default user agent. */
object Covers {
    @Volatile private var loader: ImageLoader? = null

    fun loader(ctx: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(ctx.applicationContext)
            .okHttpClient {
                LingqApi.http.newBuilder().addInterceptor { ch ->
                    ch.proceed(ch.request().newBuilder().header("User-Agent", "lingq-lesson-fixer/1.0").build())
                }.build()
            }
            .crossfade(true)
            .build().also { loader = it }
    }
}

/** Home screen widget "Продолжить чтение": the lesson opened last, its cover and how far it is read. */
object ContinueWidget {
    const val ACTION_OPEN = "com.mshemur.lingqfix.OPEN_LESSON"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE)

    /** A lesson was opened in the reader. */
    fun save(ctx: Context, lang: String, l: Lesson) {
        val p = prefs(ctx)
        val sameLesson = p.getInt("id", -1) == l.id
        p.edit()
            .putString("lang", lang).putInt("id", l.id).putString("title", l.title)
            .putString("course", l.courseTitle.ifBlank { if (sameLesson) p.getString("course", "") else "" })
            .putString("image", l.imageUrl.ifBlank { if (sameLesson) p.getString("image", "") else "" })
            .apply()
        refresh(ctx)
    }

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { render(app) } }
    }

    suspend fun render(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, ContinueWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val p = prefs(ctx)
        val v = RemoteViews(ctx.packageName, R.layout.widget_continue)
        val id = p.getInt("id", -1)
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (id < 0) {
            v.setTextViewText(R.id.title, "Откройте урок в приложении")
            v.setTextViewText(R.id.course, "Здесь появится последний урок")
            v.setViewVisibility(R.id.progressRow, View.GONE)
            v.setImageViewResource(R.id.cover, R.drawable.ic_launcher)
        } else {
            val lang = p.getString("lang", "de").orEmpty()
            val title = p.getString("title", "").orEmpty()
            v.setTextViewText(R.id.title, title.ifBlank { "Урок $id" })
            v.setTextViewText(R.id.course, p.getString("course", "").orEmpty())
            val pct = (ReadingProgress(ctx).read(lang, id) * 100).toInt()
            v.setViewVisibility(R.id.progressRow, View.VISIBLE)
            v.setProgressBar(R.id.progress, 100, pct, false)
            v.setTextViewText(R.id.percent, "$pct %")
            val bmp = p.getString("image", "").orEmpty().takeIf { it.isNotBlank() }?.let { cover(ctx, it) }
            if (bmp != null) v.setImageViewBitmap(R.id.cover, bmp) else v.setImageViewResource(R.id.cover, R.drawable.ic_launcher)
            open.setAction(ACTION_OPEN).putExtra("lang", lang).putExtra("id", id).putExtra("title", title)
        }
        val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        v.setOnClickPendingIntent(R.id.root, pi)
        mgr.updateAppWidget(ids, v)
    }

    /** Cover as a small bitmap with rounded corners (widgets cannot clip views on older Android). */
    private suspend fun cover(ctx: Context, url: String): Bitmap? = runCatching {
        val r = Covers.loader(ctx).execute(
            ImageRequest.Builder(ctx).data(url).size(256).allowHardware(false).build(),
        ) as? SuccessResult ?: return null
        val src = r.drawable.toBitmap(256, 256)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val radius = src.width * 0.18f
        Canvas(out).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), radius, radius, paint)
        out
    }.getOrNull()
}

class ContinueWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { runCatching { ContinueWidget.render(ctx.applicationContext) } } finally { pending.finish() }
        }
    }
}
