package com.mshemur.lingqfix

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Date

/** Saves the stack trace of a crash; the next start shows it so the user can copy or share it (Ui.kt). */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(ctx.applicationContext, prev))
    }

    private class Handler(val ctx: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { AppLog.error("CRASH", "поток ${t.name}", e); AppLog.add("CRASH", e.stackTraceToString().lines().take(8).joinToString(" | ")) }
            runCatching {
                file(ctx).writeText(
                    "LingQ Fix ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "${Date()}, thread ${t.name}\n\n" + e.stackTraceToString().take(30_000),
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** The report of the last crash, once (the file is removed). */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
