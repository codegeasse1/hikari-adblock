package com.codegeasse1.hikariadblock.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.codegeasse1.hikariadblock.service.ShizukuProxyService
import timber.log.Timber

class ShizukuProxyResumeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "shizuku_proxy_resume_work"
    }

    override suspend fun doWork(): Result {
        return try {
            val intent = Intent(applicationContext, ShizukuProxyService::class.java).apply {
                action = ShizukuProxyService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume Shizuku Proxy")
            Result.retry()
        }
    }
}
