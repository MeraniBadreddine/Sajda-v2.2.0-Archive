package com.sajda.ui

import com.sajda.BuildConfig
import android.Manifest
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.TextView
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
import com.sajda.geo.TrueHeadingDeg
import com.sajda.location.LocationController
import com.sajda.session.HeadingPolicy
import com.sajda.session.LocationTrust
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
    private val locationFlow = LocationFlowState()
    private var sensorHasDelivered = false
    private var guidanceWasAligned = false
    private var firstFixEscape: Runnable? = null
    private var lastConfKey: String? = null
    private var lastDegreeKey: String? = null
    /** True while upgrading a manually selected city to a real device location. */
    private var lastProvisional: Boolean? = null
    private var calibrateSince = 0L
    private var cachedSystemLocationEnabled = false
    private var lastSystemLocationCheckAt = 0L

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
            locationFlow.onPermissionGranted()
            // Re-grant after "Only this time" lapse: resume live search.
            if (isCityMode()) locationFlow.beginGpsUpgradeFromCity()
            showPill(PillMode.LOCATING, R.string.locating, action = true)
            if (locations.isLocating) locations.stop(invalidate = true)
            locations.startSearch()
        } else {
            // Explicit deny from the system sheet only — not a one-time lapse.
            val permanent =
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            locationFlow.onPermissionDenied(permanent = permanent)
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
        // Set this before any location fix so landscape and first-run states
        // are still understandable to TalkBack.
        compassView.contentDescription = getString(R.string.compass_accessibility_static)
        a11y = AccessibilityAnnouncer(compassView)
        degreeText = findViewById(R.id.degreeText)
        confidenceText = findViewById(R.id.confidenceText)
        hintText = findViewById(R.id.hintText)
        deltaText = findViewById(R.id.deltaText)
        calibrateBanner = findViewById(R.id.calibrateBanner)
        locationPill = findViewById(R.id.locationPill)
        locationPill.setOnClickListener { onLocationPillClick() }
        pillController = PillController(locationPill) { locationFlow.markReadyDismissed() }
        alignHaptic = AlignHaptic(this)

        findViewById<View>(R.id.btnInfoWrap).setOnClickListener { showInfo() }
        findViewById<View>(R.id.btnInfoWrap).contentDescription = getString(R.string.about_title)

        sessions.loadCachedIfAny()?.let { cached ->
            if (cached.source == LocationSource.MANUAL_CITY) {
                locationFlow.onCachedManualCityRestored()
            } else if (!locations.hasPermission()) {
                // A previously cached fix must not trigger the first-run permission sheet
                // again after process recreation or a permission revocation.
                locationFlow.markDeniedFromChooser()
            }
            sessions.applyPoint(
                GeoPoint(cached.lat, cached.lon),
                provisional = true,
                fixTimeMillis = cached.fixTimeMillis,
                source = cached.source,
                accuracyMeters = cached.accuracyMeters
            )
            compass.setLocation(cached.lat, cached.lon)
        }
        renderSession()
        updateLocationPillFromSession()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        invalidateSystemLocationCache()
        if (sessions.session != null) {
            compass.start()
            renderSession(updatePill = false)
        }
        // One-time grant ("Only this time") is revoked when the process stops or
        // the app leaves the foreground long enough. Treat as lapse, not denial.
        if (!locations.hasPermission()) {
            locationFlow.onPermissionLapsed()
            ensureLocation()
            updateLocationPillFromSession()
            return
        }
        // City mode: never auto-search. Only the location pill starts GPS.
        if (locations.isSystemLocationEnabled() && !isCityMode()) {
            if (sessions.session == null || sessions.provisionalLocation) {
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
        locationFlow.clearGpsUpgrade()
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
        val liveSource = source == LocationSource.GPS ||
            source == LocationSource.NETWORK ||
            source == LocationSource.FUSED
        // Leave city-only mode only on a real non-provisional provider fix.
        if (!provisional && liveSource) {
            locationFlow.onLiveGpsAccepted()
        } else {
            locationFlow.syncUsingCityFromSource(source == LocationSource.MANUAL_CITY)
            if (!provisional || source == LocationSource.MANUAL_CITY) {
                locationFlow.clearGpsUpgrade()
            }
        }
        cancelFirstFixEscape()
        sessions.applyPoint(
            GeoPoint(location.latitude, location.longitude),
            provisional = provisional,
            fixTimeMillis = location.time,
            source = source,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() }
        )
        compass.setLocation(location.latitude, location.longitude)
        if (isResumed) compass.start()
        renderSession(updatePill = true)
    }

    override fun onLocationProgress() {
        if (sessions.session == null) {
            showPill(PillMode.LOCATING, R.string.locating, action = true)
            scheduleFirstFixEscape()
        } else if (locationFlow.gpsUpgradeRequested) {
            // Keep the existing city Qibla visible while GPS is being acquired.
            showPill(PillMode.LOCATING, R.string.city_searching_gps, action = true)
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
        locationFlow.clearGpsUpgrade()
        val hasSession = sessions.session != null
        val city = isCityMode()
        when (reason) {
            LocationFailure.PERMISSION_DENIED -> {
                showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
                if (!hasSession) showCityPicker()
            }
            LocationFailure.LOCATION_DISABLED ->
                showPill(PillMode.NEED_SYSTEM, R.string.location_off_pill, action = true)
            LocationFailure.PROVIDERS_UNAVAILABLE -> {
                val res = LocationFailureUi.providersUnavailablePillRes(city, hasSession)
                // Do NOT call renderSession here — it ends with updateLocationPillFromSession
                // and would overwrite this explicit failure message with city_use_gps.
                showPill(PillMode.CACHED, res, action = true)
                if (!hasSession) showCityPicker()
            }
            LocationFailure.TIMEOUT -> {
                val res = LocationFailureUi.timeoutPillRes(city, hasSession)
                showPill(PillMode.CACHED, res, action = true)
                if (!hasSession && locations.hasPermission()) showCityPicker()
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
        // Always re-read system location on ensure (stale cache caused silent no-ops).
        invalidateSystemLocationCache()

        if (locations.hasPermission()) {
            // Do NOT call onPermissionGranted() here — that wiped CITY_ONLY and trapped the UI.
            if (!locations.isSystemLocationEnabled()) {
                // System location off: surface NEED_SYSTEM even when a city session exists.
                showPill(
                    PillMode.NEED_SYSTEM,
                    if (sessions.session != null) R.string.location_cached_enable
                    else R.string.location_off_pill,
                    action = true
                )
                return
            }
            // City mode: never auto-start GPS — only the pill tap does.
            if (isCityMode()) {
                updateLocationPillFromSession()
                return
            }
            val shouldRefresh = sessions.session == null ||
                sessions.provisionalLocation ||
                System.currentTimeMillis() - sessions.lastFreshLocationAt >
                    LocationController.LOCATION_REFRESH_INTERVAL_MS
            if (shouldRefresh && !locations.isLocating) {
                locations.startSearch()
            }
            return
        }
        // No permission
        if (locationFlow.permState == LocationPermState.CITY_ONLY && sessions.session != null) {
            updateLocationPillFromSession()
            return
        }
        if (sessions.lastSource == LocationSource.MANUAL_CITY && sessions.session != null) {
            updateLocationPillFromSession()
            return
        }
        if (locationFlow.permState == LocationPermState.DENIED ||
            locationFlow.permState == LocationPermState.DENIED_PERMANENT
        ) {
            if (sessions.session == null) {
                showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
            }
            return
        }
        if (locationFlow.permState == LocationPermState.UNKNOWN) {
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

    private fun renderSession(updatePill: Boolean = true) {
        val s = sessions.session ?: return
        val locationTrust = LocationTrust.from(s.locationAccuracyMeters, s.locationProvisional)

        // Only a GOOD (non-provisional, tight accuracy) fix may claim "at Kaaba".
        // Manual city Mecca is provisional → normal bearing + approximate location label.
        if (s.atKaaba && locationTrust == LocationTrust.GOOD) {
            val enteringAtKaaba = lastDegreeKey != "at_kaaba"
            lastDegreeKey = "at_kaaba"
            degreeText.text = getString(R.string.at_kaaba_title)
            degreeText.contentDescription = getString(R.string.at_kaaba_message)
            degreeText.setTextColor(ContextCompat.getColor(this, R.color.accent))
            deltaText.text = getString(R.string.at_kaaba_message)
            compassView.showQiblaMarker = false
            compassView.uncertain = true
            compassView.alignment = 0f
            compassView.contentDescription = getString(R.string.at_kaaba_message)
            calibrateBanner.visibility = View.GONE
            confidenceText.visibility = View.GONE
            hintText.visibility = View.GONE
            if (enteringAtKaaba) {
                degreeText.announceForAccessibility(getString(R.string.at_kaaba_message))
            }
            if (updatePill) updateLocationPillFromSession()
            return
        }
        compassView.showQiblaMarker = true

        val degKey = String.format(Locale.US, "%.0f°", s.qibla.value)
        if (degKey != lastDegreeKey) {
            lastDegreeKey = degKey
            degreeText.text = degKey
        }
        degreeText.contentDescription = getString(R.string.degree_accessibility, degreeText.text)
        degreeText.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        compassView.qiblaBearing = s.qibla.value.toFloat()
        // Keep the compass description distinct from the adjacent bearing label;
        // otherwise TalkBack can announce the same calculated angle twice.
        compassView.contentDescription = getString(R.string.compass_accessibility_static)

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
            if (updatePill) updateLocationPillFromSession()
            return
        }

        if (!HeadingPolicy.shouldShowPreciseGuidance(s.confidence, heading != null, locationTrust)) {
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
        val sensorHint = CompassHintMessages.resolve(this, hint)
        val locationAccuracyHint = s.locationAccuracyMeters
            ?.takeIf { it > LOCATION_ACCURACY_NOTICE_METERS }
            ?.let { getString(R.string.location_accuracy_approx) }
        // Location trust is independent of the sensor calibrate banner.
        val locationTrustHint =
            if (locationTrust == LocationTrust.WEAK || locationTrust == LocationTrust.UNKNOWN) {
                getString(R.string.location_approx_label)
            } else {
                null
            }
        val hintStr = sensorHint ?: locationAccuracyHint ?: locationTrustHint
        hintText.text = hintStr ?: ""
        if (hintStr != null) {
            hintText.visibility = View.VISIBLE
            val color = when {
                hint == CompassHint.CALIBRATION_NEEDED || hint == CompassHint.INTERFERENCE ->
                    ContextCompat.getColor(this, R.color.conf_low)
                hint == CompassHint.APPROXIMATE || hint == CompassHint.DECLINATION_MISSING ->
                    ContextCompat.getColor(this, R.color.conf_medium)
                locationTrustHint != null && sensorHint == null ->
                    ContextCompat.getColor(this, R.color.conf_medium)
                else -> ContextCompat.getColor(this, R.color.label_secondary)
            }
            hintText.setTextColor(color)
        } else {
            hintText.visibility = View.GONE
        }
        if (updatePill) updateLocationPillFromSession()
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
                // Support every Android grant mode, including "Only this time".
                // Always try the system dialog again unless the user permanently denied.
                if (locationFlow.permissionDeniedPermanently &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                ) {
                    promptAppPermissionSettings()
                } else {
                    locationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
            PillMode.NEED_SYSTEM -> promptEnableSystemLocation()
            PillMode.CACHED -> {
                when {
                    !locations.hasPermission() -> tryUseLiveGps()
                    isCityMode() -> tryUseLiveGps()
                    sessions.session == null -> showCityPicker()
                    else -> {
                        if (locations.isLocating) locations.stop(invalidate = true)
                        showPill(PillMode.LOCATING, R.string.locating, action = true)
                        locations.startSearch()
                    }
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
        if (mode != PillMode.HIDDEN) {
            locationPill.contentDescription = getString(
                R.string.location_pill_accessibility,
                locationPill.text
            )
        }
        if (mode == PillMode.CACHED && textRes == R.string.city_use_gps) {
            selectedCityName()?.let { cityName ->
                locationPill.text = getString(R.string.city_mode_named, cityName)
                locationPill.contentDescription = getString(
                    R.string.location_pill_accessibility,
                    locationPill.text
                )
            }
        }
    }

    private fun selectedCityName(): String? {
        val point = sessions.session?.point ?: return null
        val city = OfflineCities.all.firstOrNull {
            kotlin.math.abs(it.lat - point.latDeg) < 0.001 &&
                kotlin.math.abs(it.lon - point.lonDeg) < 0.001
        } ?: return null
        val resId = resources.getIdentifier("city_${city.nameResKey}", "string", packageName)
        return if (resId != 0) getString(resId) else city.nameResKey
    }

    private val pillMode: PillMode get() = pillController.mode


    private fun isCityMode(): Boolean {
        // Sticky CITY_ONLY alone must not trap the UI after a live fix.
        if (sessions.lastSource == LocationSource.MANUAL_CITY) return true
        if (locationFlow.usingCityFallback) return true
        return locationFlow.permState == LocationPermState.CITY_ONLY &&
            sessions.lastSource != LocationSource.GPS &&
            sessions.lastSource != LocationSource.NETWORK &&
            sessions.lastSource != LocationSource.FUSED
    }


    private fun invalidateSystemLocationCache() {
        lastSystemLocationCheckAt = 0L
    }

    private fun systemLocationEnabledCached(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSystemLocationCheckAt > 1_000L) {
            lastSystemLocationCheckAt = now
            cachedSystemLocationEnabled = locations.isSystemLocationEnabled()
        }
        return cachedSystemLocationEnabled
    }

    private fun updateLocationPillFromSession() {
        val s = sessions.session
        // Priority: live GPS > enable system location > permission > city fallback
        when {
            // System location OFF — always actionable (live check, not stale cache)
            locations.hasPermission() && !locations.isSystemLocationEnabled() -> {
                locationFlow.resetReadyDismissed()
                lastProvisional = null
                val res = if (s != null) R.string.location_cached_enable else R.string.location_off_pill
                showPill(PillMode.NEED_SYSTEM, res, action = true)
            }
            // Permission missing (including "Only this time" revocation after background).
            // Never label this as City mode — that was the stuck-UI bug.
            !locations.hasPermission() -> {
                locationFlow.resetReadyDismissed()
                lastProvisional = null
                showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
            }
            // City / manual session — offer return to GPS
            s != null && locationFlow.gpsUpgradeRequested && locations.isLocating -> {
                locationFlow.resetReadyDismissed()
                lastProvisional = true
                showPill(PillMode.LOCATING, R.string.city_searching_gps, action = true)
            }
            s != null && isCityMode() -> {
                locationFlow.resetReadyDismissed()
                lastProvisional = true
                showPill(PillMode.CACHED, R.string.city_use_gps, action = true)
            }
            // Searching
            s == null -> {
                locationFlow.resetReadyDismissed()
                showPill(PillMode.LOCATING, R.string.locating, action = true)
            }
            // Provisional last-known while still refining
            sessions.provisionalLocation -> {
                locationFlow.resetReadyDismissed()
                lastProvisional = true
                showPill(PillMode.CACHED, R.string.location_cached, action = true)
            }
            else -> {
                val wasProv = lastProvisional
                lastProvisional = false
                if (locationFlow.readyDismissed && pillMode == PillMode.HIDDEN) return
                if (wasProv == true || pillMode == PillMode.LOCATING || pillMode == PillMode.CACHED ||
                    pillMode == PillMode.NEED_PERMISSION || pillMode == PillMode.NEED_SYSTEM ||
                    (pillMode == PillMode.HIDDEN && !locationFlow.readyDismissed) ||
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
                locationFlow.onManualCitySelected()
                showCityPicker()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    /** Request a real device location while keeping the manual city visible as fallback. */
    private fun tryUseLiveGps() {
        invalidateSystemLocationCache()
        alignHaptic.tickLight()

        if (!locations.hasPermission()) {
            showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
            val permanentlyBlocked =
                locationFlow.permissionDeniedPermanently &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            if (permanentlyBlocked) {
                promptAppPermissionSettings()
            } else {
                // Works for "Only this time", "While using the app", and first grant.
                locationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
            return
        }
        if (!locations.isSystemLocationEnabled()) {
            showPill(
                PillMode.NEED_SYSTEM,
                if (sessions.session != null) R.string.location_cached_enable
                else R.string.location_off_pill,
                action = true
            )
            promptEnableSystemLocation()
            return
        }
        // Immediate feedback: searching pill before async work.
        locationFlow.beginGpsUpgradeFromCity()
        showPill(PillMode.LOCATING, R.string.city_searching_gps, action = true)
        if (locations.isLocating) {
            locations.stop(invalidate = true)
        }
        locations.startSearch()
    }

    private fun showCityPicker() {
        val labels = OfflineCities.all.map { city ->
            val resId = resources.getIdentifier("city_${city.nameResKey}", "string", packageName)
            if (resId != 0) getString(resId) else city.nameResKey
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_city)
            .setItems(labels) { _, which ->
                // A manual city selection is an explicit choice; cancel any GPS
                // request so a late callback cannot silently replace that city.
                locations.stop(invalidate = true)
                val city = OfflineCities.all[which]
                locationFlow.onManualCitySelected()
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
        if (locationFlow.permState != LocationPermState.UNKNOWN) return
        locationFlow.markPreSheetShown()
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
            locationFlow.markDeniedFromChooser()
            showPill(PillMode.NEED_PERMISSION, R.string.location_permission_pill, action = true)
        }
        view.findViewById<TextView>(R.id.permChooseCity).setOnClickListener {
            dialog.dismiss()
            locationFlow.onManualCitySelected()
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
    private companion object {
        const val LOCATION_ACCURACY_NOTICE_METERS = 1_000f
    }
}

