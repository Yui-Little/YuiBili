package com.yuilittle.bili

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 合集下载详情页：按任务类型（视频/音频/封面）过滤展示该合集的任务列表。
 * 顶栏右侧可进入多选模式，批量删除需二次确认；每行左滑露出「重命名/删除」。
 */
class DownloadSeasonActivity : Activity() {

    private var groupId: String = ""
    private var requestedType: Int = DownloadManager.TYPE_VIDEO

    private var listContainer: LinearLayout? = null
    private var headerCard: LinearLayout? = null
    private var emptyView: LinearLayout? = null
    private var multiBtn: TextView? = null
    private var bottomBar: LinearLayout? = null
    private var selectAllBtn: TextView? = null
    private var deleteBtn: TextView? = null

    /** 多选模式。 */
    private var selectionMode = false
    private val selected = HashSet<Long>()

    private val listener: (List<DownloadManager.Task>) -> Unit = { tasks ->
        val groupTasks = groupTasksOf(tasks)
        if (groupTasks.isEmpty()) {
            rebuild()
        } else {
            updateRows(groupTasks)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Theme.applySystemBars(this)
        groupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        requestedType = intent.getIntExtra(EXTRA_TYPE, DownloadManager.TYPE_VIDEO)
        if (groupId.isBlank()) {
            Toast.makeText(this, "合集信息缺失", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        DownloadManager.init(this)
        DownloadManager.addListener(listener)
        setContentView(buildUi())
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadManager.removeListener(listener)
    }

    private fun groupTasksOf(tasks: List<DownloadManager.Task>): List<DownloadManager.Task> =
        tasks.filter { it.groupId == groupId && it.type == requestedType }
            .sortedBy { it.episodeNo }

    private fun typeTitle(): String = when (requestedType) {
        DownloadManager.TYPE_AUDIO -> "音频列表"
        DownloadManager.TYPE_COVER, DownloadManager.TYPE_COMMENT -> "封面列表"
        else -> "视频列表"
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }

        // ── 顶栏：返回 + 类型标题 + 多选按钮 ──
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(12), dp(6))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(COLOR_INK)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        bar.addView(TextView(this).apply {
            text = typeTitle()
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        multiBtn = TextView(this).apply {
            text = "多选"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ROSE)
            gravity = Gravity.CENTER
            background = rounded(COLOR_ROSE_SOFT, dp(12))
            setPadding(dp(16), dp(7), dp(16), dp(7))
            setOnClickListener { setSelectionMode(!selectionMode) }
        }
        bar.addView(multiBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)))
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 可滚动内容 ──
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }

        // 头部卡片：封面 + 标题 + 统计
        val first = groupTasksOf(DownloadManager.snapshot()).firstOrNull()
        headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(COLOR_CARD, dp(16), COLOR_CARD_BORDER, 1)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val cover = FrameLayout(this).apply {
            background = rounded(COLOR_COVER, dp(12))
            clipToOutline = true
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        cover.addView(img, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        runCatching {
            val url = first?.groupCoverUrl?.ifBlank { first.coverUrl }.orEmpty()
            if (url.isNotBlank()) CoverLoader.load(img, url + "@480w_480h.webp")
        }
        headerCard!!.addView(cover, LinearLayout.LayoutParams(dp(112), dp(70)))
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mid.addView(TextView(this).apply {
            text = first?.groupTitle?.ifBlank { first?.title } ?: "合集"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val stat = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_MUTED)
            maxLines = 1
        }
        mid.addView(stat, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        headerCard!!.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12)
        })
        content.addView(headerCard!!)

        // 任务列表容器
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(listContainer!!, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        // 空状态
        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, dp(48))
        }
        emptyView!!.addView(TextView(this).apply {
            text = "暂无任务"
            textSize = 14f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
        })
        content.addView(emptyView!!)

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 底部多选操作栏（默认隐藏）──
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(COLOR_CARD, dp(18), COLOR_CARD_BORDER, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        selectAllBtn = TextView(this).apply {
            text = "全选"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ROSE)
            gravity = Gravity.CENTER
            background = rounded(COLOR_ROSE_SOFT, dp(12))
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener {
                val tasks = groupTasksOf(DownloadManager.snapshot())
                val allSelected = tasks.isNotEmpty() && tasks.all { selected.contains(it.id) }
                if (allSelected) selected.clear() else tasks.forEach { selected.add(it.id) }
                rebuild()
                refreshSelectionUi()
            }
        }
        bottomBar!!.addView(selectAllBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        bottomBar!!.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        deleteBtn = TextView(this).apply {
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

        rebuild()
        return root
    }

    /** 进入/退出多选模式。 */
    private fun setSelectionMode(on: Boolean) {
        selectionMode = on
        selected.clear()
        multiBtn?.text = if (on) "取消" else "多选"
        multiBtn?.setTextColor(if (on) COLOR_MUTED else COLOR_ROSE)
        multiBtn?.background = rounded(
            if (on) COLOR_SOFT_FILL else COLOR_ROSE_SOFT, dp(12))
        bottomBar?.visibility = if (on) View.VISIBLE else View.GONE
        refreshSelectionUi()
        rebuild()
    }

    private fun refreshSelectionUi() {
        deleteBtn?.text = if (selected.isEmpty()) "删除" else "删除 (${selected.size})"
        deleteBtn?.alpha = if (selected.isEmpty()) 0.5f else 1f
        val tasks = groupTasksOf(DownloadManager.snapshot())
        val allSelected = tasks.isNotEmpty() && tasks.all { selected.contains(it.id) }
        selectAllBtn?.text = if (allSelected) "取消全选" else "全选"
    }

    /** 未授予「所有文件访问」权限时，删除后相册副本可能残留，弹窗里给出提示。 */
    private fun storagePermissionSuffix(): String =
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
            "\n\n未授予「所有文件访问」权限，相册中的文件可能无法彻底删除"
        else ""

    /** 批量删除二次确认。 */
    private fun confirmDeleteMany(ids: List<Long>) {
        if (ids.isEmpty()) return
        val msg = TextView(this).apply {
            text = "确定删除选中的 ${ids.size} 个任务？\n已下载的文件也会一并删除。" + storagePermissionSuffix()
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(buildDialogBody(
            title = "删除 ${ids.size} 个任务",
            body = msg,
            confirmText = "删除",
            confirmColor = 0xFFE0556B.toInt(),
            onCancel = { dialog.dismiss() },
            onConfirm = {
                ids.forEach { DownloadManager.delete(it) }
                setSelectionMode(false)
                Toast.makeText(this, "已删除 ${ids.size} 个任务", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        ))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.82f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun rebuild() {
        val tasks = groupTasksOf(DownloadManager.snapshot())
        val list = listContainer ?: return
        list.removeAllViews()

        // 头部统计
        headerCard?.let { card ->
            val stat = (card.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView
            if (stat != null) {
                if (tasks.isEmpty()) {
                    stat.text = "0 个任务"
                } else {
                    val done = tasks.count { it.state == DownloadManager.STATE_COMPLETED }
                    val totalBytes = tasks.sumOf { it.bytesTotal.coerceAtLeast(0L) }
                    val doneBytes = tasks.sumOf { it.bytesDone.coerceAtLeast(0L) }
                    stat.text = if (totalBytes > 0L)
                        "${tasks.size} 个任务 · 已完成 $done 个 · ${formatBytes(doneBytes)}/${formatBytes(totalBytes)}"
                    else "${tasks.size} 个任务 · 已完成 $done 个"
                }
            }
        }

        if (tasks.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            return
        }
        emptyView?.visibility = View.GONE

        tasks.forEach { t ->
            val row = buildRow(t)
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
    }

    /** 纯进度/状态刷新：只更新已有行，不重建视图。 */
    private fun updateRows(tasks: List<DownloadManager.Task>) {
        val list = listContainer ?: return
        for (i in 0 until list.childCount) {
            val wrapper = list.getChildAt(i)
            val holder = wrapper.tag as? RowHolder ?: continue
            val task = tasks.firstOrNull { it.id == holder.taskId } ?: continue
            holder.status.text = statusOf(task).first
            holder.status.setTextColor(statusOf(task).second)
            if (task.state == DownloadManager.STATE_COMPLETED) {
                holder.progress.visibility = View.GONE
            } else {
                holder.progress.visibility = View.VISIBLE
                holder.progress.setProgress(progressOf(task))
            }
        }
    }

    /**
     * 任务行：多选模式下行首出现勾选框、点击切换选中；
     * 左滑露出操作区（整体右圆角：重命名 + 删除），操作单元与内容卡等高对齐。
     */
    private fun buildRow(task: DownloadManager.Task): View {
        val sw = resources.displayMetrics.widthPixels - dp(32)
        val actionsW = dp(136)
        val rowHeight = dp(66)

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            clipToOutline = true
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // ── 内容区 ──
        val selectedNow = selected.contains(task.id)
        val content = LinearLayout(this).apply {
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
        content.addView(CheckBox(this).apply {
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

        // 类型/集数徽章（视频=集数，音频/封面=类型）
        val badgeText = when (task.type) {
            DownloadManager.TYPE_AUDIO -> "音频"
            DownloadManager.TYPE_COVER, DownloadManager.TYPE_COMMENT -> "封面"
            else -> if (task.episodeNo > 0) "第${task.episodeNo}集" else "EP"
        }
        content.addView(TextView(this).apply {
            text = badgeText
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (task.state == DownloadManager.STATE_COMPLETED) COLOR_ROSE else COLOR_MUTED)
            gravity = Gravity.CENTER
            background = rounded(COLOR_ROSE_SOFT, dp(9))
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 中间列：标题 + 进度 + 状态
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mid.addView(TextView(this).apply {
            text = task.title.ifBlank { "未命名任务" }
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val progress = ProgressBarView(this)
        progress.visibility = if (task.state == DownloadManager.STATE_COMPLETED) View.GONE else View.VISIBLE
        progress.setProgress(progressOf(task))
        mid.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(6) })
        val (stText, stColor) = statusOf(task)
        val status = TextView(this).apply {
            text = stText
            textSize = 11f
            setTextColor(stColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mid.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })
        content.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })

        // 右侧操作图标（未完成时）
        if (task.state != DownloadManager.STATE_COMPLETED) {
            val icon = when (task.state) {
                DownloadManager.STATE_RUNNING, DownloadManager.STATE_QUEUED ->
                    GlyphView(this, GLYPH_PAUSE, COLOR_ROSE).apply {
                        setOnClickListener { DownloadManager.pause(task.id) }
                    }
                DownloadManager.STATE_PAUSED ->
                    GlyphView(this, GLYPH_PLAY, COLOR_ROSE).apply {
                        setOnClickListener { DownloadManager.resume(task.id) }
                    }
                else ->
                    GlyphView(this, GLYPH_RETRY, COLOR_ROSE).apply {
                        setOnClickListener { DownloadManager.retry(task.id) }
                    }
            }
            content.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                leftMargin = dp(6)
            })
        }

        // ── 左滑操作区：整体右圆角，重命名（灰）+ 删除（红），图标+文字竖排，与内容卡等高 ──
        val renameFg = if (Theme.isDark) 0xFFE98BAA.toInt() else COLOR_ROSE
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(COLOR_SURFACE, dp(14))
            clipToOutline = true
        }
        fun actionUnit(label: String, glyph: Int, bg: Int, fg: Int, onClick: () -> Unit): View {
            val unit = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(bg, 0)
                isClickable = true
                setOnClickListener { onClick() }
            }
            unit.addView(GlyphView(this, glyph, fg), LinearLayout.LayoutParams(dp(19), dp(19)))
            unit.addView(TextView(this).apply {
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
        scroll.scrollTo(0, 0)
        scroll.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val x = scroll.scrollX
                    if (x >= actionsW / 3) scroll.smoothScrollTo(actionsW, 0)
                    else scroll.smoothScrollTo(0, 0)
                }
            }
            false
        }

        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, rowHeight))
        wrapper.tag = RowHolder(task.id, task.state, progress, status)
        return wrapper
    }

    private fun onRowTap(task: DownloadManager.Task, scroll: HorizontalScrollView) {
        if (scroll.scrollX > 0) {
            scroll.smoothScrollTo(0, 0)
            return
        }
        when (task.state) {
            DownloadManager.STATE_COMPLETED -> {
                if (task.type == DownloadManager.TYPE_COVER || task.type == DownloadManager.TYPE_COMMENT) {
                    Toast.makeText(this, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                } else if (task.playable()) {
                    VideoDetailActivity.openLocal(this, task.id)
                } else {
                    Toast.makeText(this, "文件已缺失，请删除该任务", Toast.LENGTH_SHORT).show()
                }
            }
            DownloadManager.STATE_RUNNING, DownloadManager.STATE_QUEUED ->
                DownloadManager.pause(task.id)
            DownloadManager.STATE_PAUSED -> DownloadManager.resume(task.id)
            DownloadManager.STATE_FAILED -> DownloadManager.retry(task.id)
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

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024f
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024f
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        return String.format(Locale.US, "%.2fGB", mb / 1024f)
    }

    // ── 对话框 ──────────────────────────────────────────────

    private fun showRenameDialog(task: DownloadManager.Task) {
        val input = EditText(this).apply {
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
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(buildDialogBody(
            title = "重命名",
            body = input,
            confirmText = "确定",
            confirmColor = COLOR_ROSE,
            onCancel = { dialog.dismiss() },
            onConfirm = {
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "标题不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    DownloadManager.rename(task.id, name)
                    Toast.makeText(this, "已重命名", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        ))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.82f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun confirmDelete(task: DownloadManager.Task) {
        val msg = TextView(this).apply {
            text = "将删除「${task.title}」及已下载的文件，确定删除？" + storagePermissionSuffix()
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(buildDialogBody(
            title = "删除任务",
            body = msg,
            confirmText = "删除",
            confirmColor = 0xFFE0556B.toInt(),
            onCancel = { dialog.dismiss() },
            onConfirm = {
                DownloadManager.delete(task.id)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        ))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.82f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    /** 通用对话框主体：圆角卡片 + 标题 + 内容 + 底部按钮行。 */
    private fun buildDialogBody(
        title: String,
        body: View,
        confirmText: String,
        confirmColor: Int,
        onCancel: () -> Unit,
        onConfirm: () -> Unit
    ): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_CARD, dp(18), COLOR_CARD_BORDER, 1)
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
        })
        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(TextView(this).apply {
            text = "取消"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { onCancel() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        row.addView(TextView(this).apply {
            text = confirmText
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(confirmColor, dp(12))
            setPadding(dp(22), dp(10), dp(22), dp(10))
            setOnClickListener { onConfirm() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            leftMargin = dp(8)
        })
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        return root
    }

    // ── 小部件 ──────────────────────────────────────────────

    private class RowHolder(
        val taskId: Long,
        var state: Int,
        val progress: ProgressBarView,
        val status: TextView
    )

    /** 圆角进度条。 */
    private class ProgressBarView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_SOFT_FILL
            style = Paint.Style.FILL
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ROSE
            style = Paint.Style.FILL
        }
        private var pct = 0f

        fun setProgress(value: Float) {
            pct = value.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            val r = h / 2f
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), r, r, track)
            val w = width.toFloat() * pct
            if (w > 0.5f) canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, fill)
        }
    }

    /** 线条图标：暂停 / 播放 / 重试 / 铅笔 / 垃圾桶。 */
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
                GLYPH_PENCIL -> {
                    stroke.strokeWidth = 2.2f * density
                    val path = Path().apply {
                        moveTo(w * 0.30f, h * 0.66f)
                        lineTo(w * 0.62f, h * 0.30f)
                        lineTo(w * 0.72f, h * 0.38f)
                        lineTo(w * 0.40f, h * 0.74f)
                        lineTo(w * 0.28f, h * 0.72f)
                        close()
                    }
                    canvas.drawPath(path, fill)
                }
                GLYPH_TRASH -> {
                    stroke.strokeWidth = 2f * density
                    val path = Path().apply {
                        moveTo(w * 0.28f, h * 0.34f)
                        lineTo(w * 0.72f, h * 0.34f)
                        lineTo(w * 0.68f, h * 0.78f)
                        lineTo(w * 0.32f, h * 0.78f)
                        close()
                    }
                    canvas.drawPath(path, stroke)
                    canvas.drawLine(w * 0.44f, h * 0.44f, w * 0.44f, h * 0.68f, stroke)
                    canvas.drawLine(w * 0.56f, h * 0.44f, w * 0.56f, h * 0.68f, stroke)
                    canvas.drawLine(w * 0.30f, h * 0.28f, w * 0.70f, h * 0.28f, stroke)
                }
            }
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_TYPE = "type"

        private const val GLYPH_PAUSE = 0
        private const val GLYPH_PLAY = 1
        private const val GLYPH_RETRY = 2
        private const val GLYPH_PENCIL = 6
        private const val GLYPH_TRASH = 7

        /** 打开合集下载详情页（按类型展示对应任务列表）。 */
        fun open(context: Context, groupId: String, type: Int = DownloadManager.TYPE_VIDEO) {
            context.startActivity(
                Intent(context, DownloadSeasonActivity::class.java)
                    .putExtra(EXTRA_GROUP_ID, groupId)
                    .putExtra(EXTRA_TYPE, type)
            )
        }
    }
}
