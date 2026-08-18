package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * 收藏搜索页：跨全部收藏夹按标题关键词搜索，列表展示。
 * 动画与历史搜索页同款：右滑入/回退、结果行淡入上浮。
 */
class FavoriteSearchActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var stateView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var searchGeneration = 0
    private var loading = false
    private var currentKeyword = ""
    private val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Theme.applySystemBars(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(12), dp(8))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(COLOR_INK)
            setPadding(dp(10), 0, dp(6), 0)
            isClickable = true
            setOnClickListener { finishWithAnim() }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(42), dp(42)))

        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(COLOR_SOFT_FILL, dp(18))
            setPadding(dp(12), dp(8), dp(10), dp(8))
        }
        field.addView(SearchGlyph(this), LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            rightMargin = dp(8)
        })
        input = EditText(this).apply {
            hint = "搜索收藏夹内视频"
            textSize = 15f
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, event ->
                val go = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN)
                if (go) {
                    hideKeyboard()
                    runSearch(input.text?.toString().orEmpty(), force = true)
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    scheduleSearch(s?.toString().orEmpty())
                }
            })
        }
        field.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val clear = TextView(this).apply {
            text = "×"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(8), 0, dp(2), 0)
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                input.setText("")
                input.requestFocus()
                showIdle()
            }
        }
        field.addView(clear, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })
        bar.addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(COLOR_BORDER) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        val content = FrameLayout(this)
        stateView = TextView(this).apply {
            text = "输入关键词搜索全部收藏"
            textSize = 13f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(80), dp(24), 0)
        }
        content.addView(stateView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
        }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(28))
        }
        scroll.addView(listBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        content.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        input.post {
            input.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun scheduleSearch(raw: String) {
        pendingSearch?.let { mainHandler.removeCallbacks(it) }
        val q = raw.trim()
        if (q.isEmpty()) {
            showIdle()
            return
        }
        val task = Runnable { runSearch(q, force = false) }
        pendingSearch = task
        mainHandler.postDelayed(task, 360L)
    }

    private fun runSearch(raw: String, force: Boolean) {
        val q = raw.trim()
        if (q.isEmpty()) {
            showIdle()
            return
        }
        if (!force && q == currentKeyword && listBox.childCount > 0) return
        if (!BiliSessionStore.isLoggedIn()) {
            showState("请先登录后再搜索收藏")
            return
        }
        currentKeyword = q
        listBox.removeAllViews()
        scroll.visibility = View.VISIBLE
        stateView.visibility = View.GONE
        showState("正在搜索…")
        loading = true
        val gen = ++searchGeneration
        BiliApi.searchAllFavoriteVideos(q) { videos, error ->
            if (isFinishing || isDestroyed || gen != searchGeneration) return@searchAllFavoriteVideos
            loading = false
            if (error != null) {
                showState(error)
                return@searchAllFavoriteVideos
            }
            if (videos.isEmpty()) {
                showState("没有找到相关收藏")
                return@searchAllFavoriteVideos
            }
            stateView.visibility = View.GONE
            scroll.visibility = View.VISIBLE
            listBox.removeAllViews()
            videos.forEachIndexed { index, video ->
                val row = buildRow(video)
                listBox.addView(row)
                row.alpha = 0f
                row.translationY = dp(12).toFloat()
                row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((index.coerceAtMost(12) * 28L))
                    .setDuration(260L)
                    .setInterpolator(easeOut)
                    .start()
            }
        }
    }

    private fun buildRow(video: VideoItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            setOnClickListener {
                startActivity(
                    Intent(this@FavoriteSearchActivity, VideoDetailActivity::class.java)
                        .putExtra(VideoDetailActivity.EXTRA_BVID, video.bvid)
                )
            }
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(COLOR_COVER, dp(8), COLOR_CARD_BORDER, 1)
            clipToOutline = true
        }
        if (video.cover.isNotBlank()) CoverLoader.load(thumb, video.cover + "@320w_180h.webp")
        row.addView(thumb, LinearLayout.LayoutParams(dp(112), dp(63)))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        body.addView(TextView(this).apply {
            text = video.title
            textSize = 14f
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val date = if (video.publishedAt > 0L) {
            android.text.format.DateFormat.format(
                "yyyy-MM-dd", java.util.Date(video.publishedAt * 1000L)
            ).toString()
        } else ""
        body.addView(TextView(this).apply {
            text = if (date.isBlank()) video.owner else "${video.owner} · $date"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(5), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun showIdle() {
        currentKeyword = ""
        loading = false
        listBox.removeAllViews()
        scroll.visibility = View.GONE
        stateView.visibility = View.VISIBLE
        stateView.text = "输入关键词搜索全部收藏"
    }

    private fun showState(msg: String) {
        scroll.visibility = View.GONE
        stateView.visibility = View.VISIBLE
        stateView.text = msg
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun finishWithAnim() {
        hideKeyboard()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left_soft, R.anim.slide_out_right)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithAnim()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private class SearchGlyph(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED
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
}
