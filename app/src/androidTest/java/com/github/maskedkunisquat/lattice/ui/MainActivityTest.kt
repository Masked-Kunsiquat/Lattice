package com.github.maskedkunisquat.lattice.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.github.maskedkunisquat.lattice.MainActivity
import org.junit.Before
import org.junit.Rule

/**
 * Base class for instrumented tests that launch [MainActivity].
 *
 * [MainActivity] shows [LockScreen] on any device with a PIN or biometric enrolled
 * ([BiometricGate.canAuthenticate] returns true). Tests call [LockViewModel.onUnlocked]
 * directly before each test so the gate is bypassed without requiring real auth.
 */
abstract class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun unlockApp() {
        composeRule.activityRule.scenario.onActivity { activity ->
            ViewModelProvider(activity)[LockViewModel::class.java].onUnlocked()
        }
        composeRule.waitForIdle()
    }
}
