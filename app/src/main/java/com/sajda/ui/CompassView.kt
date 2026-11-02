package com.sajda.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.sajda.geo.GeoMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Pro instrument compass — layered dial, luminous Qibla arc, stable 30fps loop.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity

    private var targetHeading = 0f
    private var displayHeading = 0f
    private var hasHeading = false
    private var targetAlign = 0f
    private var alignPulse = 0f
    private var loopPosted = false
    private var lastFrameNanos = 0L
    private var lastW = 0
    private var lastH = 0

    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.75f); color = Color.parseColor("#78EBEBF5")
    }
    private val outerTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(10f); color = Color.parseColor("#14FFFFFF")
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(0.9f); color = Color.parseColor("#2EEBEBF5")
    }
    private val alignRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val alignHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3.5f); strokeCap = Paint.Cap.ROUND
    }
    private val arcGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(10f); strokeCap = Paint.Cap.ROUND
    }
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
    }
    private val tickFine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#30EBEBF5")
    }
    private val tickMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f); strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#60EBEBF5")
    }
    private val tickMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.2f); strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#C0EBEBF5")
    }
    private val degreePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80EBEBF5"); textAlign = Paint.Align.CENTER
        textSize = sp(10f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0EBEBF5"); textAlign = Paint.Align.CENTER
        textSize = sp(14f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        textSize = sp(16f); typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#E60A84FF")
    }
    private val shaftCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#D0D0ECFF")
    }
    private val qiblaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#FF0A84FF")
    }
    private val qiblaStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.3f); color = Color.parseColor("#B8DCFF")
    }
    private val beadGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF0A84FF") }
    private val beadCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val lubberGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lubber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val hubBlue = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val hubCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#101012") }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(0.7f); color = Color.parseColor("#18EBEBF5")
    }

    private val qiblaPath = Path()
    private val pointerPath = Path()
    private val arcRect = RectF()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            loopPosted = false
            if (!isAttachedToWindow) return
            val dtSec = if (lastFrameNanos == 0L) 1f / 60f
            else ((frameTimeNanos - lastFrameNanos) / 1e9).toFloat().coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTimeNanos
            var more = false
            if (hasHeading) {
                val delta = GeoMath.angleDelta(displayHeading.toDouble(), targetHeading.toDouble())
                if (abs(delta) > 0.02) {
                    val a = (1.0 - Math.exp(-SMOOTHING * dtSec)).coerceIn(0.0, 1.0)
                    displayHeading = GeoMath.normalizeDegrees(displayHeading + delta * a).toFloat()
                    more = true
                } else displayHeading = targetHeading
            }
            val ad = targetAlign - alignPulse
            if (abs(ad) > 0.01f) {
                alignPulse += ad * (1f - Math.exp(-10.0 * dtSec)).toFloat()
                more = true
            } else alignPulse = targetAlign
            invalidate()
            if (more) postLoop()
        }
    }

    private fun postLoop() {
        if (loopPosted || !isAttachedToWindow) return
        loopPosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        loopPosted = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    var deviceHeading: Float = 0f
        set(value) {
            field = value
            targetHeading = value
            if (!hasHeading) {
                displayHeading = value; hasHeading = true; invalidate()
            } else if (abs(GeoMath.angleDelta(displayHeading.toDouble(), value.toDouble())) > 0.05) {
                postLoop()
            }
        }

    var qiblaBearing: Float = 0f
        set(value) { if (field != value) { field = value; invalidate() } }

    var uncertain: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    var alignment: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.01f) return
            field = v; targetAlign = v; postLoop()
        }

    
    /** Drop stale dial orientation when sensors stop or trust is revoked. */
    fun clearDeviceHeading() {
        hasHeading = false
        displayHeading = 0f
        targetHeading = 0f
        targetAlign = 0f
        alignPulse = 0f
        alignment = 0f
        invalidate()
    }

override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val dim = if (uncertain) 0.42f else 1f
        val layer = canvas.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (255 * dim).toInt())
        val cx = w / 2f; val cy = h / 2f
        val r = min(cx, cy) - dp(20f)
        if (r < dp(48f)) { canvas.restoreToCount(layer); return }

        if (w != lastW || h != lastH) {
            lastW = w; lastH = h
            rebuildStatic(cx, cy, r)
        }

        val pulse = alignPulse
        val ambA = (0x14 + (0x30 * pulse)).toInt().coerceIn(0, 255)
        ambientPaint.shader = RadialGradient(
            cx, cy, r + dp(42f),
            intArrayOf(
                Color.argb(ambA, 0x0A, 0x84, 0xFF),
                Color.argb((0x0C + 0x12 * pulse).toInt(), 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r + dp(38f), ambientPaint)

        // Outer track band
        canvas.drawCircle(cx, cy, r - dp(5f), outerTrack)
        canvas.drawCircle(cx, cy, r, discPaint)

        if (pulse > 0.04f) {
            alignRing.strokeWidth = dp(2.2f + 2.5f * pulse)
            alignRing.color = Color.argb((0x30 + 0x90 * pulse).toInt(), 0x0A, 0x84, 0xFF)
            canvas.drawCircle(cx, cy, r - dp(1f), alignRing)
            alignHalo.strokeWidth = dp(8f)
            alignHalo.color = Color.argb((0x18 * pulse).toInt(), 0x0A, 0x84, 0xFF)
            canvas.drawCircle(cx, cy, r + dp(5f), alignHalo)
        }

        canvas.drawCircle(cx, cy, r - dp(0.5f), discEdge)
        canvas.drawCircle(cx, cy, r * 0.68f, ringPaint)
        canvas.drawCircle(cx, cy, r * 0.42f, ringPaint)

        // Quiet crosshair inside
        val cr = r * 0.28f
        canvas.drawLine(cx - cr, cy, cx + cr, cy, crossPaint)
        canvas.drawLine(cx, cy - cr, cx, cy + cr, crossPaint)

        canvas.drawArc(
            cx - r + dp(6f), cy - r + dp(6f),
            cx + r - dp(6f), cy + r - dp(6f),
            200f, 140f, false, specPaint
        )

        val heading = if (hasHeading) displayHeading else 0f
        canvas.save()
        canvas.rotate(-heading, cx, cy)

        for (deg in 0 until 360 step 3) {
            val rad = Math.toRadians(deg.toDouble())
            val major = deg % 30 == 0
            val mid = !major && deg % 15 == 0
            val paint = when {
                major -> tickMajor
                mid -> tickMid
                else -> tickFine
            }
            val outer = r - dp(11f)
            val inner = when {
                major -> r - dp(26f)
                mid -> r - dp(19f)
                else -> r - dp(15f)
            }
            canvas.drawLine(
                cx + outer * sin(rad).toFloat(), cy - outer * cos(rad).toFloat(),
                cx + inner * sin(rad).toFloat(), cy - inner * cos(rad).toFloat(),
                paint
            )
        }

        drawLabel(canvas, cx, cy, r, 0f, "N", northPaint)
        drawLabel(canvas, cx, cy, r, 90f, "E", cardinalPaint)
        drawLabel(canvas, cx, cy, r, 180f, "S", cardinalPaint)
        drawLabel(canvas, cx, cy, r, 270f, "W", cardinalPaint)
        for (deg in 30 until 360 step 30) {
            if (deg % 90 == 0) continue
            drawLabel(canvas, cx, cy, r, deg.toFloat(), deg.toString(), degreePaint)
        }

        drawQiblaArcAndMark(canvas, cx, cy, r, qiblaBearing, pulse)
        canvas.restore()

        drawLubber(canvas, cx, cy, r, pulse)

        hubBlue.color = Color.argb((0x90 + 0x6F * pulse).toInt(), 0x0A, 0x84, 0xFF)
        canvas.drawCircle(cx, cy, dp(8f), hubBlue)
        canvas.drawCircle(cx, cy, dp(5f), hubWhite)
        canvas.drawCircle(cx, cy, dp(2.2f), hubCore)

        canvas.restoreToCount(layer)
    }

    private fun rebuildStatic(cx: Float, cy: Float, r: Float) {
        discPaint.shader = RadialGradient(
            cx, cy - r * 0.3f, r * 1.15f,
            intArrayOf(
                Color.parseColor("#453E3E44"),
                Color.parseColor("#E6101012"),
                Color.parseColor("#FF060608")
            ),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        specPaint.shader = LinearGradient(
            cx - r * 0.6f, cy - r, cx + r * 0.6f, cy - r * 0.2f,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#48FFFFFF"), Color.TRANSPARENT),
            floatArrayOf(0.15f, 0.5f, 0.85f), Shader.TileMode.CLAMP
        )
    }

    /**
     * Luminous arc along the rim centered on Qibla + aircraft head + orbit bead.
     */
    private fun drawQiblaArcAndMark(canvas: Canvas, cx: Float, cy: Float, r: Float, bearing: Float, pulse: Float) {
        val qb = Math.toRadians(bearing.toDouble())
        val s = sin(qb).toFloat()
        val c = cos(qb).toFloat()

        // Sweep arc on outer track (screen coords: 0° = east in Android arcs, so convert)
        // Compass bearing 0 = North, Android arc 0 = East, clockwise. We need: start from bearing.
        val arcRadius = r - dp(5f)
        arcRect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
        // In our rotated canvas, Qibla is at `bearing` from north. Android drawArc: 0=east, clockwise.
        // Angle from positive X: bearing - 90, and we draw a ±18° window.
        val androidAngle = bearing - 90f
        val sweep = 28f + 10f * pulse
        val start = androidAngle - sweep / 2f

        arcGlow.color = Color.argb((0x20 + 0x40 * pulse).toInt(), 0x0A, 0x84, 0xFF)
        arcPaint.color = Color.argb((0xA0 + 0x55 * pulse).toInt(), 0x0A, 0x84, 0xFF)
        canvas.drawArc(arcRect, start, sweep, false, arcGlow)
        canvas.drawArc(arcRect, start, sweep, false, arcPaint)

        // Directional wash
        washPaint.shader = RadialGradient(
            cx + (r * 0.52f) * s, cy - (r * 0.52f) * c, r * 0.48f,
            intArrayOf(
                Color.argb((0x28 + 0x50 * pulse).toInt(), 0x0A, 0x84, 0xFF),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx + (r * 0.48f) * s, cy - (r * 0.48f) * c, r * 0.48f, washPaint)

        // Shaft
        shaftPaint.strokeWidth = dp(2.2f + pulse)
        val inner = dp(10f)
        val outer = r - dp(28f)
        canvas.drawLine(cx + inner * s, cy - inner * c, cx + outer * s, cy - outer * c, shaftPaint)
        canvas.drawLine(cx + inner * s, cy - inner * c, cx + outer * s, cy - outer * c, shaftCore)

        // Head — dual-fin aircraft
        val tip = r - dp(4f)
        val neck = r - dp(17f)
        val wing = r - dp(30f)
        val halfW = Math.toRadians(8.0 + 1.8 * pulse)
        val halfN = Math.toRadians(3.0)
        qiblaPath.reset()
        qiblaPath.moveTo(cx + tip * s, cy - tip * c)
        qiblaPath.lineTo(cx + neck * sin(qb - halfN).toFloat(), cy - neck * cos(qb - halfN).toFloat())
        qiblaPath.lineTo(cx + wing * sin(qb - halfW).toFloat(), cy - wing * cos(qb - halfW).toFloat())
        qiblaPath.lineTo(cx + (wing - dp(5f)) * s, cy - (wing - dp(5f)) * c)
        qiblaPath.lineTo(cx + wing * sin(qb + halfW).toFloat(), cy - wing * cos(qb + halfW).toFloat())
        qiblaPath.lineTo(cx + neck * sin(qb + halfN).toFloat(), cy - neck * cos(qb + halfN).toFloat())
        qiblaPath.close()
        canvas.drawPath(qiblaPath, qiblaFill)
        canvas.drawPath(qiblaPath, qiblaStroke)

        // Orbit bead
        val beadR = r + dp(12f)
        beadGlow.color = Color.argb((0x40 + 0x50 * pulse).toInt(), 0x0A, 0x84, 0xFF)
        canvas.drawCircle(cx + beadR * s, cy - beadR * c, dp(7f + 2.5f * pulse), beadGlow)
        canvas.drawCircle(cx + beadR * s, cy - beadR * c, dp(3.6f), bead)
        canvas.drawCircle(cx + beadR * s, cy - beadR * c, dp(1.4f), beadCore)
    }

    private fun drawLubber(canvas: Canvas, cx: Float, cy: Float, r: Float, pulse: Float) {
        val tipY = cy - r - dp(3f)
        lubberGlow.color = Color.argb((0x28 + 0x50 * pulse).toInt(), 0x0A, 0x84, 0xFF)
        canvas.drawCircle(cx, tipY + dp(7f), dp(11f + 5f * pulse), lubberGlow)
        pointerPath.reset()
        pointerPath.moveTo(cx, tipY)
        pointerPath.lineTo(cx - dp(6.5f), tipY + dp(13f))
        pointerPath.lineTo(cx, tipY + dp(9.5f))
        pointerPath.lineTo(cx + dp(6.5f), tipY + dp(13f))
        pointerPath.close()
        lubber.color = if (pulse > 0.85f) Color.parseColor("#0A84FF") else Color.WHITE
        canvas.drawPath(pointerPath, lubber)
    }

    private fun drawLabel(
        canvas: Canvas, cx: Float, cy: Float, r: Float, deg: Float, label: String, paint: Paint
    ) {
        val rad = Math.toRadians(deg.toDouble())
        val tr = r - dp(36f)
        val x = cx + tr * sin(rad).toFloat()
        val y = cy - tr * cos(rad).toFloat() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, x, y, paint)
    }

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * scaledDensity

    private companion object { const val SMOOTHING = 11.0 }
}
