package com.yuilittle.bili

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * 全屏下载选择页：现代设置卡片式 UI。
 * 单视频：画质下拉 + 仅音频/封面开关；合集：分集多选 + 画质下拉 + 开关，
 * 底部固定确认按钮，开关开启的附属项随主任务一起入队。
 */
class DownloadPickerActivity : Activity() {

    private lateinit var item: VideoItem
    private var acceptedQns: IntArray = IntArray(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Theme.applySystemBars(this)

        val raw = intent.getStringExtra(EXTRA_ITEM_JSON).orEmpty()
        item = parseVideoItem(raw)
        acceptedQns = intent.getIntArrayExtra(EXTRA_ACCEPTED_QNS) ?: IntArray(0)
        if (item.bvid.isBlank()) {
            Toast.makeText(this, "视频信息缺失", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(buildUi())
    }

    // ── UI 骨架 ─────────────────────────────────────────────

    private fun buildUi(): View {
        val season = item.season
        val isSeason = season != null && season.episodes.isNotEmpty()
        val qualities = availableDownloadQualities()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }

        // 顶栏：返回 + 大标题 + 类型徽标
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(16), dp(6))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(COLOR_INK)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        bar.addView(TextView(this).apply {
            text = "下载"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        bar.addView(TextView(this).apply {
            text = if (isSeason) "合集 · ${season!!.episodes.size} 集" else "单视频"
            textSize = 11f
            setTextColor(COLOR_MUTED)
            background = rounded(COLOR_SOFT_FILL, dp(10))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 可滚动内容（自定义容器：触摸落在内层分集列表时让内层优先滚动）
        val scroll = PickerScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }

        content.addView(buildInfoCard())

        // 状态（跨闭包共享）
        var selectedQuality: DownloadQuality? = qualities.firstOrNull()
        lateinit var audioSwitch: Switch
        lateinit var coverSwitch: Switch
        var confirmBtn: TextView? = null
        lateinit var qualityValue: TextView
        /** 合集分集勾选联动（底部按钮创建后统一触发一次）。 */
        var episodeUiRefresh: (() -> Unit)? = null
        /** 合集分集勾选状态（底部按钮入队时读取）。 */
        var seasonChecked: BooleanArray? = null

        // ── 下载设置卡片 ────────────────────────────────────
        content.addView(sectionTitle("下载设置", topMargin = dp(20)))
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_CARD, dp(16), COLOR_CARD_BORDER, 1)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        // 画质行：下拉选择
        qualityValue = TextView(this).apply {
            text = selectedQuality?.let { qLabel(it) } ?: "自动"
            textSize = 13f
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val qualityRow = settingsRow("画质", qualityValue, chevron = true).apply {
            setOnClickListener {
                val list = qualities
                if (list.isEmpty()) return@setOnClickListener
                showQualitySheet(list, selectedQuality) { q ->
                    selectedQuality = q
                    qualityValue.text = qLabel(q)
                    if (!isSeason) confirmBtn?.text = "下载 ${qLabel(q)}"
                }
            }
        }
        settings.addView(qualityRow, settingsRowLp(0))
        settings.addView(divider())
        // 仅音频开关
        audioSwitch = settingsSwitch(false)
        settings.addView(settingsRow("仅音频", audioSwitch), settingsRowLp(1))
        settings.addView(divider())
        // 封面开关（合集与单视频统一为「封面图片」，封面与视频封面一致）
        coverSwitch = settingsSwitch(false)
        settings.addView(
            settingsRow(if (isSeason) "下载封面" else "封面图片", coverSwitch),
            settingsRowLp(2))
        content.addView(settings)

        // ── 合集：分集选择 ──────────────────────────────────
        if (isSeason) {
            val s = season!!
            val episodes = s.episodes
            val checked = BooleanArray(episodes.size) { !episodes[it].charge }
            val downloadables = episodes.indices.filter { !episodes[it].charge }

            content.addView(sectionTitle("选择分集", topMargin = dp(24)))
            val epCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(COLOR_CARD, dp(16), COLOR_CARD_BORDER, 1)
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(12), dp(10))
            }
            val countLabel = TextView(this).apply {
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_INK)
            }
            val toggleBtn = TextView(this).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_ROSE)
                background = rounded(COLOR_ROSE_SOFT, dp(12))
                setPadding(dp(14), dp(7), dp(14), dp(7))
                gravity = Gravity.CENTER
            }
            header.addView(countLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(toggleBtn)
            epCard.addView(header)
            epCard.addView(divider(0, dp(12)))

            fun updateEpisodesUi() {
                val n = checked.count { it }
                countLabel.text = "已选 $n / ${episodes.size} 集"
                val allSelected = downloadables.isNotEmpty() && downloadables.all { checked[it] }
                toggleBtn.text = if (allSelected) "取消全选" else "全选"
                confirmBtn?.text = if (n == 0) "下载已选 0 集" else "下载已选 $n 集"
            }
            episodeUiRefresh = { updateEpisodesUi() }
            updateEpisodesUi()
            seasonChecked = checked

            val epList = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(4))
            }
            episodes.forEachIndexed { index, episode ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    setPadding(dp(16), dp(4), dp(12), dp(4))
                }
                val box = CheckBox(this).apply {
                    isChecked = checked[index]
                    isEnabled = !episode.charge
                    buttonTintList = ColorStateList(
                        arrayOf(
                            intArrayOf(-android.R.attr.state_checked),
                            intArrayOf(android.R.attr.state_checked)
                        ),
                        intArrayOf(if (episode.charge) COLOR_MUTED else COLOR_SWITCH_TRACK_OFF, COLOR_ROSE)
                    )
                    setOnCheckedChangeListener { _, value ->
                        checked[index] = value
                        updateEpisodesUi()
                    }
                }
                row.setTag(box)
                row.addView(box, LinearLayout.LayoutParams(dp(40), dp(40)))
                val title = TextView(this).apply {
                    text = episode.title
                    textSize = 13f
                    setTextColor(if (episode.charge) COLOR_MUTED else COLOR_INK)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (episode.charge) {
                    row.addView(TextView(this).apply {
                        text = "充电专属"
                        textSize = 10f
                        setTextColor(COLOR_MUTED)
                        background = rounded(COLOR_SOFT_FILL, dp(7))
                        setPadding(dp(7), dp(3), dp(7), dp(3))
                    })
                } else {
                    row.addView(TextView(this).apply {
                        text = formatDuration(episode.duration)
                        textSize = 11f
                        setTextColor(COLOR_MUTED)
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = dp(8)
                    })
                }
                epList.addView(row)
            }
            val epScroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            epScroll.addView(epList)
            epCard.addView(epScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                min(dp(48) * episodes.size + dp(8), (resources.displayMetrics.heightPixels * 0.34f).toInt())))
            content.addView(epCard)

            // 全选/取消全选：切换式单按钮，epList 就绪后再绑定
            toggleBtn.setOnClickListener {
                val allSelected = downloadables.isNotEmpty() && downloadables.all { checked[it] }
                for (i in downloadables) checked[i] = !allSelected
                for (i in 0 until epList.childCount) {
                    (epList.getChildAt(i).getTag() as? CheckBox)?.isChecked = checked[i]
                }
                updateEpisodesUi()
            }
            // 让内层分集列表优先消费滚动（解决 ScrollView 嵌套时外层抢滚动）
            scroll.innerScrollables = listOf(epScroll)
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 底部固定确认栏 ──────────────────────────────────
        val navH = runCatching {
            resources.getIdentifier("navigation_bar_height", "dimen", "android")
                .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: 0
        }.getOrDefault(0)
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_CARD)
            setPadding(dp(16), dp(12), dp(16), dp(12) + navH)
        }
        bottomBar.addView(View(this).apply {
            setBackgroundColor(COLOR_CARD_BORDER)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

        confirmBtn = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(COLOR_ROSE, dp(14))
            minimumHeight = dp(52)
            setOnClickListener {
                val q = selectedQuality ?: return@setOnClickListener
                if (q.needsVip && !BiliSessionStore.isLoggedIn()) {
                    Toast.makeText(this@DownloadPickerActivity, "${q.label} 需要登录并开通大会员", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (isSeason) {
                    val s = item.season!!
                    val sc = seasonChecked ?: return@setOnClickListener
                    val chosen = s.episodes.indices.filter { sc[it] && !s.episodes[it].charge }
                    if (chosen.isEmpty()) {
                        Toast.makeText(this@DownloadPickerActivity, "请先选择要下载的分集", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val groupId = "season-${s.id}"
                    val groupTitle = s.title.ifBlank { item.title }
                    val groupCover = item.cover.ifBlank { s.cover }
                    val withAudio = audioSwitch.isChecked
                    val withCover = coverSwitch.isChecked
                    // 合集内封面 URL 去重：UP 主若给多集用同一张图，只下一次封面，避免重复下载
                    val enqueuedCovers = HashSet<String>()
                    var coverCount = 0
                    for (index in chosen) {
                        val ep = s.episodes[index]
                        // 封面用分集自己的封面（arc.pic），没有时回退主视频封面；
                        // 这样「下载封面」不会再让每一集都只下到视频详情页的同一张封面。
                        val episodeItem = item.copy(
                            bvid = ep.bvid, aid = ep.aid, cid = ep.cid,
                            title = ep.title, duration = ep.duration, charge = ep.charge,
                            cover = ep.cover.ifBlank { item.cover }
                        )
                        DownloadManager.enqueueVideo(
                            this@DownloadPickerActivity, episodeItem, q.qn, q.label,
                            groupId, groupTitle, groupCover, index + 1
                        )
                        if (withAudio) {
                            DownloadManager.enqueueAudio(
                                this@DownloadPickerActivity, episodeItem,
                                groupId, groupTitle, groupCover, index + 1
                            )
                        }
                        if (withCover && enqueuedCovers.add(episodeItem.cover)) {
                            coverCount++
                            DownloadManager.enqueueCover(
                                this@DownloadPickerActivity, episodeItem,
                                groupId, groupTitle, groupCover, index + 1
                            )
                        }
                    }
                    val msg = StringBuilder("已加入 ${chosen.size} 集")
                    if (withAudio) msg.append(" + ${chosen.size} 个音频")
                    if (withCover) msg.append(" + $coverCount 张封面")
                    if (withCover && coverCount < chosen.size) msg.append("（重复封面已合并）")
                    Toast.makeText(this@DownloadPickerActivity, msg.toString(), Toast.LENGTH_SHORT).show()
                } else {
                    DownloadManager.enqueueVideo(this@DownloadPickerActivity, item, q.qn, q.label)
                    if (audioSwitch.isChecked) DownloadManager.enqueueAudio(this@DownloadPickerActivity, item)
                    if (coverSwitch.isChecked) DownloadManager.enqueueCover(this@DownloadPickerActivity, item)
                    Toast.makeText(this@DownloadPickerActivity, "已加入下载队列：${q.label}", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
        bottomBar.addView(confirmBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        if (!isSeason) {
            confirmBtn.text = "下载 ${selectedQuality?.let { qLabel(it) } ?: ""}"
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 底部按钮就绪后初始化合集按钮文案（分集勾选变化时 updateEpisodesUi 也会刷新）
        if (isSeason) {
            episodeUiRefresh?.invoke()
        }
        return root
    }

    // ── 组件构建 ────────────────────────────────────────────

    /** 视频信息卡：封面 + 标题/UP主/元信息。 */
    private fun buildInfoCard(): View {
        val season = item.season
        val isSeason = season != null && season.episodes.isNotEmpty()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(COLOR_CARD, dp(16), COLOR_CARD_BORDER, 1)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val cover = FrameLayout(this).apply {
            background = rounded(COLOR_COVER, dp(12))
            clipToOutline = true
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        cover.addView(img, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        runCatching {
            CoverLoader.load(img, item.cover + "@480w_480h.webp")
        }
        card.addView(cover, LinearLayout.LayoutParams(dp(112), dp(70)))

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mid.addView(TextView(this).apply {
            text = item.title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        mid.addView(TextView(this).apply {
            text = item.owner
            textSize = 12f
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        val metaParts = buildList {
            add(formatDuration(item.duration))
            add("${formatViews(item.views)} 播放")
            if (isSeason) add("${season!!.episodes.size} 集")
        }
        mid.addView(TextView(this).apply {
            text = metaParts.joinToString(" · ")
            textSize = 11f
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        card.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12)
        })
        return card
    }

    /** 设置卡片里的一行：左侧标签 + 右侧控件（value 或 Switch）。 */
    private fun settingsRow(label: String, right: View, chevron: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            isClickable = chevron
            isFocusable = chevron
            addView(TextView(this@DownloadPickerActivity).apply {
                text = label
                textSize = 14f
                setTextColor(COLOR_INK)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right)
            if (chevron) {
                addView(TextView(this@DownloadPickerActivity).apply {
                    text = "›"
                    textSize = 22f
                    setTextColor(COLOR_MUTED)
                    setPadding(dp(6), 0, 0, 0)
                })
            }
        }

    private fun settingsRowLp(seed: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun settingsSwitch(initial: Boolean): Switch = Switch(this).apply {
        isChecked = initial
        buttonTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(COLOR_SWITCH_TRACK_OFF, COLOR_ROSE)
        )
        trackTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(COLOR_SWITCH_TRACK_OFF, COLOR_ROSE)
        )
        scaleX = 0.9f
        scaleY = 0.9f
    }

    private fun divider(leftInset: Int = dp(16), rightInset: Int = dp(16)): View =
        View(this).apply {
            setBackgroundColor(COLOR_CARD_BORDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = leftInset
                rightMargin = rightInset
            }
        }

    /** 分区小标题。 */
    private fun sectionTitle(text: String, topMargin: Int = 0): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_MUTED)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = topMargin
            bottomMargin = dp(8)
        }
    }

    /** 画质下拉：底部单选列表。 */
    private fun showQualitySheet(
        qualities: List<DownloadQuality>,
        current: DownloadQuality?,
        onPick: (DownloadQuality) -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.attributes?.gravity = Gravity.BOTTOM

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_CARD, dp(20), COLOR_CARD_BORDER, 1)
            setPadding(dp(16), dp(18), dp(16), dp(10))
        }
        root.addView(TextView(this).apply {
            text = "选择画质"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        qualities.forEach { q ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = rounded(
                    if (q == current) COLOR_ROSE_SOFT else Color.TRANSPARENT, dp(12))
                isClickable = true
                setOnClickListener {
                    onPick(q)
                    dialog.dismiss()
                }
            }
            row.addView(TextView(this).apply {
                text = qLabel(q)
                textSize = 14f
                typeface = if (q == current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (q == current) COLOR_ROSE else COLOR_INK)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (q.needsVip) {
                row.addView(TextView(this).apply {
                    text = "大会员"
                    textSize = 10f
                    setTextColor(COLOR_ROSE)
                    background = rounded(COLOR_ROSE_SOFT, dp(7))
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                })
            }
            if (q == current) {
                row.addView(TextView(this).apply {
                    text = "✓"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_ROSE)
                })
            }
            root.addView(row)
        }
        root.addView(TextView(this).apply {
            text = "取消"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(12), dp(12), dp(12), dp(4))
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        dialog.setContentView(root)
        dialog.show()
        val w = (resources.displayMetrics.widthPixels * 0.94f).toInt()
        dialog.window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun qLabel(q: DownloadQuality): String =
        if (q.note.isBlank()) q.label else "${q.label} ${q.note}"
    private fun availableDownloadQualities(): List<DownloadQuality> {
        val all = BiliApi.downloadQualities()
        val accepted = acceptedQns.toSet()
        return if (accepted.isEmpty()) all else all.filter { it.qn in accepted }.ifEmpty { all }
    }

    /**
     * 外层滚动容器：触摸落在内层分集列表区域时把滚动让给内层，
     * 解决 ScrollView 嵌套时外层先拦截 MOVE 导致内层滚不动的问题。
     */
    private class PickerScrollView(context: Context) : ScrollView(context) {
        var innerScrollables: List<View> = emptyList()
        private val tmp = Rect()

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
                for (v in innerScrollables) {
                    if (v.isShown && v.getGlobalVisibleRect(tmp)) {
                        if (tmp.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                            return false
                        }
                    }
                }
            }
            return super.onInterceptTouchEvent(ev)
        }
    }

    companion object {
        const val EXTRA_ITEM_JSON = "item_json"
        const val EXTRA_ACCEPTED_QNS = "accepted_qns"

        fun itemToJson(item: VideoItem): String = JSONObject().apply {
            put("bvid", item.bvid)
            put("title", item.title)
            put("owner", item.owner)
            put("cover", item.cover)
            put("views", item.views)
            put("duration", item.duration)
            put("description", item.description)
            put("publishedAt", item.publishedAt)
            put("cid", item.cid)
            put("aid", item.aid)
            put("ownerMid", item.ownerMid)
            put("ownerAvatar", item.ownerAvatar)
            put("replyCount", item.replyCount)
            put("likes", item.likes)
            put("liked", item.liked)
            put("following", item.following)
            put("charge", item.charge)
            item.season?.let { s ->
                put("season", JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("cover", s.cover)
                    put("isMultiPage", s.isMultiPage)
                    val eps = JSONArray()
                    s.episodes.forEach { e ->
                        eps.put(JSONObject().apply {
                            put("bvid", e.bvid)
                            put("aid", e.aid)
                            put("cid", e.cid)
                            put("title", e.title)
                            put("duration", e.duration)
                            put("cover", e.cover)
                            put("charge", e.charge)
                        })
                    }
                    put("episodes", eps)
                })
            }
        }.toString()

        private fun parseVideoItem(raw: String): VideoItem {
            val o = JSONObject(raw)
            val season = o.optJSONObject("season")?.let { s ->
                val epsArr = s.optJSONArray("episodes")
                val eps = if (epsArr == null) emptyList() else (0 until epsArr.length()).map { i ->
                    val e = epsArr.getJSONObject(i)
                    UgcEpisode(
                        bvid = e.optString("bvid"), aid = e.optLong("aid"), cid = e.optLong("cid"),
                        title = e.optString("title"), duration = e.optInt("duration"),
                        cover = e.optString("cover"),
                        charge = e.optBoolean("charge")
                    )
                }
                UgcSeason(
                    id = s.optLong("id"), title = s.optString("title"), cover = s.optString("cover"),
                    episodes = eps, isMultiPage = s.optBoolean("isMultiPage")
                )
            }
            return VideoItem(
                bvid = o.optString("bvid"), title = o.optString("title"), owner = o.optString("owner"),
                cover = o.optString("cover"), views = o.optLong("views"), duration = o.optInt("duration"),
                description = o.optString("description"), publishedAt = o.optLong("publishedAt"),
                cid = o.optLong("cid"), aid = o.optLong("aid"), ownerMid = o.optLong("ownerMid"),
                ownerAvatar = o.optString("ownerAvatar"), replyCount = o.optLong("replyCount"),
                likes = o.optLong("likes"), liked = o.optBoolean("liked"),
                following = o.optBoolean("following"), season = season, charge = o.optBoolean("charge")
            )
        }
    }
}
