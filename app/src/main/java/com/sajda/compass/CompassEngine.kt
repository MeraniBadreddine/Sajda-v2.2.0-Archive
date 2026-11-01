package com.sajda.compass

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
    private var magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var rotationVectorAccuracy = -1 // unknown until first accuracy event
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
        } catch (_: Exception) {
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
        }
        registered = anyRegistered
    }

    fun stop() {
        if (registered) sm.unregisterListener(this)
        registered = false
        filter.reset()
        hasGravity = false
        hasMagneticField = false
        magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        rotationVectorAccuracy = -1
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (sensor?.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> magneticAccuracy = accuracy
            Sensor.TYPE_ROTATION_VECTOR -> rotationVectorAccuracy = accuracy
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                try {
                    if (!isFiniteVector(event.values)) return
                    if (event.accuracy >= 0) rotationVectorAccuracy = event.accuracy
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    publishFromRotation(rotationMatrix)
                } catch (_: RuntimeException) {
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
                if (event.accuracy in SensorManager.SENSOR_STATUS_UNRELIABLE..SensorManager.SENSOR_STATUS_ACCURACY_HIGH) {
                    magneticAccuracy = event.accuracy
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
            !declinationAvailable -> "True-north correction unavailable — direction is approximate"
            !hasMagneticField && !usingRotationVector ->
                "Magnetic sensor unavailable — direction cannot be verified"
            hasMagneticField && magneticAccuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE ->
                "Compass calibration needed — move the phone in a figure-eight"
            hasMagneticField && fieldUt.isFinite() && (fieldUt < 20.0 || fieldUt > 70.0) ->
                "Possible magnetic interference — move away from metal or magnets"
            confidence == Confidence.LOW -> "Direction is approximate — hold the phone steady"
            else -> null
        }

        onReading(CompassReading(
            magneticHeadingDeg = magnetic,
            trueHeadingDeg = smoothed,
            confidence = confidence,
            accuracyStatus = if (hasMagneticField) magneticAccuracy else rotationVectorAccuracy,
            interferenceHint = hint
        ))
    }

    /**
     * Confidence comes from the sensor that actually drives the heading:
     * raw magnetometer accuracy on the legacy path, rotation-vector accuracy
     * when the device does not expose the raw magnetic field.
     */
    private fun classify(fieldUt: Double): Confidence {
        val accuracy = if (hasMagneticField) magneticAccuracy else rotationVectorAccuracy
        if (accuracy < 0) return Confidence.CALIBRATE // unknown — never assume accuracy
        if (accuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE) return Confidence.CALIBRATE
        // Field magnitude is a warning heuristic, only meaningful when the raw field is visible.
        if (hasMagneticField && fieldUt.isFinite() && (fieldUt < 20.0 || fieldUt > 70.0)) return Confidence.LOW
        return when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> Confidence.HIGH
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> Confidence.MEDIUM
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> Confidence.LOW
            else -> Confidence.CALIBRATE
        }
    }

    private fun isFiniteVector(values: FloatArray, firstThreeOnly: Boolean = false): Boolean {
        if (values.size < 3) return false
        val count = if (firstThreeOnly) 3 else values.size
        for (i in 0 until count) if (!values[i].isFinite()) return false
        return true
    }

    private companion object {
        const val DECLINATION_REFRESH_MS = 24L * 60L * 60L * 1000L
    }

    private fun lowPass(out: FloatArray, input: FloatArray, alpha: Float = 0.18f) {
        for (i in out.indices) out[i] = alpha * input[i] + (1f - alpha) * out[i]
    }
}