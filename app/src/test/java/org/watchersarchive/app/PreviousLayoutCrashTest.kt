package org.watchersarchive.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Reproduces the exact 0.3.0 shelf layout independently of the fixed Activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PreviousLayoutCrashTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oldShelfCategoryRowWasMeasuredWithInfiniteHeight() {
        try {
            compose.setContent {
                LazyColumn {
                    item {
                        Row(Modifier.verticalScroll(rememberScrollState())) { Text("默认书架") }
                    }
                }
            }
            compose.waitForIdle()
            fail("The old unbounded vertical category row must reproduce its layout exception")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("infinity", ignoreCase = true))
            println("REPRODUCED_0_3_LAYOUT_EXCEPTION: ${failure.message}")
        }
    }
}
