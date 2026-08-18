package com.yuilittle.bili

import android.Manifest
import android.animation.ValueAnimator
import android.animation.Animator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Scroller
import android.widget.TextView
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.sin
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class VideoDetailActivity : Activity() {
    private lateinit var activityRoot: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var playerHost: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var gestureLayer: PlayerGestureView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var playerBack: View
    private lateinit var playerSpinner: ProgressBar
    private lateinit var playerError: TextView
    private lateinit var infoContent: LinearLayout
    private lateinit var infoState: TextView
    private lateinit var gestureDetector: GestureDetector
    private val handler = Handler(Looper.getMainLooper())
    private val reducedMotion = MotionTokens.isReduced(this)
    private var exoPlayer: ExoPlayer? = null
    private var currentBvid = ""
    private var currentAid = 0L
    private var currentCid: Long = 0L
    /** 本地下载文件播放模式：不拉详情/评论，只显示标题、大小、时长。 */
    private var isLocalMode = false
    private var localTaskId = 0L
    private var isFullscreen = false
    private var currentStream: BiliPlayUrl.DashStream? = null
    /** The single-track stream actually fed to ExoPlayer (after stable selection). */
    private var playingStream: BiliPlayUrl.DashStream? = null
    private val qualityPrefs by lazy { getSharedPreferences("bili_player", MODE_PRIVATE) }
    private var requestedQuality = BiliPlayUrl.QUALITY_720

    // ── Player control overlay ──
    private lateinit var controlOverlay: ViewGroup
    private lateinit var replayBtn: ReplayIcon
    private lateinit var bottomBar: ViewGroup
    private lateinit var playPauseBtn: PlayPauseIcon
    private lateinit var progressContainer: SeekBarView
    private lateinit var infoArea: View
    private lateinit var timeText: TextView
    private lateinit var durationText: TextView
    private lateinit var speedBtn: TextView
    private lateinit var qualityBtn: TextView
    private lateinit var fsBtn: FullscreenIcon
    private lateinit var progressRow: LinearLayout
    private lateinit var favBtn: ActionIconView
    private var favFolderIds: List<Long> = emptyList()
    private lateinit var boostIndicator: TextView
    private var boostAnimator: ValueAnimator? = null
    private var endedState = false
    private var exitingFullscreen = false
    private var fsTransitioning = false
    private var controlsVisible = true
    private var controlsAnimating = false
    private var controlsHideRunnable: Runnable? = null
    private val speeds = floatArrayOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 1
    private var isSeeking = false
    private var seekWasPlaying = false
    private var normalSpeed = 1.0f

    // ── Info tabs: 简介 / 评论 ──
    private lateinit var tabIntro: TextView
    private lateinit var tabComment: TextView
    private lateinit var tabIntroIndicator: View
    private lateinit var tabCommentIndicator: View
    private lateinit var infoPager: HorizontalPagerView
    private lateinit var introScroll: ScrollView
    private lateinit var commentScroll: ScrollView
    private lateinit var commentContent: LinearLayout
    private lateinit var commentState: TextView
    private lateinit var commentMore: TextView
    private var commentCursor = -1
    /** rpids already rendered, so a broken cursor can never duplicate rows. */
    private val seenCommentRpids = HashSet<Long>()
    private var commentLoginHintShown = false
    private var commentsLoading = false
    private var commentsLoaded = false
    private var currentDetail: VideoItem? = null
    private var currentTab = 0

    // ── Intro collapse/expand (title row with chevron; only the description collapses) ──
    private lateinit var titleText: TextView
    private lateinit var titleArrow: View
    private lateinit var titleClip: FrameLayout
    private lateinit var introExpandWrap: LinearLayout
    private lateinit var introDescWrap: LinearLayout
    private lateinit var introDescClip: FrameLayout
    private var introExpanded = false
    /** 合集分集列表是否展开：跨简介重建保留，点击选集后列表保持展开状态。 */
    private var seasonSectionExpanded = false
    private var titleLineHeight = 0
    private var titleFullHeight = 0
    private var introDescHeight = 0
    private var titleHeightAnimator: ValueAnimator? = null
    private var descHeightAnimator: ValueAnimator? = null
    // ── Comments: sort (0=hot likes, 1=newest) + auto paging + total badge ──
    private var commentSort = 0
    private var commentHasMore = false
    private lateinit var commentBubble: TextView
    private lateinit var commentSortBtn: TextView
    /** Fixed info-tab navigation row; reply sheets start immediately below it. */
    private var infoTabBarAnchor: View? = null
    /** Comment section header row; used only for the sort row. */
    private var commentHeaderAnchor: View? = null
    /** Locally liked comment rpids (server sync pending until login lands). */
    // Comment likes are always re-fetched from Bilibili after action; no local store.
    // ── Follow button state (server-backed; no local fake store) ──
    private var followBtnRef: TextView? = null
    private var followInFlight = false
    private var currentFollowed = false
    // ── Video action bar (like / favorite / download / share) ──
    private var videoLiked = false
    /** Automatic retries left for transient playback IO errors. */
    private var playRetryCount = 0
    private var playbackGeneration = 0
    private var pendingResumePositionMs = 0L
    private var userWantsPlayback = true
    private var activityResumed = false
    private var retryScheduled = false
    private var stablePlaybackStartedAtMs = 0L
    private var lastPlaybackError: String? = null
    private var videoLikesCount = 0L

    // ── Brightness / volume gesture ──
    private val audioManager: AudioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private lateinit var adjustIndicator: AdjustIndicator
    private var adjustMode = 0 // 0 none, 1 brightness, 2 volume
    private var adjustStartValue = 0f

    // ── Gesture state ──
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureSeeking = false
    private var gestureSeekStartPos = 0L
    private var longPressBoost = false
    private var longPressDown = false
    private var longPressCandidate = false
    private val longPressBoostRunnable = Runnable { applyLongPressBoost() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        BiliSessionStore.init(this)
        currentBvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        currentAid = intent.getLongExtra(EXTRA_AID, 0L)
        localTaskId = intent.getLongExtra(EXTRA_LOCAL_TASK_ID, 0L)
        isLocalMode = localTaskId > 0L
        requestedQuality = loadQualityPreference()
        // Keep the big-vip badge state fresh for the quality menu (best effort).
        BiliSessionStore.refreshVipInfo()
        if (currentBvid.isBlank() && !isLocalMode) { finish(); return }
        if (isLocalMode) {
            DownloadManager.init(this)
            val local = DownloadManager.task(localTaskId)
            if (local == null || !local.playable()) { finish(); return }
        }
        // Match the themed content canvas so the status bar is not a detached black strip.
        Theme.applySystemBars(this)
        // Detail page is portrait-designed; fullscreen switches to landscape temporarily.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        activityRoot = FrameLayout(this).apply { setBackgroundColor(COLOR_BACKGROUND) }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val playerHeight = resources.displayMetrics.widthPixels * 9 / 16

        // ── Gesture detector ──
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (longPressBoost) return true
                toggleControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlayPause()
                return true
            }
        })

        // ── Player host: Media3 PlayerView owns aspect-ratio and video surface ──
        playerHost = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            setBackgroundColor(Color.BLACK)
        }
        playerHost.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Gesture layer stays below all buttons, so it never steals their click events.
        gestureLayer = PlayerGestureView(this)
        playerHost.addView(gestureLayer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        playerSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        playerHost.addView(playerSpinner, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))

        playerError = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor((0xB3000000).toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isClickable = true
            setOnClickListener { retryPlayer() }
        }
        playerHost.addView(playerError, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // ── Back button (top-left, simple arrow, no background) ──
        playerBack = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.STROKE
                strokeWidth = dpf(2.5f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f; val cy = height / 2f; val len = dpf(9f)
                canvas.drawLine(cx + len * 0.3f, cy - len * 0.6f, cx - len * 0.7f, cy, paint)
                canvas.drawLine(cx - len * 0.7f, cy, cx + len * 0.3f, cy + len * 0.6f, paint)
            }
        }.apply {
            contentDescription = "返回"
            setOnClickListener { if (isFullscreen) toggleFullscreen() else finish() }
        }
        // Added to playerHost after createControlOverlay() so it sits above the top scrim.

        // ── Center replay button (only visible after playback ends) ──
        replayBtn = ReplayIcon(this).apply {
            visibility = View.GONE; alpha = 0f
            isClickable = true
            setOnClickListener { replay() }
        }
        playerHost.addView(replayBtn, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))

        // ── Boost indicator: translucent pill shown above the video while long-press boosting ──
        boostIndicator = TextView(this).apply {
            text = "2.0x 倍速播放中"
            textSize = 13f
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = rounded(0x26FFFFFF.toInt(), dp(13))
            alpha = 0f
            visibility = View.GONE
        }
        playerHost.addView(boostIndicator, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(52) })

        // ── Brightness / volume adjustment indicator (center overlay) ──
        adjustIndicator = AdjustIndicator(this).apply {
            alpha = 0f
            visibility = View.GONE
            // Compact in portrait; full size in fullscreen (scale updated on toggle).
            scaleX = COMPACT_ADJUST_SCALE
            scaleY = COMPACT_ADJUST_SCALE
        }
        playerHost.addView(adjustIndicator, FrameLayout.LayoutParams(dp(72), dp(170), Gravity.CENTER))

        // ── Control overlay ──
        createControlOverlay()
        page.addView(playerHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, playerHeight))
        infoArea = createInfoArea()
        page.addView(infoArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        activityRoot.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        activityRoot.addView(fullscreenContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(activityRoot)
        if (isLocalMode) {
            loadLocalPlayer()
        } else {
            loadVideoAndPlayer()
        }
    }

    // ─────────────────────────────────────────────────
    //  Control Overlay
    // ─────────────────────────────────────────────────

    private fun createControlOverlay() {
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Transparent background — contrast comes from the bottom scrim gradient.
            background = null
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(6))
        }

        // Play/Pause
        playPauseBtn = PlayPauseIcon(this).apply {
            isClickable = true
            setOnClickListener { togglePlayPause() }
        }
        bottomBar.addView(playPauseBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER })

        // Right side: speed / quality / fullscreen
        bottomBar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // Speed
        speedBtn = TextView(this).apply {
            text = "1.0x"; textSize = 10.5f
            setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { cycleSpeed() }
        }
        bottomBar.addView(speedBtn, LinearLayout.LayoutParams(dp(34), dp(28)).apply { gravity = Gravity.CENTER })

        // Quality (shows the actual playing resolution, e.g. 720p)
        qualityBtn = TextView(this).apply {
            text = "自动"; textSize = 10.5f
            setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { showQualityMenu(this) }
        }
        bottomBar.addView(qualityBtn, LinearLayout.LayoutParams(dp(38), dp(28)).apply { gravity = Gravity.CENTER })
        // 本地文件播放时画质固定，不展示切换按钮
        if (isLocalMode) qualityBtn.visibility = View.GONE

        // Fullscreen
        fsBtn = FullscreenIcon(this).apply {
            isClickable = true
            setOnClickListener { toggleFullscreen() }
        }
        bottomBar.addView(fsBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER })

        // ── Progress row: current time | seek bar | total duration ──
        progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        timeText = TextView(this).apply {
            text = "00:00"
            textSize = 10.5f
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            // Center the glyph vertically inside its box so it lines up with the
            // seek bar track (track sits at (height - trackH) / 2).
            gravity = Gravity.CENTER_VERTICAL
        }
        progressRow.addView(timeText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL })

        progressContainer = SeekBarView(this).apply {
            isClickable = true
            setOnTouchListener { _, event ->
                val p = exoPlayer ?: return@setOnTouchListener true
                val totalDuration = playerGlobalDuration(p)
                if (totalDuration <= 0L || width <= 0) return@setOnTouchListener true
                val ratio = (event.x / width.toFloat()).coerceIn(0f, 1f)
                val seekPos = (ratio * totalDuration).toLong()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        isDragging = true
                        isSeeking = true
                        seekWasPlaying = p.playWhenReady && userWantsPlayback
                        p.pause()
                        seekToFraction(ratio)
                    }
                    MotionEvent.ACTION_MOVE -> seekToFraction(ratio)
                    MotionEvent.ACTION_UP -> {
                        seekPlayerGlobal(p, seekPos)
                        if (seekWasPlaying && activityResumed && userWantsPlayback) p.play()
                        isDragging = false
                        isSeeking = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (seekWasPlaying && activityResumed && userWantsPlayback) p.play()
                        isDragging = false
                        isSeeking = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                onManualSeek(seekPos, totalDuration)
                true
            }
        }
        progressRow.addView(progressContainer, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            leftMargin = dp(8); rightMargin = dp(8) })

        durationText = TextView(this).apply {
            text = "00:00"
            textSize = 10.5f
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            includeFontPadding = false
            minWidth = dp(40)
        }
        progressRow.addView(durationText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER_VERTICAL })

        controlOverlay = FrameLayout(this).apply {
            visibility = View.VISIBLE
            background = null
        }
        // ── Scrim gradients: darken top/bottom so white controls stay readable on any content.
        // Non-interactive: taps pass straight through to the gesture layer below.
        controlOverlay.addView(View(this).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x8C000000.toInt(), 0x00000000)
            )
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(92), Gravity.TOP))
        controlOverlay.addView(View(this).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xB0000000.toInt())
            )
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(128), Gravity.BOTTOM))

        controlOverlay.addView(progressRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(24), Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(32)
        })
        controlOverlay.addView(bottomBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        playerHost.addView(controlOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Back button mounts above the control overlay so the top scrim never dims it.
        playerHost.addView(playerBack, FrameLayout.LayoutParams(
            dp(40), dp(40), Gravity.START or Gravity.TOP).apply {
            leftMargin = dp(8); topMargin = dp(8) })
        applyControlSizing()
    }

    /**
     * Control sizing follows the container: compact in the in-feed portrait player,
     * back to the roomier fullscreen layout once the player re-parents. Keeps the
     * controls from dominating the small viewport without touching fullscreen.
     */
    private fun applyControlSizing() {
        if (!::bottomBar.isInitialized) return
        val compact = !isFullscreen
        bottomBar.setPadding(dp(6), dp(if (compact) 2 else 8), dp(6), dp(if (compact) 6 else 10))
        playPauseBtn.layoutParams = LinearLayout.LayoutParams(
            dp(if (compact) 30 else 36), dp(if (compact) 30 else 36)).apply { gravity = Gravity.CENTER }
        speedBtn.layoutParams = LinearLayout.LayoutParams(
            dp(if (compact) 34 else 40), dp(if (compact) 28 else 36)).apply { gravity = Gravity.CENTER }
        speedBtn.textSize = if (compact) 10.5f else 11f
        qualityBtn.layoutParams = LinearLayout.LayoutParams(
            dp(if (compact) 38 else 44), dp(if (compact) 28 else 36)).apply { gravity = Gravity.CENTER }
        qualityBtn.textSize = if (compact) 10.5f else 11f
        fsBtn.layoutParams = LinearLayout.LayoutParams(
            dp(if (compact) 30 else 36), dp(if (compact) 30 else 36)).apply { gravity = Gravity.CENTER }
        if (::progressRow.isInitialized) {
            progressRow.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(if (compact) 24 else 28), Gravity.BOTTOM).apply {
                leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(if (compact) 32 else 44)
            }
            timeText.textSize = if (compact) 10.5f else 11f
            durationText.textSize = if (compact) 10.5f else 11f
        }
        if (::playerBack.isInitialized) {
            playerBack.layoutParams = FrameLayout.LayoutParams(
                dp(if (compact) 40 else 44), dp(if (compact) 40 else 44),
                Gravity.START or Gravity.TOP).apply {
                leftMargin = dp(8); topMargin = dp(if (compact) 8 else 12)
            }
        }
    }

    // ─────────────────────────────────────────────────
    //  Info Area
    // ─────────────────────────────────────────────────

    private fun createInfoArea(): View {
        // 本地播放：不展示简介/评论，只显示标题、大小、时长，其余留白。
        if (isLocalMode) return createLocalInfoArea()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }

        // ── Tab bar: 简介 / 评论 ──
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        fun buildTab(label: String, active: Boolean): Pair<TextView, View> {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(1), 0, dp(1), 0)
                isClickable = true
            }
            val text = TextView(this).apply {
                text = label; textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                // Theme-aware from the first frame; default TextView ink is black.
                setTextColor(if (active) COLOR_INK else COLOR_MUTED)
                setPadding(0, dp(12), 0, dp(10))
            }
            val indicator = View(this).apply {
                setBackgroundColor(COLOR_ROSE)
                alpha = if (active) 1f else 0f
            }
            container.addView(text, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            container.addView(indicator, LinearLayout.LayoutParams(dp(36), dp(3)))
            return text to indicator
        }
        val introTab = buildTab("简介", active = true)
        val commentTab = buildTab("评论", active = false)
        tabIntro = introTab.first
        tabIntroIndicator = introTab.second
        tabComment = commentTab.first
        tabCommentIndicator = commentTab.second
        tabIntro.setOnClickListener { selectTab(0) }
        tabComment.setOnClickListener { selectTab(1) }
        (introTab.first.parent as? View)?.setOnClickListener { selectTab(0) }
        (commentTab.first.parent as? View)?.setOnClickListener { selectTab(1) }
        tabBar.addView(introTab.first.parent as View, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tabBar.addView(commentTab.first.parent as View, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(28)
        })

        // ── Comment total badge: only visible while the comment page is active ──
        commentBubble = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = rounded(COLOR_ROSE, dp(9))
            text = "9999+"
            visibility = View.INVISIBLE   // occupies space so the bar never jumps
            alpha = 0f
            scaleX = 0f
            pivotX = 0f
        }
        tabBar.addView(commentBubble, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
            gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(tabBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        infoTabBarAnchor = tabBar

        // ── Intro tab content ──
        introScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        infoContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(36))
        }
        infoState = TextView(this).apply {
            text = "正在加载简介…"
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(COLOR_MUTED)
            setPadding(0, dp(28), 0, dp(28))
        }
        infoContent.addView(infoState, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        introScroll.addView(infoContent, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── Comment tab content ──
        commentScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val content = commentContent
                if (content.height > 0 && scrollY + height >= content.height - dp(140)) {
                    maybeLoadMoreComments()
                }
            }
        }
        val commentWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // Sort toggle lives at the top-right of the comment list (not on the tab bar):
        // a drawn sort icon + label, no background.
        val sortIcon = SortIconView(this)
        commentSortBtn = TextView(this).apply {
            text = "高赞"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener { toggleCommentSort() }
        }
        commentWrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(2))
            addView(View(this@VideoDetailActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(sortIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(commentSortBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }.also { commentHeaderAnchor = it }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        commentContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(24))
        }
        commentState = TextView(this).apply {
            text = "正在加载评论…"
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(COLOR_MUTED)
            setPadding(0, dp(24), 0, dp(24))
        }
        commentContent.addView(commentState, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        commentMore = TextView(this).apply {
            text = "加载更多评论"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(COLOR_ROSE); setPadding(0, dp(14), 0, dp(6))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { loadComments(reset = false) }
        }
        commentContent.addView(commentMore, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        commentWrap.addView(commentContent, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        commentScroll.addView(commentWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── Horizontal pager: 简介 | 评论 (drag + fling, tab bar stays in sync) ──
        infoPager = HorizontalPagerView(this).apply {
            pageChangeListener = { index -> updateTabUI(index) }
            // Tab visuals follow the swipe in real time (fade + text color lerp).
            pageProgressListener = { progress -> syncTabProgress(progress) }
        }
        infoPager.addView(introScroll, 0)
        infoPager.addView(commentScroll, 1)
        root.addView(infoPager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        selectTab(0)
        return root
    }

    private fun selectTab(index: Int) {
        if (currentTab == index) return
        infoPager.scrollToPage(index, smooth = true)
        // Tab visuals follow the pager via pageProgressListener; updateTabUI fires on settle.
    }

    private fun updateTabUI(index: Int) {
        if (currentTab == index) {
            syncTabVisuals(index)
            return
        }
        currentTab = index
        syncTabVisuals(index)
        if (index == 1 && !commentsLoaded) loadComments(reset = true)
    }

    /** Final visual state once the pager has settled on a page. */
    private fun syncTabVisuals(index: Int) {
        val intro = index == 0
        tabIntro.setTextColor(if (intro) COLOR_INK else COLOR_MUTED)
        tabComment.setTextColor(if (intro) COLOR_MUTED else COLOR_INK)
        tabIntroIndicator.alpha = if (intro) 1f else 0f
        tabCommentIndicator.alpha = if (intro) 0f else 1f
        commentBubble.visibility = if (intro) View.INVISIBLE else View.VISIBLE
        commentBubble.alpha = if (intro) 0f else 1f
        commentBubble.scaleX = if (intro) 0f else 1f
    }

    /** Live tab visuals while the pager is being dragged: fade + color lerp follow the finger. */
    private fun syncTabProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        tabIntro.setTextColor(lerpColor(COLOR_INK, COLOR_MUTED, p))
        tabComment.setTextColor(lerpColor(COLOR_MUTED, COLOR_INK, p))
        tabIntroIndicator.alpha = 1f - p
        tabCommentIndicator.alpha = p
        commentBubble.visibility = if (p > 0.05f) View.VISIBLE else View.INVISIBLE
        commentBubble.alpha = p
        commentBubble.scaleX = p
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int =
        (android.animation.ArgbEvaluator().evaluate(t.coerceIn(0f, 1f), from, to) as Int)

    /** Swap comment order: 高赞 (hot) <-> 最新 (newest), then reload from page 1. */
    private fun toggleCommentSort() {
        if (commentsLoading) return
        commentSort = if (commentSort == 0) 1 else 0
        commentSortBtn.text = if (commentSort == 0) "高赞" else "最新"
        commentSortBtn.setTextColor(if (commentSort == 0) COLOR_MUTED else COLOR_ROSE)
        commentSortBtn.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90)
            .withEndAction {
                commentSortBtn.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
        loadComments(reset = true)
    }

    /** Video like/unlike: optimistic UI, then real server sync when logged in. */
    private fun toggleVideoLike(btn: ActionIconView) {
        if (!BiliSessionStore.isLoggedIn()) {
            showSnackbar("请先登录后再点赞")
            return
        }
        val aid = currentDetail?.aid ?: 0L
        if (aid <= 0L) {
            showSnackbar("视频信息未就绪，请稍后再试")
            return
        }
        val previousLiked = videoLiked
        val previousCount = videoLikesCount
        val nextLiked = !previousLiked
        videoLiked = nextLiked
        videoLikesCount = (previousCount + if (nextLiked) 1 else -1).coerceAtLeast(0L)
        btn.setActive(videoLiked)
        btn.setCount(videoLikesCount)
        btn.animate().scaleX(1.25f).scaleY(1.25f).setDuration(100)
            .withEndAction { btn.animate().scaleX(1f).scaleY(1f).setDuration(150).start() }.start()
        BiliApi.likeVideo(aid, nextLiked) { ok, error ->
            if (isFinishing || isDestroyed) return@likeVideo
            if (!ok) {
                // Roll back so the UI never claims a like the server rejected.
                videoLiked = previousLiked
                videoLikesCount = previousCount
                btn.setActive(videoLiked)
                btn.setCount(videoLikesCount)
                showSnackbar(error ?: "点赞失败，请稍后再试")
            } else {
                // Bridge the few-second server lag on has/like + stat.like so a
                // quick re-enter still shows the just-confirmed action.
                LikeStateBridge.rememberVideo(aid, videoLiked, videoLikesCount)
            }
        }
    }

    /**
     * 主题化「收藏到文件夹」弹窗：
     * 圆角卡片 + 可多选收藏夹行 + 底部取消/确定，贴合下载弹窗视觉。
     */
    /** 已收藏状态下点按收藏按钮：一键取消收藏（无需弹窗）。 */
    private fun cancelFavoriteDirect(btn: ActionIconView) {
        if (!BiliSessionStore.isLoggedIn()) {
            showSnackbar("请先登录后再收藏")
            return
        }
        val item = currentDetail ?: return
        btn.isEnabled = false
        // B 站无公开 multi_fav 查询口，del 需要收藏夹 id：用已缓存或全部收藏夹 id 兜底。
        BiliApi.fetchFavoriteFolders { folders, error ->
            if (isFinishing || isDestroyed) return@fetchFavoriteFolders
            if (error != null) {
                btn.isEnabled = true
                showSnackbar(error)
                return@fetchFavoriteFolders
            }
            val delAll = if (favFolderIds.isNotEmpty())
                favFolderIds.joinToString(",")
            else
                folders.joinToString(",") { it.id.toString() }
            if (delAll.isBlank()) {
                btn.isEnabled = true
                showSnackbar("还没有收藏夹")
                return@fetchFavoriteFolders
            }
            BiliApi.favoriteVideo(item.aid, "", delAll) { ok, err ->
                if (isFinishing || isDestroyed) return@favoriteVideo
                btn.isEnabled = true
                if (ok) {
                    favFolderIds = emptyList()
                    btn.setActive(false)
                    showSnackbar("已取消收藏")
                } else {
                    showSnackbar(err ?: "取消收藏失败，请稍后再试")
                }
            }
        }
    }

    private fun openFavoriteDialog(btn: ActionIconView) {
        val item = currentDetail ?: return
        if (!BiliSessionStore.isLoggedIn()) {
            showSnackbar("请先登录后再收藏")
            return
        }
        // 收藏不弹中间 loading：列表回来后直接出主题化选择框（与改版前一致）。
        BiliApi.fetchFavoriteFolders { folders, error ->
            if (isFinishing || isDestroyed) return@fetchFavoriteFolders
            if (error != null) { showSnackbar(error); return@fetchFavoriteFolders }
            if (folders.isEmpty()) {
                showSnackbar("还没有收藏夹，请先在网页端创建")
                return@fetchFavoriteFolders
            }
            showFavoriteFolderPicker(item, folders, btn)
        }
    }

    private fun showFavoriteFolderPicker(
        item: VideoItem,
        folders: List<FavoriteFolder>,
        btn: ActionIconView
    ) {
        val checked = BooleanArray(folders.size)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_CARD, dp(20), COLOR_CARD_BORDER, 1)
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }
        root.addView(TextView(this).apply {
            text = "收藏到文件夹"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
        })
        root.addView(TextView(this).apply {
            text = "选择收藏夹，确定后同步到 B 站"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(4), 0, 0)
        })

        val listScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        folders.forEachIndexed { index, folder ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(
                    if (checked[index]) COLOR_ROSE_SOFT else COLOR_SOFT_FILL,
                    dp(14)
                )
                setPadding(dp(14), dp(13), dp(14), dp(13))
                isClickable = true
                isFocusable = true
            }
            // 自绘圆形勾选框：选中 = 玫瑰底白勾；未选中 = 灰色描边圆。
            val check = object : View(this) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val cx = width / 2f; val cy = height / 2f
                    val r = width / 2f - dpf(1.5f)
                    if (checked[index]) {
                        paint.color = COLOR_ROSE
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy, r, paint)
                        paint.color = Color.WHITE
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = dpf(1.8f)
                        paint.strokeCap = Paint.Cap.ROUND
                        canvas.drawLine(cx - r * 0.45f, cy, cx - r * 0.12f, cy + r * 0.38f, paint)
                        canvas.drawLine(cx - r * 0.12f, cy + r * 0.38f, cx + r * 0.5f, cy - r * 0.42f, paint)
                    } else {
                        paint.color = COLOR_MUTED
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = dpf(1.4f)
                        canvas.drawCircle(cx, cy, r, paint)
                    }
                }
            }
            val name = TextView(this).apply {
                text = folder.name
                textSize = 14f
                setTextColor(COLOR_INK)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(12), 0, 0, 0)
            }
            val count = TextView(this).apply {
                text = "${folder.count}"
                textSize = 12f
                setTextColor(COLOR_MUTED)
            }
            fun applyChecked(on: Boolean) {
                checked[index] = on
                check.invalidate()
                row.background = rounded(
                    if (on) COLOR_ROSE_SOFT else COLOR_SOFT_FILL,
                    dp(14)
                )
            }
            row.setOnClickListener { applyChecked(!checked[index]) }
            row.addView(check, LinearLayout.LayoutParams(dp(22), dp(22)))
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(count)
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) topMargin = dp(8) })
        }
        listScroll.addView(list, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(listScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        listScroll.post {
            if (listScroll.height > maxH) {
                listScroll.layoutParams = listScroll.layoutParams.apply { height = maxH }
                listScroll.requestLayout()
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        btnRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        val cancel = TextView(this).apply {
            text = "取消"
            textSize = 14f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val confirm = TextView(this).apply {
            text = "确定"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(COLOR_ROSE, dp(18))
            setPadding(dp(24), dp(10), dp(24), dp(10))
            isClickable = true
        }
        confirm.setOnClickListener {
            // 纯收藏弹窗：勾选的收藏夹加进去（已收藏时由星星按钮直接取消，不走这里）。
            val addIds = folders.filterIndexed { i, _ -> checked[i] }
                .joinToString(",") { it.id.toString() }
            if (addIds.isBlank()) {
                dialog.dismiss()
                return@setOnClickListener
            }
            confirm.isEnabled = false
            confirm.alpha = 0.6f
            BiliApi.favoriteVideo(item.aid, addIds, "") { ok, err ->
                if (isFinishing || isDestroyed) return@favoriteVideo
                if (ok) {
                    dialog.dismiss()
                    favFolderIds = folders.filterIndexed { i, _ -> checked[i] }.map { it.id }
                    btn.setActive(true)
                    showSnackbar("已收藏")
                    btn.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100)
                        .withEndAction {
                            btn.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                        }.start()
                } else {
                    confirm.isEnabled = true
                    confirm.alpha = 1f
                    showSnackbar(err ?: "操作失败，请稍后再试")
                }
            }
        }
        btnRow.addView(cancel)
        btnRow.addView(confirm, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })
        root.addView(btnRow)

        dialog.setContentView(root)
        val w = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        dialog.window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
        // 入场：轻微放大 + 淡入，避免生硬弹出。
        root.alpha = 0f
        root.scaleX = 0.92f
        root.scaleY = 0.92f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(160)
            .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
            .start()
        dialog.show()
        // 入场：轻微上浮淡入
        root.alpha = 0f
        root.translationY = dp(16).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    /**
     * 打开全屏下载选择页（替代旧下载弹窗）。
     * 先复用已加载的播放流；没有时才异步查询一次，避免给不支持的视频列出 4K 等画质。
     */
    private fun openDownloadDialog() {
        val item = currentDetail ?: return
        if (item.charge) {
            showSnackbar("该视频为充电/付费视频，禁止下载")
            return
        }
        val cached = currentStream
        if (cached != null && cached.acceptedQualities.isNotEmpty()) {
            launchDownloadPicker(item, cached.acceptedQualities)
            return
        }
        val loading = showThemedLoadingDialog(
            message = "正在获取可用画质…",
            title = "下载到本地"
        )
        BiliPlayUrl.fetchPlayUrl(item.bvid, item.cid, BiliPlayUrl.QUALITY_4K) { stream, _ ->
            if (isFinishing || isDestroyed) return@fetchPlayUrl
            if (stream != null) {
                runOnUiThread {
                    loading.dismiss()
                    launchDownloadPicker(item, stream.acceptedQualities)
                }
            } else {
                BiliPlayUrl.fetchPlayUrl(item.bvid, item.cid, BiliPlayUrl.QUALITY_720) { fallback, _ ->
                    if (isFinishing || isDestroyed) return@fetchPlayUrl
                    runOnUiThread {
                        loading.dismiss()
                        launchDownloadPicker(item, fallback?.acceptedQualities ?: emptyList())
                    }
                }
            }
        }
    }

    /** 携带视频信息与可用画质跳转下载页。 */
    private fun launchDownloadPicker(item: VideoItem, acceptedQualities: List<Int>) {
        startActivity(Intent(this, DownloadPickerActivity::class.java)
            .putExtra(DownloadPickerActivity.EXTRA_ITEM_JSON, DownloadPickerActivity.itemToJson(item))
            .putExtra(DownloadPickerActivity.EXTRA_ACCEPTED_QNS, acceptedQualities.toIntArray()))
    }

    /**
     * 主题化加载弹窗。
     * @param title 标题（下载/收藏等场景不同，禁止写死「下载到本地」）
     * @param message 副文案
     */
    private fun showThemedLoadingDialog(
        message: String,
        title: String = "请稍候"
    ): Dialog {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_CARD, dp(18), COLOR_CARD_BORDER, 1)
            setPadding(dp(22), dp(20), dp(22), dp(20))
            gravity = Gravity.CENTER
        }
        body.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            gravity = Gravity.CENTER
        })
        body.addView(TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(body)
        val w = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        dialog.window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
        return dialog
    }
    /** Share = copy 【标题】+ 链接 to the clipboard. */
    private fun shareVideo() {
        val item = currentDetail ?: return
        val link = "https://www.bilibili.com/video/${item.bvid}"
        val text = "【${item.title}】$link"
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("B站视频", text))
        showSnackbar("已复制链接，去分享给朋友吧")
    }

    /** 关注/取关 UP：必须登录并真正请求 B 站 relation/modify；失败则回滚 UI。 */
    private fun toggleFollow(mid: Long, btn: TextView) {
        if (mid <= 0L) { showSnackbar("暂不支持关注该用户"); return }
        if (!BiliSessionStore.isLoggedIn()) {
            showSnackbar("请先登录后再关注")
            return
        }
        if (followInFlight) return
        val newFollow = !currentFollowed
        // 乐观更新
        applyFollowUi(btn, newFollow)
        currentFollowed = newFollow
        LikeStateBridge.rememberFollow(mid, newFollow)
        followInFlight = true
        btn.animate().scaleX(1.12f).scaleY(1.12f).setDuration(100)
            .withEndAction { btn.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }.start()
        BiliApi.modifyRelation(mid, newFollow) { ok, error ->
            if (isFinishing || isDestroyed) return@modifyRelation
            followInFlight = false
            if (!ok) {
                // 失败回滚到上一次状态
                val rollback = !newFollow
                currentFollowed = rollback
                LikeStateBridge.rememberFollow(mid, rollback)
                applyFollowUi(btn, rollback)
                showSnackbar(error ?: "关注失败，请稍后再试")
            } else {
                // 成功后短暂桥接，避免重进立刻读到旧关系
                LikeStateBridge.rememberFollow(mid, newFollow)
            }
        }
    }

    private fun applyFollowUi(btn: TextView, followed: Boolean) {
        btn.text = if (followed) "已关注" else "关注"
        btn.setTextColor(if (followed) COLOR_MUTED else COLOR_ROSE)
        btn.background = if (followed) rounded(COLOR_FOLLOWED_CHIP, dp(14))
        else rounded(0x00FFFFFF.toInt(), dp(14), COLOR_ROSE, dp(1))
    }

    /** 进入详情后异步查询真实关注关系，并叠加最近一次成功操作。 */
    private fun refreshFollowState(mid: Long, btn: TextView) {
        if (mid <= 0L) return
        if (!BiliSessionStore.isLoggedIn()) {
            currentFollowed = false
            applyFollowUi(btn, false)
            return
        }
        // 先用桥接/默认状态画一次，再等服务器确认
        val bridged = LikeStateBridge.resolveFollow(mid, currentFollowed)
        currentFollowed = bridged
        applyFollowUi(btn, bridged)
        BiliApi.fetchRelation(mid) { following, _ ->
            if (isFinishing || isDestroyed) return@fetchRelation
            if (following == null) return@fetchRelation
            val resolved = LikeStateBridge.resolveFollow(mid, following)
            currentFollowed = resolved
            applyFollowUi(btn, resolved)
        }
    }

    private fun showSnackbar(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────
    //  Player Lifecycle
    // ─────────────────────────────────────────────────

    // ── 本地下载文件播放（复用同一套播放器，只展示标题/大小/时长） ──

    private fun createLocalInfoArea(): View {
        val task = DownloadManager.task(localTaskId)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        root.addView(View(this).apply { setBackgroundColor(COLOR_BORDER) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        inner.addView(TextView(this).apply {
            text = task?.title?.ifBlank { "本地视频" } ?: "本地视频"
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_INK)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        fun infoRow(label: String, value: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, 0)
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(COLOR_MUTED)
            }, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = value
                textSize = 13f
                setTextColor(COLOR_INK)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            inner.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val sizeBytes = localTaskSizeBytes(task)
        infoRow("画质", task?.qualityLabel?.ifBlank { "本地文件" } ?: "本地文件")
        infoRow("大小", localFormatBytes(sizeBytes))
        infoRow("时长", localFormatDuration(task?.durationMs ?: 0L))
        root.addView(inner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // 其余留白：后续功能再填充
        root.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun localTaskSizeBytes(task: DownloadManager.Task?): Long = when {
        task == null -> 0L
        task.mpdPath.isNotBlank() && File(task.mpdPath).exists() -> {
            val dir = File(task.mpdPath).parentFile
            dir?.listFiles()?.filter {
                it.name.startsWith("${task.id}_") && (it.name.endsWith(".m4s") || it.name.endsWith(".mp4"))
            }?.sumOf { it.length() } ?: task.bytesTotal
        }
        task.audioPath.isNotBlank() && File(task.audioPath).exists() -> File(task.audioPath).length()
        else -> task.singlePaths.sumOf { File(it).length() }.takeIf { it > 0L } ?: task.bytesTotal
    }

    private fun loadLocalPlayer() {
        val task = DownloadManager.task(localTaskId) ?: run { finish(); return }
        val generation = ++playbackGeneration
        retryScheduled = false
        exoPlayer?.let { old ->
            pendingResumePositionMs = maxOf(pendingResumePositionMs, playerGlobalPosition(old))
            playerView.player = null
            exoPlayer = null
            old.release()
        }
        playerSpinner.visibility = View.VISIBLE
        playerError.visibility = View.GONE
        val mediaSource: MediaSource
        try {
            mediaSource = when {
                task.mpdPath.isNotBlank() && File(task.mpdPath).exists() -> {
                    val item = MediaItem.Builder()
                        .setUri(Uri.fromFile(File(task.mpdPath)))
                        .setMimeType(MimeTypes.APPLICATION_MPD)
                        .build()
                    DashMediaSource.Factory(DefaultDataSource.Factory(this)).createMediaSource(item)
                }
                task.audioPath.isNotBlank() && File(task.audioPath).exists() -> {
                    val item = MediaItem.Builder()
                        .setUri(Uri.fromFile(File(task.audioPath)))
                        .setMimeType(MimeTypes.APPLICATION_MP4)
                        .build()
                    ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this)).createMediaSource(item)
                }
                else -> {
                    val paths = task.singlePaths.filter { File(it).exists() }
                    if (paths.isEmpty()) {
                        showError("本地文件缺失，请返回下载页删除该任务")
                        return
                    }
                    val sources = paths.map { path ->
                        val item = MediaItem.Builder()
                            .setUri(Uri.fromFile(File(path)))
                            .setMimeType(MimeTypes.APPLICATION_MP4)
                            .build()
                        ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this)).createMediaSource(item)
                    }
                    if (sources.size == 1) sources.first() else ConcatenatingMediaSource(true, *sources.toTypedArray())
                }
            }
        } catch (e: Exception) {
            showError("本地播放初始化失败：${e.message ?: "未知错误"}\n\n点这里重试")
            return
        }
        startLocalPlayer(mediaSource, generation)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startLocalPlayer(mediaSource: MediaSource, generation: Int) {
        if (generation != playbackGeneration || isFinishing || isDestroyed) return
        endedState = false
        playPauseBtn.setEnded(false)
        showReplay(false)
        cancelLongPressBoost()
        val renderersFactory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 90_000, 5_000, 10_000)
                    .setTargetBufferBytes(64 * 1024 * 1024)
                    .build()
            )
            .build()
        playerView.player = player
        player.setMediaSource(mediaSource)
        player.repeatMode = Player.REPEAT_MODE_OFF
        if (pendingResumePositionMs > 0L) player.seekTo(pendingResumePositionMs)
        player.playWhenReady = userWantsPlayback && activityResumed
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                when (state) {
                    Player.STATE_READY -> {
                        playerSpinner.visibility = View.GONE
                        playerError.visibility = View.GONE
                        pendingResumePositionMs = 0L
                        playPauseBtn.setEnded(false)
                        playPauseBtn.setPlaying(player.isPlaying)
                        if (!isSeeking && player.isPlaying) { showReplay(false); scheduleAutoHide() }
                    }
                    Player.STATE_BUFFERING -> if (!isSeeking) playerSpinner.visibility = View.VISIBLE
                    Player.STATE_ENDED -> {
                        playerSpinner.visibility = View.GONE
                        cancelLongPressBoost()
                        endedState = true
                        playPauseBtn.setEnded(true)
                        playPauseBtn.setPlaying(false)
                        showReplay(true)
                        showControls()
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                if (isPlaying) {
                    endedState = false
                    playPauseBtn.setEnded(false)
                    playPauseBtn.setPlaying(true)
                    showReplay(false)
                    scheduleAutoHide()
                } else {
                    playPauseBtn.setEnded(false)
                    playPauseBtn.setPlaying(false)
                    showControls()
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                showError("本地文件播放失败：${error.message ?: error.errorCodeName}\n\n点这里重试")
            }
        })
        player.prepare()
        exoPlayer = player
        handler.removeCallbacks(progressUpdater)
        handler.post(progressUpdater)
    }

    private fun localFormatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0B"
        val mb = bytes / 1024.0 / 1024.0
        return when {
            mb >= 1024.0 -> String.format(java.util.Locale.US, "%.2fGB", mb / 1024.0)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1fMB", mb)
            else -> String.format(java.util.Locale.US, "%.0fKB", bytes / 1024.0)
        }
    }

    private fun localFormatDuration(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }

    private fun loadVideoAndPlayer() {
        BiliPlayUrl.prewarm()
        // When switching a multi-P video, bvid/aid stay the same but cid changes.
        // Preserve the requested cid while refreshing the common detail payload.
        val requestedCid = currentCid
        BiliApi.fetchDetail(currentBvid, currentAid) { item, parts, error ->
            if (isFinishing || isDestroyed) return@fetchDetail
            handler.post {
                if (item != null) {
                    val selectedPart = requestedCid.takeIf { it > 0L }
                        ?.let { cid -> parts.firstOrNull { it.cid == cid } }
                    val selectedItem = if (selectedPart != null && selectedPart.cid != item.cid) {
                        item.copy(
                            title = selectedPart.title,
                            duration = selectedPart.duration,
                            cid = selectedPart.cid
                        )
                    } else {
                        item
                    }
                    currentDetail = selectedItem
                    currentCid = selectedPart?.cid ?: item.cid
                    // Record local watch history (deduped, newest first).
                    HistoryStore.add(this, selectedItem.bvid, selectedItem.title,
                        selectedItem.owner, selectedItem.cover)
                    renderIntroduction(selectedItem)
                } else {
                    infoState.text = "简介加载失败：${error ?: "未知错误"}\n\n点这里重试"
                    infoState.isClickable = true
                    infoState.setOnClickListener { loadVideoAndPlayer() }
                }
                playRetryCount = 0
                loadPlayer()
            }
        }
    }

    // ─────────────────────────────────────────────────
    //  Info Tab
    // ─────────────────────────────────────────────────

    private fun renderIntroduction(item: VideoItem) {
        if (isFinishing || isDestroyed) return
        infoContent.removeAllViews()
        introExpanded = false
        titleHeightAnimator?.cancel()
        descHeightAnimator?.cancel()
        // Comment total badge on the tab bar.
        if (::commentBubble.isInitialized) {
            commentBubble.text = if (item.replyCount > 0L) formatViews(item.replyCount) else "0"
        }

        // ── Collapsible body: title row (with chevron) + UP row + stats + description.
        //    Collapsed by default; tapping the title/arrow expands with a height animation.
        introExpandWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ── Title row: single-line ellipsized title + chevron arrow ──
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { toggleIntro() }
        }
        titleText = TextView(this).apply {
            text = item.title
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        // Clip wrapper: the title expands from 1 line to full height with a smooth
        // height animation instead of popping to multiple lines instantly.
        titleClip = FrameLayout(this).apply {
            clipChildren = true
        }
        titleClip.addView(titleText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        titleArrow = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_MUTED; style = Paint.Style.STROKE
                strokeWidth = dpf(1.8f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            private val path = Path()
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f; val cy = height / 2f; val s = dpf(4.5f)
                path.reset()
                path.moveTo(cx - s, cy - s * 0.55f)
                path.lineTo(cx, cy + s * 0.55f)
                path.lineTo(cx + s, cy - s * 0.55f)
                canvas.drawPath(path, paint)
            }
        }
        titleRow.addView(titleClip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        titleRow.addView(titleArrow, LinearLayout.LayoutParams(dp(26), dp(26)).apply {
            leftMargin = dp(4)
        })
        introExpandWrap.addView(titleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── UP row: avatar (two text lines tall) + name + fans ──
        val upRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val avatar = RoundImageView(this).apply {
            setBackgroundColor(COLOR_COVER)
        }
        if (item.ownerAvatar.isNotBlank()) {
            CoverLoader.load(avatar, item.ownerAvatar + "@52w_52h_1c.webp")
        }
        upRow.addView(avatar, LinearLayout.LayoutParams(dp(36), dp(36)))
        val upText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        upText.addView(TextView(this).apply {
            text = item.owner.ifBlank { "哔哩哔哩用户" }
            textSize = 13.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_INK)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val fansText = TextView(this).apply {
            text = "粉丝数加载中…"
            textSize = 11f; setTextColor(COLOR_MUTED)
            setPadding(0, dp(1), 0, 0)
        }
        upText.addView(fansText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        upRow.addView(upText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // ── Follow button on the right edge of the UP row ──
        // 初始态先用桥接/默认，再异步拉真实关系，避免假本地关注。
        val initialFollowed = LikeStateBridge.resolveFollow(item.ownerMid, item.following)
        currentFollowed = initialFollowed
        val followBtn = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(5), dp(14), dp(5))
            isClickable = true
        }
        applyFollowUi(followBtn, initialFollowed)
        followBtnRef = followBtn
        followBtn.setOnClickListener { toggleFollow(item.ownerMid, followBtn) }
        upRow.addView(followBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(10)
        })
        refreshFollowState(item.ownerMid, followBtn)
        introExpandWrap.addView(upRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── Stats line: views | publish time | online count ──
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statsRow.addView(object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_MUTED; style = Paint.Style.STROKE
                strokeWidth = dpf(1.4f); strokeCap = Paint.Cap.ROUND
            }
            private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_MUTED; style = Paint.Style.FILL
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat(); val h = height.toFloat()
                val r = h * 0.36f
                canvas.drawOval(RectF(w / 2f - r * 1.45f, h / 2f - r, w / 2f + r * 1.45f, h / 2f + r), paint)
                canvas.drawCircle(w / 2f, h / 2f, r * 0.32f, dotPaint)
            }
        }, LinearLayout.LayoutParams(dp(16), dp(11)).apply { gravity = Gravity.CENTER_VERTICAL })

        val viewsText = TextView(this).apply {
            text = formatViews(item.views)
            textSize = 13f; setTextColor(COLOR_MUTED)
            setPadding(dp(4), 0, dp(10), 0)
        }
        statsRow.addView(viewsText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val dateText = TextView(this).apply {
            text = formatPublish(item.publishedAt)
            textSize = 13f; setTextColor(COLOR_MUTED)
        }
        statsRow.addView(dateText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val onlineText = TextView(this).apply {
            text = " · 正在加载观看人数"
            textSize = 13f; setTextColor(COLOR_MUTED)
            setPadding(dp(10), 0, 0, 0)
            maxLines = 1
        }
        statsRow.addView(onlineText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        introExpandWrap.addView(statsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── Action bar below the stats: like / favorite / download / share ──
        // view.req_user is often missing; has/like is the flag authority.
        // stat.like can lag a few seconds after archive/like, so overlay the
        // short-lived bridge from a just-confirmed action (not a disk store).
        run {
            val bridged = LikeStateBridge.resolveVideo(item.aid, item.liked, item.likes)
            videoLiked = bridged.first
            videoLikesCount = bridged.second
        }
        val likeBtn = ActionIconView(this, KIND_LIKE)
        likeBtn.setCount(videoLikesCount)
        likeBtn.setActive(videoLiked)
        likeBtn.setOnClickListener { toggleVideoLike(likeBtn) }
        if (BiliSessionStore.isLoggedIn() && item.aid > 0L) {
            BiliApi.fetchHasLiked(item.aid) { liked, _ ->
                if (isFinishing || isDestroyed || liked == null) return@fetchHasLiked
                val bridged = LikeStateBridge.resolveVideo(item.aid, liked, videoLikesCount)
                if (videoLiked == bridged.first && videoLikesCount == bridged.second) return@fetchHasLiked
                videoLiked = bridged.first
                videoLikesCount = bridged.second
                likeBtn.setActive(videoLiked)
                likeBtn.setCount(videoLikesCount)
            }
        }
        val favBtn = ActionIconView(this, KIND_FAVORITE)
        this.favBtn = favBtn
        favBtn.setOnClickListener {
            if (favBtn.isActive()) {
                cancelFavoriteDirect(favBtn)
            } else {
                openFavoriteDialog(favBtn)
            }
        }
        if (BiliSessionStore.isLoggedIn() && item.aid > 0L) {
            BiliApi.fetchFavoriteDeal(item.aid) { isFav, favIds, _ ->
                if (isFinishing || isDestroyed) return@fetchFavoriteDeal
                favFolderIds = favIds
                favBtn.setActive(isFav)
            }
        }
        val dlBtn = ActionIconView(this, KIND_DOWNLOAD)
        dlBtn.setOnClickListener { openDownloadDialog() }
        val shareBtn = ActionIconView(this, KIND_SHARE)
        shareBtn.setOnClickListener { shareVideo() }
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        }
        listOf(likeBtn, favBtn, dlBtn, shareBtn).forEach { btn ->
            actionBar.addView(btn, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        introExpandWrap.addView(actionBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── Description block (the only part that collapses; UP/stats rows stay visible) ──
        introDescWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        introDescWrap.addView(View(this).apply {
            setBackgroundColor(COLOR_BORDER)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        })
        introDescWrap.addView(TextView(this).apply {
            text = item.description.ifBlank { "暂无简介" }
            textSize = 13f; setTextColor(COLOR_MUTED)
            setLineSpacing(dp(2).toFloat(), 1f); setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Clip wrapper: description height animates 0 <-> full while the content is
        // revealed line by line (no per-frame re-measure of the multi-line text).
        introDescClip = FrameLayout(this).apply {
            clipChildren = true
        }
        introDescClip.addView(introDescWrap, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        introExpandWrap.addView(introDescClip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        infoContent.addView(introExpandWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 合集/多 P：视频属于官方合集或同 BV 的 pages 多 P 时展示。
        renderSeasonSection(item)

        // Measure heights: title one line / full lines, and the description block.
        val availWidth = if (infoContent.width > 0) infoContent.width
        else resources.displayMetrics.widthPixels - dp(36)
        val unconstrained = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        titleText.maxLines = 1
        titleClip.measure(
            View.MeasureSpec.makeMeasureSpec(availWidth, View.MeasureSpec.EXACTLY), unconstrained)
        titleLineHeight = titleClip.measuredHeight
        titleText.maxLines = Int.MAX_VALUE
        titleText.ellipsize = null
        titleClip.measure(
            View.MeasureSpec.makeMeasureSpec(availWidth, View.MeasureSpec.EXACTLY), unconstrained)
        titleFullHeight = titleClip.measuredHeight
        titleText.maxLines = 1
        titleText.ellipsize = android.text.TextUtils.TruncateAt.END
        // Keep weight=1 so the chevron stays on the row; only the height animates.
        titleClip.layoutParams = LinearLayout.LayoutParams(0, titleLineHeight, 1f)
        introDescWrap.measure(
            View.MeasureSpec.makeMeasureSpec(availWidth, View.MeasureSpec.EXACTLY), unconstrained)
        introDescHeight = introDescWrap.measuredHeight
        introDescClip.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0)

        // ── Fans + online (async) ──
        if (item.ownerMid > 0L) {
            BiliApi.fetchFans(item.ownerMid) { fans, _ ->
                if (isFinishing || isDestroyed || currentDetail?.bvid != item.bvid) return@fetchFans
                fansText.text = formatFans(fans ?: 0L)
            }
        } else {
            fansText.text = "粉丝数未知"
        }
        if (item.aid > 0L && item.cid > 0L) {
            BiliApi.fetchOnline(item.aid, item.cid) { online, _ ->
                if (isFinishing || isDestroyed || currentDetail?.bvid != item.bvid) return@fetchOnline
                onlineText.text = if (online != null && online > 0L) " · ${online} 人正在看" else ""
            }
        } else {
            onlineText.text = ""
        }
    }

    private fun toggleIntro() = setIntroExpanded(!introExpanded)

    /**
     * 简介下方的「合集分集」卡片：视频属于合集(ugc_season)时渲染，
     * 无合集直接 return（整体不占空间）。默认收起只显示头部，
     * 点头部展开全部分集列表；点击某一集切到对应视频。
     */
    private fun renderSeasonSection(item: VideoItem) {
        val season = item.season ?: return
        if (isFinishing || isDestroyed || season.episodes.isEmpty()) return
        val episodes = season.episodes
        // 单行高度：行布局(标题 20dp + 上下留白)固定，供展开后定位当前集计算用。
        val rowHeight = dp(38)
        val currentIndex = episodes.indexOfFirst {
            (it.cid > 0L && it.cid == item.cid) ||
                (it.bvid.isNotBlank() && it.bvid == item.bvid && it.aid > 0L && it.aid == item.aid)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(10))
            background = rounded(COLOR_CARD, dp(14), COLOR_CARD_BORDER, 1)
        }
        infoContent.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        // ── 头部行：合集徽标 + 标题 + 集数 + 展开箭头 ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
        }
        header.addView(TextView(this).apply {
            text = "合集"
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_ROSE)
            gravity = Gravity.CENTER
            background = rounded(COLOR_ROSE_SOFT, dp(6))
            setPadding(dp(7), dp(3), dp(7), dp(3))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(TextView(this).apply {
            text = season.title
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8)
        })
        header.addView(TextView(this).apply {
            text = "共 ${episodes.size} 集"
            textSize = 11f
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(6)
        })
        val arrow = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_MUTED; style = Paint.Style.STROKE
                strokeWidth = dpf(1.8f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            private val path = Path()
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f; val cy = height / 2f; val s = dpf(4.5f)
                path.reset()
                path.moveTo(cx - s, cy + s * 0.55f)
                path.lineTo(cx, cy - s * 0.55f)
                path.lineTo(cx + s, cy + s * 0.55f)
                canvas.drawPath(path, paint)
            }
        }
        header.addView(arrow, LinearLayout.LayoutParams(dp(26), dp(26)).apply {
            leftMargin = dp(2)
        })
        card.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 分集列表（默认收起，高度 0；展开时显示全部）──
        fun buildRow(ep: UgcEpisode, isCurrent: Boolean): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 默认 baselineAligned=true 会把子 TextView 按基线对齐，导致右侧时长
                // 相对标题下沉；关闭后 gravity=CENTER_VERTICAL 才能垂直居中。
                isBaselineAligned = false
                isClickable = true
                setOnClickListener {
                    if (!isCurrent && (ep.bvid.isNotBlank() || ep.aid > 0L)) {
                        currentBvid = ep.bvid
                        currentAid = ep.aid
                        loadVideoAndPlayer()
                    }
                }
            }
            // 标题过长时单行省略（不再自动滚动，保持列表简洁）。
            val title = TextView(this).apply {
                text = ep.title
                textSize = 13f
                setTextColor(if (isCurrent) COLOR_ROSE else COLOR_INK)
                includeFontPadding = false
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(title, LinearLayout.LayoutParams(0, dp(20), 1f))
            // 播放中的那一集右侧不显示时长（已有"播放中"标签，避免时长误导）。
            if (ep.duration > 0 && !isCurrent) {
                row.addView(TextView(this).apply {
                    text = formatDuration(ep.duration)
                    textSize = 11f
                    setTextColor(COLOR_MUTED)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(8)
                })
            }
            if (isCurrent) {
                row.addView(TextView(this).apply {
                    text = "播放中"
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = rounded(COLOR_ROSE, dp(8))
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(8)
                })
            }
            return row
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        episodes.forEachIndexed { index, ep ->
            list.addView(buildRow(ep, index == currentIndex), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowHeight))
        }
        // 列表内部滚动：几百集的合集也不会把简介撑爆，超过上限高度后只滚列表。
        // 不能用 setOnTouchListener 设 disallow：列表行是 clickable 的，DOWN 事件被行
        // 消费后 scroll 的 onTouchEvent 不再触发；必须重写 dispatchTouchEvent，
        // 让任何事件（无论子 view 是否消费）都先经过这里禁止外层简介区拦截手势。
        val scroll = object : ScrollView(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_MOVE -> {
                        // 列表完全不能滚时才交还手势，让外层页面继续滚动。
                        if (!canScrollVertically(1) && !canScrollVertically(-1)) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        parent?.requestDisallowInterceptTouchEvent(false)
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        scroll.addView(list, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val clip = FrameLayout(this).apply { clipChildren = true }
        clip.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        card.addView(clip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0))

        // 提前测量展开高度：超过上限时列表内部滚动。
        val availWidth = if (infoContent.width > 0) infoContent.width - dp(28)
        else resources.displayMetrics.widthPixels - dp(36) - dp(28)
        val unconstrained = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        list.measure(
            View.MeasureSpec.makeMeasureSpec(availWidth, View.MeasureSpec.EXACTLY), unconstrained)
        val fullHeight = list.measuredHeight
        val maxListHeight = minOf(fullHeight, dp(320))
        clip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            if (seasonSectionExpanded) maxListHeight else 0)
        arrow.rotation = if (seasonSectionExpanded) 180f else 0f

        header.setOnClickListener {
            seasonSectionExpanded = !seasonSectionExpanded
            animateHeight(clip,
                if (seasonSectionExpanded) maxListHeight else 0,
                if (seasonSectionExpanded) 240L else 200L)
            arrow.animate().rotation(if (seasonSectionExpanded) 180f else 0f).setDuration(200L).start()
            if (seasonSectionExpanded && currentIndex >= 0) {
                // 展开后把正在播放的那一集滚到可视区域附近，方便快捷点击确认。
                scroll.post {
                    scroll.smoothScrollTo(0,
                        (currentIndex * rowHeight - (maxListHeight - rowHeight) / 2)
                            .coerceAtLeast(0))
                }
            }
        }
        // 切换分集后简介会重建：保持展开状态并重新定位到新播放的那一集。
        if (seasonSectionExpanded && currentIndex >= 0) {
            scroll.post {
                scroll.smoothScrollTo(0,
                    (currentIndex * rowHeight - (maxListHeight - rowHeight) / 2)
                        .coerceAtLeast(0))
            }
        }
    }

    private fun setIntroExpanded(expanded: Boolean) {
        if (introExpanded == expanded || !::introDescWrap.isInitialized) return
        introExpanded = expanded
        titleArrow.animate().rotation(if (expanded) 180f else 0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
        // Both clips animate height via ValueAnimator + layoutParams. Their children
        // are pre-measured and clipped, so no per-frame text re-measure happens:
        // the title lines reveal smoothly and the description no longer stutters.
        titleHeightAnimator?.cancel()
        descHeightAnimator?.cancel()
        if (expanded) {
            titleText.maxLines = Int.MAX_VALUE
            titleText.ellipsize = null
        } else {
            titleText.maxLines = 1
            titleText.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleHeightAnimator = animateHeight(titleClip, if (expanded) titleFullHeight else titleLineHeight,
            if (expanded) 280L else 200L)
        descHeightAnimator = animateHeight(introDescClip, if (expanded) introDescHeight else 0,
            if (expanded) 300L else 220L).apply {
            // After expanding, release the fixed height so long descriptions are
            // fully laid out and the surrounding ScrollView scrolls naturally
            // (no boxed-in region).
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (introExpanded) {
                        introDescClip.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }
            })
        }
    }

    private fun animateHeight(view: View, target: Int, duration: Long): ValueAnimator =
        ValueAnimator.ofInt(view.layoutParams.height, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val lp = view.layoutParams
                lp.height = va.animatedValue as Int
                view.layoutParams = lp
            }
            start()
        }

    /** 粉丝数：万以下显示具体数字，万以上显示 "1 万粉丝"（最多一位小数）。 */
    private fun formatFans(count: Long): String {
        if (count <= 0L) return "粉丝数未知"
        return if (count < 10_000L) "$count 粉丝"
        else "${formatViews(count)} 粉丝"
    }

    /** 发布时间精确到分钟，例如 "2026年7月31日 22:22"。 */
    private fun formatPublish(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "时间未知"
        return SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)
            .format(Date(epochSeconds * 1000L))
    }

    // ─────────────────────────────────────────────────
    //  Comment Tab
    // ─────────────────────────────────────────────────

    private fun loadComments(reset: Boolean) {
        val item = currentDetail ?: return
        if (item.aid <= 0L) {
            commentState.text = "暂无评论"
            commentMore.visibility = View.GONE
            return
        }
        if (commentsLoading) return
        commentsLoading = true
        if (reset) {
            commentCursor = 1
            commentsLoaded = false
            commentHasMore = false
            seenCommentRpids.clear()
            commentContent.removeAllViews()
            commentContent.addView(commentState, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            commentState.text = "正在加载评论…"
            commentState.visibility = View.VISIBLE
            commentMore.visibility = View.GONE
        } else {
            commentMore.text = "正在加载更多…"
        }
        BiliApi.fetchComments(item.aid, commentCursor, commentSort) { list, next, error ->
            if (isFinishing || isDestroyed) return@fetchComments
            commentsLoading = false
            commentState.visibility = View.GONE
            if (error != null) {
                if (!commentsLoaded) {
                    if (commentState.parent == null) commentContent.addView(commentState, 0)
                    commentState.text = "评论加载失败：$error\n\n点这里重试"
                    commentState.visibility = View.VISIBLE
                    commentState.isClickable = true
                    commentState.setOnClickListener { loadComments(reset = true) }
                } else {
                    commentMore.text = "加载失败，点这里重试"
                    commentMore.isClickable = true
                }
                return@fetchComments
            }
            commentsLoaded = true
            commentHasMore = next >= 0
            if (reset) commentContent.removeAllViews()
            // Deduplicate by rpid: even if the cursor were ever wrong, already
            // rendered comments are never appended a second time.
            val fresh = list.filter { seenCommentRpids.add(it.id) }
            fresh.forEachIndexed { index, comment ->
                if (index > 0) {
                    commentContent.addView(View(this).apply { setBackgroundColor(COLOR_BORDER) },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
                }
                commentContent.addView(buildCommentRow(comment))
            }
            commentCursor = next
            if (commentHasMore) {
                if (commentMore.parent == null) commentContent.addView(commentMore)
                if (reset) {
                    // First load: show the affordance once; later pages load
                    // automatically on scroll and the hint stays hidden.
                    commentMore.text = "上滑加载更多"
                    commentMore.visibility = View.VISIBLE
                } else {
                    commentMore.visibility = View.GONE
                }
            } else {
                commentMore.visibility = View.GONE
                if (list.isEmpty() && commentContent.childCount == 0) {
                    if (commentState.parent == null) commentContent.addView(commentState)
                    commentState.text = "暂无评论"
                    commentState.visibility = View.VISIBLE
                } else if (!BiliSessionStore.isLoggedIn() && !commentLoginHintShown) {
                    // Bilibili now caps anonymous comment lists at 3 hot replies; guide login.
                    commentLoginHintShown = true
                    val hint = TextView(this).apply {
                        text = "仅展示热门评论，登录后可查看全部回复 →"
                        textSize = 12f; setTextColor(COLOR_ROSE)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(14), 0, dp(14))
                        isClickable = true
                        setOnClickListener {
                            startActivity(Intent(this@VideoDetailActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                putExtra(EXTRA_SWITCH_TO_ACCOUNT, true)
                            })
                        }
                    }
                    commentContent.addView(hint, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
            }
        }
    }

    /** Auto-paging: when the user scrolls near the bottom, load the next page. */
    private fun maybeLoadMoreComments() {
        if (!commentsLoaded || commentsLoading || !commentHasMore) return
        loadComments(reset = false)
    }

    private fun buildCommentRow(comment: VideoComment): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(12), 0, dp(12))
        }
        val avatar = RoundImageView(this).apply { setBackgroundColor(COLOR_COVER) }
        if (comment.avatar.isNotBlank()) {
            CoverLoader.load(avatar, comment.avatar + "@52w_52h_1c.webp")
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(36), dp(36)))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        body.addView(TextView(this).apply {
            text = comment.user
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_INK)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(TextView(this).apply {
            text = formatCommentTime(comment.publishedAt)
            textSize = 11f; setTextColor(COLOR_MUTED)
            setPadding(0, dp(3), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Long comments start collapsed so one wall of text does not dominate the list.
        body.addView(
            buildExpandableCommentText(comment.content, comment.emotes, textSizeSp = 14f),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // User-uploaded comment photos. Fixed (non-scrolling) layout: every
        // card is always visible, so taps map 1:1 to the picture order.
        if (comment.pictures.isNotEmpty()) {
            body.addView(buildPicturesRow(comment.pictures), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        body.addView(LikeButton(this, comment), LinearLayout.LayoutParams(dp(64), dp(26)).apply {
            topMargin = dp(7)
        })
        // In-floor (sub) replies preview with a "view all replies" sheet entry.
        if (comment.replies.isNotEmpty() || comment.replyCount > 0L) {
            val floor = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // Softer, quieter surface than the generic skeleton fill so
                // collapsed previews do not look like loading placeholders.
                background = rounded(
                    if (Theme.isDark) 0x1AFFFFFF.toInt() else 0x0F000000.toInt(),
                    dp(10)
                )
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                // Whole preview opens the full reply sheet; matches official UX.
                setOnClickListener { showReplySheet(comment) }
            }
            comment.replies.take(2).forEach { sub ->
                floor.addView(buildSubCommentItem(sub))
            }
            val moreLabel = when {
                comment.replyCount > comment.replies.size ->
                    "共 ${comment.replyCount} 条回复，点击查看"
                comment.replies.isNotEmpty() ->
                    "查看全部回复"
                else -> "查看回复"
            }
            floor.addView(TextView(this).apply {
                text = moreLabel
                textSize = 12f
                setTextColor(COLOR_ROSE)
                setPadding(0, if (comment.replies.isEmpty()) 0 else dp(4), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            body.addView(floor, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /**
     * Long comments collapse past 50 characters so one wall of text does not
     * dominate the list. Tap 「展开」 / 「收起」 to toggle; short comments stay full.
     */
    private fun buildExpandableCommentText(
        content: String,
        emotes: Map<String, String>,
        textSizeSp: Float
    ): View {
        val fullText = commentSpannable(content, emotes)
        val needsCollapse = content.length > COMMENT_COLLAPSE_CHARS
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val body = TextView(this).apply {
            textSize = textSizeSp
            setTextColor(COLOR_INK)
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        val toggle = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_ROSE)
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        fun applyState(expanded: Boolean) {
            if (!needsCollapse || expanded) {
                body.text = fullText
                body.maxLines = Integer.MAX_VALUE
                body.ellipsize = null
                toggle.visibility = if (needsCollapse) View.VISIBLE else View.GONE
                toggle.text = "收起"
            } else {
                // Keep the full spannable so emotes in the first lines still
                // render, then cap the visible height with maxLines.
                body.text = fullText
                body.maxLines = COMMENT_COLLAPSE_LINES
                body.ellipsize = android.text.TextUtils.TruncateAt.END
                toggle.visibility = View.VISIBLE
                toggle.text = "展开"
            }
            attachEmoteSpans(body)
        }
        var expanded = false
        applyState(expanded = false)
        if (needsCollapse) {
            toggle.setOnClickListener {
                expanded = !expanded
                applyState(expanded)
            }
            body.setOnClickListener {
                if (!expanded) {
                    expanded = true
                    applyState(expanded)
                }
            }
        }
        box.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(toggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return box
    }

    /** Comment photo row: fixed, non-scrolling, so every tap maps 1:1 to the
     *  picture order. A single photo keeps its natural aspect ratio and sits
     *  left-aligned; 2+ photos share the row width evenly, with the remainder
     *  collapsed into a "+N" card that opens the viewer at the third picture. */
    private fun buildPicturesRow(pics: List<CommentPicture>): LinearLayout {
        val shown = minOf(2, pics.size)
        val extraCount = pics.size - shown
        val gap = dp(6)
        // Body width = screen minus avatar (36dp) and its margin (10dp).
        val bodyW = resources.displayMetrics.widthPixels - dp(46)
        val cardCount = shown + (if (extraCount > 0) 1 else 0)
        val cardW = if (pics.size == 1) {
            val p = pics[0]
            val h = dp(120)
            if (p.width > 0 && p.height > 0)
                (h.toFloat() * p.width / p.height).toInt().coerceIn(dp(60), dp(240))
            else h
        } else {
            (bodyW - gap * (cardCount - 1)) / cardCount
        }
        val cardH = if (pics.size == 1) dp(120) else
            pics.take(shown).map { picture ->
                if (picture.width > 0 && picture.height > 0)
                    (cardW.toFloat() * picture.height / picture.width).toInt().coerceIn(dp(80), dp(170))
                else dp(120)
            }.maxOrNull() ?: dp(120)
        val imageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        pics.take(shown).forEachIndexed { index, picture ->
            val image = ImageView(this).apply {
                // CENTER_CROP fills the card so FIT_CENTER no longer leaves a
                // pale letterbox halo around GIFs on the dark canvas.
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(COLOR_COVER, dp(8))
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
                setOnClickListener {
                    showImagesDialog(pics.map { it.url }, index)
                }
            }
            // cropThumb = false keeps the full picture (no 16:9 centre-crop).
            CoverLoader.load(image, picture.url, cropThumb = false)
            imageRow.addView(image, LinearLayout.LayoutParams(cardW, cardH).apply {
                rightMargin = gap
            })
        }
        if (extraCount > 0) {
            // Use the third picture itself as the more-card background, then lay a
            // soft dark veil and "+N" on top so it feels continuous with the gallery.
            val moreHost = FrameLayout(this).apply {
                background = rounded(COLOR_COVER, dp(8))
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
                setOnClickListener {
                    showImagesDialog(pics.map { it.url }, shown)
                }
            }
            val moreImage = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            CoverLoader.load(moreImage, pics[shown].url, cropThumb = false)
            moreHost.addView(moreImage, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            moreHost.addView(View(this).apply {
                setBackgroundColor(0x66000000.toInt())
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            moreHost.addView(TextView(this).apply {
                text = "+$extraCount"
                textSize = 20f
                setTextColor(Color.WHITE)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            imageRow.addView(moreHost, LinearLayout.LayoutParams(cardW, cardH).apply {
                rightMargin = gap
            })
        }
        return imageRow
    }

    /** One sub (in-floor) reply: small text line, name + content, collapsed to 2 lines. */
    private fun buildSubCommentItem(sub: VideoComment): View {
        val line = TextView(this).apply {
            // Name stays slightly stronger so the preview is scannable; body is muted.
            val span = android.text.SpannableStringBuilder()
            val nameStart = span.length
            span.append(sub.user)
            span.setSpan(
                android.text.style.ForegroundColorSpan(COLOR_INK),
                nameStart, span.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            span.append("：")
            span.append(commentSpannable(sub.content, sub.emotes))
            text = span
            textSize = 12.5f
            setTextColor(COLOR_MUTED)
            setLineSpacing(dpf(1.5f), 1f)
            setPadding(0, dp(2), 0, dp(2))
            // Preview rows are tappable via the parent floor; keep them non-selectable
            // so a long-press does not steal the open-sheet gesture.
            setTextIsSelectable(false)
            // Long sub replies must not fill the floor: collapse them.
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        attachEmoteSpans(line)
        return line
    }

    /** Opens the reply bottom sheet: the root (楼主) comment on top, replies below. */
    private fun showReplySheet(root: VideoComment) {
        if (root.id <= 0L) return
        ReplySheetDialog(root).show()
    }

    /** Bottom sheet listing the root comment first, then its replies with full rows. */
    private inner class ReplySheetDialog(private val root: VideoComment) : Dialog(this@VideoDetailActivity) {
        private var page = 1
        private var hasMore = true
        private var loading = false
        private var loadedRoot = false
        private var hasReplyRows = false
        private var pagesToLoad = 0
        private var generation = 0
        private var closing = false
        private var panelHeightPx = 0
        private var panelTopPx = 0
        private var panelPositioned = false
        private var openAnimationStarted = false
        private lateinit var overlayView: FrameLayout
        private val seenReplyIds = HashSet<Long>()
        private lateinit var listBox: LinearLayout
        private lateinit var scroll: ScrollView
        private lateinit var panel: LinearLayout
        private var skeletonBox: LinearLayout? = null
        private var skeletonAnimator: ValueAnimator? = null
        private var retryView: TextView? = null
        private val sheetTopY: Int
            get() {
                val anchor = infoTabBarAnchor ?: return dp(64)
                val pos = IntArray(2)
                anchor.getLocationOnScreen(pos)
                // The panel begins directly below the fixed 简介/评论 tab row.
                return (pos[1] + anchor.height).coerceIn(0, resources.displayMetrics.heightPixels - dp(260))
            }

        private val sheetHeight: Int
            get() = (resources.displayMetrics.heightPixels - sheetTopY)
                .coerceAtLeast(dp(260))

        init {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            window?.setBackgroundDrawable(ColorDrawable(0x00000000))
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }

        override fun onStart() {
            super.onStart()
            // The dialog itself is a transparent full-screen host; the panel is
            // the only visible surface and is anchored to the physical bottom.
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            window?.setGravity(Gravity.BOTTOM)
            window?.setBackgroundDrawable(ColorDrawable(0x00000000))
            window?.setWindowAnimations(0)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val overlay = FrameLayout(this@VideoDetailActivity).apply {
                setBackgroundColor(0x2E000000)
                setOnClickListener { dismissWithAnim() }
            }
            overlayView = overlay
            panel = LinearLayout(this@VideoDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedTop(COLOR_BACKGROUND, dp(18))
                // Keep the panel out of the first frame. It is positioned from
                // its measured height below, then only translated upward.
                visibility = View.INVISIBLE
                translationY = 0f
            }
            // Header: title + close.
            panel.addView(LinearLayout(this@VideoDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(6))
                addView(TextView(this@VideoDetailActivity).apply {
                    text = "全部回复 · ${root.replyCount}"
                    textSize = 15f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(COLOR_INK)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@VideoDetailActivity).apply {
                    text = "✕"; textSize = 18f; setTextColor(COLOR_MUTED)
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    setOnClickListener { dismissWithAnim() }
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            scroll = ScrollView(this@VideoDetailActivity).apply {
                isFillViewport = false
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            listBox = LinearLayout(this@VideoDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(14), dp(12))
            }
            scroll.addView(listBox, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            panel.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            overlay.addView(panel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, sheetHeight, Gravity.BOTTOM))
            setContentView(overlay)
            // Position the panel's top edge exactly below the fixed tab bar in
            // the dialog coordinate space, then animate only translationY.
            panel.post {
                val screenHeight = resources.displayMetrics.heightPixels
                val location = IntArray(2)
                overlay.getLocationOnScreen(location)
                val overlayTop = location[1]
                val overlayHeight = overlay.height.coerceAtLeast(dp(260))
                panelTopPx = (sheetTopY - overlayTop)
                    .coerceIn(0, (overlayHeight - dp(260)).coerceAtLeast(0))
                panelHeightPx = (overlayHeight - panelTopPx).coerceAtLeast(dp(260))
                panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                    width = FrameLayout.LayoutParams.MATCH_PARENT
                    height = panelHeightPx
                    gravity = Gravity.TOP or Gravity.START
                }
                panel.translationY = (panelTopPx + panelHeightPx).toFloat()
                panel.visibility = View.VISIBLE
                panelPositioned = true
                if (!openAnimationStarted) {
                    openAnimationStarted = true
                    panel.animate().translationY(panelTopPx.toFloat()).setDuration(220)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
            }
            // Start the next pages before the user reaches the end. The loading
            // state is represented by skeleton rows inside the list, never by a
            // fixed "正在加载更多" footer.
            scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val child = scroll.getChildAt(0) ?: return@setOnScrollChangeListener
                if (scrollY >= child.height - scroll.height - dp(260)) {
                    maybePreloadReplies()
                }
            }
            loadReplies(maxPages = 2)
        }

        private fun loadReplies(maxPages: Int = 1) {
            if (!hasMore) return
            pagesToLoad = maxOf(pagesToLoad, maxPages.coerceIn(1, 2))
            retryView?.let {
                listBox.removeView(it)
                retryView = null
            }
            showSkeleton()
            if (!loading) requestNextReplyPage()
        }

        /** Requests pages serially so replies stay ordered and Bilibili is not hit concurrently. */
        private fun requestNextReplyPage() {
            if (loading || !hasMore || pagesToLoad <= 0) {
                if (!loading) hideSkeleton()
                return
            }
            loading = true
            val requestGeneration = generation
            val requestPage = page
            val aid = currentDetail?.aid ?: 0L
            BiliApi.fetchSubReplies(aid, root.id, requestPage) { list, next, error ->
                if (isFinishing || isDestroyed || requestGeneration != generation) return@fetchSubReplies
                loading = false
                if (error != null) {
                    pagesToLoad = 0
                    hideSkeleton()
                    showReplyRetry()
                    return@fetchSubReplies
                }
                if (!loadedRoot) {
                    loadedRoot = true
                    listBox.addView(buildSheetRootItem(root), 0)
                    listBox.addView(View(this@VideoDetailActivity).apply { setBackgroundColor(COLOR_BORDER) },
                        1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                            topMargin = dp(10)
                            bottomMargin = dp(4)
                        })
                }
                list.forEach { sub ->
                    if (sub.id <= 0L || !seenReplyIds.add(sub.id)) return@forEach
                    val insertAt = skeletonBox?.let { listBox.indexOfChild(it) }
                        ?.takeIf { it >= 0 } ?: listBox.childCount
                    if (hasReplyRows) {
                        listBox.addView(View(this@VideoDetailActivity).apply { setBackgroundColor(COLOR_BORDER) },
                            insertAt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
                    }
                    val rowInsertAt = skeletonBox?.let { listBox.indexOfChild(it) }
                        ?.takeIf { it >= 0 } ?: listBox.childCount
                    listBox.addView(buildSheetReplyItem(sub), rowInsertAt)
                    hasReplyRows = true
                }
                hasMore = next > 0
                page = if (next > 0) next else page + 1
                pagesToLoad = (pagesToLoad - 1).coerceAtLeast(0)
                if (hasMore && pagesToLoad > 0) {
                    // Keep the skeleton visible between pages, then continue on
                    // the main thread so rows are appended in API order.
                    listBox.post { requestNextReplyPage() }
                } else {
                    hideSkeleton()
                }
            }
        }

        private fun maybePreloadReplies() {
            if (hasMore) loadReplies(maxPages = 2)
        }

        /** Three compact placeholder rows act like a list skeleton, not a footer label. */
        private fun showSkeleton() {
            if (skeletonBox != null) return
            val box = LinearLayout(this@VideoDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            repeat(3) { index ->
                box.addView(buildReplySkeletonItem(), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply {
                    if (index > 0) topMargin = dp(8)
                })
            }
            skeletonBox = box
            listBox.addView(box)
            skeletonAnimator?.cancel()
            skeletonAnimator = ValueAnimator.ofFloat(0.42f, 0.9f).apply {
                duration = 760L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { box.alpha = it.animatedValue as Float }
                start()
            }
        }

        private fun hideSkeleton() {
            skeletonAnimator?.cancel()
            skeletonAnimator = null
            skeletonBox?.let { listBox.removeView(it) }
            skeletonBox = null
        }

        private fun showReplyRetry() {
            if (retryView != null) return
            retryView = TextView(this@VideoDetailActivity).apply {
                text = "加载失败，点击重试"
                textSize = 12f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(16))
                setOnClickListener {
                    listBox.removeView(this)
                    retryView = null
                    loadReplies(maxPages = 1)
                }
            }
            listBox.addView(retryView)
        }

        /** A single row with avatar/name/content bars for the in-list loading effect. */
        private fun buildReplySkeletonItem(): View {
            val row = LinearLayout(this@VideoDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                background = rounded(COLOR_SOFT_FILL, dp(10))
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            val avatar = View(this@VideoDetailActivity).apply {
                background = rounded(COLOR_SOFT_FILL_STRONG, dp(18))
            }
            row.addView(avatar, LinearLayout.LayoutParams(dp(30), dp(30)))
            val bars = LinearLayout(this@VideoDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(22), 0)
                addView(View(this@VideoDetailActivity).apply {
                    background = rounded(COLOR_SOFT_FILL_STRONG, dp(5))
                }, LinearLayout.LayoutParams(dp(92), dp(10)))
                addView(View(this@VideoDetailActivity).apply {
                    background = rounded(COLOR_SOFT_FILL, dp(5))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)).apply {
                    topMargin = dp(9)
                })
                addView(View(this@VideoDetailActivity).apply {
                    background = rounded(COLOR_SOFT_FILL, dp(5))
                }, LinearLayout.LayoutParams(dp(150), dp(10)).apply {
                    topMargin = dp(7)
                })
            }
            row.addView(bars, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            return row
        }

        /** Root comment row: avatar, name + 楼主 tag, time, body, like. */
        private fun buildSheetRootItem(comment: VideoComment): View {
            return buildSheetItem(comment, 36, showRootTag = true, reply = false)
        }

        /** One reply row inside the sheet: indented, tinted, smaller avatar. */
        private fun buildSheetReplyItem(comment: VideoComment): View {
            return buildSheetItem(comment, 30, showRootTag = false, reply = true)
        }

        private fun buildSheetItem(comment: VideoComment, avatarSize: Int, showRootTag: Boolean, reply: Boolean): View {
            val row = LinearLayout(this@VideoDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                if (reply) {
                    // Replies sit on a soft tinted block, slightly indented, so the
                    // root comment (楼主) visually stands apart from the in-floor ones.
                    background = rounded(
                        (COLOR_MUTED and 0x00FFFFFF.toInt()) or 0x14000000.toInt(), dp(10))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                } else {
                    setPadding(0, dp(8), 0, dp(8))
                }
            }
            val avatar = RoundImageView(this@VideoDetailActivity).apply { setBackgroundColor(COLOR_COVER) }
            if (comment.avatar.isNotBlank()) {
                CoverLoader.load(avatar, comment.avatar + "@52w_52h_1c.webp")
            }
            row.addView(avatar, LinearLayout.LayoutParams(dp(avatarSize), dp(avatarSize)))
            val body = LinearLayout(this@VideoDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            val nameLine = LinearLayout(this@VideoDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            nameLine.addView(TextView(this@VideoDetailActivity).apply {
                text = comment.user
                textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (reply) COLOR_MUTED else COLOR_INK)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (showRootTag) {
                nameLine.addView(TextView(this@VideoDetailActivity).apply {
                    text = "楼主"
                    textSize = 10f; setTextColor(COLOR_ON_ROSE)
                    background = rounded(COLOR_ROSE, dp(8))
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(6)
                })
            }
            body.addView(nameLine, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            body.addView(TextView(this@VideoDetailActivity).apply {
                text = formatCommentTime(comment.publishedAt)
                textSize = 11f; setTextColor(COLOR_MUTED)
                setPadding(0, dp(3), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            body.addView(
                buildExpandableCommentText(comment.content, comment.emotes, textSizeSp = 14f),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            if (comment.pictures.isNotEmpty()) {
                body.addView(buildPicturesRow(comment.pictures), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            body.addView(LikeButton(this@VideoDetailActivity, comment), LinearLayout.LayoutParams(dp(64), dp(26)).apply {
                topMargin = dp(7)
            })
            row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }

        private fun dismissWithAnim() {
            if (!isShowing || closing) return
            closing = true
            generation++
            pagesToLoad = 0
            hideSkeleton()
            panel.animate().translationY((panelTopPx + panelHeightPx).toFloat()).setDuration(180)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { dismiss() }.start()
        }

        override fun onStop() {
            generation++
            pagesToLoad = 0
            if (::listBox.isInitialized) hideSkeleton()
            super.onStop()
        }

        override fun onBackPressed() {
            dismissWithAnim()
        }
    }

    /** Fullscreen image viewer with a translucent download button (static + animated). */
    private fun showImagesDialog(urls: List<String>, startIndex: Int) {
        val images = urls.filter { it.isNotBlank() }.distinct()
        if (images.isEmpty()) return
        var current = startIndex.coerceIn(0, images.size - 1)
        var animating = false
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xE6000000.toInt())
        }
        val slots = ArrayList<ZoomableImageView>(3).apply {
            repeat(3) { add(ZoomableImageView(this@VideoDetailActivity)) }
        }
        fun rotateSlotWindow(direction: Int) {
            if (direction > 0) {
                // Left swipe: the old next slot is the new current slot.
                slots.add(slots.removeAt(0))
            } else if (direction < 0) {
                // Right swipe: the old previous slot is the new current slot.
                slots.add(0, slots.removeAt(slots.lastIndex))
            }
        }
        var appliedDelta = 0f
        // Picture counter, e.g. "2/4", bottom centre.
        val counter = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = rounded(0x66000000.toInt(), dp(14))
        }
        lateinit var renderPage: () -> Unit
        slots.forEach { slot ->
            root.addView(slot, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            slot.visibility = View.GONE
            // Drag follows the finger; release decides flip vs bounce-back.
            slot.onPagerDrag = { delta ->
                val w = root.width.toFloat().coerceAtLeast(1f)
                // Edge pages: dragging towards the empty side gets a springy
                // resistance instead of revealing a blank page.
                val limited = when {
                    current == 0 && delta > 0f -> delta * 0.28f
                    current == images.size - 1 && delta < 0f -> delta * 0.28f
                    else -> delta
                }
                appliedDelta = limited
                slots.forEachIndexed { i, s ->
                    s.translationX = (i - 1) * w + limited
                }
            }
            slot.onPagerRelease = { delta, velocityX ->
                if (!animating) {
                    val w = root.width.toFloat().coerceAtLeast(1f)
                    // Light swipe: 10% of the screen width, or a quick flick
                    // (velocity-based) flips the page like the feed pager.
                    // Clamped to the picture range: 0/2 and 3/2 must never exist.
                    val next = when {
                        delta < -w * 0.10f || (velocityX < -500f && delta < 0f) -> (current + 1).coerceAtMost(images.size - 1)
                        delta > w * 0.10f || (velocityX > 500f && delta > 0f) -> (current - 1).coerceAtLeast(0)
                        else -> current
                    }
                    val target = when {
                        next > current -> -w
                        next < current -> w
                        else -> 0f
                    }
                    animating = true
                    val startDelta = when {
                        current == 0 && delta > 0f -> delta * 0.28f
                        current == images.size - 1 && delta < 0f -> delta * 0.28f
                        else -> delta
                    }
                    val anim = ValueAnimator.ofFloat(startDelta, target)
                    anim.duration = 200
                    anim.interpolator = DecelerateInterpolator()
                    anim.addUpdateListener {
                        val v = it.animatedValue as Float
                        slots.forEachIndexed { i, s -> s.translationX = (i - 1) * w + v }
                    }
                    var settled = false
                    fun settle() {
                        if (settled) return
                        settled = true
                        if (next != current) {
                            val direction = if (next > current) 1 else -1
                            current = next
                            rotateSlotWindow(direction)
                        }
                        renderPage()
                        animating = false
                    }
                    anim.addListener(object : Animator.AnimatorListener {
                        override fun onAnimationStart(animation: Animator) {}
                        override fun onAnimationRepeat(animation: Animator) {}
                        override fun onAnimationCancel(animation: Animator) = settle()
                        override fun onAnimationEnd(animation: Animator) = settle()
                    })
                    anim.start()
                }
            }
        }
        renderPage = {
            val w = root.width.toFloat().coerceAtLeast(1f)
            listOf(current - 1, current, current + 1).forEachIndexed { slotIdx, imageIndex ->
                val slot = slots[slotIdx]
                // Slot anchors: previous page sits one width left, next page
                // one width right, so drag/flip animations look like a pager.
                slot.translationX = (slotIdx - 1) * w
                if (imageIndex in images.indices) {
                    // Centre page plays; neighbours stay VISIBLE for swipe but
                    // animate=false freezes their GIF loop so only one Movie
                    // burns CPU at a time.
                    slot.visibility = View.VISIBLE
                    CoverLoader.load(
                        slot,
                        images[imageIndex].substringBefore('@'),
                        original = true,
                        clearBeforeLoad = false,
                        animate = slotIdx == 1
                    )
                    if (slotIdx == 1) {
                        slot.invalidate()
                        slot.drawable?.invalidateSelf()
                    }
                } else {
                    slot.visibility = View.GONE
                }
            }
            counter.text = "${current + 1}/${images.size}"
        }
        root.addView(counter, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(28)
        })
        // Save to system gallery, top-right corner.
        val save = TextView(this).apply {
            text = "存相册"
            textSize = 13f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(0x66000000.toInt(), dp(18))
            isClickable = true
            setOnClickListener { downloadImage(images[current].substringBefore('@')) }
        }
        root.addView(save, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply {
            topMargin = dp(16); rightMargin = dp(16)
        })
        val close = TextView(this).apply {
            text = "×"
            textSize = 16f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0x66000000.toInt(), dp(16))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(close, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply {
            topMargin = dp(16); leftMargin = dp(16)
        })
        dialog.setContentView(root)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0x00000000))
        dialog.show()
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(200).start()
        // Must wait for the dialog to lay out: right after show() the root
        // width is still 0, and a 0-width pager stacks all three slots at the
        // origin with the LAST slot (current+1) on top - which is exactly why
        // tapping picture 1 opened picture 2.
        root.post { renderPage() }
    }

    /**
     * Fullscreen image viewer with pinch-zoom, pan and double-tap.
     *
     * Rewritten with the battle-tested PhotoView algorithm:
     * - the image is loaded as a static BitmapDrawable, so dimensions always
     *   come from the bitmap itself (never the unreliable intrinsic of
     *   AnimatedImageDrawable which caused "zoomed into the centre").
     * - min/max scale are derived from the fit scale, not hardcoded.
     * - fit is (re)applied when the drawable arrives and when the view is
     *   laid out, whichever comes last.
     */
    private inner class ZoomableImageView(context: Context) : ImageView(context) {
        private val baseMatrix = Matrix()
        private val matrix = Matrix()
        private var minScale = 1f
        private var maxScale = 5f

        private val scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    var factor = detector.scaleFactor
                    val next = currentScale * factor
                    if (next > maxScale) factor = maxScale / currentScale
                    if (next < minScale) factor = minScale / currentScale
                    matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    clampMatrix()
                    imageMatrix = matrix
                    return true
                }
            })

        private val tapDetector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (currentScale > minScale * 1.2f) resetToFit() else zoomTo(minScale * 2.5f, e.x, e.y)
                    return true
                }
                override fun onScroll(e1: MotionEvent?, e2: MotionEvent,
                                      distanceX: Float, distanceY: Float): Boolean {
                    if (scaleDetector.isInProgress) return false
                    // Zoomed in: pan the image. (Fit-state paging is handled
                    // directly in onTouchEvent so the drag stays under the finger.)
                    if (currentScale > minScale * 1.05f) {
                        matrix.postTranslate(-distanceX, -distanceY)
                        clampMatrix()
                        imageMatrix = matrix
                        return true
                    }
                    return false
                }
            })

        /** Pager hooks used by the multi-image viewer (fit state horizontal drags). */
        var onPagerDrag: ((Float) -> Unit)? = null
        var onPagerRelease: ((delta: Float, velocityX: Float) -> Unit)? = null
        private var pagerMode = 0 // 0 undecided, 1 horizontal drag, 2 other
        private var downX = 0f
        private var downY = 0f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val velocityTracker = VelocityTracker.obtain()

        private val currentScale: Float
            get() {
                val v = FloatArray(9)
                matrix.getValues(v)
                return v[Matrix.MSCALE_X]
            }

        init {
            scaleType = ImageView.ScaleType.MATRIX
            isClickable = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (drawable != null) resetToFit()
        }

        override fun setImageDrawable(drawable: Drawable?) {
            super.setImageDrawable(drawable)
            if (drawable != null) resetToFit()
        }

        private fun imageWidth(): Float =
            (drawable as? BitmapDrawable)?.bitmap?.width?.toFloat()
                ?: drawable?.intrinsicWidth?.toFloat() ?: 0f

        private fun imageHeight(): Float =
            (drawable as? BitmapDrawable)?.bitmap?.height?.toFloat()
                ?: drawable?.intrinsicHeight?.toFloat() ?: 0f

        private fun resetToFit() {
            if (width == 0 || height == 0) {
                // View not laid out yet; retry after layout.
                post { resetToFit() }
                return
            }
            val vw = width.toFloat()
            val vh = height.toFloat()
            val dw = imageWidth()
            val dh = imageHeight()
            if (dw <= 0f || dh <= 0f) return // retried from onSizeChanged
            val fit = minOf(vw / dw, vh / dh)
            minScale = fit
            maxScale = maxOf(fit * 5f, 2f)
            baseMatrix.reset()
            baseMatrix.postScale(fit, fit)
            baseMatrix.postTranslate((vw - dw * fit) / 2f, (vh - dh * fit) / 2f)
            matrix.set(baseMatrix)
            imageMatrix = matrix
        }

        private fun zoomTo(target: Float, fx: Float, fy: Float) {
            val factor = target / currentScale
            matrix.postScale(factor, factor, fx, fy)
            clampMatrix()
            imageMatrix = matrix
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            velocityTracker.recycle()
        }

        private fun clampMatrix() {
            val vw = width.toFloat().coerceAtLeast(1f)
            val vh = height.toFloat().coerceAtLeast(1f)
            val v = FloatArray(9)
            matrix.getValues(v)
            val dw = imageWidth() * v[Matrix.MSCALE_X]
            val dh = imageHeight() * v[Matrix.MSCALE_Y]
            v[Matrix.MTRANS_X] = if (dw <= vw) (vw - dw) / 2f
            else v[Matrix.MTRANS_X].coerceIn(vw - dw, 0f)
            v[Matrix.MTRANS_Y] = if (dh <= vh) (vh - dh) / 2f
            else v[Matrix.MTRANS_Y].coerceIn(vh - dh, 0f)
            matrix.setValues(v)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            velocityTracker.addMovement(event)
            // Fit state: track single-finger drags directly so the picture
            // follows the finger; release flips the page or bounces back.
            if (!scaleDetector.isInProgress && currentScale <= minScale * 1.05f) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pagerMode = 0
                        downX = event.x
                        downY = event.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (pagerMode == 0) {
                            val dx = event.x - downX
                            val dy = event.y - downY
                            if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                                pagerMode = 1
                            } else if (Math.abs(dy) > touchSlop) {
                                pagerMode = 2
                            }
                        }
                        if (pagerMode == 1) {
                            onPagerDrag?.invoke(event.x - downX)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (pagerMode == 1) {
                            velocityTracker.computeCurrentVelocity(1000)
                            onPagerRelease?.invoke(event.x - downX, velocityTracker.xVelocity)
                            pagerMode = 0
                            return true
                        }
                        pagerMode = 0
                    }
                }
            }
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            return super.onTouchEvent(event)
        }
    }

    private inner class EmoteImageSpan(
        private val url: String,
        private val sizePx: Int
    ) : ReplacementSpan() {
        private var drawable: Drawable? = null
        private var loading = false

        fun attach(tv: TextView) {
            if (drawable != null || loading) return
            loading = true
            EMOTE_POOL.execute {
                try {
                    // Bilibili emote URLs sometimes already carry a CDN size
                    // suffix ("@..."); appending another one would 404.
                    val loadUrl = if (url.contains("@")) url else "$url@48w_48h"
                    val bytes = fetchBytes(loadUrl) ?: return@execute
                    val bmp = decodeEmote(bytes)
                    if (bmp == null) {
                        Log.w("YuiBili", "emote decode failed: $loadUrl")
                        return@execute
                    }
                    val d = BitmapDrawable(resources, bmp)
                    d.setBounds(0, 0, sizePx, sizePx)
                    runOnUiThread {
                        drawable = d
                        // requestLayout forces a full measure/layout/draw pass;
                        // a bare invalidate() can be swallowed while the
                        // TextView is mid-layout, which is why emotes only
                        // appeared after returning from the task switcher.
                        tv.requestLayout()
                        tv.invalidate()
                    }
                } catch (error: Exception) {
                    Log.w("YuiBili", "emote load failed: $url -> ${error.message}")
                }
            }
        }

        private fun fetchBytes(loadUrl: String): ByteArray? {
            val connection = URL(loadUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", NETWORK_USER_AGENT)
                connection.setRequestProperty("Referer", "https://www.bilibili.com/")
                connection.connect()
                if (connection.responseCode !in 200..299) return null
                return connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }

        /** Decodes static frames; animated webp/gif fall back to the first
         *  frame via ImageDecoder (BitmapFactory returns null for them). */
        private fun decodeEmote(bytes: ByteArray): Bitmap? {
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                    return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } catch (_: Exception) {
                    // fall through to BitmapFactory
                }
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: FontMetricsInt?): Int {
            if (fm != null) {
                val lineH = fm.descent - fm.ascent
                if (sizePx > lineH) {
                    val extra = sizePx - lineH
                    fm.ascent -= extra / 2
                    fm.descent += extra - extra / 2
                }
            }
            return sizePx
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            val d = drawable ?: return
            val cy = (top + bottom) / 2f
            canvas.save()
            canvas.translate(x, cy - sizePx / 2f)
            d.draw(canvas)
            canvas.restore()
        }
    }

    /** Replaces emote codes in [content] with inline image spans (text flow preserved). */
    private fun commentSpannable(content: String, emotes: Map<String, String>): SpannableString {
        val spannable = SpannableString(content)
        if (emotes.isEmpty()) return spannable
        val sizePx = dp(18)
        emotes.forEach { (code, url) ->
            if (code.isBlank() || url.isBlank()) return@forEach
            var index = content.indexOf(code)
            while (index >= 0) {
                spannable.setSpan(
                    EmoteImageSpan(url, sizePx), index, index + code.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                index = content.indexOf(code, index + code.length)
            }
        }
        return spannable
    }

    /** Starts async loading for every inline emote span of a TextView. */
    private fun attachEmoteSpans(tv: TextView) {
        val spanned = tv.text as? Spanned ?: return
        spanned.getSpans(0, spanned.length, EmoteImageSpan::class.java).forEach { it.attach(tv) }
    }

    /** Save the image to the gallery (MediaStore on API 29+, legacy path below). */
    private fun downloadImage(url: String) {
        if (url.isBlank()) return
        Thread {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", NETWORK_USER_AGENT)
                    setRequestProperty("Referer", "https://www.bilibili.com/")
                }
                val bytes = connection.inputStream.use { it.readBytes() }
                val extension = url.substringAfterLast('.', "png").substringBefore('?').take(4)
                val fileName = "yuibili_${System.currentTimeMillis()}.$extension"
                val saved = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val resolver = contentResolver
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE,
                            if (extension == "gif") "image/gif" else "image/webp")
                        put(MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES + "/YuiBili")
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        true
                    } else false
                } else {
                    val dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES)
                    val file = java.io.File(dir, fileName)
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    true
                }
                runOnUiThread {
                    showSnackbar(if (saved) "已保存到相册（Pictures/YuiBili）" else "保存失败")
                }
            } catch (error: Exception) {
                runOnUiThread { showSnackbar("下载失败：${error.message ?: "网络错误"}") }
            }
        }.start()
    }

    private fun formatCommentTime(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "时间未知"
        val date = Date(epochSeconds * 1000L)
        val now = System.currentTimeMillis()
        val diff = now - date.time
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L}分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L}小时前"
            diff < 7L * 86_400_000L -> "${diff / 86_400_000L}天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(date)
        }
    }

    private fun loadPlayer() {
        val generation = ++playbackGeneration
        retryScheduled = false
        exoPlayer?.let { old ->
            pendingResumePositionMs = maxOf(pendingResumePositionMs, playerGlobalPosition(old))
            playerView.player = null
            exoPlayer = null
            old.release()
        }
        playerSpinner.visibility = View.VISIBLE
        playerError.visibility = View.GONE
        if (currentCid <= 0L) { showError("无法获取视频信息，点这里重试"); return }
        BiliPlayUrl.fetchPlayUrl(currentBvid, currentCid, effectiveQuality()) { stream, error ->
            if (isFinishing || isDestroyed || generation != playbackGeneration) return@fetchPlayUrl
            handler.post {
                if (generation != playbackGeneration) return@post
                val chargeOnly = currentDetail?.charge == true
                if (stream != null) {
                    startExoPlayer(stream, generation)
                    // 充电/付费视频：未解锁时 playurl 只给试看时长。rights 字段可能因
                    // 接口版本差异取不到，因此同时用“流时长明显小于视频总时长”兜底判断。
                    val totalMs = (currentDetail?.duration ?: 0).toLong() * 1000L
                    val looksPreview = stream.durationMs > 0L && totalMs > 0L &&
                        stream.durationMs < totalMs * 6 / 10
                    if (chargeOnly || looksPreview) {
                        showSnackbar("该视频为充电/付费视频，当前仅可试看部分内容，解锁后可在 B 站客户端完整观看")
                    }
                } else {
                    val err = error ?: "未知错误"
                    val lockedHint = chargeOnly || err.contains("充电") || err.contains("付费") ||
                        err.contains("大会员") || err.contains("权限")
                    if (lockedHint) {
                        showError("该视频为充电/付费视频，未解锁无法播放：$err\n\n点这里重试")
                    } else {
                        showError("播放失败：$err\n\n点这里重试")
                    }
                }
            }
        }
    }

    private fun startExoPlayer(stream: BiliPlayUrl.DashStream, generation: Int = playbackGeneration) {
        if (generation != playbackGeneration || isFinishing || isDestroyed) return
        currentStream = stream
        playerView.player = null
        exoPlayer?.release(); exoPlayer = null
        val okClient = mediaOkClient
        playerSpinner.visibility = View.VISIBLE
        playerError.visibility = View.GONE

        // Do not probe every CDN/quality before playback. That approach adds several
        // round trips, can pick a random lower quality, and makes first frame slow.
        // Keep one requested compatible track and let Media3 use its CDN backups.
        val selected = selectStableStream(stream)
        handler.post {
            if (generation == playbackGeneration && !isFinishing && !isDestroyed) {
                doStartExoPlayer(selected, okClient, generation)
            }
        }
    }

    private fun selectStableStream(stream: BiliPlayUrl.DashStream): BiliPlayUrl.DashStream {
        if (!stream.isDash) return stream
        val effective = effectiveQuality()
        val requestedOrLower = stream.videoTracks
            .filter { it.id <= effective }
            .ifEmpty { stream.videoTracks }
        val selectedQuality = requestedOrLower.maxOfOrNull { it.id }
            ?: stream.videoTracks.maxOfOrNull { it.id }
        val qualityTracks = requestedOrLower.filter { it.id == selectedQuality }
        // AVC/H.264 is the widest hardware-compatible format on Android 6+; only
        // use HEVC/AV1 when Bilibili did not return AVC for the selected quality.
        val video = qualityTracks.minWithOrNull(
            compareBy<BiliPlayUrl.DashVideoTrack> { codecStabilityRank(it.codecs) }
                .thenByDescending { it.bandwidth }
        ) ?: stream.videoTracks.first()
        val audio = stream.audioTracks
            .filter { it.codecs.startsWith("mp4a", true) }
            .maxByOrNull { it.bandwidth }
            ?: stream.audioTracks.maxByOrNull { it.bandwidth }
        Log.i("YuiBiliPlayer", "stable-select video=${video.id}/${video.codecs}, audio=${audio?.id}")
        return stream.copy(
            videoTracks = listOf(video),
            audioTracks = listOfNotNull(audio),
            actualQuality = video.id
        )
    }

    private fun codecStabilityRank(codec: String): Int = when {
        codec.startsWith("avc", true) -> 0
        codec.startsWith("hev", true) || codec.startsWith("hvc", true) -> 1
        codec.startsWith("av01", true) -> 2
        else -> 3
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun doStartExoPlayer(
        stream: BiliPlayUrl.DashStream,
        okClient: OkHttpClient,
        generation: Int
    ) {
        if (generation != playbackGeneration || isFinishing || isDestroyed) return
        playingStream = stream
        // Fresh playback: clear ended/replay and boost state from any previous player.
        endedState = false
        playPauseBtn.setEnded(false)
        showReplay(false)
        cancelLongPressBoost()
        val mediaSource = buildMediaSource(stream, okClient)
        // Keep enough data for weak mobile networks without caching the whole video.
        // Start quickly, but require a healthier buffer after a rebuffer to stop the
        // repeated play/pause loop users experience as "playing slowly".
        val renderersFactory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // Deep buffering for long videos: short clips used to fit
                    // entirely inside the old 12s/45s window while long ones
                    // starved between refills. Start playback after 5s, keep
                    // downloading until 90s (or 64MB) buffered, then refill
                    // when it drops below 30s. Rebuffer waits for 10s.
                    .setBufferDurationsMs(30_000, 90_000, 5_000, 10_000)
                    .setTargetBufferBytes(64 * 1024 * 1024)
                    .setBackBuffer(8_000, false)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
        playerView.player = player
        player.setMediaSource(mediaSource)
        player.repeatMode = Player.REPEAT_MODE_OFF
        if (pendingResumePositionMs > 0L) seekPlayerGlobal(player, pendingResumePositionMs)
        player.playWhenReady = userWantsPlayback && activityResumed
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                when (state) {
                    Player.STATE_READY -> {
                        playerSpinner.visibility = View.GONE
                        playerError.visibility = View.GONE
                        pendingResumePositionMs = 0L
                        retryScheduled = false
                        playPauseBtn.setEnded(false)
                        playPauseBtn.setPlaying(player.isPlaying)
                        if (!isSeeking && player.isPlaying) { showReplay(false); scheduleAutoHide() }
                        updateQualityState(playingStream ?: stream)
                    }
                    Player.STATE_BUFFERING -> if (!isSeeking) playerSpinner.visibility = View.VISIBLE
                    Player.STATE_ENDED -> {
                        playerSpinner.visibility = View.GONE
                        cancelLongPressBoost()
                        endedState = true
                        playPauseBtn.setEnded(true)
                        playPauseBtn.setPlaying(false)
                        showReplay(true)
                        showControls()
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                if (isPlaying) {
                    // Report playback to the Bilibili watch history: on start and
                    // every 30 seconds while playing.
                    reportPlayHistory(status = 3)
                    handler.removeCallbacks(historyTick)
                    handler.postDelayed(historyTick, 30_000L)
                    endedState = false
                    playPauseBtn.setEnded(false)
                    playPauseBtn.setPlaying(true)
                    showReplay(false)
                    scheduleAutoHide()
                } else {
                    playPauseBtn.setEnded(false)
                    playPauseBtn.setPlaying(false)
                    showControls()
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                recoverPlayback(stream, player, error, generation)
            }
        })
        player.prepare()
        exoPlayer = player
        handler.removeCallbacks(progressUpdater)
        handler.post(progressUpdater)
    }

    /**
     * Build the Media3 source for a stream. DASH streams are served through a
     * locally generated MPD (file name includes the target quality so a
     * quality switch never races with the manifest currently being read).
     */
    private fun buildMediaSource(stream: BiliPlayUrl.DashStream, okClient: OkHttpClient): MediaSource {
        val httpFactory = OkHttpDataSource.Factory(okClient)
            .setUserAgent(CDN_USER_AGENT)
            .setDefaultRequestProperties(
                buildMap {
                    put("Referer", "https://www.bilibili.com/")
                    put("Origin", "https://www.bilibili.com")
                    // CDN segment requests must only carry the anonymous buvid
                    // fingerprint. CURRENT_FNVAL / SESSDATA sent to CDN hosts make
                    // nodes reject the request (403/503) whenever the fingerprint
                    // or session is not perfectly valid.
                    val cdnCookie = BiliPlayUrl.cdnCookieHeader()
                    if (cdnCookie.isNotBlank()) put("Cookie", cdnCookie)
                }
            )
        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(this, httpFactory)
        val loadPolicy = DefaultLoadErrorHandlingPolicy(4)
        return if (stream.isDash) {
            val manifest = BiliDashManifest.build(stream)
            val manifestFile = File(cacheDir, "bili_dash_${currentBvid}_${currentCid}_${requestedQuality}.mpd")
            manifestFile.writeText(manifest)
            val mediaItem = MediaItem.Builder().setUri(Uri.fromFile(manifestFile)).setMimeType(MimeTypes.APPLICATION_MPD).build()
            DashMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadPolicy)
                .createMediaSource(mediaItem)
        } else {
            val segments = stream.durlSegments.ifEmpty {
                listOf(BiliPlayUrl.DurlSegment(
                    urls = stream.fallbackUrls.ifEmpty { listOf(stream.videoUrl) },
                    lengthMs = stream.durationMs,
                    sizeBytes = 0L
                ))
            }
            val sources = segments.mapNotNull { segment ->
                val url = segment.urls.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                val mediaItem = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_MP4).build()
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadPolicy)
                    .createMediaSource(mediaItem)
            }
            when (sources.size) {
                0 -> throw IllegalStateException("没有可用的单文件播放地址")
                1 -> sources.first()
                else -> ConcatenatingMediaSource(true, *sources.toTypedArray())
            }
        }
    }

    /**
     * Quality the user is allowed to play right now. Guests are capped at
     * 480p (720p and above require login, 1080p+ requires a big member).
     */
    private fun effectiveQuality(): Int {
        if (BiliSessionStore.isLoggedIn()) return requestedQuality
        return minOf(requestedQuality, BiliPlayUrl.QUALITY_480)
    }

    /**
     * Switch quality. When the target tier is already present in the current
     * DASH response we rebuild only the local MPD and hot-swap the media
     * source on the existing player (no network round-trip, near-instant).
     * Higher tiers require a fresh playurl request.
     */
    private fun switchQuality(quality: Int) {
        if (quality == requestedQuality) return
        requestedQuality = quality
        saveQualityPreference(quality)
        playRetryCount = 0
        lastPlaybackError = null
        val stream = currentStream ?: return
        pendingResumePositionMs = exoPlayer?.let { playerGlobalPosition(it) } ?: 0L
        if (stream.isDash && stream.videoTracks.any { it.id == quality }) {
            val selected = selectStableStream(stream)
            Log.i("YuiBiliPlayer", "quality switch local: ${qualityName(quality)}")
            applyMediaSource(selected)
        } else {
            Log.i("YuiBiliPlayer", "quality switch refetch: ${qualityName(quality)}")
            loadPlayer()
        }
    }

    /** Swap the media source on the live player and resume at the saved position. */
    private fun applyMediaSource(stream: BiliPlayUrl.DashStream) {
        val player = exoPlayer ?: return
        playingStream = stream
        val positionMs = pendingResumePositionMs
        pendingResumePositionMs = 0L
        playerSpinner.visibility = View.VISIBLE
        playerError.visibility = View.GONE
        player.setMediaSource(buildMediaSource(stream, mediaOkClient), positionMs)
        player.prepare()
        player.playWhenReady = userWantsPlayback && activityResumed
    }

    private fun loadQualityPreference(): Int {
        val stored = qualityPrefs.getInt(KEY_LAST_QUALITY, 0)
        return if (stored in SUPPORTED_QUALITIES) stored else BiliPlayUrl.QUALITY_720
    }

    private fun saveQualityPreference(quality: Int) {
        if (quality in SUPPORTED_QUALITIES) {
            qualityPrefs.edit().putInt(KEY_LAST_QUALITY, quality).apply()
        }
    }

    private fun recoverPlayback(
        stream: BiliPlayUrl.DashStream,
        player: ExoPlayer,
        error: androidx.media3.common.PlaybackException,
        generation: Int
    ) {
        if (isLocalMode) {
            showError("本地文件播放失败：${error.message ?: error.errorCodeName}\n\n点这里重试")
            return
        }
        if (generation != playbackGeneration || retryScheduled || currentCid <= 0L) return
        lastPlaybackError = "${error.errorCodeName} ${error.message ?: ""}"
        val transientIo = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        val decodeError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        if (!transientIo && !decodeError) {
            showError("播放出错：${error.errorCodeName}\n\n${error.message ?: ""}\n\n点这里重试")
            return
        }

        retryScheduled = true
        stablePlaybackStartedAtMs = 0L
        pendingResumePositionMs = maxOf(pendingResumePositionMs, playerGlobalPosition(player))
        playRetryCount++
        playerSpinner.visibility = View.VISIBLE
        playerError.visibility = View.GONE

        when {
            playRetryCount == 1 -> {
                // Rotate every chosen track/segment to its next CDN candidate before
                // spending another playurl request. This is fast and preserves quality.
                val rotated = rotateCdnCandidates(stream)
                handler.postDelayed({
                    if (generation == playbackGeneration) {
                        retryScheduled = false
                        startExoPlayer(rotated, generation)
                    }
                }, 350L)
            }
            playRetryCount == 2 && stream.isDash -> {
                // DASH/codec/manifest still failed: use Bilibili's classic progressive
                // stream, capped at 1080P, and keep the exact playback position.
                val durlQuality = requestedQuality.coerceAtMost(BiliPlayUrl.QUALITY_1080)
                BiliPlayUrl.fetchDurlPlayUrl(currentBvid, currentCid, durlQuality) { fallback, _ ->
                    if (isFinishing || isDestroyed || generation != playbackGeneration) return@fetchDurlPlayUrl
                    handler.post {
                        if (generation != playbackGeneration) return@post
                        retryScheduled = false
                        if (fallback != null) {
                            startExoPlayer(fallback, generation)
                        } else {
                            lowerQualityAndReload()
                        }
                    }
                }
            }
            playRetryCount <= 3 -> lowerQualityAndReload()
            else -> showError(
                "播放失败：网络或解码器持续异常\n\n${lastPlaybackError ?: ""}\n\n已尝试备用线路、兼容流和低画质。点这里重试"
            )
        }
    }

    private fun rotateCdnCandidates(stream: BiliPlayUrl.DashStream): BiliPlayUrl.DashStream {
        fun <T> rotate(list: List<T>): List<T> = if (list.size > 1) list.drop(1) + list.first() else list
        if (!stream.isDash) {
            val original = stream.durlSegments.ifEmpty {
                listOf(BiliPlayUrl.DurlSegment(stream.fallbackUrls, stream.durationMs, 0L))
            }
            val segments = original.map { segment ->
                segment.copy(urls = rotate(segment.urls.filter(String::isNotBlank)))
            }
            return stream.copy(
                fallbackUrls = segments.firstOrNull()?.urls.orEmpty(),
                durlSegments = segments
            )
        }
        return stream.copy(
            videoTracks = stream.videoTracks.map { track ->
                val urls = rotate((listOf(track.url) + track.backups).distinct())
                track.copy(url = urls.first(), backups = urls.drop(1))
            },
            audioTracks = stream.audioTracks.map { track ->
                val urls = rotate((listOf(track.url) + track.backups).distinct())
                track.copy(url = urls.first(), backups = urls.drop(1))
            }
        )
    }

    private fun lowerQualityAndReload() {
        retryScheduled = false
        requestedQuality = when {
            requestedQuality > BiliPlayUrl.QUALITY_720 -> BiliPlayUrl.QUALITY_720
            requestedQuality > BiliPlayUrl.QUALITY_480 -> BiliPlayUrl.QUALITY_480
            else -> BiliPlayUrl.QUALITY_360
        }
        loadPlayer()
    }

    private fun playerGlobalPosition(player: ExoPlayer): Long {
        val stream = currentStream
        val segments = stream?.durlSegments.orEmpty()
        if (segments.isEmpty() || player.currentMediaItemIndex <= 0) {
            return player.currentPosition.coerceAtLeast(0L)
        }
        val before = segments.take(player.currentMediaItemIndex).sumOf { it.lengthMs }
        return (before + player.currentPosition).coerceAtLeast(0L)
    }

    private fun playerGlobalBufferedPosition(player: ExoPlayer): Long {
        val segments = currentStream?.durlSegments.orEmpty()
        if (segments.isEmpty() || player.currentMediaItemIndex <= 0) {
            return player.bufferedPosition.coerceAtLeast(0L)
        }
        val before = segments.take(player.currentMediaItemIndex).sumOf { it.lengthMs }
        return (before + player.bufferedPosition).coerceAtLeast(0L)
    }

    private fun playerGlobalDuration(player: ExoPlayer): Long {
        val stream = currentStream
        val segmentDuration = stream?.durlSegments.orEmpty().sumOf { it.lengthMs }
        return when {
            segmentDuration > 0L -> segmentDuration
            stream != null && stream.durationMs > 0L -> stream.durationMs
            player.duration > 0L -> player.duration
            else -> 0L
        }
    }

    private fun seekPlayerGlobal(player: ExoPlayer, positionMs: Long) {
        val segments = currentStream?.durlSegments.orEmpty()
        if (segments.isEmpty()) {
            player.seekTo(positionMs.coerceAtLeast(0L))
            return
        }
        var remaining = positionMs.coerceAtLeast(0L)
        for ((index, segment) in segments.withIndex()) {
            val length = segment.lengthMs
            if (length <= 0L || remaining <= length || index == segments.lastIndex) {
                player.seekTo(index, remaining.coerceIn(0L, length.coerceAtLeast(0L)))
                return
            }
            remaining -= length
        }
    }

    private fun updateQualityState(stream: BiliPlayUrl.DashStream) {
        // 本地播放不展示画质切换与在线信息
        if (isLocalMode) return
        val full = currentStream ?: stream
        val available = full.videoTracks.map { it.id }.distinct().sortedDescending()
        // Show the tier actually being played (the probed track), not the
        // highest tier the API offered — they can differ after fallbacks.
        val playingId = stream.videoTracks.firstOrNull()?.id
            ?: if (!stream.isDash) stream.actualQuality
            else available.firstOrNull() ?: stream.actualQuality
        val suffix = if (available.isEmpty()) "" else " · 可用：${available.joinToString("/") { qualityName(it) }}"
        infoState.text = "当前播放：${qualityLabelOf(playingId)}$suffix"
        infoState.setTextColor(COLOR_MUTED)
        qualityBtn.text = qualityLabelOf(playingId).replace("P", "p")
    }

    private fun qualityLabelOf(id: Int): String = BiliPlayUrl.qualityLabel(id)

    private fun qualityResLabel(id: Int): String =
        BiliPlayUrl.qualityLabel(id).replace("P", "p")

    private fun qualityName(id: Int): String = when (id) {
        127 -> "8K"; 126 -> "杜比"; 125 -> "HDR"; 120 -> "4K"; 116 -> "1080P60"
        112 -> "1080P+"; 80 -> "1080P"; 64 -> "720P"; 32 -> "480P"; 16 -> "360P"
        else -> "${id}档"
    }

    // ─────────────────────────────────────────────────
    //  Progress & Time
    // ─────────────────────────────────────────────────

    private val progressUpdater = object : Runnable {
        override fun run() {
            val p = exoPlayer ?: return
            val dur = playerGlobalDuration(p)
            if (dur > 0 && !isSeeking) {
                val pos = playerGlobalPosition(p)
                progressContainer.updateFromPlayer()
                timeText.text = formatTime(pos)
                durationText.text = formatTime(dur)
            }
            handler.postDelayed(this, 250)
        }
    }

    /** Shared by the seek bar and the swipe gesture: updates time text + bar, clears ended state when scrubbing back. */
    private fun onManualSeek(pos: Long, dur: Long) {
        timeText.text = formatTime(pos)
        progressContainer.seekToFraction(pos.toFloat() / dur.toFloat())
        if (endedState && pos < dur - 1500) clearEndedState()
    }

    private fun clearEndedState() {
        endedState = false
        playPauseBtn.setEnded(false)
        playPauseBtn.setPlaying(false)
        showReplay(false)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return String.format(Locale.CHINA, "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    // ─────────────────────────────────────────────────
    //  Controls: Play/Pause, Speed, Quality, Fullscreen
    // ─────────────────────────────────────────────────

    private fun togglePlayPause() {
        val p = exoPlayer ?: return
        if (endedState) { replay() }
        else if (p.playWhenReady) {
            // isPlaying is false while buffering; playWhenReady reflects whether
            // playback will resume automatically and is the correct pause toggle.
            userWantsPlayback = false
            p.pause()
        } else {
            userWantsPlayback = true
            if (activityResumed) p.play()
        }
    }

    private fun replay() {
        val p = exoPlayer ?: return
        endedState = false
        playPauseBtn.setEnded(false)
        playPauseBtn.setPlaying(false)
        showReplay(false)
        userWantsPlayback = true
        p.seekTo(0)
        p.play()
        showControls()
    }

    private fun showReplay(show: Boolean) {
        if (show) {
            replayBtn.visibility = View.VISIBLE
            replayBtn.alpha = 0f
            replayBtn.scaleX = 0.8f
            replayBtn.scaleY = 0.8f
            replayBtn.animate().cancel()
            replayBtn.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        } else {
            replayBtn.animate().cancel()
            replayBtn.visibility = View.GONE
        }
    }

    // ── Long-press boost (manual timing so it works on every press) ──

    private fun applyLongPressBoost() {
        val p = exoPlayer ?: return
        if (!longPressDown || endedState || !p.isPlaying) return
        longPressBoost = true
        normalSpeed = p.playbackParameters.speed.let { if (it > 0f) it else 1f }
        p.playbackParameters = PlaybackParameters(2.0f)
        showBoostIndicator()
    }

    private fun cancelLongPressBoost() {
        longPressDown = false
        longPressCandidate = false
        handler.removeCallbacks(longPressBoostRunnable)
        if (longPressBoost) {
            longPressBoost = false
            exoPlayer?.playbackParameters = PlaybackParameters(normalSpeed)
            hideBoostIndicator()
        }
    }

    private fun showBoostIndicator() {
        boostIndicator.visibility = View.VISIBLE
        boostIndicator.alpha = 0f
        boostIndicator.animate().cancel()
        boostIndicator.animate().alpha(1f).setDuration(150).start()
        boostAnimator?.cancel()
        boostAnimator = ValueAnimator.ofFloat(1f, 1.08f).apply {
            duration = 420
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val s = it.animatedValue as Float
                boostIndicator.scaleX = s
                boostIndicator.scaleY = s
            }
            start()
        }
    }

    private fun hideBoostIndicator() {
        boostAnimator?.cancel(); boostAnimator = null
        boostIndicator.animate().cancel()
        boostIndicator.visibility = View.GONE
        boostIndicator.alpha = 0f
        boostIndicator.scaleX = 1f
        boostIndicator.scaleY = 1f
    }

    private fun showSpeedToast(text: String) {
        val toast = TextView(this).apply {
            this.text = text; textSize = 16f
            setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(0xBB000000.toInt())
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val overlay = FrameLayout(this).apply {
            addView(toast, FrameLayout.LayoutParams(dp(64), dp(36), Gravity.CENTER))
            setOnClickListener { (parent as? ViewGroup)?.removeView(this) }
        }
        controlOverlay.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay.postDelayed({ controlOverlay.removeView(overlay) }, 800)
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        val speed = speeds[speedIndex]
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
        speedBtn.text = "${speed}x"
        showSpeedToast("${speed}x")
    }

    private fun showQualityMenu(anchor: View) {
        val stream = currentStream ?: return
        val qualities = stream.acceptedQualities.ifEmpty { stream.videoTracks.map { it.id } }
            .distinct().sortedDescending()
        if (qualities.isEmpty()) return
        val loggedIn = BiliSessionStore.isLoggedIn()
        val playingId = playingStream?.actualQuality ?: requestedQuality
        val availableIds = stream.videoTracks.map { it.id }.toSet()

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            // 70% transparent backdrop so the video stays visible behind the list.
            background = rounded(0x4D29262A.toInt(), dp(12))
        }
        lateinit var popup: PopupWindow
        qualities.forEach { quality ->
            val available = quality in availableIds
            val suffix = when {
                quality <= BiliPlayUrl.QUALITY_480 -> ""
                quality < BiliPlayUrl.QUALITY_1080 -> if (loggedIn) "" else "（需登录）"
                else -> if (BiliSessionStore.isBigVip()) "（大会员）" else "（需大会员）"
            }
            val label = "${if (quality == playingId) "✓ " else ""}${qualityName(quality)}$suffix"
            val item = TextView(this).apply {
                text = label; textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(if (available) Color.WHITE else 0xFFAAA4A7.toInt())
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
                setOnClickListener {
                    popup.dismiss()
                    if (quality != requestedQuality) switchQuality(quality)
                }
            }
            if (quality == playingId) {
                item.setBackgroundColor(0x33FFFFFF.toInt())
            }
            menu.addView(item, LinearLayout.LayoutParams(dp(176), dp(42)))
        }
        popup = PopupWindow(menu, dp(180), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
        }
        popup.showAsDropDown(anchor, -dp(144), -dp(menu.childCount * 42 + dp(16)))
    }

    // ─────────────────────────────────────────────────
    //  Controls Visibility
    // ─────────────────────────────────────────────────

    private fun toggleControls() {
        if (controlsAnimating) return
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsAnimating = true
        controlsVisible = true
        controlOverlay.visibility = View.VISIBLE
        if (reducedMotion) {
            controlOverlay.alpha = 1f
            controlsAnimating = false
        } else {
            controlOverlay.alpha = 0f
            controlOverlay.animate().alpha(1f).setDuration(MotionTokens.panelMs)
                .setInterpolator(MotionTokens.easeOut)
                .withEndAction { controlsAnimating = false }.start()
        }
        playerBack.animate().cancel()
        playerBack.alpha = 1f
        playerBack.visibility = View.VISIBLE
        controlsHideRunnable?.let { handler.removeCallbacks(it) }
        if (exoPlayer?.isPlaying == true) scheduleAutoHide()
    }

    private fun hideControls() {
        controlsAnimating = true
        controlsVisible = false
        if (reducedMotion) {
            controlOverlay.visibility = View.GONE
            controlsAnimating = false
        } else {
            controlOverlay.animate().alpha(0f).setDuration(MotionTokens.panelMs)
                .setInterpolator(MotionTokens.easeOut)
                .withEndAction {
                    controlOverlay.visibility = View.GONE
                    controlsAnimating = false
                }.start()
        }
        playerBack.animate().alpha(0f).setDuration(200)
            .withEndAction { if (playerBack.alpha <= 0.01f) playerBack.visibility = View.GONE }.start()
    }

    private fun scheduleAutoHide() {
        controlsHideRunnable?.let { handler.removeCallbacks(it) }
        controlsHideRunnable = Runnable { if (exoPlayer?.isPlaying == true) hideControls() }
        handler.postDelayed(controlsHideRunnable!!, 3500)
    }

    // ─────────────────────────────────────────────────
    //  Fullscreen: re-parent into a dedicated overlay container.
    //  Landscape videos rotate to landscape; portrait videos stay portrait.
    // ─────────────────────────────────────────────────

    private fun isPortraitVideo(): Boolean {
        val track = currentStream?.videoTracks?.firstOrNull() ?: return false
        return track.width > 0 && track.height > 0 && track.height > track.width
    }

    private fun toggleFullscreen() {
        if (!::fullscreenContainer.isInitialized || !::playerHost.isInitialized) return
        if (fsTransitioning) return
        isFullscreen = !isFullscreen
        fsBtn.setFullscreen(isFullscreen)
        controlsHideRunnable?.let { handler.removeCallbacks(it) }
        if (isFullscreen) enterFullscreen() else exitFullscreen()
    }

    private fun enterFullscreen() {
        fsTransitioning = true
        // Instant re-parent, no alpha/scale animation: the player is a SurfaceView and
        // view animations don't render its video frames — they only add jank on top of
        // the system rotation animation. For landscape videos the system rotation
        // provides the visual transition; for portrait videos there is nothing to animate.
        (playerHost.parent as? ViewGroup)?.removeView(playerHost)
        fullscreenContainer.removeAllViews()
        fullscreenContainer.addView(playerHost, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        fullscreenContainer.visibility = View.VISIBLE
        page.visibility = View.GONE
        hideSystemBars()
        // Brightness/volume indicator grows to full size in fullscreen.
        adjustIndicator.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        // Rotate to landscape unless this is a portrait video; system animates.
        if (!isPortraitVideo() &&
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        applyControlSizing()
        showControls()
        fsTransitioning = false
    }

    private fun exitFullscreen() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            movePlayerBackToPage()
        } else {
            exitingFullscreen = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // movePlayerBackToPage() runs from onConfigurationChanged once the rotation lands
        }
    }

    private fun movePlayerBackToPage() {
        exitingFullscreen = false
        fsTransitioning = true
        // Restore system bars first so the inset change and the re-parent happen in the
        // same layout pass — avoids a visible "jump" mid-transition.
        showSystemBars()
        // Same instant re-parent as entering fullscreen: the system rotation already
        // animated the viewport, so any extra fade only adds dropped frames.
        fullscreenContainer.removeView(playerHost)
        fullscreenContainer.visibility = View.GONE
        val playerHeight = resources.displayMetrics.widthPixels * 9 / 16
        page.addView(playerHost, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, playerHeight))
        page.visibility = View.VISIBLE
        // Brightness/volume indicator shrinks back to compact size in portrait.
        adjustIndicator.animate().scaleX(COMPACT_ADJUST_SCALE).scaleY(COMPACT_ADJUST_SCALE)
            .setDuration(200).start()
        applyControlSizing()
        showControls()
        fsTransitioning = false
    }

    private fun hideSystemBars() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun showSystemBars() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
        // Re-apply theme chrome after fullscreen; SYSTEM_UI_FLAG_VISIBLE alone drops light/dark icons.
        Theme.applySystemBars(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Exiting fullscreen: as soon as the portrait rotation lands, move the player back
        // into the detail page on the next frame so the transition stays continuous —
        // waiting any longer makes the player linger fullscreen-sized in the middle.
        if (exitingFullscreen && newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            handler.post { if (exitingFullscreen) movePlayerBackToPage() }
        }
    }

    private fun retryPlayer() {
        exoPlayer?.let { pendingResumePositionMs = playerGlobalPosition(it) }
        playRetryCount = 0
        retryScheduled = false
        userWantsPlayback = true
        if (isLocalMode) { loadLocalPlayer(); return }
        if (currentCid > 0L) loadPlayer() else loadVideoAndPlayer()
    }
    private fun showError(msg: String) {
        playerSpinner.visibility = View.GONE; playerError.text = msg; playerError.visibility = View.VISIBLE
    }
    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    /** Top-rounded panel background for bottom sheets. */
    private fun roundedTop(fill: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadii = floatArrayOf(radius.toFloat(), radius.toFloat(), radius.toFloat(), radius.toFloat(), 0f, 0f, 0f, 0f)
        }

    /** Horizontal pager used for 简介/评论: drag-follows the finger, flings, and snaps.
     *  Vertical scroll is left to the child ScrollViews. */
    /** Drawn sort icon (up/down chevrons, no emoji) shown next to the sort label. */
    private inner class SortIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED
            strokeWidth = dpf(1.8f)
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val s = dpf(3.4f)
            // Up chevron
            canvas.drawLine(cx - s, cy - s * 0.4f, cx, cy - s * 1.3f, paint)
            canvas.drawLine(cx + s, cy - s * 0.4f, cx, cy - s * 1.3f, paint)
            // Down chevron
            canvas.drawLine(cx - s, cy + s * 0.4f, cx, cy + s * 1.3f, paint)
            canvas.drawLine(cx + s, cy + s * 0.4f, cx, cy + s * 1.3f, paint)
        }
    }

        /**
     * Comment like button.
     * - Initial state from reply/main `action`/`like` (Bilibili, login cookie required).
     * - Click: POST reply/action; success keeps optimistic UI (action API returns no state).
     * - Re-enter page: fetchComments refreshes action from server — no local store.
     */
    private inner class LikeButton(context: Context, private val comment: VideoComment) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dpf(11f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // Seed from reply/main, then overlay a just-confirmed action if the
        // server action field has not caught up yet (few-second lag).
        private var liked: Boolean
        private var likes: Long
        private var inFlight = false

        init {
            val bridged = LikeStateBridge.resolveComment(comment.id, comment.liked, comment.likes)
            liked = bridged.first
            likes = bridged.second
            isClickable = true
            setOnClickListener { toggleLike() }
        }

        override fun onDraw(canvas: Canvas) {
            val color = if (liked) COLOR_ROSE else COLOR_MUTED
            paint.color = color
            paint.strokeWidth = dpf(1.7f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val cx = dpf(10f)
            val cy = height / 2f
            // 与动作栏点赞同款：拇指上扬线条手势；激活时实心
            paint.style = if (liked) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            val thumb = Path().apply {
                // 拇指
                moveTo(cx - dpf(1.2f), cy - dpf(5.8f))
                quadTo(cx + dpf(1.6f), cy - dpf(7.2f), cx + dpf(3.2f), cy - dpf(4.4f))
                lineTo(cx + dpf(2.2f), cy - dpf(1.2f))
                // 手掌上沿
                lineTo(cx + dpf(5.6f), cy - dpf(0.6f))
                quadTo(cx + dpf(7.2f), cy + dpf(0.2f), cx + dpf(6.6f), cy + dpf(2.6f))
                // 掌底
                lineTo(cx + dpf(5.8f), cy + dpf(5.6f))
                quadTo(cx + dpf(5.2f), cy + dpf(6.8f), cx + dpf(3.6f), cy + dpf(6.6f))
                lineTo(cx - dpf(3.8f), cy + dpf(6.4f))
                quadTo(cx - dpf(5.4f), cy + dpf(6.2f), cx - dpf(5.6f), cy + dpf(4.6f))
                lineTo(cx - dpf(5.4f), cy + dpf(0.6f))
                quadTo(cx - dpf(5.2f), cy - dpf(1.0f), cx - dpf(3.4f), cy - dpf(1.4f))
                // 回到拇指根
                lineTo(cx - dpf(1.8f), cy - dpf(1.6f))
                close()
            }
            canvas.drawPath(thumb, paint)
            val text = if (likes > 0L) formatViews(likes) else ""
            if (text.isNotEmpty()) {
                textPaint.color = color
                canvas.drawText(text, dpf(20f), cy + textPaint.textSize / 2f - dpf(1f), textPaint)
            }
        }

        private fun toggleLike() {
            if (inFlight) return
            if (!BiliSessionStore.isLoggedIn()) {
                showSnackbar("请先登录后再点赞评论")
                return
            }
            val aid = currentDetail?.aid ?: 0L
            if (aid <= 0L || comment.id <= 0L) {
                showSnackbar("评论信息未就绪，请稍后再试")
                return
            }
            val previousLiked = liked
            val previousLikes = likes
            val newLiked = !previousLiked
            // Optimistic UI only after we will confirm POST code==0.
            // Count is approximate until next list refresh (B站 like 字段有延迟).
            liked = newLiked
            likes = (previousLikes + if (newLiked) 1 else -1).coerceAtLeast(0L)
            inFlight = true
            isEnabled = false
            animate().scaleX(1.3f).scaleY(1.3f).setDuration(110)
                .withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(150).start() }.start()
            invalidate()
            BiliApi.postCommentLike(aid, comment.id, newLiked) { ok, error ->
                if (isFinishing || isDestroyed) return@postCommentLike
                inFlight = false
                isEnabled = true
                if (ok) {
                    // Keep optimistic state and bridge the short reply.action lag
                    // so an immediate re-enter still shows the confirmed action.
                    LikeStateBridge.rememberComment(comment.id, liked, likes)
                    invalidate()
                } else {
                    liked = previousLiked
                    likes = previousLikes
                    invalidate()
                    showSnackbar(error ?: "评论点赞失败，请稍后再试")
                }
            }
        }
    }

/** Action bar icon: modern outline style (thin strokes, no emoji) + label. */
    private inner class ActionIconView(context: Context, private val kind: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dpf(11f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private var active = false
        private var count = 0L

        fun setActive(value: Boolean) { active = value; invalidate() }
        fun isActive(): Boolean = active
        fun setCount(value: Long) { count = value.coerceAtLeast(0L); invalidate() }

        override fun onDraw(canvas: Canvas) {
            val color = if (active) COLOR_ROSE else COLOR_MUTED
            paint.color = color
            paint.strokeWidth = dpf(1.7f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val cx = width / 2f
            val iconCy = height / 2f - dpf(7f)
            val labelCy = height - dpf(11f)
            // 与「我的」页 MenuIconView 同批：1.7dp 圆角线条；点赞/收藏激活时实心
            when (kind) {
                KIND_LIKE -> {
                    paint.style = if (active) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                    val thumb = Path().apply {
                        moveTo(cx - dpf(1.2f), iconCy - dpf(5.8f))
                        quadTo(cx + dpf(1.6f), iconCy - dpf(7.2f), cx + dpf(3.2f), iconCy - dpf(4.4f))
                        lineTo(cx + dpf(2.2f), iconCy - dpf(1.2f))
                        lineTo(cx + dpf(5.6f), iconCy - dpf(0.6f))
                        quadTo(cx + dpf(7.2f), iconCy + dpf(0.2f), cx + dpf(6.6f), iconCy + dpf(2.6f))
                        lineTo(cx + dpf(5.8f), iconCy + dpf(5.6f))
                        quadTo(cx + dpf(5.2f), iconCy + dpf(6.8f), cx + dpf(3.6f), iconCy + dpf(6.6f))
                        lineTo(cx - dpf(3.8f), iconCy + dpf(6.4f))
                        quadTo(cx - dpf(5.4f), iconCy + dpf(6.2f), cx - dpf(5.6f), iconCy + dpf(4.6f))
                        lineTo(cx - dpf(5.4f), iconCy + dpf(0.6f))
                        quadTo(cx - dpf(5.2f), iconCy - dpf(1.0f), cx - dpf(3.4f), iconCy - dpf(1.4f))
                        lineTo(cx - dpf(1.8f), iconCy - dpf(1.6f))
                        close()
                    }
                    canvas.drawPath(thumb, paint)
                }
                KIND_FAVORITE -> {
                    // 空心五角星（与「我的收藏」同款）
                    paint.style = if (active) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                    val outer = dpf(8.2f)
                    val inner = outer * 0.42f
                    val star = Path()
                    for (i in 0 until 5) {
                        val aOut = Math.toRadians((-90 + i * 72).toDouble())
                        val aIn = Math.toRadians((-90 + i * 72 + 36).toDouble())
                        val ox = cx + (outer * Math.cos(aOut)).toFloat()
                        val oy = iconCy + (outer * Math.sin(aOut)).toFloat()
                        val ix = cx + (inner * Math.cos(aIn)).toFloat()
                        val iy = iconCy + (inner * Math.sin(aIn)).toFloat()
                        if (i == 0) star.moveTo(ox, oy) else star.lineTo(ox, oy)
                        star.lineTo(ix, iy)
                    }
                    star.close()
                    canvas.drawPath(star, paint)
                }
                KIND_DOWNLOAD -> {
                    // 下行箭头 + 托盘
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(cx, iconCy - dpf(7.2f), cx, iconCy + dpf(2.2f), paint)
                    canvas.drawLine(cx - dpf(4.6f), iconCy - dpf(1.4f), cx, iconCy + dpf(2.6f), paint)
                    canvas.drawLine(cx + dpf(4.6f), iconCy - dpf(1.4f), cx, iconCy + dpf(2.6f), paint)
                    val tray = Path().apply {
                        moveTo(cx - dpf(6.8f), iconCy + dpf(4.2f))
                        lineTo(cx - dpf(6.8f), iconCy + dpf(6.8f))
                        lineTo(cx + dpf(6.8f), iconCy + dpf(6.8f))
                        lineTo(cx + dpf(6.8f), iconCy + dpf(4.2f))
                    }
                    canvas.drawPath(tray, paint)
                }
                KIND_SHARE -> {
                    // 三节点分享：与菜单线条同风格
                    paint.style = Paint.Style.STROKE
                    val r = dpf(2.4f)
                    val topX = cx + dpf(4.8f); val topY = iconCy - dpf(5.2f)
                    val midX = cx - dpf(5.0f); val midY = iconCy
                    val botX = cx + dpf(4.8f); val botY = iconCy + dpf(5.2f)
                    canvas.drawLine(midX, midY, topX, topY, paint)
                    canvas.drawLine(midX, midY, botX, botY, paint)
                    canvas.drawCircle(topX, topY, r, paint)
                    canvas.drawCircle(midX, midY, r, paint)
                    canvas.drawCircle(botX, botY, r, paint)
                }
            }
            textPaint.color = color
            val label = when (kind) {
                KIND_LIKE -> if (count > 0L) formatViews(count) else "点赞"
                KIND_FAVORITE -> "收藏"
                KIND_DOWNLOAD -> "下载"
                else -> "分享"
            }
            canvas.drawText(label, cx, labelCy, textPaint)
        }
    }

    private inner class HorizontalPagerView(context: Context) : ViewGroup(context) {
        private val scroller = Scroller(context, DecelerateInterpolator())
        private val velocityTracker = VelocityTracker.obtain()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var dragging = false
        private var pageIndex = 0
        var pageChangeListener: ((Int) -> Unit)? = null
        // 0..1 horizontal progress between the first and the last page; drives tab visuals.
        var pageProgressListener: ((Float) -> Unit)? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val childW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
            val childH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) getChildAt(i).measure(childW, childH)
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = width
            for (i in 0 until childCount) {
                getChildAt(i).layout(i * w, 0, (i + 1) * w, height)
            }
        }

        private fun notifyProgress() {
            pageProgressListener?.invoke((scrollX.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f))
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; lastX = ev.x; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.x
                    velocityTracker.clear()
                    velocityTracker.addMovement(ev)
                    scroller.forceFinished(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val d = lastX - ev.x
                    lastX = ev.x
                    velocityTracker.addMovement(ev)
                    val maxX = (childCount - 1) * width
                    scrollTo((scrollX + d.toInt()).coerceIn(0, maxX), 0)
                    notifyProgress()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    velocityTracker.addMovement(ev)
                    velocityTracker.computeCurrentVelocity(1000)
                    val vx = velocityTracker.xVelocity
                    val maxX = (childCount - 1) * width
                    val offset = scrollX - pageIndex * width
                    val target = when {
                        // A fast fling switches page even without dragging far.
                        vx > 900 -> (pageIndex - 1).coerceAtLeast(0)
                        vx < -900 -> (pageIndex + 1).coerceAtMost(childCount - 1)
                        // Otherwise only ~1/4 of the page width is enough.
                        offset > width * 0.25f -> (pageIndex + 1).coerceAtMost(childCount - 1)
                        offset < -width * 0.25f -> (pageIndex - 1).coerceAtLeast(0)
                        else -> pageIndex
                    }
                    velocityTracker.clear()
                    scrollToPage(target, smooth = true)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker.clear()
                    scrollToPage(pageIndex, smooth = true)
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        fun scrollToPage(index: Int, smooth: Boolean) {
            val target = index.coerceIn(0, childCount - 1)
            if (smooth) {
                scroller.forceFinished(true)
                scroller.startScroll(scrollX, 0, target * width - scrollX, 0, 260)
                invalidate()
            } else {
                scroller.forceFinished(true)
                scrollTo(target * width, 0)
                pageIndex = target
                notifyProgress()
                pageChangeListener?.invoke(target)
            }
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.currX, 0)
                notifyProgress()
                postInvalidateOnAnimation()
            } else {
                val idx = if (width > 0) (scrollX + width / 2) / width else 0
                if (idx != pageIndex) {
                    pageIndex = idx
                    notifyProgress()
                    pageChangeListener?.invoke(idx)
                }
            }
        }
    }

    /** Floating pill showing brightness/volume level while adjusting via vertical drag. */
    private inner class AdjustIndicator(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC1A1A1A.toInt(); style = Paint.Style.FILL
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40FFFFFF.toInt(); style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFB7D2.toInt(); style = Paint.Style.FILL
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
        }
        var mode = 1 // 1 brightness, 2 volume
            set(value) { field = value; invalidate() }
        var fraction = 0.5f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRoundRect(RectF(0f, 0f, w, h), dpf(12f), dpf(12f), bgPaint)
            iconPaint.textSize = dpf(17f)
            canvas.drawText(if (mode == 1) "☀" else "♪", w / 2f, dpf(30f), iconPaint)
            val barLeft = w / 2f - dpf(2.5f)
            val barTop = dpf(42f)
            val barBot = h - dpf(30f)
            canvas.drawRoundRect(RectF(barLeft, barTop, barLeft + dpf(5f), barBot), dpf(2.5f), dpf(2.5f), trackPaint)
            val fillH = (barBot - barTop) * fraction
            if (fillH > 0f) {
                canvas.drawRoundRect(RectF(barLeft, barBot - fillH, barLeft + dpf(5f), barBot),
                    dpf(2.5f), dpf(2.5f), fillPaint)
            }
            iconPaint.textSize = dpf(12f)
            canvas.drawText("${(fraction * 100).roundToInt()}", w / 2f, h - dpf(12f), iconPaint)
        }
    }

    /** Custom drawn seek bar with rounded track and thumb */
    private inner class SeekBarView(context: Context) : FrameLayout(context) {
        init { setWillNotDraw(false) }

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x44FFFFFF.toInt(); style = Paint.Style.FILL
        }
        // Buffered (prefetched) segment: white, drawn between played and unplayed.
        private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB3FFFFFF.toInt(); style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFB7D2.toInt(); style = Paint.Style.FILL
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFB7D2.toInt(); style = Paint.Style.FILL
        }
        private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dpf(2f)
        }
        var fillWidth = 0f
            private set
        var bufferedWidth = 0f
            private set
        var isDragging = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            val trackH = dpf(4f)
            val trackY = (h - trackH) / 2f
            val r = trackH / 2f

            // Track background
            canvas.drawRoundRect(RectF(0f, trackY, width.toFloat(), trackY + trackH), r, r, trackPaint)
            // Buffered (prefetched) portion — white, sits right of the played part
            val fw = fillWidth.coerceAtLeast(0f)
            val bw = bufferedWidth.coerceAtLeast(fw)
            if (bw > 0) {
                canvas.drawRoundRect(RectF(fw, trackY, bw, trackY + trackH), r, r, bufferedPaint)
            }
            // Filled portion
            if (fw > 0) {
                canvas.drawRoundRect(RectF(0f, trackY, fw, trackY + trackH), r, r, fillPaint)
            }
            // Thumb
            val thumbR = dpf(5f)
            val thumbX = fw.coerceIn(thumbR, width.toFloat() - thumbR)
            canvas.drawCircle(thumbX, h / 2f, thumbR, thumbPaint)
            canvas.drawCircle(thumbX, h / 2f, thumbR, thumbBorderPaint)
        }

        fun seekToFraction(fraction: Float) {
            fillWidth = (fraction * width).coerceIn(0f, width.toFloat())
            invalidate()
        }

        fun updateFromPlayer() {
            val p = exoPlayer ?: return
            val duration = playerGlobalDuration(p)
            if (duration > 0L && width > 0 && !isDragging) {
                fillWidth = (playerGlobalPosition(p).toFloat() / duration.toFloat()) * width
                bufferedWidth = (playerGlobalBufferedPosition(p).toFloat() / duration.toFloat()) * width
                invalidate()
            }
        }
    }

    private inner /**
         * Circular avatar. Never draws a square background — that was the
         * "square shadow border" around comment avatars (COLOR_COVER square bg
         * peeking out from under a circular bitmap).
         */
        class RoundImageView(context: Context) : ImageView(context) {
        private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_COVER
        }
        private val shaderMatrix = Matrix()

        init {
            // Background must stay transparent; placeholder is drawn as a circle.
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ScaleType.CENTER_CROP
        }

        override fun setBackgroundColor(color: Int) {
            // Ignore square fills from call sites; keep circular placeholder only.
            if (color == Color.TRANSPARENT || color == 0) {
                super.setBackgroundColor(Color.TRANSPARENT)
            } else {
                placeholderPaint.color = color
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val radius = minOf(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f
            // Circular placeholder so empty/loading states never show a square halo.
            canvas.drawCircle(cx, cy, radius, placeholderPaint)

            val d = drawable ?: return
            val bmp = when (d) {
                is BitmapDrawable -> d.bitmap
                else -> {
                    // Fallback: clip any non-bitmap drawable to a circle.
                    val saved = canvas.save()
                    canvas.clipPath(Path().apply {
                        addCircle(cx, cy, radius, Path.Direction.CW)
                    })
                    d.setBounds(0, 0, width, height)
                    d.draw(canvas)
                    canvas.restoreToCount(saved)
                    return
                }
            } ?: return
            if (bmp.isRecycled) return
            val scale = (width.toFloat() / bmp.width.coerceAtLeast(1))
                .coerceAtLeast(height.toFloat() / bmp.height.coerceAtLeast(1))
            val dx = (width - bmp.width * scale) * 0.5f
            val dy = (height - bmp.height * scale) * 0.5f
            shaderMatrix.setScale(scale, scale)
            shaderMatrix.postTranslate(dx, dy)
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(shaderMatrix)
            shaderPaint.shader = shader
            canvas.drawCircle(cx, cy, radius, shaderPaint)
        }
    }

    // ─────────────────────────────────────────────────
    //  Gesture layer. It sits below controls and never intercepts their clicks.
    // ─────────────────────────────────────────────────

    private inner class PlayerGestureView(context: Context) : View(context) {
        init { isClickable = true }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = ev.x
                    gestureStartY = ev.y
                    gestureSeeking = false
                    adjustMode = 0
                    longPressBoost = false
                    longPressDown = true
                    longPressCandidate = true
                    handler.removeCallbacks(longPressBoostRunnable)
                    handler.postDelayed(longPressBoostRunnable, 450)
                    gestureDetector.onTouchEvent(ev)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longPressBoost) return true
                    val dx = ev.x - gestureStartX
                    val dy = ev.y - gestureStartY
                    if (longPressCandidate &&
                        (abs(dx) > dpf(16f) || abs(dy) > dpf(16f))) {
                        longPressCandidate = false
                        handler.removeCallbacks(longPressBoostRunnable)
                    }
                    if (gestureSeeking) {
                        val p = exoPlayer ?: return true
                        val duration = playerGlobalDuration(p)
                        if (duration <= 0L) return true
                        val seekDelta = ((dx / width.toFloat().coerceAtLeast(1f)) * duration).toLong()
                        val newPos = (gestureSeekStartPos + seekDelta).coerceIn(0L, duration)
                        seekPlayerGlobal(p, newPos)
                        onManualSeek(newPos, duration)
                    } else if (adjustMode != 0) {
                        applyAdjust(dy)
                    } else {
                        // Decide gesture type: horizontal drag → seek; vertical drag on the
                        // left/right third → brightness/volume; otherwise pass to detector.
                        if (!gestureSeeking && abs(dx) > dpf(20f) && abs(dx) > abs(dy) * 1.5f) {
                            gestureSeeking = true
                            gestureSeekStartPos = exoPlayer?.let { playerGlobalPosition(it) } ?: 0L
                        } else if (abs(dy) > dpf(20f) && abs(dy) > abs(dx) * 1.2f && width > 0) {
                            val zone = when {
                                gestureStartX < width / 3f -> 1 // brightness
                                gestureStartX > width * 2f / 3f -> 2 // volume
                                else -> 0
                            }
                            if (zone != 0) {
                                adjustMode = zone
                                adjustStartValue = if (zone == 1) {
                                    val b = window.attributes.screenBrightness
                                    if (b in 0.01f..1f) b else 0.5f
                                } else {
                                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                }
                                showAdjust(zone, adjustStartValue)
                            }
                        } else {
                            gestureDetector.onTouchEvent(ev)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val boosted = longPressBoost
                    cancelLongPressBoost()
                    if (adjustMode != 0) {
                        adjustMode = 0
                        hideAdjust()
                    }
                    if (boosted) {
                        // A boost release must not also toggle the controls: feed a synthetic
                        // CANCEL to the detector so it skips single-tap confirmation.
                        val synthetic = MotionEvent.obtain(ev)
                        synthetic.action = MotionEvent.ACTION_CANCEL
                        gestureDetector.onTouchEvent(synthetic)
                        synthetic.recycle()
                    } else if (!gestureSeeking) {
                        gestureDetector.onTouchEvent(ev)
                    }
                    gestureSeeking = false
                    return true
                }
            }
            return true
        }
    }

    /** Vertical drag amount → brightness (zone 1) or volume (zone 2). */
    private fun applyAdjust(dy: Float) {
        val travel = (playerHost.height.coerceAtLeast(1)) * 0.7f
        val delta = -dy / travel
        if (adjustMode == 1) {
            val target = (adjustStartValue + delta).coerceIn(0.05f, 1f)
            val lp = window.attributes
            lp.screenBrightness = target
            window.attributes = lp
            updateAdjust(target)
        } else if (adjustMode == 2) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) {
                val target = (adjustStartValue + delta * max).roundToInt().coerceIn(0, max)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                updateAdjust(target / max.toFloat())
            }
        }
    }

    private fun showAdjust(mode: Int, startValue: Float) {
        adjustIndicator.mode = mode
        adjustIndicator.fraction = if (mode == 1) startValue else {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) startValue / max else 0.5f
        }
        adjustIndicator.animate().cancel()
        adjustIndicator.visibility = View.VISIBLE
        adjustIndicator.alpha = 0f
        val targetScale = if (isFullscreen) 1f else COMPACT_ADJUST_SCALE
        adjustIndicator.scaleX = targetScale * 0.85f
        adjustIndicator.scaleY = targetScale * 0.85f
        adjustIndicator.animate().alpha(1f).scaleX(targetScale).scaleY(targetScale)
            .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun updateAdjust(fraction: Float) {
        adjustIndicator.fraction = fraction
    }

    private fun hideAdjust() {
        adjustIndicator.animate().alpha(0f).setDuration(180)
            .withEndAction { adjustIndicator.visibility = View.GONE }.start()
    }

    // ─────────────────────────────────────────────────
    //  Custom Drawable Icons
    // ─────────────────────────────────────────────────

    private inner class PlayPauseIcon(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = dpf(2f); strokeCap = Paint.Cap.ROUND
        }
        var isPlaying = false
            private set
        private var ended = false
        private val path = Path()

        fun setPlaying(playing: Boolean) {
            if (isPlaying != playing) { isPlaying = playing; invalidate() }
        }

        fun setEnded(end: Boolean) {
            if (ended != end) { ended = end; invalidate() }
        }

        private fun dpf(v: Float): Float = v * resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            if (ended) {
                // Replay icon: circular arrow
                val iconR = dpf(8f)
                canvas.drawArc(RectF(cx - iconR, cy - iconR, cx + iconR, cy + iconR),
                    30f, 285f, false, arcPaint)
                val endDeg = java.lang.Math.toRadians(315.0)
                val tipX = cx + iconR * kotlin.math.cos(endDeg).toFloat()
                val tipY = cy + iconR * kotlin.math.sin(endDeg).toFloat()
                val tanX = kotlin.math.sin(endDeg).toFloat()
                val tanY = -kotlin.math.cos(endDeg).toFloat()
                val wing = dpf(4f)
                path.reset()
                val r1 = java.lang.Math.toRadians(150.0)
                path.moveTo(tipX, tipY)
                path.lineTo(tipX + (tanX * kotlin.math.cos(r1).toFloat() - tanY * kotlin.math.sin(r1).toFloat()) * wing,
                    tipY + (tanX * kotlin.math.sin(r1).toFloat() + tanY * kotlin.math.cos(r1).toFloat()) * wing)
                val r2 = java.lang.Math.toRadians(-150.0)
                path.moveTo(tipX, tipY)
                path.lineTo(tipX + (tanX * kotlin.math.cos(r2).toFloat() - tanY * kotlin.math.sin(r2).toFloat()) * wing,
                    tipY + (tanX * kotlin.math.sin(r2).toFloat() + tanY * kotlin.math.cos(r2).toFloat()) * wing)
                canvas.drawPath(path, paint)
                return
            }
            path.reset()
            if (isPlaying) {
                val barW = dpf(3.5f); val gap = dpf(2.5f); val h = dpf(10f)
                val left = cx - gap / 2f - barW
                path.addRect(left, cy - h / 2f, left + barW, cy + h / 2f, Path.Direction.CW)
                path.addRect(cx + gap / 2f, cy - h / 2f, cx + gap / 2f + barW, cy + h / 2f, Path.Direction.CW)
            } else {
                // Slightly smaller than the pause bars so both states feel balanced.
                val size = dpf(7f)
                path.moveTo(cx - size * 0.5f, cy - size)
                path.lineTo(cx + size * 0.7f, cy)
                path.lineTo(cx - size * 0.5f, cy + size)
                path.close()
            }
            canvas.drawPath(path, paint)
        }
    }

    private inner class ReplayIcon(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x59000000.toInt(); style = Paint.Style.FILL
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = dpf(2.5f); strokeCap = Paint.Cap.ROUND
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            canvas.drawCircle(cx, cy, dp(28).toFloat(), bgPaint)
            val iconR = dpf(13f)
            canvas.drawArc(RectF(cx - iconR, cy - iconR, cx + iconR, cy + iconR),
                30f, 285f, false, arcPaint)
            val endDeg = java.lang.Math.toRadians(315.0)
            val tipX = cx + iconR * kotlin.math.cos(endDeg).toFloat()
            val tipY = cy + iconR * kotlin.math.sin(endDeg).toFloat()
            val tanX = kotlin.math.sin(endDeg).toFloat()
            val tanY = -kotlin.math.cos(endDeg).toFloat()
            val wing = dpf(6f)
            val r1 = java.lang.Math.toRadians(150.0)
            val r2 = java.lang.Math.toRadians(-150.0)
            path.reset()
            path.moveTo(tipX, tipY)
            path.lineTo(tipX + (tanX * kotlin.math.cos(r1).toFloat() - tanY * kotlin.math.sin(r1).toFloat()) * wing,
                tipY + (tanX * kotlin.math.sin(r1).toFloat() + tanY * kotlin.math.cos(r1).toFloat()) * wing)
            path.moveTo(tipX, tipY)
            path.lineTo(tipX + (tanX * kotlin.math.cos(r2).toFloat() - tanY * kotlin.math.sin(r2).toFloat()) * wing,
                tipY + (tanX * kotlin.math.sin(r2).toFloat() + tanY * kotlin.math.cos(r2).toFloat()) * wing)
            canvas.drawPath(path, iconPaint)
        }
    }

    private inner class FullscreenIcon(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = dpf(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private var isFullscreen = false
        fun setFullscreen(fs: Boolean) { isFullscreen = fs; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val s = dpf(8f); val gap = dpf(2f)
            if (isFullscreen) {
                // Exit fullscreen: inward arrows (top-right, bottom-left)
                canvas.drawLine(cx + s - gap, cy - s + gap, cx + s - gap, cy - gap, paint)
                canvas.drawLine(cx + s - gap, cy - gap, cx + gap, cy - gap, paint)
                canvas.drawLine(cx - s + gap, cy + s - gap, cx - s + gap, cy + gap, paint)
                canvas.drawLine(cx - s + gap, cy + gap, cx - gap, cy + gap, paint)
            } else {
                // Enter fullscreen: outward arrows
                canvas.drawLine(cx - s, cy - s + gap, cx - gap, cy - s + gap, paint)
                canvas.drawLine(cx - s, cy - s + gap, cx - s, cy - gap, paint)
                canvas.drawLine(cx + s, cy + s - gap, cx + gap, cy + s - gap, paint)
                canvas.drawLine(cx + s, cy + s - gap, cx + s, cy + gap, paint)
            }
        }
    }

    override fun onBackPressed() { if (isFullscreen) toggleFullscreen() else super.onBackPressed() }
    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (userWantsPlayback) exoPlayer?.play()
        // User may have just finished logging in from the comment login hint: refresh.
        if (commentsLoaded && commentLoginHintShown && BiliSessionStore.isLoggedIn()) {
            commentLoginHintShown = false
            loadComments(reset = true)
        }
    }
    override fun onPause() {
        reportPlayHistory(status = 2)
        handler.removeCallbacks(historyTick)
        activityResumed = false
        exoPlayer?.pause()
        super.onPause()
    }
    override fun onDestroy() {
        reportPlayHistory(status = 2)
        handler.removeCallbacks(historyTick)
        handler.removeCallbacksAndMessages(null)
        titleHeightAnimator?.cancel()
        descHeightAnimator?.cancel()
        if (::playerView.isInitialized) playerView.player = null
        exoPlayer?.release(); exoPlayer = null
        super.onDestroy()
    }

    /** Watch-history heartbeat: report the current playback progress. */
    private val historyTick: Runnable = Runnable {
        if (exoPlayer?.isPlaying == true) {
            reportPlayHistory(status = 3)
            handler.postDelayed(historyTick, 30_000L)
        }
    }

    private fun reportPlayHistory(status: Int) {
        // 本地文件播放不向 B 站上报观看进度
        if (isLocalMode) return
        val item = currentDetail ?: return
        val player = exoPlayer ?: return
        val aid = item.aid
        val cid = item.cid
        if (aid <= 0L || cid <= 0L) return
        val progress = (player.currentPosition / 1000L).coerceAtLeast(0L)
        val duration = (player.duration / 1000L).coerceAtLeast(0L)
        BiliApi.reportHistory(aid, cid, progress, duration, status)
    }

    companion object {
        const val EXTRA_BVID = "bvid"
        const val EXTRA_AID = "aid"
        const val EXTRA_LOCAL_TASK_ID = "local_task_id"
        /** Comments longer than this start collapsed with an expand control. */
        private const val COMMENT_COLLAPSE_CHARS = 50
        /** Approximate visible lines while collapsed (about 50 CJK chars). */
        private const val COMMENT_COLLAPSE_LINES = 3
        /** Shared bounded pool for inline emote image loads. */
        private val EMOTE_POOL: ExecutorService = Executors.newFixedThreadPool(2)
        /** Brightness/volume indicator scale when the player is NOT fullscreen. */
        private const val COMPACT_ADJUST_SCALE = 0.68f
        private const val KEY_LAST_QUALITY = "last_quality"
        private val SUPPORTED_QUALITIES = setOf(
            BiliPlayUrl.QUALITY_360, BiliPlayUrl.QUALITY_480, BiliPlayUrl.QUALITY_720,
            BiliPlayUrl.QUALITY_1080, BiliPlayUrl.QUALITY_1080_PLUS, BiliPlayUrl.QUALITY_4K,
            116, 125, 126, 127
        )
        // Action bar icon kinds.
        const val KIND_LIKE = 0
        const val KIND_FAVORITE = 1
        const val KIND_DOWNLOAD = 2
        const val KIND_SHARE = 3
        fun open(context: Context, bvid: String, aid: Long = 0L) {
            if (bvid.isNotBlank() || aid > 0L)
                context.startActivity(Intent(context, VideoDetailActivity::class.java)
                    .putExtra(EXTRA_BVID, bvid)
                    .putExtra(EXTRA_AID, aid))
        }

        /** 播放本地下载任务（复用同一播放器，本地模式只显示标题/大小/时长）。 */
        fun openLocal(context: Context, taskId: Long) {
            context.startActivity(Intent(context, VideoDetailActivity::class.java)
                .putExtra(EXTRA_LOCAL_TASK_ID, taskId))
        }

        /** Shared OkHttp client: keeps the connection pool & TLS sessions alive across players,
         *  so replaying or switching quality skips TCP/TLS handshakes. */
        private val mediaOkClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                // Never cap the total lifetime of a long Range request: callTimeout
                // previously killed valid playback after 25 seconds on slow links.
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", CDN_USER_AGENT)
                        .header("Referer", "https://www.bilibili.com/")
                        .header("Origin", "https://www.bilibili.com")
                        .header("Accept", "*/*")
                        // Never inject API cookies (CURRENT_FNVAL / SESSDATA) here:
                        // this client only serves CDN media requests, and CDN nodes
                        // reject such cookies when the buvid fingerprint is missing.
                        // Playback requests set their own minimal cookie explicitly.
                        .build()
                    chain.proceed(request)
                }.build()
        }
    }
}