package org.watchersarchive.app

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PageTranslationPanelTest {
    @get:Rule val compose = createComposeRule()
    private class FakeEngine(var ready: Boolean) : PageTranslationEngine<Bitmap> {
        var downloads = 0; var recognitions = 0; var closed = false
        override suspend fun modelAvailable() = ready
        override suspend fun downloadModel(wifiOnly: Boolean) { downloads++; ready = true }
        override suspend fun recognize(input: Bitmap): List<String> { recognitions++; return listOf("Hello, hero.") }
        override suspend fun translate(english: String) = "你好，英雄。"
        override fun close() { closed = true }
    }

    @Test fun panelRequiresExplicitModelDownloadThenDisplaysPairedText() {
        val engine = FakeEngine(false)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val key = "fixture-panel:${System.nanoTime()}"
        compose.setContent { MaterialTheme { PageTranslationPanel(bitmap, key, {}, { engine }) } }
        compose.waitForIdle()
        compose.onNodeWithText("下载模型并翻译本页").assertIsDisplayed()
        assertEquals(0, engine.downloads); assertEquals(0, engine.recognitions)
        compose.onNodeWithText("下载模型并翻译本页").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("1. 你好，英雄。").assertIsDisplayed()
        compose.onNodeWithText("Hello, hero.").assertIsDisplayed()
        assertEquals(1, engine.downloads); assertEquals(1, engine.recognitions)
    }

    @Test fun closingThePanelClosesItsEngineWithoutStartingRecognition() {
        val engine = FakeEngine(true)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val key = "fixture-close:${System.nanoTime()}"
        compose.setContent {
            var show by remember { mutableStateOf(true) }
            MaterialTheme { if (show) PageTranslationPanel(bitmap, key, { show = false }, { engine }) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("关闭").performClick()
        compose.waitForIdle()
        assertTrue(engine.closed)
        assertEquals(0, engine.recognitions)
    }
}
