package com.github.maskedkunisquat.lattice.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented acceptance tests for Task 6.7: Journal History Screen.
 *
 * Covers:
 *   1. History screen is accessible from the bottom nav.
 *   2. Empty state message is shown when there are no entries.
 *   3. Screen renders under its testTag after navigation.
 *
 * Note: swipe-to-delete and undo require pre-seeded data, which is covered
 * by the ViewModel unit tests (Task 6.7 unit test follow-up). These UI tests
 * validate the screen structure and navigation contract.
 */
@RunWith(AndroidJUnit4::class)
class JournalHistoryScreenTest : MainActivityTest() {

    private fun navigateToHistory() {
        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithTag("screen:history").assertIsDisplayed()
    }

    // ── Criterion 1 & 3: screen is reachable and correctly tagged ────────────

    @Test
    fun historyScreen_isAccessibleFromNav() {
        navigateToHistory()
    }

    // ── Empty state ───────────────────────────────────────────────────────────

    @Test
    fun historyScreen_emptyState_showsPrompt() {
        navigateToHistory()
        // Fresh test DB has no entries; empty-state message must appear.
        composeRule.onNodeWithText(
            "No entries yet. Start writing in the Journal tab.",
            substring = true,
        ).assertIsDisplayed()
    }

    // ── Navigation back ───────────────────────────────────────────────────────

    @Test
    fun historyScreen_journalTab_returnsToEditor() {
        navigateToHistory()
        composeRule.onNodeWithText("Journal").performClick()
        composeRule.onNodeWithTag("screen:editor").assertIsDisplayed()
    }
}
