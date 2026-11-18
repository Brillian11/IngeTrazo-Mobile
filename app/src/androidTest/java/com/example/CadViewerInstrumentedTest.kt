package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation CUJ test deliverable for physical devices or streaming emulator verification.
 */
@RunWith(AndroidJUnit4::class)
class CadViewerInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun openSampleModel_switchTools_andOpenAngleCounterSheet() {
        composeRule.onNodeWithText("IngeTrazo Mobile").assertIsDisplayed()
        composeRule.onNodeWithTag("sample_card_pestanas-prueba.igz").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("cad_gl_viewport").assertIsDisplayed()
        composeRule.onNodeWithTag("tool_tape").performClick()
        composeRule.onNodeWithTag("tool_angle").performClick()
        composeRule.onNodeWithTag("viewer_ortho_toggle").performClick()
    }
}
