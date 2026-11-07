package com.xlollx.songport.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Why the app last closed, in the diagnostics log: an uncaught exception with its stack (written
 * just before the process dies), and at the next start the reason Android gives for the previous
 * process' end (Android 11+), which also names native crashes in libraries the app cannot catch.
 * Nothing leaves the phone: the log goes out only in a report the user chooses to send.
 */
object CrashLog {
    private const val PREFS = "crash_log"

    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { Diagnostics.log(app, "crash", "on ${thread.name}: " + describe(e)) }
            previous?.uncaughtException(thread, e)
        }
        runCatching { previousExit(app) }
    }

    /** The exception and its causes, each with its first frames: short enough for one log line. */
    fun describe(e: Throwable): String {
        val parts = ArrayList<String>()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 3) {
            val frames = t.stackTrace.take(8).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            parts += "${t.javaClass.name}: ${t.message.orEmpty().take(200)} @ $frames"
            t = t.cause.takeIf { it !== t }; depth++
        }
        return parts.joinToString(" | caused by ")
    }

    private fun previousExit(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong("seen", 0L)
        val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).filter { it.timestamp > seen }
        for (x in exits.sortedBy { it.timestamp }) {
            val reason = when (x.reason) {
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
                else -> null
            } ?: continue
            Diagnostics.log(ctx, "exit", "previous run ended: $reason" + (x.description?.let { " ($it)" } ?: "") +
                " · process ${x.processName} · memory ${x.pss / 1024} MB")
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("seen", it).apply() }
    }
}
