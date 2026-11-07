package com.sajda

import android.Manifest
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.sajda.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke for location pill under granted permission.
 *
 * Full City→searching_gps assertion on emulators is flaky without a mock
 * LocationManager. This test verifies: granted COARSE/FINE → activity usable
 * and pill remains interactive. TODO: inject a TestLocationProvider for a
 * deterministic LOCATING text assert.
 */
@RunWith(AndroidJUnit4::class)
class LocationFlowInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun activityLaunchesWithLocationPermissionGranted() {
        onView(withId(R.id.compass)).check(matches(isDisplayed()))
        onView(withId(R.id.locationPill)).check(matches(isDisplayed()))
    }

    @Test
    fun locationPillClickDoesNotCrash() {
        onView(withId(R.id.locationPill)).perform(click())
        onView(withId(R.id.compass)).check(matches(isDisplayed()))
    }
}
