package com.byterdevs.rsswidget

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Pushes a "mark as read" for a single Miniflux entry back to the server. Runs via WorkManager so
 * it survives the article-open Activity finishing and is retried automatically when offline.
 */
class MinifluxMarkReadWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val appWidgetId = inputData.getInt("appWidgetId", -1)
        val remoteId = inputData.getLong("remoteId", -1L)
        if (appWidgetId < 0 || remoteId < 0L) return Result.failure()

        val prefs = applicationContext.getWidgetPrefs(appWidgetId)
        if (prefs.sourceMode != SourceMode.MINIFLUX ||
            prefs.minifluxUrl.isBlank() || prefs.minifluxToken.isBlank()
        ) {
            return Result.success()
        }

        val ok = MinifluxClient.markRead(prefs.minifluxUrl, prefs.minifluxToken, listOf(remoteId))
        return if (ok) {
            Result.success()
        } else {
            Log.w("MinifluxMarkReadWorker", "Failed to mark entry $remoteId read; will retry")
            Result.retry()
        }
    }
}
