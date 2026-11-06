package com.sajda

import android.Manifest
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.sajda.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented checks for city/permission chrome.
 * Full GPS callback sequencing is covered by unit tests on RequestGate +
 * LocationCandidatePolicy; these assert the UI remains stable under granted perms.
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
    fun degreeAndDeltaRemainVisible() {
        onView(withId(R.id.degreeText)).check(matches(isDisplayed()))
        onView(withId(R.id.deltaText)).check(matches(isDisplayed()))
    }
}
