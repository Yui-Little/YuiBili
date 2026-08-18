package com.yuilittle.bili

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Bilibili web login endpoints (scan QR code + SMS) and the authenticated
 * profile endpoint (nav). Only official endpoints are used; nothing is
 * automated or imitated beyond what the public web flow does.
 */
object BiliLoginApi {

    private const val LOGIN_UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val REFERER = "https://www.bilibili.com/"

    private val pool: ExecutorService by lazy { Executors.newFixedThreadPool(2) }

    /** QR scan status from poll(). */
    const val QR_PENDING = 0
    const val QR_SCANNED = 1
    const val QR_CONFIRMED = 2
    const val QR_EXPIRED = 3
    const val QR_FAILED = 4

    /** Profile of the logged-in account (from /x/web-interface/nav). */
    data class UserInfo(
        val uid: Long,
        val uname: String,
        val face: String,
        val coins: Double,
        val vipStatus: Int,
        val vipType: Int,
        val level: Int,
        val following: Int,
        val follower: Int
    )

    /** Async GET returning the parsed JSON object, or error text. */
    private fun getJson(url: String, cookie: String = "",
                        headerSink: ((Map<String, List<String>>) -> Unit)? = null,
                        callback: (JSONObject?, String?) -> Unit) {
        pool.execute {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", LOGIN_UA)
                    setRequestProperty("Referer", REFERER)
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
                }
                val code = connection.responseCode
                headerSink?.invoke(connection.headerFields)
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                callback(JSONObject(text), null)
            } catch (error: Exception) {
                callback(null, error.message ?: "网络错误")
            }
        }
    }

    /** Async POST form-encoded returning the parsed JSON object, or error text. */
    private fun postForm(url: String, fields: Map<String, String>, cookie: String = "",
                         callback: (JSONObject?, String?) -> Unit) {
        pool.execute {
            try {
                val body = fields.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", LOGIN_UA)
                    setRequestProperty("Referer", REFERER)
                    setRequestProperty("Origin", "https://www.bilibili.com")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
                }
                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                callback(JSONObject(text), null)
            } catch (error: Exception) {
                callback(null, error.message ?: "网络错误")
            }
        }
    }

    // ── QR code login ──────────────────────────────────────────────

    /** Requests a fresh QR login payload: (loginUrl, qrcodeKey) or error. */
    fun generateQrCode(callback: (String?, String?, String?) -> Unit) {
        getJson("https://passport.bilibili.com/x/passport-login/web/qrcode/generate") { root, error ->
            if (error != null || root == null) { callback(null, null, error ?: "网络错误"); return@getJson }
            val data = root.optJSONObject("data")
            callback(data?.optString("url"), data?.optString("qrcode_key"), null)
        }
    }

    /**
     * Polls the QR scan state.
     * On success the returned cookie string (SESSDATA etc.) is non-blank.
     */
    fun pollQrCode(key: String, callback: (state: Int, cookie: String?) -> Unit) {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" +
            URLEncoder.encode(key, "UTF-8")
        // Bilibili hands out the session cookies via Set-Cookie response headers
        // on a successful poll; capture them as the primary source (the data.url
        // cross-domain link can be empty for some accounts).
        val headerPairs = ArrayList<String>()
        getJson(url, headerSink = { headers ->
            // HttpURLConnection 的 Set-Cookie 键名大小写不统一，全扫一遍更稳。
            headers.forEach { (key, values) ->
                if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                    values.forEach { raw ->
                        val pair = raw.substringBefore(';').trim()
                        val name = pair.substringBefore('=')
                        val value = pair.substringAfter('=', "")
                        if (name in SESSION_NAMES && value.isNotBlank() &&
                            headerPairs.none { it.startsWith("$name=") }
                        ) {
                            headerPairs += "$name=$value"
                        }
                    }
                }
            }
        }) { root, error ->
            if (error != null || root == null) { callback(QR_FAILED, null); return@getJson }
            val data = root.optJSONObject("data") ?: run { callback(QR_FAILED, null); return@getJson }
            when (data.optInt("code", -1)) {
                0 -> {
                    val pairs = ArrayList<String>()
                    // 1) 响应头 Set-Cookie（主路径）
                    pairs += headerPairs
                    // 2) JSON body 里的 cookies 对象
                    val cookies = data.optJSONObject("cookies")
                    if (cookies != null) {
                        val names = cookies.keys()
                        while (names.hasNext()) {
                            val name = names.next()
                            val value = cookies.optString(name)
                            if (name in SESSION_NAMES && value.isNotBlank() &&
                                pairs.none { it.startsWith("$name=") }
                            ) {
                                pairs += "$name=$value"
                            }
                        }
                    }
                    // 3) 旧版 redirect url 查询参数
                    val redirect = data.optString("url")
                    if (redirect.isNotBlank()) {
                        redirect.substringAfter('?', "").split('&').forEach { param ->
                            val name = param.substringBefore('=')
                            val value = try {
                                java.net.URLDecoder.decode(param.substringAfter('=', ""), "UTF-8")
                            } catch (_: Exception) {
                                param.substringAfter('=', "")
                            }
                            if (name in SESSION_NAMES && value.isNotBlank() &&
                                pairs.none { it.startsWith("$name=") }
                            ) {
                                pairs += "$name=$value"
                            }
                        }
                    }
                    // 4) 部分账号会给 refresh_token，本身不能当 cookie，但至少保证 code=0 被识别
                    callback(QR_CONFIRMED, if (pairs.isEmpty()) null else pairs.joinToString("; "))
                }
                86101 -> callback(QR_PENDING, null)      // not scanned yet
                86090 -> callback(QR_SCANNED, null)      // scanned, waiting for confirm
                86038 -> callback(QR_EXPIRED, null)      // expired
                else -> callback(QR_PENDING, null)
            }
        }
    }

    // ── Profile ────────────────────────────────────────────────────

    /**
     * Fetches the logged-in account profile.
     *
     * Basic fields come from `/x/web-interface/nav` (uname/face/coins/vip/level).
     * Following / follower counts are no longer on that endpoint — they live on
     * `/x/web-interface/nav/stat` (with `/x/relation/stat?vmid=` as a fallback).
     */
    fun fetchProfile(cookie: String, callback: (UserInfo?, String?) -> Unit) {
        getJson("https://api.bilibili.com/x/web-interface/nav", cookie) { root, error ->
            if (error != null || root == null) { callback(null, error ?: "网络错误"); return@getJson }
            if (root.optInt("code") != 0) { callback(null, "未登录"); return@getJson }
            val data = root.optJSONObject("data") ?: run { callback(null, "数据异常"); return@getJson }
            if (data.optBoolean("isLogin", false) == false) { callback(null, "未登录"); return@getJson }
            val levelInfo = data.optJSONObject("level_info")
            // Keep legacy key names as last-resort defaults in case Bilibili
            // re-introduces them on nav later.
            val base = UserInfo(
                uid = data.optLong("mid", 0L),
                uname = data.optString("uname", "未知用户"),
                face = data.optString("face", ""),
                coins = data.optDouble("money", 0.0),
                vipStatus = data.optInt("vipStatus", 0),
                vipType = data.optInt("vipType", 0),
                level = levelInfo?.optInt("current_level", 0) ?: 0,
                following = data.optInt("following", data.optInt("mid_following", 0)),
                follower = data.optInt("follower", 0)
            )
            enrichRelationCounts(cookie, base, callback)
        }
    }

    /** Fills following/follower via nav/stat, then relation/stat as fallback. */
    private fun enrichRelationCounts(
        cookie: String,
        base: UserInfo,
        callback: (UserInfo?, String?) -> Unit
    ) {
        getJson("https://api.bilibili.com/x/web-interface/nav/stat", cookie) { statRoot, _ ->
            val stat = if (statRoot != null && statRoot.optInt("code") == 0) {
                statRoot.optJSONObject("data")
            } else null
            if (stat != null) {
                callback(
                    base.copy(
                        following = stat.optInt("following", base.following),
                        follower = stat.optInt("follower", base.follower)
                    ),
                    null
                )
                return@getJson
            }
            if (base.uid <= 0L) {
                callback(base, null)
                return@getJson
            }
            getJson(
                "https://api.bilibili.com/x/relation/stat?vmid=${base.uid}",
                cookie
            ) { relRoot, _ ->
                val rel = if (relRoot != null && relRoot.optInt("code") == 0) {
                    relRoot.optJSONObject("data")
                } else null
                if (rel != null) {
                    callback(
                        base.copy(
                            following = rel.optInt("following", base.following),
                            follower = rel.optInt("follower", base.follower)
                        ),
                        null
                    )
                } else {
                    // Profile itself is valid; counts just stay at whatever
                    // nav happened to provide (usually 0 after API change).
                    callback(base, null)
                }
            }
        }
    }

    private val SESSION_NAMES = setOf(
        "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid"
    )
}
