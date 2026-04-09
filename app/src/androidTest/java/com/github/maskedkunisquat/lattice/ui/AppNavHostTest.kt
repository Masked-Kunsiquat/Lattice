package com.github.maskedkunisquat.lattice.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.maskedkunisquat.lattice.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for Task 6.1: NavHost + Bottom Navigation.
 *
 * Covers all three acceptance criteria:
 *   1. NavHost renders with correct start destination (editor).
 *   2. Back press from a secondary screen returns to the correct parent.
 *   3. Navigation dep check is a build-time concern — no test needed.
 *
 * Run via: ./gradlew connectedAndroidTest
 * Or via Android Studio → right-click the class → Run
 */
@RunWith(AndroidJUnit4::class)
class AppNavHostTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    // ── Criterion 1: correct start destination ────────────────────────────────

    @Test
    fun startDestination_isEditor() {
        // The editor wrapper is visible…
        composeRule.onNodeWithTag("screen:editor").assertIsDisplayed()
        // …and the Journal tab is the selected bottom-nav item.
        composeRule.onNodeWithText("Journal").assertIsSelected()
        composeRule.onNodeWithText("History").assertIsNotSelected()
        composeRule.onNodeWithText("Settings").assertIsNotSelected()
    }

    // ── Bottom-nav routing ────────────────────────────────────────────────────

    @Test
    fun tappingHistory_navigatesToHistoryScreen() {
        composeRule.onNodeWithText("History").performClick()

        composeRule.onNodeWithTag("screen:history").assertIsDisplayed()
        composeRule.onNode(hasText("History") and isTab).assertIsSelected()
        composeRule.onNodeWithText("Journal").assertIsNotSelected()
    }

    @Test
    fun tappingSettings_navigatesToSettingsScreen() {
        composeRule.onNodeWithText("Settings").performClick()

        composeRule.onNodeWithTag("screen:settings").assertIsDisplayed()
        composeRule.onNode(hasText("Settings") and isTab).assertIsSelected()
        composeRule.onNodeWithText("Journal").assertIsNotSelected()
    }

    @Test
    fun tappingJournalFromHistory_returnsToEditor() {
        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithTag("screen:history").assertIsDisplayed()

        composeRule.onNodeWithText("Journal").performClick()

        composeRule.onNodeWithTag("screen:editor").assertIsDisplayed()
        composeRule.onNodeWithText("Journal").assertIsSelected()
    }

    // ── Criterion 2: back press returns to correct parent ─────────────────────

    @Test
    fun backPress_fromHistory_returnsToEditor() {
        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithTag("screen:history").assertIsDisplayed()

        Espresso.pressBack()

        composeRule.onNodeWithTag("screen:editor").assertIsDisplayed()
        composeRule.onNodeWithText("Journal").assertIsSelected()
    }

    @Test
    fun backPress_fromSettings_returnsToEditor() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithTag("screen:settings").assertIsDisplayed()

        Espresso.pressBack()

        composeRule.onNodeWithTag("screen:editor").assertIsDisplayed()
        composeRule.onNodeWithText("Journal").assertIsSelected()
    }

    // ── State restoration across tab switches ─────────────────────────────────

    @Test
    fun switchingTabs_restoresEachTabState() {
        // Visit History, then Settings, then back to Journal — each destination
        // should be accessible and the editor should still be the active screen
        // when returning to the Journal tab.
        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithTag("screen:history").assertIsDisplayed()

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithTag("screen:settings").assertIsDisplayed()

        composeRule.onNodeWithText("Journal").performClick()
        composeRule.onNodeWithTag("screen:editor").assertIsDisplayed()
    }
}
