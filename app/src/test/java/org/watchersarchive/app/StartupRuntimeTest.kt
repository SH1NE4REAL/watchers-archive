package org.watchersarchive.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Executes real Activity + Compose layout under Android framework shadows; no phone required. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class StartupRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun startupMeasuresAndDisplaysShelfWithoutLayoutCrash() {
        compose.onNodeWithText("我的书架").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("我的书架").onFirst().assertIsDisplayed()
    }

    @Test fun openingSettingsDoesNotCrashTheActivity() {
        compose.onNodeWithTag("nav-settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("存储管理").performScrollTo().assertIsDisplayed()
    }

    @Test fun activityRecreationRetainsAUsableRootScreen() {
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("nav-settings").assertIsDisplayed()
    }

    @Test fun compactRedHeaderReplacesTheLargeApplicationTitle() {
        compose.onNodeWithTag("comic-header-decoration").assertIsDisplayed()
        compose.onNodeWithText("观察者漫格").assertDoesNotExist()
        compose.onNodeWithText("THE WATCHER'S ARCHIVE").assertDoesNotExist()
    }

    @Test fun firstNavigationDestinationIsNamedWatchersLibrary() {
        compose.onNodeWithTag("nav-watchers-library").assertIsDisplayed()
        compose.onNodeWithText("观察者书库").assertIsDisplayed()
        compose.onNodeWithTag("library-tab:recent").assertIsDisplayed()
        compose.onNodeWithTag("library-tab:events").assertIsDisplayed()
        compose.onNodeWithTag("library-tab:official").assertIsDisplayed()
    }
}
