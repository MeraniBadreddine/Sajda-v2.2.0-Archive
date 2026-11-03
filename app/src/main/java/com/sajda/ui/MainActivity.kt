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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sajda.R
import com.sajda.compass.AndroidDeclination
import com.sajda.compass.CompassEngine
import com.sajda.compass.Confidence
import com.sajda.compass.CompassHint
import com.sajda.data.LocationStore
import com.sajda.data.OfflineCities
import com.sajda.data.LocationFailure
import com.sajda.data.LocationSource
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
    private lateinit var a11y: AccessibilityAnnouncer
    private lateinit var degreeText: TextView
    private lateinit var confidenceText: TextView
    private lateinit var hintText: TextView
    private lateinit var deltaText: TextView
    private lateinit var calibrateBanner: TextView
    private lateinit var locationPill: TextView
    private lateinit var pillController: PillController
    private lateinit var alignHaptic: AlignHaptic

    private lateinit var sessions: SessionRepository
    private lateinit var locations: LocationController

    private var isResumed = false
    private var lastUiHeadingAt = 0L
    private var lastUiHeading = Float.NaN
    private var permissionDeniedPermanently = false
    private var locationPermState = LocationPermState.UNKNOWN
    private var sensorHasDelivered = false
    private var guidanceWasAligned = false
    private var firstFixEscape: Runnable? = null
    private var lastConfKey: String? = null
    private var lastHintKey: String? = null
    private var lastDeltaKey: String? = null
    private var lastDegreeKey: String? = null
    private var usingCityFallback = false
    private var readyDismissed = false
    private var lastProvisional: Boolean? = null
    private var calibrateSince = 0L

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
                sensorHasDelivered = true
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
            locationPermState = LocationPermState.GRANTED
            locations.startSearch()
        } else {
            permissionDeniedPermanently =
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
            locationPermState = if (permissionDeniedPermanently)
                LocationPermState.DENIED_PERMANENT else LocationPermState.DENIED
            showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
            if (sessions.session == null) showCityPicker()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        applySystemBarInsets()

        sessions = SessionRepository(LocationStore(this))
        locations = LocationController(this, this)

        compassView = findViewById(R.id.compass)
        a11y = AccessibilityAnnouncer(compassView)
        degreeText = findViewById(R.id.degreeText)
        confidenceText = findViewById(R.id.confidenceText)
        hintText = findViewById(R.id.hintText)
        deltaText = findViewById(R.id.deltaText)
        calibrateBanner = findViewById(R.id.calibrateBanner)
        locationPill = findViewById(R.id.locationPill)
        locationPill.setOnClickListener { onLocationPillClick() }
        pillController = PillController(locationPill) { readyDismissed = true }
        alignHaptic = AlignHaptic(this)

        findViewById<View>(R.id.btnInfoWrap).setOnClickListener { showInfo() }
        findViewById<View>(R.id.btnInfoWrap).contentDescription = getString(R.string.about_title)

        sessions.loadCachedIfAny()?.let { cached ->
            if (cached.source == LocationSource.MANUAL_CITY) usingCityFallback = true
            sessions.applyPoint(
                GeoPoint(cached.lat, cached.lon),
                provisional = true,
                fixTimeMillis = cached.fixTimeMillis,
                source = cached.source
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
            renderSession()
        }
        // GPS priority: if location is available, try live fix (even after city fallback)
        if (locations.hasPermission() && locations.isSystemLocationEnabled()) {
            if (isCityMode() || sessions.session == null || sessions.provisionalLocation) {
                if (!locations.isLocating) locations.startSearch()
            }
        }
        ensureLocation()
        updateLocationPillFromSession()
    }

    override fun onPause() {
        isResumed = false
        cancelFirstFixEscape()
        mainHandlerRemoveHide()
        // Invalidate in-flight location so late fixes cannot mutate UI after leave
        locations.stop(invalidate = true)
        compass.stop()
        sessions.clearHeadingTrust()
        sensorHasDelivered = false
        guidanceWasAligned = false
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

    override fun onLocationFixed(location: Location, provisional: Boolean, source: LocationSource) {
        usingCityFallback = source == LocationSource.MANUAL_CITY
        cancelFirstFixEscape()
        sessions.applyPoint(
            GeoPoint(location.latitude, location.longitude),
            provisional = provisional,
            fixTimeMillis = location.time,
            source = source
        )
        compass.setLocation(location.latitude, location.longitude)
        if (isResumed) compass.start()
        renderSession()
        updateLocationPillFromSession()
    }

    override fun onLocationProgress() {
        if (sessions.session == null) {
            showPill(PillMode.LOCATING, R.string.locating, action = true)
            scheduleFirstFixEscape()
        }
    }

    private fun scheduleFirstFixEscape() {
        firstFixEscape?.let { locationPill.handler?.removeCallbacks(it) }
        val r = Runnable {
            if (sessions.session == null && locations.isLocating) {
                showPill(PillMode.LOCATING, R.string.locating_tap_city, action = true)
            }
        }
        firstFixEscape = r
        locationPill.handler?.postDelayed(r, 8_000L)
    }

    private fun cancelFirstFixEscape() {
        firstFixEscape?.let { locationPill.handler?.removeCallbacks(it) }
        firstFixEscape = null
    }


    override fun onLocationFailed(reason: LocationFailure) {
        when (reason) {
            LocationFailure.PERMISSION_DENIED -> {
                showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
                // Manual city needs no GPS permission
                if (sessions.session == null) showCityPicker()
            }
            LocationFailure.LOCATION_DISABLED ->
                showPill(PillMode.NEED_SYSTEM, R.string.location_off_pill, action = true)
            LocationFailure.PROVIDERS_UNAVAILABLE -> {
                if (sessions.session != null) {
                    showPill(PillMode.CACHED, R.string.location_cached, action = true)
                    renderSession()
                } else {
                    showPill(PillMode.CACHED, R.string.location_providers_unavailable, action = true)
                    showCityPicker()
                }
            }
            LocationFailure.TIMEOUT -> {
                if (sessions.session != null) {
                    // Keep working Qibla; soft status only
                    showPill(PillMode.CACHED, R.string.location_cached, action = true)
                    renderSession()
                } else {
                    showPill(PillMode.CACHED, R.string.location_timeout, action = true)
                    if (locations.hasPermission()) showCityPicker()
                }
            }
        }
    }


    /** Keep title + About below status bar / cutout; away from nav bar. */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (10 * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                padH + bars.left,
                padV + bars.top,
                padH + bars.right,
                padV + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun ensureLocation() {
        // Already granted
        if (locations.hasPermission()) {
            locationPermState = LocationPermState.GRANTED
            if (!locations.isSystemLocationEnabled()) {
                updateLocationPillFromSession()
                if (sessions.session == null) promptEnableSystemLocation()
                return
            }
            // GPS is priority: search when no session, provisional, city mode, or stale
            val shouldRefresh = sessions.session == null ||
                sessions.provisionalLocation ||
                isCityMode() ||
                System.currentTimeMillis() - sessions.lastFreshLocationAt >
                    LocationController.LOCATION_REFRESH_INTERVAL_MS
            if (shouldRefresh && !locations.isLocating) {
                locations.startSearch()
            }
            return
        }
        // City-only: keep session, but if user later grants system location we still can upgrade via pill
        if (locationPermState == LocationPermState.CITY_ONLY && sessions.session != null) {
            updateLocationPillFromSession()
            return
        }
        // Denied — pill only, no automatic system dialog
        if (locationPermState == LocationPermState.DENIED ||
            locationPermState == LocationPermState.DENIED_PERMANENT
        ) {
            if (sessions.session == null) {
                showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
            }
            return
        }
        // First time / unknown — one pre-sheet only
        if (locationPermState == LocationPermState.UNKNOWN) {
            showPermissionPreSheet()
        }
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

        val degKey = String.format(Locale.US, "%.0f°", s.qibla.value)
        if (degKey != lastDegreeKey) {
            lastDegreeKey = degKey
            degreeText.text = degKey
        }
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
            // Debounce: brief UNRELIABLE blips must not flash figure-eight constantly
            val needCal = HeadingPolicy.shouldShowCalibrateBanner(s.confidence, sensorHasDelivered)
            val now = System.currentTimeMillis()
            if (needCal) {
                if (calibrateSince == 0L) calibrateSince = now
                val sustained = now - calibrateSince >= 1_500L
                calibrateBanner.visibility = if (sustained) View.VISIBLE else View.GONE
                if (sustained) calibrateBanner.text = getString(R.string.calibrate_banner)
            } else {
                calibrateSince = 0L
                calibrateBanner.visibility = View.GONE
            }
        } else {
            calibrateBanner.visibility = View.GONE
            val turn = s.turnToQibla()?.value ?: 0.0
            val turnAbs = abs(turn)
            alignHaptic.onTurn(turnAbs)
            compassView.alignment = HeadingPolicy.alignmentFactor(turnAbs)
            val alignedNow = HeadingPolicy.isAligned(turnAbs, guidanceWasAligned)
            guidanceWasAligned = alignedNow
            if (alignedNow) {
                degreeText.setTextColor(ContextCompat.getColor(this, R.color.accent))
                deltaText.text = getString(R.string.aligned)
            } else {
                degreeText.setTextColor(ContextCompat.getColor(this, R.color.label_primary))
                val deg = turnAbs.toInt().coerceAtLeast(1)
                val side = HeadingPolicy.turnInstruction(turn, guidanceWasAligned)
                deltaText.text = when (side) {
                    HeadingPolicy.TurnInstruction.RIGHT ->
                        getString(R.string.turn_degrees, deg, getString(R.string.direction_right))
                    HeadingPolicy.TurnInstruction.LEFT ->
                        getString(R.string.turn_degrees, deg, getString(R.string.direction_left))
                    HeadingPolicy.TurnInstruction.ALIGNED -> getString(R.string.aligned)
                }
            }
        }

        when (s.confidence) {
            Confidence.HIGH -> confidenceText.visibility = View.GONE
            else -> {
                confidenceText.visibility = View.VISIBLE
                val confKey = s.confidence.name
                if (confKey != lastConfKey) {
                    lastConfKey = confKey
                    confidenceText.text = when (s.confidence) {
                        Confidence.MEDIUM -> getString(R.string.conf_label_medium)
                        Confidence.LOW -> getString(R.string.conf_label_low)
                        Confidence.CALIBRATE -> getString(R.string.conf_label_calibrate)
                        Confidence.HIGH -> getString(R.string.conf_label_high)
                    }
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
        if (hintStr != null) {
            hintText.visibility = View.VISIBLE
            val color = when (hint) {
                CompassHint.CALIBRATION_NEEDED, CompassHint.INTERFERENCE ->
                    ContextCompat.getColor(this, R.color.conf_low)
                CompassHint.APPROXIMATE, CompassHint.DECLINATION_MISSING ->
                    ContextCompat.getColor(this, R.color.conf_medium)
                else -> ContextCompat.getColor(this, R.color.label_secondary)
            }
            hintText.setTextColor(color)
        } else {
            hintText.visibility = View.GONE
        }
        updateLocationPillFromSession()
        announceGuidance()
        a11y.announce(s, s.confidence)
    }

    // ── Location pill (same smart states as 1.4+) ─────────────────



    private fun mainHandlerRemoveHide() {
        if (::pillController.isInitialized) pillController.cancelHide()
    }


    private fun onLocationPillClick() {
        when (pillMode) {
            PillMode.NEED_PERMISSION -> {
                when (locationPermState) {
                    LocationPermState.DENIED_PERMANENT -> promptAppPermissionSettings()
                    LocationPermState.DENIED -> showDeniedRecoveryDialog()
                    LocationPermState.CITY_ONLY, LocationPermState.PRE_SHOWN -> showCityPicker()
                    else -> {
                        locationPermState = LocationPermState.UNKNOWN
                        showPermissionPreSheet()
                    }
                }
            }
            PillMode.NEED_SYSTEM -> promptEnableSystemLocation()
            PillMode.CACHED -> {
                if (isCityMode()) {
                    showCityOrGpsDialog()
                } else if (sessions.session == null) {
                    showCityPicker()
                } else if (!locations.isLocating) {
                    // Retry live GPS (priority)
                    locations.startSearch()
                    showPill(PillMode.LOCATING, R.string.locating, action = true)
                }
            }
            PillMode.LOCATING -> {
                showCityPicker()
            }
            else -> Unit
        }
    }

    private fun showPill(mode: PillMode, textRes: Int, action: Boolean) {
        pillController.show(mode, textRes, action)
    }

    private val pillMode: PillMode get() = pillController.mode


    private fun isCityMode(): Boolean =
        usingCityFallback ||
            locationPermState == LocationPermState.CITY_ONLY ||
            sessions.lastSource == LocationSource.MANUAL_CITY

    private fun updateLocationPillFromSession() {
        val s = sessions.session
        // Priority: live GPS > enable system location > permission > city fallback
        when {
            // System location OFF — always actionable (even with last-known on screen)
            locations.hasPermission() && !locations.isSystemLocationEnabled() -> {
                readyDismissed = false
                lastProvisional = null
                val res = if (s != null) R.string.location_cached_enable else R.string.location_off_pill
                showPill(PillMode.NEED_SYSTEM, res, action = true)
            }
            // No permission and no working session
            !locations.hasPermission() && s == null -> {
                readyDismissed = false
                lastProvisional = null
                showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
            }
            // City / manual session — offer return to GPS
            s != null && isCityMode() -> {
                readyDismissed = false
                lastProvisional = true
                showPill(PillMode.CACHED, R.string.city_use_gps, action = true)
            }
            // Permission missing but session exists (edge) — treat as city recovery
            !locations.hasPermission() && s != null -> {
                readyDismissed = false
                showPill(PillMode.CACHED, R.string.city_use_gps, action = true)
            }
            // Searching
            s == null -> {
                readyDismissed = false
                showPill(PillMode.LOCATING, R.string.locating, action = true)
            }
            // Provisional last-known while still refining
            sessions.provisionalLocation -> {
                readyDismissed = false
                lastProvisional = true
                showPill(PillMode.CACHED, R.string.location_cached, action = true)
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


    private fun showDeniedRecoveryDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_permission_denied)
            .setMessage(R.string.permission_rationale)
            .setPositiveButton(R.string.permission_allow_location) { _, _ ->
                locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            .setNeutralButton(R.string.permission_choose_city) { _, _ ->
                locationPermState = LocationPermState.CITY_ONLY
                showCityPicker()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    /** After city fallback: prefer returning to live GPS, or change city. */
    private fun showCityOrGpsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_city)
            .setMessage(R.string.city_approx_warning)
            .setPositiveButton(R.string.use_gps) { _, _ ->
                tryUseLiveGps()
            }
            .setNeutralButton(R.string.change_city) { _, _ ->
                showCityPicker()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Clear city mode and start a real location search when possible. */
    private fun tryUseLiveGps() {
        usingCityFallback = false
        if (!locations.hasPermission()) {
            locationPermState = LocationPermState.UNKNOWN
            showPermissionPreSheet()
            return
        }
        locationPermState = LocationPermState.GRANTED
        if (!locations.isSystemLocationEnabled()) {
            promptEnableSystemLocation()
            return
        }
        showPill(PillMode.LOCATING, R.string.locating, action = true)
        if (!locations.isLocating) locations.startSearch()
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
                locationPermState = LocationPermState.CITY_ONLY
                sessions.applyPoint(city.point(), provisional = true, fixTimeMillis = System.currentTimeMillis(), source = LocationSource.MANUAL_CITY)
                compass.setLocation(city.lat, city.lon)
                if (isResumed) compass.start()
                renderSession()
                updateLocationPillFromSession()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    private fun showPermissionPreSheet() {
        if (locationPermState != LocationPermState.UNKNOWN) return
        locationPermState = LocationPermState.PRE_SHOWN
        val view = layoutInflater.inflate(R.layout.dialog_permission_pre, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        LiquidGlass.applyDialogBackdrop(dialog)
        view.findViewById<TextView>(R.id.permContinue).setOnClickListener {
            dialog.dismiss()
            // COARSE is enough for Qibla; FINE is optional if the user grants it system-wide
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        view.findViewById<TextView>(R.id.permNotNow).setOnClickListener {
            dialog.dismiss()
            locationPermState = LocationPermState.DENIED
            showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
        }
        view.findViewById<TextView>(R.id.permChooseCity).setOnClickListener {
            dialog.dismiss()
            locationPermState = LocationPermState.CITY_ONLY
            showCityPicker()
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
