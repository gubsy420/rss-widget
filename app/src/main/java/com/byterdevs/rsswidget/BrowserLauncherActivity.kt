package com.byterdevs.rsswidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.WindowManager
import android.graphics.Color
import com.byterdevs.rsswidget.webview.BottomSheetWebView
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.byterdevs.rsswidget.room.RssDatabase
import kotlinx.coroutines.runBlocking

class BrowserLauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        supportActionBar?.hide()
        Log.d("RssWidgetProvider", "BrowserLauncherActivity onCreate called.")
        val link = intent.getStringExtra("EXTRA_LINK")
        if(link.isNullOrEmpty()) return

        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if(appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        // Copy-link button: copy the article URL to the clipboard and bail out (no read marking).
        if (intent.getStringExtra("EXTRA_ACTION") == "copy") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Article link", link))
            android.widget.Toast.makeText(this, R.string.link_copied, android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val prefs = applicationContext.getWidgetPrefs(appWidgetId)

        ReadItemsStore.markRead(this, link)

        // Push the opened article's read state back to Miniflux.
        val remoteId = intent.getLongExtra("EXTRA_REMOTE_ID", -1L)
        if (remoteId >= 0L) {
            enqueueMinifluxMarkRead(appWidgetId, remoteId)
        }

        // Optionally mark everything listed above the opened article as read too: to reach this
        // one you scrolled past those, so they're treated as already seen.
        val position = intent.getIntExtra("EXTRA_POSITION", -1)
        if (prefs.markAboveReadOnOpen && position > 0) {
            markArticlesAboveRead(appWidgetId, position)
        }

        // Redraw all widget instances so read items dim immediately and consistently (the read
        // state is shared, so the folded/unfolded instances stay in sync).
        notifyAllWidgets()

        when (prefs.readerType) {
            ReaderType.INTERNAL -> openInternal(appWidgetId, link, false)
            ReaderType.READER -> openInternal(appWidgetId, link, true)
            ReaderType.EXTERNAL -> openExternal(appWidgetId, link)
        }
    }

    private fun enqueueMinifluxMarkRead(appWidgetId: Int, remoteId: Long) {
        val request = OneTimeWorkRequestBuilder<MinifluxMarkReadWorker>()
            .setInputData(
                Data.Builder()
                    .putInt("appWidgetId", appWidgetId)
                    .putLong("remoteId", remoteId)
                    .build()
            )
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }

    /**
     * Marks every article listed above [position] as read (and syncs each to Miniflux). The DB
     * query matches the widget's newest-first display order, so items before the clicked position
     * are exactly the ones shown above it. Runs off the main thread.
     */
    private fun markArticlesAboveRead(appWidgetId: Int, position: Int) {
        val ctx = applicationContext
        Thread {
            try {
                val dao = RssDatabase.getInstance(ctx).rssItemDao()
                val items = runBlocking { dao.getItemsForWidget(appWidgetId) }
                items.take(position).forEach { item ->
                    if (!ReadItemsStore.isRead(ctx, item.link)) {
                        ReadItemsStore.markRead(ctx, item.link)
                        item.remoteId?.let { enqueueMinifluxMarkRead(appWidgetId, it) }
                    }
                }
                notifyAllWidgets()
            } catch (e: Exception) {
                Log.e("BrowserLauncherActivity", "Failed to mark articles above position $position", e)
            }
        }.start()
    }

    /** Triggers a list redraw on every widget instance so shared read-state stays consistent. */
    private fun notifyAllWidgets() {
        val mgr = AppWidgetManager.getInstance(applicationContext)
        val ids = mgr.getAppWidgetIds(
            android.content.ComponentName(applicationContext, RssWidgetProvider::class.java)
        )
        if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    fun openInternal(appWidgetId: Int, link: String, readerable: Boolean) {
        val bottomSheetWebView = BottomSheetWebView(this, this, readerable)
        bottomSheetWebView.showWithUrl(link)
    }

    fun openExternal(appWidgetId: Int, link: String) {
        Log.d("RssWidgetProvider", "Received link extra: $link")

        if (link.isNotEmpty()) {

            val browserIntent = Intent(Intent.ACTION_VIEW, link.toUri())
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                Log.d("RssWidgetProvider", "Attempting to start browser with link: $link")
                startActivity(browserIntent)
            } catch (e: Exception) {
                Log.e("RssWidgetProvider", "Failed to start browser", e)
            }
        }

        finish()
    }
}