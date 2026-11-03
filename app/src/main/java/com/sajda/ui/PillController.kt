
package com.sajda.ui

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sajda.R

class PillController(
    private val pill: TextView,
    private val onReadyDismissed: () -> Unit = {}
) {
    var mode: PillMode = PillMode.HIDDEN
        private set

    private val hideReadyRunnable = Runnable {
        if (mode != PillMode.READY_FADE) return@Runnable
        pill.animate().cancel()
        pill.animate().alpha(0f).setDuration(320).withEndAction {
            if (mode == PillMode.READY_FADE) {
                pill.visibility = View.GONE
                pill.alpha = 1f
                mode = PillMode.HIDDEN
                onReadyDismissed()
            }
        }.start()
    }

    fun cancelHide() {
        pill.handler?.removeCallbacks(hideReadyRunnable)
    }

    fun show(mode: PillMode, textRes: Int, action: Boolean) {
        if (mode == PillMode.HIDDEN) {
            this.mode = mode
            cancelHide()
            pill.animate().cancel()
            pill.visibility = View.GONE
            return
        }
        cancelHide()
        pill.animate().cancel()
        this.mode = mode
        pill.alpha = 1f
        pill.visibility = View.VISIBLE
        pill.setText(textRes)
        val ctx = pill.context
        when {
            mode == PillMode.NEED_PERMISSION || mode == PillMode.NEED_SYSTEM -> {
                pill.setBackgroundResource(R.drawable.bg_glass_pill_warn)
                pill.setTextColor(ContextCompat.getColor(ctx, R.color.conf_low))
            }
            mode == PillMode.CACHED && textRes == R.string.city_approx_warning -> {
                pill.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
                pill.setTextColor(ContextCompat.getColor(ctx, R.color.conf_medium))
            }
            action -> {
                pill.setBackgroundResource(R.drawable.bg_liquid_glass_action)
                pill.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            }
            else -> {
                pill.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
                pill.setTextColor(ContextCompat.getColor(ctx, R.color.label_secondary))
            }
        }
        pill.isClickable = action || mode == PillMode.CACHED || mode == PillMode.LOCATING
        if (mode == PillMode.READY_FADE) {
            pill.postDelayed(hideReadyRunnable, 2200L)
        }
    }
}
