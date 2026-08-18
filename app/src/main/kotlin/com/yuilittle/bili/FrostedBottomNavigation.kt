package com.yuilittle.bili

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import kotlin.math.abs

/**
 * A compact, dependency-free floating navigation dock with icon + label tabs.
 *
 * The dock uses a quiet warm-white surface, rose active icon/label, and one moving
 * underline. A normal tap selects a destination; holding briefly enters scrub mode,
 * then sliding across 首页 / 下载 / 我的 selects whichever item is under the finger.
 */
class FrostedBottomNavigation @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val panelBounds = RectF()
    private val selection = floatArrayOf(1f, 0f, 0f)
    private var selectedIndex = 0
    // A shared position lets the active underline glide continuously between tabs.
    private var indicatorPosition = 0f
    private var selectionAnimator: ValueAnimator? = null
    private var destinationListener: ((Int) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var longPressArmed = false
    private var scrubMode = false
    private var gestureMoved = false
    private var scrubIndex = -1
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressRunnable = Runnable {
        if (!longPressArmed) return@Runnable
        updatePanelBounds()
        // Natural finger drift is allowed while waiting for the long press.
        // Only leaving the dock vertically cancels activation; horizontal movement
        // is exactly what this scrub interaction needs.
        val verticalAllowance = dp(24f)
        if (lastTouchY < panelBounds.top - verticalAllowance ||
            lastTouchY > panelBounds.bottom + verticalAllowance
        ) return@Runnable
        scrubMode = true
        scrubIndex = itemAt(lastTouchX)
        selectDestination(scrubIndex)
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "底部导航"
    }

    fun setOnDestinationSelectedListener(listener: (Int) -> Unit) {
        destinationListener = listener
    }

    fun setSelectedIndex(index: Int) {
        if (index !in 0..2) return
        val start = selection.copyOf()
        val startPosition = indicatorPosition
        selectedIndex = index
        selectionAnimator?.cancel()
        // Already settled on this tab: nothing to animate.
        if (startPosition == index.toFloat() && start[index] == 1f) return
        selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (scrubMode) 160L else 260L
            interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                indicatorPosition = lerp(startPosition, selectedIndex.toFloat(), progress)
                for (item in 0..2) {
                    val target = if (item == selectedIndex) 1f else 0f
                    selection[item] = lerp(start[item], target, progress)
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updatePanelBounds()
        if (panelBounds.width() <= 0f || panelBounds.height() <= 0f) return

        val radius = dp(22f)

        // Quiet floating surface that tracks light / dark theme.
        glassPaint.shader = null
        glassPaint.style = Paint.Style.FILL
        glassPaint.color = COLOR_NAV_FILL
        canvas.drawRoundRect(panelBounds, radius, radius, glassPaint)

        glassPaint.style = Paint.Style.STROKE
        glassPaint.strokeWidth = dp(1f)
        glassPaint.color = COLOR_NAV_STROKE
        canvas.drawRoundRect(panelBounds, radius, radius, glassPaint)

        val itemWidth = panelBounds.width() / 3f
        val iconCenterY = panelBounds.centerY() - dp(7f)
        val activeCenterX = panelBounds.left + itemWidth * (indicatorPosition + 0.5f)
        drawActiveIndicator(canvas, activeCenterX, panelBounds.bottom - dp(6.5f))

        for (item in 0..2) {
            val centerX = panelBounds.left + itemWidth * (item + 0.5f)
            drawIcon(canvas, item, centerX, iconCenterY - dp(1f) * selection[item], selection[item])
            drawLabel(canvas, item, centerX, selection[item])
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                updatePanelBounds()
                if (!panelBounds.contains(event.x, event.y)) return false
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                longPressArmed = true
                scrubMode = false
                gestureMoved = false
                scrubIndex = itemAt(event.x)
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = event.x
                lastTouchY = event.y
                if (!scrubMode) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        // Suppress a later tap, but keep horizontal long-press
                        // activation armed so hold-then-slide works reliably.
                        gestureMoved = true
                    }
                    val verticalAllowance = dp(24f)
                    if (event.y < panelBounds.top - verticalAllowance ||
                        event.y > panelBounds.bottom + verticalAllowance
                    ) {
                        longPressArmed = false
                        removeCallbacks(longPressRunnable)
                    }
                } else {
                    val destination = itemAt(event.x)
                    if (destination != scrubIndex) {
                        scrubIndex = destination
                        selectDestination(destination)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (!scrubMode && longPressArmed && !gestureMoved && panelBounds.contains(event.x, event.y)) {
                    performClick()
                    selectDestination(itemAt(event.x))
                }
                finishGesture()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                finishGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Returns the nearest navigation item, including while the finger drifts past an edge. */
    private fun itemAt(x: Float): Int {
        updatePanelBounds()
        val itemWidth = panelBounds.width() / 3f
        return ((x - panelBounds.left) / itemWidth).toInt().coerceIn(0, 2)
    }

    private fun selectDestination(destination: Int) {
        if (destination !in 0..2 || destination == selectedIndex) return
        destinationListener?.invoke(destination) ?: setSelectedIndex(destination)
    }

    private fun finishGesture() {
        longPressArmed = false
        scrubMode = false
        gestureMoved = false
        scrubIndex = -1
    }

    private fun drawActiveIndicator(canvas: Canvas, centerX: Float, centerY: Float) {
        val indicator = RectF(
            centerX - dp(8f),
            centerY - dp(1.4f),
            centerX + dp(8f),
            centerY + dp(1.4f)
        )
        glassPaint.shader = null
        glassPaint.style = Paint.Style.FILL
        glassPaint.color = ACTIVE_ICON
        canvas.drawRoundRect(indicator, dp(1.4f), dp(1.4f), glassPaint)
    }

    private fun drawIcon(
        canvas: Canvas,
        item: Int,
        centerX: Float,
        centerY: Float,
        progress: Float
    ) {
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeCap = Paint.Cap.ROUND
        iconPaint.strokeJoin = Paint.Join.ROUND
        iconPaint.strokeWidth = dp(1.8f) * (1f + progress * 0.08f)
        iconPaint.color = mixColor(COLOR_NAV_INACTIVE, ACTIVE_ICON, progress)
        val scale = lerp(0.96f, 1.02f, progress)

        when (item) {
            0 -> drawHomeIcon(canvas, centerX, centerY, scale)
            1 -> drawDownloadsIcon(canvas, centerX, centerY, scale)
            else -> drawProfileIcon(canvas, centerX, centerY, scale)
        }
    }

    /** Label under each icon; the active one turns rose and bold. */
    private fun drawLabel(canvas: Canvas, item: Int, centerX: Float, progress: Float) {
        iconPaint.style = Paint.Style.FILL
        iconPaint.textAlign = Paint.Align.CENTER
        iconPaint.textSize = sp(10f)
        iconPaint.typeface =
            if (progress > 0.5f) Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        iconPaint.color = mixColor(COLOR_NAV_INACTIVE, ACTIVE_ICON, progress)
        val label = when (item) {
            0 -> "首页"
            1 -> "下载"
            else -> "我的"
        }
        canvas.drawText(label, centerX, panelBounds.centerY() + dp(13.5f), iconPaint)
    }

    /** A single continuous home outline with a centered doorway. */
    private fun drawHomeIcon(canvas: Canvas, x: Float, y: Float, scale: Float) {
        val side = dp(9f) * scale
        val roof = y - dp(8.8f) * scale
        val floor = y + dp(8.3f) * scale

        path.reset()
        path.moveTo(x - side, y - dp(0.7f) * scale)
        path.lineTo(x, roof)
        path.lineTo(x + side, y - dp(0.7f) * scale)
        path.lineTo(x + dp(7.4f) * scale, floor)
        path.lineTo(x - dp(7.4f) * scale, floor)
        path.close()
        canvas.drawPath(path, iconPaint)
    }

    /** Simple download tray + arrow, matches the rest of the monoline icons. */
    private fun drawDownloadsIcon(canvas: Canvas, x: Float, y: Float, scale: Float) {
        val shaftTop = y - dp(8.2f) * scale
        val shaftBottom = y + dp(2.2f) * scale
        val arrow = dp(4.4f) * scale
        canvas.drawLine(x, shaftTop, x, shaftBottom, iconPaint)
        path.reset()
        path.moveTo(x - arrow, shaftBottom - arrow)
        path.lineTo(x, shaftBottom)
        path.lineTo(x + arrow, shaftBottom - arrow)
        canvas.drawPath(path, iconPaint)

        val trayLeft = x - dp(7.6f) * scale
        val trayRight = x + dp(7.6f) * scale
        val trayTop = y + dp(4.2f) * scale
        val trayBottom = y + dp(8.6f) * scale
        path.reset()
        path.moveTo(trayLeft, trayTop)
        path.lineTo(trayLeft, trayBottom - dp(1.6f) * scale)
        path.quadTo(trayLeft, trayBottom, trayLeft + dp(1.6f) * scale, trayBottom)
        path.lineTo(trayRight - dp(1.6f) * scale, trayBottom)
        path.quadTo(trayRight, trayBottom, trayRight, trayBottom - dp(1.6f) * scale)
        path.lineTo(trayRight, trayTop)
        canvas.drawPath(path, iconPaint)
    }

    /** A balanced portrait silhouette with a restrained open shoulder curve. */
    private fun drawProfileIcon(canvas: Canvas, x: Float, y: Float, scale: Float) {
        val headRadius = dp(3.8f) * scale
        canvas.drawCircle(x, y - dp(5.3f) * scale, headRadius, iconPaint)

        path.reset()
        path.moveTo(x - dp(9f) * scale, y + dp(8.5f) * scale)
        path.cubicTo(
            x - dp(8.2f) * scale, y + dp(1.8f) * scale,
            x + dp(8.2f) * scale, y + dp(1.8f) * scale,
            x + dp(9f) * scale, y + dp(8.5f) * scale
        )
        canvas.drawPath(path, iconPaint)
    }

    private fun updatePanelBounds() {
        val horizontalInset = dp(10f)
        panelBounds.set(
            horizontalInset,
            dp(6f),
            width.toFloat() - horizontalInset,
            height.toFloat() - dp(6f)
        )
    }

    private fun mixColor(from: Int, to: Int, amount: Float): Int {
        val bounded = amount.coerceIn(0f, 1f)
        return Color.rgb(
            lerp(Color.red(from).toFloat(), Color.red(to).toFloat(), bounded).toInt(),
            lerp(Color.green(from).toFloat(), Color.green(to).toFloat(), bounded).toInt(),
            lerp(Color.blue(from).toFloat(), Color.blue(to).toFloat(), bounded).toInt()
        )
    }

    private fun lerp(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private companion object {
        val ACTIVE_ICON: Int = Color.rgb(235, 67, 132)
    }
}
