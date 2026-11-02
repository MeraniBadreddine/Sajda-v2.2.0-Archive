package com.sajda.ui

import com.sajda.BuildConfig
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sajda.R
import com.sajda.compass.AndroidDeclination
import com.sajda.compass.CompassEngine
import com.sajda.compass.Confidence
import com.sajda.compass.CompassHint
import com.sajda.data.LocationStore
import com.sajda.data.OfflineCities
import com.sajda.geo.GeoPoint
import com.sajda.geo.MagneticHeadingDeg
import com.sajda.geo.QiblaSession
import com.sajda.geo.TrueHeadingDeg
import com.sajda.location.LocationController
import com.sajda.session.HeadingPolicy
import com.sajda.session.SessionRepository
import kotlin.math.abs
import java.util.Locale

/**
 * UI shell: binds sensors + location + session to the compass screen.
 * GPS plumbing lives in [LocationController]; session in [SessionRepository].
 */
class MainActivity : AppCompatActivity(), LocationController.Listener {

    private lateinit var compassView: CompassView
    private lateinit var degreeText: TextView
    private lateinit var confidenceText: TextView
    private lateinit var locationText: TextView
    private lateinit var hintText: TextView
    private lateinit var deltaText: TextView
    private lateinit var calibrateBanner: TextView
    private lateinit var locationPill: TextView
    private lateinit var alignHaptic: AlignHaptic

    private lateinit var sessions: SessionRepository
    private lateinit var locations: LocationController

    private var isResumed = false
    private var lastUiHeadingAt = 0L
    private var lastUiHeading = Float.NaN
    private var permissionDeniedPermanently = false
    private var usingCityFallback = false
    private var lastA11yKey: String? = null

    private val compass: CompassEngine by lazy {
        CompassEngine(this, AndroidDeclination()) { reading ->
            runOnUiThread {
                if (isFinishing || isDestroyed || !isResumed) return@runOnUiThread
                val h = reading.trueHeadingDeg.toFloat()
                val now = System.currentTimeMillis()
                if (!lastUiHeading.isNaN() &&
                    abs(h - lastUiHeading) < 0.2f &&
                    now - lastUiHeadingAt < 33L
                ) return@runOnUiThread
                lastUiHeading = h
                lastUiHeadingAt = now
                sessions.updateHeading(
                    TrueHeadingDeg(reading.trueHeadingDeg),
                    MagneticHeadingDeg(reading.magneticHeadingDeg),
                    reading.confidence,
                    reading.hint
                )
                renderSession()
            }
        }
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) {
            permissionDeniedPermanently = false
            locations.startSearch()
        } else {
            permissionDeniedPermanently =
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
            showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
            Toast.makeText(this, R.string.permission_rationale, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        sessions = SessionRepository(LocationStore(this))
        locations = LocationController(this, this)

        compassView = findViewById(R.id.compass)
        degreeText = findViewById(R.id.degreeText)
        confidenceText = findViewById(R.id.confidenceText)
        locationText = findViewById(R.id.locationText)
        hintText = findViewById(R.id.hintText)
        deltaText = findViewById(R.id.deltaText)
        calibrateBanner = findViewById(R.id.calibrateBanner)
        locationPill = findViewById(R.id.locationPill)
        locationPill.setOnClickListener { onLocationPillClick() }
        alignHaptic = AlignHaptic(this)

        findViewById<View>(R.id.btnInfoWrap).setOnClickListener { showInfo() }
        findViewById<View>(R.id.btnInfoWrap).contentDescription = getString(R.string.about_title)

        sessions.loadCachedIfAny()?.let { cached ->
            sessions.applyPoint(
                GeoPoint(cached.lat, cached.lon),
                provisional = true,
                fixTimeMillis = cached.fixTimeMillis
            )
            compass.setLocation(cached.lat, cached.lon)
        }
        renderSession()
        updateLocationPillFromSession()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        if (sessions.session != null) {
            compass.start()
            renderSession() // redraw after pause cleared heading trust
        }
        ensureLocation()
    }

    override fun onPause() {
        isResumed = false
        mainHandlerRemoveHide()
        // Invalidate in-flight location so late fixes cannot mutate UI after leave
        locations.stop(invalidate = true)
        compass.stop()
        sessions.clearHeadingTrust()
        compassView.clearDeviceHeading()
        alignHaptic.reset()
        super.onPause()
    }

    override fun onDestroy() {
        locations.stop(invalidate = true)
        compass.stop()
        super.onDestroy()
    }

    // ── LocationController.Listener ───────────────────────────────

    override fun onLocationFixed(location: Location, provisional: Boolean) {
        usingCityFallback = false
        sessions.applyPoint(
            GeoPoint(location.latitude, location.longitude),
            provisional = provisional,
            fixTimeMillis = location.time
        )
        compass.setLocation(location.latitude, location.longitude)
        if (isResumed) compass.start()
        renderSession()
        updateLocationPillFromSession()
    }

    override fun onLocationProgress() {
        if (sessions.session == null) {
            showPill(PillMode.LOCATING, R.string.locating, action = false)
        }
    }

    override fun onLocationTimeout() {
        if (sessions.session == null) {
            showPill(PillMode.CACHED, R.string.location_timeout, action = true)
            Toast.makeText(this, R.string.location_timeout, Toast.LENGTH_LONG).show()
            showCityPicker()
        } else {
            renderSession()
            updateLocationPillFromSession()
        }
    }

    override fun onLocationUnavailable() {
        if (!locations.hasPermission()) {
            showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
        } else {
            showPill(PillMode.NEED_SYSTEM, R.string.location_off_pill, action = true)
        }
    }

    private var permissionPreShown = false

    private fun ensureLocation() {
        if (!locations.hasPermission()) {
            showPermissionPreSheet()
            return
        }
        if (!locations.isSystemLocationEnabled()) {
            if (sessions.session == null) promptEnableSystemLocation()
            return
        }
        val shouldRefresh = sessions.session == null ||
            sessions.provisionalLocation ||
            System.currentTimeMillis() - sessions.lastFreshLocationAt > LocationController.LOCATION_REFRESH_INTERVAL_MS
        if (shouldRefresh && !locations.isLocating) locations.startSearch()
    }

    private fun promptEnableSystemLocation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_off_title)
            .setMessage(R.string.location_off_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {}
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptAppPermissionSettings() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_permission_denied)
            .setMessage(R.string.permission_rationale)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                    )
                } catch (_: Exception) {}
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderSession() {
        val s = sessions.session ?: return

        degreeText.text = String.format(Locale.US, "%.1f°", s.qibla.value)
        degreeText.contentDescription = getString(R.string.degree_accessibility, degreeText.text)
        degreeText.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        compassView.qiblaBearing = s.qibla.value.toFloat()
        compassView.contentDescription = degreeText.contentDescription

        val heading = s.trueHeading
        compassView.uncertain = heading == null || !s.confidence.allowsPreciseHeading
        if (heading != null) compassView.deviceHeading = heading.value.toFloat()

        if (!compass.isAvailable || compass.registrationFailed) {
            compassView.uncertain = true
            compassView.alignment = 0f
            degreeText.setTextColor(ContextCompat.getColor(this, R.color.label_primary))
            deltaText.text = getString(R.string.heading_approximate)
            calibrateBanner.visibility = View.GONE
            confidenceText.visibility = View.VISIBLE
            confidenceText.text = getString(R.string.conf_calibrate)
            confidenceText.setTextColor(ContextCompat.getColor(this, R.color.conf_low))
            hintText.text = getString(R.string.sensor_unavailable)
            hintText.visibility = View.VISIBLE
            updateLocationPillFromSession()
            return
        }

        if (!HeadingPolicy.shouldShowPreciseGuidance(s.confidence, heading != null)) {
            alignHaptic.reset()
            compassView.alignment = 0f
            degreeText.setTextColor(ContextCompat.getColor(this, R.color.label_primary))
            deltaText.text = when {
                s.confidence.needsCalibration -> getString(R.string.calibrate_guidance)
                else -> getString(R.string.heading_approximate)
            }
            calibrateBanner.visibility =
                if (HeadingPolicy.shouldShowCalibrateBanner(s.confidence)) View.VISIBLE else View.GONE
            if (s.confidence.needsCalibration) {
                calibrateBanner.text = getString(R.string.calibrate_banner)
            }
        } else {
            calibrateBanner.visibility = View.GONE
            val turn = s.turnToQibla()?.value ?: 0.0
            val turnAbs = abs(turn)
            alignHaptic.onTurn(turnAbs, threshold = 2.0)
            compassView.alignment = HeadingPolicy.alignmentFactor(turnAbs)
            if (HeadingPolicy.isAligned(turnAbs)) {
                degreeText.setTextColor(ContextCompat.getColor(this, R.color.accent))
                deltaText.text = getString(R.string.aligned)
            } else {
                degreeText.setTextColor(ContextCompat.getColor(this, R.color.label_primary))
                val deg = turnAbs.toInt().coerceAtLeast(1)
                deltaText.text = when (HeadingPolicy.turnInstruction(turn)) {
                    HeadingPolicy.TurnInstruction.RIGHT -> getString(R.string.turn_degrees, deg, getString(R.string.direction_right))
                    HeadingPolicy.TurnInstruction.LEFT -> getString(R.string.turn_degrees, deg, getString(R.string.direction_left))
                    HeadingPolicy.TurnInstruction.ALIGNED -> getString(R.string.aligned)
                }
            }
        }

        when (s.confidence) {
            Confidence.HIGH -> confidenceText.visibility = View.GONE
            else -> {
                confidenceText.visibility = View.VISIBLE
                confidenceText.text = when (s.confidence) {
                    Confidence.MEDIUM -> "●  " + getString(R.string.conf_medium)
                    Confidence.LOW -> "●  " + getString(R.string.conf_low)
                    Confidence.CALIBRATE -> "●  " + getString(R.string.conf_calibrate)
                    Confidence.HIGH -> ""
                }
                confidenceText.setTextColor(
                    ContextCompat.getColor(
                        this,
                        when (s.confidence) {
                            Confidence.MEDIUM -> R.color.conf_medium
                            Confidence.LOW, Confidence.CALIBRATE -> R.color.conf_low
                            Confidence.HIGH -> R.color.conf_high
                        }
                    )
                )
            }
        }

        val hint = s.interferenceHint
        val hintStr = CompassHintMessages.resolve(this, hint)
        hintText.text = hintStr ?: ""
        hintText.visibility = if (hintStr != null) View.VISIBLE else View.GONE
        updateLocationPillFromSession()
        announceGuidance()
        announceLiveTurn(s)
    }

    // ── Location pill (same smart states as 1.4+) ─────────────────

    private enum class PillMode { HIDDEN, LOCATING, CACHED, READY_FADE, NEED_PERMISSION, NEED_SYSTEM }

    private var pillMode = PillMode.HIDDEN
    private var readyDismissed = false
    private var lastProvisional: Boolean? = null
    private val hideReadyRunnable = Runnable {
        if (pillMode != PillMode.READY_FADE) return@Runnable
        locationPill.animate().cancel()
        locationPill.animate().alpha(0f).setDuration(320).withEndAction {
            if (pillMode == PillMode.READY_FADE) {
                locationPill.visibility = View.GONE
                locationPill.alpha = 1f
                pillMode = PillMode.HIDDEN
                readyDismissed = true
            }
        }.start()
    }

    private fun mainHandlerRemoveHide() {
        locationPill.handler?.removeCallbacks(hideReadyRunnable)
    }

    private fun onLocationPillClick() {
        when (pillMode) {
            PillMode.NEED_PERMISSION -> {
                if (permissionDeniedPermanently) promptAppPermissionSettings()
                else ensureLocation()
            }
            PillMode.NEED_SYSTEM -> promptEnableSystemLocation()
            PillMode.CACHED -> {
                if (usingCityFallback || sessions.session == null) showCityPicker()
                else if (!locations.isLocating) locations.startSearch()
            }
            else -> Unit
        }
    }

    private fun showPill(mode: PillMode, textRes: Int, action: Boolean) {
        if (mode == pillMode && mode != PillMode.LOCATING) return
        mainHandlerRemoveHide()
        locationPill.animate().cancel()
        pillMode = mode
        if (mode == PillMode.HIDDEN) {
            locationPill.visibility = View.GONE
            return
        }
        locationPill.alpha = 1f
        locationPill.visibility = View.VISIBLE
        locationPill.setText(textRes)
        locationText.setText(textRes)
        locationPill.setBackgroundResource(
            if (action) R.drawable.bg_liquid_glass_action else R.drawable.bg_liquid_glass_pill
        )
        locationPill.setTextColor(
            ContextCompat.getColor(this, if (action) android.R.color.white else R.color.label_secondary)
        )
        locationPill.isClickable = action || mode == PillMode.CACHED
        if (mode == PillMode.READY_FADE) {
            locationPill.postDelayed(hideReadyRunnable, 2200L)
        }
    }

    private fun updateLocationPillFromSession() {
        val s = sessions.session
        when {
            !locations.hasPermission() -> {
                readyDismissed = false; lastProvisional = null
                showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
            }
            !locations.isSystemLocationEnabled() -> {
                readyDismissed = false; lastProvisional = null
                showPill(PillMode.NEED_SYSTEM, R.string.location_off_pill, action = true)
            }
            s == null -> {
                readyDismissed = false
                showPill(PillMode.LOCATING, R.string.locating, action = false)
            }
            s.locationProvisional -> {
                readyDismissed = false; lastProvisional = true
                showPill(PillMode.CACHED, R.string.location_cached, action = false)
            }
            else -> {
                val wasProv = lastProvisional
                lastProvisional = false
                if (readyDismissed && pillMode == PillMode.HIDDEN) return
                if (wasProv == true || pillMode == PillMode.LOCATING || pillMode == PillMode.CACHED ||
                    pillMode == PillMode.NEED_PERMISSION || pillMode == PillMode.NEED_SYSTEM ||
                    (pillMode == PillMode.HIDDEN && !readyDismissed) ||
                    pillMode == PillMode.READY_FADE
                ) {
                    if (pillMode != PillMode.READY_FADE) {
                        showPill(PillMode.READY_FADE, R.string.location_ready, action = false)
                    }
                }
            }
        }
    }




    private fun announceGuidance() {
        deltaText.contentDescription = getString(R.string.delta_accessibility, deltaText.text)
        confidenceText.contentDescription = getString(
            R.string.confidence_accessibility,
            confidenceText.text.ifEmpty { getString(R.string.conf_high) }
        )
        locationPill.contentDescription = getString(
            R.string.location_pill_accessibility,
            locationPill.text
        )
    }

    /** Speak only on meaningful changes — avoids TalkBack spam. */
    private fun announceLiveTurn(s: com.sajda.geo.QiblaSession) {
        val heading = s.trueHeading
        if (heading == null || !s.confidence.allowsPreciseHeading) {
            val key = "approx"
            if (lastA11yKey != key) {
                lastA11yKey = key
                compassView.announceForAccessibility(getString(R.string.a11y_approx))
            }
            return
        }
        val turn = s.turnToQibla()?.value ?: 0.0
        val absT = kotlin.math.abs(turn)
        val key = when {
            absT < 2.0 -> "aligned"
            turn > 0 -> "R${absT.toInt().coerceAtLeast(1)}"
            else -> "L${absT.toInt().coerceAtLeast(1)}"
        }
        // Coalesce small changes: only re-announce when bucket changes by >= 5° or align state flips
        val prev = lastA11yKey
        val should = when {
            prev == null -> true
            key == "aligned" && prev != "aligned" -> true
            prev == "aligned" && key != "aligned" -> true
            key.startsWith("R") && prev.startsWith("R") ->
                kotlin.math.abs(key.drop(1).toInt() - prev.drop(1).toInt()) >= 5
            key.startsWith("L") && prev.startsWith("L") ->
                kotlin.math.abs(key.drop(1).toInt() - prev.drop(1).toInt()) >= 5
            else -> key != prev
        }
        if (!should) return
        lastA11yKey = key
        val msg = when {
            key == "aligned" -> getString(R.string.a11y_aligned)
            key.startsWith("R") -> getString(R.string.a11y_turn_right, key.drop(1).toInt())
            else -> getString(R.string.a11y_turn_left, key.drop(1).toInt())
        }
        compassView.announceForAccessibility(msg)
    }

    private fun showCityPicker() {
        val labels = OfflineCities.all.map { city ->
            val resId = resources.getIdentifier("city_${city.nameResKey}", "string", packageName)
            if (resId != 0) getString(resId) else city.nameResKey
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_city)
            .setItems(labels) { _, which ->
                val city = OfflineCities.all[which]
                usingCityFallback = true
                sessions.applyPoint(city.point(), provisional = true, fixTimeMillis = System.currentTimeMillis())
                compass.setLocation(city.lat, city.lon)
                if (isResumed) compass.start()
                renderSession()
                showPill(PillMode.CACHED, R.string.city_approx_warning, action = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    private fun showPermissionPreSheet() {
        if (permissionPreShown) {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        permissionPreShown = true
        val view = layoutInflater.inflate(R.layout.dialog_permission_pre, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        LiquidGlass.applyDialogBackdrop(dialog)
        view.findViewById<TextView>(R.id.permContinue).setOnClickListener {
            dialog.dismiss()
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        view.findViewById<TextView>(R.id.permNotNow).setOnClickListener {
            dialog.dismiss()
            showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
        }
        dialog.show()
    }




    private fun showInfo() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: BuildConfig.VERSION_NAME
        } catch (_: Exception) {
            BuildConfig.VERSION_NAME
        }
        view.findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.about_version, versionName)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()
        LiquidGlass.applyDialogBackdrop(dialog)
        view.findViewById<TextView>(R.id.aboutOk).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
