package com.mshemur.lingqfix

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Counts the time the Snipd podcast app plays (clock time, while it is minimised too) as listening time in the
 * statistics. Android shows other apps' players only to a notification listener, so the user grants
 * "Доступ к уведомлениям" once; only the media sessions are looked at, notifications are not read.
 */
class SnipdWatch : NotificationListenerService() {
    companion object {
        fun isSnipd(pkg: String?) = pkg?.contains("snipd", ignoreCase = true) == true

        fun enabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        /** The system screen where the access is switched on (this app's own switch where Android has it). */
        fun openSettings(ctx: Context) {
            val comp = ComponentName(ctx, SnipdWatch::class.java)
            val i = if (Build.VERSION.SDK_INT >= 30)
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, comp.flattenToString())
            else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
                runCatching { ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }

        /** After an app update Android may leave the listener unbound: ask it to bind again. */
        fun rebind(ctx: Context) {
            if (enabled(ctx) && Build.VERSION.SDK_INT >= 24)
                runCatching { requestRebind(ComponentName(ctx, SnipdWatch::class.java)) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var sm: MediaSessionManager? = null
    private val watched = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    private var playingSince = 0L      // elapsedRealtime, 0 = not playing

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list.orEmpty()) }

    /** Counted time goes into the statistics every minute (so a killed process loses at most a minute). */
    private val tick = object : Runnable {
        override fun run() {
            flush()
            if (playingSince > 0) handler.postDelayed(this, 60_000)
        }
    }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        sm = m
        val comp = ComponentName(this, SnipdWatch::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsChanged, comp, handler)
            watch(m.getActiveSessions(comp))
        }.onFailure { AppLog.error("snipd", "Плееры не видны", it) }
        AppLog.add("snipd", "Слежу за Snipd")
    }

    override fun onListenerDisconnected() {
        flush(); playingSince = 0
        runCatching { sm?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        unwatch()
    }

    override fun onDestroy() {
        flush(); playingSince = 0
        unwatch()
        super.onDestroy()
    }

    private fun unwatch() {
        watched.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        watched.clear()
    }

    /** Players seen (package names only), logged once per change so a missing Snipd can be found. */
    private var seenPlayers = ""

    private fun logPlayers(list: List<MediaController>) {
        val s = list.joinToString(", ") { "${it.packageName}=${it.playbackState?.state ?: "-"}" }
        if (s != seenPlayers) { seenPlayers = s; AppLog.add("snipd", "Плееры: ${s.ifBlank { "нет" }}") }
    }

    /** Snipd's notification carries its player: used when the session list does not show it. */
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        val n = sbn?.notification ?: return
        val token = runCatching {
            if (Build.VERSION.SDK_INT >= 33) n.extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION, android.media.session.MediaSession.Token::class.java)
            else @Suppress("DEPRECATION") n.extras.getParcelable<android.media.session.MediaSession.Token>(android.app.Notification.EXTRA_MEDIA_SESSION)
        }.getOrNull() ?: return
        if (!isSnipd(sbn.packageName)) {
            if (sbn.packageName != packageName && !seenPlayers.contains(sbn.packageName)) AppLog.add("snipd", "Плеер в уведомлении: ${sbn.packageName}")
            return
        }
        if (watched.any { it.first.sessionToken == token }) return
        AppLog.add("snipd", "Snipd найден по уведомлению")
        add(MediaController(this, token))
        recompute()
    }

    private fun add(c: MediaController) {
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
            override fun onSessionDestroyed() = recompute()
        }
        c.registerCallback(cb, handler)
        watched += c to cb
    }

    private fun watch(list: List<MediaController>) {
        logPlayers(list)
        unwatch()
        list.filter { isSnipd(it.packageName) }.forEach { add(it) }
        // Snipd's notification may already be there: take its player too
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
        recompute()
    }

    private fun recompute() {
        val playing = watched.any { (c, _) -> c.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing && playingSince == 0L) {
            playingSince = SystemClock.elapsedRealtime()
            AppLog.add("snipd", "Snipd играет")
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 60_000)
        } else if (!playing && playingSince > 0) {
            flush()
            playingSince = 0
            handler.removeCallbacks(tick)
            AppLog.add("snipd", "Snipd на паузе")
        }
    }

    private fun flush() {
        if (playingSince == 0L) return
        val now = SystemClock.elapsedRealtime()
        val sec = ((now - playingSince) / 1000).toInt()
        if (sec <= 0) return
        ReadingStats.get(this).addTime(sec, listening = true)
        playingSince += sec * 1000L
    }
}
