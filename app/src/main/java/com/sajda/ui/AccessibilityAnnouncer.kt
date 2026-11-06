package com.sajda.ui

import android.view.View
import com.sajda.R
import com.sajda.compass.Confidence
import com.sajda.geo.QiblaSession
import com.sajda.session.HeadingPolicy
import kotlin.math.abs

/** Throttled TalkBack announcements for turn / alignment. */
class AccessibilityAnnouncer(private val host: View) {
    private var lastKey: String? = null
    private var wasAligned = false

    fun reset() {
        lastKey = null
        wasAligned = false
    }

    fun announce(session: QiblaSession, conf: Confidence) {
        val ctx = host.context
        val heading = session.trueHeading
        if (heading == null || !conf.allowsPreciseHeading || session.atKaaba) {
            val key = "approx"
            if (lastKey != key) {
                lastKey = key
                host.announceForAccessibility(ctx.getString(R.string.a11y_approx))
            }
            return
        }
        val turn = session.turnToQibla()?.value ?: 0.0
        val absT = abs(turn)
        val aligned = HeadingPolicy.isAligned(absT, wasAligned)
        wasAligned = aligned
        val key = when {
            aligned -> "aligned"
            turn > 0 -> "R${absT.toInt().coerceAtLeast(1)}"
            else -> "L${absT.toInt().coerceAtLeast(1)}"
        }
        val prev = lastKey
        val should = when {
            prev == null -> true
            key == "aligned" && prev != "aligned" -> true
            prev == "aligned" && key != "aligned" -> true
            key.startsWith("R") && prev.startsWith("R") ->
                abs(key.drop(1).toInt() - prev.drop(1).toInt()) >= 5
            key.startsWith("L") && prev.startsWith("L") ->
                abs(key.drop(1).toInt() - prev.drop(1).toInt()) >= 5
            else -> key != prev
        }
        if (!should) return
        lastKey = key
        val msg = when {
            key == "aligned" -> ctx.getString(R.string.a11y_aligned)
            key.startsWith("R") -> ctx.getString(R.string.a11y_turn_right, key.drop(1).toInt())
            else -> ctx.getString(R.string.a11y_turn_left, key.drop(1).toInt())
        }
        host.announceForAccessibility(msg)
    }
}
