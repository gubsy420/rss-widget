package com.byterdevs.rsswidget

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal client for the Miniflux REST API (https://miniflux.app/docs/api.html).
 *
 * Authenticates with a per-app API token via the `X-Auth-Token` header. Uses the built-in
 * [HttpURLConnection] and [org.json] so no extra dependencies are pulled in.
 */
object MinifluxClient {
    private const val TAG = "MinifluxClient"
    private const val CONNECT_TIMEOUT = 10000
    private const val READ_TIMEOUT = 15000
    private const val MAX_REFRESH_CONCURRENCY = 6

    data class Entry(
        val id: Long,
        val title: String,
        val url: String,
        val author: String,
        val content: String,
        val publishedAtMillis: Long?,
        val feedTitle: String,
        val imageUrl: String?
    )

    /** Normalizes a user-entered base URL into `scheme://host[:port]` with no trailing slash. */
    private fun normalizeBase(baseUrl: String): String {
        var b = baseUrl.trim().trimEnd('/')
        if (!b.startsWith("http://", true) && !b.startsWith("https://", true)) {
            b = "http://$b"
        }
        // Drop a trailing /v1 if the user pasted the API root rather than the server root.
        if (b.endsWith("/v1")) b = b.dropLast(3)
        return b
    }

    private fun open(baseUrl: String, path: String, token: String, method: String): HttpURLConnection {
        val conn = URL(normalizeBase(baseUrl) + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("X-Auth-Token", token)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        return conn
    }

    /** Returns a human-readable error string on failure, or null on success. Used by "Test connection". */
    fun testConnection(baseUrl: String, token: String): String? {
        if (baseUrl.isBlank() || token.isBlank()) return "Server URL and token are required"
        var conn: HttpURLConnection? = null
        return try {
            conn = open(baseUrl, "/v1/me", token, "GET")
            conn.connect()
            when (val code = conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val name = JSONObject(body).optString("username", "")
                    null.also { Log.i(TAG, "Connected to Miniflux as '$name'") }
                }
                401, 403 -> "Authentication failed (check the API token)"
                else -> "Server returned HTTP $code"
            }
        } catch (e: Exception) {
            Log.e(TAG, "testConnection failed", e)
            "Could not reach server: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    /** Fetches the most recent unread entries, newest first. Throws on network/parse failure. */
    fun fetchUnreadEntries(baseUrl: String, token: String, limit: Int): List<Entry> {
        var conn: HttpURLConnection? = null
        try {
            conn = open(
                baseUrl,
                "/v1/entries?status=unread&order=published_at&direction=desc&limit=$limit",
                token,
                "GET"
            )
            conn.connect()
            if (conn.responseCode != 200) {
                throw RuntimeException("Miniflux entries request returned HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val entriesJson = JSONObject(body).optJSONArray("entries") ?: return emptyList()
            val result = ArrayList<Entry>(entriesJson.length())
            for (i in 0 until entriesJson.length()) {
                val e = entriesJson.getJSONObject(i)
                val feed = e.optJSONObject("feed")
                val content = e.optString("content", "")
                result.add(
                    Entry(
                        id = e.getLong("id"),
                        title = e.optString("title", "No Title"),
                        url = e.optString("url", ""),
                        author = e.optString("author", ""),
                        content = content,
                        publishedAtMillis = parseTimestamp(e.optString("published_at", "")),
                        feedTitle = feed?.optString("title", "").orEmpty(),
                        // Prefer a real image enclosure; otherwise fall back to the first inline
                        // <img> in the article HTML, since many feeds embed images there instead.
                        imageUrl = firstImageEnclosure(e) ?: firstContentImage(content)
                    )
                )
            }
            return result
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Forces Miniflux to poll every source feed now and returns the number it accepted.
     *
     * The bulk `/v1/feeds/refresh` endpoint only enqueues feeds the scheduler considers "due"
     * (it reports `nb_jobs=0` when nothing is), so it's useless for an on-demand sync. Instead we
     * list the feeds and hit the per-feed `/v1/feeds/{id}/refresh` endpoint, which forces an
     * immediate fetch regardless of the polling schedule.
     */
    fun refreshAllFeeds(baseUrl: String, token: String): Int {
        val feedIds = try {
            fetchFeedIds(baseUrl, token)
        } catch (e: Exception) {
            Log.e(TAG, "Could not list feeds to refresh", e)
            return 0
        }
        if (feedIds.isEmpty()) return 0

        // Force each feed in parallel (each call is a synchronous poll server-side), so total time
        // scales with the slowest feed rather than the sum of all of them.
        val pool = Executors.newFixedThreadPool(minOf(feedIds.size, MAX_REFRESH_CONCURRENCY))
        val refreshed = AtomicInteger(0)
        try {
            feedIds.map { id ->
                pool.submit { if (refreshFeed(baseUrl, token, id)) refreshed.incrementAndGet() }
            }.forEach {
                try {
                    it.get()
                } catch (e: Exception) {
                    Log.e(TAG, "Feed refresh task failed", e)
                }
            }
        } finally {
            pool.shutdown()
        }
        Log.i(TAG, "Forced refresh of ${refreshed.get()}/${feedIds.size} Miniflux feeds")
        return refreshed.get()
    }

    private fun fetchFeedIds(baseUrl: String, token: String): List<Long> {
        var conn: HttpURLConnection? = null
        try {
            conn = open(baseUrl, "/v1/feeds", token, "GET")
            conn.connect()
            if (conn.responseCode != 200) {
                throw RuntimeException("Miniflux feeds list returned HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(body)
            val ids = ArrayList<Long>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { ids.add(it.getLong("id")) }
            }
            return ids
        } finally {
            conn?.disconnect()
        }
    }

    /** Forces an immediate poll of a single feed, bypassing the polling schedule. */
    private fun refreshFeed(baseUrl: String, token: String, feedId: Long): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(baseUrl, "/v1/feeds/$feedId/refresh", token, "PUT")
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.use { /* no body required */ }
            val code = conn.responseCode
            (code in 200..299).also {
                if (!it) Log.w(TAG, "refreshFeed $feedId returned HTTP $code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshFeed $feedId failed", e)
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** Marks the given Miniflux entry ids as read. Returns true on success. */
    fun markRead(baseUrl: String, token: String, entryIds: List<Long>): Boolean {
        if (entryIds.isEmpty()) return true
        var conn: HttpURLConnection? = null
        return try {
            conn = open(baseUrl, "/v1/entries", token, "PUT")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject()
                .put("entry_ids", org.json.JSONArray(entryIds))
                .put("status", "read")
                .toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            (code in 200..299).also {
                if (!it) Log.e(TAG, "markRead returned HTTP $code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "markRead failed", e)
            false
        } finally {
            conn?.disconnect()
        }
    }

    private val imgTagRegex = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun firstContentImage(content: String): String? {
        if (content.isEmpty()) return null
        val url = imgTagRegex.find(content)?.groupValues?.get(1) ?: return null
        return if (url.startsWith("http")) url else null
    }

    private fun firstImageEnclosure(entry: JSONObject): String? {
        val enclosures = entry.optJSONArray("enclosures") ?: return null
        for (i in 0 until enclosures.length()) {
            val enc = enclosures.optJSONObject(i) ?: continue
            val mime = enc.optString("mime_type", "")
            val url = enc.optString("url", "")
            if (mime.startsWith("image/", true) && url.startsWith("http")) {
                return url
            }
        }
        return null
    }

    private fun parseTimestamp(value: String): Long? {
        if (value.isBlank()) return null
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}
