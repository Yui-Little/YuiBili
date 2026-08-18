package com.yuilittle.bili

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small, dependency-free client for the public, read-only web endpoints used by
 * the MVP. Authentication, signing, write actions and media URL extraction are
 * intentionally out of scope.
 */
data class VideoItem(
    val bvid: String,
    val title: String,
    val owner: String,
    val cover: String,
    val views: Long,
    val duration: Int,
    val description: String,
    /** Bilibili pubdate/ctime, in epoch seconds; 0 means the source omitted it. */
    val publishedAt: Long = 0L,
    val cid: Long = 0L,
    val aid: Long = 0L,
    val ownerMid: Long = 0L,
    val ownerAvatar: String = "",
    /** Total comment count (detail stat.reply / search review), 0 when unknown. */
    val replyCount: Long = 0L,
    /** Like count (detail stat.like), 0 when unknown. */
    val likes: Long = 0L,
    /** Whether the signed-in account already liked this video (req_user.like). */
    val liked: Boolean = false,
    /**
     * Whether the signed-in account already follows this UP.
     * Filled asynchronously via relation API (null/false until queried).
     */
    val following: Boolean = false,
    /** 视频所属合集；仅详情接口(view)返回 ugc_season 时非空，无合集为 null。 */
    val season: UgcSeason? = null,
    /** 是否充电（付费）视频：仅接受接口明确给出的专属/付费标记，不以流时长比例猜测。 */
    val charge: Boolean = false
)

/** A single comment (top-level or in-floor) from the reply/main endpoint. */
data class VideoComment(
    val id: Long,
    val user: String,
    val avatar: String,
    val content: String,
    val likes: Long,
    val publishedAt: Long,
    /** Official Bilibili emote images inside the message: placeholder name -> image URL. */
    val emotes: Map<String, String> = emptyMap(),
    /** User-uploaded comment images (pictures array) with intrinsic size. */
    val pictures: List<CommentPicture> = emptyList(),
    /** In-floor (sub) replies already returned with this comment. */
    val replies: List<VideoComment> = emptyList(),
    /** Total count of sub replies (rcount); 0 means no replies at all. */
    val replyCount: Long = 0L,
    /** Whether the signed-in account already liked this comment (action == 1). */
    val liked: Boolean = false
)

/** A user-uploaded comment image with its intrinsic width/height in pixels. */
data class CommentPicture(
    val url: String,
    val width: Int = 0,
    val height: Int = 0
)

/** A user-created favorite folder (from x/v3/fav/folder/created/list-all). */
data class FavoriteFolder(
    val id: Long,
    val name: String,
    val count: Long
)

/** 合集（ugc_season）中的一集，来自 view 接口 data.ugc_season.sections[].episodes[]。 */
data class UgcEpisode(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val title: String,
    val duration: Int,
    /** 分集独立封面；接口未给（如多P pages 形态）时为空串，由调用方回退主视频封面。 */
    val cover: String = "",
    /** 分集本身是否带有明确的付费/充电专属标记。 */
    val charge: Boolean = false
)

/** 视频所属合集（来自 view 接口 data.ugc_season）；episodes 按接口顺序展平。 */
data class UgcSeason(
    val id: Long,
    val title: String,
    val cover: String,
    val episodes: List<UgcEpisode>,
    /** true 表示同一 BV 的 pages 多 P，不是跨 BV 的 ugc_season。 */
    val isMultiPage: Boolean = false
)

/** One downloadable quality entry (qn + label + required privilege). */
data class DownloadQuality(
    val qn: Int,
    val label: String,
    val note: String,
    val needsVip: Boolean
)

/** A successfully parsed feed page; protocol failures use the error callback. */
data class FeedPage(
    val items: List<VideoItem>,
    val endReached: Boolean
)

object BiliApi {
    private const val PAGE_SIZE = 12
    private const val RECOMMEND_URL = "https://api.bilibili.com/x/web-interface/index/top/feed/rcmd?y_num=$PAGE_SIZE&fresh_type=3&feed_version=V8&ps=$PAGE_SIZE"
    private const val POPULAR_URL = "https://api.bilibili.com/x/web-interface/popular?ps=$PAGE_SIZE"
    private const val DETAIL_URL = "https://api.bilibili.com/x/web-interface/view?bvid="
    private const val SEARCH_URL = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword="
    private const val SUGGEST_URL = "https://s.search.bilibili.com/main/suggest?term="
    private const val FINGERPRINT_URL = "https://api.bilibili.com/x/frontend/finger/spi"
    private const val MAX_NETWORK_QUEUE = 16
    private const val MAX_SEARCH_QUEUE = 8
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkWorkers = ThreadPoolExecutor(
        3,
        3,
        20L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(MAX_NETWORK_QUEUE),
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    // Suggestions and searches share one serial lane. A submitted search can no
    // longer race an in-flight suggestion and trip Bilibili's anonymous rate limit.
    private val searchWorkers = ThreadPoolExecutor(
        1,
        1,
        20L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(MAX_SEARCH_QUEUE),
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    private val suggestionGeneration = AtomicInteger(0)
    @Volatile private var anonymousSearchCookie = ""

    /** Bump the generation so any in-flight suggestion callback is ignored. */
    fun cancelSuggestions() {
        suggestionGeneration.incrementAndGet()
    }

    fun fetchRecommend(page: Int = 1, refreshToken: Long = 0L, callback: (FeedPage?, String?) -> Unit) {
        // refreshToken is a harmless cache-buster: it never signs, authenticates or changes platform access.
        requestFeed("$RECOMMEND_URL&pn=${page.coerceAtLeast(1)}&fresh_idx=${page.coerceAtLeast(1)}&_=${if (refreshToken > 0) refreshToken else System.currentTimeMillis()}", callback) { root ->
            root.optJSONObject("data")?.optJSONArray("item")
        }
    }

    fun fetchPopular(page: Int = 1, refreshToken: Long = 0L, callback: (FeedPage?, String?) -> Unit) {
        requestFeed("$POPULAR_URL&pn=${page.coerceAtLeast(1)}&_=${if (refreshToken > 0) refreshToken else System.currentTimeMillis()}", callback) { root ->
            root.optJSONObject("data")?.optJSONArray("list")
        }
    }

    fun suggest(keyword: String, callback: (List<String>?, String?) -> Unit) {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val requestGeneration = suggestionGeneration.incrementAndGet()
        executeSearch(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            if (requestGeneration != suggestionGeneration.get()) return@executeSearch
            try {
                val root = getJson(SUGGEST_URL + encoded, searchHeaders())
                ensureSuccess(root)
                if (requestGeneration != suggestionGeneration.get()) return@executeSearch
                val tags = root.optJSONObject("result")?.optJSONArray("tag") ?: JSONArray()
                val values = LinkedHashSet<String>()
                for (index in 0 until tags.length()) {
                    val tag = tags.optJSONObject(index) ?: continue
                    val value = cleanHtml(tag.optString("value").ifBlank { tag.optString("term") })
                    if (value.isNotBlank()) values += value
                    if (values.size >= 8) break
                }
                mainHandler.post { callback(values.toList(), null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    fun search(keyword: String, page: Int = 1, order: String = "", duration: Int = 0, callback: (List<VideoItem>?, String?) -> Unit) {
        cancelSuggestions()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val params = StringBuilder(SEARCH_URL + encoded + "&page=${page.coerceAtLeast(1)}&__refresh__=true")
        if (order.isNotBlank()) params.append("&order=").append(order)
        if (duration > 0) params.append("&duration=").append(duration)
        executeSearch(onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }) {
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    ensureAnonymousSearchCookie()
                    val root = getJson(params.toString(), searchHeaders())
                    ensureSuccess(root)
                    val array = root.optJSONObject("data")?.optJSONArray("result") ?: JSONArray()
                    val videos = ArrayList<VideoItem>()
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { videos += videoFromJson(it) }
                    }
                    mainHandler.post { callback(videos, null) }
                    return@executeSearch
                } catch (error: Exception) {
                    lastError = error
                    if (attempt == 0 && isRequestBanned(error)) {
                        anonymousSearchCookie = ""
                        Thread.sleep(650L)
                    } else {
                        mainHandler.post { callback(null, readableError(error)) }
                        return@executeSearch
                    }
                }
            }
            mainHandler.post { callback(null, readableError(lastError ?: IllegalStateException("搜索暂不可用"))) }
        }
    }

    fun fetchDetail(bvid: String, aid: Long = 0L, callback: (VideoItem?, List<VideoPart>, String?) -> Unit) {
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, emptyList(), "操作过快，请稍后再试") } }
        ) {
            var lastError: Exception? = null
            // Prefer bvid; fall back to aid (search results occasionally carry an aid
            // that resolves while the bvid path is rejected).
            val attempts = buildList {
                if (bvid.isNotBlank()) add(DETAIL_URL + URLEncoder.encode(bvid, "UTF-8"))
                if (aid > 0L) add("https://api.bilibili.com/x/web-interface/view?aid=$aid")
            }
            for (attempt in attempts) {
                try {
                    val root = getJson(attempt)
                    ensureSuccess(root)
                    val data = root.optJSONObject("data") ?: throw IllegalStateException("未返回视频信息")
                    val video = videoFromJson(data)
                    val pages = data.optJSONArray("pages") ?: JSONArray()
                    val parts = ArrayList<VideoPart>()
                    for (index in 0 until pages.length()) {
                        val page = pages.optJSONObject(index) ?: continue
                        parts += VideoPart(
                            page.optInt("page", index + 1),
                            page.optString("part").ifBlank { "第 ${index + 1} P" },
                            page.optLong("cid"),
                            page.optInt("duration")
                        )
                    }
                    mainHandler.post { callback(video, parts, null) }
                    return@executeNetwork
                } catch (error: Exception) {
                    lastError = error
                }
            }
            mainHandler.post { callback(null, emptyList(), readableError(lastError ?: IllegalStateException("无法获取视频信息"))) }
        }
    }

    /** UP主粉丝数（万级由界面格式化）。mid 无效时直接回调 null。 */
    fun fetchFans(mid: Long, callback: (Long?, String?) -> Unit) {
        if (mid <= 0L) { callback(null, null); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson("https://api.bilibili.com/x/relation/stat?vmid=$mid")
                ensureSuccess(root)
                val fans = root.optJSONObject("data")?.optLong("follower", 0L) ?: 0L
                mainHandler.post { callback(fans, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    /** 视频当前在线观看人数。aid/cid 无效时直接回调 null。 */
    fun fetchOnline(aid: Long, cid: Long, callback: (Long?, String?) -> Unit) {
        if (aid <= 0L || cid <= 0L) { callback(null, null); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson("https://api.bilibili.com/x/player/online/total?aid=$aid&cid=$cid")
                ensureSuccess(root)
                val data = root.optJSONObject("data")
                val online = (data?.optLong("total", 0L) ?: 0L).coerceAtLeast(data?.optLong("count", 0L) ?: 0L)
                mainHandler.post { callback(online, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    /** 加载一页评论；sort=0 按热度（高赞），sort=1 按最新时间。page 从 1 开始，返回下一页页码（-1 表示没有更多）。 */
    /** 加载一页评论；sort=0 按热度（高赞），sort=1 按最新时间。
     *  实测（2026-07）：旧接口 x/v2/reply 的 sort 参数已失效（sort=0 返回空、sort=1/2 同序），
     *  必须用新接口 x/v2/reply/main 的 mode 参数：mode=0 热度 / mode=2 时间，
     *  且分页用游标（pagination_str.offset），不能用 pn/ps。 */
    private val commentCursors = HashMap<String, String>()

    fun fetchComments(aid: Long, page: Int, sort: Int = 0, callback: (List<VideoComment>, Int, String?) -> Unit) {
        if (aid <= 0L) { callback(emptyList(), -1, null); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(emptyList(), -1, "操作过快，请稍后再试") } }
        ) {
            try {
                val cursorKey = "$aid:$sort"
                if (page <= 1) commentCursors.remove(cursorKey)
                val offset = commentCursors[cursorKey] ?: ""
                val signed = BiliWbiSign.signParams(
                    mapOf(
                        "type" to "1", "oid" to aid.toString(),
                        "mode" to (if (sort == 0) "0" else "2"),
                        "pagination_str" to """{"offset":"$offset"}""",
                        "plat" to "1", "web_location" to "333.788"
                    )
                )
                // Prefer the full playurl cookie (session + buvid). Extra headers override
                // the default session cookie in getJson so reply.action is personalised.
                val root = getJson(
                    "https://api.bilibili.com/x/v2/reply/main?$signed",
                    mapOf("Cookie" to BiliPlayUrl.cookieHeader())
                )
                ensureSuccess(root)
                val data = root.optJSONObject("data") ?: throw IllegalStateException("未返回评论数据")
                val replies = data.optJSONArray("replies") ?: JSONArray()
                val list = ArrayList<VideoComment>(replies.length())
                for (index in 0 until replies.length()) {
                    val reply = replies.optJSONObject(index) ?: continue
                    list += parseComment(reply)
                }
                val cursor = data.optJSONObject("cursor")
                // reply/main's real next-page token lives in
                // cursor.pagination_reply.next_offset (a string); cursor.next
                // is only an int page hint and passing it as offset silently
                // returns page 1 again, which is why "load more" used to
                // repeat the same comments forever.
                val pagination = cursor?.optJSONObject("pagination_reply")
                val next = pagination?.optString("next_offset").orEmpty()
                    .ifEmpty { cursor?.optString("next", "").orEmpty() }
                val isEnd = cursor?.optBoolean("is_end", true) ?: true
                if (next.isNotBlank()) commentCursors[cursorKey] = next
                val nextPage = if (isEnd || list.isEmpty()) -1 else page + 1
                mainHandler.post { callback(list, nextPage, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), -1, readableError(error)) }
            }
        }
    }

    /** 楼中楼（子评论）分页加载；root=主评论 rpid。page 从 1 开始，-1 表示没有更多。 */
    fun fetchSubReplies(aid: Long, root: Long, page: Int, callback: (List<VideoComment>, Int, String?) -> Unit) {
        if (aid <= 0L || root <= 0L) { callback(emptyList(), -1, null); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(emptyList(), -1, "操作过快，请稍后再试") } }
        ) {
            try {
                val signed = BiliWbiSign.signParams(
                    mapOf(
                        "type" to "1", "oid" to aid.toString(), "root" to root.toString(),
                        "pn" to page.toString(), "ps" to "20", "plat" to "1"
                    )
                )
                val rootJson = getJson(
                    "https://api.bilibili.com/x/v2/reply/reply?$signed",
                    mapOf("Cookie" to BiliPlayUrl.cookieHeader())
                )
                ensureSuccess(rootJson)
                val data = rootJson.optJSONObject("data") ?: throw IllegalStateException("未返回回复数据")
                val replies = data.optJSONArray("replies") ?: JSONArray()
                val list = ArrayList<VideoComment>(replies.length())
                for (index in 0 until replies.length()) {
                    val reply = replies.optJSONObject(index) ?: continue
                    list += parseComment(reply)
                }
                val pageInfo = data.optJSONObject("page")
                val total = pageInfo?.optLong("count", 0L) ?: 0L
                val nextPage = if (list.isNotEmpty() && page * 20L < total) page + 1 else -1
                mainHandler.post { callback(list, nextPage, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), -1, readableError(error)) }
            }
        }
    }

    /** Parse one reply object (shared by top-level and in-floor replies). */
    private fun parseComment(reply: JSONObject): VideoComment {
        val member = reply.optJSONObject("member")
        val content = reply.optJSONObject("content")
        val message = cleanHtml(content?.optString("message").orEmpty())
        // Official Bilibili emotes (placeholder name -> url): rendered inline at text size.
        val emotes = HashMap<String, String>()
        content?.optJSONObject("emote")?.let { emote ->
            val names = emote.keys()
            while (names.hasNext()) {
                val name = names.next()
                val value = emote.optJSONObject(name)
                val url = value?.optString("url").orEmpty()
                if (url.isNotBlank()) emotes[name] = normalizeUrl(url)
            }
        }
        // User-uploaded comment images (pictures array, each item has img_src).
        val pictures = ArrayList<CommentPicture>()
        content?.optJSONArray("pictures")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                val url = item?.optString("img_src").orEmpty()
                if (url.isNotBlank()) {
                    val normalized = normalizeUrl(url)
                    if (pictures.none { it.url == normalized }) {
                        pictures += CommentPicture(
                            url = normalized,
                            width = item.optInt("img_width", 0),
                            height = item.optInt("img_height", 0)
                        )
                    }
                }
            }
        }
        // In-floor replies already embedded in the response (up to 3).
        val subs = ArrayList<VideoComment>()
        reply.optJSONArray("replies")?.let { array ->
            for (index in 0 until array.length()) {
                val sub = array.optJSONObject(index) ?: continue
                subs += parseComment(sub)
            }
        }
        // Keep the "[name]" placeholders in the content: the renderer replaces
        // them with inline emote images. Removing them here made emotes
        // disappear once the separate emote row was replaced by inline spans,
        // and pure-sticker comments used to be mislabelled as deleted.
        return VideoComment(
            id = reply.optLong("rpid", 0L),
            user = member?.optString("uname").orEmpty().ifBlank { "哔哩哔哩用户" },
            avatar = normalizeUrl(member?.optString("avatar").orEmpty()),
            content = message.ifBlank { "（评论已删除）" },
            likes = reply.optLong("like", 0L),
            publishedAt = reply.optLong("ctime", 0L),
            emotes = emotes,
            pictures = pictures,
            replies = subs,
            replyCount = reply.optLong("rcount", 0L),
            // action: 0=none, 1=liked by the signed-in account.
            liked = reply.optInt("action", 0) == 1
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // 互动接口（预留）：以下接口需要登录 Cookie（SESSDATA + bili_jct）。
    // 当前版本没有登录入口，统一走"未登录"分支返回提示；登录功能实现后
    // 这些方法即可直接使用。详见 docs/BILI_API_INTEGRATION.md。
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 评论点赞/取消赞（需登录）。
     *
     * 官方文档（bilibili-API-collect comment/action.md）：
     *   POST https://api.bilibili.com/x/v2/reply/action
     *   form: type=1, oid=aid, rpid, action=1点赞/0取消, csrf=bili_jct
     *   成功只返回 {code:0}，不带 like/action。
     *
     * 实测（2026-08-02）：
     * - x/v2/reply/info 即使 code=0 也没有 data，不可用。
     * - 登录后 reply/main 的每条评论 action 字段是权威状态：0无 / 1已赞 / 2已踩。
     * - 点赞数 like 会有短暂延迟，不作为“是否成功”的依据。
     * - 评论错误码是 12002/12004/12006/12009/12011 等，不是视频点赞的 65004/65006。
     *
     * 因此本方法：action 成功即返回 true；UI 用“请求态”更新，重进列表时由 reply/main 校正。
     */
    fun postCommentLike(
        oid: Long,
        rpid: Long,
        like: Boolean,
        callback: (Boolean, String?) -> Unit
    ) {
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录后再点赞评论"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = postForm(
                    "https://api.bilibili.com/x/v2/reply/action",
                    mapOf(
                        "type" to "1",
                        "oid" to oid.toString(),
                        "rpid" to rpid.toString(),
                        "action" to (if (like) "1" else "0"),
                        "csrf" to csrf
                    )
                )
                val code = root.optInt("code", -1)
                if (code != 0) {
                    // Keep Bilibili's own message (e.g. 12004 禁止操作赞或踩).
                    throw IllegalStateException(
                        root.optString("message").ifBlank { "评论点赞失败($code)" }
                    )
                }
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /** 视频点赞/取消赞（需登录）。like=1 点赞 / 2 取消（B 站协议）。 */
    fun likeVideo(aid: Long, like: Boolean, callback: (Boolean, String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录后再点赞"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = postForm(
                    "https://api.bilibili.com/x/web-interface/archive/like",
                    mapOf("aid" to aid.toString(), "like" to (if (like) "1" else "2"), "csrf" to csrf)
                )
                // code 65006 = already liked; treat as success so UI stays on.
                // code 65004 = already cancelled; treat as success so UI stays off.
                val code = root.optInt("code", -1)
                if (code != 0 && code != 65006 && code != 65004) {
                    throw IllegalStateException(root.optString("message").ifBlank { "点赞失败" })
                }
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /**
     * Authoritative like state for the signed-in account.
     * `x/web-interface/view` often omits `req_user.like`, so detail pages query
     * this endpoint separately: data=1 means liked, data=0 means not.
     */
    fun fetchHasLiked(aid: Long, callback: (Boolean?, String?) -> Unit) {
        if (aid <= 0L || !BiliSessionStore.isLoggedIn()) {
            callback(null, null)
            return
        }
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson("https://api.bilibili.com/x/web-interface/archive/has/like?aid=$aid")
                ensureSuccess(root)
                val liked = when (val data = root.opt("data")) {
                    is Number -> data.toInt() == 1
                    is Boolean -> data
                    else -> root.optInt("data", 0) == 1
                }
                mainHandler.post { callback(liked, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    /** 我的收藏夹列表（需登录）。返回 (id, 名称, 视频数)。 */
    fun fetchFavoriteFolders(callback: (List<FavoriteFolder>, String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(emptyList(), "请先登录后再收藏"); return }
        val mid = BiliSessionStore.cookie().substringAfter("DedeUserID=", "").substringBefore(";").trim()
        if (mid.isBlank()) { callback(emptyList(), "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(emptyList(), "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson(
                    "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid",
                    mapOf("Cookie" to BiliSessionStore.cookie())
                )
                ensureSuccess(root)
                val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
                val folders = ArrayList<FavoriteFolder>(list.length())
                for (index in 0 until list.length()) {
                    val item = list.optJSONObject(index) ?: continue
                    folders += FavoriteFolder(
                        id = item.optLong("id", 0L),
                        name = item.optString("title").ifBlank { "未命名收藏夹" },
                        count = item.optLong("media_count", 0L)
                    )
                }
                mainHandler.post { callback(folders, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), readableError(error)) }
            }
        }
    }

    /**
     * 查询视频收藏状态（需登录）：是否已收藏。
     * GET /x/v2/fav/video/favoured 是查询「是否已收藏」最稳的接口（无需 CSRF，已实测）。
     * favIds 恒为空：B 站没有公开的 multi_fav 查询口（deal 为 POST 且必须带增删参数），
     * 弹窗「取消收藏」时由 UI 层用「全部收藏夹 id」兜底。
     */
    fun fetchFavoriteDeal(aid: Long, callback: (isFav: Boolean, favIds: List<Long>, error: String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(false, emptyList(), null); return }
        if (aid <= 0L) { callback(false, emptyList(), null); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, emptyList(), "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson("https://api.bilibili.com/x/v2/fav/video/favoured?aid=$aid")
                ensureSuccess(root)
                val isFav = root.optJSONObject("data")?.optBoolean("favoured", false) == true
                mainHandler.post { callback(isFav, emptyList(), null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, emptyList(), readableError(error)) }
            }
        }
    }

    /** 收藏夹内视频列表（需登录）。默认第一页 20 条；空列表表示收藏夹为空。 */
    fun fetchFavoriteVideos(
        mediaId: Long,
        page: Int = 1,
        pageSize: Int = 20,
        keyword: String = "",
        callback: (List<VideoItem>, String?, Boolean) -> Unit
    ) {
        if (!BiliSessionStore.isLoggedIn()) {
            callback(emptyList(), "请先登录后再查看收藏", true); return
        }
        if (mediaId <= 0L) { callback(emptyList(), "收藏夹不存在", true); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(emptyList(), "操作过快，请稍后再试", true) } }
        ) {
            try {
                val params = linkedMapOf(
                    "media_id" to mediaId.toString(),
                    "pn" to page.coerceAtLeast(1).toString(),
                    "ps" to pageSize.coerceIn(1, 40).toString(),
                    "platform" to "web",
                    "web_location" to "333.788"
                )
                val kw = keyword.trim()
                if (kw.isNotBlank()) params["keyword"] = kw
                val signed = BiliWbiSign.signParams(params)
                val root = getJson(
                    "https://api.bilibili.com/x/v3/fav/resource/list?$signed",
                    mapOf("Cookie" to BiliPlayUrl.cookieHeader())
                )
                ensureSuccess(root)
                val data = root.optJSONObject("data") ?: JSONObject()
                val medias = data.optJSONArray("medias") ?: JSONArray()
                val hasMore = data.optBoolean("has_more", medias.length() >= pageSize)
                val list = ArrayList<VideoItem>(medias.length())
                for (index in 0 until medias.length()) {
                    val item = medias.optJSONObject(index) ?: continue
                    val upper = item.optJSONObject("upper")
                    val bvid = item.optString("bvid")
                    if (bvid.isBlank()) continue
                    list += VideoItem(
                        bvid = bvid,
                        title = item.optString("title").ifBlank { "（无标题）" },
                        owner = upper?.optString("name").orEmpty().ifBlank { "未知UP主" },
                        cover = normalizeUrl(item.optString("cover")),
                        views = item.optJSONObject("cnt_info")?.optLong("play", 0L) ?: 0L,
                        duration = item.optInt("duration", 0),
                        description = "",
                        publishedAt = item.optLong("pubtime", 0L),
                        aid = item.optLong("id", 0L),
                        ownerMid = upper?.optLong("mid", 0L) ?: 0L
                    )
                }
                mainHandler.post { callback(list, null, !hasMore) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), readableError(error), true) }
            }
        }
    }

    /**
     * 跨收藏夹搜索：遍历用户全部自建收藏夹，按关键词拉 resource/list。
     * 结果按 bvid 去重后汇总，适合“搜索所有收藏夹内视频”。
     */
    fun searchAllFavoriteVideos(
        keyword: String,
        callback: (List<VideoItem>, String?) -> Unit
    ) {
        val q = keyword.trim()
        if (q.isBlank()) { callback(emptyList(), null); return }
        if (!BiliSessionStore.isLoggedIn()) {
            callback(emptyList(), "请先登录后再搜索收藏"); return
        }
        executeNetwork(
            onRejected = { mainHandler.post { callback(emptyList(), "操作过快，请稍后再试") } }
        ) {
            try {
                val mid = BiliSessionStore.cookie()
                    .substringAfter("DedeUserID=", "").substringBefore(";").trim()
                if (mid.isBlank()) throw IllegalStateException("登录凭证不完整")
                val foldersRoot = getJson(
                    "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid",
                    mapOf("Cookie" to BiliSessionStore.cookie())
                )
                ensureSuccess(foldersRoot)
                val folders = foldersRoot.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
                val seen = HashSet<String>()
                val merged = ArrayList<VideoItem>()
                for (i in 0 until folders.length()) {
                    val folder = folders.optJSONObject(i) ?: continue
                    val mediaId = folder.optLong("id", 0L)
                    if (mediaId <= 0L) continue
                    // 每个收藏夹最多拉 2 页，避免搜索一次打爆网络
                    for (page in 1..2) {
                        val signed = BiliWbiSign.signParams(
                            mapOf(
                                "media_id" to mediaId.toString(),
                                "pn" to page.toString(),
                                "ps" to "20",
                                "keyword" to q,
                                "platform" to "web",
                                "web_location" to "333.788"
                            )
                        )
                        val root = getJson(
                            "https://api.bilibili.com/x/v3/fav/resource/list?$signed",
                            mapOf("Cookie" to BiliPlayUrl.cookieHeader())
                        )
                        ensureSuccess(root)
                        val data = root.optJSONObject("data") ?: JSONObject()
                        val medias = data.optJSONArray("medias") ?: JSONArray()
                        for (j in 0 until medias.length()) {
                            val item = medias.optJSONObject(j) ?: continue
                            val bvid = item.optString("bvid")
                            if (bvid.isBlank() || !seen.add(bvid)) continue
                            val upper = item.optJSONObject("upper")
                            merged += VideoItem(
                                bvid = bvid,
                                title = item.optString("title").ifBlank { "（无标题）" },
                                owner = upper?.optString("name").orEmpty().ifBlank { "未知UP主" },
                                cover = normalizeUrl(item.optString("cover")),
                                views = item.optJSONObject("cnt_info")?.optLong("play", 0L) ?: 0L,
                                duration = item.optInt("duration", 0),
                                description = "",
                                publishedAt = item.optLong("pubtime", 0L),
                                aid = item.optLong("id", 0L),
                                ownerMid = upper?.optLong("mid", 0L) ?: 0L
                            )
                        }
                        if (!data.optBoolean("has_more", false) || medias.length() == 0) break
                    }
                }
                mainHandler.post { callback(merged, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), readableError(error)) }
            }
        }
    }

    /** 拉取 B 站观看历史（需登录）。返回最近观看的视频列表。 */
    /**
     * 拉取 B 站观看历史（需登录）。旧接口 x/v2/history 已废弃（code -400），
     * 改用 x/web-interface/history/cursor 游标分页：
     * 首次 max=0&view_at=0&business=archive；
     * 后续必须把上一页 cursor 的 max / view_at / business 原样回传，
     * 否则会卡在第一段（这是「历史显示不全」的常见根因）。
     *
     * callback: (videos, error, nextMax, nextViewAt, nextBusiness, ended)
     */
    fun fetchHistory(
        max: Long = 0L,
        viewAt: Long = 0L,
        business: String = "archive",
        callback: (List<VideoItem>, String?, Long, Long, String, Boolean) -> Unit
    ) {
        if (!BiliSessionStore.isLoggedIn()) {
            callback(emptyList(), "请先登录后再查看历史记录", 0L, 0L, business, true); return
        }
        executeNetwork(
            onRejected = {
                mainHandler.post { callback(emptyList(), "操作过快，请稍后再试", max, viewAt, business, false) }
            }
        ) {
            try {
                val biz = business.ifBlank { "archive" }
                val root = getJson(
                    "https://api.bilibili.com/x/web-interface/history/cursor" +
                        "?max=$max&view_at=$viewAt&business=${URLEncoder.encode(biz, "UTF-8")}&ps=30",
                    mapOf("Cookie" to BiliPlayUrl.cookieHeader())
                )
                ensureSuccess(root)
                val data = root.optJSONObject("data") ?: JSONObject()
                val list = data.optJSONArray("list") ?: JSONArray()
                val cursor = data.optJSONObject("cursor")
                val nextMax = cursor?.optLong("max", max) ?: max
                val nextViewAt = cursor?.optLong("view_at", viewAt) ?: viewAt
                val nextBusiness = cursor?.optString("business").orEmpty().ifBlank { biz }
                val videos = ArrayList<VideoItem>(list.length())
                for (index in 0 until list.length()) {
                    val item = list.optJSONObject(index) ?: continue
                    val history = item.optJSONObject("history") ?: continue
                    // 只收视频稿件；cursor 里 business 可能短暂切到 pgc 等，跳过无 bvid 的项
                    val bvid = history.optString("bvid")
                    if (bvid.isBlank()) continue
                    videos += VideoItem(
                        bvid = bvid,
                        title = item.optString("title").ifBlank { "（无标题）" },
                        owner = item.optString("author_name").ifBlank { "未知UP主" },
                        cover = normalizeUrl(item.optString("cover")),
                        views = 0L,
                        duration = item.optInt("duration", 0),
                        description = "",
                        // 历史接口的 view_at 是观看时间（秒）
                        publishedAt = item.optLong("view_at", 0L),
                        cid = history.optLong("cid", 0L),
                        aid = history.optLong("oid", 0L),
                        ownerMid = item.optLong("author_mid", 0L)
                    )
                }
                // 结束判定：本页原始 list 为空，或游标没有前进（B 站常见终点）
                val ended = list.length() == 0 ||
                    (nextMax == max && nextViewAt == viewAt && max != 0L)
                mainHandler.post { callback(videos, null, nextMax, nextViewAt, nextBusiness, ended) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), readableError(error), max, viewAt, business, false) }
            }
        }
    }

    /**
     * 搜索观看历史（需登录）。
     * 官方 web 接口：x/web-interface/history/search?keyword=&pn=
     * 只取 archive（视频）结果。
     */
    fun searchHistory(
        keyword: String,
        page: Int = 1,
        callback: (List<VideoItem>, String?, Boolean) -> Unit
    ) {
        val q = keyword.trim()
        if (q.isBlank()) {
            callback(emptyList(), null, true); return
        }
        if (!BiliSessionStore.isLoggedIn()) {
            callback(emptyList(), "请先登录后再搜索历史", true); return
        }
        executeNetwork(
            onRejected = { mainHandler.post { callback(emptyList(), "操作过快，请稍后再试", true) } }
        ) {
            try {
                val root = getJson(
                    "https://api.bilibili.com/x/web-interface/history/search" +
                        "?keyword=${URLEncoder.encode(q, "UTF-8")}&pn=$page&business=archive",
                    mapOf("Cookie" to BiliPlayUrl.cookieHeader())
                )
                ensureSuccess(root)
                val data = root.optJSONObject("data") ?: JSONObject()
                // 兼容 list / raw_list / data.list 多种返回
                val list = data.optJSONArray("list")
                    ?: data.optJSONArray("raw_list")
                    ?: JSONArray()
                val videos = ArrayList<VideoItem>(list.length())
                for (index in 0 until list.length()) {
                    val item = list.optJSONObject(index) ?: continue
                    val history = item.optJSONObject("history")
                    val bvid = history?.optString("bvid").orEmpty()
                        .ifBlank { item.optString("bvid") }
                    if (bvid.isBlank()) continue
                    videos += VideoItem(
                        bvid = bvid,
                        title = item.optString("title").ifBlank { "（无标题）" },
                        owner = item.optString("author_name").ifBlank {
                            item.optJSONObject("owner")?.optString("name").orEmpty().ifBlank { "未知UP主" }
                        },
                        cover = normalizeUrl(
                            item.optString("cover").ifBlank { item.optString("pic") }
                        ),
                        views = 0L,
                        duration = item.optInt("duration", 0),
                        description = "",
                        publishedAt = item.optLong("view_at", 0L),
                        cid = history?.optLong("cid", 0L) ?: 0L,
                        aid = history?.optLong("oid", 0L)
                            ?: item.optLong("aid", 0L),
                        ownerMid = item.optLong("author_mid", 0L)
                    )
                }
                val hasMore = data.optBoolean("has_more", list.length() >= 20)
                mainHandler.post { callback(videos, null, !hasMore && videos.isNotEmpty() || list.length() == 0) }
            } catch (error: Exception) {
                mainHandler.post { callback(emptyList(), readableError(error), true) }
            }
        }
    }

    /** 上报观看进度到 B 站历史（需登录）。status: 3=播放中, 2=暂停/退出。 */
    fun reportHistory(aid: Long, cid: Long, progressSeconds: Long, durationSeconds: Long, status: Int) {
        if (aid <= 0L || cid <= 0L || !BiliSessionStore.isLoggedIn()) return
        val form = linkedMapOf(
            "aid" to aid.toString(),
            "cid" to cid.toString(),
            "progress" to progressSeconds.toString(),
            "pt" to durationSeconds.toString(),
            "status" to status.toString(),
            "type" to "3",
            "csrf" to csrfToken()
        )
        Thread {
            try { postForm("https://api.bilibili.com/x/v2/history/report", form) } catch (_: Exception) { }
        }.start()
    }

    /** 收藏/取消收藏视频（需登录）。rid=视频 aid，type=2 固定；addIds/delIds 为逗号分隔的收藏夹 id。 */
    fun favoriteVideo(aid: Long, addIds: String, delIds: String, callback: (Boolean, String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录后再收藏"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = postForm(
                    "https://api.bilibili.com/x/v3/fav/resource/deal",
                    mapOf(
                        "rid" to aid.toString(), "type" to "2",
                        "add_media_ids" to addIds, "del_media_ids" to delIds,
                        "platform" to "web", "csrf" to csrf
                    )
                )
                ensureSuccess(root)
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /**
     * 查询与目标用户的关系（需登录）。
     * 使用 x/space/acc/relation：
     * data.relation.attribute = 我对 TA 的关系
     * 0 无关系 / 1 悄悄关注 / 2 关注 / 6 互粉 / 128 拉黑。
     * 1/2/6 都视为「已关注」。
     */
    fun fetchRelation(mid: Long, callback: (Boolean?, String?) -> Unit) {
        if (mid <= 0L) { callback(null, null); return }
        if (!BiliSessionStore.isLoggedIn()) { callback(false, null); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson("https://api.bilibili.com/x/space/acc/relation?mid=$mid")
                ensureSuccess(root)
                val data = root.optJSONObject("data")
                val attr = data?.optJSONObject("relation")?.optInt("attribute", 0)
                    ?: data?.optInt("attribute", 0)
                    ?: 0
                val following = attr == 1 || attr == 2 || attr == 6
                mainHandler.post { callback(following, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    /** 关注/取关 UP 主（需登录）。fid=目标用户 mid，act=1 关注 / 2 取关。 */
    fun modifyRelation(fid: Long, follow: Boolean, callback: (Boolean, String?) -> Unit) {
        if (!BiliSessionStore.isLoggedIn()) { callback(false, "请先登录后再关注"); return }
        val csrf = csrfToken()
        if (csrf.isBlank()) { callback(false, "登录凭证不完整"); return }
        executeNetwork(
            onRejected = { mainHandler.post { callback(false, "操作过快，请稍后再试") } }
        ) {
            try {
                // re_src 是网页端常规来源位；缺它时部分账号会返回成功但关系未落库。
                val root = postForm(
                    "https://api.bilibili.com/x/relation/modify",
                    mapOf(
                        "fid" to fid.toString(),
                        "act" to (if (follow) "1" else "2"),
                        "re_src" to "11",
                        "csrf" to csrf
                    )
                )
                ensureSuccess(root)
                mainHandler.post { callback(true, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(false, readableError(error)) }
            }
        }
    }

    /**
     * 下载画质策略（预留接口）：未登录仅提供 360P/480P；登录后可解锁 720P/1080P；
     * 1080P60+/4K/杜比/8K 需要大会员。qn 值对应 playurl 的 quality 参数，
     * fnval=4048 请求 DASH 流（参考 BiliTools：DASH 自动合并音视频，支持高画质）。
     */
    fun downloadQualities(): List<DownloadQuality> {
        val loggedIn = BiliSessionStore.isLoggedIn()
        val list = arrayListOf(
            DownloadQuality(16, "360P", "流畅", false),
            DownloadQuality(32, "480P", "清晰", false)
        )
        if (loggedIn) {
            list += DownloadQuality(64, "720P", "高清", false)
            list += DownloadQuality(80, "1080P", "全高清", false)
            list += DownloadQuality(116, "1080P60", "60帧", true)
            list += DownloadQuality(112, "1080P+", "高码率", true)
            list += DownloadQuality(120, "4K", "超清", true)
        }
        return list
    }

    private fun csrfToken(): String =
        BiliSessionStore.cookie()
            .substringAfter("bili_jct=", "").substringBefore(";").trim()

    private fun postForm(address: String, form: Map<String, String>): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", NETWORK_USER_AGENT)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            // Prefer full playurl cookie (session + buvid) for write actions.
            val cookie = BiliPlayUrl.cookieHeader().ifBlank { BiliSessionStore.cookie() }
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        try {
            val body = form.entries.joinToString("&") { (name, value) ->
                "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            connection.outputStream.bufferedWriter().use { it.write(body) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw IllegalStateException("网络请求没有返回内容（HTTP ${connection.responseCode}）")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestFeed(
        address: String,
        callback: (FeedPage?, String?) -> Unit,
        extractor: (JSONObject) -> JSONArray?
    ) {
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson(address)
                ensureSuccess(root)
                val array = extractor(root) ?: throw IllegalStateException("未返回视频列表")
                val videos = ArrayList<VideoItem>()
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { video ->
                        val parsed = videoFromJson(video)
                        if (parsed.bvid.isNotBlank()) videos += parsed
                    }
                }
                if (array.length() > 0 && videos.isEmpty()) {
                    throw IllegalStateException("未返回有效视频")
                }
                mainHandler.post { callback(FeedPage(videos, array.length() == 0), null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    private fun requestList(
        address: String,
        callback: (List<VideoItem>?, String?) -> Unit,
        extractor: (JSONObject) -> JSONArray
    ) {
        executeNetwork(
            onRejected = { mainHandler.post { callback(null, "操作过快，请稍后再试") } }
        ) {
            try {
                val root = getJson(address)
                ensureSuccess(root)
                val array = extractor(root)
                val videos = ArrayList<VideoItem>()
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { videos += videoFromJson(it) }
                }
                mainHandler.post { callback(videos, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, readableError(error)) }
            }
        }
    }

    private fun executeNetwork(onRejected: () -> Unit, task: () -> Unit) {
        try {
            networkWorkers.execute(task)
        } catch (_: RejectedExecutionException) {
            onRejected()
        }
    }

    private fun executeSearch(onRejected: () -> Unit, task: () -> Unit) {
        try {
            searchWorkers.execute(task)
        } catch (_: RejectedExecutionException) {
            onRejected()
        }
    }

    private fun ensureAnonymousSearchCookie() {
        if (anonymousSearchCookie.isNotBlank()) return
        val root = getJson(FINGERPRINT_URL)
        ensureSuccess(root)
        val data = root.optJSONObject("data") ?: throw IllegalStateException("未建立搜索会话")
        val buvid3 = data.optString("b_3")
        val buvid4 = data.optString("b_4")
        if (buvid3.isBlank()) throw IllegalStateException("未建立搜索会话")
        anonymousSearchCookie = "buvid3=$buvid3; buvid4=$buvid4; CURRENT_FNVAL=4048; CURRENT_QUALITY=80"
    }

    private fun searchHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Referer"] = "https://search.bilibili.com/"
        headers["Origin"] = "https://search.bilibili.com"
        if (anonymousSearchCookie.isNotBlank()) headers["Cookie"] = anonymousSearchCookie
        return headers
    }

    private fun isRequestBanned(error: Exception): Boolean =
        error.message.orEmpty().contains("request was banned", ignoreCase = true)

    private fun getJson(address: String, extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", NETWORK_USER_AGENT)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            // Logged-in cookie unlocks personalised fields such as req_user.like
            // and reply.action. Anonymous requests keep working without it.
            val session = BiliSessionStore.cookie()
            if (session.isNotBlank()) setRequestProperty("Cookie", session)
            extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) throw IllegalStateException("网络请求没有返回内容（HTTP ${connection.responseCode}）")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureSuccess(root: JSONObject) {
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException(root.optString("message").ifBlank { "接口暂不可用" })
        }
    }

    private fun videoFromJson(source: JSONObject): VideoItem {
        val ownerObject = source.optJSONObject("owner")
        val title = cleanHtml(source.optString("title")).ifBlank { "未命名视频" }
        // req_user.like is only present when the request carries a session cookie.
        val reqUser = source.optJSONObject("req_user")
        val liked = when {
            reqUser == null -> false
            reqUser.has("like") -> reqUser.optInt("like", 0) == 1
            else -> false
        }

        // 付费/充电状态只认站方明确标记：rights、UP 主充电专属字段或付费合集字段。
        // 不能用播放流时长与 view.duration 的比例猜测，多 P、DURL、试看/转码都会造成误报。
        val charge = hasExplicitChargeFlag(source)

        // 官方合集：data.ugc_season 的 sections[].episodes[] 展平为有序分集。
        val season = source.optJSONObject("ugc_season")?.let { seasonJson ->
            val sections = seasonJson.optJSONArray("sections") ?: JSONArray()
            val episodes = ArrayList<UgcEpisode>()
            for (sectionIndex in 0 until sections.length()) {
                val section = sections.optJSONObject(sectionIndex) ?: continue
                val epArray = section.optJSONArray("episodes") ?: continue
                for (epIndex in 0 until epArray.length()) {
                    val ep = epArray.optJSONObject(epIndex) ?: continue
                    val bvid = ep.optString("bvid")
                    val aid = ep.optLong("aid")
                    if (bvid.isBlank() && aid <= 0L) continue
                    episodes += UgcEpisode(
                        bvid = bvid,
                        aid = aid,
                        cid = ep.optLong("cid"),
                        title = cleanHtml(ep.optString("title"))
                            .ifBlank { "第 ${episodes.size + 1} 集" },
                        // episodes 顶层没有 duration，真实时长在 arc.duration 里。
                        duration = ep.optInt("duration").takeIf { it > 0 }
                            ?: (ep.optJSONObject("arc")?.optInt("duration") ?: 0),
                        // episodes 顶层一般没有 cover，分集封面在 arc.pic（arc 与 data 对象同构）；
                        // 保留顶层 cover 兼容个别返回该字段的接口形态。
                        cover = normalizeUrl(ep.optString("cover").ifBlank {
                            ep.optJSONObject("arc")?.optString("pic").orEmpty()
                        }),
                        charge = hasExplicitChargeFlag(ep) ||
                            hasExplicitChargeFlag(ep.optJSONObject("arc"))
                    )
                }
            }
            if (episodes.isEmpty()) null else UgcSeason(
                id = seasonJson.optLong("id"),
                title = cleanHtml(seasonJson.optString("title")).ifBlank { "合集" },
                cover = normalizeUrl(seasonJson.optString("cover")),
                episodes = episodes,
                isMultiPage = false
            )
        } ?: run {
            // 另一种常见合集形态：同一个 BV 下的多 P，详情接口只返回 pages[]，
            // 不会返回 ugc_season。它也必须进入合集分集 UI 和下载选择器。
            val pages = source.optJSONArray("pages") ?: JSONArray()
            if (pages.length() <= 1) {
                null
            } else {
                val rootBvid = source.optString("bvid")
                val rootAid = source.optLong("aid")
                val episodes = ArrayList<UgcEpisode>()
                for (index in 0 until pages.length()) {
                    val page = pages.optJSONObject(index) ?: continue
                    episodes += UgcEpisode(
                        bvid = rootBvid,
                        aid = rootAid,
                        cid = page.optLong("cid"),
                        title = cleanHtml(page.optString("part"))
                            .ifBlank { "第 ${page.optInt("page", index + 1)} P" },
                        duration = page.optInt("duration"),
                        // 多P 没有独立稿件封面，用 B 站为该 P 生成的预览帧(first_frame)作封面，
                        // 避免「下载封面」时每一P都回退成视频详情页主封面。
                        cover = normalizeUrl(page.optString("first_frame")),
                        charge = charge
                    )
                }
                if (episodes.isEmpty()) null else UgcSeason(
                    id = rootAid.takeIf { it > 0L }
                        ?: rootBvid.hashCode().toLong(),
                    title = title,
                    cover = normalizeUrl(source.optString("pic")),
                    episodes = episodes,
                    isMultiPage = true
                )
            }
        }

        val normalizedSeason = season?.let { parsed ->
            if (!charge) parsed else parsed.copy(
                episodes = parsed.episodes.map { episode ->
                    if ((episode.cid > 0L && episode.cid == source.optLong("cid")) ||
                        (episode.bvid.isNotBlank() && episode.bvid == source.optString("bvid") &&
                            episode.aid > 0L && episode.aid == source.optLong("aid"))
                    ) episode.copy(charge = true) else episode
                }
            )
        }

        return VideoItem(
            bvid = source.optString("bvid"),
            title = title,
            owner = source.optString("author").ifBlank { ownerObject?.optString("name").orEmpty() }
                .ifBlank { "哔哩哔哩用户" },
            cover = normalizeUrl(source.optString("pic")),
            views = source.optLong("play", source.optJSONObject("stat")?.optLong("view", 0L) ?: 0L),
            duration = durationFrom(source.opt("duration")),
            description = cleanHtml(source.optString("description").ifBlank { source.optString("desc") }),
            publishedAt = source.optLong("pubdate", source.optLong("ctime")),
            cid = source.optLong("cid"),
            aid = source.optLong("aid"),
            ownerMid = ownerObject?.optLong("mid", 0L) ?: 0L,
            ownerAvatar = normalizeUrl(ownerObject?.optString("face").orEmpty()),
            replyCount = source.optJSONObject("stat")?.optLong("reply", 0L)
                ?: source.optLong("review", 0L),
            likes = source.optJSONObject("stat")?.optLong("like", 0L) ?: 0L,
            liked = liked,
            season = normalizedSeason ?: season,
            charge = charge || (normalizedSeason?.episodes?.any { it.charge } == true)
        )
    }

    /** Read a Bilibili flag that may be encoded as boolean, integer 0/1, or string 0/1. */
    private fun flag(source: JSONObject?, name: String): Boolean {
        if (source == null || !source.has(name)) return false
        return source.optBoolean(name, false) || source.optInt(name, 0) == 1 ||
            source.optString(name) == "1"
    }

    /**
     * Explicit paid/charging markers from x/web-interface/view. In particular,
     * UP 主 charging-exclusive videos use is_upower_exclusive/is_upower_preview
     * while rights.arc_pay and rights.ugc_pay remain 0.
     */
    private fun hasExplicitChargeFlag(source: JSONObject?): Boolean {
        if (source == null) return false
        val rights = source.optJSONObject("rights")
        val season = source.optJSONObject("ugc_season")
        return flag(rights, "pay") || flag(rights, "arc_pay") ||
            flag(rights, "ugc_pay") || flag(rights, "ugc_pay_preview") ||
            flag(source, "is_chargeable_season") ||
            flag(source, "is_upower_exclusive") || flag(source, "is_upower_preview") ||
            flag(source, "is_upower_exclusive_with_qa") ||
            flag(season, "is_pay_season") || flag(season, "is_chargeable_season")
    }

    private fun durationFrom(value: Any?): Int = when (value) {
        is Number -> value.toInt()
        is String -> {
            val pieces = value.split(":").mapNotNull { it.toIntOrNull() }
            if (pieces.isEmpty()) 0 else pieces.fold(0) { total, part -> total * 60 + part }
        }
        else -> 0
    }

    private fun normalizeUrl(value: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://${value.removePrefix("http://") }"
        else -> value
    }

    private fun cleanHtml(value: String): String = value
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .trim()

    private fun readableError(error: Exception): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) "网络暂不可用，请稍后再试" else message
    }
}

data class VideoPart(val number: Int, val title: String, val cid: Long, val duration: Int)

const val NETWORK_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

/**
 * User-Agent for Bilibili CDN media requests (DASH segments / DURL / downloads).
 * Verified by curl: CDN nodes reject Android-mobile and BiliDroid user agents
 * with HTTP 403 and only serve desktop Web UAs (206). Same finding as
 * suzhelan/BiliCompose commit a968e57 ("fixed request headers for 403").
 */
const val CDN_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
