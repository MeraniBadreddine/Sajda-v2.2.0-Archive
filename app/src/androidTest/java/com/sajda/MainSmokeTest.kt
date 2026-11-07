package com.sajda

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.sajda.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke UI checks — layout must inflate and core chrome must be visible.
 * Does not assert GPS or sensor accuracy on emulator.
 */
@RunWith(AndroidJUnit4::class)
class MainSmokeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun compassIsDisplayed() {
        onView(withId(R.id.compass)).check(matches(isDisplayed()))
    }

    @Test
    fun compassHasAccessibleDescriptionBeforeLocationFix() {
        onView(withId(R.id.compass)).check(matches(withContentDescription()))
    }

    @Test
    fun degreeTextIsDisplayed() {
        onView(withId(R.id.degreeText)).check(matches(isDisplayed()))
    }

    @Test
    fun infoButtonIsDisplayed() {
        onView(withId(R.id.btnInfoWrap)).check(matches(isDisplayed()))
    }
}
