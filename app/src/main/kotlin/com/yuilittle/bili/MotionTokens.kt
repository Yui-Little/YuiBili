package com.yuilittle.bili

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator

/** Central motion vocabulary for the classic Android View UI. */
internal object MotionTokens {
    /** Strong responsive ease-out for entrances and UI feedback. */
    val easeOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    /** Spatial curve for a drawer/sheet that follows the user's gesture. */
    val drawer = PathInterpolator(0.32f, 0.72f, 0f, 1f)

    const val pressMs = 120L
    const val smallMs = 160L
    const val panelMs = 220L

    /** Respect the system animator scale without depending on Compose. */
    fun isReduced(context: android.content.Context): Boolean {
        return runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }

    /** Enters with alpha and a small lift; reduced motion keeps only the fade. */
    fun enter(view: View, reduced: Boolean, duration: Long = panelMs, liftPx: Float = 8f) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = if (reduced) 0f else liftPx
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(if (reduced) smallMs else duration)
            .setInterpolator(easeOut)
            .start()
    }

    /** Fast, interruptible exit; does not use a layout property. */
    fun exit(view: View, reduced: Boolean, duration: Long = smallMs, liftPx: Float = 3f, end: (() -> Unit)? = null) {
        view.animate().cancel()
        val animator = view.animate()
            .alpha(0f)
            .translationY(if (reduced) 0f else -liftPx)
            .setDuration(if (reduced) smallMs else duration)
            .setInterpolator(easeOut)
        if (end != null) animator.withEndAction(end)
        animator.start()
    }
}
