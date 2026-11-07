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
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.sajda.ui.MainActivity
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.containsString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented checks for city/permission chrome.
 *
 * TODO: A full city-picker → locationPill → city_searching_gps assertion requires
 * stable dialog item matching across locales and a deterministic location provider
 * on CI emulators. Until that harness exists, this test only verifies the activity
 * remains usable under granted COARSE/FINE permission and that the location pill
 * is interactive (click does not crash). Do not treat this as coverage of the
 * full City→GPS state machine — that path is unit-tested via LocationFlowStateTest.
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
        // After click the pill or a system/dialog chrome must still be present.
        onView(withId(R.id.compass)).check(matches(isDisplayed()))
    }
}
