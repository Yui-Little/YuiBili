package com.yuilittle.bili

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** WBI signing compatible with Bilibili's public web playurl request. */
object BiliWbiSign {
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    @Volatile private var cachedImgKey: String? = null
    @Volatile private var cachedSubKey: String? = null
    @Volatile private var cachedDay: Long = -1L

    /** Returns an already URL-encoded query containing exactly one wts and one w_rid. */
    fun signParams(params: Map<String, String>): String {
        val (imgKey, subKey) = ensureKeys()
        val raw = java.util.TreeMap<String, String>()
        params.forEach { (key, value) -> raw[key] = filterIllegalChars(value) }
        raw["wts"] = (System.currentTimeMillis() / 1000L).toString()
        val query = raw.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        val mixin = getMixinKey(imgKey + subKey)
        val wRid = md5Hex(query + mixin)
        return "$query&w_rid=$wRid"
    }

    @Synchronized
    internal fun ensureKeys(): Pair<String, String> {
        val day = System.currentTimeMillis() / 86_400_000L
        val img = cachedImgKey
        val sub = cachedSubKey
        if (!img.isNullOrBlank() && !sub.isNullOrBlank() && cachedDay == day) {
            return img to sub
        }
        val fallback = "7cd084941338484aae1ad9425b84077c" to "4932caff0ff746eab6f01bf08b70ac45"
        return try {
            val connection = (URL("https://api.bilibili.com/x/web-interface/nav").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 6_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", NETWORK_USER_AGENT)
                setRequestProperty("Referer", "https://www.bilibili.com/")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            try {
                val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val image = JSONObject(body).optJSONObject("data")?.optJSONObject("wbi_img")
                val imgKey = image?.optString("img_url").orEmpty().substringAfterLast('/').substringBeforeLast('.')
                val subKey = image?.optString("sub_url").orEmpty().substringAfterLast('/').substringBeforeLast('.')
                if (imgKey.isNotBlank() && subKey.isNotBlank()) {
                    cachedImgKey = imgKey
                    cachedSubKey = subKey
                    cachedDay = day
                    imgKey to subKey
                } else fallback
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun getMixinKey(raw: String): String {
        if (raw.length < 32) return raw
        return buildString(32) {
            for (index in MIXIN_KEY_ENC_TAB.take(32)) append(raw[index])
        }
    }

    private fun filterIllegalChars(value: String): String = value.replace(Regex("[!'()*]"), "")

    private fun urlEncode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val c = byte.toInt() and 0xff
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code ||
                c == '.'.code || c == '~'.code
            ) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(String.format(Locale.US, "%02X", c))
            }
        }
        return out.toString()
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
    }
}

/**
 * Bilibili playurl client.
 *
 * The primary path is the normal Web/WBI DASH API. It does not manufacture
 * quality permissions: anonymous and logged-in users receive exactly the
 * tracks Bilibili returns for their session.
 */
object BiliPlayUrl {
    data class DashVideoTrack(
        val id: Int,
        val url: String,
        val backups: List<String>,
        val bandwidth: Int,
        val mimeType: String,
        val codecs: String,
        val width: Int,
        val height: Int,
        val frameRate: String,
        val initializationRange: String?,
        val indexRange: String?
    )

    data class DashAudioTrack(
        val id: Int,
        val url: String,
        val backups: List<String>,
        val bandwidth: Int,
        val mimeType: String,
        val codecs: String,
        val initializationRange: String?,
        val indexRange: String?
    )

    data class DurlSegment(
        val urls: List<String>,
        val lengthMs: Long,
        val sizeBytes: Long
    )

    data class DashStream(
        val videoTracks: List<DashVideoTrack>,
        val audioTracks: List<DashAudioTrack>,
        val durationMs: Long,
        val actualQuality: Int,
        val acceptedQualities: List<Int>,
        val acceptedDescriptions: List<String>,
        val isDash: Boolean,
        /** Compatibility alias for the first classic DURL segment's CDN candidates. */
        val fallbackUrls: List<String> = emptyList(),
        /** Classic DURL videos may contain multiple consecutive media segments. */
        val durlSegments: List<DurlSegment> = emptyList()
    ) {
        val videoUrl: String get() = videoTracks.firstOrNull()?.url.orEmpty()
            .ifBlank { durlSegments.firstOrNull()?.urls?.firstOrNull().orEmpty() }
            .ifBlank { fallbackUrls.firstOrNull().orEmpty() }
        val audioUrl: String get() = audioTracks.firstOrNull()?.url.orEmpty()
        val qualityLabel: String get() = BiliPlayUrl.qualityLabel(actualQuality)
    }

    @Volatile private var cachedBuvidCookie: String? = null
    @Volatile private var cachedBuvidAtMs: Long = 0L

    /** Gets the anonymous device fingerprint used by Bilibili's normal web flow. */
    @Synchronized
    fun buvidCookie(): String {
        val now = System.currentTimeMillis()
        val cached = cachedBuvidCookie
        val ttl = if (cached.isNullOrBlank()) 10L * 60_000L else 6L * 60L * 60_000L
        if (cached != null && now - cachedBuvidAtMs < ttl) return cached
        return try {
            val connection = (URL("https://api.bilibili.com/x/frontend/finger/spi").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 6_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", NETWORK_USER_AGENT)
                setRequestProperty("Referer", "https://www.bilibili.com/")
            }
            try {
                val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val data = JSONObject(body).optJSONObject("data")
                val b3 = data?.optString("b_3").orEmpty()
                val b4 = data?.optString("b_4").orEmpty()
                val result = if (b3.isBlank()) "" else {
                    "buvid3=$b3; buvid4=$b4; b_nut=${System.currentTimeMillis() / 1000L}"
                }
                cachedBuvidCookie = result
                cachedBuvidAtMs = now
                result
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            cachedBuvidCookie = ""
            cachedBuvidAtMs = now
            ""
        }
    }

    /** Cookie used for both the playurl request and subsequent CDN segment requests. */
    fun cookieHeader(fnval: Int = 4048): String {
        val values = LinkedHashMap<String, String>()
        fun add(cookie: String) {
            cookie.split(';').forEach { part ->
                val name = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isNotBlank() && value.isNotBlank()) values[name] = value
            }
        }
        add(buvidCookie())
        add(BiliSessionStore.cookie())
        values["CURRENT_FNVAL"] = fnval.toString()
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Cookie for CDN media segment requests only. CDN hosts must NOT receive
     * CURRENT_FNVAL or SESSDATA: nodes reject requests that carry those values
     * without a valid buvid3 with 403/503. The signed URL already authorizes the
     * request, so only the anonymous buvid fingerprint is sent (or nothing at all
     * when the fingerprint could not be obtained).
     */
    fun cdnCookieHeader(): String = buvidCookie()

    /** Start conservatively for fast first frame; higher tiers remain user-selectable. */
    fun defaultTargetQuality(): Int = QUALITY_720

    fun fetchPlayUrl(
        bvid: String,
        cid: Long,
        callback: (DashStream?, String?) -> Unit
    ) = fetchPlayUrl(bvid, cid, defaultTargetQuality(), callback)

    /** Requests the user's selected tier; lower tiers are always allowed to fall back normally. */
    fun fetchPlayUrl(
        bvid: String,
        cid: Long,
        targetQuality: Int,
        callback: (DashStream?, String?) -> Unit
    ) {
        Thread {
            try {
                val loggedIn = BiliSessionStore.isLoggedIn()
                // BiliPai's normal Web/WBI parameter set. No AppKey or fake token is used.
                val params = linkedMapOf(
                    "bvid" to bvid,
                    "cid" to cid.toString(),
                    "qn" to targetQuality.toString(),
                    "fnval" to "4048",
                    "fnver" to "0",
                    "fourk" to "1",
                    "voice_balance" to "1",
                    "gaia_source" to "pre-load",
                    "isGaiaAvoided" to "true",
                    "web_location" to "1315873"
                )
                if (!loggedIn) params["try_look"] = "1"
                val url = "https://api.bilibili.com/x/player/wbi/playurl?${BiliWbiSign.signParams(params)}"
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7_000
                    readTimeout = 12_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", NETWORK_USER_AGENT)
                    setRequestProperty("Referer", "https://www.bilibili.com/")
                    setRequestProperty("Origin", "https://www.bilibili.com")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    setRequestProperty("Cookie", cookieHeader())
                }
                try {
                    val httpCode = connection.responseCode
                    val body = (if (httpCode in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (httpCode !in 200..299) {
                        callback(null, "播放网络响应异常（HTTP $httpCode）")
                        return@Thread
                    }
                    val root = JSONObject(body)
                    val code = root.optInt("code", -1)
                    if (code != 0) {
                        callback(null, readablePlayError(code, root.optString("message")))
                        return@Thread
                    }
                    val data = root.optJSONObject("data") ?: run {
                        callback(null, "播放接口没有返回数据")
                        return@Thread
                    }
                    val stream = parseStream(data)
                    if (stream == null) {
                        callback(null, "当前账号没有可用的视频流")
                    } else {
                        callback(stream, null)
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                callback(null, error.message?.takeIf { it.isNotBlank() } ?: "网络请求失败")
            }
        }.start()
    }

    /**
     * Requests a classic single-file stream (fnval=0, durl) as a playback
     * fallback when DASH probing fails: one URL, no manifest parsing, much more
     * tolerant of flaky CDN links. Only 1080P and below are offered this way.
     */
    fun fetchDurlPlayUrl(
        bvid: String,
        cid: Long,
        targetQuality: Int,
        callback: (DashStream?, String?) -> Unit
    ) {
        Thread {
            try {
                val params = linkedMapOf(
                    "bvid" to bvid,
                    "cid" to cid.toString(),
                    "qn" to targetQuality.toString(),
                    "fnval" to "0",
                    "fnver" to "0",
                    "fourk" to "1",
                    "gaia_source" to "pre-load",
                    "isGaiaAvoided" to "true",
                    "web_location" to "1315873"
                )
                if (!BiliSessionStore.isLoggedIn()) params["try_look"] = "1"
                val url = "https://api.bilibili.com/x/player/wbi/playurl?${BiliWbiSign.signParams(params)}"
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7_000
                    readTimeout = 12_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", NETWORK_USER_AGENT)
                    setRequestProperty("Referer", "https://www.bilibili.com/")
                    setRequestProperty("Origin", "https://www.bilibili.com")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    setRequestProperty("Cookie", cookieHeader(fnval = 0))
                }
                try {
                    val httpCode = connection.responseCode
                    val body = (if (httpCode in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (httpCode !in 200..299) {
                        callback(null, "单文件流网络响应异常（HTTP $httpCode）")
                        return@Thread
                    }
                    val root = JSONObject(body)
                    if (root.optInt("code", -1) != 0) {
                        callback(null, "单文件流接口返回异常：${root.optString("message")}")
                        return@Thread
                    }
                    val data = root.optJSONObject("data") ?: run {
                        callback(null, "单文件流没有返回数据"); return@Thread
                    }
                    val durls = data.optJSONArray("durl") ?: run {
                        callback(null, "该视频没有单文件流（可能仅支持 DASH）"); return@Thread
                    }
                    val segments = parseDurlSegments(durls)
                    if (segments.isEmpty()) {
                        callback(null, "该视频没有可用的单文件流")
                        return@Thread
                    }
                    callback(
                        DashStream(
                            videoTracks = emptyList(), audioTracks = emptyList(),
                            durationMs = data.optLong("timelength", 0L).takeIf { it > 0L }
                                ?: segments.sumOf { it.lengthMs }.coerceAtLeast(1L),
                            actualQuality = data.optInt("quality", QUALITY_360),
                            acceptedQualities = intList(data.optJSONArray("accept_quality")),
                            acceptedDescriptions = stringList(data.optJSONArray("accept_description")),
                            isDash = false,
                            fallbackUrls = segments.first().urls,
                            durlSegments = segments
                        ), null
                    )
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                callback(null, error.message?.takeIf { it.isNotBlank() } ?: "网络请求失败")
            }
        }.start()
    }

    private fun parseStream(data: JSONObject): DashStream? {
        val accepted = intList(data.optJSONArray("accept_quality"))
        val descriptions = stringList(data.optJSONArray("accept_description"))
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val videos = parseVideoTracks(dash.optJSONArray("video"))
            val audios = parseAudioTracks(dash.optJSONArray("audio"))
            if (videos.isNotEmpty()) {
                val durationMs = when {
                    dash.optLong("duration", 0L) > 0L -> dash.optLong("duration") * 1000L
                    data.optLong("timelength", 0L) > 0L -> data.optLong("timelength")
                    else -> 1L
                }
                return DashStream(
                    videoTracks = videos,
                    audioTracks = audios,
                    durationMs = durationMs,
                    actualQuality = videos.maxOfOrNull { it.id } ?: data.optInt("quality", 0),
                    acceptedQualities = accepted,
                    acceptedDescriptions = descriptions,
                    isDash = true
                )
            }
        }

        // Some old/limited videos expose only durl. Keep this as a standards-compliant fallback.
        val durls = data.optJSONArray("durl") ?: return null
        val segments = parseDurlSegments(durls)
        if (segments.isEmpty()) return null
        return DashStream(
            videoTracks = emptyList(), audioTracks = emptyList(),
            durationMs = data.optLong("timelength", 0L).takeIf { it > 0L }
                                ?: segments.sumOf { it.lengthMs }.coerceAtLeast(1L),
            actualQuality = data.optInt("quality", QUALITY_360),
            acceptedQualities = accepted,
            acceptedDescriptions = descriptions,
            isDash = false,
            fallbackUrls = segments.first().urls,
            durlSegments = segments
        )
    }

    private fun parseDurlSegments(array: JSONArray): List<DurlSegment> {
        val segments = ArrayList<DurlSegment>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val candidates = ArrayList<String>()
            firstString(item, "url").takeIf { it.isNotBlank() }?.let { candidates += it }
            val backups = item.optJSONArray("backup_url") ?: item.optJSONArray("backupUrl")
            if (backups != null) for (i in 0 until backups.length()) {
                backups.optString(i).takeIf { it.isNotBlank() && it !in candidates }?.let { candidates += it }
            }
            if (candidates.isNotEmpty()) {
                segments += DurlSegment(
                    urls = candidates,
                    lengthMs = item.optLong("length", 0L).coerceAtLeast(0L),
                    sizeBytes = item.optLong("size", 0L).coerceAtLeast(0L)
                )
            }
        }
        return segments
    }

    private fun parseVideoTracks(array: JSONArray?): List<DashVideoTrack> {
        if (array == null) return emptyList()
        val result = ArrayList<DashVideoTrack>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = firstString(item, "baseUrl", "base_url")
            if (url.isBlank()) continue
            val segment = item.optJSONObject("segment_base") ?: item.optJSONObject("segmentBase")
            result += DashVideoTrack(
                id = item.optInt("id", 0),
                url = url,
                backups = backupUrls(item),
                bandwidth = item.optInt("bandwidth", 0),
                mimeType = firstString(item, "mime_type", "mimeType").ifBlank { "video/mp4" },
                codecs = item.optString("codecs", "avc1"),
                width = item.optInt("width", 0),
                height = item.optInt("height", 0),
                frameRate = firstString(item, "frame_rate", "frameRate"),
                initializationRange = segment?.let { firstString(it, "initialization") },
                indexRange = segment?.let { firstString(it, "index_range", "indexRange") }
            )
        }
        return result.sortedWith(compareByDescending<DashVideoTrack> { it.id }.thenBy { codecRank(it.codecs) })
    }

    private fun parseAudioTracks(array: JSONArray?): List<DashAudioTrack> {
        if (array == null) return emptyList()
        val result = ArrayList<DashAudioTrack>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = firstString(item, "baseUrl", "base_url")
            if (url.isBlank()) continue
            val segment = item.optJSONObject("segment_base") ?: item.optJSONObject("segmentBase")
            result += DashAudioTrack(
                id = item.optInt("id", 0),
                url = url,
                backups = backupUrls(item),
                bandwidth = item.optInt("bandwidth", 0),
                mimeType = firstString(item, "mime_type", "mimeType").ifBlank { "audio/mp4" },
                codecs = item.optString("codecs", "mp4a.40.2"),
                initializationRange = segment?.let { firstString(it, "initialization") },
                indexRange = segment?.let { firstString(it, "index_range", "indexRange") }
            )
        }
        return result.sortedByDescending { it.bandwidth }
    }

    private fun backupUrls(item: JSONObject): List<String> {
        val array = item.optJSONArray("backupUrl") ?: item.optJSONArray("backup_url") ?: return emptyList()
        val result = ArrayList<String>()
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let { if (it !in result) result += it }
        }
        return result
    }

    private fun firstString(item: JSONObject, vararg names: String): String {
        for (name in names) item.optString(name).takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    private fun intList(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).toIntOrNull() ?: array.optInt(index).takeIf { it > 0 }
        }.distinct().sortedDescending()
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }

    private fun codecRank(codec: String): Int = when {
        codec.startsWith("avc", true) -> 0
        codec.startsWith("hev", true) || codec.startsWith("hvc", true) -> 1
        codec.startsWith("av01", true) -> 2
        else -> 3
    }

    private fun readablePlayError(code: Int, message: String): String = when (code) {
        -101, -400 -> "请先按哔哩哔哩要求登录后再播放该画质（$message）"
        -10403, -404 -> "当前画质需要登录或大会员，已按站方权限处理（$message）"
        else -> "播放接口错误 $code${message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}"
    }

    /**
     * Pre-warm WBI keys and buvid cookie so the first fetchPlayUrl call
     * doesn't have to wait for these two extra HTTP round-trips.
     * Safe to call from any thread; results are cached internally.
     */
    fun prewarm() {
        Thread {
            BiliWbiSign.ensureKeys(); buvidCookie()
        }.start()
    }

    fun qualityLabel(id: Int): String = when (id) {
        127 -> "8K"
        126 -> "杜比视界"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P+"
        80 -> "1080P"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> if (id > 0) "${id}档" else "未知画质"
    }

    const val QUALITY_360 = 16
    const val QUALITY_480 = 32
    const val QUALITY_720 = 64
    const val QUALITY_1080 = 80
    const val QUALITY_1080_PLUS = 112
    const val QUALITY_4K = 120
}
