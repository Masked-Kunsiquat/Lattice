package com.github.maskedkunisquat.lattice.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.maskedkunisquat.lattice.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented acceptance tests for Task 6.5: Settings Screen.
 *
 * Covers:
 *   1. All major sections render after navigating to Settings.
 *   2. Cloud toggle shows amber warning dialog on enable.
 *   3. Dismissing the warning keeps cloud disabled.
 *   4. Confirming the warning enables cloud and shows the provider dropdown.
 *   5. Add-activity dialog opens via the + button.
 *   6. Audit Trail row is present and navigates correctly.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    private fun navigateToSettings() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithTag("screen:settings").assertIsDisplayed()
    }

    // ── Criterion 1: sections render ─────────────────────────────────────────

    @Test
    fun settingsScreen_showsAllSections() {
        navigateToSettings()
        composeRule.onNodeWithText("Sovereignty").assertIsDisplayed()
        composeRule.onNodeWithText("Audit Trail").assertIsDisplayed()
        composeRule.onNodeWithText("Behavioral Activation").assertIsDisplayed()
        composeRule.onNodeWithText("Data Portability").assertIsDisplayed()
        composeRule.onNodeWithText("About").assertIsDisplayed()
    }

    // ── Criterion 2: cloud toggle warning dialog ──────────────────────────────

    @Test
    fun cloudToggle_showsWarningDialog_onEnable() {
        navigateToSettings()
        composeRule.onNode(isSwitch).performClick()
        composeRule.onNodeWithText("Enable cloud processing?").assertIsDisplayed()
    }

    // ── Criterion 3: dismissing warning does not enable cloud ─────────────────

    @Test
    fun cloudToggleDialog_keepLocal_dismissesWithoutEnabling() {
        navigateToSettings()
        composeRule.onNode(isSwitch).performClick()
        composeRule.onNodeWithText("Enable cloud processing?").assertIsDisplayed()

        composeRule.onNodeWithText("Keep local").performClick()

        composeRule.onAllNodesWithText("Enable cloud processing?").assertCountEquals(0)
        // Provider dropdown should not be visible (only shown when cloud is on)
        composeRule.onAllNodesWithText("Provider").assertCountEquals(0)
    }

    // ── Criterion 4: confirming warning enables cloud ─────────────────────────

    @Test
    fun cloudToggleDialog_enable_showsProviderDropdown() {
        navigateToSettings()
        composeRule.onNode(isSwitch).performClick()
        composeRule.onNodeWithText("Enable cloud processing?").assertIsDisplayed()

        composeRule.onNodeWithText("Enable").performClick()

        composeRule.onAllNodesWithText("Enable cloud processing?").assertCountEquals(0)
        composeRule.onNodeWithText("Provider").assertIsDisplayed()
    }

    // ── Criterion 5: add-activity dialog ─────────────────────────────────────

    @Test
    fun addActivityButton_opensDialog() {
        navigateToSettings()
        composeRule.onNodeWithContentDescription("Add activity").performClick()
        composeRule.onNodeWithText("Add Activity").assertIsDisplayed()
        composeRule.onNodeWithText("Task name").assertIsDisplayed()
        composeRule.onNodeWithText("Value category").assertIsDisplayed()
    }

    @Test
    fun addActivityDialog_cancel_dismissesWithoutAdding() {
        navigateToSettings()
        composeRule.onNodeWithContentDescription("Add activity").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onAllNodesWithText("Add Activity").assertCountEquals(0)
    }

    // ── Criterion 6: audit trail navigation ──────────────────────────────────

    @Test
    fun auditTrailRow_isDisplayed() {
        navigateToSettings()
        composeRule.onNodeWithText("View Audit Log").assertIsDisplayed()
    }

    @Test
    fun auditTrailRow_navigatesToAuditScreen() {
        navigateToSettings()
        composeRule.onNodeWithText("View Audit Log").performClick()
        composeRule.onNodeWithTag("screen:audit").assertIsDisplayed()
    }
}
