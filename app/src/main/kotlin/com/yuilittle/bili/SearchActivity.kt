package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.Transition
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Search is a two-stage surface within one activity: entry/typing -> results.
 * This keeps result back-navigation inside search rather than losing the query
 * and jumping to home. The home search field is the shared element for entry
 * and return, so the surface expands and collapses from the original position.
 */
class SearchActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var state: TextView
    private lateinit var results: GridView
    private lateinit var resultsHost: FrameLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var resultAdapter: SearchVideoAdapter
    private lateinit var suggestions: LinearLayout
    private lateinit var historySection: LinearLayout
    private lateinit var historyChips: HistoryChipLayout
    private lateinit var clearHistoryAction: TextView
    private lateinit var searchAction: TextView
    private lateinit var loadingSpinner: ProgressBar
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSuggestion: Runnable? = null
    private var searchInProgress = false
    private var loadingNextPage = false
    private var searchEndReached = false
    private var currentSearchKeyword = ""
    private var searchPage = 0
    private var showingResultPage = false
    private var searchGeneration = 0
    private lateinit var pagingSpinner: ProgressBar
    private var suppressTextWatcher = false
    private var entryFocusDispatched = false

    // ── Inline search filters ────────────────────────────────────
    // Each group is always visible under the search box; only one option row
    // expands at a time. Selection applies immediately and refreshes results.
    private var filterDuration = 0
    private var filterPlayOrder = 0
    private var filterPublishOrder = 0
    private lateinit var filterBar: LinearLayout
    private lateinit var filterDropdown: LinearLayout
    private lateinit var durationFilter: TextView
    private lateinit var playFilter: TextView
    private lateinit var publishFilter: TextView
    private var expandedFilterGroup: FilterGroup? = null
    private val filterDurationOptions = listOf("默认", "5 分钟+", "10 分钟+", "30 分钟+", "1 小时+")
    private val filterPlayOptions = listOf("默认", "正序", "倒序")
    private val filterPublishOptions = listOf("默认", "最新")
    private var lastSuggestionKeyword = ""
    private var cachedSuggestions: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureTransitions()
        configureBars()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(dp(6), dp(10), dp(6), 0)
            // 不要让 root 抢焦点：否则从首页点搜索栏进来，输入框不会自动进入编辑态。
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        root.addView(createToolbar(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(SEARCH_BOX_HEIGHT_DP)))
        filterBar = createInlineFilterBar().apply { visibility = View.GONE }
        root.addView(filterBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply {
            topMargin = dp(8)
        })
        filterDropdown = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            alpha = 0f
            background = rounded(COLOR_NOTICE, dp(14), COLOR_BORDER, 1)
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        contentHost = FrameLayout(this).apply {
            alpha = 0f
            translationY = dp(8).toFloat()
        }
        val contentColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historySection = createHistorySection()
        contentColumn.addView(
            historySection,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(22)
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        )
        resultsHost = FrameLayout(this).apply {
            setPadding(dp(2), dp(18), dp(2), dp(36))
        }
        resultAdapter = SearchVideoAdapter()
        results = GridView(this).apply {
            numColumns = 2
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(4)
            verticalSpacing = dp(6)
            overScrollMode = View.OVER_SCROLL_NEVER
            setVerticalScrollBarEnabled(false)
            setHorizontalScrollBarEnabled(false)
            setSelector(android.R.color.transparent)
            clipToPadding = false
            adapter = resultAdapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

                override fun onScroll(view: AbsListView?, firstVisible: Int, visibleCount: Int, totalCount: Int) {
                    if (showingResultPage && totalCount > 0 &&
                        firstVisible + visibleCount >= totalCount - SEARCH_LOAD_AHEAD_ITEMS
                    ) loadNextSearchPage()
                }
            })
        }
        state = TextView(this).apply {
            text = "输入关键词，搜索公开视频"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(18), dp(30), dp(18), dp(24))
        }
        resultsHost.addView(results, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        resultsHost.addView(state, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
        })
        pagingSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_ROSE)
            visibility = View.GONE
            contentDescription = "正在加载更多搜索结果"
        }
        resultsHost.addView(pagingSpinner, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(18)
        })
        contentColumn.addView(resultsHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        contentHost.addView(contentColumn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // The option row is an overlay above cards. Showing it never changes
        // contentHost height, GridView viewport or current scroll position.
        filterDropdown.elevation = dp(10).toFloat()
        contentHost.addView(filterDropdown, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply { topMargin = dp(4) })

        // Suggestions are an independent floating layer: they never consume
        // history layout height or move the history bubbles.
        suggestions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_SURFACE, dp(14), COLOR_BORDER, 1)
            visibility = View.GONE
            elevation = dp(8).toFloat()
            setPadding(0, dp(4), 0, dp(4))
        }
        contentHost.addView(suggestions, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(48)
            rightMargin = dp(60)
            topMargin = dp(5)
        })


        root.addView(contentHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showSearchHistory()
        contentHost.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(55L)
            .setDuration(SEARCH_SURFACE_FADE_MS)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
        // Focus immediately so the keyboard is ready when the user arrives.
        // Soft-input mode is ADJUST_RESIZE (manifest) so the IME does not
        // thrash the shared-element geometry the way ADJUST_PAN used to.
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        root.post {
            if (!entryFocusDispatched) requestInputFocus(showKeyboard = true)
        }
        // Safety net if the first post was swallowed by the shared-element pass.
        root.postDelayed({
            if (!entryFocusDispatched) requestInputFocus(showKeyboard = true)
        }, 180L)
    }

    override fun onDestroy() {
        pendingSuggestion?.let { mainHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    override fun onBackPressed() {
        navigateBack()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN &&
            ::suggestions.isInitialized && suggestions.visibility == View.VISIBLE
        ) {
            val inputBounds = Rect().also { input.getGlobalVisibleRect(it) }
            val suggestionBounds = Rect().also { suggestions.getGlobalVisibleRect(it) }
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            if (!inputBounds.contains(x, y) && !suggestionBounds.contains(x, y)) {
                hideSuggestions()
                input.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(input.windowToken, 0)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun createToolbar(): View {
        val bar = FrameLayout(this)
        input = EditText(this).apply {
            hint = "搜索视频"
            textSize = SEARCH_BOX_TEXT_SIZE_SP
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = true
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(SEARCH_BOX_HORIZONTAL_PADDING_DP), 0, dp(SEARCH_BOX_HORIZONTAL_PADDING_DP), 0)
            background = rounded(COLOR_SURFACE, dp(SEARCH_BOX_CORNER_DP), COLOR_BORDER, 1)
            transitionName = SEARCH_BOX_TRANSITION_NAME
            isFocusableInTouchMode = true
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    performSearch()
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(value: Editable?) {
                    if (suppressTextWatcher) return
                    onKeywordChanged(value?.toString().orEmpty())
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) restoreSuggestionsForCurrentText() else hideSuggestions()
            }
            setOnClickListener {
                requestInputFocus(showKeyboard = true)
                restoreSuggestionsForCurrentText()
            }
        }
        bar.addView(input, FrameLayout.LayoutParams(
            dp(SEARCH_BOX_WIDTH_DP),
            dp(SEARCH_BOX_HEIGHT_DP),
            Gravity.CENTER
        ))
        val back = CenteredBackIcon(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "返回"
            setOnClickListener { navigateBack() }
        }
        bar.addView(back, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.START or Gravity.CENTER_VERTICAL))
        val actionHost = FrameLayout(this)
        searchAction = TextView(this).apply {
            text = "搜索"
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ROSE)
            isClickable = true
            isFocusable = true
            contentDescription = "开始搜索"
            setOnClickListener { performSearch() }
        }
        loadingSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_ROSE)
            visibility = View.GONE
            contentDescription = "搜索加载中"
        }
        actionHost.addView(searchAction, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        actionHost.addView(loadingSpinner, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        bar.addView(actionHost, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
        return bar
    }

    private fun createHistorySection(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        val header = LinearLayout(this@SearchActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
        }
        header.addView(TextView(this@SearchActivity).apply {
            text = "搜索历史"
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_INK)
        }, LinearLayout.LayoutParams(0, dp(34), 1f))
        clearHistoryAction = TextView(this@SearchActivity).apply {
            text = "清空"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_ROSE)
            isClickable = true
            isFocusable = true
            contentDescription = "清空全部搜索历史"
            setOnClickListener { confirmClearHistory() }
        }
        header.addView(clearHistoryAction, LinearLayout.LayoutParams(dp(58), dp(34)))
        addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
        historyChips = HistoryChipLayout(this@SearchActivity).apply {
            setPadding(0, dp(5), 0, dp(4))
        }
        addView(historyChips, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun showSearchHistory() {
        // History belongs to the editable search page, not to an empty input only.
        if (showingResultPage) {
            historySection.visibility = View.GONE
            return
        }
        val values = SearchHistoryStore.load(this)
        historyChips.removeAllViews()
        if (values.isEmpty()) {
            historySection.visibility = View.GONE
            if (input.text.toString().trim().isBlank()) {
                state.apply {
                    text = "输入关键词，搜索公开视频"
                    visibility = View.VISIBLE
                    isClickable = false
                    setOnClickListener(null)
                }
            } else {
                state.visibility = View.GONE
            }
            return
        }
        values.forEach {
            historyChips.addView(
                historyChip(it),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        historySection.visibility = View.VISIBLE
        state.visibility = View.GONE
    }

    private fun historyChip(keyword: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
        background = rounded(COLOR_NOTICE, dp(15), COLOR_BORDER, 1)
        setPadding(dp(12), 0, dp(5), 0)
        contentDescription = "历史搜索：$keyword"
        val label = TextView(this@SearchActivity).apply {
            text = keyword
            textSize = 13f
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setGravity(Gravity.CENTER_VERTICAL)
        }
        addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)))
        val remove = TextView(this@SearchActivity).apply {
            text = "×"
            textSize = 18f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(COLOR_MUTED)
            isClickable = true
            isFocusable = true
            contentDescription = "删除历史：$keyword"
            setOnClickListener {
                SearchHistoryStore.remove(this@SearchActivity, keyword)
                showSearchHistory()
            }
        }
        addView(remove, LinearLayout.LayoutParams(dp(28), dp(32)).apply { leftMargin = dp(2) })
        setOnClickListener {
            suppressTextWatcher = true
            input.setText(keyword)
            input.setSelection(input.text.length)
            suppressTextWatcher = false
            pendingSuggestion?.let { mainHandler.removeCallbacks(it) }
            BiliApi.cancelSuggestions()
            performSearch()
        }
    }

    private fun confirmClearHistory() {
        android.app.AlertDialog.Builder(this)
            .setTitle("清空搜索历史？")
            .setMessage("这只会删除本机保存的搜索词，无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                SearchHistoryStore.clear(this)
                showSearchHistory()
            }
            .show()
    }

    private fun onKeywordChanged(raw: String) {
        pendingSuggestion?.let { mainHandler.removeCallbacks(it) }
        if (searchInProgress) {
            searchGeneration += 1
            searchInProgress = false
            hideLoading()
        }
        if (showingResultPage) {
            showingResultPage = false
            collapseFilterDropdown()
            filterBar.animate().cancel()
            filterBar.visibility = View.GONE
            resultAdapter.replace(emptyList())
            state.apply {
                text = "输入关键词，搜索公开视频"
                visibility = View.VISIBLE
                isClickable = false
                setOnClickListener(null)
            }
        }
        val keyword = raw.trim()
        if (keyword.isBlank()) {
            hideSuggestions()
            showSearchHistory()
            return
        }
        showSearchHistory()
        requestSuggestions(keyword)
    }

    private fun requestSuggestions(keyword: String, immediate: Boolean = false) {
        if (keyword.isBlank() || !input.hasFocus()) {
            hideSuggestions()
            return
        }
        pendingSuggestion?.let { mainHandler.removeCallbacks(it) }
        if (keyword == lastSuggestionKeyword && cachedSuggestions.isNotEmpty()) {
            showSuggestions(cachedSuggestions)
            return
        }
        val request = Runnable {
            BiliApi.suggest(keyword) callback@{ values, _ ->
                if (showingResultPage || searchInProgress || !input.hasFocus() || input.text.toString().trim() != keyword) return@callback
                lastSuggestionKeyword = keyword
                cachedSuggestions = values.orEmpty()
                showSuggestions(cachedSuggestions)
            }
        }
        pendingSuggestion = request
        mainHandler.postDelayed(request, if (immediate) 0L else SUGGEST_DEBOUNCE_MS)
    }

    private fun restoreSuggestionsForCurrentText() {
        if (showingResultPage || searchInProgress) return
        val keyword = input.text.toString().trim()
        if (keyword.isBlank()) {
            hideSuggestions()
        } else {
            requestSuggestions(keyword, immediate = true)
        }
    }

    private fun showSuggestions(values: List<String>) {
        suggestions.removeAllViews()
        if (values.isEmpty()) {
            suggestions.visibility = View.GONE
            return
        }
        values.take(MAX_SUGGESTIONS).forEachIndexed { index, value ->
            suggestions.addView(
                suggestionRow(value),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
            )
            if (index < minOf(values.size, MAX_SUGGESTIONS) - 1) {
                suggestions.addView(
                    View(this).apply { setBackgroundColor(COLOR_BORDER) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                        leftMargin = dp(15)
                        rightMargin = dp(15)
                    }
                )
            }
        }
        suggestions.bringToFront()
        if (suggestions.visibility != View.VISIBLE) {
            suggestions.alpha = 0f
            suggestions.translationY = -dp(6).toFloat()
            suggestions.visibility = View.VISIBLE
            suggestions.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(SUGGESTION_EXPAND_DURATION_MS)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()
        }
    }

    private fun suggestionRow(keyword: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
        isClickable = true
        contentDescription = "搜索建议：$keyword"
        setPadding(dp(14), 0, dp(12), 0)
        addView(TextView(this@SearchActivity).apply {
            text = "⌕"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.MATCH_PARENT))
        addView(TextView(this@SearchActivity).apply {
            text = keyword
            textSize = 14f
            setTextColor(COLOR_INK)
            setGravity(Gravity.CENTER_VERTICAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(6) })
        setOnClickListener {
            suppressTextWatcher = true
            input.setText(keyword)
            input.setSelection(input.text.length)
            suppressTextWatcher = false
            BiliApi.cancelSuggestions()
            performSearch()
        }
    }

    private fun hideSuggestions(animated: Boolean = true) {
        suggestions.animate().cancel()
        if (!animated || suggestions.visibility != View.VISIBLE) {
            suggestions.removeAllViews()
            suggestions.alpha = 1f
            suggestions.translationY = 0f
            suggestions.visibility = View.GONE
            return
        }
        suggestions.animate()
            .alpha(0f)
            .translationY(-dp(4).toFloat())
            .setDuration(SUGGESTION_COLLAPSE_DURATION_MS)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                if (suggestions.alpha == 0f) {
                    suggestions.removeAllViews()
                    suggestions.alpha = 1f
                    suggestions.translationY = 0f
                    suggestions.visibility = View.GONE
                }
            }
            .start()
    }

    private fun performSearch() {
        pulseSearchAction()
        val keyword = input.text.toString().trim()
        if (keyword.isBlank()) {
            state.apply {
                text = "先输入想找的视频关键词"
                visibility = View.VISIBLE
            }
            hideSuggestions()
            return
        }
        SearchHistoryStore.record(this, keyword)
        ++searchGeneration
        currentSearchKeyword = keyword
        searchPage = 0
        searchEndReached = false
        showingResultPage = true
        historySection.visibility = View.GONE
        searchInProgress = true
        loadingNextPage = false
        pendingSuggestion?.let { mainHandler.removeCallbacks(it) }
        hideSuggestions()
        hideKeyboard()
        resultAdapter.replace(emptyList())
        state.visibility = View.GONE
        if (filterBar.visibility != View.VISIBLE) {
            filterBar.visibility = View.VISIBLE
            filterBar.alpha = 0f
            filterBar.translationY = -dp(5).toFloat()
            filterBar.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        }
        mainHandler.postDelayed({
            if (searchInProgress) showLoading()
        }, SEARCH_BUTTON_PULSE_MS)
        requestSearchPage(page = 1, initial = true)
    }

    private fun loadNextSearchPage() {
        if (!showingResultPage || searchInProgress || loadingNextPage || searchEndReached || currentSearchKeyword.isBlank()) return
        requestSearchPage(page = searchPage + 1, initial = false)
    }

    private fun requestSearchPage(page: Int, initial: Boolean) {
        val keyword = currentSearchKeyword
        val requestGeneration = searchGeneration
        if (!initial) {
            loadingNextPage = true
            pagingSpinner.visibility = View.VISIBLE
        }
        BiliApi.search(keyword, page, searchOrderParam(), searchDurationParam()) callback@{ videos, error ->
            if (requestGeneration != searchGeneration || !showingResultPage || currentSearchKeyword != keyword) return@callback
            if (initial) {
                searchInProgress = false
                hideLoading()
            } else {
                loadingNextPage = false
                pagingSpinner.visibility = View.GONE
            }
            if (videos == null) {
                if (initial) {
                    state.apply {
                        text = "搜索暂不可用：$error\n\n点这里在官方搜索页继续"
                        visibility = View.VISIBLE
                        isClickable = true
                        setOnClickListener {
                            startActivity(
                                Intent(this@SearchActivity, PlayerActivity::class.java).putExtra(
                                    PlayerActivity.EXTRA_URL,
                                    "https://search.bilibili.com/all?keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}" 
                                )
                            )
                        }
                    }
                }
                return@callback
            }
            if (videos.isEmpty()) {
                searchEndReached = true
                if (initial) {
                    state.apply {
                        text = "没有找到相关公开视频"
                        visibility = View.VISIBLE
                        isClickable = false
                        setOnClickListener(null)
                    }
                }
                return@callback
            }

            val known = resultAdapter.bvids()
            val additions = applyClientFilters(videos.filter { it.bvid.isNotBlank() && it.bvid !in known })
            searchPage = page
            if (initial && additions.isEmpty() && filterDuration != 0 && page < SEARCH_FILTER_SCAN_PAGES) {
                // Exact minimum-duration filtering is client-side; scan a few
                // server pages so sparse filters do not leave an empty screen.
                requestSearchPage(page + 1, initial = true)
                return@callback
            }
            state.visibility = View.GONE
            if (initial) {
                resultAdapter.replace(additions)
            } else if (additions.isNotEmpty()) {
                val firstVisible = results.firstVisiblePosition
                val firstTop = results.getChildAt(0)?.top ?: results.paddingTop
                val removed = resultAdapter.appendWindowed(additions)
                if (removed > 0) results.setSelectionFromTop((firstVisible - removed).coerceAtLeast(0), firstTop)
            }
            // A non-empty page consumed the cursor even if its items were duplicates.
            // Continue from the next page on the following bottom-reach event.
        }
    }

    // ── Inline filter bar ───────────────────────────────────────

    private enum class FilterGroup { DURATION, PLAY, PUBLISH }

    private fun createInlineFilterBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
        }
        durationFilter = createFilterControl("时长") { toggleFilterDropdown(FilterGroup.DURATION) }
        playFilter = createFilterControl("播放量") { toggleFilterDropdown(FilterGroup.PLAY) }
        publishFilter = createFilterControl("发布时间") { toggleFilterDropdown(FilterGroup.PUBLISH) }
        bar.addView(durationFilter)
        bar.addView(filterDivider())
        bar.addView(playFilter)
        bar.addView(filterDivider())
        bar.addView(publishFilter)
        bar.addView(filterDivider())
        bar.addView(TextView(this).apply {
            text = "重置"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(14), 0, 0, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "重置搜索筛选"
            setOnClickListener { resetFilters() }
        })
        updateFilterControls()
        return bar
    }

    /** 筛选文字之间的竖直分隔线。 */
    private fun filterDivider(): View = View(this).apply {
        setBackgroundColor(COLOR_BORDER)
        layoutParams = LinearLayout.LayoutParams(1, dp(14))
    }

    private fun createFilterControl(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = "$label ⌄"
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(COLOR_INK)
        setPadding(dp(14), 0, dp(14), 0)
        isClickable = true
        isFocusable = true
        contentDescription = "$label 筛选"
        setOnClickListener { onClick() }
    }

    private fun toggleFilterDropdown(group: FilterGroup) {
        if (expandedFilterGroup == group && filterDropdown.visibility == View.VISIBLE) {
            collapseFilterDropdown()
            return
        }
        expandedFilterGroup = group
        populateFilterDropdown(group)
        if (filterDropdown.visibility == View.VISIBLE) {
            filterDropdown.animate().cancel()
            filterDropdown.alpha = 0f
            filterDropdown.translationY = -dp(3).toFloat()
            filterDropdown.animate().alpha(1f).translationY(0f).setDuration(140L).start()
        } else {
            expandFilterDropdown()
        }
    }

    private fun populateFilterDropdown(group: FilterGroup) {
        filterDropdown.removeAllViews()
        val options = when (group) {
            FilterGroup.DURATION -> filterDurationOptions
            FilterGroup.PLAY -> filterPlayOptions
            FilterGroup.PUBLISH -> filterPublishOptions
        }
        val selected = when (group) {
            FilterGroup.DURATION -> filterDuration
            FilterGroup.PLAY -> filterPlayOrder
            FilterGroup.PUBLISH -> filterPublishOrder
        }
        options.forEachIndexed { index, label ->
            filterDropdown.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (index == selected) Color.WHITE else COLOR_INK)
                background = if (index == selected) rounded(COLOR_ROSE, dp(10)) else null
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
                isFocusable = true
                contentDescription = "${filterGroupLabel(group)}：$label"
                setOnClickListener { selectFilter(group, index) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        }
    }

    private fun expandFilterDropdown() {
        filterDropdown.animate().cancel()
        filterDropdown.visibility = View.VISIBLE
        filterDropdown.alpha = 0f
        filterDropdown.translationY = -dp(7).toFloat()
        filterDropdown.scaleY = 0.86f
        filterDropdown.pivotY = 0f
        filterDropdown.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleY(1f)
            .setDuration(180L)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    private fun collapseFilterDropdown() {
        if (filterDropdown.visibility != View.VISIBLE) {
            expandedFilterGroup = null
            return
        }
        filterDropdown.animate().cancel()
        filterDropdown.animate()
            .alpha(0f)
            .translationY(-dp(5).toFloat())
            .scaleY(0.9f)
            .setDuration(120L)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                filterDropdown.visibility = View.GONE
                filterDropdown.alpha = 1f
                filterDropdown.translationY = 0f
                filterDropdown.scaleY = 1f
                expandedFilterGroup = null
            }
            .start()
    }

    private fun selectFilter(group: FilterGroup, index: Int) {
        val changed = when (group) {
            FilterGroup.DURATION -> filterDuration != index
            FilterGroup.PLAY -> filterPlayOrder != index || (index != 0 && filterPublishOrder != 0)
            FilterGroup.PUBLISH -> filterPublishOrder != index || (index != 0 && filterPlayOrder != 0)
        }
        when (group) {
            FilterGroup.DURATION -> filterDuration = index
            FilterGroup.PLAY -> {
                filterPlayOrder = index
                if (index != 0) filterPublishOrder = 0
            }
            FilterGroup.PUBLISH -> {
                filterPublishOrder = index
                if (index != 0) filterPlayOrder = 0
            }
        }
        updateFilterControls()
        populateFilterDropdown(group)
        mainHandler.postDelayed({ collapseFilterDropdown() }, 90L)
        if (changed && showingResultPage && currentSearchKeyword.isNotBlank()) {
            mainHandler.postDelayed({ performSearch() }, 130L)
        }
    }

    private fun resetFilters() {
        val changed = filterDuration != 0 || filterPlayOrder != 0 || filterPublishOrder != 0
        filterDuration = 0
        filterPlayOrder = 0
        filterPublishOrder = 0
        updateFilterControls()
        collapseFilterDropdown()
        if (changed && showingResultPage && currentSearchKeyword.isNotBlank()) performSearch()
    }

    private fun updateFilterControls() {
        if (!::durationFilter.isInitialized) return
        styleFilterControl(durationFilter, "时长", filterDurationOptions[filterDuration], filterDuration != 0)
        styleFilterControl(playFilter, "播放量", filterPlayOptions[filterPlayOrder], filterPlayOrder != 0)
        styleFilterControl(publishFilter, "发布时间", filterPublishOptions[filterPublishOrder], filterPublishOrder != 0)
    }

    private fun styleFilterControl(view: TextView, label: String, value: String, active: Boolean) {
        view.text = if (active) "$value ⌄" else "$label ⌄"
        view.setTextColor(if (active) COLOR_ROSE else COLOR_INK)
        view.typeface = Typeface.create(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
        view.background = null
    }

    private fun filterGroupLabel(group: FilterGroup): String = when (group) {
        FilterGroup.DURATION -> "时长"
        FilterGroup.PLAY -> "播放量"
        FilterGroup.PUBLISH -> "发布时间"
    }

    private fun searchOrderParam(): String = when {
        filterPublishOrder == 1 -> "pubdate"
        filterPlayOrder != 0 -> "click"
        else -> ""
    }

    // Bilibili's duration parameter uses disjoint ranges and cannot represent
    // 5+/10+/30+ accurately. Keep the server query broad and apply exact minimums locally.
    private fun searchDurationParam(): Int = 0

    private fun applyClientFilters(items: List<VideoItem>): List<VideoItem> {
        val minimumSeconds = when (filterDuration) {
            1 -> 5 * 60
            2 -> 10 * 60
            3 -> 30 * 60
            4 -> 60 * 60
            else -> 0
        }
        val filtered = if (minimumSeconds > 0) items.filter { it.duration >= minimumSeconds } else items
        return when {
            filterPublishOrder == 1 -> filtered.sortedByDescending { it.publishedAt }
            filterPlayOrder == 1 -> filtered.sortedBy { it.views }
            filterPlayOrder == 2 -> filtered.sortedByDescending { it.views }
            else -> filtered
        }
    }

    private fun pulseSearchAction() {
        if (!::searchAction.isInitialized) return
        searchAction.animate().cancel()
        searchAction.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(70L)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .withEndAction {
                searchAction.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150L)
                    .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                    .start()
            }
            .start()
    }

    private fun showLoading() {
        searchAction.visibility = View.INVISIBLE
        loadingSpinner.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        if (!::loadingSpinner.isInitialized) return
        loadingSpinner.visibility = View.GONE
        searchAction.visibility = View.VISIBLE
    }

    private fun navigateBack() {
        if (showingResultPage) {
            resetToSearchEntry()
        } else {
            collapseToHome()
        }
    }

    private fun resetToSearchEntry() {
        searchGeneration += 1
        searchInProgress = false
        loadingNextPage = false
        searchEndReached = false
        currentSearchKeyword = ""
        searchPage = 0
        pagingSpinner.visibility = View.GONE
        showingResultPage = false
        collapseFilterDropdown()
        filterBar.animate().cancel()
        filterBar.visibility = View.GONE
        hideLoading()
        hideSuggestions()
        resultAdapter.replace(emptyList())
        state.apply {
            text = "修改关键词后再次搜索"
            visibility = View.VISIBLE
            isClickable = false
            setOnClickListener(null)
        }
        input.requestFocus()
        input.setSelection(input.text.length)
        showSearchHistory()
        requestSuggestions(input.text.toString().trim())
    }

    private fun collapseToHome() {
        hideKeyboard()
        finishAfterTransition()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    fun requestInputFocus(showKeyboard: Boolean) {
        if (!::input.isInitialized || isFinishing) return
        entryFocusDispatched = true
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.requestFocus()
        try { input.setSelection(input.text.length) } catch (_: Exception) {}
        if (showKeyboard) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            // SHOW_FORCED 在从首页点进搜索时更稳，避免共享元素过渡吞掉键盘。
            imm?.showSoftInput(input, InputMethodManager.SHOW_FORCED)
            input.post {
                if (!isFinishing) {
                    input.requestFocus()
                    imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            input.postDelayed({
                if (!isFinishing) {
                    if (!input.hasFocus()) input.requestFocus()
                    imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 220L)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !showingResultPage) {
            // 窗口真正拿到焦点后再补一次，覆盖过渡动画后的焦点丢失。
            requestInputFocus(showKeyboard = true)
        }
    }

    private fun configureTransitions() {
        val enter = ChangeBounds().apply {
            duration = SEARCH_TRANSITION_DURATION_MS
            interpolator = PathInterpolator(0.16f, 0.84f, 0.24f, 1f)
            addListener(object : Transition.TransitionListener {
                override fun onTransitionStart(transition: Transition?) = Unit
                override fun onTransitionCancel(transition: Transition?) = Unit
                override fun onTransitionPause(transition: Transition?) = Unit
                override fun onTransitionResume(transition: Transition?) = Unit
                override fun onTransitionEnd(transition: Transition?) {
                    removeListener(this)
                    if (!entryFocusDispatched) requestInputFocus(showKeyboard = true)
                }
            })
        }
        val exit = ChangeBounds().apply {
            duration = SEARCH_TRANSITION_DURATION_MS
            interpolator = PathInterpolator(0.16f, 0.84f, 0.24f, 1f)
        }
        window.sharedElementEnterTransition = enter
        window.sharedElementReturnTransition = exit
        // Surrounding controls fade in a little later so they do not visually
        // compete with the now-stationary shared search field.
        window.enterTransition = Fade().apply {
            startDelay = 60L
            duration = SEARCH_SURFACE_FADE_MS
        }
        window.returnTransition = Fade().apply { duration = SEARCH_SURFACE_FADE_MS }
    }

    /** Pixel-centered arrow avoids the font-baseline drift shown in the screenshot. */
    private inner class CenteredBackIcon(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_INK
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            val arm = dp(7).toFloat()
            canvas.drawLine(centerX + dp(4), centerY - arm, centerX - dp(4), centerY, paint)
            canvas.drawLine(centerX - dp(4), centerY, centerX + dp(4), centerY + arm, paint)
        }
    }

    /** Lightweight wrapping layout for local-history bubbles. */
    private inner class HistoryChipLayout(context: Context) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            var cursorX = 0
            var cursorY = 0
            var rowHeight = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == View.GONE) continue
                child.measure(
                    MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                if (cursorX > 0 && cursorX + child.measuredWidth > availableWidth) {
                    cursorX = 0
                    cursorY += rowHeight + dp(HISTORY_CHIP_GAP)
                    rowHeight = 0
                }
                cursorX += child.measuredWidth + dp(HISTORY_CHIP_GAP)
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
            val contentHeight = if (childCount == 0) 0 else cursorY + rowHeight
            setMeasuredDimension(
                resolveSize(availableWidth + paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec)
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val availableWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
            var cursorX = paddingLeft
            var cursorY = paddingTop
            var rowHeight = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == View.GONE) continue
                if (cursorX > paddingLeft && cursorX + child.measuredWidth > paddingLeft + availableWidth) {
                    cursorX = paddingLeft
                    cursorY += rowHeight + dp(HISTORY_CHIP_GAP)
                    rowHeight = 0
                }
                child.layout(cursorX, cursorY, cursorX + child.measuredWidth, cursorY + child.measuredHeight)
                cursorX += child.measuredWidth + dp(HISTORY_CHIP_GAP)
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
        }
    }

    /** Recycled cards intentionally match the home feed's two-column visual grammar. */
    private inner class SearchVideoAdapter : BaseAdapter() {
        private val videos = ArrayList<VideoItem>()
        private val cardWidth = (resources.displayMetrics.widthPixels - dp(20)) / 2
        private val coverHeight = (cardWidth - dp(8)) * 9 / 16
        private val cardHeight = coverHeight + dp(90)

        fun replace(items: List<VideoItem>) {
            videos.clear()
            videos.addAll(items)
            notifyDataSetChanged()
            results.post { results.setSelection(0) }
        }

        fun bvids(): Set<String> = videos.mapTo(LinkedHashSet()) { it.bvid }

        fun appendWindowed(items: List<VideoItem>): Int {
            if (items.isEmpty()) return 0
            val removeCount = FeedPagingPolicy.trimFromStart(videos.size, items.size)
            if (removeCount > 0) videos.subList(0, removeCount).clear()
            videos.addAll(items)
            notifyDataSetChanged()
            return removeCount
        }

        override fun getCount(): Int = videos.size
        override fun getItem(position: Int): VideoItem = videos[position]
        override fun getItemId(position: Int): Long = videos[position].bvid.hashCode().toLong()
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val card: LinearLayout
            val holder: SearchCardHolder
            if (convertView == null) {
                card = createCard()
                holder = card.tag as SearchCardHolder
            } else {
                card = convertView as LinearLayout
                holder = card.tag as SearchCardHolder
            }
            val video = videos[position]
            holder.bvid = video.bvid
            holder.aid = video.aid
            holder.title.text = video.title
            holder.owner.text = "UP: ${video.owner.ifBlank { "未知UP主" }}"
            holder.meta.text = "${formatPublishedDate(video.publishedAt)}  ·  ${formatViews(video.views)}播放"
            holder.duration.text = formatDuration(video.duration)
            CoverLoader.load(holder.cover, video.cover)
            card.contentDescription = "$SEARCH_CARD_ROW：${video.title}，${formatPublishedDate(video.publishedAt)}，${formatViews(video.views)}播放"
            return card
        }

        private fun createCard(): LinearLayout {
            val card = LinearLayout(this@SearchActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(COLOR_CARD, dp(15), COLOR_CARD_BORDER, 1)
                setPadding(dp(4), dp(4), dp(4), dp(6))
                layoutParams = AbsListView.LayoutParams(cardWidth, cardHeight)
                isClickable = true
            }
            val coverFrame = FrameLayout(this@SearchActivity).apply {
                background = rounded(COLOR_COVER, dp(12))
                clipToOutline = true
            }
            val cover = ImageView(this@SearchActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(COLOR_COVER)
            }
            coverFrame.addView(cover, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            val duration = TextView(this@SearchActivity).apply {
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
            card.addView(coverFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, coverHeight))
            val title = TextView(this@SearchActivity).apply {
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(COLOR_INK)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(7), dp(6), dp(7), 0)
            }
            card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
            val owner = TextView(this@SearchActivity).apply {
                textSize = 11f
                setTextColor(COLOR_MUTED)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(7), dp(1), dp(7), 0)
            }
            card.addView(owner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(17)))
            val meta = TextView(this@SearchActivity).apply {
                textSize = 11f
                setTextColor(COLOR_MUTED)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(7), 0, dp(7), 0)
            }
            card.addView(meta, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)))
            card.setOnClickListener {
                val item = card.tag as? SearchCardHolder ?: return@setOnClickListener
                VideoDetailActivity.open(this@SearchActivity, item.bvid, item.aid)
            }
            card.tag = SearchCardHolder(cover, duration, title, owner, meta, "", 0L)
            return card
        }
    }

    private data class SearchCardHolder(
        val cover: ImageView,
        val duration: TextView,
        val title: TextView,
        val owner: TextView,
        val meta: TextView,
        var bvid: String,
        var aid: Long
    )

    private fun configureBars() {
        Theme.init(this)
        Theme.applySystemBars(this)
    }

    private companion object {
        const val SUGGEST_DEBOUNCE_MS = 260L
        const val MAX_SUGGESTIONS = 8
        const val SEARCH_LOAD_AHEAD_ITEMS = 4
        const val SEARCH_FILTER_SCAN_PAGES = 4
        const val SEARCH_CARD_ROW = "search_card_row"
        // The destination is deliberately 16dp wider than the home source.
        // Keeping every other visual parameter identical gives ChangeBounds a
        // visible, controlled morph without the old height/baseline jump.
        const val SEARCH_BOX_WIDTH_DP = 240
        const val SEARCH_BOX_HEIGHT_DP = 44
        const val SEARCH_BOX_CORNER_DP = 18
        const val SEARCH_BOX_HORIZONTAL_PADDING_DP = 15
        const val SEARCH_BOX_TEXT_SIZE_SP = 15f
        const val SEARCH_BOX_TRANSITION_NAME = "yui_search_box"
        const val SEARCH_TRANSITION_DURATION_MS = 220L
        const val SEARCH_SURFACE_FADE_MS = 160L
        const val SEARCH_BUTTON_PULSE_MS = 90L
        const val SUGGESTION_EXPAND_DURATION_MS = 170L
        const val SUGGESTION_COLLAPSE_DURATION_MS = 120L
        const val HISTORY_CHIP_GAP = 8
    }
}
