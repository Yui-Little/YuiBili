package com.yuilittle.bili

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地下载中心：任务队列 + 单worker串行下载 + 断点续传 + 持久化。
 *
 * 视频采用 B 站 DASH 流（fnval=4048）：视频轨(.m4s)与音频轨(.m4s)各自按
 * initialization/index 区间分三段下载并拼回原文件布局，随后生成指向本地文件的
 * MPD，由 Media3 的 DashMediaSource 直接本地播放（音画同步、可拖动）。
 * 纯音频任务只下音频轨；单文件流(durl)任务整体下载。封面作为每个视频任务的第一步。
 */
object DownloadManager {

    const val TYPE_VIDEO = 0
    const val TYPE_AUDIO = 1
    const val TYPE_COVER = 2
    const val TYPE_COMMENT = 3

    const val STATE_QUEUED = 0
    const val STATE_RUNNING = 1
    const val STATE_PAUSED = 2
    const val STATE_COMPLETED = 3
    const val STATE_FAILED = 4

    /** 正在“进行中”Tab 展示的状态集合（排队/下载/暂停/失败）。 */
    val ACTIVE_STATES = setOf(STATE_QUEUED, STATE_RUNNING, STATE_PAUSED, STATE_FAILED)

    class Task {
        var id: Long = 0L
        var type: Int = TYPE_VIDEO
        var bvid: String = ""
        var cid: Long = 0L
        var aid: Long = 0L
        var title: String = ""
        var owner: String = ""
        var coverUrl: String = ""
        var coverPath: String = ""
        /** 合集聚合元数据；空 groupId 表示旧任务/普通单视频。 */
        var groupId: String = ""
        var groupTitle: String = ""
        var groupCoverUrl: String = ""
        var episodeNo: Int = 0
        var quality: Int = 0
        var qualityLabel: String = ""
        var durationMs: Long = 0L
        var state: Int = STATE_QUEUED
        var bytesDone: Long = 0L
        var bytesTotal: Long = 0L
        var speedBytes: Long = 0L
        var error: String = ""
        var createdAt: Long = 0L
        var finishedAt: Long = 0L
        /** 断点：DASH 下已写好的媒体段字节数；单文件流下已写文件长度。 */
        var resumeOffset: Long = 0L
        /** DASH 任务：本地 MPD 路径（存在即走本地 DashMediaSource）。 */
        var mpdPath: String = ""
        /** 纯音频任务：本地音频文件路径。 */
        var audioPath: String = ""
        /** 单文件流(durl)任务：各分片本地路径（多于1个时用 ConcatenatingMediaSource）。 */
        var singlePaths: MutableList<String> = mutableListOf()
        /** 已导出到公共媒体库的文件（"相对目录|文件名"），删除任务时同步清理相册副本。 */
        var galleryExports: MutableList<String> = mutableListOf()
        /** 下载中置 true；暂停/删除时置 true 让 worker 快速退出。 */
        @Volatile var cancelRequested: Boolean = false
        /** 是否正在被 worker 执行。 */
        @Volatile var running: Boolean = false

        fun playable(): Boolean =
            (mpdPath.isNotBlank() && File(mpdPath).exists()) ||
                (audioPath.isNotBlank() && File(audioPath).exists()) ||
                singlePaths.any { File(it).exists() }
    }

    private var appContext: Context? = null
    @Volatile private var initialized = false

    /** 相册导出（mp4 合并/MediaStore 写入）专用线程，避免阻塞下载 worker。 */
    private val galleryIo = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "YuiBiliGalleryExport").apply { isDaemon = true }
    }

    private val lock = java.lang.Object()
    private val tasks = LinkedHashMap<Long, Task>()
    private val queue = ConcurrentLinkedQueue<Long>()
    private val listeners = CopyOnWriteArrayList<(List<Task>) -> Unit>()
    private val idSeq = AtomicLong(System.currentTimeMillis())
    private val workers = Array(3) { index ->
        Thread({ workerLoop() }.also { }, "YuiBiliDownloader-$index").apply { isDaemon = true }
    }
    private var workersStarted = false

    private var lastNotifyAt = 0L
    private var downloadDir: File? = null
    private var foregroundStarted = false
    private var notificationManager: NotificationManager? = null

    private const val NOTIFICATION_CHANNEL_ID = "yuibili_downloads"
    private const val NOTIFICATION_ID = 0x5942
    private const val ACTION_OPEN_DOWNLOADS = "com.yuilittle.bili.OPEN_DOWNLOADS"

    /** 下载目录（供 UI 统计占用、清理残留等）。 */
    fun downloadDir(): File? = downloadDir

    fun init(context: Context) {
        if (initialized) {
            updateDownloadNotification()
            return
        }
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            downloadDir = File(appContext!!.filesDir, "downloads").apply { mkdirs() }
            notificationManager = appContext!!.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createNotificationChannel()
            load()
            initialized = true
        }
        // 普通 Activity 进程不能调用 startForeground；通知由 DownloadManager 托管。
        // 真正的 Service 入口会在用户开始下载时启动并接管同一份队列。
        updateDownloadNotification()
        if (!workersStarted) {
            synchronized(lock) {
                if (!workersStarted) {
                    workers.forEach { it.start() }
                    workersStarted = true
                }
            }
        }
    }

    // ── 对外 API ────────────────────────────────────────────────

    fun addListener(listener: (List<Task>) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (List<Task>) -> Unit) { listeners.remove(listener) }

    fun snapshot(): List<Task> = synchronized(lock) { tasks.values.toList() }

    fun task(id: Long): Task? = synchronized(lock) { tasks[id] }

    fun enqueueVideo(
        context: Context,
        item: VideoItem,
        quality: Int,
        label: String,
        groupId: String = "",
        groupTitle: String = "",
        groupCoverUrl: String = "",
        episodeNo: Int = 0
    ) {
        init(context)
        if (item.charge) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_VIDEO && it.bvid == item.bvid && it.cid == item.cid &&
                    it.quality == quality && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_VIDEO
                bvid = item.bvid; cid = item.cid; aid = item.aid
                title = item.title; owner = item.owner; coverUrl = item.cover
                this.quality = quality; qualityLabel = label
                this.groupId = groupId; this.groupTitle = groupTitle
                this.groupCoverUrl = groupCoverUrl; this.episodeNo = episodeNo
                durationMs = item.duration * 1000L
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    fun enqueueAudio(
        context: Context,
        item: VideoItem,
        groupId: String = "",
        groupTitle: String = "",
        groupCoverUrl: String = "",
        episodeNo: Int = 0
    ) {
        init(context)
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_AUDIO && it.bvid == item.bvid && it.cid == item.cid && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_AUDIO
                bvid = item.bvid; cid = item.cid; aid = item.aid
                title = item.title; owner = item.owner; coverUrl = item.cover
                qualityLabel = "仅音频"
                this.groupId = groupId; this.groupTitle = groupTitle
                this.groupCoverUrl = groupCoverUrl; this.episodeNo = episodeNo
                durationMs = item.duration * 1000L
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    fun enqueueCover(
        context: Context,
        item: VideoItem,
        groupId: String = "",
        groupTitle: String = "",
        groupCoverUrl: String = "",
        episodeNo: Int = 0
    ) {
        init(context)
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_COVER && it.bvid == item.bvid && it.cid == item.cid && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_COVER
                bvid = item.bvid; cid = item.cid; aid = item.aid
                title = item.title + "（封面）"; owner = item.owner; coverUrl = item.cover
                qualityLabel = "封面"
                this.groupId = groupId; this.groupTitle = groupTitle
                this.groupCoverUrl = groupCoverUrl; this.episodeNo = episodeNo
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    /** 评论图片（评论区图片直链）下载任务。 */
    fun enqueueComment(context: Context, title: String, url: String, owner: String = "") {
        init(context)
        if (url.isBlank()) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_COMMENT && it.coverUrl == url && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_COMMENT
                this.title = title.ifBlank { "评论图片" }; this.owner = owner
                coverUrl = url
                qualityLabel = "图片"
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    /** 按直链下载封面图片（合集封面等非主视频封面场景）。 */
    fun enqueueCoverUrl(context: Context, title: String, url: String) {
        init(context)
        if (url.isBlank()) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_COVER && it.coverUrl == url && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_COVER
                this.title = title.ifBlank { "封面" }
                coverUrl = url
                qualityLabel = "封面"
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    /** 重命名任务标题（不改本地文件名，仅展示用）。 */
    fun rename(id: Long, title: String) {
        val t = task(id) ?: return
        val name = title.trim()
        if (name.isBlank()) return
        synchronized(t) { t.title = name }
        save()
        notifyListeners()
    }

    /** 重命名合集标题：该 groupId 下所有任务的 groupTitle 一起改（各自分集标题不动）。 */
    fun renameGroup(groupId: String, title: String) {
        if (groupId.isBlank()) return
        val name = title.trim()
        if (name.isBlank()) return
        synchronized(lock) {
            var changed = false
            tasks.values.filter { it.groupId == groupId }.forEach { t ->
                synchronized(t) { t.groupTitle = name }
                changed = true
            }
            if (changed) saveLocked()
        }
        notifyListeners()
    }

    /** 删除整个合集：该 groupId 下所有任务及其已下载文件。 */
    fun deleteGroup(groupId: String) {
        if (groupId.isBlank()) return
        val ids = synchronized(lock) {
            tasks.values.filter { it.groupId == groupId }.map { it.id }
        }
        ids.forEach { delete(it) }
    }

    fun pause(id: Long) {
        val t = task(id) ?: return
        synchronized(t) {
            if (t.state != STATE_RUNNING && t.state != STATE_QUEUED) return
            t.cancelRequested = true
            if (t.state == STATE_QUEUED) {
                t.state = STATE_PAUSED
            }
        }
        notifyListeners()
    }

    fun resume(id: Long) {
        val t = task(id) ?: return
        synchronized(t) {
            if (t.state != STATE_PAUSED) return
            t.state = STATE_QUEUED
            t.cancelRequested = false
            t.error = ""
        }
        notifyListeners()
        appContext?.let { startDownloadService(it) }
        kick()
    }

    fun retry(id: Long) {
        val t = task(id) ?: return
        synchronized(t) {
            if (t.state != STATE_FAILED) return
            t.state = STATE_QUEUED
            t.cancelRequested = false
            t.error = ""
        }
        notifyListeners()
        appContext?.let { startDownloadService(it) }
        kick()
    }

    fun delete(id: Long) {
        val t = task(id) ?: return
        synchronized(t) { t.cancelRequested = true }
        synchronized(lock) {
            tasks.remove(id)
            queue.remove(id)
            saveLocked()
        }
        val dir = downloadDir
        if (dir != null) {
            (listOf(t.mpdPath, t.audioPath) + t.singlePaths + t.coverPath)
                .filter { it.isNotBlank() }
                .forEach { path -> runCatching { File(path).delete() } }
            // 顺带清理该任务的任何残留文件
            runCatching { dir.listFiles()?.filter { it.name.startsWith("${t.id}_") }?.forEach { it.delete() } }
        }
        // 同步删除已导出到相册（Pictures/YuiBili、Movies/YuiBili）的公共副本
        deleteGalleryExports(t)
        notifyListeners()
        maybeGuideStoragePermission()
    }

    fun clearFinished() {
        synchronized(lock) {
            val ids = tasks.values.filter { it.state == STATE_COMPLETED }.map { it.id }
            val dir = downloadDir
            ids.forEach { id ->
                val t = tasks.remove(id)
                queue.remove(id)
                if (dir != null && t != null) {
                    (listOf(t.mpdPath, t.audioPath) + t.singlePaths + t.coverPath)
                        .filter { it.isNotBlank() }
                        .forEach { path -> runCatching { File(path).delete() } }
                    runCatching { dir.listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() } }
                    deleteGalleryExports(t)
                }
            }
            saveLocked()
        }
        notifyListeners()
    }

    // ── 内部：队列调度 ──────────────────────────────────────────

    private fun addTaskLocked(t: Task) {
        tasks[t.id] = t
        queue.add(t.id)
        saveLocked()
    }

    private fun kick() {
        synchronized(lock) {
            if (!workersStarted) {
                workers.forEach { it.start() }
                workersStarted = true
            }
            lock.notifyAll()
        }
    }

    private fun workerLoop() {
        while (true) {
            val next = nextTaskId()
            if (next == null) {
                synchronized(lock) {
                    try { lock.wait(1500L) } catch (_: InterruptedException) { }
                }
                continue
            }
            val t = task(next) ?: continue
            var skip = false
            synchronized(t) {
                if (t.state != STATE_QUEUED || t.running) {
                    skip = true
                } else {
                    t.state = STATE_RUNNING
                    t.running = true
                    t.cancelRequested = false
                }
            }
            if (skip) continue
            notifyListeners()
            try {
                execute(t)
            } catch (_: Throwable) {
            }
            synchronized(t) {
                t.running = false
                t.cancelRequested = false
            }
            notifyListeners()
        }
    }

    private fun nextTaskId(): Long? {
        for (id in queue) {
            val t = task(id) ?: continue
            if (t.state == STATE_QUEUED && !t.running) return id
        }
        return null
    }

    // ── 内部：执行 ──────────────────────────────────────────────

    private fun execute(t: Task) {
        val dir = downloadDir ?: run { fail(t, "存储不可用"); return }
        try {
            when (t.type) {
                TYPE_COVER -> runCover(t, dir)
                TYPE_AUDIO -> runAudio(t, dir)
                TYPE_COMMENT -> runComment(t, dir)
                else -> runVideo(t, dir)
            }
        } catch (c: CancelledException) {
            synchronized(t) {
                if (t.state == STATE_RUNNING) t.state = STATE_PAUSED
            }
            t.resumeOffset = mediaOffsetOf(t)
            t.cancelRequested = false
            save()
            notifyListeners()
        } catch (e: Exception) {
            fail(t, e.message?.takeIf { it.isNotBlank() } ?: "下载失败")
        }
    }

    private fun runCover(t: Task, dir: File) {
        checkNotCancelled(t)
        val url = t.coverUrl
        if (url.isBlank()) { fail(t, "封面地址为空"); return }
        val ext = url.substringAfterLast('.', "jpg").substringBefore('?').takeIf { it.length in 2..5 } ?: "jpg"
        val out = File(dir, "${t.id}_cover.$ext")
        t.coverPath = out.absolutePath
        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(url), out, t) { done ->
            t.bytesDone = done
            t.speedBytes = SpeedCounter.tick(t.bytesDone)
            if (t.bytesTotal > 0L) maybeNotify()
        }
        t.bytesDone = out.length()
        t.bytesTotal = out.length()
        complete(t)
    }

    private fun runComment(t: Task, dir: File) {
        checkNotCancelled(t)
        val url = t.coverUrl
        if (url.isBlank()) { fail(t, "图片地址为空"); return }
        val ext = url.substringAfterLast('.', "jpg").substringBefore('?').takeIf { it.length in 2..5 } ?: "jpg"
        val out = File(dir, "${t.id}_comment.$ext")
        t.coverPath = out.absolutePath
        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(url), out, t) { done ->
            t.bytesDone = done
            t.speedBytes = SpeedCounter.tick(t.bytesDone)
            if (t.bytesTotal > 0L) maybeNotify()
        }
        t.bytesDone = out.length()
        t.bytesTotal = out.length()
        complete(t)
    }

    private fun runAudio(t: Task, dir: File) {
        checkNotCancelled(t)
        t.qualityLabel = "仅音频"
        save(); notifyListeners()
        // 防下错：cid 缺失时 B 站播放接口会返回该 bvid 默认分集（主视频）的流，导致合集分集音频静默下错
        if (t.cid <= 0L && t.bvid.isNotBlank()) {
            fail(t, "音频任务缺少分集标识，请删除后重新添加")
            return
        }
        val stream = fetchStream(t) ?: return
        val audio = pickAudio(stream) ?: run { fail(t, "该视频没有可下载的音频流"); return }
        val out = File(dir, "${t.id}_audio.m4s")
        t.audioPath = out.absolutePath
        t.durationMs = stream.durationMs.coerceAtLeast(t.durationMs)
        // 整段下载（BiliTools 同款）：DASH baseUrl 直接 Range 下载即为完整 m4s，
        // 避免 init/media 分段拼接在部分 CDN 节点上出错
        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(audio.url) + audio.backups, out, t) { done ->
            t.bytesDone = done
            t.speedBytes = SpeedCounter.tick(t.bytesDone)
            maybeNotify()
        }
        t.bytesDone = out.length()
        t.bytesTotal = out.length()
        t.resumeOffset = 0L
        complete(t)
    }

    private fun runVideo(t: Task, dir: File) {
        checkNotCancelled(t)
        // 第一步：封面（失败不阻塞视频本体）
        if (t.coverPath.isBlank() && t.coverUrl.isNotBlank()) {
            runCatching {
                val ext = t.coverUrl.substringAfterLast('.', "jpg").substringBefore('?')
                    .takeIf { it.length in 2..5 } ?: "jpg"
                val cover = File(dir, "${t.id}_cover.$ext")
                downloadFullQuiet(t.coverUrl, cover)
                t.coverPath = cover.absolutePath
            }
        }
        checkNotCancelled(t)
        save(); notifyListeners()

        val stream = fetchStream(t) ?: return
        t.durationMs = stream.durationMs.coerceAtLeast(t.durationMs)

        if (!stream.isDash) {
            // 单文件流(durl)：逐段下载，已存在的分片从断点续传（网络中断/重试不再从头）
            val segments = stream.durlSegments.ifEmpty {
                listOf(BiliPlayUrl.DurlSegment(
                    stream.fallbackUrls.ifEmpty { listOf(stream.videoUrl) },
                    stream.durationMs, 0L
                ))
            }
            t.singlePaths.clear()
            var segIndex = 0
            var segDone = 0L
            for (segment in segments) {
                checkNotCancelled(t)
                val urls = segment.urls.filter { it.isNotBlank() }
                if (urls.isEmpty()) continue
                val segFile = File(dir, "${t.id}_seg${segIndex++}.mp4")
                t.singlePaths.add(segFile.absolutePath)
                t.bytesTotal = -1L
                save(); notifyListeners()
                downloadFullResume(urls, segFile, t, base = segDone) { done ->
                    // done 已含 base（前序分片字节），即累计已下载字节
                    t.bytesDone = done
                    t.speedBytes = SpeedCounter.tick(t.bytesDone)
                    maybeNotify()
                }
                segDone += segFile.length()
            }
            t.bytesTotal = t.singlePaths.sumOf { File(it).length() }
            t.bytesDone = t.bytesTotal
            t.resumeOffset = 0L
            complete(t)
            return
        }

        // DASH：视频轨 + 音频轨（选 <= 目标画质的最高档，AVC 优先）
        // 整段下载（BiliTools 同款）：baseUrl 直接 Range 下载即为完整可播放 m4s，
        // 不拆 init/index/media 分段，避免部分 CDN 节点 range 语义差异导致几 KB 假文件
        val video = pickVideo(stream, t.quality)
        val audio = pickAudio(stream)
        if (video == null) { fail(t, "没有可用的视频流"); return }

        val videoFile = File(dir, "${t.id}_video.m4s")
        val audioFile = File(dir, "${t.id}_audio.m4s")
        t.singlePaths.clear()

        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(video.url) + video.backups, videoFile, t) { done ->
            t.bytesDone = done
            t.speedBytes = SpeedCounter.tick(t.bytesDone)
            maybeNotify()
        }

        if (audio != null) {
            val videoDone = videoFile.length()
            downloadFullResume(listOf(audio.url) + audio.backups, audioFile, t, base = videoDone) { done ->
                // done 已含 base（视频轨字节），即累计已下载字节
                t.bytesDone = done
                t.speedBytes = SpeedCounter.tick(t.bytesDone)
                maybeNotify()
            }
        }

        checkNotCancelled(t)
        t.bytesDone = videoFile.length() + (if (audio != null) audioFile.length() else 0L)
        t.bytesTotal = t.bytesDone
        t.resumeOffset = 0L

        // 生成本地 MPD：BaseURL 指向本地文件，区间与原始布局一致
        val mpdFile = File(dir, "${t.id}.mpd")
        val localVideo = BiliPlayUrl.DashVideoTrack(
            id = video.id, url = Uri.fromFile(videoFile).toString(), backups = emptyList(),
            bandwidth = video.bandwidth, mimeType = video.mimeType, codecs = video.codecs,
            width = video.width, height = video.height, frameRate = video.frameRate,
            initializationRange = video.initializationRange, indexRange = video.indexRange
        )
        val localAudio = audio?.let {
            BiliPlayUrl.DashAudioTrack(
                id = it.id, url = Uri.fromFile(audioFile).toString(), backups = emptyList(),
                bandwidth = it.bandwidth, mimeType = it.mimeType, codecs = it.codecs,
                initializationRange = it.initializationRange, indexRange = it.indexRange
            )
        }
        val streamForMpd = BiliPlayUrl.DashStream(
            videoTracks = listOf(localVideo),
            audioTracks = listOfNotNull(localAudio),
            durationMs = t.durationMs.coerceAtLeast(1L),
            actualQuality = video.id,
            acceptedQualities = emptyList(),
            acceptedDescriptions = emptyList(),
            isDash = true
        )
        mpdFile.writeText(BiliDashManifest.build(streamForMpd))
        t.mpdPath = mpdFile.absolutePath
        t.qualityLabel = BiliPlayUrl.qualityLabel(video.id).replace("P", "p")
        complete(t)
    }

    // ── 内部：流获取与轨道选择 ─────────────────────────────────

    private fun fetchStream(t: Task): BiliPlayUrl.DashStream? {
        val latch = CountDownLatch(1)
        var result: BiliPlayUrl.DashStream? = null
        var err: String? = null
        val quality = if (t.type == TYPE_AUDIO) BiliPlayUrl.QUALITY_720 else t.quality
        BiliPlayUrl.fetchPlayUrl(t.bvid, t.cid, quality) { stream, error ->
            result = stream
            err = error
            latch.countDown()
        }
        val finished = try { latch.await(25L, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
        if (!finished) { fail(t, "获取视频流超时"); return null }
        if (result == null) { fail(t, err ?: "获取视频流失败"); return null }
        return result
    }

    private fun pickVideo(stream: BiliPlayUrl.DashStream, targetQn: Int): BiliPlayUrl.DashVideoTrack? {
        val candidates = stream.videoTracks.filter { it.id <= targetQn }.ifEmpty { stream.videoTracks }
        val bestQn = candidates.maxOfOrNull { it.id } ?: return null
        return candidates
            .filter { it.id == bestQn }
            .minWithOrNull(compareBy<BiliPlayUrl.DashVideoTrack> { codecRank(it.codecs) }
                .thenByDescending { it.bandwidth })
    }

    private fun pickAudio(stream: BiliPlayUrl.DashStream): BiliPlayUrl.DashAudioTrack? =
        stream.audioTracks
            .filter { it.codecs.startsWith("mp4a", true) }
            .maxByOrNull { it.bandwidth }
            ?: stream.audioTracks.maxByOrNull { it.bandwidth }

    private fun codecRank(codec: String): Int = when {
        codec.startsWith("avc", true) -> 0
        codec.startsWith("hev", true) || codec.startsWith("hvc", true) -> 1
        codec.startsWith("av01", true) -> 2
        else -> 3
    }

    // ── 内部：区间下载 ──────────────────────────────────────────

    private class RangeParts(val initStart: Long, val initEnd: Long, val indexStart: Long, val indexEnd: Long) {
        val mediaStart: Long get() = initEnd + 1
        val mediaEnd: Long get() = indexStart - 1
        val total: Long get() = indexEnd + 1
        val initLen: Long get() = initEnd - initStart + 1
        val indexLen: Long get() = indexEnd - indexStart + 1
        val mediaLen: Long get() = (mediaEnd - mediaStart + 1).coerceAtLeast(0L)
    }

    private fun rangeParts(initRange: String, indexRange: String): RangeParts {
        fun parse(range: String): Pair<Long, Long> {
            val s = range.substringBefore('-').trim().toLongOrNull() ?: 0L
            val e = range.substringAfter('-', "").trim().toLongOrNull() ?: s
            return s to e
        }
        val (is0, ie0) = parse(initRange)
        val (xs, xe) = parse(indexRange)
        // B 站 m4s：init 从 0 开始；若服务端给出的 init 起点不是 0，则整体按相对值处理
        val shift = is0
        return RangeParts(0L, ie0 - shift, xs - shift, xe - shift)
    }

    private class CancelledException : Exception()

    private fun checkNotCancelled(t: Task) {
        if (t.cancelRequested) throw CancelledException()
    }

    /** 按三段（init / media / index）顺序写入，天然还原原文件布局；媒体段支持断点续传。 */
    private fun downloadDashParts(
        url: String,
        backups: List<String>,
        parts: RangeParts,
        out: File,
        t: Task
    ) {
        val urls = (listOf(url) + backups).filter { it.isNotBlank() }
        if (urls.isEmpty()) { fail(t, "下载地址为空"); return }

        // 文件已完整（如上次中断在音频轨、视频轨已完成）：整段跳过，避免重写 init 截断损坏
        if (out.exists() && out.length() >= parts.total) {
            t.resumeOffset = parts.mediaLen
            t.bytesDone = (t.bytesDone).coerceAtLeast(parts.total)
            return
        }

        val mediaOffset = t.resumeOffset.coerceIn(0L, parts.mediaLen)
        // init + index 每次都重下（很小），媒体段从断点继续

        // init
        checkNotCancelled(t)
        writeRange(urls, parts.initStart, parts.initEnd, out, append = false, t) { done ->
            t.bytesDone = parts.initLen + parts.indexLen + mediaOffset + done
            t.speedBytes = SpeedCounter.tick(t.bytesDone)
            maybeNotify()
        }
        // media（断点续传）
        if (mediaOffset < parts.mediaLen) {
            checkNotCancelled(t)
            val start = parts.mediaStart + mediaOffset
            writeRange(urls, start, parts.mediaEnd, out, append = true, t) { done ->
                t.bytesDone = parts.initLen + parts.indexLen + mediaOffset + done
                t.speedBytes = SpeedCounter.tick(t.bytesDone)
                maybeNotify()
            }
            t.resumeOffset = parts.mediaLen
        }
        // index
        checkNotCancelled(t)
        writeRange(urls, parts.indexStart, parts.indexEnd, out, append = true, t) { done ->
            t.bytesDone = parts.initLen + parts.indexLen + parts.mediaLen + done
            t.speedBytes = SpeedCounter.tick(t.bytesDone)
            maybeNotify()
        }
    }

    private fun writeRange(
        urls: List<String>,
        start: Long,
        end: Long,
        out: File,
        append: Boolean,
        t: Task,
        onProgress: (Long) -> Unit
    ) {
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val connection = open(url, start, end)
                try {
                    val code = connection.responseCode
                    requireMediaStream(connection, code)
                    val stream = connection.inputStream
                    val fos = FileOutputStream(out, append)
                    try {
                        val expected = (end - start + 1).coerceAtLeast(0L)
                        var written = 0L
                        val buf = ByteArray(256 * 1024)
                        while (written < expected) {
                            checkNotCancelled(t)
                            val n = stream.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                fos.write(buf, 0, n)
                                written += n
                                if (written % (4 * 1024 * 1024) == 0L) onProgress(written)
                            }
                        }
                        fos.flush()
                        // 媒体段被截断（如 CDN 返回了错误页/半截流）时必须视为失败并切换备用节点
                        if (written < expected) {
                            throw IllegalStateException("响应被截断（$written/$expected 字节）")
                        }
                        onProgress(written)
                        return
                    } finally {
                        fos.close()
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (c: CancelledException) {
                throw c
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("无可用下载地址")
    }

    /** 整体下载（封面 / 无区间流的兜底 / 单文件流）。 */
    private fun downloadFull(url: String, out: File, t: Task) {
        downloadFullQuiet(url, out) { done ->
            t.bytesDone = done
            t.speedBytes = SpeedCounter.tick(t.bytesDone)
            if (t.bytesTotal > 0L) maybeNotify()
        }
        checkNotCancelled(t)
    }

    /**
     * 整体下载 + 断点续传：已存在的文件从 [length] 处继续（服务端支持 Range 则 206 追加，
     * 否则 200 从头覆盖）。onProgress 回调当前文件已写字节数（不含续传起点）。
     * 多个候选 URL 依次尝试，403/截断等异常自动切换下一节点。
     */
    /**
     * 断点续传前校验已有文件头：旧版本可能把 CDN 403 错误页（HTML，几 KB）写入
     * 了文件头。媒体文件以 ftyp（mp4/m4s）、JPEG/PNG/WebP/GIF 等 magic 开头，
     * 不是这些就视为坏文件删除，避免断点续传把坏头拼进成品。
     */
    private fun discardCorruptedPart(out: File) {
        if (!out.exists() || out.length() == 0L) return
        val magic = ByteArray(12)
        try {
            RandomAccessFile(out, "r").use { raf -> raf.readFully(magic) }
        } catch (_: Exception) {
            return
        }
        val b = { i: Int -> magic[i] }
        val isFtyp = magic.size >= 8 && b(4) == 'f'.code.toByte() && b(5) == 't'.code.toByte() &&
            b(6) == 'y'.code.toByte() && b(7) == 'p'.code.toByte()
        val isJpeg = magic.size >= 3 && (b(0).toInt() and 0xFF) == 0xFF && (b(1).toInt() and 0xFF) == 0xD8 &&
            (b(2).toInt() and 0xFF) == 0xFF
        val isPng = magic.size >= 8 && b(0) == 0x89.toByte() && b(1) == 'P'.code.toByte() &&
            b(2) == 'N'.code.toByte() && b(3) == 'G'.code.toByte()
        val isWebp = magic.size >= 12 && b(0) == 'R'.code.toByte() && b(1) == 'I'.code.toByte() &&
            b(2) == 'F'.code.toByte() && b(3) == 'F'.code.toByte() && b(8) == 'W'.code.toByte()
        val isGif = magic.size >= 6 && b(0) == 'G'.code.toByte() && b(1) == 'I'.code.toByte() &&
            b(2) == 'F'.code.toByte() && b(3) == '8'.code.toByte()
        if (!(isFtyp || isJpeg || isPng || isWebp || isGif)) {
            out.delete()
        }
    }

    private fun downloadFullResume(
        urls: List<String>,
        out: File,
        t: Task,
        /** 本次下载在任务总量中的起始偏移（多轨任务：视频轨完成后音频轨从 videoSize 起算）。 */
        base: Long = 0L,
        onProgress: ((Long) -> Unit)? = null
    ) {
        var lastError: Exception? = null
        for (u in urls.filter { it.isNotBlank() }) {
            try {
                discardCorruptedPart(out)
                val start = if (out.exists()) out.length() else 0L
                val connection = open(u, start, null)
                try {
                    val code = connection.responseCode
                    requireMediaStream(connection, code)
                    val append = code == 206 && start > 0L
                    val stream = connection.inputStream
                    val total = connection.contentLengthLong
                    if (total >= 0L) {
                        // 206 = 断点续传（总大小 = 已下载 + 本次内容）；200 = 从头下载（即完整大小）
                        val full = if (append) start + total else total
                        val absolute = base + full
                        if (absolute > 0L && absolute != t.bytesTotal) {
                            t.bytesTotal = absolute
                            save(); notifyListeners()
                        }
                    }
                    val fos = FileOutputStream(out, append)
                    try {
                        val buf = ByteArray(256 * 1024)
                        var written = 0L
                        while (true) {
                            checkNotCancelled(t)
                            val n = stream.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                fos.write(buf, 0, n)
                                written += n
                                // 进度回调传「base + 断点前字节 + 本次写入」= 累计已下载字节，
                                // 暂停/中断后恢复时进度从断点继续，而不是从 0 重新开始
                                // （200 覆盖从头下载时 written 本身就是累计，无需加 start）
                                onProgress?.invoke(if (append) base + start + written else base + written)
                            }
                        }
                        fos.flush()
                        // 服务端声明了长度但实际没有收满（错误页/半截流）→ 失败并换节点
                        if (total >= 0L && written < total) {
                            throw IllegalStateException("响应被截断（$written/$total 字节）")
                        }
                        return
                    } finally {
                        fos.close()
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (c: CancelledException) {
                throw c
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("无可用下载地址")
    }

    private fun downloadFullQuiet(url: String, out: File, onProgress: ((Long) -> Unit)? = null) {
        discardCorruptedPart(out)
        val connection = open(url, null, null)
        try {
            val code = connection.responseCode
            requireMediaStream(connection, code)
            val total = connection.contentLengthLong
            val input = connection.inputStream
            val fos = FileOutputStream(out)
            try {
                val buf = ByteArray(256 * 1024)
                var written = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        fos.write(buf, 0, n)
                        written += n
                        onProgress?.invoke(written)
                    }
                }
                fos.flush()
                if (total >= 0L && written < total) {
                    throw IllegalStateException("响应被截断（$written/$total 字节）")
                }
            } finally {
                fos.close()
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 校验媒体响应：非 2xx（403/416/503 等）或返回 HTML/JSON 错误页时直接抛异常，
     * 由上层切换到备用 CDN 节点。这是「下载到十几 KB 假完成」问题的关键防线。
     */
    private fun requireMediaStream(connection: HttpURLConnection, code: Int) {
        if (code !in 200..299) {
            throw IllegalStateException("媒体服务器响应异常（HTTP $code）")
        }
        val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
        if (type.contains("text/html") || type.contains("application/json") || type.contains("text/plain")) {
            throw IllegalStateException("媒体服务器返回了非媒体内容（HTTP $code）")
        }
    }

    private fun open(url: String, start: Long?, end: Long?): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", CDN_USER_AGENT)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Accept", "*/*")
            // CDN 需要与 playurl 一致的 PC 平台签名校验：带上 buvid3 + 登录 Cookie
            // （BiliTools 同款做法：全程 PC UA + 完整 Cookie，实测 CDN 200）
            val cdnCookie = buildString {
                val buvid = BiliPlayUrl.buvidCookie()
                val session = BiliSessionStore.cookie()
                if (buvid.isNotBlank()) append(buvid)
                if (session.isNotBlank()) {
                    if (isNotEmpty()) append("; ")
                    append(session)
                }
            }
            if (cdnCookie.isNotBlank()) setRequestProperty("Cookie", cdnCookie)
            if (start != null) {
                setRequestProperty("Range", if (end != null) "bytes=$start-$end" else "bytes=$start-")
            }
            instanceFollowRedirects = true
        }
        return connection
    }

    private object SpeedCounter {
        @Volatile var lastMark = 0L
        @Volatile var lastBytes = 0L
        @Volatile var speed = 0L
        @Synchronized fun tick(bytes: Long): Long {
            val now = System.currentTimeMillis()
            if (lastMark == 0L) { lastMark = now; lastBytes = bytes; speed = 0L; return speed }
            val dt = (now - lastMark).coerceAtLeast(1L)
            if (dt >= 1000L) {
                speed = ((bytes - lastBytes) * 1000L / dt).coerceAtLeast(0L)
                lastMark = now
                lastBytes = bytes
            }
            return speed
        }
        @Synchronized fun reset() { lastMark = 0L; lastBytes = 0L; speed = 0L }
    }

    private fun mediaOffsetOf(t: Task): Long = t.resumeOffset

    // ── 内部：状态落库 ──────────────────────────────────────────

    private fun complete(t: Task) {
        synchronized(t) {
            if (t.state == STATE_RUNNING) {
                t.state = STATE_COMPLETED
                t.finishedAt = System.currentTimeMillis()
                t.error = ""
                t.resumeOffset = 0L
            }
        }
        SpeedCounter.reset()
        save()
        notifyListeners()
        // 完成后再把产物导出到公共媒体库，让系统相册/文件管理器可见。
        // 私有目录(Android/data/...)不会被媒体扫描，导出失败不影响下载结果。
        // 异步执行：mp4 合并是重 IO，阻塞下载 worker 会让后续任务看起来「卡住」。
        galleryIo.execute { exportToGallery(t) }
    }

    private fun fail(t: Task, message: String) {
        synchronized(t) {
            if (t.state == STATE_RUNNING) {
                t.state = STATE_FAILED
                t.error = message
                t.finishedAt = System.currentTimeMillis()
            }
        }
        SpeedCounter.reset()
        save()
        notifyListeners()
    }

    // ─────────────────────────────────────────────────
    //  相册导出：下载产物复制到公共媒体库（Pictures/YuiBili、Movies/YuiBili），
    //  让系统相册 / 文件管理器能看到。应用私有目录不会被媒体扫描。
    //  Android 10+ 走 MediaStore（免权限）；旧系统回退公共目录 + MediaScanner。
    //  任何失败都静默跳过，绝不影响下载主流程。
    // ─────────────────────────────────────────────────

    private fun exportToGallery(t: Task) {
        val ctx = appContext ?: return
        if (t.state != STATE_COMPLETED) return
        // 任务已被删除时不再导出，避免删除后相册里又冒出副本
        if (synchronized(lock) { !tasks.containsKey(t.id) }) return
        // 封面导出：只由独立封面任务(TYPE_COVER)负责，且仅在用户开启「封面图片」时才会创建；
        // 视频/音频任务内部的封面(coverPath)仅用于列表缩略图，不导出到相册，
        // 避免用户没开封面开关时相册里也自动出现一张封面。
        if (t.type == TYPE_COVER && t.coverPath.isNotBlank()) {
            val cover = File(t.coverPath)
            if (cover.exists() && cover.length() > 0L) {
                exportToMediaStore(ctx, cover, "Pictures/YuiBili", t.title, false)
                recordGalleryExport(t, "Pictures/YuiBili", t.title, cover.extension)
            }
        }
        if (t.type == TYPE_AUDIO || t.type == TYPE_COVER || t.type == TYPE_COMMENT) return
        // 视频 → 相册视频
        if (t.mpdPath.isNotBlank()) {
            // DASH：视频轨(.m4s)+音频轨(.m4s) 合并为有声 mp4 再导出
            val dir = downloadDir ?: return
            val v = File(dir, "${t.id}_video.m4s")
            val a = File(dir, "${t.id}_audio.m4s")
            if (v.exists() && v.length() > 0L) {
                val merged = File(dir, "${t.id}_merged.mp4")
                if (mergeDashToMp4(v, if (a.exists() && a.length() > 0L) a else null, merged) &&
                    merged.exists() && merged.length() > 0L) {
                    // 合并耗时较长，期间任务可能已被删除：不再导出，避免相册残留副本
                    if (synchronized(lock) { !tasks.containsKey(t.id) }) {
                        runCatching { merged.delete() }
                        return
                    }
                    exportToMediaStore(ctx, merged, "Movies/YuiBili", t.title, true)
                    recordGalleryExport(t, "Movies/YuiBili", t.title, "mp4")
                    runCatching { merged.delete() }
                }
            }
        } else {
            // 单文件 / 分段流：整段 mp4 直接导出
            val single = t.singlePaths.firstOrNull { it.endsWith(".mp4") && File(it).exists() }
            if (single != null) {
                if (synchronized(lock) { !tasks.containsKey(t.id) }) return
                exportToMediaStore(ctx, File(single), "Movies/YuiBili", t.title, true)
                recordGalleryExport(t, "Movies/YuiBili", t.title, "mp4")
            }
        }
    }

    /** 记录一次相册导出，删除任务时据此清理公共媒体库副本。 */
    private fun recordGalleryExport(t: Task, relativeDir: String, title: String, ext: String) {
        val display = sanitizeFileName(title.ifBlank { "video" }) + ".${ext.lowercase(Locale.ROOT)}"
        synchronized(t) {
            val entry = "$relativeDir|$display"
            if (entry !in t.galleryExports) t.galleryExports.add(entry)
        }
        save()
    }

    /** 把本地文件写入公共媒体库；文件名按任务标题命名，重名时系统自动加序号。 */
    private fun exportToMediaStore(ctx: Context, src: File, relativeDir: String, title: String, isVideo: Boolean) {
        runCatching {
            if (!src.exists() || src.length() == 0L) return
            val ext = src.extension.lowercase(Locale.ROOT).ifBlank { if (isVideo) "mp4" else "jpg" }
            val mime = if (isVideo) {
                "video/mp4"
            } else {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
            }
            val display = sanitizeFileName(title.ifBlank { src.nameWithoutExtension }) + ".$ext"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                 else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, display)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(collection, values) ?: return
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            } else {
                val pubDir = Environment.getExternalStoragePublicDirectory(
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                val target = File(File(pubDir, "YuiBili"), display).apply {
                    parentFile?.mkdirs()
                }
                src.inputStream().use { i -> target.outputStream().use { o -> i.copyTo(o) } }
                android.media.MediaScannerConnection.scanFile(
                    ctx, arrayOf(target.absolutePath), arrayOf(mime), null)
            }
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "video" }

    /** 删除任务时同步清理已导出到公共媒体库/相册的副本（含重名时系统自动加序号的变体）。 */
    private fun deleteGalleryExports(t: Task) {
        val ctx = appContext ?: return
        val entries = synchronized(t) { t.galleryExports.toList() }
        entries.forEach { entry ->
            val sep = entry.indexOf('|')
            if (sep <= 0) return@forEach
            deleteMediaEntry(ctx, entry.substring(0, sep), entry.substring(sep + 1))
        }
        // 兜底：旧版本任务没有导出记录，按当前标题尽力清理（含 " (1)" 序号变体）
        if (entries.isEmpty()) {
            val base = sanitizeFileName(t.title.ifBlank { "video" })
            if (t.coverPath.isNotBlank()) {
                val ext = t.coverPath.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
                    .takeIf { it.length in 2..5 } ?: "jpg"
                deleteMediaEntry(ctx, "Pictures/YuiBili", "$base.$ext")
            }
            if (t.type == TYPE_VIDEO) {
                deleteMediaEntry(ctx, "Movies/YuiBili", "$base.mp4")
            }
        }
    }

    /** 按相对目录 + 文件名删除公共媒体库条目（MediaStore）或旧系统公共目录文件。 */
    private fun deleteMediaEntry(ctx: Context, relativeDir: String, display: String) {
        runCatching {
            val isVideo = relativeDir.startsWith("Movies")
            val base = display.substringBeforeLast('.', display)
            val ext = display.substringAfterLast('.', "")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                 else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                // 转义 LIKE 通配符，只匹配「原名」与「原名 (n).ext」两种形态
                val escapedBase = base.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                // RELATIVE_PATH 在不同系统上可能带/不带尾部斜杠，两种都匹配
                val sel = "(${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?) AND (" +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? OR " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\')"
                val args = arrayOf("$relativeDir/", relativeDir, display, "$escapedBase (%.$ext")
                val ids = ArrayList<Long>()
                ctx.contentResolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID), sel, args, null
                )?.use { c ->
                    while (c.moveToNext()) ids.add(c.getLong(0))
                }
                ids.forEach { id ->
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    // 不带 recycle 参数直接删条目（部分 ROM 不识别 recycle token 会抛异常，runCatching 兜住）
                    runCatching { ctx.contentResolver.delete(uri, null, null) }
                }
            } else {
                // 旧系统：公共目录直接删文件
                val pubDir = Environment.getExternalStoragePublicDirectory(
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                val parent = File(pubDir, "YuiBili")
                parent.listFiles()?.filter {
                    it.name.startsWith(base + " (") && it.name.endsWith(".$ext")
                }?.forEach { runCatching { it.delete() } }
                File(File(pubDir, "YuiBili"), display).delete()
            }
        }
        // 物理删除（含系统回收站 .trashed-* 变体）：仅在已授予「所有文件访问」权限时执行。
        // 部分国产 ROM 的 MediaStore 删除只删条目、把物理文件改名 .trashed-<时间戳>-<原名> 留在原地，
        // 没有该权限 App 无法触碰公共目录，删除后会残留可见文件。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            runCatching {
                val pubDir = Environment.getExternalStoragePublicDirectory(
                    if (relativeDir.startsWith("Movies")) Environment.DIRECTORY_MOVIES
                    else Environment.DIRECTORY_PICTURES)
                val parent = File(pubDir, "YuiBili")
                val base = display.substringBeforeLast('.', display)
                val ext = display.substringAfterLast('.', "")
                parent.listFiles()?.forEach { f ->
                    val n = f.name
                    // 去掉系统回收站前缀 .trashed-<时间戳>-
                    val stripped = n.removePrefix(".trashed-")
                        .substringAfter('-', n.removePrefix(".trashed-"))
                    val clean = if (n.startsWith(".trashed-")) stripped else n
                    if (clean == display || clean == "$base.$ext" ||
                        clean.startsWith("$base ") || clean.startsWith("$base（封面")) {
                        runCatching { f.delete() }
                    }
                }
            }
        }
    }

    /** 删除后检查：若相册副本无法彻底删除（未授予所有文件访问权限），引导一次去授权。 */
    private fun maybeGuideStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ctx = appContext ?: return
        if (Environment.isExternalStorageManager()) return
        val prefs = ctx.getSharedPreferences("yuibili_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("storage_permission_guided", false)) return
        prefs.edit().putBoolean("storage_permission_guided", true).apply()
        runCatching {
            android.widget.Toast.makeText(
                ctx, "未授予「所有文件访问」权限，相册副本将无法彻底删除", android.widget.Toast.LENGTH_LONG
            ).show()
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
    }

    /** 用 MediaMuxer 把 DASH 的视频轨/音频轨混流成单个有声 mp4（B 站整段 m4s 是完整 MP4）。 */
    private fun mergeDashToMp4(video: File, audio: File?, out: File): Boolean {
        var extractorV: MediaExtractor? = null
        var extractorA: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            val ev = MediaExtractor().apply { setDataSource(video.absolutePath) }
            extractorV = ev
            val vi = (0 until ev.trackCount).firstOrNull {
                ev.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            ev.selectTrack(vi)
            val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val mv = mux.addTrack(ev.getTrackFormat(vi))
            var ea: MediaExtractor? = null
            var ma = -1
            if (audio != null) {
                runCatching {
                    val e = MediaExtractor().apply { setDataSource(audio.absolutePath) }
                    val ai = (0 until e.trackCount).firstOrNull {
                        e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                    if (ai != null) {
                        e.selectTrack(ai)
                        ma = mux.addTrack(e.getTrackFormat(ai))
                        ea = e
                        extractorA = e
                    }
                }
            }
            mux.start()
            val vBuf = java.nio.ByteBuffer.allocate(2 * 1024 * 1024)
            val aBuf = java.nio.ByteBuffer.allocate(2 * 1024 * 1024)
            val vInfo = MediaCodec.BufferInfo()
            val aInfo = MediaCodec.BufferInfo()
            var vPending = true
            var aPending = ea != null
            while (vPending || aPending) {
                if (vPending && (!aPending || ev.sampleTime <= ea!!.sampleTime)) {
                    vInfo.offset = 0
                    vInfo.size = ev.readSampleData(vBuf, 0)
                    if (vInfo.size < 0) { vPending = false } else {
                        vInfo.presentationTimeUs = ev.sampleTime
                        vInfo.flags = ev.sampleFlags
                        mux.writeSampleData(mv, vBuf, vInfo)
                        ev.advance()
                    }
                } else if (aPending) {
                    aInfo.offset = 0
                    aInfo.size = ea!!.readSampleData(aBuf, 0)
                    if (aInfo.size < 0) { aPending = false } else {
                        aInfo.presentationTimeUs = ea!!.sampleTime
                        aInfo.flags = ea!!.sampleFlags
                        mux.writeSampleData(ma, aBuf, aInfo)
                        ea!!.advance()
                    }
                }
            }
            mux.stop()
            ev.release(); ea?.release(); mux.release()
            true
        } catch (_: Throwable) {
            runCatching { muxer?.stop() }
            runCatching { extractorV?.release() }
            runCatching { extractorA?.release() }
            runCatching { muxer?.release() }
            runCatching { out.delete() }
            false
        }
    }

    private fun maybeNotify() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt >= 250L) {
            lastNotifyAt = now
            notifyListeners()
        }
    }

    private fun notifyListeners() {
        val snapshot = snapshot()
        updateDownloadNotification()
        listeners.forEach { it(snapshot) }
    }

    // ── 后台通知/保活 ─────────────────────────────────────────

    private fun startDownloadService(context: Context) {
        val intent = Intent(context, DownloadService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Throwable) {
            // 通知权限/厂商后台策略不允许时，队列仍由进程内 worker 继续执行。
        }
    }

    /** 在独立前台服务中托管同一 DownloadManager 单例，避免 Activity 退出后进程被回收。 */
    class DownloadService : Service() {
        override fun onCreate() {
            super.onCreate()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL_ID, "下载任务", NotificationManager.IMPORTANCE_LOW))
            }
            val notification = DownloadManager.emptyServiceNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            DownloadManager.init(this)
            updateDownloadNotification()
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DownloadManager.init(this)
            return START_STICKY
        }

        override fun onBind(intent: Intent?) = null
    }

    private fun emptyServiceNotification(context: Context): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        return builder.setSmallIcon(com.yuilittle.bili.R.drawable.ic_launcher)
            .setContentTitle("YuiBili 下载")
            .setContentText("下载服务已启动")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "下载任务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "YuiBili 后台下载进度"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notificationIntent(): PendingIntent? {
        val ctx = appContext ?: return null
        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = ACTION_OPEN_DOWNLOADS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
    }

    private fun activeDownloadTasks(): List<Task> = synchronized(lock) {
        tasks.values.filter { it.type == TYPE_VIDEO || it.type == TYPE_AUDIO }
            .filter { it.state == STATE_RUNNING || it.state == STATE_QUEUED || it.state == STATE_PAUSED }
    }

    private fun buildDownloadNotification(): Notification? {
        val ctx = appContext ?: return null
        val active = activeDownloadTasks()
        val running = active.filter { it.state == STATE_RUNNING }
        if (active.isEmpty()) return null
        val totalDone = active.sumOf { it.bytesDone.coerceAtLeast(0L) }
        val total = active.sumOf { it.bytesTotal.takeIf { b -> b > 0L } ?: 0L }
        val title = when {
            running.size == 1 -> "正在下载：${running.first().title.ifBlank { "视频" }}"
            running.isNotEmpty() -> "正在下载 ${running.size} 个视频"
            else -> "下载任务等待中"
        }
        val text = when {
            total > 0L -> "${formatBytesForNotification(totalDone)} / ${formatBytesForNotification(total)} · 最多同时 3 个"
            else -> "${active.size} 个任务 · 最多同时 3 个"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(ctx)
        }
        builder.setSmallIcon(com.yuilittle.bili.R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(notificationIntent())
            .setOngoing(running.isNotEmpty())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (total > 0L) {
            builder.setProgress(100, ((totalDone * 100L) / total).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun startForegroundKeepAlive() {
        val ctx = appContext ?: return
        if (foregroundStarted) return
        val notification = buildDownloadNotification() ?: return
        if (ctx is Service) {
            ctx.startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } else {
            notificationManager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateDownloadNotification() {
        val active = activeDownloadTasks()
        val manager = notificationManager ?: return
        if (active.isEmpty()) {
            if (foregroundStarted && appContext is Service) {
                (appContext as Service).stopForeground(Service.STOP_FOREGROUND_DETACH)
                foregroundStarted = false
            }
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val notification = buildDownloadNotification() ?: return
        if (appContext is Service) {
            if (!foregroundStarted) {
                (appContext as Service).startForeground(NOTIFICATION_ID, notification)
                foregroundStarted = true
            } else {
                (appContext as Service).startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun formatBytesForNotification(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fGB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024f / 1024f)
        bytes >= 1024L -> String.format(Locale.US, "%.0fKB", bytes / 1024f)
        else -> "${bytes}B"
    }


    private fun save() {
        synchronized(lock) { saveLocked() }
    }

    private fun saveLocked() {
        val dir = downloadDir ?: return
        val file = File(dir, "tasks.json")
        val arr = JSONArray()
        tasks.values.forEach { t -> arr.put(toJson(t)) }
        runCatching { file.writeText(JSONObject().put("tasks", arr).toString()) }
    }

    private fun load() {
        val dir = downloadDir ?: return
        val file = File(dir, "tasks.json")
        if (!file.exists()) return
        runCatching {
            val arr = JSONObject(file.readText()).optJSONArray("tasks") ?: return
            for (i in 0 until arr.length()) {
                val t = fromJson(arr.getJSONObject(i))
                tasks[t.id] = t
                if (t.state == STATE_RUNNING || t.state == STATE_QUEUED) {
                    // 进程重启后未完成任务回到排队/暂停，断点保留
                    t.state = if (t.cancelRequested || t.resumeOffset > 0L) STATE_PAUSED else STATE_QUEUED
                    t.running = false
                    t.cancelRequested = false
                    if (t.state == STATE_QUEUED) queue.add(t.id)
                }
            }
        }
    }

    private fun toJson(t: Task): JSONObject = JSONObject().apply {
        put("id", t.id); put("type", t.type)
        put("bvid", t.bvid); put("cid", t.cid); put("aid", t.aid)
        put("title", t.title); put("owner", t.owner)
        put("coverUrl", t.coverUrl); put("coverPath", t.coverPath)
        put("groupId", t.groupId); put("groupTitle", t.groupTitle)
        put("groupCoverUrl", t.groupCoverUrl); put("episodeNo", t.episodeNo)
        put("quality", t.quality); put("qualityLabel", t.qualityLabel)
        put("durationMs", t.durationMs)
        put("state", t.state)
        put("bytesDone", t.bytesDone); put("bytesTotal", t.bytesTotal)
        put("error", t.error)
        put("createdAt", t.createdAt); put("finishedAt", t.finishedAt)
        put("resumeOffset", t.resumeOffset)
        put("mpdPath", t.mpdPath); put("audioPath", t.audioPath)
        put("singlePaths", JSONArray(t.singlePaths))
        put("galleryExports", JSONArray(t.galleryExports))
    }

    private fun fromJson(o: JSONObject): Task = Task().apply {
        id = o.optLong("id"); type = o.optInt("type")
        bvid = o.optString("bvid"); cid = o.optLong("cid"); aid = o.optLong("aid")
        title = o.optString("title"); owner = o.optString("owner")
        coverUrl = o.optString("coverUrl"); coverPath = o.optString("coverPath")
        groupId = o.optString("groupId"); groupTitle = o.optString("groupTitle")
        groupCoverUrl = o.optString("groupCoverUrl"); episodeNo = o.optInt("episodeNo")
        quality = o.optInt("quality"); qualityLabel = o.optString("qualityLabel")
        durationMs = o.optLong("durationMs")
        state = o.optInt("state")
        bytesDone = o.optLong("bytesDone"); bytesTotal = o.optLong("bytesTotal")
        error = o.optString("error")
        createdAt = o.optLong("createdAt"); finishedAt = o.optLong("finishedAt")
        resumeOffset = o.optLong("resumeOffset")
        mpdPath = o.optString("mpdPath"); audioPath = o.optString("audioPath")
        val paths = o.optJSONArray("singlePaths")
        if (paths != null) for (i in 0 until paths.length()) singlePaths.add(paths.optString(i))
        val gExports = o.optJSONArray("galleryExports")
        if (gExports != null) for (i in 0 until gExports.length()) galleryExports.add(gExports.optString(i))
    }
}
