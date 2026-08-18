package com.yuilittle.bili

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Small, bounded snapshot used only to paint the first frame on a cold start. */
object FeedStore {
    // Keep every item the bounded runtime window may expose, so a restored
    // scroll anchor never lands past the persisted snapshot.
    const val MAX_STORED_ITEMS = FeedPagingPolicy.MAX_IN_MEMORY_ITEMS
    private const val PREFS = "bounded_feed_snapshot"

    data class State(
        val videos: List<VideoItem>,
        val lastSuccessfulPage: Int,
        val lastScannedPage: Int
    )

    private const val MAX_STORE_QUEUE = 8
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateWriter = ThreadPoolExecutor(
        1,
        1,
        10L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(MAX_STORE_QUEUE),
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }

    fun load(context: Context, tab: Int): List<VideoItem> = loadState(context, tab).videos

    fun loadPage(context: Context, tab: Int): Int = loadState(context, tab).lastSuccessfulPage

    fun loadScannedPage(context: Context, tab: Int): Int = loadState(context, tab).lastScannedPage

    fun loadState(context: Context, tab: Int): State {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasLegacySnapshot = preferences.contains(key(tab))
        val successfulPage = preferences.getInt(pageKey(tab), if (hasLegacySnapshot) 1 else 0)
            .coerceAtLeast(0)
        val scannedPage = preferences.getInt(scanKey(tab), successfulPage).coerceAtLeast(successfulPage)
        return State(
            videos = videosFromJson(preferences.getString(key(tab), null)),
            lastSuccessfulPage = successfulPage,
            lastScannedPage = scannedPage
        )
    }

    /**
     * Commits one coherent feed state off the UI thread. The callback runs on the
     * main thread only after the disk commit is complete, so a visible new batch
     * always has its matching cursor and duplicate-scan progress persisted.
     */
    fun saveState(
        context: Context,
        tab: Int,
        videos: List<VideoItem>,
        lastSuccessfulPage: Int,
        lastScannedPage: Int,
        callback: (Boolean) -> Unit
    ) {
        val storedVideos = videos.take(MAX_STORED_ITEMS)
        val encoded = videosToJson(storedVideos)
        val safeSuccessfulPage = lastSuccessfulPage.coerceAtLeast(0)
        val safeScannedPage = lastScannedPage.coerceAtLeast(safeSuccessfulPage)
        val applicationContext = context.applicationContext
        try {
            stateWriter.execute {
                val committed = try {
                    applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(key(tab), encoded)
                        .putInt(pageKey(tab), safeSuccessfulPage)
                        .putInt(scanKey(tab), safeScannedPage)
                        .commit()
                } catch (_: Exception) {
                    false
                }
                mainHandler.post { callback(committed) }
            }
        } catch (_: RejectedExecutionException) {
            mainHandler.post { callback(false) }
        }
    }

    private fun videosFromJson(value: String?): List<VideoItem> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(value)
            val videos = ArrayList<VideoItem>(minOf(array.length(), MAX_STORED_ITEMS))
            for (index in 0 until minOf(array.length(), MAX_STORED_ITEMS)) {
                val item = array.optJSONObject(index) ?: continue
                val bvid = item.optString("bvid")
                if (bvid.isBlank()) continue
                videos += VideoItem(
                    bvid = bvid,
                    title = item.optString("title"),
                    owner = item.optString("owner"),
                    cover = item.optString("cover"),
                    views = item.optLong("views"),
                    duration = item.optInt("duration"),
                    description = "",
                    publishedAt = item.optLong("publishedAt"),
                    cid = item.optLong("cid")
                )
            }
            videos
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun videosToJson(videos: List<VideoItem>): String {
        val array = JSONArray()
        videos.forEach { video ->
            array.put(JSONObject().apply {
                put("bvid", video.bvid)
                put("title", video.title)
                put("owner", video.owner)
                put("cover", video.cover)
                put("views", video.views)
                put("duration", video.duration)
                put("publishedAt", video.publishedAt)
                put("cid", video.cid)
            })
        }
        return array.toString()
    }

    private fun key(tab: Int) = if (tab == 0) "recommend" else "popular"
    private fun pageKey(tab: Int) = "${key(tab)}_last_page"
    private fun scanKey(tab: Int) = "${key(tab)}_last_scanned_page"
}
