package com.kundan.hisabledger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AutoBackupWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("hisab", Context.MODE_PRIVATE)
            val source = JSONArray(prefs.getString("data", "[]"))
            val payload = JSONObject().apply {
                put("app", "Hisab Ledger")
                put("version", 2)
                put("createdAt", System.currentTimeMillis())
                put("transactions", source)
            }
            val dir = File(applicationContext.filesDir, "auto_backups")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "auto_${System.currentTimeMillis()}.json").writeText(payload.toString(2))
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
