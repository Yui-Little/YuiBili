package com.yuilittle.bili

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Two long-lived, recycled home feeds. A genuine activity entry can refresh both
 * feeds while an Activity restoration only restores the persisted first frame.
 */
class HomeFeedView(context: Context, refreshOnEntry: Boolean) : LinearLayout(context) {
    private val tabs = arrayOfNulls<TextView>(2)
    private val panels = arrayOfNulls<FeedPanel>(2)
    private lateinit var tabIndicator: View
    private var selectedTab = TAB_RECOMMEND

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BACKGROUND)
        setPadding(dp(6), dp(8), dp(6), 0)
        addView(createSearchBar(), LayoutParams(dp(SEARCH_BOX_WIDTH_DP), dp(SEARCH_BOX_HEIGHT_DP)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addView(createTabs(), LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(4)
        })

        val host = SwipePagerContainer(context)
        for (tab in 0..1) {
            val panel = FeedPanel(context, tab).apply {
                visibility = if (tab == selectedTab) View.VISIBLE else View.INVISIBLE
            }
            panels[tab] = panel
            host.addView(panel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        addView(host, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        selectTab(TAB_RECOMMEND)

        // The snapshot is only a fast first frame. A fresh task/activity entry
        // advances each persisted page cursor so both feeds receive a new batch.
        panels.forEach { it?.loadSnapshot() }
        if (refreshOnEntry) panels.forEach { it?.refresh(fromUser = false) }
    }

    private fun createSearchBar(): View = TextView(context).apply {
        text = "搜索视频"
        textSize = SEARCH_BOX_TEXT_SIZE_SP
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(COLOR_MUTED)
        setPadding(dp(SEARCH_BOX_HORIZONTAL_PADDING_DP), 0, dp(SEARCH_BOX_HORIZONTAL_PADDING_DP), 0)
        background = rounded(COLOR_SURFACE, dp(SEARCH_BOX_CORNER_DP), COLOR_BORDER, 1)
        isClickable = true
        isFocusable = true
        contentDescription = "搜索视频"
        transitionName = SEARCH_BOX_TRANSITION_NAME
        setOnClickListener {
            val intent = Intent(context, SearchActivity::class.java)
            val activity = context as? Activity
            if (activity != null) {
                val options = ActivityOptions.makeSceneTransitionAnimation(
                    activity,
                    this,
                    SEARCH_BOX_TRANSITION_NAME
                )
                activity.startActivity(intent, options.toBundle())
            } else {
                context.startActivity(intent)
            }
        }
    }

    private fun createTabs(): View = FrameLayout(context).apply {
        val strip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setGravity(Gravity.CENTER)
            setPadding(dp(62), dp(4), dp(62), dp(4))
        }
        listOf("推荐", "热门").forEachIndexed { index, label ->
            val tab = TextView(context).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(index) }
            }
            tabs[index] = tab
            strip.addView(tab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (index == TAB_POPULAR) leftMargin = dp(20)
            })
        }
        addView(strip, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        tabIndicator = View(context).apply {
            background = rounded(COLOR_ROSE, dp(2))
        }
        addView(tabIndicator, FrameLayout.LayoutParams(dp(TAB_INDICATOR_WIDTH), dp(3), Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(4)
        })
        post { moveTabIndicator(selectedTab, animate = false) }
    }

    /** Saves only lightweight UI anchors; feed content/cursors live in FeedStore. */
    fun saveState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab)
        panels.forEachIndexed { tab, panel -> panel?.saveState(outState, tab) }
    }

    /** Restores UI after snapshots are attached, without treating it as a new entry. */
    fun restoreState(state: Bundle) {
        val restoredTab = state.getInt(STATE_SELECTED_TAB, TAB_RECOMMEND).coerceIn(TAB_RECOMMEND, TAB_POPULAR)
        panels.forEachIndexed { tab, panel -> panel?.restoreState(state, tab) }
        selectTab(restoredTab)
    }

    private fun selectTab(tab: Int) {
        if (tab !in 0..1) return
        val previous = selectedTab
        val changed = previous != tab
        selectedTab = tab
        for (index in 0..1) {
            tabs[index]?.let { label ->
                val selected = index == tab
                label.setTextColor(if (selected) COLOR_ROSE else COLOR_MUTED)
                label.typeface = Typeface.create(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                label.background = if (selected) rounded(COLOR_ROSE_SOFT, dp(16)) else null
                label.animate().cancel()
                label.animate()
                    .scaleX(if (selected) 1.07f else 1f)
                    .scaleY(if (selected) 1.07f else 1f)
                    .alpha(if (selected) 1f else 0.72f)
                    .setDuration(TAB_SWITCH_DURATION_MS)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                    .start()
            }
        }
        moveTabIndicator(tab, animate = changed)
        if (changed) animatePanelTransition(previous, tab) else {
            panels[tab]?.visibility = View.VISIBLE
            panels[tab]?.activateCovers()
        }
    }

    private fun moveTabIndicator(tab: Int, animate: Boolean) {
        val target = tabs[tab] ?: return
        target.post {
            val targetX = target.left + (target.width - tabIndicator.width) / 2f
            tabIndicator.animate().cancel()
            if (animate) {
                tabIndicator.animate()
                    .translationX(targetX)
                    .setDuration(TAB_SWITCH_DURATION_MS)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                    .start()
            } else {
                tabIndicator.translationX = targetX
            }
        }
    }

    private fun animatePanelTransition(from: Int, to: Int) {
        val outgoing = panels[from] ?: return
        val incoming = panels[to] ?: return
        val direction = if (to > from) 1 else -1
        outgoing.animate().cancel()
        incoming.animate().cancel()
        // Slide both panels simultaneously in the same direction so the new grid
        // enters from the right/left while the old one exits the opposite edge.
        // Both remain fully opaque and opaque-background, preventing the flash
        // that a sequential fade causes.
        incoming.visibility = View.VISIBLE
        incoming.alpha = 1f
        val pageWidth = width.toFloat().coerceAtLeast(1f)
        val interactive = kotlin.math.abs(outgoing.translationX) > 0.5f ||
            kotlin.math.abs(incoming.translationX) > 0.5f
        // A tap starts the incoming panel at its natural side. A swipe already
        // positioned both panels, so continue from those exact coordinates.
        if (!interactive) {
            outgoing.translationX = 0f
            incoming.translationX = direction * pageWidth
        }
        outgoing.alpha = 1f
        outgoing.animate()
            .translationX(-direction * width.toFloat())
            .setDuration(TAB_SWITCH_DURATION_MS)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .withEndAction {
                if (selectedTab == to) {
                    outgoing.visibility = View.INVISIBLE
                    outgoing.translationX = 0f
                }
            }
            .start()
        incoming.animate()
            .translationX(0f)
            .setDuration(TAB_SWITCH_DURATION_MS)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .withEndAction {
                if (selectedTab == to) incoming.activateCovers()
            }
            .start()
        incoming.activateCovers()
    }

    /** Host for the two feed panels; horizontal drags swipe between tabs. */
    private inner class SwipePagerContainer(context: Context) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var tracking = false
        private var dragging = false
        private val slop: Int = ViewConfiguration.get(context).scaledTouchSlop

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    tracking = true
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tracking) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f) {
                            dragging = true
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    dragging = false
                }
            }
            return super.onInterceptTouchEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!dragging) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> applyOffset(event.x - downX)
                MotionEvent.ACTION_UP -> {
                    tracking = false
                    dragging = false
                    val dx = event.x - downX
                    val target = if (dx < 0) TAB_POPULAR else TAB_RECOMMEND
                    // At either outer edge there is no adjacent page. Keep the
                    // selected panel pinned instead of exposing the host background.
                    if (target == selectedTab) {
                        springBack()
                    } else if (kotlin.math.abs(dx) > width * 0.22f) {
                        selectTab(target)
                    } else {
                        springBack()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    dragging = false
                    springBack()
                }
            }
            return true
        }

        private fun applyOffset(dx: Float) {
            val from = selectedTab
            val to = if (dx < 0) TAB_POPULAR else TAB_RECOMMEND
            if (to == from) {
                // Hard-clamp the two outer edges. Moving the only visible panel
                // here exposes the container background as a blank strip.
                panels[from]?.translationX = 0f
                panels[1 - from]?.let { hidden ->
                    hidden.animate().cancel()
                    hidden.visibility = View.INVISIBLE
                    hidden.translationX = 0f
                    hidden.alpha = 1f
                }
                return
            }
            val dir = if (to > from) 1 else -1
            val pageWidth = width.toFloat().coerceAtLeast(1f)
            val offset = dx.coerceIn(-pageWidth, pageWidth)
            panels[from]?.let {
                it.animate().cancel()
                it.visibility = View.VISIBLE
                it.translationX = offset
            }
            panels[to]?.let {
                it.animate().cancel()
                it.visibility = View.VISIBLE
                it.alpha = 1f
                // A page with greater index lives to the right; a lower one to the left.
                it.translationX = dir * pageWidth + offset
            }
        }

        private fun springBack() {
            val from = selectedTab
            val current = panels[from] ?: return
            val other = panels[1 - from]
            current.animate().cancel()
            current.animate()
                .translationX(0f)
                .setDuration(TAB_SWITCH_DURATION_MS)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()
            other?.let { page ->
                page.animate().cancel()
                if (page.visibility == View.VISIBLE) {
                    val restingX = if (page.translationX >= 0f) width.toFloat() else -width.toFloat()
                    page.animate()
                        .translationX(restingX)
                        .setDuration(TAB_SWITCH_DURATION_MS)
                        .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                        .withEndAction {
                            if (selectedTab == from) {
                                page.visibility = View.INVISIBLE
                                page.translationX = 0f
                                page.alpha = 1f
                            }
                        }
                        .start()
                } else {
                    page.translationX = 0f
                    page.alpha = 1f
                }
            }
        }
    }

    private inner class FeedPanel(context: Context, private val tab: Int) : FrameLayout(context) {
        private val adapter = VideoAdapter(context)
        private val grid = PullGridView(context)
        private val topSpinner = ProgressBar(context)
        private val bottomSpinner = ProgressBar(context)
        private val notice = TextView(context)
        private var page = 0
        private var lastScannedPage = 0
        private var generation = 0
        private var refreshing = false
        private var loadingMore = false
        private var savingState = false
        private var queuedRefreshFromUser: Boolean? = null
        private var reachedEnd = false
        private var userHasScrolled = false

        init {
            topSpinner.apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(COLOR_ROSE)
                alpha = 0f
                visibility = View.GONE
                contentDescription = "正在刷新"
            }
            bottomSpinner.apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(COLOR_ROSE)
                visibility = View.GONE
                contentDescription = "正在加载更多"
            }
            grid.apply {
                numColumns = 2
                horizontalSpacing = dp(4)
                verticalSpacing = dp(6)
                setPadding(dp(2), dp(2), dp(2), dp(104))
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setVerticalScrollBarEnabled(false)
                setHorizontalScrollBarEnabled(false)
                setSelector(android.R.color.transparent)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                adapter = this@FeedPanel.adapter
                setOnItemClickListener { _, _, position, _ ->
                    this@FeedPanel.adapter.itemOrNull(position)?.let { video ->
                        VideoDetailActivity.open(context, video.bvid, video.aid)
                    }
                }
                onRefreshRequested = { refresh(fromUser = true) }
                onPullChanged = { distance, _ -> updateTopSpinner(distance) }
                setOnScrollListener(object : AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                        if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL ||
                            scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING
                        ) {
                            userHasScrolled = true
                        }
                    }

                    override fun onScroll(
                        view: AbsListView?,
                        firstVisibleItem: Int,
                        visibleItemCount: Int,
                        totalItemCount: Int
                    ) {
                        if (userHasScrolled && totalItemCount > 0 &&
                            firstVisibleItem + visibleItemCount >= totalItemCount - LOAD_AHEAD_ITEMS
                        ) {
                            loadMore()
                        }
                    }
                })
            }
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(topSpinner, LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(4)
            })
            addView(notice, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(44)
            })
            addView(bottomSpinner, LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(84)
            })
            notice.apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(COLOR_MUTED)
                setPadding(dp(13), dp(7), dp(13), dp(7))
                background = rounded(COLOR_NOTICE, dp(14), COLOR_BORDER, 1)
                visibility = View.GONE
                isClickable = true
            }
        }

        fun activateCovers() {
            adapter.enableCoverLoading()
        }

        fun loadSnapshot() {
            val state = FeedStore.loadState(context, tab)
            page = state.lastSuccessfulPage
            lastScannedPage = state.lastScannedPage
            if (state.videos.isNotEmpty()) adapter.replace(state.videos, animateNew = false)
        }

        fun saveState(outState: Bundle, slot: Int) {
            outState.putInt(scrollPositionKey(slot), grid.firstVisiblePosition.coerceAtLeast(0))
            outState.putInt(scrollOffsetKey(slot), grid.getChildAt(0)?.top ?: grid.paddingTop)
        }

        fun restoreState(state: Bundle, slot: Int) {
            if (!state.containsKey(scrollPositionKey(slot)) || adapter.count == 0) return
            val position = state.getInt(scrollPositionKey(slot), 0)
                .coerceIn(0, (adapter.count - 1).coerceAtLeast(0))
            val offset = state.getInt(scrollOffsetKey(slot), grid.paddingTop)
            grid.post { grid.setSelectionFromTop(position, offset) }
        }

        fun refresh(fromUser: Boolean) {
            if (savingState) {
                queuedRefreshFromUser = fromUser
                cancelAppendUiState()
                return
            }
            if (refreshing) return
            refreshing = true
            cancelAppendUiState()
            reachedEnd = false
            // Adapter replacement and pull-reset both dispatch onScroll. They
            // are not user pagination gestures and must not auto-load the next
            // (possibly terminal) page immediately after a refresh.
            userHasScrolled = false
            generation += 1
            val requestGeneration = generation
            hideNotice()
            grid.setRefreshing(true)
            showTopSpinner()

            // Pull-to-refresh means a visibly new batch on both feeds. Popular
            // advances through ranking pages too, but its terminal page wraps to
            // page one instead of permanently disabling future refreshes.
            val known = adapter.bvids()
            val startPage = FeedPagingPolicy.nextRefreshPage(maxOf(page, lastScannedPage))
            loadFreshPage(startPage, known, requestGeneration, fromUser, attempts = 0, didWrap = false)
        }

        private fun loadFreshPage(
            candidatePage: Int,
            known: Set<String>,
            requestGeneration: Int,
            fromUser: Boolean,
            attempts: Int,
            didWrap: Boolean
        ) {
            requestPage(candidatePage, System.currentTimeMillis()) callback@{ result, _ ->
                if (requestGeneration != generation) return@callback
                if (result == null) {
                    finishRefresh()
                    showTopNotice(
                        if (adapter.count == 0) "加载失败，点这里重试" else "刷新失败，已保留当前内容",
                        clickable = true
                    ) { refresh(fromUser = fromUser) }
                    return@callback
                }

                val additions = distinct(result.items, known)
                val scannedPage = if (tab == TAB_POPULAR) candidatePage else {
                    maxOf(lastScannedPage, candidatePage)
                }
                if (additions.isNotEmpty()) {
                    persistFeedState(additions, candidatePage, scannedPage, requestGeneration,
                        onCommitted = {
                            page = candidatePage
                            lastScannedPage = scannedPage
                            adapter.replace(additions, animateNew = true)
                            finishRefresh()
                        },
                        onFailed = {
                            finishRefresh()
                            showTopNotice("保存失败，点这里重试", clickable = true) {
                                refresh(fromUser = fromUser)
                            }
                        }
                    )
                    return@callback
                }

                persistFeedState(adapter.snapshot(), page, scannedPage, requestGeneration,
                    onCommitted = {
                        lastScannedPage = scannedPage
                        val canScanAgain = attempts + 1 < FeedPagingPolicy.MAX_REFRESH_SCAN_PAGES
                        when {
                            canScanAgain && result.endReached && !didWrap ->
                                loadFreshPage(1, known, requestGeneration, fromUser, attempts + 1, didWrap = true)
                            canScanAgain && !result.endReached ->
                                loadFreshPage(candidatePage + 1, known, requestGeneration, fromUser, attempts + 1, didWrap)
                            else -> {
                                finishRefresh()
                                showTopNotice("暂未发现新的内容", clickable = false)
                            }
                        }
                    },
                    onFailed = {
                        finishRefresh()
                        showTopNotice("保存刷新进度失败，点这里重试", clickable = true) {
                            refresh(fromUser = fromUser)
                        }
                    }
                )
            }
        }

        private fun loadMore() {
            if (savingState || refreshing || loadingMore || reachedEnd || adapter.count == 0) return
            loadingMore = true
            hideNotice()
            showBottomLoader(true)
            loadMorePage(maxOf(page, lastScannedPage) + 1, skippedPages = 0, didWrap = false)
        }

        private fun loadMorePage(candidatePage: Int, skippedPages: Int, didWrap: Boolean) {
            val requestGeneration = generation
            requestPage(candidatePage, 0L) callback@{ result, _ ->
                if (requestGeneration != generation) return@callback
                if (result == null) {
                    cancelAppendUiState()
                    showTopNotice("加载失败，点这里重试", clickable = true) { loadMore() }
                    return@callback
                }

                val scannedPage = if (tab == TAB_POPULAR && didWrap) candidatePage else {
                    maxOf(lastScannedPage, candidatePage)
                }
                if (result.endReached && FeedPagingPolicy.isTerminalPage(result.items.size)) {
                    if (tab == TAB_POPULAR && !didWrap) {
                        // Popular is a rotating finite ranking, not a permanent
                        // feed end. Wrap to page one and look for newly ranked
                        // videos instead of locking reachedEnd forever.
                        loadMorePage(1, skippedPages = 0, didWrap = true)
                    } else {
                        val terminalPage = if (tab == TAB_POPULAR) 0 else page
                        val terminalScan = if (tab == TAB_POPULAR) 0 else scannedPage
                        persistFeedState(adapter.snapshot(), terminalPage, terminalScan, requestGeneration,
                            onCommitted = {
                                page = terminalPage
                                lastScannedPage = terminalScan
                                reachedEnd = tab != TAB_POPULAR
                                cancelAppendUiState()
                                showTopNotice(
                                    if (tab == TAB_POPULAR) "暂时没有新的热门内容" else "已经到底了",
                                    clickable = false
                                )
                            },
                            onFailed = {
                                cancelAppendUiState()
                                showTopNotice("保存分页进度失败，点这里重试", clickable = true) { loadMore() }
                            }
                        )
                    }
                    return@callback
                }

                val additions = distinct(result.items, adapter.bvids())
                if (additions.isEmpty()) {
                    persistFeedState(adapter.snapshot(), page, scannedPage, requestGeneration,
                        onCommitted = {
                            lastScannedPage = scannedPage
                            if (skippedPages < FeedPagingPolicy.MAX_APPEND_SKIP_PAGES) {
                                loadMorePage(candidatePage + 1, skippedPages + 1, didWrap)
                            } else {
                                cancelAppendUiState()
                                showTopNotice("本页内容重复，继续下滑重试", clickable = false)
                            }
                        },
                        onFailed = {
                            cancelAppendUiState()
                            showTopNotice("保存分页进度失败，点这里重试", clickable = true) { loadMore() }
                        }
                    )
                    return@callback
                }

                val nextWindow = adapter.snapshotAfterAppend(additions)
                persistFeedState(nextWindow, candidatePage, scannedPage, requestGeneration,
                    onCommitted = {
                        val firstVisible = grid.firstVisiblePosition
                        val firstTop = grid.getChildAt(0)?.top ?: grid.paddingTop
                        val removed = adapter.appendWindowed(additions)
                        if (removed > 0) {
                            grid.setSelectionFromTop((firstVisible - removed).coerceAtLeast(0), firstTop)
                        }
                        page = candidatePage
                        lastScannedPage = scannedPage
                        reachedEnd = false
                        cancelAppendUiState()
                    },
                    onFailed = {
                        cancelAppendUiState()
                        showTopNotice("保存失败，点这里重试", clickable = true) { loadMore() }
                    }
                )
            }
        }

        private fun requestPage(page: Int, token: Long, callback: (FeedPage?, String?) -> Unit) {
            if (tab == TAB_RECOMMEND) {
                BiliApi.fetchRecommend(page, token, callback)
            } else {
                BiliApi.fetchPopular(page, token, callback)
            }
        }

        private fun persistFeedState(
            videos: List<VideoItem>,
            successfulPage: Int,
            scannedPage: Int,
            requestGeneration: Int,
            onCommitted: () -> Unit,
            onFailed: () -> Unit
        ) {
            savingState = true
            FeedStore.saveState(context, tab, videos, successfulPage, scannedPage) { committed ->
                savingState = false
                if (requestGeneration == generation) {
                    if (committed) onCommitted() else onFailed()
                }
                queuedRefreshFromUser?.let { queuedFromUser ->
                    queuedRefreshFromUser = null
                    refresh(queuedFromUser)
                }
            }
        }

        private fun cancelAppendUiState() {
            loadingMore = false
            showBottomLoader(false)
        }

        private fun distinct(items: List<VideoItem>, existing: Set<String>): List<VideoItem> {
            val seen = existing.toMutableSet()
            return items.filter { it.bvid.isNotBlank() && seen.add(it.bvid) }
        }

        private fun finishRefresh() {
            refreshing = false
            grid.setRefreshing(false)
            hideTopSpinner()
        }

        private fun updateTopSpinner(distance: Float) {
            if (refreshing) {
                showTopSpinner()
                return
            }
            if (distance <= 0f) {
                hideTopSpinner()
                return
            }
            topSpinner.visibility = View.VISIBLE
            topSpinner.alpha = (distance / dp(PULL_REFRESH_DISTANCE).toFloat()).coerceIn(0.25f, 1f)
        }

        private fun showTopSpinner() {
            topSpinner.animate().cancel()
            topSpinner.visibility = View.VISIBLE
            topSpinner.alpha = 1f
        }

        private fun hideTopSpinner() {
            topSpinner.animate().cancel()
            topSpinner.visibility = View.GONE
            topSpinner.alpha = 0f
        }

        private fun showBottomLoader(show: Boolean) {
            bottomSpinner.visibility = if (show) View.VISIBLE else View.GONE
        }

        private fun showTopNotice(text: String, clickable: Boolean, action: (() -> Unit)? = null) {
            notice.removeCallbacks(hideNotice)
            notice.animate().cancel()
            notice.alpha = 1f
            notice.text = text
            notice.isClickable = clickable
            notice.setOnClickListener(if (clickable && action != null) View.OnClickListener { action() } else null)
            notice.visibility = View.VISIBLE
            if (!clickable) notice.postDelayed(hideNotice, NOTICE_DURATION_MS)
        }

        private fun hideNotice() {
            notice.removeCallbacks(hideNotice)
            notice.animate().cancel()
            notice.visibility = View.GONE
            notice.alpha = 1f
        }

        private val hideNotice = Runnable {
            notice.animate().alpha(0f).setDuration(150L).withEndAction {
                notice.visibility = View.GONE
                notice.alpha = 1f
            }.start()
        }
    }

    private inner class PullGridView(context: Context) : GridView(context) {
        var onRefreshRequested: (() -> Unit)? = null
        var onPullChanged: ((Float, Boolean) -> Unit)? = null
        private var downY = 0f
        private var pullDistance = 0f
        private var pulling = false
        private var armed = false
        private var refreshing = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (refreshing) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    pullDistance = 0f
                    pulling = false
                    armed = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val distance = (event.y - downY).coerceAtLeast(0f)
                    if (pulling || (isAtTop() && distance > touchSlop)) {
                        pulling = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        pullDistance = distance
                        armed = pullDistance >= dp(PULL_REFRESH_DISTANCE)
                        translationY = minOf(pullDistance * PULL_DRAG_RATIO, dp(PULL_MAX_OFFSET).toFloat())
                        onPullChanged?.invoke(translationY, armed)
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (pulling) {
                        if (armed) {
                            setRefreshing(true)
                            onRefreshRequested?.invoke()
                        } else {
                            resetPull()
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> if (pulling) {
                    resetPull()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        fun setRefreshing(value: Boolean) {
            refreshing = value
            if (value) {
                pulling = false
                armed = false
                translationY = dp(PULL_REFRESH_HOLD).toFloat()
                onPullChanged?.invoke(translationY, true)
            } else {
                resetPull()
            }
        }

        private fun resetPull() {
            pulling = false
            armed = false
            pullDistance = 0f
            animate().cancel()
            animate().translationY(0f).setDuration(180L).setInterpolator(MOTION_EASE).start()
            onPullChanged?.invoke(0f, false)
        }

        private fun isAtTop(): Boolean =
            firstVisiblePosition == 0 && (getChildAt(0)?.top ?: 0) >= paddingTop
    }

    private inner class VideoAdapter(private val context: Context) : BaseAdapter() {
        private val videos = ArrayList<VideoItem>()
        private val pendingAnimations = LinkedHashSet<String>()
        private var coverLoadingEnabled = false
        val reduced = MotionTokens.isReduced(context)
        private val cardWidth = (resources.displayMetrics.widthPixels - dp(16)) / 2
        private val coverHeight = (cardWidth - dp(8)) * 9 / 16
        private val cardHeight = coverHeight + dp(90)

        override fun getCount(): Int = videos.size
        override fun getItem(position: Int): VideoItem = videos[position]
        override fun getItemId(position: Int): Long = videos[position].bvid.hashCode().toLong()
        override fun hasStableIds(): Boolean = true

        fun itemOrNull(position: Int): VideoItem? = videos.getOrNull(position)
        fun bvids(): Set<String> = videos.mapTo(LinkedHashSet()) { it.bvid }
        fun snapshot(): List<VideoItem> = videos.toList()

        fun snapshotAfterAppend(items: List<VideoItem>): List<VideoItem> {
            if (items.isEmpty()) return snapshot()
            val next = ArrayList(videos)
            next.addAll(items)
            val removed = FeedPagingPolicy.trimFromStart(videos.size, items.size)
            if (removed > 0) next.subList(0, removed).clear()
            return next
        }

        fun enableCoverLoading() {
            if (coverLoadingEnabled) return
            coverLoadingEnabled = true
            notifyDataSetChanged()
        }

        fun replace(items: List<VideoItem>, animateNew: Boolean) {
            val oldIds = bvids()
            videos.clear()
            videos.addAll(items)
            pendingAnimations.clear()
            if (animateNew) items.filter { it.bvid !in oldIds }.forEach { pendingAnimations += it.bvid }
            notifyDataSetChanged()
        }

        /** Appends a real page and trims only old history when the window grows too large. */
        fun appendWindowed(items: List<VideoItem>): Int {
            if (items.isEmpty()) return 0
            val oldSize = videos.size
            videos.addAll(items)
            items.forEach { pendingAnimations += it.bvid }
            val removed = FeedPagingPolicy.trimFromStart(oldSize, items.size)
            if (removed > 0) {
                val dropped = videos.take(removed).mapTo(HashSet()) { it.bvid }
                videos.subList(0, removed).clear()
                pendingAnimations.removeAll(dropped)
            }
            notifyDataSetChanged()
            return removed
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val card: LinearLayout
            val holder: CardHolder
            if (convertView == null) {
                card = createCard()
                holder = card.tag as CardHolder
            } else {
                card = convertView as LinearLayout
                holder = card.tag as CardHolder
            }
            val video = videos[position]
            holder.title.text = video.title
            holder.owner.text = "UP: ${video.owner.ifBlank { "未知UP主" }}"
            holder.meta.text = "${formatPublishedDate(video.publishedAt)}  ·  ${formatViews(video.views)}播放"
            holder.duration.text = formatDuration(video.duration)
            if (coverLoadingEnabled) {
                CoverLoader.load(holder.cover, video.cover)
            } else {
                holder.cover.tag = null
                holder.cover.setImageDrawable(null)
            }
            card.contentDescription = "${video.title}，${video.owner}，${formatViews(video.views)}播放"
            card.animate().cancel()
            card.alpha = 1f
            card.translationY = 0f
            if (!reduced && pendingAnimations.remove(video.bvid)) {
                card.alpha = 0f
                card.translationY = dp(14).toFloat()
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((position % 2) * 32L)
                    .setDuration(240L)
                    .setInterpolator(MOTION_EASE)
                    .start()
            }
            return card
        }

        private fun createCard(): LinearLayout {
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                background = rounded(COLOR_CARD, dp(15), COLOR_CARD_BORDER, 1)
                setPadding(dp(4), dp(4), dp(4), dp(6))
                layoutParams = AbsListView.LayoutParams(cardWidth, cardHeight)
            }
            val coverFrame = FrameLayout(context).apply {
                background = rounded(COLOR_COVER, dp(12))
                clipToOutline = true
            }
            val cover = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(COLOR_COVER)
            }
            coverFrame.addView(cover, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            val duration = TextView(context).apply {
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            coverFrame.addView(duration, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(22),
                Gravity.END or Gravity.BOTTOM
            ).apply {
                rightMargin = dp(6)
                bottomMargin = dp(6)
            })
            card.addView(coverFrame, LayoutParams(LayoutParams.MATCH_PARENT, coverHeight))
            val title = TextView(context).apply {
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(COLOR_INK)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(7), dp(6), dp(7), 0)
            }
            card.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
            val owner = TextView(context).apply {
                textSize = 11f
                setTextColor(COLOR_MUTED)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(7), dp(1), dp(7), 0)
            }
            card.addView(owner, LayoutParams(LayoutParams.MATCH_PARENT, dp(17)))
            val meta = TextView(context).apply {
                textSize = 11f
                setTextColor(COLOR_MUTED)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(7), 0, dp(7), 0)
            }
            card.addView(meta, LayoutParams(LayoutParams.MATCH_PARENT, dp(16)))
            card.tag = CardHolder(cover, duration, title, owner, meta)
            return card
        }
    }

    private data class CardHolder(
        val cover: ImageView,
        val duration: TextView,
        val title: TextView,
        val owner: TextView,
        val meta: TextView
    )

    private fun scrollPositionKey(slot: Int): String = "$STATE_SCROLL_POSITION_PREFIX$slot"

    private fun scrollOffsetKey(slot: Int): String = "$STATE_SCROLL_OFFSET_PREFIX$slot"

    private companion object {
        const val TAB_RECOMMEND = 0
        const val TAB_POPULAR = 1
        const val SEARCH_BOX_WIDTH_DP = 224
        const val HOME_SEARCH_WIDTH = SEARCH_BOX_WIDTH_DP
        const val SEARCH_BOX_HEIGHT_DP = 44
        const val SEARCH_BOX_CORNER_DP = 18
        const val SEARCH_BOX_HORIZONTAL_PADDING_DP = 15
        const val SEARCH_BOX_TEXT_SIZE_SP = 15f
        const val SEARCH_BOX_TRANSITION_NAME = "yui_search_box"
        const val TAB_SWITCH_DURATION_MS = 190L
        const val TAB_INDICATOR_WIDTH = 28
        const val TAB_PANEL_SHIFT = 18
        const val STATE_SELECTED_TAB = "home_selected_tab"
        const val STATE_SCROLL_POSITION_PREFIX = "home_scroll_position_"
        const val STATE_SCROLL_OFFSET_PREFIX = "home_scroll_offset_"
        const val LOAD_AHEAD_ITEMS = 4
        const val PULL_REFRESH_DISTANCE = 56
        const val PULL_REFRESH_HOLD = 38
        const val PULL_MAX_OFFSET = 56
        const val PULL_DRAG_RATIO = 0.42f
        const val NOTICE_DURATION_MS = 1_600L
        val MOTION_EASE = MotionTokens.easeOut
    }
}

/**
 * Global UI theme. Switchable at runtime; all COLOR_* constants follow it.
 */
object Theme {
    const val PREFS = "yuibili_settings"
    const val KEY_DARK = "dark_mode"

    @Volatile
    var isDark: Boolean = false
        private set

    /**
     * One-shot circular theme transition: the outgoing screenshot is drawn as a
     * full-screen overlay and then revealed/collapsed around [cx]/[cy].
     * Cleared after the animation finishes (or if no Activity consumes it).
     */
    @Volatile
    var pendingTransitionBitmap: android.graphics.Bitmap? = null
        private set
    @Volatile
    var pendingTransitionCx: Float = 0f
        private set
    @Volatile
    var pendingTransitionCy: Float = 0f
        private set
    @Volatile
    var pendingTransitionExpand: Boolean = true
        private set

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isDark = prefs.getBoolean(KEY_DARK, false)
    }

    fun setDark(context: Context, dark: Boolean) {
        isDark = dark
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, dark).apply()
    }

    fun prepareCircularTransition(
        snapshot: android.graphics.Bitmap,
        cx: Float,
        cy: Float,
        expand: Boolean
    ) {
        pendingTransitionBitmap?.recycle()
        pendingTransitionBitmap = snapshot
        pendingTransitionCx = cx
        pendingTransitionCy = cy
        pendingTransitionExpand = expand
    }

    fun peekCircularTransition(): Boolean =
        pendingTransitionBitmap != null

    fun consumeCircularTransition(): Triple<android.graphics.Bitmap, android.util.Pair<Float, Float>, Boolean>? {
        val bmp = pendingTransitionBitmap ?: return null
        pendingTransitionBitmap = null
        return Triple(
            bmp,
            android.util.Pair(pendingTransitionCx, pendingTransitionCy),
            pendingTransitionExpand
        )
    }

    /**
     * Status / navigation bar chrome.
     * - lightContent=true: black status strip for pure black player chrome.
     * - otherwise: themed canvas color, with light/dark icons matching the theme.
     * Fullscreen player should call its own immersive helpers instead.
     */
    fun applySystemBars(activity: android.app.Activity, lightContent: Boolean = false) {
        val window = activity.window
        window.statusBarColor = if (lightContent) android.graphics.Color.BLACK else COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        @Suppress("DEPRECATION")
        var flags = window.decorView.systemUiVisibility and
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
            0x00000010.inv()
        if (!isDark && !lightContent) {
            // Dark icons on light canvas. 0x10 = LIGHT_NAVIGATION_BAR (API 26+).
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or 0x00000010
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }
}

internal val COLOR_BACKGROUND: Int get() = if (Theme.isDark) 0xFF141218.toInt() else 0xFFFFFCFA.toInt()
internal val COLOR_CARD: Int get() = if (Theme.isDark) 0xFF1E1A24.toInt() else 0xFFFFFFFF.toInt()
/** Elevated surfaces (search boxes, floating docks, dialogs). */
internal val COLOR_SURFACE: Int get() = if (Theme.isDark) 0xFF252029.toInt() else 0xFFFFFFFF.toInt()
internal val COLOR_INK: Int get() = if (Theme.isDark) 0xFFEDE6EA.toInt() else 0xFF2D282B.toInt()
internal val COLOR_MUTED: Int get() = if (Theme.isDark) 0xFF9A9098.toInt() else 0xFF81777B.toInt()
internal val COLOR_ROSE: Int get() = 0xFFE95786.toInt()
internal val COLOR_ROSE_SOFT: Int get() = if (Theme.isDark) 0xFF3A2130.toInt() else 0xFFFFE9F0.toInt()
internal val COLOR_RED_SOFT: Int get() = if (Theme.isDark) 0xFF3A2024.toInt() else 0xFFFFE6E8.toInt()
internal val COLOR_BORDER: Int get() = if (Theme.isDark) 0xFF2A242E.toInt() else 0xFFEDE4E6.toInt()
internal val COLOR_CARD_BORDER: Int get() = if (Theme.isDark) 0xFF322B36.toInt() else 0xFFE7DDE0.toInt()
internal val COLOR_COVER: Int get() = if (Theme.isDark) 0xFF221E28.toInt() else 0xFFF0E8EA.toInt()
internal val COLOR_NOTICE: Int get() = if (Theme.isDark) 0xFF1E1A24.toInt() else 0xFFFFF7F9.toInt()
internal val COLOR_SWITCH_TRACK_OFF: Int get() = if (Theme.isDark) 0xFF4A4348.toInt() else 0xFFD9D2D6.toInt()
internal val COLOR_NAV_INACTIVE: Int get() = if (Theme.isDark) 0xFFB0A6AC.toInt() else 0xFF666064.toInt()
internal val COLOR_NAV_FILL: Int get() = if (Theme.isDark) 0xF2252029.toInt() else 0xF8FFFCFD.toInt()
internal val COLOR_NAV_STROKE: Int get() = if (Theme.isDark) 0xAA3A3238.toInt() else 0xAAF1E4EA.toInt()
/** Soft chip / avatar / skeleton fill that stays visible on both themes. */
internal val COLOR_SOFT_FILL: Int get() = if (Theme.isDark) 0x33FFFFFF.toInt() else 0x14000000.toInt()
internal val COLOR_SOFT_FILL_STRONG: Int get() = if (Theme.isDark) 0x44FFFFFF.toInt() else 0x22000000.toInt()
internal val COLOR_FOLLOWED_CHIP: Int get() = if (Theme.isDark) 0x33FFFFFF.toInt() else 0x14CCCCCC.toInt()
internal val COLOR_ON_ROSE: Int get() = if (Theme.isDark) 0xFFFFF7FA.toInt() else 0xFF141414.toInt()

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
internal fun View.dp(value: Int): Int = context.dp(value)

internal fun rounded(fill: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setShape(GradientDrawable.RECTANGLE)
        setColor(fill)
        setCornerRadius(radius.toFloat())
        if (stroke != null && strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

internal fun formatDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val remainder = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%d:%02d".format(minutes, remainder)
}

internal fun formatViews(value: Long): String = when {
    value >= 100_000_000L -> compactCount(value / 100_000_000.0) + "亿"
    value >= 10_000L -> compactCount(value / 10_000.0) + "万"
    value > 0L -> value.toString()
    else -> "--"
}

/** 万/亿级紧凑计数：整万显示 "1"，非整万保留一位小数，如 "1.2"。 */
private fun compactCount(raw: Double): String {
    val rounded = Math.round(raw * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString()
    else String.format(Locale.CHINA, "%.1f", rounded)
}

/** Source timestamps are epoch seconds; use a stable China-local date label. */
internal fun formatPublishedDate(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "日期未知"
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(epochSeconds * 1_000L))
}
