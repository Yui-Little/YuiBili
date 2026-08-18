package com.yuilittle.bili

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 底栏「下载」页：顶部存储卡 + 分类导航（视频/音频/封面/评论）+ 单列任务列表。
 * 列表行：封面 | 标题+进度+状态 | 状态图标+操作图标；左滑露出「重命名/删除」。
 * 断点续传：DASH 三段式与 durl 单文件流均支持重试续传（引擎层）。
 */
class DownloadPageView(context: Context) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val storageIo = Executors.newSingleThreadExecutor()

    private val tabs = listOf(
        DownloadManager.TYPE_VIDEO to "视频",
        DownloadManager.TYPE_AUDIO to "音频",
        DownloadManager.TYPE_COVER to "封面"
    )
    private var currentTab = DownloadManager.TYPE_VIDEO

    private val tabTexts = HashMap<Int, TextView>()
    private val tabCounts = HashMap<Int, TextView>()
    private val tabIndicators = HashMap<Int, View>()

    private lateinit var multiBtn: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var emptyView: LinearLayout
    private lateinit var emptyTitle: TextView

    /** 多选状态变化回调：宿主（MainActivity）据此暂时隐藏/恢复底部导航栏。 */
    var onSelectionModeChanged: ((Boolean) -> Unit)? = null

    /** 多选模式：顶栏「多选」切换，底部出现全选/删除栏；选中行高亮。 */
    private var selectionMode = false
    private val selected = HashSet<Long>()
    private var bottomBar: LinearLayout? = null
    private var selectAllBtn: TextView? = null
    private var deleteBtn: TextView? = null

    /** 「所有文件访问」权限提示条：部分 ROM 上 MediaStore 删除会残留 .trashed 文件，需要该权限物理删除。 */
    private var storagePermissionHint: TextView? = null

    private val storageTitle = TextView(context)
    private val storageFree = TextView(context)
    private val storageBar = StorageUsageBar(context)
    private val storageOther = TextView(context)
    private val storageApp = TextView(context)
    private val storageFreeLabel = TextView(context)

    private var lastNotifyAt = 0L
    private var lastRowStates = emptySet<Pair<Long, Int>>()

    /** 行就地更新所需的轻量句柄。 */
    private class RowHolder(val id: Long, val progress: ProgressBarView, val status: TextView)

    /** 合集聚合卡片的轻量句柄。 */
    private class GroupHolder(val groupId: String, val progress: ProgressBarView, val status: TextView)

    init {
        buildUi()
        DownloadManager.addListener(::onTasksChanged)
        refreshStorage(force = true)
        rebuild()
    }

    // ── UI 骨架 ──────────────────────────────────────────────

    private fun buildUi() {
        setBackgroundColor(COLOR_BACKGROUND)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 顶栏
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        header.addView(TextView(context).apply {
            text = "下载"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        multiBtn = TextView(context).apply {
            text = "多选"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ROSE)
            gravity = Gravity.CENTER
            background = rounded(COLOR_ROSE_SOFT, dp(13))
            setPadding(dp(14), dp(7), dp(14), dp(7))
            visibility = View.GONE
            setOnClickListener { setSelectionMode(!selectionMode) }
        }
        header.addView(multiBtn)

        // 存储卡
        val storageCard = buildStorageCard()
        storageCard.visibility = View.GONE
        storageCard.post { storageCard.visibility = View.VISIBLE }

        // 分类导航
        val tabBar = buildTabBar()

        // 内容区
        contentScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }
        contentScroll.addView(listContainer, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 空态
        emptyView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        emptyView.addView(DownloadGlyph(context), LinearLayout.LayoutParams(dp(64), dp(64)))
        emptyTitle = TextView(context).apply {
            textSize = 13f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
        }
        emptyView.addView(emptyTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        val contentArea = FrameLayout(context)
        contentArea.addView(contentScroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentArea.addView(emptyView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(storageCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(2)
        })

        // ── 「所有文件访问」权限提示条：部分 ROM 的 MediaStore 删除只进回收站（.trashed 残留），
        // 授予该权限后 App 才能物理删除相册副本。 ──
        storagePermissionHint = TextView(context).apply {
            text = "授予「所有文件访问」权限后，删除任务可彻底清理相册中的文件 ›"
            textSize = 11f
            setTextColor(0xFFB8860B.toInt())
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x22FFB300.toInt(), dp(12), 0x44FFB300.toInt(), 1)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener { openStoragePermissionSettings() }
            visibility = if (Build.VERSION.SDK_INT >= 30 &&
                !Environment.isExternalStorageManager()) View.VISIBLE else View.GONE
        }
        root.addView(storagePermissionHint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(8)
        })

        root.addView(tabBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        root.addView(contentArea, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 底部多选操作栏（默认隐藏）──
        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(COLOR_CARD, dp(18), COLOR_CARD_BORDER, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        selectAllBtn = TextView(context).apply {
            text = "全选"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ROSE)
            gravity = Gravity.CENTER
            background = rounded(COLOR_ROSE_SOFT, dp(12))
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener {
                val tasks = DownloadManager.snapshot().filter { it.type == currentTab }
                val allSelected = tasks.isNotEmpty() && tasks.all { selected.contains(it.id) }
                if (allSelected) selected.clear() else tasks.forEach { selected.add(it.id) }
                rebuild()
                refreshSelectionUi()
            }
        }
        bottomBar!!.addView(selectAllBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        bottomBar!!.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        deleteBtn = TextView(context).apply {
            text = "删除"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(0xFFE0556B.toInt(), dp(12))
            setPadding(dp(22), dp(9), dp(22), dp(9))
            setOnClickListener { confirmDeleteMany(selected.toList()) }
        }
        bottomBar!!.addView(deleteBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(bottomBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        tabs.forEach { (type, label) ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
                setOnClickListener { switchTab(type) }
            }
            val tv = TextView(context).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (type == currentTab) COLOR_ROSE else COLOR_MUTED)
            }
            val count = TextView(context).apply {
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(COLOR_MUTED)
            }
            val indicator = View(context).apply {
                background = rounded(COLOR_ROSE, dp(2))
                alpha = if (type == currentTab) 1f else 0f
            }
            item.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            item.addView(count, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(1)
            })
            item.addView(indicator, LinearLayout.LayoutParams(dp(34), dp(3)).apply {
                topMargin = dp(6)
            })
            bar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            tabTexts[type] = tv
            tabCounts[type] = count
            tabIndicators[type] = indicator
        }
        return bar
    }

    private fun switchTab(type: Int) {
        if (currentTab == type) return
        currentTab = type
        // 切换分类时退出多选模式，避免跨分类误删
        if (selectionMode) {
            selectionMode = false
            selected.clear()
            multiBtn.text = "多选"
            multiBtn.setTextColor(COLOR_ROSE)
            multiBtn.background = rounded(COLOR_ROSE_SOFT, dp(13))
            bottomBar?.visibility = View.GONE
            onSelectionModeChanged?.invoke(false)
        }
        tabs.forEach { (t, _) ->
            tabTexts[t]?.setTextColor(if (t == type) COLOR_ROSE else COLOR_MUTED)
            tabIndicators[t]?.alpha = if (t == type) 1f else 0f
        }
        rebuild()
    }

    // ── 存储卡 ────────────────────────────────────────────────

    private fun buildStorageCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_CARD, dp(16), COLOR_CARD_BORDER, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        storageTitle.apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
        }
        storageFree.apply {
            textSize = 12f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.END
        }
        titleRow.addView(storageTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(storageFree)
        card.addView(titleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        storageBar.apply {
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(storageBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(9)).apply {
            topMargin = dp(10)
        })

        val legend = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        legend.addView(legendDot(0xFFA8D4F0.toInt()), LinearLayout.LayoutParams(dp(8), dp(8)))
        storageOther.apply {
            textSize = 11f
            setTextColor(COLOR_MUTED)
        }
        legend.addView(storageOther, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(5)
        })
        legend.addView(legendDot(COLOR_ROSE), LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            leftMargin = dp(14)
        })
        storageApp.apply {
            textSize = 11f
            setTextColor(COLOR_MUTED)
        }
        legend.addView(storageApp, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(5)
        })
        legend.addView(legendDot(COLOR_SOFT_FILL), LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            leftMargin = dp(14)
        })
        storageFreeLabel.apply {
            textSize = 11f
            setTextColor(COLOR_MUTED)
        }
        legend.addView(storageFreeLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(5)
        })
        card.addView(legend, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        return card
    }

    private fun legendDot(color: Int): View = View(context).apply {
        background = rounded(color, dp(4))
    }

    fun refreshStorage(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAt < 500L) return
        lastNotifyAt = now
        storageIo.execute {
            val (total, used, free) = try {
                val dir = Environment.getDataDirectory()
                val stat = StatFs(dir.path)
                val block = stat.blockSizeLong
                val total = stat.blockCountLong * block
                val free = stat.availableBlocksLong * block
                val used = total - free
                Triple(total, used, free)
            } catch (_: Throwable) {
                Triple(0L, 0L, 0L)
            }
            // YuiBili 占用 = 应用数据目录（含下载文件、数据库、缓存）+ APK 本体大小
            val appBytes = runCatching {
                val ctxApp = context.applicationContext
                val apkSize = listOfNotNull(ctxApp.applicationInfo.sourceDir)
                    .plus(ctxApp.applicationInfo.splitSourceDirs?.toList() ?: emptyList())
                    .filter { it.isNotBlank() && File(it).exists() }
                    .sumOf { File(it).length() }
                val dataSize = runCatching {
                    val d = ctxApp.dataDir
                    if (d.exists()) d.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                }.getOrDefault(0L)
                apkSize + dataSize
            }.getOrDefault(0L)
            val other = (used - appBytes).coerceAtLeast(0L)
            handler.post {
                storageTitle.text = "本地存储 · ${formatStorage(total)}"
                storageFree.text = "可用 ${formatStorage(free)}"
                storageOther.text = "其它 ${formatStorage(other)}"
                storageApp.text = "YuiBili ${formatStorage(appBytes)}"
                storageFreeLabel.text = "可用 ${formatStorage(free)}"
                storageBar.setValues(total, other, appBytes)
            }
        }
    }

    /** 容量展示：不足 1GB 用 MB 精确显示，避免小文件显示成 0.0 GB。 */
    private fun formatStorage(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(Locale.US, "%.1f GB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1024L * 1024 -> "${bytes / 1024 / 1024} MB"
        bytes >= 1024L -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    // ── 数据渲染 ──────────────────────────────────────────────

    private fun onTasksChanged(tasks: List<DownloadManager.Task>) {
        handler.post {
            // 只有当前 tab 的任务增删/状态切换才重建列表；纯进度变化就地刷新进度条与状态文字，
            // 避免下载中每几百毫秒全量重建导致行自动滑出/抽动。
            val currentTasks = tasks.filter { it.type == currentTab }
            val now = currentTasks.map { it.id to it.state }.toSet()
            if (now != lastRowStates || currentTasks.size != lastRowStates.size) {
                lastRowStates = now
                rebuild()
            } else {
                updateRows(currentTasks)
            }
            // 下载中文件在持续变大，同步刷新存储卡（内部有 500ms 节流）
            refreshStorage(force = false)
        }
    }

    private fun rebuild() {
        // 渲染异常不允许清空列表：removeAllViews 后任一 addView 失败都逐行兜底
        runCatching {
            val all = DownloadManager.snapshot()
            val tasks = all.filter { it.type == currentTab }
                .sortedWith(compareBy<DownloadManager.Task> {
                    when (it.state) {
                        DownloadManager.STATE_RUNNING -> 0
                        DownloadManager.STATE_QUEUED -> 1
                        DownloadManager.STATE_PAUSED -> 2
                        DownloadManager.STATE_FAILED -> 3
                        else -> 4
                    }
                }.thenByDescending { it.createdAt })

            // 分类计数
            tabs.forEach { (type, _) ->
                val n = all.count { it.type == type }
                tabCounts[type]?.text = if (n > 0) "$n" else ""
            }

            // 多选按钮：当前分类没有下载内容时隐藏
            val tabTasks = all.filter { it.type == currentTab }
            multiBtn.visibility = if (tabTasks.isEmpty()) View.GONE else View.VISIBLE
            // 当前分类被删空时自动退出多选模式
            if (selectionMode && tabTasks.isEmpty()) {
                selectionMode = false
                selected.clear()
                multiBtn.text = "多选"
                multiBtn.setTextColor(COLOR_ROSE)
                multiBtn.background = rounded(COLOR_ROSE_SOFT, dp(13))
                bottomBar?.visibility = View.GONE
                onSelectionModeChanged?.invoke(false)
            }

            listContainer.removeAllViews()
            if (tasks.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                emptyTitle.text = tabs.firstOrNull { it.first == currentTab }?.let {
                    "暂无${it.second}下载"
                } ?: "暂无下载"
            } else {
                emptyView.visibility = View.GONE
                // 所有 tab 都聚合合集任务：视频/音频/封面按 groupId 合并为合集卡片，
                // 点击进入合集详情页查看该合集对应类型的任务列表；其余显示单行。
                val groups = tasks.filter { it.groupId.isNotBlank() }.groupBy { it.groupId }
                val singles = tasks.filter { it.groupId.isBlank() }
                groups.values
                    .sortedBy { g -> g.minOfOrNull { it.createdAt } ?: 0L }
                    .forEach { g -> runCatching { listContainer.addView(buildGroupCard(g)) } }
                singles.forEach { runCatching { listContainer.addView(buildRow(it)) } }
            }
            lastRowStates = tasks.map { it.id to it.state }.toSet()
        }
    }

    /** 跳转系统「所有文件访问」授权页。 */
    private fun openStoragePermissionSettings() {
        runCatching {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
    }

    /** 授权返回后刷新提示条可见性。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            storagePermissionHint?.visibility =
                if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
                    View.VISIBLE else View.GONE
        }
    }

    /** 进入/退出多选模式（样式与合集下载详情页一致）。 */
    private fun setSelectionMode(on: Boolean) {
        selectionMode = on
        selected.clear()
        multiBtn.text = if (on) "取消" else "多选"
        multiBtn.setTextColor(if (on) COLOR_MUTED else COLOR_ROSE)
        multiBtn.background = rounded(
            if (on) COLOR_SOFT_FILL else COLOR_ROSE_SOFT, dp(13))
        bottomBar?.visibility = if (on) View.VISIBLE else View.GONE
        refreshSelectionUi()
        onSelectionModeChanged?.invoke(selectionMode)
        rebuild()
    }

    private fun refreshSelectionUi() {
        deleteBtn?.text = if (selected.isEmpty()) "删除" else "删除 (${selected.size})"
        deleteBtn?.alpha = if (selected.isEmpty()) 0.5f else 1f
        val tasks = DownloadManager.snapshot().filter { it.type == currentTab }
        val allSelected = tasks.isNotEmpty() && tasks.all { selected.contains(it.id) }
        selectAllBtn?.text = if (allSelected) "取消全选" else "全选"
    }

    /** 未授予「所有文件访问」权限时，删除后相册副本可能残留，弹窗里给出提示。 */
    private fun storagePermissionSuffix(): String =
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
            "\n\n未授予「所有文件访问」权限，相册中的文件可能无法彻底删除"
        else ""

    /** 批量删除二次确认（含已下载文件与相册副本）。 */
    private fun confirmDeleteMany(ids: List<Long>) {
        if (ids.isEmpty()) return
        val msg = TextView(context).apply {
            text = "确定删除选中的 ${ids.size} 个任务？\n已下载的文件也会一并删除。" + storagePermissionSuffix()
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(buildDialogBody(
            icon = GlyphView(context, GLYPH_TRASH, 0xFFD94A5C.toInt()),
            title = "删除 ${ids.size} 个任务",
            body = msg,
            confirmText = "删除",
            confirmBg = 0xFFD94A5C.toInt(),
            onCancel = { dialog.dismiss() },
            onConfirm = {
                ids.forEach { DownloadManager.delete(it) }
                setSelectionMode(false)
                refreshStorage(force = true)
                showToast("已删除 ${ids.size} 个任务")
                dialog.dismiss()
            }
        ))
        val w = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        dialog.window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    /** 纯进度变化：只刷新已有行（含合集卡片）的进度条与状态文字，不重建视图。 */
    private fun updateRows(tasks: List<DownloadManager.Task>) {
        val byId = tasks.associateBy { it.id }
        val byGroup = tasks.filter { it.groupId.isNotBlank() }.groupBy { it.groupId }
        for (i in 0 until listContainer.childCount) {
            val child = listContainer.getChildAt(i)
            val rowHolder = child.tag as? RowHolder
            if (rowHolder != null) {
                val t = byId[rowHolder.id] ?: continue
                rowHolder.progress.visibility = if (t.state == DownloadManager.STATE_COMPLETED) View.GONE else View.VISIBLE
                rowHolder.progress.setProgress(progressOf(t))
                val (text, color) = statusOf(t)
                rowHolder.status.text = text
                rowHolder.status.setTextColor(color)
                continue
            }
            val groupHolder = child.tag as? GroupHolder ?: continue
            val groupTasks = byGroup[groupHolder.groupId] ?: continue
            val done = groupTasks.sumOf { it.bytesDone.coerceAtLeast(0L) }
            val total = groupTasks.sumOf { it.bytesTotal.takeIf { b -> b > 0L } ?: 0L }
            val completedCount = groupTasks.count { it.state == DownloadManager.STATE_COMPLETED }
            val pct = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else if (completedCount == groupTasks.size) 100 else 0
            groupHolder.progress.visibility = if (completedCount == groupTasks.size) View.GONE else View.VISIBLE
            groupHolder.progress.setProgress(pct.toFloat())
            groupHolder.status.text = groupStatusText(groupTasks, done, total, completedCount)
            groupHolder.status.setTextColor(COLOR_MUTED)
        }
    }

    // ── 列表行 ────────────────────────────────────────────────

    private fun formatBytesShort(bytes: Long): String = formatBytes(bytes)

    /** 合集卡片的进度文案（视频=集、音频=个、封面=张）。 */
    private fun groupStatusText(
        groupTasks: List<DownloadManager.Task>,
        done: Long,
        total: Long,
        completedCount: Int
    ): String {
        val unit = when (groupTasks.firstOrNull()?.type) {
            DownloadManager.TYPE_AUDIO -> "音频"
            DownloadManager.TYPE_COVER -> "封面"
            else -> "集"
        }
        return if (completedCount == groupTasks.size) "已完成 ${completedCount}/${groupTasks.size} $unit"
        else "$completedCount/${groupTasks.size} $unit · ${formatBytesShort(done)}/${formatBytesShort(total)}"
    }

    /** 合集聚合卡片：显示合集标题、总大小、已下载大小与整体进度；点击进入合集详情页，左滑露出「重命名/删除」。
     *  视频/音频/封面 tab 共用：文案单位随任务类型变化。 */
    private fun buildGroupCard(groupTasks: List<DownloadManager.Task>): View {
        val first = groupTasks.first()
        val groupId = first.groupId
        val title = first.groupTitle.ifBlank { first.title }
        val coverUrl = first.groupCoverUrl.ifBlank { first.coverUrl }
        val sorted = groupTasks.sortedBy { it.episodeNo }
        val done = groupTasks.sumOf { it.bytesDone.coerceAtLeast(0L) }
        val total = groupTasks.sumOf { it.bytesTotal.takeIf { b -> b > 0L } ?: 0L }
        val completedCount = groupTasks.count { it.state == DownloadManager.STATE_COMPLETED }
        val pct = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100)
        else if (completedCount == groupTasks.size) 100 else 0
        val unit = when (first.type) {
            DownloadManager.TYPE_AUDIO -> "音频"
            DownloadManager.TYPE_COVER -> "封面"
            else -> "集"
        }
        val countHead = when (first.type) {
            DownloadManager.TYPE_AUDIO -> "${groupTasks.size} 个音频"
            DownloadManager.TYPE_COVER -> "${groupTasks.size} 张封面"
            else -> "${groupTasks.size} 集合集"
        }
        val statusText = groupStatusText(groupTasks, done, total, completedCount)

        val sw = resources.displayMetrics.widthPixels - dp(28)
        val actionsW = dp(136)
        val unitH = dp(76)

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            clipToOutline = true
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val groupSelected = selectionMode && sorted.all { selected.contains(it.id) }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            background = if (selectionMode && groupSelected)
                rounded(COLOR_ROSE_SOFT, dp(14), COLOR_ROSE, 1)
            else
                rounded(COLOR_CARD, dp(14), COLOR_CARD_BORDER, 1)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                if (scroll.scrollX > 0) {
                    scroll.smoothScrollTo(0, 0)
                    return@setOnClickListener
                }
                if (selectionMode) {
                    val allSel = sorted.all { selected.contains(it.id) }
                    if (allSel) sorted.forEach { selected.remove(it.id) }
                    else sorted.forEach { selected.add(it.id) }
                    val box = getChildAt(0) as? CheckBox
                    box?.isChecked = sorted.all { selected.contains(it.id) }
                    background = if (sorted.all { selected.contains(it.id) })
                        rounded(COLOR_ROSE_SOFT, dp(14), COLOR_ROSE, 1)
                    else
                        rounded(COLOR_CARD, dp(14), COLOR_CARD_BORDER, 1)
                    refreshSelectionUi()
                } else {
                    DownloadSeasonActivity.open(context, groupId, first.type)
                }
            }
        }
        // 多选勾选框（仅多选模式可见；点击由整卡处理，避免事件冲突）
        card.addView(CheckBox(context).apply {
            buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_checked),
                    intArrayOf(android.R.attr.state_checked)
                ),
                intArrayOf(COLOR_MUTED, COLOR_ROSE)
            )
            isChecked = groupSelected
            isClickable = false
            isFocusable = false
            visibility = if (selectionMode) View.VISIBLE else View.GONE
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val cover = FrameLayout(context).apply {
            background = rounded(COLOR_COVER, dp(10))
            clipToOutline = true
        }
        val coverImg = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        cover.addView(coverImg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        loadCoverUrl(coverImg, coverUrl)

        val mid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        mid.addView(TextView(context).apply {
            text = title
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_INK)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        mid.addView(TextView(context).apply {
            text = "$countHead · 点击查看列表"
            textSize = 11f; setTextColor(COLOR_MUTED)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        val progress = ProgressBarView(context)
        progress.visibility = if (completedCount == groupTasks.size) View.GONE else View.VISIBLE
        progress.setProgress(pct.toFloat())
        mid.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(6) })

        val status = TextView(context).apply {
            text = statusText
            textSize = 11f; setTextColor(COLOR_MUTED)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mid.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })

        card.addView(cover, LinearLayout.LayoutParams(dp(64), dp(40)))
        card.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        card.addView(TextView(context).apply {
            text = "›"
            textSize = 18f; setTextColor(COLOR_MUTED)
            setPadding(dp(6), 0, dp(2), 0)
        })

        // ── 左滑操作区：整体右圆角，重命名（灰）+ 删除（红），图标+文字竖排，与合集详情页一致 ──
        val renameFg = if (Theme.isDark) 0xFFE98BAA.toInt() else COLOR_ROSE
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(COLOR_SURFACE, dp(14))
            clipToOutline = true
        }
        fun actionUnit(label: String, glyph: Int, bg: Int, fg: Int, onClick: () -> Unit): View {
            val unit = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(bg, 0)
                isClickable = true
                setOnClickListener { onClick() }
            }
            unit.addView(GlyphView(context, glyph, fg), LinearLayout.LayoutParams(dp(19), dp(19)))
            unit.addView(TextView(context).apply {
                text = label
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(fg)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            })
            return unit
        }
        actions.addView(
            actionUnit("重命名", GLYPH_PENCIL, COLOR_SURFACE, renameFg) { showRenameGroupDialog(groupId, title) },
            LinearLayout.LayoutParams(dp(68), unitH))
        actions.addView(
            actionUnit("删除", GLYPH_TRASH, 0xFFE0556B.toInt(), Color.WHITE) { confirmDeleteGroup(sorted) },
            LinearLayout.LayoutParams(dp(68), unitH))

        inner.addView(card, LinearLayout.LayoutParams(sw, LinearLayout.LayoutParams.WRAP_CONTENT))
        inner.addView(actions, LinearLayout.LayoutParams(actionsW, LinearLayout.LayoutParams.WRAP_CONTENT))
        scroll.addView(inner)

        // 初始收起操作区，松手吸附（与单集行一致）
        scroll.scrollTo(0, 0)
        scroll.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val x = scroll.scrollX
                    if (x >= actionsW / 3) scroll.smoothScrollTo(actionsW, 0)
                    else scroll.smoothScrollTo(0, 0)
                }
            }
            false
        }

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        wrapper.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        wrapper.tag = GroupHolder(groupId, progress, status)
        return wrapper
    }

    /** 重命名合集标题（作用于整个合集，不改各分集标题）。 */
    private fun showRenameGroupDialog(groupId: String, currentTitle: String) {
        val input = EditText(context).apply {
            setText(currentTitle)
            setSingleLine(true)
            setSelectAllOnFocus(true)
            hint = "输入新的合集标题"
            textSize = 15f
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            background = rounded(COLOR_SOFT_FILL, dp(10))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(buildDialogBody(
            icon = GlyphView(context, GLYPH_PENCIL, COLOR_ROSE),
            title = "重命名合集",
            body = input,
            confirmText = "确定",
            confirmBg = COLOR_ROSE,
            onCancel = { dialog.dismiss() },
            onConfirm = {
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    showToast("标题不能为空")
                } else {
                    DownloadManager.renameGroup(groupId, name)
                    showToast("已重命名合集")
                    dialog.dismiss()
                }
            }
        ))
        val w = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        dialog.window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    /** 删除整个合集（含所有分集任务与已下载文件）。 */
    private fun confirmDeleteGroup(groupTasks: List<DownloadManager.Task>) {
        val first = groupTasks.first()
        val groupName = first.groupTitle.ifBlank { first.title }
        val msg = TextView(context).apply {
            text = "将删除合集「$groupName」的全部 ${groupTasks.size} 个分集及已下载的文件，确定删除？" + storagePermissionSuffix()
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(buildDialogBody(
            icon = GlyphView(context, GLYPH_TRASH, 0xFFD94A5C.toInt()),
            title = "删除合集",
            body = msg,
            confirmText = "删除",
            confirmBg = 0xFFD94A5C.toInt(),
            onCancel = { dialog.dismiss() },
            onConfirm = {
                DownloadManager.deleteGroup(first.groupId)
                refreshStorage(force = true)
                showToast("已删除合集")
                dialog.dismiss()
            }
        ))
        val w = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        dialog.window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun openLocalPlayer(taskId: Long) {
        VideoDetailActivity.openLocal(context, taskId)
    }

    private fun loadCoverUrl(iv: ImageView, url: String) {
        if (url.isBlank()) return
        try {
            CoverLoader.load(iv, url + "@480w_480h.webp")
        } catch (_: Throwable) {
        }
    }

    private fun buildRow(task: DownloadManager.Task): View {
        val sw = resources.displayMetrics.widthPixels - dp(28)
        val actionsW = dp(136)
        val rowHeight = dp(66)

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            clipToOutline = true
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val selectedNow = selected.contains(task.id)
        // 内容区
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = rowHeight
            background = if (selectionMode && selectedNow)
                rounded(COLOR_ROSE_SOFT, dp(14), COLOR_ROSE, 1)
            else
                rounded(COLOR_CARD, dp(14), COLOR_CARD_BORDER, 1)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                if (scroll.scrollX > 0) {
                    scroll.smoothScrollTo(0, 0)
                    return@setOnClickListener
                }
                if (selectionMode) {
                    if (!selected.add(task.id)) selected.remove(task.id)
                    val box = getChildAt(0) as? CheckBox
                    box?.isChecked = selected.contains(task.id)
                    background = if (selected.contains(task.id))
                        rounded(COLOR_ROSE_SOFT, dp(14), COLOR_ROSE, 1)
                    else
                        rounded(COLOR_CARD, dp(14), COLOR_CARD_BORDER, 1)
                    refreshSelectionUi()
                } else {
                    onRowTap(task, scroll)
                }
            }
        }

        // 多选勾选框（仅多选模式可见；点击由整行处理，避免事件冲突）
        content.addView(CheckBox(context).apply {
            buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_checked),
                    intArrayOf(android.R.attr.state_checked)
                ),
                intArrayOf(COLOR_MUTED, COLOR_ROSE)
            )
            isChecked = selectedNow
            isClickable = false
            isFocusable = false
            visibility = if (selectionMode) View.VISIBLE else View.GONE
        }, LinearLayout.LayoutParams(dp(36), dp(36)))

        // 封面
        val cover = FrameLayout(context).apply {
            background = rounded(COLOR_COVER, dp(10))
            clipToOutline = true
        }
        val coverImg = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        cover.addView(coverImg, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        loadCover(coverImg, task)

        // 中间列
        val mid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = task.title.ifBlank {
                when (task.type) {
                    DownloadManager.TYPE_COVER -> "封面"
                    DownloadManager.TYPE_COMMENT -> "评论图片"
                    else -> "未命名视频"
                }
            }
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mid.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val progress = ProgressBarView(context)
        progress.visibility = if (task.state == DownloadManager.STATE_COMPLETED) View.GONE else View.VISIBLE
        progress.setProgress(progressOf(task))
        mid.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply {
            topMargin = dp(6)
        })

        val (statusText, statusColor) = statusOf(task)
        val status = TextView(context).apply {
            text = statusText
            textSize = 11f
            setTextColor(statusColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mid.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })

        // 图标列：只放一个可点击的操作图标（暂停→点击继续 / 下载中→点击暂停 / 失败→点击重试）
        val iconCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, 0, 0)
        }
        if (task.state != DownloadManager.STATE_COMPLETED) {
            val actionIcon = buildActionIcon(task)
            iconCol.addView(actionIcon, LinearLayout.LayoutParams(dp(26), dp(26)))
        }

        content.addView(cover, LinearLayout.LayoutParams(dp(64), dp(40)))
        content.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })
        content.addView(iconCol)

        // ── 左滑操作区：整体右圆角，重命名（灰）+ 删除（红），图标+文字竖排，与合集详情页一致 ──
        val renameFg = if (Theme.isDark) 0xFFE98BAA.toInt() else COLOR_ROSE
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(COLOR_SURFACE, dp(14))
            clipToOutline = true
        }
        fun actionUnit(label: String, glyph: Int, bg: Int, fg: Int, onClick: () -> Unit): View {
            val unit = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(bg, 0)
                isClickable = true
                setOnClickListener { onClick() }
            }
            unit.addView(GlyphView(context, glyph, fg), LinearLayout.LayoutParams(dp(19), dp(19)))
            unit.addView(TextView(context).apply {
                text = label
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(fg)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            })
            return unit
        }
        actions.addView(
            actionUnit("重命名", GLYPH_PENCIL, COLOR_SURFACE, renameFg) { showRenameDialog(task) },
            LinearLayout.LayoutParams(dp(68), rowHeight))
        actions.addView(
            actionUnit("删除", GLYPH_TRASH, 0xFFE0556B.toInt(), Color.WHITE) { confirmDelete(task) },
            LinearLayout.LayoutParams(dp(68), rowHeight))

        inner.addView(content, LinearLayout.LayoutParams(sw, rowHeight))
        inner.addView(actions, LinearLayout.LayoutParams(actionsW, rowHeight))
        scroll.addView(inner)

        // 初始收起操作区（左滑才露出「重命名/删除」）。
        scroll.scrollTo(0, 0)

        // 松手吸附：滑出超过 1/3 就完全展开露出全部操作，否则完全收起。
        scroll.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val x = scroll.scrollX
                    if (x >= actionsW / 3) scroll.smoothScrollTo(actionsW, 0)
                    else scroll.smoothScrollTo(0, 0)
                }
            }
            false
        }

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        wrapper.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, rowHeight))
        wrapper.tag = RowHolder(task.id, progress, status)
        return wrapper
    }

    private fun buildStateIcon(task: DownloadManager.Task): View {
        return when (task.state) {
            DownloadManager.STATE_RUNNING -> SpinIcon(context)
            DownloadManager.STATE_PAUSED -> GlyphView(context, GLYPH_PAUSE, COLOR_MUTED)
            DownloadManager.STATE_FAILED -> GlyphView(context, GLYPH_EXCLAIM, 0xFFE0556B.toInt())
            DownloadManager.STATE_COMPLETED -> GlyphView(context, GLYPH_CHECK, COLOR_ROSE)
            else -> GlyphView(context, GLYPH_CLOCK, COLOR_MUTED)
        }
    }

    private fun buildActionIcon(task: DownloadManager.Task): View {
        val color = COLOR_ROSE
        // 现代化简约图标：线条 glyph 直接展示，不套圆形背景
        return when (task.state) {
            DownloadManager.STATE_RUNNING, DownloadManager.STATE_QUEUED ->
                GlyphView(context, GLYPH_PAUSE, color).apply {
                    setOnClickListener { DownloadManager.pause(task.id) }
                }
            DownloadManager.STATE_PAUSED ->
                GlyphView(context, GLYPH_PLAY, color).apply {
                    setOnClickListener { DownloadManager.resume(task.id) }
                }
            DownloadManager.STATE_FAILED ->
                GlyphView(context, GLYPH_RETRY, color).apply {
                    setOnClickListener { DownloadManager.retry(task.id) }
                }
            else ->
                GlyphView(context, GLYPH_PLAY, color).apply {
                    setOnClickListener { onRowClick(task) }
                }
        }
    }

    private fun progressOf(t: DownloadManager.Task): Float =
        if (t.bytesTotal > 0L) (t.bytesDone.toFloat() / t.bytesTotal).coerceIn(0f, 1f) else 0f

    private fun statusOf(t: DownloadManager.Task): Pair<String, Int> = when (t.state) {
        DownloadManager.STATE_RUNNING -> {
            val pct = if (t.bytesTotal > 0L) "${(progressOf(t) * 100).toInt()}% · " else ""
            val size = if (t.bytesTotal > 0L)
                "${formatBytes(t.bytesDone)}/${formatBytes(t.bytesTotal)} · " else ""
            "下载中 $pct$size${formatBytes(t.speedBytes)}/s" to COLOR_ROSE
        }
        DownloadManager.STATE_QUEUED -> "等待中" to COLOR_MUTED
        DownloadManager.STATE_PAUSED -> {
            val size = if (t.bytesTotal > 0L)
                " · ${formatBytes(t.bytesDone)}/${formatBytes(t.bytesTotal)}" else ""
            "已暂停$size" to COLOR_MUTED
        }
        DownloadManager.STATE_FAILED -> {
            val why = t.error.takeIf { it.isNotBlank() }?.let { " · ${it.take(16)}" } ?: ""
            "下载失败$why" to 0xFFE0556B.toInt()
        }
        else -> "已完成 · ${formatBytes(t.bytesDone)}" to COLOR_MUTED
    }

    /**
     * 整行点击：完成 → 播放/查看；未完成 → 暂停/继续/重试（优先级高于播放）。
     * 左滑露出操作区时点击内容区先收起，避免误触。
     */
    private fun onRowTap(task: DownloadManager.Task, scroll: HorizontalScrollView) {
        if (scroll.scrollX > 0) {
            scroll.smoothScrollTo(0, 0)
            return
        }
        when (task.state) {
            DownloadManager.STATE_COMPLETED -> onRowClick(task)
            DownloadManager.STATE_RUNNING, DownloadManager.STATE_QUEUED ->
                DownloadManager.pause(task.id)
            DownloadManager.STATE_PAUSED -> DownloadManager.resume(task.id)
            DownloadManager.STATE_FAILED -> DownloadManager.retry(task.id)
        }
    }

    private fun onRowClick(task: DownloadManager.Task) {
        if (task.state != DownloadManager.STATE_COMPLETED) return
        if (task.type == DownloadManager.TYPE_COVER || task.type == DownloadManager.TYPE_COMMENT) {
            showImageDialog(task)
            return
        }
        if (task.playable()) {
            VideoDetailActivity.openLocal(context, task.id)
        } else {
            showToast("文件已缺失，请删除该任务")
        }
    }

    private fun showImageDialog(task: DownloadManager.Task) {
        val local = task.coverPath
        if (local.isBlank() || !File(local).exists()) { showToast("图片文件缺失"); return }
        val dialog = android.app.Dialog(context)
        val image = ImageView(context).apply {
            setBackgroundColor(0xFF000000.toInt())
        }
        Thread {
            val bmp = BitmapFactory.decodeFile(local)
            handler.post {
                if (bmp == null) { dialog.dismiss(); showToast("图片解析失败"); return@post }
                image.setImageBitmap(bmp)
            }
        }.start()
        dialog.setContentView(image)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF000000.toInt()))
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels,
            (resources.displayMetrics.heightPixels * 0.8).toInt())
        dialog.show()
    }

    // ── 右滑操作 ──────────────────────────────────────────────

    private fun showRenameDialog(task: DownloadManager.Task) {
        val input = EditText(context).apply {
            setText(task.title)
            setSingleLine(true)
            setSelectAllOnFocus(true)
            hint = "输入新标题"
            textSize = 15f
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            background = rounded(COLOR_SOFT_FILL, dp(10))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(buildDialogBody(
            icon = GlyphView(context, GLYPH_PENCIL, COLOR_ROSE),
            title = "重命名",
            body = input,
            confirmText = "确定",
            confirmBg = COLOR_ROSE,
            onCancel = { dialog.dismiss() },
            onConfirm = {
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    showToast("标题不能为空")
                } else {
                    DownloadManager.rename(task.id, name)
                    showToast("已重命名")
                    dialog.dismiss()
                }
            }
        ))
        val w = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        dialog.window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun confirmDelete(task: DownloadManager.Task) {
        val msg = TextView(context).apply {
            text = "将删除「${task.title}」及已下载的文件，确定删除？" + storagePermissionSuffix()
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(buildDialogBody(
            icon = GlyphView(context, GLYPH_TRASH, 0xFFD94A5C.toInt()),
            title = "删除任务",
            body = msg,
            confirmText = "删除",
            confirmBg = 0xFFD94A5C.toInt(),
            onCancel = { dialog.dismiss() },
            onConfirm = {
                DownloadManager.delete(task.id)
                refreshStorage(force = true)
                showToast("已删除")
                dialog.dismiss()
            }
        ))
        val w = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        dialog.window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    /** 主题化弹窗主体：圆角卡片 + 图标标题 + 内容 + 底部按钮行（贴合 YuiBili 视觉）。 */
    private fun buildDialogBody(
        icon: View,
        title: String,
        body: View,
        confirmText: String,
        confirmBg: Int,
        onCancel: () -> Unit,
        onConfirm: () -> Unit
    ): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_CARD, dp(18), COLOR_CARD_BORDER, 1)
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)))
        val tv = TextView(context).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
        }
        titleRow.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        root.addView(titleRow)

        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancel = TextView(context).apply {
            text = "取消"
            textSize = 14f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isClickable = true
            setOnClickListener { onCancel() }
        }
        val confirm = TextView(context).apply {
            text = confirmText
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(confirmBg, dp(18))
            setPadding(dp(24), dp(10), dp(24), dp(10))
            isClickable = true
            setOnClickListener { onConfirm() }
        }
        btnRow.addView(cancel)
        btnRow.addView(confirm, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(6)
        })
        root.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        return root
    }

    // ── 封面加载 ──────────────────────────────────────────────

    private fun loadCover(iv: ImageView, task: DownloadManager.Task) {
        val local = task.coverPath
        if (local.isNotBlank() && File(local).exists()) {
            iv.setImageBitmap(null)
            Thread {
                val bmp = BitmapFactory.decodeFile(local)
                handler.post { if (bmp != null) iv.setImageBitmap(bmp) }
            }.start()
        } else if (task.coverUrl.isNotBlank()) {
            iv.setImageBitmap(null)
            CoverLoader.load(iv, task.coverUrl + "@480w_480h.webp")
        }
    }

    // ── 工具 ──────────────────────────────────────────────────

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024f
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024f
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        return String.format(Locale.US, "%.2fGB", mb / 1024f)
    }

    // ── 自绘小部件 ────────────────────────────────────────────

    companion object {
        private const val GLYPH_PAUSE = 0
        private const val GLYPH_PLAY = 1
        private const val GLYPH_RETRY = 2
        private const val GLYPH_CHECK = 3
        private const val GLYPH_EXCLAIM = 4
        private const val GLYPH_CLOCK = 5
        private const val GLYPH_PENCIL = 6
        private const val GLYPH_TRASH = 7
    }

    /** 下载中旋转圆环。 */
    private class SpinIcon(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ROSE
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
        }
        private var angle = 0f
        private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 850
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animator.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = min(width, height) / 2f - 1.5f * density
            val c = width / 2f
            val cy = height / 2f
            canvas.drawArc(RectF(c - r, cy - r, c + r, cy + r), angle, 280f, false, paint)
        }
    }

    /** 通用图标：暂停 / 播放 / 重试 / 勾 / 感叹 / 时钟。 */
    private class GlyphView(
        context: Context,
        private val mode: Int,
        private val color: Int
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            when (mode) {
                GLYPH_PAUSE -> {
                    val bw = w * 0.26f
                    val gap = w * 0.13f
                    val top = h * 0.18f
                    val bot = h * 0.82f
                    canvas.drawRoundRect(RectF(w * 0.22f, top, w * 0.22f + bw, bot), bw / 2, bw / 2, fill)
                    canvas.drawRoundRect(RectF(w * 0.22f + bw + gap, top, w * 0.22f + bw + gap + bw, bot), bw / 2, bw / 2, fill)
                }
                GLYPH_PLAY -> {
                    val path = Path().apply {
                        moveTo(w * 0.32f, h * 0.2f)
                        lineTo(w * 0.8f, h * 0.5f)
                        lineTo(w * 0.32f, h * 0.8f)
                        close()
                    }
                    canvas.drawPath(path, fill)
                }
                GLYPH_RETRY -> {
                    val r = min(w, h) * 0.3f
                    val cx = w / 2f
                    val cy = h / 2f
                    canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 30f, 280f, false, stroke)
                    val rad = Math.toRadians(30.0)
                    val cosA = cos(rad).toFloat()
                    val sinA = sin(rad).toFloat()
                    val p1x = cx + (r - 4f * density) * cosA - (r - 4f * density) * sinA * 0.35f
                    val p1y = cy + (r - 4f * density) * sinA + (r - 4f * density) * cosA * 0.35f
                    val p2x = cx + (r + 3f * density) * cosA
                    val p2y = cy + (r + 3f * density) * sinA
                    val p3x = cx + (r - 4f * density) * cosA + (r - 4f * density) * sinA * 0.35f
                    val p3y = cy + (r - 4f * density) * sinA - (r - 4f * density) * cosA * 0.35f
                    val path = Path().apply {
                        moveTo(p1x, p1y); lineTo(p2x, p2y); lineTo(p3x, p3y); close()
                    }
                    canvas.drawPath(path, fill)
                }
                GLYPH_CHECK -> {
                    stroke.strokeWidth = 2.4f * density
                    val path = Path().apply {
                        moveTo(w * 0.22f, h * 0.52f)
                        lineTo(w * 0.44f, h * 0.72f)
                        lineTo(w * 0.8f, h * 0.3f)
                    }
                    canvas.drawPath(path, stroke)
                }
                GLYPH_EXCLAIM -> {
                    canvas.drawRoundRect(
                        RectF(w * 0.43f, h * 0.14f, w * 0.57f, h * 0.56f), 1.5f * density, 1.5f * density, fill)
                    canvas.drawCircle(w * 0.5f, h * 0.74f, w * 0.07f, fill)
                }
                GLYPH_CLOCK -> {
                    val r = min(w, h) * 0.4f
                    val cx = w / 2f
                    val cy = h / 2f
                    canvas.drawCircle(cx, cy, r, stroke)
                    canvas.drawLine(cx, cy, cx, cy - r * 0.55f, stroke)
                    canvas.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.2f, stroke)
                }
                GLYPH_PENCIL -> {
                    // 简洁铅笔：斜笔身 + 笔尖 + 笔尾
                    stroke.strokeWidth = 2.2f * density
                    val path = Path().apply {
                        moveTo(w * 0.30f, h * 0.64f)
                        lineTo(w * 0.58f, h * 0.36f)
                        moveTo(w * 0.58f, h * 0.36f)
                        lineTo(w * 0.74f, h * 0.20f)
                        moveTo(w * 0.74f, h * 0.20f)
                        lineTo(w * 0.62f, h * 0.48f)
                        moveTo(w * 0.28f, h * 0.66f)
                        lineTo(w * 0.38f, h * 0.56f)
                    }
                    canvas.drawPath(path, stroke)
                }
                GLYPH_TRASH -> {
                    // 垃圾桶：盖 + 桶身 + 竖纹
                    stroke.strokeWidth = 2f * density
                    val path = Path().apply {
                        moveTo(w * 0.24f, h * 0.32f); lineTo(w * 0.76f, h * 0.32f)
                        moveTo(w * 0.36f, h * 0.24f); lineTo(w * 0.64f, h * 0.24f)
                        moveTo(w * 0.30f, h * 0.38f); lineTo(w * 0.34f, h * 0.78f)
                        moveTo(w * 0.70f, h * 0.38f); lineTo(w * 0.66f, h * 0.78f)
                        moveTo(w * 0.34f, h * 0.78f); lineTo(w * 0.66f, h * 0.78f)
                        moveTo(w * 0.44f, h * 0.42f); lineTo(w * 0.45f, h * 0.70f)
                        moveTo(w * 0.56f, h * 0.42f); lineTo(w * 0.55f, h * 0.70f)
                    }
                    canvas.drawPath(path, stroke)
                }
            }
        }
    }

    /** 细进度条：圆角轨道 + 粉色填充。 */
    private class ProgressBarView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BORDER }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ROSE }
        private var progress = 0f

        fun setProgress(p: Float) {
            progress = p.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            val r = h / 2f
            val w = width.toFloat()
            canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, track)
            if (progress > 0.005f) {
                canvas.drawRoundRect(RectF(0f, 0f, w * progress, h), r, r, fill)
            }
        }
    }

    /** 双色存储进度条：其它占用(淡蓝) + YuiBili(粉) + 可用空白。 */
    private class StorageUsageBar(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BORDER }
        private val otherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (Theme.isDark) 0xFF6B9BC3.toInt() else 0xFFA8D4F0.toInt()
        }
        private val appPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ROSE }
        private var otherRatio = 0f
        private var appRatio = 0f

        fun setValues(total: Long, other: Long, app: Long) {
            otherRatio = if (total > 0) (other.toFloat() / total).coerceIn(0f, 1f) else 0f
            appRatio = if (total > 0) (app.toFloat() / total).coerceIn(0f, 1f) else 0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            val r = h / 2f
            val w = width.toFloat()
            val trackPaint = Paint(track)
            if (otherRatio + appRatio < 0.01f) {
                canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, trackPaint)
                return
            }
            val otherW = w * otherRatio
            val appW = w * appRatio
            val path = Path().apply {
                addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawColor(COLOR_BORDER)
            canvas.drawRect(0f, 0f, otherW, h, otherPaint)
            canvas.drawRect(otherW, 0f, (otherW + appW).coerceAtMost(w), h, appPaint)
            canvas.restore()
        }
    }

    /**
     * 空态插图：与全 App 统一的 1.7dp 圆角线条风格。
     * 下载语义 = 下行箭头 + 托盘（同详情页动作栏 KIND_DOWNLOAD）。
     */
    private class DownloadGlyph(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // 空态用柔和灰，避免与可点击图标的 COLOR_ROSE 抢视觉
            color = if (Theme.isDark) 0xFF7A717A.toInt() else 0xFFC9BFC4.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            // 空态更大，线宽稍粗，但图形语义与动作栏下载图标一致
            val unit = min(w, h) * 0.5f
            // 下行箭头
            canvas.drawLine(cx, cy - unit * 0.55f, cx, cy + unit * 0.18f, paint)
            canvas.drawLine(cx - unit * 0.32f, cy - unit * 0.05f, cx, cy + unit * 0.22f, paint)
            canvas.drawLine(cx + unit * 0.32f, cy - unit * 0.05f, cx, cy + unit * 0.22f, paint)
            // 托盘
            val tray = Path().apply {
                moveTo(cx - unit * 0.48f, cy + unit * 0.36f)
                lineTo(cx - unit * 0.48f, cy + unit * 0.55f)
                lineTo(cx + unit * 0.48f, cy + unit * 0.55f)
                lineTo(cx + unit * 0.48f, cy + unit * 0.36f)
            }
            canvas.drawPath(tray, paint)
        }
    }
}
