package com.yuilittle.bili

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/**
 * Profile sub pages: watch history (local), favorites (Bilibili API),
 * downloads (empty state), WeChat sponsor QR, and about.
 */
class ProfileSubActivity : Activity() {

    private var mode = MODE_HISTORY
    private lateinit var listBox: LinearLayout

    // ── B 站历史游标分页状态 ──
    private var historyCursorMax = 0L
    private var historyCursorViewAt = 0L
    private var historyCursorBusiness = "archive"
    private var historyEnded = false
    private var historyLoading = false
    private var loadingRow: View? = null
    private var historyScroll: ScrollView? = null
    private val historySeen = HashSet<String>()
    private var historyRowAnimIndex = 0
    private val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    /** 收藏夹详情态：顶栏返回应回到「我的收藏」列表，而不是直接 finish 回个人页。 */
    private var inFavoriteFolder = false
    private var currentFolderName: String? = null
    private lateinit var titleView: TextView
    private var searchBtnRef: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_HISTORY
        Theme.applySystemBars(this)

        val root = FrameLayout(this).apply { setBackgroundColor(COLOR_BACKGROUND) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── Title bar ─────────────────────────────────────────────
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(TextView(this@ProfileSubActivity).apply {
                text = "‹"
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(COLOR_INK)
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener { handleBack() }
            }, LinearLayout.LayoutParams(dp(46), dp(42)))
            titleView = TextView(this@ProfileSubActivity).apply {
                text = titleFor(mode)
                textSize = 17f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(COLOR_INK)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (mode == MODE_HISTORY || mode == MODE_FAVORITES) {
                // 右上角：统一线条风格搜索图标
                val searchBtn = FrameLayout(this@ProfileSubActivity).apply {
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(10), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        animate().scaleX(0.88f).scaleY(0.88f).setDuration(90)
                            .withEndAction {
                                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                                if (mode == MODE_HISTORY) openHistorySearch()
                                else openFavoriteSearch()
                            }.start()
                    }
                }
                searchBtn.addView(
                    HistorySearchIcon(this@ProfileSubActivity),
                    FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                )
                searchBtnRef = searchBtn
                addView(searchBtn, LinearLayout.LayoutParams(dp(46), dp(42)))
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(View(this).apply { setBackgroundColor(COLOR_BORDER) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = false
        }
        historyScroll = scroll
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (mode != MODE_HISTORY || historyEnded || historyLoading) return@setOnScrollChangeListener
            val child = scroll.getChildAt(0) ?: return@setOnScrollChangeListener
            // 距底部不足 240dp 就预取下一页，保证能一直往下刷
            if (child.bottom <= scroll.height + scroll.scrollY + dp(240)) {
                if (loadingRow == null) {
                    loadingRow = loadMoreHint("正在加载…")
                    listBox.addView(loadingRow)
                }
                loadHistoryPage()
            }
        }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }
        scroll.addView(listBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        column.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        when (mode) {
            MODE_HISTORY -> renderHistory()
            MODE_FAVORITES -> loadFavorites()
            MODE_DOWNLOADS -> renderDownloads()
            MODE_SPONSOR -> renderSponsor()
            else -> renderAbout()
        }
    }

    private fun titleFor(mode: String): String = when (mode) {
        MODE_FAVORITES -> "我的收藏"
        MODE_DOWNLOADS -> "下载管理"
        MODE_SPONSOR -> "赞助开发"
        MODE_ABOUT -> "关于软件"
        else -> "历史记录"
    }

    // ── History (Bilibili API when logged in, local fallback) ─────

    private fun renderHistory() {
        listBox.removeAllViews()
        historySeen.clear()
        historyRowAnimIndex = 0
        if (!BiliSessionStore.isLoggedIn()) {
            renderLocalHistory()
            return
        }
        historyCursorMax = 0L
        historyCursorViewAt = 0L
        historyCursorBusiness = "archive"
        historyEnded = false
        historyLoading = false
        loadingRow = null
        listBox.addView(loadingHint())
        loadHistoryPage()
    }

    /** 游标分页加载 B 站历史：第一页替换，后续页追加，滚动到底自动翻页。 */
    private fun loadHistoryPage() {
        if (historyLoading || historyEnded) return
        historyLoading = true
        val firstPage = historyCursorMax == 0L && historyCursorViewAt == 0L && historySeen.isEmpty()
        if (firstPage) {
            listBox.removeAllViews()
            listBox.addView(loadingHint())
        }
        BiliApi.fetchHistory(
            historyCursorMax,
            historyCursorViewAt,
            historyCursorBusiness
        ) { videos, error, nextMax, nextViewAt, nextBusiness, ended ->
            if (isFinishing || isDestroyed) return@fetchHistory
            historyLoading = false
            loadingRow?.let { runCatching { listBox.removeView(it) } }
            loadingRow = null
            if (firstPage) listBox.removeAllViews()
            if (error != null) {
                if (firstPage) {
                    // 第一页失败：回退本地镜像，保证页面不为空。
                    renderLocalHistory()
                } else {
                    //  freestanding 失败不立刻结束，允许用户再滑触发重试
                    listBox.addView(loadMoreHint("加载失败，上滑重试"))
                }
                return@fetchHistory
            }
            if (firstPage && videos.isEmpty()) {
                historyEnded = true
                listBox.addView(emptyHint("暂无观看记录\n看完的视频会同步到 B 站历史"))
                return@fetchHistory
            }
            var added = 0
            videos.forEach { item ->
                if (!historySeen.add(item.bvid)) return@forEach
                val timeText = if (item.publishedAt > 0L) {
                    android.text.format.DateFormat.format(
                        "MM-dd HH:mm",
                        java.util.Date(item.publishedAt * 1000L)
                    ).toString()
                } else ""
                val row = buildRow(item.bvid, item.title, item.owner, item.cover, timeText)
                listBox.addView(row)
                listBox.addView(rowDivider())
                animateHistoryRowIn(row, historyRowAnimIndex++)
                added++
            }
            // 游标必须原样推进（含 business），否则下一页会重复第一页
            historyCursorMax = nextMax
            historyCursorViewAt = nextViewAt
            historyCursorBusiness = nextBusiness.ifBlank { historyCursorBusiness }
            historyEnded = ended
            if (historyEnded) {
                if (!firstPage || added > 0) listBox.addView(loadMoreHint("没有更多了"))
            } else if (added == 0 && !ended) {
                // 本页全是重复/非视频，立刻再拉，避免用户感觉卡死
                listBox.post { loadHistoryPage() }
            }
        }
    }

    private fun renderLocalHistory() {
        listBox.removeAllViews()
        historySeen.clear()
        historyRowAnimIndex = 0
        val history = HistoryStore.load(this)
        if (history.isEmpty()) {
            listBox.addView(emptyHint("还没有观看记录\n看完的视频会出现在这里"))
            return
        }
        history.forEach { item ->
            val row = buildRow(item.bvid, item.title, item.owner, item.cover, item.date)
            listBox.addView(row)
            listBox.addView(rowDivider())
            animateHistoryRowIn(row, historyRowAnimIndex++)
        }
    }

    private fun openHistorySearch() {
        startActivity(Intent(this, HistorySearchActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left_soft)
    }

    /** 列表行入场：轻微上浮 + 淡入，错峰 28ms，克制不花哨。 */
    private fun animateHistoryRowIn(row: View, index: Int) {
        row.alpha = 0f
        row.translationY = dp(14).toFloat()
        row.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index.coerceAtMost(12) * 28L))
            .setDuration(280L)
            .setInterpolator(easeOut)
            .start()
    }

    /** 统一线条搜索图标（与全 App 1.7dp stroke 规范一致）。 */
    private class HistorySearchIcon(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ROSE
            style = Paint.Style.STROKE
            strokeWidth = 1.7f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) * 0.28f
            canvas.drawCircle(cx - r * 0.18f, cy - r * 0.18f, r, paint)
            canvas.drawLine(
                cx + r * 0.55f, cy + r * 0.55f,
                cx + r * 1.15f, cy + r * 1.15f,
                paint
            )
        }
    }

    // ── Favorites (API) ──────────────────────────────────────────

    private fun loadFavorites() {
        inFavoriteFolder = false
        currentFolderName = null
        if (::titleView.isInitialized) titleView.text = titleFor(MODE_FAVORITES)
        searchBtnRef?.visibility = View.VISIBLE
        listBox.removeAllViews()
        listBox.addView(loadingHint())
        if (!BiliSessionStore.isLoggedIn()) {
            listBox.removeAllViews()
            listBox.addView(emptyHint("请先扫码登录后再查看收藏"))
            return
        }
        BiliApi.fetchFavoriteFolders { folders, error ->
            if (isFinishing || isDestroyed) return@fetchFavoriteFolders
            if (error != null || folders.isEmpty()) {
                listBox.removeAllViews()
                listBox.addView(emptyHint(error ?: "还没有收藏夹"))
                return@fetchFavoriteFolders
            }
            listBox.removeAllViews()
            folders.forEachIndexed { index, folder ->
                val card = buildFavoriteFolderCard(folder)
                listBox.addView(
                    card,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { if (index > 0) topMargin = dp(6) }
                )
                animateHistoryRowIn(card, index)
            }
        }
    }

    /**
     * 收藏夹卡片：名称 + 数量；下方最多 3 个横向封面预览，封面下显示标题前缀。
     * 少于 3 个就有几个展示几个。点击整卡进入该收藏夹详情列表。
     */
    private fun buildFavoriteFolderCard(folder: FavoriteFolder): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_CARD, dp(16), COLOR_CARD_BORDER, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { openFavoriteFolder(folder) }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = folder.name
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply {
            text = "${folder.count} 个内容"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(dp(8), 0, 0, 0)
        })
        titleRow.addView(TextView(this).apply {
            text = "›"
            textSize = 18f
            setTextColor(COLOR_MUTED)
            setPadding(dp(4), 0, 0, 0)
        })
        card.addView(titleRow)

        val previewHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(
            previewHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 先放 3 个骨架位，再异步填封面；不足 3 个的会在回调里隐藏
        val slots = ArrayList<Triple<ImageView, TextView, LinearLayout>>(3)
        repeat(3) { i ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.INVISIBLE
            }
            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(COLOR_COVER, dp(8), COLOR_CARD_BORDER, 1)
                clipToOutline = true
            }
            val title = TextView(this).apply {
                textSize = 11f
                setTextColor(COLOR_MUTED)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
            col.addView(cover, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ))
            col.addView(title, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            previewHost.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
            slots += Triple(cover, title, col)
        }

        if (folder.count > 0L) {
            BiliApi.fetchFavoriteVideos(folder.id, page = 1, pageSize = 3) { videos, _, _ ->
                if (isFinishing || isDestroyed) return@fetchFavoriteVideos
                val take = videos.take(3)
                slots.forEachIndexed { i, (cover, title, col) ->
                    if (i < take.size) {
                        val v = take[i]
                        col.visibility = View.VISIBLE
                        col.alpha = 0f
                        if (v.cover.isNotBlank()) {
                            CoverLoader.load(cover, v.cover + "@240w_135h.webp")
                        }
                        title.text = v.title
                        col.setOnClickListener {
                            startActivity(
                                Intent(this@ProfileSubActivity, VideoDetailActivity::class.java)
                                    .putExtra(VideoDetailActivity.EXTRA_BVID, v.bvid)
                            )
                        }
                        col.animate().alpha(1f).setDuration(220L)
                            .setStartDelay(i * 40L).setInterpolator(easeOut).start()
                    } else {
                        col.visibility = View.GONE
                    }
                }
                if (take.isEmpty()) {
                    previewHost.visibility = View.GONE
                }
            }
        } else {
            previewHost.visibility = View.GONE
            card.addView(TextView(this).apply {
                text = "还没有内容"
                textSize = 12f
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(8), 0, 0)
            })
        }
        return card
    }

    private fun openFavoriteFolder(folder: FavoriteFolder) {
        inFavoriteFolder = true
        currentFolderName = folder.name
        if (::titleView.isInitialized) titleView.text = folder.name
        // 夹内详情不需要全收藏搜索入口，避免层级混乱
        searchBtnRef?.visibility = View.GONE
        listBox.removeAllViews()
        listBox.addView(loadingHint())
        // 详情列表：顶栏返回回到「我的收藏」夹列表
        BiliApi.fetchFavoriteVideos(folder.id, page = 1, pageSize = 40) { videos, error, _ ->
            if (isFinishing || isDestroyed) return@fetchFavoriteVideos
            listBox.removeAllViews()
            listBox.addView(TextView(this).apply {
                text = "${folder.count} 个内容"
                textSize = 12f
                setTextColor(COLOR_MUTED)
                setPadding(0, 0, 0, dp(8))
            })
            if (error != null) {
                listBox.addView(emptyHint(error))
                return@fetchFavoriteVideos
            }
            if (videos.isEmpty()) {
                listBox.addView(emptyHint("这个收藏夹还是空的"))
                return@fetchFavoriteVideos
            }
            videos.forEachIndexed { index, video ->
                val row = buildRow(
                    video.bvid, video.title, video.owner, video.cover,
                    formatShortDate(video.publishedAt)
                )
                listBox.addView(row)
                listBox.addView(rowDivider())
                animateHistoryRowIn(row, index)
            }
        }
    }

    /** 顶栏/系统返回：夹详情 → 收藏列表 → 退出页面。 */
    private fun handleBack() {
        if (mode == MODE_FAVORITES && inFavoriteFolder) {
            loadFavorites()
            return
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode == MODE_FAVORITES && inFavoriteFolder) {
            loadFavorites()
            return
        }
        super.onBackPressed()
    }

    private fun openFavoriteSearch() {
        startActivity(Intent(this, FavoriteSearchActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left_soft)
    }

    // ── Downloads (empty state) ──────────────────────────────────

    private fun renderDownloads() {
        listBox.removeAllViews()
        listBox.addView(emptyHint("暂无下载任务\n下载功能即将上线，敬请期待"))
    }

    // ── Sponsor (WeChat only, keep it plain) ─────────────────────

    private fun renderSponsor() {
        listBox.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(COLOR_CARD, dp(16), COLOR_CARD_BORDER, dp(1))
            setPadding(dp(22), dp(24), dp(22), dp(22))
        }
        card.addView(TextView(this).apply {
            text = "微信赞助"
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_INK)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(TextView(this).apply {
            text = "目前仅支持微信扫码"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val qrFrame = FrameLayout(this).apply {
            background = rounded(0xFFFFFFFF.toInt(), dp(12), COLOR_CARD_BORDER, dp(1))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val qrImage = ImageView(this).apply {
            setImageResource(R.drawable.weixin_sponsor_qr)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "微信收款码"
            isClickable = true
            isLongClickable = true
            setOnLongClickListener {
                downloadSponsorQr()
                true
            }
        }
        qrFrame.addView(qrImage, FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER))
        card.addView(qrFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        card.addView(TextView(this).apply {
            text = "打开微信扫一扫上方二维码即可赞助\n截图可能扫不出来，建议下载后发给对方"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(16), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Download button — same style as login QR download.
        card.addView(TextView(this).apply {
            text = "下载收款码"
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(COLOR_ROSE, dp(22))
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener { downloadSponsorQr() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        listBox.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })
    }

    private fun downloadSponsorQr() {
        val bitmap = try {
            BitmapFactory.decodeResource(resources, R.drawable.weixin_sponsor_qr)
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) {
            Toast.makeText(this, "收款码加载失败", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "YuiBili微信收款码_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YuiBili")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    Toast.makeText(this, "已下载到相册（Pictures/YuiBili）", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    contentResolver.delete(uri, null, null)
                    Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    0x51A2
                )
                Toast.makeText(this, "请允许存储权限后再次点击下载", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "YuiBili"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                sendBroadcast(
                    Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(file))
                )
                Toast.makeText(this, "已下载到相册（Pictures/YuiBili）", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── About ────────────────────────────────────────────────────

    /** Changelog panel: shows remote updateLog, then system download on confirm. */
    private fun showUpdatePanel(info: UpdateChecker.UpdateInfo) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = rounded(COLOR_CARD, dp(18), COLOR_CARD_BORDER, dp(1))
        }

        val local = UpdateChecker.localVersion(this)
        val verLabel = info.latestVersionName.ifBlank { info.latestVersionCode.toString() }

        root.addView(TextView(this).apply {
            text = "发现新版本"
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_INK)
        })
        root.addView(TextView(this).apply {
            text = "当前 ${local.versionName}  →  $verLabel"
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(6), 0, 0)
        })

        val logBox = ScrollView(this).apply {
            isFillViewport = false
            setPadding(0, dp(14), 0, 0)
        }
        val logText = TextView(this).apply {
            text = info.updateLog.ifBlank { "有新版本可用" }
            textSize = 14f
            setTextColor(COLOR_INK)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        logBox.addView(
            logText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // Cap changelog height so long logs still scroll inside the panel.
        root.addView(
            logBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(180)
            )
        )

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        val later = TextView(this).apply {
            text = "稍后"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val go = TextView(this).apply {
            text = "立即更新"
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(COLOR_ROSE, dp(12))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            setOnClickListener {
                dialog.dismiss()
                UpdateChecker.startSystemDownload(this@ProfileSubActivity, info)
            }
        }
        btnRow.addView(later)
        btnRow.addView(
            go,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
        )
        root.addView(
            btnRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.86f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun renderAbout() {
        listBox.removeAllViews()
        val local = UpdateChecker.localVersion(this)
        listBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(28), 0, 0)
            addView(LogoView(this@ProfileSubActivity), LinearLayout.LayoutParams(dp(76), dp(76)))
            addView(TextView(this@ProfileSubActivity).apply {
                text = "YuiBili"
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(COLOR_INK)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@ProfileSubActivity).apply {
                text = "版本 ${local.versionName} (${local.versionCode})"
                textSize = 12f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@ProfileSubActivity).apply {
                text = "一款简洁的哔哩哔哩视频客户端\n支持扫码登录、高清播放、评论互动与夜间模式"
                textSize = 13f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, dp(20), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            // Update action: label shows target version when a newer build is known.
            val updateBtn = TextView(this@ProfileSubActivity).apply {
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = rounded(COLOR_ROSE, dp(14))
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
            }
            fun bindUpdateButton(live: UpdateChecker.UpdateInfo? = null) {
                val localNow = UpdateChecker.localVersion(this@ProfileSubActivity)
                // Prefer the just-fetched live result; fall back to session snapshot.
                val candidate = live ?: UpdateChecker.pendingUpdate(this@ProfileSubActivity)
                if (candidate != null && UpdateChecker.isNewer(localNow, candidate)) {
                    val label = candidate.latestVersionName.ifBlank {
                        candidate.latestVersionCode.toString()
                    }
                    updateBtn.text = "更新到 $label"
                    updateBtn.alpha = 1f
                    updateBtn.isEnabled = true
                    updateBtn.setOnClickListener {
                        showUpdatePanel(candidate)
                    }
                } else {
                    updateBtn.text = "已是最新版本"
                    updateBtn.alpha = 0.72f
                    updateBtn.isEnabled = true
                    updateBtn.setOnClickListener {
                        Toast.makeText(this@ProfileSubActivity, "正在检查更新…", Toast.LENGTH_SHORT).show()
                        UpdateChecker.refreshSilent(this@ProfileSubActivity, reason = "about-tap") { info, error ->
                            if (isFinishing) return@refreshSilent
                            if (info == null) {
                                Toast.makeText(
                                    this@ProfileSubActivity,
                                    error ?: "检查更新失败，请稍后重试",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@refreshSilent
                            }
                            val now = UpdateChecker.localVersion(this@ProfileSubActivity)
                            if (UpdateChecker.isNewer(now, info)) {
                                bindUpdateButton(info)
                            } else {
                                Toast.makeText(
                                    this@ProfileSubActivity,
                                    "已是最新版本 ${now.versionName} (${now.versionCode})",
                                    Toast.LENGTH_SHORT
                                ).show()
                                bindUpdateButton(info)
                            }
                        }
                    }
                }
            }
            // Loading state until the live fetch returns.
            updateBtn.text = "正在检查更新…"
            updateBtn.alpha = 0.72f
            updateBtn.isEnabled = false
            updateBtn.setOnClickListener(null)
            // Always live-fetch when opening About; bind from the callback result.
            UpdateChecker.refreshSilent(this@ProfileSubActivity, reason = "about-open") { info, error ->
                if (isFinishing) return@refreshSilent
                if (info == null) {
                    // Keep a tappable "retry" surface on failure.
                    updateBtn.text = "检查失败，点此重试"
                    updateBtn.alpha = 1f
                    updateBtn.isEnabled = true
                    updateBtn.setOnClickListener {
                        Toast.makeText(
                            this@ProfileSubActivity,
                            error ?: "检查更新失败，请稍后重试",
                            Toast.LENGTH_SHORT
                        ).show()
                        UpdateChecker.refreshSilent(this@ProfileSubActivity, reason = "about-retry") { again, _ ->
                            if (!isFinishing) bindUpdateButton(again)
                        }
                    }
                } else {
                    bindUpdateButton(info)
                }
            }
            addView(updateBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(22)
                leftMargin = dp(28)
                rightMargin = dp(28)
            })

            addView(View(this@ProfileSubActivity).apply { setBackgroundColor(COLOR_BORDER) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(24)
                })
            addView(TextView(this@ProfileSubActivity).apply {
                text = "免责声明：本软件仅供学习交流使用，视频内容版权归原作者及哔哩哔哩所有。\n\n请遵守相关法律法规，理性上网。"
                textSize = 12f
                setTextColor(COLOR_MUTED)
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, dp(16), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    // ── Shared builders ──────────────────────────────────────────

    /** 行间分隔线：历史记录/收藏列表的 hairline。细(1px)但颜色可见，不随全局 COLOR_BORDER 变化。 */
    private fun rowDivider(): View = View(this).apply {
        setBackgroundColor(if (Theme.isDark) 0x40FFFFFF.toInt() else 0x2E000000.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun buildRow(bvid: String, title: String, owner: String, cover: String, date: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@ProfileSubActivity, VideoDetailActivity::class.java)
                    .putExtra(VideoDetailActivity.EXTRA_BVID, bvid))
            }
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(COLOR_COVER, dp(8), COLOR_CARD_BORDER, dp(1))
            clipToOutline = true
        }
        if (cover.isNotBlank()) CoverLoader.load(thumb, cover + "@320w_180h.webp")
        row.addView(thumb, LinearLayout.LayoutParams(dp(112), dp(63)))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        body.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(TextView(this).apply {
            text = if (date.isBlank()) owner else "$owner · $date"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun emptyHint(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(COLOR_MUTED)
        gravity = Gravity.CENTER
        setLineSpacing(dp(5).toFloat(), 1f)
        setPadding(0, dp(72), 0, 0)
    }

    private fun loadingHint(): View = TextView(this).apply {
        text = "正在加载…"
        textSize = 13f
        setTextColor(COLOR_MUTED)
        gravity = Gravity.CENTER
        setPadding(0, dp(72), 0, 0)
    }

    /** 列表底部提示行（加载中 / 没有更多了 / 加载失败）。 */
    private fun loadMoreHint(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(COLOR_MUTED)
        gravity = Gravity.CENTER
        setPadding(0, dp(16), 0, dp(16))
    }

    private fun formatShortDate(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return ""
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date(epochSeconds * 1000L))
    }

    private inner class LogoView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density: Float = resources.displayMetrics.density
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 34f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = Math.min(width, height) / 2f
            paint.color = COLOR_ROSE
            canvas.drawRoundRect(cx - r, cy - r, cx + r, cy + r, 18f * density, 18f * density, paint)
            val base = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("Y", cx, base, textPaint)
        }
    }

    companion object {
        const val EXTRA_MODE = "profile_sub_mode"
        const val MODE_HISTORY = "history"
        const val MODE_FAVORITES = "favorites"
        const val MODE_DOWNLOADS = "downloads"
        const val MODE_SPONSOR = "sponsor"
        const val MODE_ABOUT = "about"
    }
}

/** Local watch history: JSON array of {bvid,title,owner,cover,date}. */
object HistoryStore {
    private const val PREFS = "yuibili_history"
    private const val KEY = "items"
    private const val MAX = 100

    data class Entry(val bvid: String, val title: String, val owner: String, val cover: String, val date: String)

    fun add(context: Context, bvid: String, title: String, owner: String, cover: String) {
        if (bvid.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = try { JSONArray(prefs.getString(KEY, "[]").orEmpty()) } catch (_: Exception) { JSONArray() }
        val filtered = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optString("bvid") != bvid) filtered.put(obj)
        }
        val head = JSONArray().put(JSONObject().apply {
            put("bvid", bvid)
            put("title", title)
            put("owner", owner)
            put("cover", cover)
            put("date", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date()))
        })
        for (i in 0 until Math.min(filtered.length(), MAX - 1)) head.put(filtered.get(i))
        prefs.edit().putString(KEY, head.toString()).apply()
    }

    fun load(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = try { JSONArray(prefs.getString(KEY, "[]").orEmpty()) } catch (_: Exception) { JSONArray() }
        val result = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            result += Entry(
                bvid = obj.optString("bvid"),
                title = obj.optString("title").ifBlank { "（无标题）" },
                owner = obj.optString("owner").ifBlank { "未知UP主" },
                cover = obj.optString("cover"),
                date = obj.optString("date")
            )
        }
        return result
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
