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
 * Instrumented: permission granted → main UI + location pill interactive.
 *
 * City-picker → GPS text assert needs locale-stable dialog item matching and a
 * controllable provider; covered in unit tests (FirstFixGate / LocationFlowState).
 * This test locks the regression that the activity remains usable after grant.
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
    fun grantedPermission_activityAndPillUsable() {
        onView(withId(R.id.compass)).check(matches(isDisplayed()))
        onView(withId(R.id.locationPill)).check(matches(isDisplayed()))
        onView(withId(R.id.locationPill)).perform(click())
        onView(withId(R.id.compass)).check(matches(isDisplayed()))
    }
}
