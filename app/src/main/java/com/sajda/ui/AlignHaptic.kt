package com.sajda.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.sajda.session.HeadingPolicy
import kotlin.math.abs

class AlignHaptic(context: Context) {
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (e: Exception) {
        Log.w("SajdaHaptic", "vibrate failed", e)
        null
    }

    private var wasAligned = false

    fun onTurn(turnAbsDeg: Double) {
        val a = abs(turnAbsDeg)
        val aligned = if (wasAligned) a < HeadingPolicy.ALIGN_EXIT_DEG
        else a < HeadingPolicy.ALIGN_ENTER_DEG
        if (aligned && !wasAligned) tick()
        wasAligned = aligned
    }

    fun reset() {
        wasAligned = false
    }

    /** Single light tick for user-initiated actions (e.g. start GPS search). */
    fun tickLight() {
        tick()
    }

    private fun tick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(18)
            }
        } catch (e: Exception) {
            Log.w("SajdaHaptic", "vibrate failed", e)
        }
    }
}
