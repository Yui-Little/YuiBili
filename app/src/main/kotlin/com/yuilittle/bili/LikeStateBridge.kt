package com.yuilittle.bili

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived in-memory bridge for Bilibili like eventual consistency.
 *
 * Official like endpoints only return `{code:0}` on success. Immediately
 * re-reading `has/like`, `view.stat.like`, or `reply.action` can still lag a
 * few seconds, so a fresh page load right after a successful like looks wrong
 * (icon/count desync, or "not applied yet").
 *
 * This is NOT a permanent local like store:
 * - process memory only (never written to disk / SharedPreferences)
 * - expires after [TTL_MS]
 * - dropped as soon as the server flag catches up
 */
object LikeStateBridge {
    private const val TTL_MS = 15_000L
    // 关注关系查询偶有延迟，给更长桥接窗口，避免退出重进立刻回退。
    private const val FOLLOW_TTL_MS = 60_000L

    data class Snapshot(
        val liked: Boolean,
        val likes: Long,
        val untilMs: Long
    )

    private val videos = ConcurrentHashMap<Long, Snapshot>()
    private val comments = ConcurrentHashMap<Long, Snapshot>()
    private val follows = ConcurrentHashMap<Long, Snapshot>()

    fun rememberVideo(aid: Long, liked: Boolean, likes: Long) {
        if (aid <= 0L) return
        videos[aid] = Snapshot(liked, likes.coerceAtLeast(0L), now() + TTL_MS)
    }

    fun rememberComment(rpid: Long, liked: Boolean, likes: Long) {
        if (rpid <= 0L) return
        comments[rpid] = Snapshot(liked, likes.coerceAtLeast(0L), now() + TTL_MS)
    }

    fun rememberFollow(mid: Long, following: Boolean) {
        if (mid <= 0L) return
        follows[mid] = Snapshot(following, 0L, now() + FOLLOW_TTL_MS)
    }

    /** Overlay server video like state with a recent successful action. */
    fun resolveVideo(aid: Long, serverLiked: Boolean, serverLikes: Long): Pair<Boolean, Long> =
        resolve(videos, aid, serverLiked, serverLikes)

    /** Overlay server comment like state with a recent successful action. */
    fun resolveComment(rpid: Long, serverLiked: Boolean, serverLikes: Long): Pair<Boolean, Long> =
        resolve(comments, rpid, serverLiked, serverLikes)

    /** Overlay server follow flag with a recent successful follow/unfollow. */
    fun resolveFollow(mid: Long, serverFollowing: Boolean): Boolean {
        if (mid <= 0L) return serverFollowing
        val snap = fresh(follows, mid) ?: return serverFollowing
        if (serverFollowing == snap.liked) {
            follows.remove(mid, snap)
            return serverFollowing
        }
        return snap.liked
    }

    private fun resolve(
        map: ConcurrentHashMap<Long, Snapshot>,
        id: Long,
        serverLiked: Boolean,
        serverLikes: Long
    ): Pair<Boolean, Long> {
        val safeServerLikes = serverLikes.coerceAtLeast(0L)
        val snap = fresh(map, id) ?: return serverLiked to safeServerLikes
        if (serverLiked == snap.liked) {
            // Flag has caught up — drop the bridge entry.
            map.remove(id, snap)
            val likes = if (snap.liked) {
                maxOf(safeServerLikes, snap.likes)
            } else {
                minOf(safeServerLikes, snap.likes)
            }
            return serverLiked to likes
        }
        // Server still lagging on the action flag.
        return snap.liked to snap.likes
    }

    private fun fresh(map: ConcurrentHashMap<Long, Snapshot>, id: Long): Snapshot? {
        val snap = map[id] ?: return null
        if (snap.untilMs < now()) {
            map.remove(id, snap)
            return null
        }
        return snap
    }

    private fun now(): Long = System.currentTimeMillis()
}
