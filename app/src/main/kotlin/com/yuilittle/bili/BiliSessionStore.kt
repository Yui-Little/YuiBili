package com.yuilittle.bili

import android.content.Context

/**
 * Local Bilibili session holder.
 *
 * Cookies are only kept in the app's private preferences after the user
 * completes the official Bilibili login page. They are never logged or sent
 * anywhere except Bilibili endpoints used by this app.
 */
object BiliSessionStore {
    private const val PREFS = "bili_session"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_VIP_STATUS = "vip_status"
    private const val KEY_VIP_TYPE = "vip_type"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedCookie: String? = null

    @Volatile
    private var vipStatus = 0

    @Volatile
    private var vipType = 0

    /** Last successfully loaded profile card (process-local). Survives night-mode recreate. */
    @Volatile
    private var lastProfile: BiliLoginApi.UserInfo? = null

    @Volatile
    private var lastProfileAt: Long = 0L

    fun init(context: Context) {
        val application = context.applicationContext
        appContext = application
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (cachedCookie == null) {
            cachedCookie = prefs.getString(KEY_COOKIE, "").orEmpty()
        }
        if (vipStatus == 0 && vipType == 0) {
            vipStatus = prefs.getInt(KEY_VIP_STATUS, 0)
            vipType = prefs.getInt(KEY_VIP_TYPE, 0)
        }
    }

    fun cookie(): String {
        cachedCookie?.let { return it }
        val context = appContext ?: return ""
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE, "")
            .orEmpty()
        cachedCookie = value
        return value
    }

    fun isLoggedIn(): Boolean {
        return cookie().split(';')
            .asSequence()
            .map { it.trim() }
            .any { it.startsWith("SESSDATA=") && it.substringAfter('=', "").isNotBlank() }
    }

    /** True when the account has an active big-vip membership (vipType >= 1). */
    fun isBigVip(): Boolean = vipStatus == 1 && vipType >= 1


    fun cacheProfile(info: BiliLoginApi.UserInfo) {
        lastProfile = info
        lastProfileAt = System.currentTimeMillis()
        setVip(info.vipStatus, info.vipType)
    }

    fun cachedProfile(): BiliLoginApi.UserInfo? = lastProfile

    fun cachedProfileAt(): Long = lastProfileAt

    fun clearProfileCache() {
        lastProfile = null
        lastProfileAt = 0L
    }

    /** Cache the membership state fetched from the nav endpoint. */
    fun setVip(status: Int, type: Int) {
        vipStatus = status
        vipType = type
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VIP_STATUS, status)
            .putInt(KEY_VIP_TYPE, type)
            .apply()
    }

    /** Refresh the big-vip state from the nav endpoint (async, best effort). */
    fun refreshVipInfo() {
        val ck = cookie()
        if (ck.isBlank()) {
            setVip(0, 0)
            return
        }
        BiliLoginApi.fetchProfile(ck) { info, _ ->
            if (info != null) {
                setVip(info.vipStatus, info.vipType)
            } else if (!isLoggedIn()) {
                setVip(0, 0)
            }
        }
    }

    /** Save only the cookie pairs useful for a normal Bilibili session. */
    fun saveCookie(context: Context, rawCookie: String): Boolean {
        init(context)
        val normalized = normalize(rawCookie)
        if (normalized.isBlank()) return false
        cachedCookie = normalized
        appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COOKIE, normalized)
            .apply()
        val loggedIn = isLoggedIn()
        if (loggedIn) refreshVipInfo() else setVip(0, 0)
        return loggedIn
    }

    fun clear(context: Context) {
        clearProfileCache()
        init(context)
        cachedCookie = ""
        setVip(0, 0)
        appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_COOKIE)
            .apply()
    }

    private fun normalize(rawCookie: String): String {
        val allowed = setOf(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5",
            "sid", "buvid3", "buvid4", "b_nut", "b_lsid", "CURRENT_FNVAL",
            "CURRENT_QUALITY", "CURRENT_PID", "CURRENT_QUALITY"
        )
        val seen = HashSet<String>()
        return rawCookie.split(';')
            .asSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
            .filter { (name, value) -> name in allowed && value.isNotBlank() }
            .filter { seen.add(it.first) }
            .joinToString("; ") { (name, value) -> "$name=$value" }
    }
}
