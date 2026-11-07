package com.sajda.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager

/**
 * Platform liquid-glass helpers.
 * Window background blur (API 31+) approximates iOS backdrop material for dialogs.
 */
object LiquidGlass {
    fun applyDialogBackdrop(dialog: Dialog, blurRadiusPx: Int = 48) {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.also {
                    it.blurBehindRadius = blurRadiusPx
                }
            } catch (_: Exception) {
            }
            try {
                // Some devices also support window background blur
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 31) {
                    window.setBackgroundBlurRadius(blurRadiusPx.coerceIn(1, 60))
                }
            } catch (_: Exception) {
            }
        }
        // Dim so the glass card reads on pure black OLED
        window.setDimAmount(0.45f)
    }
}
