package com.yuilittle.bili

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max

/** Extra flag: when set, MainActivity switches to the account ("我的") page. */
const val EXTRA_SWITCH_TO_ACCOUNT = "main_switch_to_account"

/**
 * The shell for YuiBili's three root destinations.
 * Content is deliberately empty for the current shell; this class owns only
 * root navigation, swipe handling, and lightweight continuous transitions.
 */
class MainActivity : Activity() {
    private lateinit var pageHost: FrameLayout
    private lateinit var bottomNavigation: FrostedBottomNavigation
    private val pages = arrayOfNulls<View>(3)
    private var currentPage = HOME
    private var refreshOnEntry = true
    private var reducedMotion = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reducedMotion = MotionTokens.isReduced(this)
        Theme.init(this)
        // Paint the window with the current theme colour BEFORE any content so
        // recreate() after night-mode toggle never flashes the light styles.xml
        // windowBackground (#FFFCFA) for one frame.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(COLOR_BACKGROUND))
        // Suppress the default activity cross-fade that fights the circular reveal.
        if (Theme.peekCircularTransition()) {
            overridePendingTransition(0, 0)
        }
        BiliSessionStore.init(this)
        // A new task/activity entry refreshes the feeds; state restoration (such as
        // rotation or process recreation with saved state) keeps the visible feed.
        refreshOnEntry = savedInstanceState == null
        currentPage = savedInstanceState?.getInt(STATE_CURRENT_PAGE, HOME)
            ?.coerceIn(HOME, PROFILE)
            ?: HOME
        configureSystemBars()

        val root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
        }

        // Root pages are selected by tapping, or by holding and scrubbing the dock.
        // The content area keeps its own horizontal gestures for 推荐 / 热门.
        pageHost = FrameLayout(this)
        root.addView(
            pageHost,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        for (index in pages.indices) {
            val page = createRootPage(index).apply {
                contentDescription = pageDescription(index)
                visibility = if (index == currentPage) View.VISIBLE else View.INVISIBLE
            }
            pages[index] = page
            pageHost.addView(
                page,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        bottomNavigation = FrostedBottomNavigation(this).apply {
            setOnDestinationSelectedListener { destination -> showPage(destination) }
        }
        // A compact floating dock: generous touch targets without a heavy long rail.
        val navigationWidth = minOf(
            dp(240),
            resources.displayMetrics.widthPixels - dp(40)
        )
        root.addView(
            bottomNavigation,
            FrameLayout.LayoutParams(
                navigationWidth,
                dp(78),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(16)
            }
        )

        setContentView(root)
        playPendingThemeTransition(root)
        requestNotificationPermissionIfNeeded()
        showDisclaimerIfNeeded()
        // Each open: one silent live check against remote JSON. No sticky disk cache —
        // tip only appears when the *current* remote is newer than this install.
        // Skipped while the first-run disclaimer is still pending.
        root.postDelayed({
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)) {
                UpdateChecker.checkOnLaunch(this@MainActivity)
            }
        }, 1200L)
        bottomNavigation.setSelectedIndex(currentPage)
        (pages[HOME] as? HomeFeedView)?.let { home ->
            savedInstanceState?.let { state -> home.restoreState(state) }
        }
        root.post {
            bottomNavigation.alpha = if (reducedMotion) 1f else 0f
            bottomNavigation.translationY = if (reducedMotion) 0f else dp(18).toFloat()
            bottomNavigation.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(if (reducedMotion) MotionTokens.smallMs else MotionTokens.panelMs)
                .setInterpolator(MotionTokens.easeOut)
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        // Soft: keep the profile card stable when returning from history/favorites.
        (pages[PROFILE] as? AccountPageView)?.refresh(soft = true)
        // Returning from background / launcher also re-checks remote JSON so a
        // just-pushed update.json is picked up without killing the process.
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)) {
            UpdateChecker.checkOnForeground(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SWITCH_TO_ACCOUNT, false)) {
            showPage(PROFILE)
            (pages[PROFILE] as? AccountPageView)?.refresh(soft = false)
        }
    }

    /** Android 13+ 需要运行时授予通知权限，后台下载进度通知依赖它。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4201)
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** First launch: show the disclaimer dialog; "取消" exits the app. */
    private fun showDisclaimerIfNeeded() {        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)) return

        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = rounded(COLOR_CARD, dp(18), COLOR_CARD_BORDER, dp(1))
        }
        content.addView(TextView(this).apply {
            text = "免责声明"
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(COLOR_INK)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(TextView(this).apply {
            text = "该应用用于持久化的所有数据均仅明文存储于用户本地!\n" +
                "因使用本项目而产生的任何后果均由用户个人承担,与开发者无关,概不负责。\n" +
                "\"哔哩哔哩\"及\"Bilibili\"名称、LOGO及相关图形是上海幻电信息科技有限公司的注册商标或商标。\n" +
                "本项目与哔哩哔哩及其关联公司无任何关联、合作、授权或背书等关系。"
            textSize = 13f
            setLineSpacing(dp(6).toFloat(), 1f)
            setTextColor(COLOR_INK)
            setPadding(0, dp(16), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val agreeBox = CheckBox(this).apply {
            text = "我已阅读免责声明，继续使用"
            textSize = 13f
            setTextColor(COLOR_INK)
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(agreeBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        val cancelButton = TextView(this).apply {
            text = "取消"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            background = rounded(COLOR_SURFACE, dp(13), COLOR_CARD_BORDER, dp(1))
            isClickable = true
        }
        val confirmButton = TextView(this).apply {
            text = "确定"
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(COLOR_ROSE, dp(13))
            isClickable = true
            isEnabled = false
            alpha = 0.4f
        }
        agreeBox.setOnCheckedChangeListener { _, checked ->
            confirmButton.isEnabled = checked
            confirmButton.alpha = if (checked) 1f else 0.4f
        }
        cancelButton.setOnClickListener {
            dialog.dismiss()
            finishAffinity()
        }
        confirmButton.setOnClickListener {
            prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
            dialog.dismiss()
        }
        buttonRow.addView(cancelButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        buttonRow.addView(confirmButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            leftMargin = dp(12)
        })
        content.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(content)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - dp(48),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_CURRENT_PAGE, currentPage)
        (pages[HOME] as? HomeFeedView)?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun createRootPage(index: Int): View = when (index) {
        HOME -> HomeFeedView(this, refreshOnEntry)
        DOWNLOADS -> DownloadPageView(this).apply {
            // 多选时暂时隐藏悬浮底部导航，避免挡住底部「全选/删除」操作栏
            onSelectionModeChanged = { on ->
                bottomNavigation.visibility = if (on) View.GONE else View.VISIBLE
            }
        }
        else -> AccountPageView(this)
    }

    /**
     * Select a root destination without imitating the 推荐 / 热门 horizontal pager.
     * A short cross-fade with a tiny vertical lift remains interruptible, which is
     * important when a held finger scrubs quickly across several destinations.
     */
    private fun showPage(destination: Int) {
        if (destination !in pages.indices) return
        // 切走下载页时确保底部导航恢复可见（下载页多选期间它可能被隐藏）
        bottomNavigation.visibility = View.VISIBLE
        if (destination == currentPage) {
            bottomNavigation.setSelectedIndex(destination)
            return
        }

        val previous = currentPage
        val outgoing = pages[previous] ?: return
        val incoming = pages[destination] ?: return

        for (index in pages.indices) {
            val page = pages[index] ?: continue
            page.animate().setListener(null)
            page.animate().cancel()
            if (index != previous && index != destination) {
                page.visibility = View.INVISIBLE
                page.alpha = 1f
                page.translationX = 0f
                page.translationY = 0f
                page.scaleX = 1f
                page.scaleY = 1f
            }
        }

        incoming.visibility = View.VISIBLE
        outgoing.visibility = View.VISIBLE
        incoming.alpha = if (reducedMotion) 1f else 0f
        incoming.translationY = if (reducedMotion) 0f else dp(8).toFloat()
        outgoing.alpha = 1f
        outgoing.translationY = 0f
        outgoing.translationX = 0f
        outgoing.scaleX = 1f
        outgoing.scaleY = 1f
        currentPage = destination
        bottomNavigation.setSelectedIndex(destination)

        outgoing.animate()
            .alpha(0f)
            .translationY(if (reducedMotion) 0f else -dp(3).toFloat())
            .setDuration(MotionTokens.smallMs)
            .setInterpolator(MotionTokens.easeOut)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentPage != previous) {
                        outgoing.visibility = View.INVISIBLE
                        outgoing.alpha = 1f
                        outgoing.translationY = 0f
                    }
                }
            })
            .start()

        if (reducedMotion) {
            incoming.alpha = 1f
            incoming.translationY = 0f
        } else {
            incoming.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(MotionTokens.panelMs)
                .setInterpolator(MotionTokens.easeOut)
                .setListener(null)
                .start()
        }
    }

    private fun configureSystemBars() {
        Theme.applySystemBars(this)
    }

    /**
     * Circular theme hand-off after night-mode toggle:
     * - expand=true  (light→dark): old light screenshot is punched open from the
     *   switch, revealing the new dark content underneath (night spreads out).
     * - expand=false (dark→light): old dark screenshot is clipped to a shrinking
     *   circle that collapses into the switch (night is sucked back in).
     */
    private fun playPendingThemeTransition(root: FrameLayout) {
        val pending = Theme.consumeCircularTransition() ?: return
        val (bitmap, center, expand) = pending
        val cx = center.first
        val cy = center.second
        val overlay = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            private val path = Path()
            var radius = 0f
            var modeExpand = true

            override fun onDraw(canvas: Canvas) {
                if (width <= 0 || height <= 0 || bitmap.isRecycled) return
                val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
                if (modeExpand) {
                    // Full old screenshot with a growing clear hole.
                    val layer = canvas.saveLayer(dst, null)
                    canvas.drawBitmap(bitmap, null, dst, paint)
                    path.reset()
                    path.addCircle(cx, cy, radius, Path.Direction.CW)
                    canvas.drawPath(path, clearPaint)
                    canvas.restoreToCount(layer)
                } else {
                    // Shrinking circle of the old screenshot.
                    val layer = canvas.saveLayer(dst, null)
                    path.reset()
                    path.addCircle(cx, cy, radius.coerceAtLeast(0f), Path.Direction.CW)
                    canvas.clipPath(path)
                    canvas.drawBitmap(bitmap, null, dst, paint)
                    canvas.restoreToCount(layer)
                }
            }
        }
        overlay.modeExpand = expand
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        fun startAnim(maxR: Float) {
            val start = if (expand) 0f else maxR
            val end = if (expand) maxR else 0f
            overlay.radius = start
            ValueAnimator.ofFloat(start, end).apply {
                duration = 520L
                interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
                addUpdateListener {
                    overlay.radius = it.animatedValue as Float
                    overlay.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    private fun cleanup() {
                        root.removeView(overlay)
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    override fun onAnimationEnd(animation: Animator) = cleanup()
                    override fun onAnimationCancel(animation: Animator) = cleanup()
                })
                start()
            }
        }
        if (overlay.width > 0 && overlay.height > 0) {
            val maxR = maxRadius(cx, cy, overlay.width, overlay.height)
            startAnim(maxR)
        } else {
            overlay.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    overlay.removeOnLayoutChangeListener(this)
                    startAnim(maxRadius(cx, cy, overlay.width, overlay.height))
                }
            })
        }
    }

    private fun maxRadius(cx: Float, cy: Float, w: Int, h: Int): Float {
        val corners = listOf(
            hypot(cx.toDouble(), cy.toDouble()),
            hypot((w - cx).toDouble(), cy.toDouble()),
            hypot(cx.toDouble(), (h - cy).toDouble()),
            hypot((w - cx).toDouble(), (h - cy).toDouble())
        )
        return (corners.maxOrNull() ?: 1.0).toFloat() + dp(24).toFloat()
    }

    private fun pageDescription(index: Int): String = when (index) {
        HOME -> "首页"
        DOWNLOADS -> "下载"
        else -> "我的"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val HOME = 0
        const val DOWNLOADS = 1
        const val PROFILE = 2
        const val STATE_CURRENT_PAGE = "main_current_page"
        const val PREFS_NAME = "yuibili_settings"
        const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
    }
}
