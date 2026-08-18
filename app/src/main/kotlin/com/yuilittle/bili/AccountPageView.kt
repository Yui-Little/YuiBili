package com.yuilittle.bili

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/**
 * Account ("我的") page.
 *
 * Logged out: renders the native YuiBili login page (QR scan).
 * Logged in: shows a rounded profile card
 * with avatar, nickname, uid, coins, VIP badge, Bilibili level, following
 * and followers, plus a logout entry.
 */
class AccountPageView(context: Context) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── containers ────────────────────────────────────────────────
    private val loginPage = LinearLayout(context)
    private val profilePage = LinearLayout(context)
    private val qrContainer = LinearLayout(context)

    // ── QR login views ────────────────────────────────────────────
    private val qrImage = ImageView(context)
    private val qrStatus = TextView(context)
    private var qrKey = ""
    private var qrPolling = false
    private var qrBitmap: Bitmap? = null
    private var qrContent: String = ""

    // ── profile card views ────────────────────────────────────────
    private val cardAvatar = RoundAvatar(context)
    private val cardName = TextView(context)
    private val cardUid = TextView(context)
    private val cardBadges = LinearLayout(context)
    private val cardFollowing = TextView(context)
    private val cardFollower = TextView(context)
    private val aboutBadge = TextView(context)
    private val updateBadgeListener: () -> Unit = { refreshAboutBadge() }
    private val cardCoins = TextView(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BACKGROUND)

        // ── login page ──────────────────────────────────────────────
        loginPage.orientation = VERTICAL
        loginPage.setPadding(dp(28), dp(48), dp(28), dp(40))

        loginPage.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_logo_round)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "YuiBili"
            // Soft rounded plate so the brand mark never reads as a raw square tile.
            background = rounded(COLOR_CARD, dp(16), COLOR_CARD_BORDER, dp(1))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
        }, LayoutParams(dp(72), dp(72)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })
        loginPage.addView(TextView(context).apply {
            text = "YuiBili登录"
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(COLOR_INK)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        loginPage.addView(TextView(context).apply {
            text = "扫码登录"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // QR panel.
        qrContainer.orientation = VERTICAL
        qrContainer.gravity = Gravity.CENTER_HORIZONTAL
        qrContainer.setPadding(0, dp(30), 0, 0)
        val qrCard = FrameLayout(context).apply {
            background = rounded(COLOR_CARD, dp(16), COLOR_CARD_BORDER, dp(1))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        qrImage.apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.WHITE)
            contentDescription = "登录二维码"
        }
        qrCard.addView(qrImage, FrameLayout.LayoutParams(dp(212), dp(212)))
        qrContainer.addView(qrCard, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        qrStatus.apply {
            text = "请使用哔哩哔哩 App 扫码登录"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(16), 0, 0)
        }
        qrContainer.addView(qrStatus, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Hint: screenshots of on-screen QR often fail to scan on another phone.
        qrContainer.addView(TextView(context).apply {
            text = "截图可能扫不出来，建议下载后发给对方"
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(10), 0, 0)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Primary: download a high-res QR; secondary: refresh.
        val qrActions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        qrActions.addView(TextView(context).apply {
            text = "下载二维码"
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(COLOR_ROSE, dp(22))
            isClickable = true
            isFocusable = true
            setPadding(dp(22), dp(10), dp(22), dp(10))
            setOnClickListener { saveQrToGallery() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        qrActions.addView(TextView(context).apply {
            text = "刷新"
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(COLOR_ROSE)
            background = rounded(0x00000000.toInt(), dp(22), COLOR_ROSE, dp(1))
            isClickable = true
            isFocusable = true
            setPadding(dp(18), dp(9), dp(18), dp(9))
            setOnClickListener { startQrLogin() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12)
        })
        qrContainer.addView(qrActions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // Long-press the QR itself also downloads — discoverable fallback.
        qrImage.isLongClickable = true
        qrImage.setOnLongClickListener {
            saveQrToGallery()
            true
        }
        loginPage.addView(qrContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── profile page ─────────────────────────────────────────────
        profilePage.orientation = VERTICAL
        profilePage.setPadding(dp(20), dp(28), dp(20), dp(40))

        // Rounded profile card.
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = rounded(COLOR_CARD, dp(18), COLOR_CARD_BORDER, dp(1))
            setPadding(dp(20), dp(22), dp(20), dp(20))
        }
        val headRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // No rectangular background: RoundAvatar draws its own circular placeholder.
        headRow.addView(cardAvatar, LayoutParams(dp(60), dp(60)))
        val nameBox = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        cardName.apply {
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_INK)
            maxLines = 1
        }
        nameBox.addView(cardName, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        cardUid.apply {
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(4), 0, 0)
        }
        nameBox.addView(cardUid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        cardBadges.apply {
            orientation = HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        nameBox.addView(cardBadges, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        headRow.addView(nameBox, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        // Logout icon on the card's right side; confirm dialog is fully custom.
        headRow.addView(LogoutIconView(context).apply {
            setOnClickListener { showLogoutConfirm() }
        }, LayoutParams(dp(40), dp(40)))
        card.addView(headRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Stat row: following / followers / coins.
        card.addView(View(context).apply { setBackgroundColor(COLOR_BORDER) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(18); bottomMargin = dp(14) })
        val statRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        statRow.addView(buildStat("关注", cardFollowing), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        statRow.addView(View(context).apply { setBackgroundColor(COLOR_BORDER) },
            LayoutParams(dp(1), dp(30)))
        statRow.addView(buildStat("粉丝", cardFollower), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        statRow.addView(View(context).apply { setBackgroundColor(COLOR_BORDER) },
            LayoutParams(dp(1), dp(30)))
        statRow.addView(buildStat("硬币", cardCoins), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        card.addView(statRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        profilePage.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Settings card: night mode pill switch ──────────────────
        val settingsCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = rounded(COLOR_CARD, dp(18), COLOR_CARD_BORDER, dp(1))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        val darkSwitch = PillSwitch(context, Theme.isDark)
        fun applyDarkToggle(anchor: View) {
            val next = !Theme.isDark
            darkSwitch.toggle()
            // Snapshot the current window so MainActivity can play a circular
            // reveal/collapse from the switch after recreate.
            val activity = context as? Activity
            if (activity != null) {
                try {
                    val decor = activity.window.decorView
                    // Capture content root only (not the full decor with status bar),
                    // then convert the switch centre into content-local coordinates so
                    // the circle does not start one status-bar height lower after recreate.
                    val content = decor.findViewById<View>(android.R.id.content) ?: decor
                    val switchLoc = IntArray(2)
                    val contentLoc = IntArray(2)
                    anchor.getLocationOnScreen(switchLoc)
                    content.getLocationOnScreen(contentLoc)
                    val cx = switchLoc[0] - contentLoc[0] + anchor.width / 2f
                    val cy = switchLoc[1] - contentLoc[1] + anchor.height / 2f
                    val bmp = android.graphics.Bitmap.createBitmap(
                        content.width.coerceAtLeast(1),
                        content.height.coerceAtLeast(1),
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val c = Canvas(bmp)
                    content.draw(c)
                    // expand=true when turning dark on: night spreads from the switch.
                    // expand=false when turning dark off: night collapses back into it.
                    Theme.prepareCircularTransition(bmp, cx, cy, expand = next)
                } catch (_: Exception) {
                    // Fall through to a plain recreate if snapshot fails.
                }
            }
            Theme.setDark(context, next)
            activity?.overridePendingTransition(0, 0)
            activity?.recreate()
        }
        settingsCard.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(context).apply {
                text = "夜间模式"
                textSize = 15f
                setTextColor(COLOR_INK)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(darkSwitch, LayoutParams(dp(48), dp(28)))
            // Whole row (including the switch) shares one handler so both the
            // label and the pill actually flip the theme.
            setOnClickListener { applyDarkToggle(darkSwitch) }
            darkSwitch.setOnClickListener { applyDarkToggle(darkSwitch) }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        profilePage.addView(settingsCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        // ── Menu card: history / favorites / downloads / about ─────
        val menuCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = rounded(COLOR_CARD, dp(18), COLOR_CARD_BORDER, dp(1))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        // 统一菜单行：自绘线条图标（同一批 stroke 风格），避免 Unicode 字符各有千秋。
        // 行间分隔线单独取可见色（不动全局 COLOR_BORDER），保持 1dp 发丝级细度。
        fun addMenuDivider() {
            menuCard.addView(View(context).apply {
                setBackgroundColor(if (Theme.isDark) 0x3AFFFFFF.toInt() else 0x26000000.toInt())
            },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                    leftMargin = dp(48); rightMargin = dp(12)
                })
        }
        fun menuRow(iconKind: Int, label: String, extraEnd: View? = null, onClick: () -> Unit) {
            menuCard.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                addView(
                    MenuIconView(context, iconKind),
                    LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) }
                )
                addView(TextView(context).apply {
                    text = label
                    textSize = 15f
                    setTextColor(COLOR_INK)
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                if (extraEnd != null) {
                    addView(
                        extraEnd,
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                            rightMargin = dp(8)
                        }
                    )
                }
                addView(TextView(context).apply {
                    text = "›"
                    textSize = 20f
                    setTextColor(COLOR_MUTED)
                }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        }
        menuRow(MenuIconView.HISTORY, "历史记录") {
            context.startActivity(Intent(context, ProfileSubActivity::class.java)
                .putExtra(ProfileSubActivity.EXTRA_MODE, ProfileSubActivity.MODE_HISTORY))
        }
        addMenuDivider()
        menuRow(MenuIconView.FAVORITE, "我的收藏") {
            context.startActivity(Intent(context, ProfileSubActivity::class.java)
                .putExtra(ProfileSubActivity.EXTRA_MODE, ProfileSubActivity.MODE_FAVORITES))
        }
        addMenuDivider()
        menuRow(MenuIconView.SPONSOR, "赞助开发") {
            context.startActivity(Intent(context, ProfileSubActivity::class.java)
                .putExtra(ProfileSubActivity.EXTRA_MODE, ProfileSubActivity.MODE_SPONSOR))
        }
        addMenuDivider()
        // Open QQ group feedback page in the system browser (no in-app WebView).
        menuRow(MenuIconView.GROUP, "群聊反馈") {
            try {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://qun.qq.com/universal-share/share?ac=1&authKey=mi%2F1NPMkv68IZY%2FjWTSovU4mgcq%2FLKGBcEe6VIdf7rn4cju57FyW2hDPVX43AEfU&busi_data=eyJncm91cENvZGUiOiIxMDk3MDQ3NzQ3IiwidG9rZW4iOiI1Q3Ewd1hyNTJKMzlzUjg2UmZsclJ6Z2h2aEJMbjZJcTZ2Wm1Pb0Nnc2U5eUlFM1AwcXFTSXd0SFhZNmxreER5IiwidWluIjoiMjQ3NjkxNjU3MyJ9&data=x4EWUUQal_zU_nMigagLWGY9vd7zwRepc7wSgc96xwz4YxcDhYENVMfmKbJsbp7elJ2Xm415Cv1inVohsQjyiQ&svctype=4&tempid=h5_group_info"
                        )
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                Toast.makeText(context, "无法打开链接，请检查是否安装浏览器", Toast.LENGTH_SHORT).show()
            }
        }
        addMenuDivider()
        // About row with optional red bubble when a newer build is pending.
        aboutBadge.apply {
            text = "发现新版本"
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(3), dp(8), dp(3))
            includeFontPadding = false
            background = rounded(0xFFE53935.toInt(), dp(10))
            visibility = View.GONE
        }
        menuRow(MenuIconView.ABOUT, "关于软件", extraEnd = aboutBadge) {
            context.startActivity(Intent(context, ProfileSubActivity::class.java)
                .putExtra(ProfileSubActivity.EXTRA_MODE, ProfileSubActivity.MODE_ABOUT))
        }

        profilePage.addView(menuCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        // Root scroll container.
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = FrameLayout(context)
        content.addView(loginPage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        content.addView(profilePage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        scroll.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        refresh()
    }


    private fun refreshAboutBadge() {
        val show = UpdateChecker.hasPendingUpdate(context)
        aboutBadge.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        UpdateChecker.addListener(updateBadgeListener)
        // Badge only reflects the live result of this process's open-time check
        // (no sticky disk cache / no re-check on every tab switch).
        refreshAboutBadge()
    }

    override fun onDetachedFromWindow() {
        UpdateChecker.removeListener(updateBadgeListener)
        stopQrPolling()
        super.onDetachedFromWindow()
    }

    // ── public refresh ────────────────────────────────────────────

    /**
     * [soft]=true keeps the existing profile card on screen (used when
     * returning from history/favorites or tab switches). Only a quiet
     * background re-fetch runs, and only if the cache is stale — no
     * "加载中…" flash that made the card look like it reloaded.
     */
    fun refresh(soft: Boolean = false) {
        refreshAboutBadge()
        BiliSessionStore.init(context)
        if (BiliSessionStore.isLoggedIn()) {
            stopQrPolling()
            loginPage.visibility = View.GONE
            profilePage.visibility = View.VISIBLE
            loadProfile(soft = soft && profileBound)
        } else {
            profileBound = false
            lastProfileFace = ""
            loginPage.visibility = View.VISIBLE
            profilePage.visibility = View.GONE
            // Only (re)generate the QR code when there is none yet; keeps the code
            // stable while the user switches back and forth between tabs.
            if (qrKey.isBlank() && qrImage.drawable == null) startQrLogin()
        }
    }

    // ── QR login ──────────────────────────────────────────────────

    private fun startQrLogin() {
        stopQrPolling()
        qrImage.setImageDrawable(null)
        qrKey = ""
        qrStatus.text = "正在生成二维码…"
        BiliLoginApi.generateQrCode { url, key, error ->
            mainHandler.post {
                if (error != null || url == null || key == null) {
                    qrStatus.text = "二维码生成失败，点击刷新重试"
                    return@post
                }
                qrKey = key
                renderQr(url)
                qrStatus.text = "请使用哔哩哔哩 App 扫码登录"
                startPolling()
            }
        }
    }

    private fun renderQr(content: String) {
        try {
            qrContent = content
            val size = dp(212)
            val bitmap = buildQrBitmap(content, size, marginModules = 2)
            qrBitmap = bitmap
            qrImage.setImageBitmap(bitmap)
        } catch (error: Exception) {
            qrContent = ""
            qrBitmap = null
            qrStatus.text = "二维码生成失败，点击刷新重试"
        }
    }

    /**
     * Build a scan-friendly QR bitmap.
     * Export uses a larger size + thicker quiet zone so another phone can scan
     * the saved image more reliably than a screenshot of the on-screen tile.
     */
    private fun buildQrBitmap(content: String, sizePx: Int, marginModules: Int = 2): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to marginModules, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix: BitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val black = Color.BLACK
        val white = Color.WHITE
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) black else white)
            }
        }
        return bitmap
    }

    /** Saves a high-res QR into the system gallery (Pictures/YuiBili). */
    private fun saveQrToGallery() {
        val bitmap = try {
            if (qrContent.isNotBlank()) {
                // 800px + larger quiet zone → easier for another phone to scan.
                buildQrBitmap(qrContent, sizePx = 800, marginModules = 3)
            } else {
                qrBitmap ?: (qrImage.drawable as? BitmapDrawable)?.bitmap
            }
        } catch (_: Exception) {
            qrBitmap ?: (qrImage.drawable as? BitmapDrawable)?.bitmap
        }
        if (bitmap == null) {
            Toast.makeText(context, "二维码还未生成", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "YuiBili登录二维码_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Scoped storage: no permission needed.
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YuiBili")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    Toast.makeText(context, "已下载到相册（Pictures/YuiBili）", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Legacy storage: needs WRITE_EXTERNAL_STORAGE.
            if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                val act = context as? Activity
                if (act != null) {
                    act.requestPermissions(
                        arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        0x51A1
                    )
                    Toast.makeText(context, "请允许存储权限后再次点击下载", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "请在系统设置中授予存储权限后重试", Toast.LENGTH_SHORT).show()
                }
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
                context.sendBroadcast(
                    android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(file))
                )
                Toast.makeText(context, "已下载到相册（Pictures/YuiBili）", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPolling() {
        if (qrPolling || qrKey.isBlank()) return
        qrPolling = true
        pollRunnable.run()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!qrPolling || qrKey.isBlank()) return
            BiliLoginApi.pollQrCode(qrKey) { state, cookie ->
                mainHandler.post {
                    when (state) {
                        BiliLoginApi.QR_CONFIRMED -> {
                            stopQrPolling()
                            if (!cookie.isNullOrBlank() && BiliSessionStore.saveCookie(context, cookie)) {
                                Toast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
                                resetLoginPage()
                                refresh()
                            } else {
                                // 确认成功但 cookie 解析失败：提示刷新，不要卡死在“已扫码”
                                qrStatus.text = "登录凭证获取失败，请刷新重试"
                            }
                        }
                        BiliLoginApi.QR_SCANNED -> {
                            // 关键：扫码后必须继续轮询，否则用户确认后永远卡在「已扫码」
                            qrStatus.text = "已扫码，请在手机上确认"
                            if (qrPolling) mainHandler.postDelayed(this, 1200)
                        }
                        BiliLoginApi.QR_EXPIRED -> {
                            stopQrPolling()
                            qrStatus.text = "二维码已过期，点击刷新"
                        }
                        BiliLoginApi.QR_FAILED -> {
                            // 单次网络失败不终止，继续下一轮
                            if (qrPolling) mainHandler.postDelayed(this, 2000)
                        }
                        else -> {
                            if (qrPolling) mainHandler.postDelayed(this, 2000)
                        }
                    }
                }
            }
        }
    }

    private fun stopQrPolling() {
        qrPolling = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    // ── profile ───────────────────────────────────────────────────

    /** Guards against overlapping profile fetches (recreate + onResume race). */
    private var profileGeneration = 0
    /** True once the card has been painted with real profile data. */
    private var profileBound = false
    private var lastProfileFace = ""
    private var profileInFlight = false
    private var lastProfileFetchAt = 0L

    private fun loadProfile(soft: Boolean = false) {
        val now = System.currentTimeMillis()
        // Soft path: if we already show a card and fetched recently, skip.
        if (soft && profileBound && now - lastProfileFetchAt < 8_000L) return
        if (profileInFlight && soft) return

        // Night-mode recreate rebuilds this whole page; restore the last good
        // snapshot instantly so avatar/name/stats do not flash "加载中…".
        var quiet = soft
        val cached = BiliSessionStore.cachedProfile()
        if (!profileBound && cached != null) {
            applyProfile(cached, allowAvatarReload = true)
            profileBound = true
            lastProfileFetchAt = BiliSessionStore.cachedProfileAt().takeIf { it > 0L } ?: now
            // Night-mode recreate / quick revisit: keep painted card as-is.
            // Skip network if the snapshot is still fresh.
            quiet = true
            if (now - lastProfileFetchAt < 30_000L) {
                return
            }
        }

        val generation = ++profileGeneration
        if (!quiet || !profileBound) {
            // Hard load only (first paint with no cache / after logout-login).
            cardName.text = "加载中…"
            cardUid.text = ""
            cardBadges.removeAllViews()
            cardFollowing.text = "—"
            cardFollower.text = "—"
            cardCoins.text = "—"
        }
        profileInFlight = true
        lastProfileFetchAt = now
        BiliLoginApi.fetchProfile(BiliSessionStore.cookie()) { info, error ->
            mainHandler.post {
                profileInFlight = false
                // A newer refresh already started; drop this stale response so
                // badges are never appended twice on dark-mode recreate.
                if (generation != profileGeneration) return@post
                if (info == null) {
                    if (!profileBound) {
                        cardBadges.removeAllViews()
                        cardName.text = "加载失败"
                        cardUid.text = error ?: "点击退出后重新登录"
                    }
                    // Soft refresh failure: keep whatever is already on screen.
                    return@post
                }
                BiliSessionStore.cacheProfile(info)
                applyProfile(info, allowAvatarReload = true)
                profileBound = true
            }
        }
    }

    /** Paint the profile card from a UserInfo snapshot without network. */
    private fun applyProfile(info: BiliLoginApi.UserInfo, allowAvatarReload: Boolean) {
        BiliSessionStore.setVip(info.vipStatus, info.vipType)
        cardName.text = info.uname
        cardUid.text = "UID: ${info.uid}"
        val faceUrl = if (info.face.isNotBlank()) info.face + "@128w_128h.webp" else ""
        if (allowAvatarReload && faceUrl.isNotBlank() && faceUrl != lastProfileFace) {
            lastProfileFace = faceUrl
            CoverLoader.load(cardAvatar, faceUrl)
        } else if (faceUrl.isNotBlank() && lastProfileFace.isBlank()) {
            lastProfileFace = faceUrl
            CoverLoader.load(cardAvatar, faceUrl)
        }
        cardBadges.removeAllViews()
        if (info.vipStatus == 1 && info.vipType >= 1) {
            cardBadges.addView(badge("大会员", COLOR_ROSE, COLOR_ROSE_SOFT))
        }
        val levelColor = when (info.level) {
            in 0..3 -> 0xFF8A7F84.toInt()
            in 4..5 -> 0xFFE95786.toInt()
            else -> 0xFFF0A63C.toInt()
        }
        cardBadges.addView(badge("LV${info.level}", 0xFFFFFFFF.toInt(), levelColor))
        cardFollowing.text = formatCount(info.following)
        cardFollower.text = formatCount(info.follower)
        cardCoins.text = if (info.coins > 0) {
            if (info.coins == info.coins.toLong().toDouble()) "${info.coins.toLong()}" else String.format("%.1f", info.coins)
        } else "0"
    }

    private fun badge(text: String, textColor: Int, fill: Int): TextView = TextView(context).apply {
        this.text = text
        textSize = 10.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(textColor)
        background = rounded(fill, dp(8))
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }

    private fun buildStat(label: String, valueView: TextView): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            valueView.apply {
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(COLOR_INK)
            }
            addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(3), 0, 0)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

    private fun formatCount(count: Int): String = when {
        count >= 10000 -> String.format("%.1f万", count / 10000.0)
        count >= 1000 -> String.format("%.1f千", count / 1000.0)
        else -> count.toString()
    }

    // ── helpers ───────────────────────────────────────────────────

    /** Custom logout confirmation dialog (not the system AlertDialog). */
    private fun showLogoutConfirm() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0x00000000))
        val density = resources.displayMetrics.density
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            background = rounded(COLOR_BACKGROUND, dp(20), COLOR_CARD_BORDER, dp(1))
            setPadding(dp(22), dp(22), dp(22), dp(18))
        }
        panel.addView(TextView(context).apply {
            text = "退出登录"
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_INK)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(TextView(context).apply {
            text = "退出后本机的登录凭证将被清除，\n下次需要重新扫码登录。确定退出吗？"
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(20), 0, 0)
        }
        buttons.addView(TextView(context).apply {
            text = "取消"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(22), dp(9), dp(22), dp(9))
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        buttons.addView(TextView(context).apply {
            text = "退出"
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(COLOR_ROSE, dp(16))
            setPadding(dp(26), dp(9), dp(26), dp(9))
            setOnClickListener {
                dialog.dismiss()
                BiliSessionStore.clear(context)
                resetLoginPage()
                refresh()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12)
        })
        panel.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - dp(48),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun resetLoginPage() {
        stopQrPolling()
        qrImage.setImageDrawable(null)
        qrKey = ""
    }

    /** ImageView that draws its drawable clipped to a circle (avatar). */
    private class RoundAvatar(context: Context) : ImageView(context) {
        private val clipPath = Path()
        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val radius = width.coerceAtMost(height) / 2f
            // Circular placeholder so dark mode never shows a square halo.
            placeholderPaint.color = COLOR_COVER
            canvas.drawCircle(width / 2f, height / 2f, radius, placeholderPaint)
            val drawable = drawable ?: return
            clipPath.reset()
            clipPath.addCircle(width / 2f, height / 2f, radius, Path.Direction.CW)
            val saved = canvas.save()
            canvas.clipPath(clipPath)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            canvas.restoreToCount(saved)
        }
    }

    /**
     * 我的页菜单统一图标：同一套 stroke 线条风格，按 kind 绘制不同语义。
     * 0 历史时钟 1 收藏星 2 赞助心 3 群聊气泡 4 关于 i。
     */
    private class MenuIconView(context: Context, private val kind: Int) : View(context) {
        companion object {
            const val HISTORY = 0
            const val FAVORITE = 1
            const val SPONSOR = 2
            const val GROUP = 3
            const val ABOUT = 4
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ROSE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ROSE
            style = Paint.Style.FILL
        }
        private val density: Float = resources.displayMetrics.density
        private fun dpf(v: Float) = v * density

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            paint.strokeWidth = dpf(1.7f)
            when (kind) {
                HISTORY -> {
                    // 时钟：圆环 + 时针分针
                    val r = dpf(8f)
                    canvas.drawCircle(cx, cy, r, paint)
                    canvas.drawLine(cx, cy, cx, cy - dpf(4.5f), paint)
                    canvas.drawLine(cx, cy, cx + dpf(3.8f), cy + dpf(1.5f), paint)
                }
                FAVORITE -> {
                    // 空心五角星（线条风格，与其他 stroke 图标一致）
                    val outer = dpf(8.2f)
                    val inner = outer * 0.42f
                    val path = Path()
                    for (i in 0 until 5) {
                        val aOut = Math.toRadians((-90 + i * 72).toDouble())
                        val aIn = Math.toRadians((-90 + i * 72 + 36).toDouble())
                        val ox = cx + (outer * Math.cos(aOut)).toFloat()
                        val oy = cy + (outer * Math.sin(aOut)).toFloat()
                        val ix = cx + (inner * Math.cos(aIn)).toFloat()
                        val iy = cy + (inner * Math.sin(aIn)).toFloat()
                        if (i == 0) path.moveTo(ox, oy) else path.lineTo(ox, oy)
                        path.lineTo(ix, iy)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                SPONSOR -> {
                    // 心形轮廓
                    val path = Path()
                    val top = cy - dpf(3.2f)
                    path.moveTo(cx, cy + dpf(6.5f))
                    path.cubicTo(
                        cx - dpf(10f), cy + dpf(0.5f),
                        cx - dpf(9.5f), top - dpf(3.5f),
                        cx, top + dpf(1.5f)
                    )
                    path.cubicTo(
                        cx + dpf(9.5f), top - dpf(3.5f),
                        cx + dpf(10f), cy + dpf(0.5f),
                        cx, cy + dpf(6.5f)
                    )
                    path.close()
                    canvas.drawPath(path, paint)
                }
                GROUP -> {
                    // 双人剪影（两个圆头 + 肩弧）
                    canvas.drawCircle(cx - dpf(3.5f), cy - dpf(3.2f), dpf(3.2f), paint)
                    canvas.drawCircle(cx + dpf(4.2f), cy - dpf(2.2f), dpf(2.6f), paint)
                    val left = Path().apply {
                        moveTo(cx - dpf(9f), cy + dpf(6.5f))
                        quadTo(cx - dpf(3.5f), cy + dpf(1.2f), cx + dpf(1.5f), cy + dpf(6.5f))
                    }
                    val right = Path().apply {
                        moveTo(cx + dpf(0.5f), cy + dpf(6.5f))
                        quadTo(cx + dpf(4.5f), cy + dpf(2.2f), cx + dpf(8.8f), cy + dpf(6.5f))
                    }
                    canvas.drawPath(left, paint)
                    canvas.drawPath(right, paint)
                }
                ABOUT -> {
                    // 圆圈 + 中心 i（点 + 竖线）
                    canvas.drawCircle(cx, cy, dpf(8.2f), paint)
                    canvas.drawCircle(cx, cy - dpf(3.6f), dpf(1.15f), fill)
                    canvas.drawLine(cx, cy - dpf(1.1f), cx, cy + dpf(4.2f), paint)
                }
            }
        }
    }

    /** Self-drawn logout icon: door + outward arrow. */
    private class LogoutIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val density: Float = resources.displayMetrics.density
        private val dpf: (Float) -> Float = { it * density }

        init {
            isClickable = true
            isFocusable = true
            contentDescription = "退出登录"
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            paint.strokeWidth = dpf(1.8f)
            // Door frame (left 60%).
            val left = cx - dpf(9f)
            val top = cy - dpf(8f)
            val right = cx + dpf(2f)
            val bottom = cy + dpf(8f)
            canvas.drawRoundRect(left, top, right, bottom, dpf(2f), dpf(2f), paint)
            // Outward arrow from door to the right.
            val startX = right
            val midY = cy
            canvas.drawLine(startX, midY, startX + dpf(10f), midY, paint)
            canvas.drawLine(startX + dpf(5.5f), midY - dpf(4.5f), startX + dpf(10f), midY, paint)
            canvas.drawLine(startX + dpf(5.5f), midY + dpf(4.5f), startX + dpf(10f), midY, paint)
        }
    }

    /** Self-drawn pill switch for the night mode row. */
    private class PillSwitch(context: Context, initial: Boolean) : View(context) {
        var isOn: Boolean = initial
            private set
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density: Float = resources.displayMetrics.density
        private val dpf: (Float) -> Float = { it * density }
        private var progress: Float = if (initial) 1f else 0f
        private var animator: ValueAnimator? = null

        init {
            // Clicks are owned by the parent row / explicit setOnClickListener so
            // the pill never swallows the gesture without flipping the theme.
            isClickable = false
            isFocusable = false
        }

        fun toggle() {
            val target = !isOn
            isOn = target
            animator?.cancel()
            val start = progress
            animator = ValueAnimator.ofFloat(start, if (target) 1f else 0f).apply {
                duration = 220L
                interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            val trackColor = mixColor(COLOR_SWITCH_TRACK_OFF, COLOR_ROSE, progress)
            paint.color = trackColor
            canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, paint)
            val knobSize = h - dpf(5f)
            val knobX = dpf(2.5f) + (w - knobSize - dpf(5f)) * progress
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(knobX + knobSize / 2f, h / 2f, knobSize / 2f, paint)
        }

        private fun mixColor(from: Int, to: Int, t: Float): Int {
            val fr = (from shr 16) and 0xFF
            val fg = (from shr 8) and 0xFF
            val fb = from and 0xFF
            val tr = (to shr 16) and 0xFF
            val tg = (to shr 8) and 0xFF
            val tb = to and 0xFF
            return Color.rgb(
                (fr + (tr - fr) * t).toInt(),
                (fg + (tg - fg) * t).toInt(),
                (fb + (tb - fb) * t).toInt()
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
