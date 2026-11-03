package com.sajda.compass

import android.util.Log

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.sajda.geo.DeclinationProvider
import com.sajda.geo.GeoMath
import com.sajda.geo.TrueHeading
import kotlin.math.sqrt

/**
 * Lifecycle-bound compass using Android's fused rotation vector when available.
 *
 * Confidence is derived from whichever sensor actually feeds the heading:
 * raw magnetometer accuracy for the legacy path, rotation-vector accuracy
 * when the raw magnetic field is not exposed by the device.
 */
class CompassEngine(
    context: Context,
    private val declinationProvider: DeclinationProvider,
    private val onReading: (CompassReading) -> Unit
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val gravity = FloatArray(3)
    private val magneticField = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val inclinationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var hasGravity = false
    private var hasMagneticField = false
    private var magneticAccuracy = -1 // unknown until OEM reports
    private var rotationVectorAccuracy = -1 // unknown until first accuracy event
    private var magneticAccuracyAt = 0L
    private var rotationVectorAccuracyAt = 0L
    private var activeHeadingSource = HeadingSource.NONE
    private val filter = HeadingFilter()

    @Volatile private var lat = 0.0
    @Volatile private var lon = 0.0
    @Volatile private var alt = 0.0
    @Volatile private var cachedDeclination = 0.0
    @Volatile private var declinationUpdatedAt = 0L
    @Volatile private var declinationAvailable = false
    private var registered = false
    private var startAttempted = false

    val registrationFailed: Boolean get() = startAttempted && !registered
    val usingRotationVector: Boolean get() = rotation != null
    val isAvailable: Boolean get() = rotation != null || (accelerometer != null && magnetometer != null)

    fun setLocation(latDeg: Double, lonDeg: Double, altMeters: Double = 0.0) {
        if (!latDeg.isFinite() || !lonDeg.isFinite() || latDeg !in -90.0..90.0 || lonDeg !in -180.0..180.0) return
        lat = latDeg
        lon = lonDeg
        alt = altMeters.takeIf { it.isFinite() } ?: 0.0
        refreshDeclination(force = true)
    }

    private fun refreshDeclination(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - declinationUpdatedAt < DECLINATION_REFRESH_MS) return
        try {
            val value = declinationProvider.declination(lat, lon, alt, now)
            if (value.isFinite()) {
                cachedDeclination = value
                declinationAvailable = true
            } else {
                cachedDeclination = 0.0
                declinationAvailable = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "declination failed", e)
            cachedDeclination = 0.0
            declinationAvailable = false
        }
        declinationUpdatedAt = now
    }

    fun start() {
        if (registered) return
        startAttempted = true
        if (!isAvailable) return
        val rotationRegistered = rotation?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } == true
        var anyRegistered = rotationRegistered
        if (rotationRegistered) {
            // Raw magnetic data is also registered so interference can be detected
            // independently of the fused heading sensor.
            magnetometer?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        } else if (accelerometer != null && magnetometer != null) {
            val accelOk = sm.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            val magOk = sm.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
            anyRegistered = accelOk && magOk
            if (!anyRegistered) sm.unregisterListener(this)
            if (anyRegistered) activeHeadingSource = HeadingSource.ACCELEROMETER_MAGNETOMETER
        }
        if (rotationRegistered) activeHeadingSource = HeadingSource.ROTATION_VECTOR
        registered = anyRegistered
        if (!anyRegistered) activeHeadingSource = HeadingSource.NONE
    }

    fun stop() {
        if (registered) sm.unregisterListener(this)
        registered = false
        activeHeadingSource = HeadingSource.NONE
        filter.reset()
        hasGravity = false
        hasMagneticField = false
        magneticAccuracy = -1
        rotationVectorAccuracy = -1
        magneticAccuracyAt = 0L
        rotationVectorAccuracyAt = 0L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val now = System.currentTimeMillis()
        when (sensor?.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticAccuracy = accuracy
                magneticAccuracyAt = now
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                rotationVectorAccuracy = accuracy
                rotationVectorAccuracyAt = now
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                try {
                    if (!isFiniteVector(event.values)) return
                    if (event.accuracy >= 0) {
                        rotationVectorAccuracy = event.accuracy
                        rotationVectorAccuracyAt = System.currentTimeMillis()
                    }
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    publishFromRotation(rotationMatrix)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "rotation vector read failed", e)
                    // Ignore malformed sensor frames; never publish fabricated heading.
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (isFiniteVector(event.values, firstThreeOnly = true)) {
                    if (!hasGravity) event.values.copyInto(gravity, endIndex = 3)
                    else lowPass(gravity, event.values)
                    hasGravity = true
                    tryLegacy()
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (event.accuracy >= SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    magneticAccuracy = event.accuracy
                    magneticAccuracyAt = System.currentTimeMillis()
                }
                if (isFiniteVector(event.values, firstThreeOnly = true)) {
                    if (!hasMagneticField) event.values.copyInto(magneticField, endIndex = 3)
                    else lowPass(magneticField, event.values)
                    hasMagneticField = true
                    tryLegacy()
                }
            }
        }
    }

    private fun tryLegacy() {
        if (!hasGravity || !hasMagneticField) return
        if (!SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravity, magneticField)) return
        publishFromRotation(rotationMatrix)
    }

    private fun publishFromRotation(matrix: FloatArray) {
        // Orientation is locked to portrait in the manifest, so no axis remap is needed.
        SensorManager.getOrientation(matrix, orientation)
        val magnetic = GeoMath.normalizeDegrees(Math.toDegrees(orientation[0].toDouble()))
        if (!magnetic.isFinite()) return
        refreshDeclination()
        val trueHeading = TrueHeading.fromMagnetic(magnetic, cachedDeclination).value
        val fieldUt = if (hasMagneticField) {
            sqrt((magneticField[0] * magneticField[0] + magneticField[1] * magneticField[1] +
                magneticField[2] * magneticField[2]).toDouble())
        } else Double.NaN

        val confidence = if (!declinationAvailable) Confidence.LOW
            else classify(fieldUt)
        filter.setConfidence(confidence)
        val smoothed = filter.filter(trueHeading)
        val hint = when {
            !declinationAvailable -> CompassHint.DECLINATION_MISSING
            !hasMagneticField && rotation == null ->
                CompassHint.MAGNETIC_UNAVAILABLE
            hasMagneticField && magneticAccuracy >= 0 &&
                magneticAccuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE ->
                CompassHint.CALIBRATION_NEEDED
            hasMagneticField && fieldUt.isFinite() && (fieldUt < 15.0 || fieldUt > 90.0) ->
                CompassHint.INTERFERENCE
            confidence == Confidence.LOW -> CompassHint.APPROXIMATE
            else -> CompassHint.NONE
        }

        onReading(CompassReading(
            magneticHeadingDeg = magnetic,
            trueHeadingDeg = smoothed,
            confidence = confidence,
            accuracyStatus = if (hasMagneticField) magneticAccuracy else rotationVectorAccuracy,
            hint = hint
        ))
    }

    /**
     * Confidence comes from the sensor that actually drives the heading:
     * raw magnetometer accuracy on the legacy path, rotation-vector accuracy
     * when the device does not expose the raw magnetic field.
     */
    private fun classify(fieldUt: Double): Confidence {
        val now = System.currentTimeMillis()
        // Use the path that actually registered — not merely that the sensor exists.
        if (activeHeadingSource == HeadingSource.ROTATION_VECTOR) {
            val acc = rotationVectorAccuracy
            if (acc <= SensorManager.SENSOR_STATUS_UNRELIABLE && acc >= 0) {
                return Confidence.CALIBRATE
            }
            // Stale only after a long gap once we had a real accuracy sample
            if (rotationVectorAccuracyAt > 0L &&
                now - rotationVectorAccuracyAt > ACCURACY_STALE_MS &&
                acc >= SensorManager.SENSOR_STATUS_ACCURACY_LOW
            ) {
                return Confidence.LOW
            }
            val base = when {
                acc < 0 -> Confidence.MEDIUM // OEM silent on accuracy
                acc >= SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> Confidence.HIGH
                acc >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
            // Extreme field is a soft warning only (hint INTERFERENCE), not permanent CALIBRATE
            if (hasMagneticField && fieldUt.isFinite() && (fieldUt < 15.0 || fieldUt > 90.0)) {
                return minConfidence(base, Confidence.LOW)
            }
            return base
        }
        // Legacy accel+mag path
        val accuracy = magneticAccuracy
        if (accuracy < 0) return Confidence.MEDIUM // wait for samples without nagging figure-eight
        if (accuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE) return Confidence.CALIBRATE
        if (magneticAccuracyAt > 0L && now - magneticAccuracyAt > ACCURACY_STALE_MS) {
            return Confidence.LOW
        }
        if (fieldUt.isFinite() && (fieldUt < 15.0 || fieldUt > 90.0)) return Confidence.LOW
        return when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> Confidence.HIGH
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> Confidence.MEDIUM
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> Confidence.LOW
            else -> Confidence.MEDIUM
        }
    }

    private fun minConfidence(a: Confidence, b: Confidence): Confidence {
        val order = listOf(Confidence.HIGH, Confidence.MEDIUM, Confidence.LOW, Confidence.CALIBRATE)
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }

    private fun isFiniteVector(values: FloatArray, firstThreeOnly: Boolean = false): Boolean {
        if (values.size < 3) return false
        val count = if (firstThreeOnly) 3 else values.size
        for (i in 0 until count) if (!values[i].isFinite()) return false
        return true
    }

    private companion object {
        private const val TAG = "SajdaCompass"
        const val DECLINATION_REFRESH_MS = 24L * 60L * 60L * 1000L
        const val ACCURACY_STALE_MS = 45_000L
    }

    private fun lowPass(out: FloatArray, input: FloatArray, alpha: Float = 0.18f) {
        for (i in out.indices) out[i] = alpha * input[i] + (1f - alpha) * out[i]
    }
}