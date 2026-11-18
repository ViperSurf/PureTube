package com.puretube.app.data.local

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Persists the most recent uncaught exception to filesDir/last_crash.txt so the
 * Settings > Diagnostics section can show it after a restart. The handler
 * always delegates to the previously installed handler — it never swallows
 * the crash, it only records it first.
 */
object LastCrashStore {

    private const val FILE_NAME = "last_crash.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                sw.appendLine("Thread: ${thread.name}")
                sw.appendLine("Time: ${System.currentTimeMillis()}")
                throwable.printStackTrace(PrintWriter(sw))
                file(appContext).writeText(sw.toString())
            }
            // Delegate: preserve the platform's normal crash behaviour.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                throw throwable
            }
        }
    }

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    fun read(context: Context): String? = runCatching {
        val f = file(context)
        if (f.exists()) f.readText().takeIf { it.isNotBlank() } else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
