package com.github.maskedkunisquat.lattice.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented acceptance tests for Task 6.6: Audit Trail Screen.
 *
 * Covers:
 *   1. Screen is reachable via Settings → View Audit Log.
 *   2. Empty state shows "No data has left this device" for local-only sessions.
 */
@RunWith(AndroidJUnit4::class)
class AuditTrailScreenTest : MainActivityTest() {

    private fun navigateToAudit() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithTag("screen:settings").assertIsDisplayed()
        composeRule.onNodeWithText("View Audit Log").performClick()
        composeRule.onNodeWithTag("screen:audit").assertIsDisplayed()
    }

    // ── Criterion 1: screen is reachable ─────────────────────────────────────

    @Test
    fun auditTrailScreen_isReachableFromSettings() {
        navigateToAudit()
    }

    // ── Criterion 2: empty state for local-only sessions ─────────────────────

    @Test
    fun auditTrailScreen_emptyState_showsNoDataMessage() {
        navigateToAudit()
        // A fresh test DB has no TransitEvents → empty state must be visible.
        composeRule.onNodeWithTag("audit:empty").assertIsDisplayed()
        composeRule.onNodeWithText("No data has left this device").assertIsDisplayed()
    }
}
