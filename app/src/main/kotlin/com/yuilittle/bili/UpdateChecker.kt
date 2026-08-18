package com.yuilittle.bili

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live update check against the public GitHub repo.
 *
 * Design (keep it simple, no sticky tip):
 * 1. Each app open / return-to-foreground does one silent live fetch.
 * 2. Tip shows only when *this* fetch's versionCode > installed versionCode.
 * 3. If the developer rolls update.json back, the tip disappears on next fetch.
 * 4. Never write update state to SharedPreferences.
 *
 * Freshness:
 * - Prefer GitHub Contents API (returns the blob of the current main tip).
 * - Fallback: raw URL pinned to the latest main commit SHA (immutable path).
 * - Last resort: raw @ main (may lag up to a few minutes on some edges).
 * - jsDelivr is intentionally NOT used — it caches for hours and caused false tips.
 */
object UpdateChecker {

    private const val TAG = "YuiUpdate"
    private const val OWNER = "Yui-Little"
    private const val REPO = "YuiBili"
    private const val FILE = "update.json"
    private const val BRANCH = "main"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val checking = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var sessionRemote: UpdateInfo? = null
    @Volatile private var sessionChecked: Boolean = false
    @Volatile private var lastCheckAt: Long = 0L

    data class UpdateInfo(
        val latestVersionCode: Int,
        val latestVersionName: String,
        val minVersionCode: Int,
        val forceUpdate: Boolean,
        val apkUrl: String,
        val updateLog: String
    )

    data class LocalVersion(
        val versionCode: Int,
        val versionName: String
    )

    fun localVersion(context: Context): LocalVersion {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            LocalVersion(pi.versionCode, pi.versionName ?: "")
        } catch (_: Exception) {
            LocalVersion(0, "")
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        main.post {
            for (l in listeners) {
                try {
                    l()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun hasPendingUpdate(context: Context): Boolean {
        if (!sessionChecked) return false
        val info = sessionRemote ?: return false
        return isNewer(localVersion(context), info)
    }

    fun pendingUpdate(context: Context): UpdateInfo? {
        if (!hasPendingUpdate(context)) return null
        return sessionRemote
    }

    fun pendingVersionLabel(context: Context): String {
        val info = pendingUpdate(context) ?: return ""
        val name = info.latestVersionName.trim()
        return if (name.isNotEmpty()) name else info.latestVersionCode.toString()
    }

    fun isNewer(local: LocalVersion, info: UpdateInfo): Boolean {
        return local.versionCode < info.latestVersionCode
    }

    fun checkOnLaunch(context: Context) {
        refreshSilent(context, reason = "launch")
    }

    fun checkOnForeground(context: Context) {
        // Throttle: at most one live check per 5 minutes. Launch check already
        // happened, so quick background/foreground cycles stay silent.
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < 5 * 60_000L && sessionChecked) return
        refreshSilent(context, reason = "foreground")
    }

    /**
     * Always network. [done] gets the live result of *this* request.
     * UI should bind from the callback when present.
     */
    fun refreshSilent(
        context: Context,
        reason: String = "manual",
        done: ((UpdateInfo?, String?) -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        if (!checking.compareAndSet(false, true)) {
            // Coalesce: if a check is already running, attach is best-effort via
            // a short delayed re-entry rather than stacking parallel fetches.
            main.postDelayed({
                if (!checking.get()) {
                    refreshSilent(appCtx, reason = "$reason-retry", done = done)
                } else if (done != null) {
                    // Still busy — return current session snapshot so UI is not stuck.
                    done(sessionRemote, if (sessionChecked) null else "正在检查中")
                }
            }, 600L)
            return
        }
        lastCheckAt = System.currentTimeMillis()
        io.execute {
            var info: UpdateInfo? = null
            var error: String? = null
            try {
                info = fetchLiveConfig()
            } catch (e: Exception) {
                error = e.message ?: "网络错误"
                Log.w(TAG, "[$reason] fetch failed: $error")
            }
            main.post {
                checking.set(false)
                if (info != null) {
                    applyLiveResult(appCtx, info, reason)
                }
                done?.invoke(info, if (info == null) error else null)
            }
        }
    }

    private fun applyLiveResult(context: Context, info: UpdateInfo, reason: String) {
        val local = localVersion(context)
        val newer = isNewer(local, info)
        Log.i(
            TAG,
            "[$reason] remote=${info.latestVersionName}(${info.latestVersionCode}) " +
                "local=${local.versionName}(${local.versionCode}) newer=$newer"
        )
        // Always replace session with the live snapshot.
        // If remote was rolled back to <= local, hasPendingUpdate() becomes false.
        sessionRemote = info
        sessionChecked = true
        notifyListeners()
    }

    /**
     * 1) Contents API (current tip blob)
     * 2) raw pinned to latest commit SHA
     * 3) raw @ main
     */
    private fun fetchLiveConfig(): UpdateInfo {
        // Path 1: Contents API — decodes the file at the tip of main.
        try {
            val apiUrl =
                "https://api.github.com/repos/$OWNER/$REPO/contents/$FILE?ref=$BRANCH"
            val body = httpGet(apiUrl, accept = "application/vnd.github.raw+json")
            // With Accept: raw, GitHub returns the file body directly.
            if (body.trimStart().startsWith("{")) {
                val info = parse(body)
                Log.i(TAG, "source=contents-api-raw code=${info.latestVersionCode}")
                return info
            }
        } catch (e: Exception) {
            Log.w(TAG, "contents-api-raw failed: ${e.message}")
        }

        // Path 1b: Contents API JSON (base64) if raw accept is rejected.
        try {
            val apiUrl =
                "https://api.github.com/repos/$OWNER/$REPO/contents/$FILE?ref=$BRANCH"
            val body = httpGet(apiUrl, accept = "application/vnd.github+json")
            val root = JSONObject(body.trim().removePrefix("\uFEFF"))
            val encoding = root.optString("encoding", "")
            val content = root.optString("content", "")
            if (encoding == "base64" && content.isNotBlank()) {
                val decoded = String(
                    Base64.decode(content.replace("\n", ""), Base64.DEFAULT),
                    Charsets.UTF_8
                )
                val info = parse(decoded)
                Log.i(TAG, "source=contents-api-b64 code=${info.latestVersionCode}")
                return info
            }
        } catch (e: Exception) {
            Log.w(TAG, "contents-api-b64 failed: ${e.message}")
        }

        // Path 2: resolve main SHA, then raw at that immutable commit.
        try {
            val commitUrl = "https://api.github.com/repos/$OWNER/$REPO/commits/$BRANCH"
            val commitBody = httpGet(commitUrl, accept = "application/vnd.github+json")
            val sha = JSONObject(commitBody.trim().removePrefix("\uFEFF")).optString("sha", "")
            if (sha.length >= 7) {
                val rawPinned =
                    "https://raw.githubusercontent.com/$OWNER/$REPO/$sha/$FILE"
                val body = httpGet(rawPinned)
                val info = parse(body)
                Log.i(TAG, "source=raw-pinned@$sha code=${info.latestVersionCode}")
                return info
            }
        } catch (e: Exception) {
            Log.w(TAG, "raw-pinned failed: ${e.message}")
        }

        // Path 3: raw @ main (may lag on some CDN edges — last resort only).
        val rawMain =
            "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE"
        val body = httpGet(rawMain)
        val info = parse(body)
        Log.i(TAG, "source=raw-main code=${info.latestVersionCode}")
        return info
    }

    fun startSystemDownload(context: Context, info: UpdateInfo): Boolean {
        val url = info.apkUrl.trim()
        if (url.isEmpty()) {
            Toast.makeText(context, "更新地址尚未配置", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val name = info.latestVersionName.ifBlank { info.latestVersionCode.toString() }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val fileName = "YuiBili-$safe.apk"
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("YuiBili $name")
                setDescription("正在下载更新包…")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setMimeType("application/vnd.android.package-archive")
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            dm.enqueue(request)
            Toast.makeText(context, "已开始下载 $name，请在通知栏查看进度", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "无法启动系统下载：${e.message ?: "未知错误"}",
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    private fun httpGet(url: String, accept: String = "application/json,text/plain,*/*"): String {
        // Bust intermediate caches; GitHub APIs ignore unknown query params safely.
        val sep = if (url.contains("?")) "&" else "?"
        val busted = url + sep + "_=" + System.currentTimeMillis()
        HttpURLConnection.setFollowRedirects(true)
        val conn = (URL(busted).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            useCaches = false
            defaultUseCaches = false
            // GitHub requires a UA; spoof a normal client.
            setRequestProperty(
                "User-Agent",
                "YuiBili-UpdateChecker (Android; +https://github.com/$OWNER/$REPO)"
            )
            setRequestProperty("Accept", accept)
            setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
            setRequestProperty("Pragma", "no-cache")
            // GitHub API version pin (harmless on raw).
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${text.take(120)}")
            }
            if (text.isBlank()) throw IllegalStateException("空响应")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(raw: String): UpdateInfo {
        val cleaned = raw.trim().removePrefix("\uFEFF")
        val root = JSONObject(cleaned)
        val code = root.optInt("latestVersionCode", 0)
        if (code <= 0) throw IllegalStateException("latestVersionCode 无效: $code")
        return UpdateInfo(
            latestVersionCode = code,
            latestVersionName = root.optString("latestVersionName", ""),
            minVersionCode = root.optInt("minVersionCode", 1),
            forceUpdate = root.optBoolean("forceUpdate", false),
            apkUrl = root.optString("apkUrl", ""),
            updateLog = root.optString("updateLog", "有新版本可用")
        )
    }
}
