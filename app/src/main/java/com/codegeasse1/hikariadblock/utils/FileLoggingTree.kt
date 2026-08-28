package com.codegeasse1.hikariadblock.utils

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLoggingTree(context: Context) : Timber.DebugTree() {
    private val logDir = File(context.cacheDir, "logs").apply { mkdirs() }

    private val logFile: File
        get() {
            val file = File(logDir, "hikari_logs.txt")
            if (!file.exists()) { file.createNewFile() }

            if (file.length() > 5L * 1024 * 1024) {
                val backupFile = File(logDir, "hikari_logs_old.txt")
                if (backupFile.exists()) backupFile.delete()
                file.renameTo(backupFile)
                file.createNewFile()
            }
            return file
        }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (com.codegeasse1.hikariadblock.BuildConfig.DEBUG) {
            super.log(priority, tag, message, t)
        }

        try {
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val priorityStr = when (priority) {
                Log.VERBOSE -> "V"
                Log.DEBUG -> "D"
                Log.INFO -> "I"
                Log.WARN -> "W"
                Log.ERROR -> "E"
                Log.ASSERT -> "WTF"
                else -> "?"
            }
            val threadName = Thread.currentThread().name
            val logHeader = "$timeStamp $priorityStr/[${tag ?: "Hikari AdBlock"}] <$threadName>"

            FileWriter(logFile, true).use { writer ->
                writer.append("$logHeader: $message\n")
                t?.let { writer.append(Log.getStackTraceString(it)).append("\n") }
            }
        } catch (e: Exception) {
            Log.e("FileLoggingTree", "Failed to write local log", e)
        }
    }
}
