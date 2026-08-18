package com.yuilittle.bili

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hosts only the official bilibili web player/page. It neither requests media
 * stream URLs nor downloads or modifies protected content.
 */
class PlayerActivity : Activity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBars()
        val customUrl = intent.getStringExtra(EXTRA_URL)
        val bvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        val page = intent.getIntExtra(EXTRA_PAGE, 1).coerceAtLeast(1)
        if (customUrl.isNullOrBlank() && bvid.isBlank()) {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(toolbar(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        val host = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setWebChromeClient(WebChromeClient())
            setWebViewClient(WebViewClient())
        }
        host.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(host, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        val destination = customUrl
            ?: "https://player.bilibili.com/player.html?bvid=$bvid&page=$page&as_wide=1&high_quality=1&danmaku=1"
        webView.loadUrl(destination)
    }

    private fun toolbar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
        setPadding(dp(8), 0, dp(14), 0)
        addView(TextView(this@PlayerActivity).apply {
            text = "‹"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "返回"
            isClickable = true
            setOnClickListener { onBackPressed() }
        }, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.MATCH_PARENT))
        addView(TextView(this@PlayerActivity).apply {
            text = "官方网页播放"
            textSize = 16f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@PlayerActivity).apply {
            text = "重新加载"
            textSize = 13f
            setTextColor(0xFFFFB7D2.toInt())
            isClickable = true
            setPadding(dp(8), dp(8), 0, dp(8))
            setOnClickListener { webView.reload() }
        })
    }

    override fun onDestroy() {
        webView.apply {
            loadUrl("about:blank")
            stopLoading()
            destroy()
        }
        super.onDestroy()
    }

    private fun configureBars() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    companion object {
        const val EXTRA_BVID = "player_bvid"
        const val EXTRA_PAGE = "player_page"
        const val EXTRA_URL = "player_url"
    }
}
