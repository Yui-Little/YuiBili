package com.yuilittle.bili

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Movie
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Bounded cover pipeline tuned for the two-column feed.
 *
 * - Requests a CDN-sized 480 x 270 WebP instead of the original archive image.
 * - Reuses decoded bitmaps through a bounded memory LRU.
 * - Reuses files across app launches through a bounded 24 MiB disk LRU.
 * - Coalesces duplicate requests and uses only three worker threads.
 * - Uses ImageView tags so a recycled card never receives an old response.
 */
object CoverLoader {
    private const val MAX_DISK_BYTES = 48L * 1024L * 1024L
    /** Feed/comment thumbs stay small; fullscreen originals use a higher ceiling. */
    private const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024
    private const val MAX_ORIGINAL_DOWNLOAD_BYTES = 16 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 7_000
    private const val READ_TIMEOUT_MS = 9_000
    private const val ORIGINAL_READ_TIMEOUT_MS = 18_000
    private const val MAX_COVER_QUEUE = 48
    private const val THUMBNAIL_SUFFIX = "@480w_270h_1c.webp"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workers = ThreadPoolExecutor(
        3,
        3,
        20L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(MAX_COVER_QUEUE),
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    private val CDN_COVER_HOSTS = arrayOf("hdslb.com", "biliimg.com", "hdslb.net")

    private val lock = Any()
    private val diskLock = Any()
    private var knownDiskBytes = -1L
    private val inFlight = HashMap<String, MutableList<WeakReference<ImageView>>>()
    private val memoryBytes = minOf(
        20L * 1024L * 1024L,
        (Runtime.getRuntime().maxMemory() / 8L).coerceAtLeast(4L * 1024L * 1024L)
    ).toInt()
    private val memory = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    /** Bounded decoded-GIF cache (native Movie objects can be heavy). */
    private val gifMemory = object : LruCache<String, Any>(24) {}

    /** True when the URL points at an animated GIF (CDN params stripped). */
    private fun isGifUrl(value: String): Boolean =
        value.substringBefore('@').substringBefore('?').endsWith(".gif", ignoreCase = true)

    /** Plays an animated GIF and resumes when its ImageView becomes visible again. */
    private class GifDrawable(private val movie: Movie) : Drawable() {
        private var playTimeMs = 0L
        private var lastTickAt = SystemClock.uptimeMillis()
        private var playing = false
        private var lastFrame = -1
        /** When false, only the first/current frame is drawn (no invalidate loop). */
        private var playbackEnabled = true
        private val visibleRect = Rect()
        private var watchedView: View? = null
        private var watchedObserver: android.view.ViewTreeObserver? = null

        fun setPlaybackEnabled(enabled: Boolean) {
            if (playbackEnabled == enabled) return
            playbackEnabled = enabled
            if (!enabled) {
                pauseAt(SystemClock.uptimeMillis())
            } else {
                // Kick one frame so the host resumes the loop.
                lastTickAt = SystemClock.uptimeMillis()
                playing = false
                invalidateSelf()
            }
        }

        /**
         * A ScrollView does not change the child ImageView's visibility while
         * scrolling. Therefore draw() alone cannot restart an animation after
         * it stopped invalidating off-screen. Listen to tree scroll changes and
         * request exactly one frame when the image re-enters the viewport.
         */
        private val scrollListener = android.view.ViewTreeObserver.OnScrollChangedListener {
            val host = watchedView ?: return@OnScrollChangedListener
            if (host is ImageView && host.drawable !== this) {
                stopWatching(host)
            } else {
                updateVisibility(host, SystemClock.uptimeMillis(), requestFrame = true)
            }
        }
        private val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                registerScrollObserver(v)
                updateVisibility(v, SystemClock.uptimeMillis(), requestFrame = true)
            }

            override fun onViewDetachedFromWindow(v: View) {
                pauseAt(SystemClock.uptimeMillis())
                unregisterScrollObserver()
            }
        }

        private fun isActuallyVisible(host: View): Boolean {
            if (!playbackEnabled) return false
            // INVISIBLE (used by the image-viewer neighbour slots) must pause
            // even though the view still has a non-empty layout rect.
            if (host.visibility != View.VISIBLE) return false
            if (!host.isShown || host.windowVisibility != View.VISIBLE) return false
            return host.getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty
        }

        private fun pauseAt(now: Long) {
            if (playing) {
                playTimeMs += (now - lastTickAt).coerceAtLeast(0L)
                playing = false
            }
        }

        private fun updateVisibility(host: View, now: Long, requestFrame: Boolean) {
            if (isActuallyVisible(host)) {
                if (!playing) {
                    playing = true
                    lastTickAt = now
                    // Always request a frame when resuming; off-screen GIFs that
                    // were preloaded in a pager slot otherwise stay frozen.
                    invalidateSelf()
                } else if (requestFrame) {
                    invalidateSelf()
                }
            } else {
                pauseAt(now)
            }
        }

        private fun registerScrollObserver(host: View) {
            val observer = host.viewTreeObserver
            if (watchedObserver === observer && observer.isAlive) return
            unregisterScrollObserver()
            if (observer.isAlive) {
                observer.addOnScrollChangedListener(scrollListener)
                watchedObserver = observer
            }
        }

        private fun unregisterScrollObserver() {
            val observer = watchedObserver
            if (observer != null && observer.isAlive) {
                observer.removeOnScrollChangedListener(scrollListener)
            }
            watchedObserver = null
        }

        private fun stopWatching(host: View) {
            pauseAt(SystemClock.uptimeMillis())
            unregisterScrollObserver()
            host.removeOnAttachStateChangeListener(attachListener)
            if (watchedView === host) watchedView = null
        }

        private fun ensureWatching(host: View) {
            if (watchedView !== host) {
                watchedView?.removeOnAttachStateChangeListener(attachListener)
                unregisterScrollObserver()
                watchedView = host
                host.addOnAttachStateChangeListener(attachListener)
            }
            registerScrollObserver(host)
        }

        override fun draw(canvas: Canvas) {
            val host = callback as? View
            if (host != null) {
                ensureWatching(host)
                updateVisibility(host, SystemClock.uptimeMillis(), requestFrame = false)
            } else {
                pauseAt(SystemClock.uptimeMillis())
            }

            val duration = movie.duration()
            if (duration > 0 && playing) {
                val now = SystemClock.uptimeMillis()
                val elapsed = ((playTimeMs + (now - lastTickAt)) % duration).toInt()
                if (elapsed != lastFrame) {
                    movie.setTime(elapsed)
                    lastFrame = elapsed
                }
                // Keep the frame loop alive only while this image is visible.
                invalidateSelf()
            } else if (duration > 0 && lastFrame < 0) {
                movie.setTime((playTimeMs % duration).toInt())
                lastFrame = (playTimeMs % duration).toInt()
            }
            movie.draw(canvas, 0f, 0f)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = movie.width()
        override fun getIntrinsicHeight(): Int = movie.height()
    }

    /**
     * Load into a fullscreen-style viewer: animated webp/gif via ImageDecoder on
     * API 28+, otherwise falls back to the static frame with a fade-in.
     * [onReady] reports the decoded image size (AnimatedImageDrawable reports
     * intrinsic -1 on several API levels, so callers that rely on real
     * dimensions must use this callback).
     */
    fun loadAnimated(image: ImageView, rawUrl: String, host: View?, onReady: ((Int, Int) -> Unit)? = null) {
        if (rawUrl.isBlank()) return
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val bytes = URL(rawUrl).openStream().use { it.readBytes() }
                val source = android.graphics.ImageDecoder.createSource(bytes)
                val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    onReady?.invoke(info.size.width, info.size.height)
                }
                image.setImageDrawable(drawable)
                image.alpha = 0.72f
                image.animate().alpha(1f).setDuration(160L).start()
                return
            } catch (_: Exception) {
                // fall through to static frame
            }
        }
        load(image, rawUrl)
    }

    fun load(image: ImageView, rawUrl: String, original: Boolean = false,
             cropThumb: Boolean = true, clearBeforeLoad: Boolean = true,
             animate: Boolean = true) {
        // original = load the exact URL without CDN thumbnail suffix (used by
        // the fullscreen viewer so zooming stays sharp). cropThumb=false uses a
        // proportional thumbnail (no _1c centre-crop) so comment photos show
        // the full picture instead of the cropped middle strip.
        val url = when {
            original -> rawUrl
            cropThumb -> thumbnailUrl(rawUrl)
            else -> thumbnailUrl(rawUrl, crop = false)
        }
        if (url.isBlank()) {
            image.tag = url
            if (clearBeforeLoad) image.setImageDrawable(null)
            return
        }
        // Animated GIFs play through Movie instead of the static-bitmap path.
        if (isGifUrl(url)) {
            loadGif(image, url, clearBeforeLoad, animate = animate)
            return
        }
        if (image.tag == url && image.drawable != null) return
        image.tag = url
        image.animate().cancel()
        image.alpha = 1f
        if (url.isBlank()) {
            image.setImageDrawable(null)
            return
        }

        memory.get(url)?.let {
            image.setImageBitmap(it)
            return
        }
        // Fullscreen originals: if the comment thumb is already in memory, show it
        // immediately so the viewer never opens on a black frame while the large
        // archive image is still downloading.
        if (original && image.drawable == null) {
            val thumb = thumbnailUrl(url, crop = false)
            memory.get(thumb)?.let { image.setImageBitmap(it) }
        }
        if (clearBeforeLoad) image.setImageDrawable(null)

        val shouldStart: Boolean
        synchronized(lock) {
            val waiting = inFlight[url]
            if (waiting != null) {
                waiting += WeakReference(image)
                shouldStart = false
            } else {
                inFlight[url] = mutableListOf(WeakReference(image))
                shouldStart = true
            }
        }
        if (!shouldStart) return

        val appContext = image.context.applicationContext
        try {
            workers.execute {
                // Fullscreen originals can be several megabytes; keep thumbs tight.
                val maxBytes = if (original) MAX_ORIGINAL_DOWNLOAD_BYTES else MAX_DOWNLOAD_BYTES
                val readTimeout = if (original) ORIGINAL_READ_TIMEOUT_MS else READ_TIMEOUT_MS
                var bitmap = loadBitmap(appContext, url, maxBytes = maxBytes, readTimeoutMs = readTimeout)
                // If the true original is still too large / undecodable, fall back to a
                // large CDN webp. This is what the user can download but the viewer
                // previously refused to show because of the 2 MiB feed limit.
                if (bitmap == null && original) {
                    val fallback = fallbackViewerUrl(url)
                    if (fallback != null && fallback != url) {
                        bitmap = loadBitmap(
                            appContext,
                            fallback,
                            maxBytes = MAX_ORIGINAL_DOWNLOAD_BYTES,
                            readTimeoutMs = ORIGINAL_READ_TIMEOUT_MS
                        )
                    }
                }
                if (bitmap != null) memory.put(url, bitmap)
                val targets = synchronized(lock) { inFlight.remove(url).orEmpty() }
                if (bitmap != null) {
                    mainHandler.post {
                        targets.forEach { reference ->
                            val target = reference.get() ?: return@forEach
                            if (target.tag == url) {
                                target.setImageBitmap(bitmap)
                                target.alpha = 0.72f
                                target.animate().alpha(1f).setDuration(130L).start()
                            }
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { inFlight.remove(url) }
        }
    }

    /**
     * When the true archive original is too large for in-memory decode, ask the
     * CDN for a large but bounded still frame that still looks sharp full-screen.
     */
    private fun fallbackViewerUrl(rawUrl: String): String? {
        val bare = rawUrl.substringBefore('@').substringBefore('?')
        if (bare.isBlank()) return null
        if (CDN_COVER_HOSTS.none { bare.contains(it, ignoreCase = true) }) return bare
        return bare + "@1080w_1080h.webp"
    }

    /**
     * GIF path. [animate] controls whether the Movie loop runs:
     * - true  : comment thumbs / current viewer page (play)
     * - false : preloaded neighbour pages in the viewer (static first frame only)
     * Keeping neighbours static is what stops multi-GIF swipe hitching.
     */
    private fun loadGif(
        image: ImageView,
        url: String,
        clearBeforeLoad: Boolean = true,
        animate: Boolean = true
    ) {
        // Same URL already bound: just (re)apply play/pause policy.
        if (image.tag == url && image.drawable != null) {
            (image.drawable as? GifDrawable)?.setPlaybackEnabled(animate)
            if (animate) {
                image.drawable?.invalidateSelf()
                image.invalidate()
            }
            return
        }
        image.tag = url
        image.animate().cancel()
        image.alpha = 1f
        gifMemory.get(url)?.let { cached ->
            val context = image.context
            val drawable = if (cached is Movie) {
                GifDrawable(cached).also { it.setPlaybackEnabled(animate) }
            } else {
                BitmapDrawable(context.resources, cached as Bitmap)
            }
            image.setImageDrawable(drawable)
            if (animate) image.invalidate()
            return
        }
        if (clearBeforeLoad) image.setImageDrawable(null)

        val shouldStart: Boolean
        synchronized(lock) {
            val waiting = inFlight[url]
            if (waiting != null) {
                waiting += WeakReference(image)
                shouldStart = false
            } else {
                inFlight[url] = mutableListOf(WeakReference(image))
                shouldStart = true
            }
        }
        if (!shouldStart) return

        val appContext = image.context.applicationContext
        try {
            workers.execute {
                val decoded = loadGifMovie(appContext, url)
                if (decoded != null && decoded is Movie) gifMemory.put(url, decoded)
                val targets = synchronized(lock) { inFlight.remove(url).orEmpty() }
                if (decoded != null) {
                    mainHandler.post {
                        targets.forEach { reference ->
                            val target = reference.get() ?: return@forEach
                            if (target.tag == url) {
                                val drawable = when (decoded) {
                                    is Movie -> GifDrawable(decoded).also {
                                        it.setPlaybackEnabled(animate)
                                    }
                                    is Bitmap -> BitmapDrawable(
                                        appContext.resources, decoded
                                    )
                                    else -> null
                                }
                                if (drawable != null) {
                                    target.setImageDrawable(drawable)
                                    target.alpha = 0.72f
                                    target.animate().alpha(1f).setDuration(130L).start()
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { inFlight.remove(url) }
        }
    }

    /**
     * Fetch + decode a GIF origin. Returns the animated [Movie] when the bytes
     * really are a decodable GIF, otherwise a static [Bitmap] fallback (first
     * frame, or the server returning webp/jpg) so the comment never renders
     * blank.
     */
    private fun loadGifMovie(context: Context, url: String): Any? {
        val cacheDir = File(context.cacheDir, "gif_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cached = File(cacheDir, sha256(url) + ".gif")
        val bytes: ByteArray?
        if (cached.isFile && cached.length() > 0L) {
            bytes = cached.readBytes()
            cached.setLastModified(System.currentTimeMillis())
        } else {
            bytes = downloadBytes(url, maxBytes = 8 * 1024 * 1024) ?: return null
            if (bytes.isEmpty()) return null
            val temporary = File(cacheDir, cached.name + ".tmp")
            temporary.writeBytes(bytes)
            commitAndTrim(temporary, cached, cacheDir)
        }
        if (bytes.size < 6) return null
        return if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()
            && bytes[2] == 'F'.code.toByte()
        ) {
            val movie = try {
                Movie.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
            movie ?: decodeStill(bytes)
        } else {
            // Not actually a GIF (CDN may have served webp/jpg under a .gif
            // path): show the static frame instead of a blank tile.
            decodeStill(bytes)
        }
    }

    private fun decodeStill(bytes: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
        null
    }

    /** Raw byte download with size cap (used for GIF originals). */
    private fun downloadBytes(url: String, maxBytes: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", NETWORK_USER_AGENT)
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val announced = connection.contentLength
            if (announced > maxBytes) return null
            val buffer = java.io.ByteArrayOutputStream()
            BufferedInputStream(connection.inputStream).use { input ->
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) return null
                    buffer.write(chunk, 0, count)
                }
            }
            buffer.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun loadBitmap(
        context: Context,
        url: String,
        maxBytes: Int = MAX_DOWNLOAD_BYTES,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): Bitmap? {
        val cacheDir = File(context.cacheDir, "cover_thumbs")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cached = File(cacheDir, sha256(url) + ".img")
        if (cached.isFile && cached.length() > 0L) {
            decodeSampledFile(cached.absolutePath)?.let {
                cached.setLastModified(System.currentTimeMillis())
                return it
            }
            cached.delete()
        }

        val temporary = File(cacheDir, cached.name + ".tmp")
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", NETWORK_USER_AGENT)
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val announced = connection.contentLength
            if (announced > maxBytes) return null

            var total = 0
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) {
                            temporary.delete()
                            return null
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (total <= 0) return null
            commitAndTrim(temporary, cached, cacheDir)
            decodeSampledFile(cached.absolutePath)
        } catch (_: Exception) {
            temporary.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Decode large comment originals without OOM: sample down to roughly phone
     * resolution while still keeping the full-screen viewer sharp.
     */
    private fun decodeSampledFile(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return BitmapFactory.decodeFile(path)
        }
        val maxEdge = 2048
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(path, options)
        } catch (_: OutOfMemoryError) {
            options.inSampleSize = sample * 2
            options.inPreferredConfig = Bitmap.Config.RGB_565
            try {
                BitmapFactory.decodeFile(path, options)
            } catch (_: OutOfMemoryError) {
                null
            }
        }
    }

    private fun commitAndTrim(temporary: File, cached: File, directory: File) {
        synchronized(diskLock) {
            val previousBytes = if (cached.isFile) cached.length() else 0L
            if (!temporary.renameTo(cached)) {
                temporary.copyTo(cached, overwrite = true)
                temporary.delete()
            }
            val files = directory.listFiles { file -> file.isFile && !file.name.endsWith(".tmp") }
                ?: emptyArray()
            knownDiskBytes = if (knownDiskBytes < 0L) {
                var measuredBytes = 0L
                for (file in files) measuredBytes += file.length()
                measuredBytes
            } else {
                knownDiskBytes - previousBytes + cached.length()
            }
            if (knownDiskBytes <= MAX_DISK_BYTES) return
            for (file in files.sortedBy { it.lastModified() }) {
                if (knownDiskBytes <= MAX_DISK_BYTES) break
                if (file == cached) continue
                val length = file.length()
                if (file.delete()) knownDiskBytes -= length
            }
        }
    }

    private fun thumbnailUrl(raw: String, crop: Boolean = true): String {
        val normalized = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") -> "https://${raw.removePrefix("http://")}"
            else -> raw
        }
        // Apply the CDN thumbnail suffix to any bilibili cover domain, not just
        // hdslb.com — the search API sometimes returns other CDN hosts.
        if (normalized.isBlank() || normalized.contains("@")) return normalized
        // Keep animated GIFs untouched: the CDN's "@...webp" variant is a
        // static frame, so GIFs must be fetched in their original form.
        if (isGifUrl(normalized)) return normalized
        val host = normalized.substringAfter("https://", "").substringBefore("/")
        if (host.isBlank()) return normalized
        val isCdnHost = CDN_COVER_HOSTS.any { host.contains(it) }
        if (!isCdnHost) return normalized
        // _1c = centre-crop to 16:9 (feed covers); plain w/h keeps the full
        // picture's aspect ratio (comment photos).
        return normalized + if (crop) THUMBNAIL_SUFFIX else "@480w_480h.webp"
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private inline fun <T> Iterable<T>.sumOfCompat(selector: (T) -> Long): Long {
        var sum = 0L
        for (item in this) sum += selector(item)
        return sum
    }
}
