package com.mshemur.lingqfix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app alive while podcast episodes go to LingQ in the background: without a foreground service Android
 * freezes the process seconds after the app is minimised (sockets break) and later kills it, and the job is lost.
 * Shows the progress as a notification; a finished job leaves its own notification (success or error).
 */
class ImportService : Service() {
    companion object {
        private const val CH = "import"
        private const val CH_DONE = "import_done"
        private const val ID = 4201
        private var doneId = 4300
        @Volatile var running = false

        private fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Загрузка в LingQ", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Загрузка в LingQ: готово / ошибка", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun build(ctx: Context, title: String, text: String, progress: Float): Notification =
            NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setProgress(100, (progress * 100).toInt(), progress <= 0f)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(ctx))
                .build()

        private var lastTitle = "SatzLoop: загрузка в LingQ"
        private var lastText = "Готовлю…"
        private var lastProgress = 0f

        fun start(ctx: Context) {
            if (running) return
            channels(ctx)
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, ImportService::class.java)) }
                .onFailure { AppLog.error("podcast", "Фоновая служба не запустилась", it) }
        }

        fun update(ctx: Context, title: String, text: String, progress: Float) {
            lastTitle = title; lastText = text; lastProgress = progress
            if (!running) return
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, title, text, progress)) }
        }

        fun stop(ctx: Context) {
            if (running) ctx.stopService(Intent(ctx, ImportService::class.java))
        }

        /** A job ended: its own notification (stays after the service is gone). */
        fun finished(ctx: Context, title: String, text: String, ok: Boolean) {
            channels(ctx)
            val n = NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (ok) "Готово: $title" else "Не загрузилось: $title")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(doneId++, n) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, ID, build(this, lastTitle, lastText, lastProgress), type) }
            .onFailure { AppLog.error("podcast", "startForeground", it); stopSelf(); return START_NOT_STICKY }
        running = true
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.add("podcast", "Фоновая служба: время Android вышло")
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
