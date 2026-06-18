package com.byterdevs.rsswidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.text.HtmlCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.byterdevs.rsswidget.room.RssDatabase
import com.byterdevs.rsswidget.room.RssItemDao
import com.byterdevs.rsswidget.room.RssItemEntity
import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.modules.mediarss.types.UrlReference
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID


// Buffer after force-refreshing Miniflux feeds (which is synchronous server-side) before fetching,
// to let the server commit the newly parsed entries.
private const val MINIFLUX_REFRESH_GRACE_MS = 1000L
// How many article images to download at once during a refresh.
private const val MAX_IMAGE_CONCURRENCY = 8
// Cached images are downsampled so their largest dimension is roughly this many pixels, to keep
// the launcher from having to decode an oversized bitmap (which can crash it).
private const val MAX_IMAGE_DIMEN = 1080

class RssWidgetUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val appWidgetId = inputData.getInt("appWidgetId", AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return Result.failure()
        }
        val hardRefresh = inputData.getBoolean("hardRefresh", false)

        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        val db = RssDatabase.getInstance(applicationContext)
        val dao = db.rssItemDao()

        if (hardRefresh) {
            Log.d("RssWidgetUpdateWorker", "Refresh request received")
            runBlocking { dao.clearItemsForWidget(appWidgetId) }
            RssWidgetProvider.updateAppWidget(applicationContext, appWidgetManager, appWidgetId)
        }

        updateRssFeed(appWidgetId, dao, hardRefresh)
        RssWidgetProvider.updateAppWidget(applicationContext, appWidgetManager, appWidgetId)

        // A manual refresh re-syncs sibling instances (e.g. folded/unfolded home screens) so they
        // converge to the same feed. Siblings refresh softly (no force-poll) and don't fan out
        // further, so there's no loop.
        if (hardRefresh) {
            enqueueSiblingSoftRefreshes(appWidgetId, appWidgetManager)
        }
        return Result.success()
    }

    private fun enqueueSiblingSoftRefreshes(currentId: Int, appWidgetManager: AppWidgetManager) {
        val ids = appWidgetManager.getAppWidgetIds(
            ComponentName(applicationContext, RssWidgetProvider::class.java)
        )
        ids.filter { it != currentId }.forEach { id ->
            val request = OneTimeWorkRequestBuilder<RssWidgetUpdateWorker>()
                .setInputData(
                    Data.Builder().putInt("appWidgetId", id).putBoolean("hardRefresh", false).build()
                )
                .build()
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork("rss_sibling_sync_$id", ExistingWorkPolicy.REPLACE, request)
        }
    }

    /**
     * Converts HTML to a plain-text snippet for the description. Strips the Unicode object
     * replacement character (U+FFFC) that fromHtml leaves behind for <img>/<object> tags (it
     * renders as a "OBJ" box), and collapses whitespace.
     */
    private fun htmlToPlainText(html: String): String =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace("￼", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun fetchRssItems(appWidgetId: Int, rssUrl: String): List<RssItemEntity> {
        val feedUrl = URL(rssUrl)
        val prefs = applicationContext.getWidgetPrefs(appWidgetId)
        val input = SyndFeedInput()
        val connection = feedUrl.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:48.0) Gecko/48.0 Firefox/48.0")
        connection.connectTimeout = 10000
        connection.readTimeout = 15000

        return try {
            connection.connect()
            val feed = input.build(XmlReader(connection.inputStream))
            val feedTitle = feed.title?.trim() ?: ""

            feed.entries.map { entry ->
                val title = entry.title ?: "No Title"
                val link = entry.link ?: ""
                val rawDescription = entry.description?.value
                    ?: entry.contents.firstOrNull()?.value
                    ?: ""
                val plainDescription = htmlToPlainText(rawDescription)
                val description = if (prefs.descriptionLength > 0 && plainDescription.length > prefs.descriptionLength)
                    plainDescription.take(prefs.descriptionLength) + "..."
                else plainDescription

                val source = when {
                    rssUrl.contains("reddit.com", ignoreCase = true) -> {
                        val subreddit = rssUrl.substringAfter("/r/").substringBefore("/").trim()
                        if (subreddit.isNotEmpty() && subreddit != rssUrl) "Reddit /r/$subreddit" else "Reddit"
                    }
                    feedTitle.isNotEmpty() -> feedTitle
                    else -> {
                        try {
                            val host = URL(link).host
                            if (host.startsWith("www.")) host.substring(4) else host
                        } catch (e: Exception) { "" }
                    }
                }

                val localImageUri = if (prefs.showImages) getImageUrl(entry)?.let {
                    getLocalImageUri(applicationContext, appWidgetId, it)
                } else null

                RssItemEntity(
                    appWidgetId = appWidgetId,
                    title = title,
                    description = description,
                    link = link,
                    date = entry.publishedDate?.time,
                    source = source,
                    image = localImageUri
                )
            }
        } catch (e: Exception) {
            Log.e("RssWidgetUpdateWorker", "Error fetching $rssUrl: $e")
            e.printStackTrace()
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /** Deletes this widget's cached images that are no longer referenced by the current items. */
    private fun clearStaleImages(context: Context, appWidgetId: Int, keepFileNames: Set<String>) {
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("${appWidgetId}_") && f.name !in keepFileNames) {
                f.delete()
            }
        }
    }

    /** Downloads the given image URLs concurrently; returns a url -> local FileProvider uri map. */
    private fun downloadImagesParallel(appWidgetId: Int, urls: List<String?>): Map<String, String> {
        val distinct = urls.filterNotNull().filter { it.startsWith("http") }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val result = java.util.concurrent.ConcurrentHashMap<String, String>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(distinct.size, MAX_IMAGE_CONCURRENCY))
        try {
            distinct.map { url ->
                pool.submit {
                    getLocalImageUri(applicationContext, appWidgetId, url)?.let { result[url] = it }
                }
            }.forEach {
                try { it.get() } catch (e: Exception) { /* individual image failure is non-fatal */ }
            }
        } finally {
            pool.shutdown()
        }
        return result
    }

    private fun getLocalImageUri(context: Context, appWidgetId: Int, imageUrl: String?): String? {
        if (imageUrl == null || !imageUrl.startsWith("http")) return null
        val cachedFile = downloadAndCacheImage(context, appWidgetId, imageUrl)
        return cachedFile?.let {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    it
                )
                uri.toString()
            } catch (e: Exception) {
                null
            }
        }
    }

    // Downloads an image and caches a downsampled JPEG. Downsampling is important: handing an
    // oversized bitmap to a RemoteViews ImageView can crash the launcher when it scrolls into view.
    private fun downloadAndCacheImage(context: Context, appWidgetId: Int, url: String): java.io.File? {
        return try {
            val cacheDir = context.cacheDir
            // Key by URL only (no per-run id) so an already-cached image is reused across refreshes.
            // The "ds" marks the downsampled format; it also invalidates any old full-res cache files.
            val fileName = "${appWidgetId}_ds_${url.hashCode()}.jpg"
            val file = java.io.File(cacheDir, fileName)
            if (!file.exists()) {
                val connection = URL(url).openConnection()
                connection.connect()
                val bytes = connection.getInputStream().use { it.readBytes() }

                // Measure first, then decode at a reduced sample size so we never hold (or pass to
                // the host) a giant bitmap.
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMEN)
                }
                // Decode failure (corrupt/unsupported) -> no cached file, so the row renders imageless.
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: return null
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                bitmap.recycle()
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /** Largest power-of-two downscale that keeps both dimensions at/above [maxDimen]. */
    private fun computeInSampleSize(width: Int, height: Int, maxDimen: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxDimen || h / 2 >= maxDimen) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun getImageUrl(entry: SyndEntry): String? {
        return bestMediaContent(entry)
            ?: bestThumbnail(entry)
            ?: enclosureImage(entry)
    }

    private fun bestMediaContent(entry: SyndEntry): String? {
        var bestWidth: Int? = null
        var imageUrl: String? = null
        val mediaModule = entry.getModule(MediaEntryModule.URI) as MediaEntryModule?
        if (mediaModule == null) {
            return null
        }

        for (mediaContent in mediaModule.mediaContents) {
            (mediaContent.reference as? UrlReference)?.url.let {
                if (mediaContent.width != null || bestWidth == null || bestWidth < mediaContent.width) {
                    imageUrl = it.toString()
                    bestWidth = mediaContent.width
                }
            }
        }

        return imageUrl
    }

    private fun bestThumbnail(entry: SyndEntry): String? {
        var bestWidth: Int? = null
        var imageUrl: String? = null
        val mediaModule = entry.getModule(MediaEntryModule.URI) as MediaEntryModule?
        if (mediaModule == null) {
            return null
        }

        for (thumbnail in mediaModule.metadata.thumbnail) {
            if (thumbnail.width == null || bestWidth == null || bestWidth < thumbnail.width) {
                imageUrl = thumbnail.url.toString()
                bestWidth = thumbnail.width
            }
        }

        return imageUrl
    }

    private fun enclosureImage(entry: SyndEntry): String? {
        for (enclosure in entry.enclosures) {
            if (enclosure.url?.startsWith("http") == true && enclosure.type.startsWith("image/")) {
                return enclosure.url
            }
        }
        return null
    }

    /** Returns the unread entries as widget rows, or null if the fetch failed (so we don't wipe the cache). */
    private fun fetchMinifluxItems(appWidgetId: Int): List<RssItemEntity>? {
        val prefs = applicationContext.getWidgetPrefs(appWidgetId)
        if (prefs.minifluxUrl.isBlank() || prefs.minifluxToken.isBlank()) {
            Log.w("RssWidgetUpdateWorker", "Miniflux not configured for widget $appWidgetId")
            return null
        }
        return try {
            val entries = MinifluxClient.fetchUnreadEntries(
                prefs.minifluxUrl, prefs.minifluxToken, prefs.maxItems
            )
            val imageUris = if (prefs.showImages) {
                downloadImagesParallel(appWidgetId, entries.map { it.imageUrl })
            } else emptyMap()
            entries.map { entry ->
                val plainDescription = htmlToPlainText(entry.content)
                val description = if (prefs.descriptionLength > 0 && plainDescription.length > prefs.descriptionLength)
                    plainDescription.take(prefs.descriptionLength) + "..."
                else plainDescription

                val localImageUri = entry.imageUrl?.let { imageUris[it] }

                RssItemEntity(
                    appWidgetId = appWidgetId,
                    title = entry.title,
                    description = description,
                    link = entry.url,
                    date = entry.publishedAtMillis,
                    source = entry.feedTitle,
                    image = localImageUri,
                    remoteId = entry.id
                )
            }
        } catch (e: Exception) {
            Log.e("RssWidgetUpdateWorker", "Error fetching from Miniflux", e)
            null
        }
    }

    fun updateRssFeed(appWidgetId: Int, dao: RssItemDao, hardRefresh: Boolean = false) = runBlocking {
        val prefs = applicationContext.getWidgetPrefs(appWidgetId)

        if (prefs.sourceMode == SourceMode.MINIFLUX) {
            // On a manual refresh, ask Miniflux to poll its feeds first, then give it a moment.
            // The server refresh is asynchronous, so this is best-effort for brand-new items.
            if (hardRefresh && prefs.minifluxUrl.isNotBlank() && prefs.minifluxToken.isNotBlank()) {
                if (MinifluxClient.refreshAllFeeds(prefs.minifluxUrl, prefs.minifluxToken) > 0) {
                    try { Thread.sleep(MINIFLUX_REFRESH_GRACE_MS) } catch (e: InterruptedException) { /* ignore */ }
                }
            }
            // null = fetch failed (keep existing cache); empty = genuinely no unread (clear it).
            val entities = fetchMinifluxItems(appWidgetId) ?: return@runBlocking
            dao.clearItemsForWidget(appWidgetId)
            clearStaleImages(applicationContext, appWidgetId, currentImageFileNames(entities))
            if (entities.isNotEmpty()) dao.insertAll(entities)
            Log.i("RssWidgetUpdateWorker", "Loaded ${entities.size} unread articles from Miniflux for widget $appWidgetId")
            return@runBlocking
        }

        val entities = mutableListOf<RssItemEntity>()
        prefs.urls.forEach { url ->
            entities.addAll(fetchRssItems(appWidgetId, url))
        }

        if (!entities.isEmpty()) {
            dao.clearItemsForWidget(appWidgetId)
            clearStaleImages(applicationContext, appWidgetId, currentImageFileNames(entities))
            dao.insertAll(entities)
            Log.i("RssWidgetUpdateWorker", "Loaded ${entities.size} articles for widget $appWidgetId")
        }
    }

    /** The cache file names referenced by the given items (last path segment of each image uri). */
    private fun currentImageFileNames(entities: List<RssItemEntity>): Set<String> =
        entities.mapNotNull { it.image?.substringAfterLast('/') }.toSet()
}
