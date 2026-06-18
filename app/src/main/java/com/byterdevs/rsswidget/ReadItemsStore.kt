package com.byterdevs.rsswidget

import android.content.Context
import android.util.Log

/**
 * Tracks which article links have been read. State is global (not per-widget) so that all widget
 * instances — e.g. the folded and unfolded home screens on a foldable — share the same read/dim
 * state. A read link is read everywhere.
 */
object ReadItemsStore {
    private const val READ_KEY = "read"
    private const val PRUNE_AFTER_MS = 2 * 24 * 60 * 60 * 1000L

    private fun prefs(context: Context) =
        context.getSharedPreferences("read_items", Context.MODE_PRIVATE)

    fun markRead(context: Context, link: String) {
        val prefs = prefs(context)
        val set = prefs.getStringSet(READ_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(link)
        prefs.edit()
            .putStringSet(READ_KEY, set)
            .putLong("read_time_$link", System.currentTimeMillis())
            .apply()
        Log.d("ReadItemsStore", "Marked item as read: $link")
        prune(context)
    }

    fun isRead(context: Context, link: String): Boolean {
        return prefs(context).getStringSet(READ_KEY, emptySet())?.contains(link) == true
    }

    fun prune(context: Context) {
        val prefs = prefs(context)
        val set = prefs.getStringSet(READ_KEY, emptySet())?.toMutableSet() ?: return
        val now = System.currentTimeMillis()
        val toRemove = set.filter { link ->
            val readTime = prefs.getLong("read_time_$link", 0L)
            readTime == 0L || now - readTime > PRUNE_AFTER_MS
        }
        if (toRemove.isNotEmpty()) {
            val editor = prefs.edit()
            toRemove.forEach { editor.remove("read_time_$it") }
            set.removeAll(toRemove.toSet())
            editor.putStringSet(READ_KEY, set).apply()
        }
    }
}
