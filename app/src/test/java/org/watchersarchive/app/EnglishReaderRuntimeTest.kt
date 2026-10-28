package org.watchersarchive.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h720dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishReaderRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private val comic = DemoComic("english-fixture", "English assistant fixture", "", "fixture.cbz")
    private val bitmap = Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(android.graphics.Color.rgb(244, 239, 226))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = android.graphics.Color.DKGRAY }
        canvas.drawText("TEST PAGE", 35f, 55f, paint)
        paint.textSize = 22f
        canvas.drawText("Hero saves city", 90f, 110f, paint)
        paint.textSize = 14f
        canvas.drawText("SYNTHETIC TEST CONTENT", 35f, 560f, paint)
    }
    private val bounds = RectF(90f, 90f, 150f, 115f)
    private class Ocr : EnglishOcrEngine {
        var calls = 0
        override suspend fun recognize(bitmap: Bitmap, pageKey: String): EnglishPageTokens {
            calls++
            val word = if (pageKey.contains("page-0")) "hero" else "city"
            return EnglishPageTokens(pageKey, bitmap.width, bitmap.height,
                listOf(EnglishToken("word-$word", word, RectF(90f,90f,150f,115f), "$word saves", "line")), false)
        }
        override fun close() { }
    }

    @Test fun readerRecognizesOnlyWhenEnabledAndClickLookupTracksPageChanges() {
        val ocr = Ocr()
        val dictionary = object : EnglishDictionaryProvider {
            override suspend fun lookup(token: EnglishToken, page: EnglishPageTokens) =
                EnglishDictionaryEntry(token.text, meaning = if (token.text == "hero") "英雄" else "城市", partOfSpeech = "名词")
        }
        val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        val repository = object : ReaderRepository by actual {
            override suspend fun lastPage(comicId: String) = 0
            override suspend fun bookmarkPages(comicId: String) = emptySet<Int>()
            override suspend fun open(comic: DemoComic) = object : ReaderSession {
                override val totalPages = 2
                override suspend fun loadPage(index: Int) = ReaderPage(index, 2, bitmap, true, 0, stablePageId = "page-$index")
                override suspend fun close() { }
            }
        }
        lateinit var view: View
        compose.setContent { view = LocalView.current; MaterialTheme { ReaderScreen(comic, repository, { ocr }, dictionary) {} } }
        compose.waitForIdle()
        assertEquals(0, ocr.calls)
        val image = compose.onNodeWithTag("reader-gesture-surface")
        image.performTouchInput { click(Offset(2f, 2f)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("toggle-english-assistant").performClick()
        compose.waitForIdle()
        assertEquals(1, ocr.calls)
        val viewport = image.fetchSemanticsNode().boundsInRoot
        val mapped = checkNotNull(ReaderImageBounds(viewport.width, viewport.height, 300f, 600f)
            .imageRectOnScreen(ReaderImageTransform(), bounds))
        image.performTouchInput { click(Offset(mapped.centerX(), mapped.centerY())) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("英雄").assertIsDisplayed()
        // Host rendering of generated content; not an actual phone screenshot.
        runCatching { compose.runOnIdle {
            val folder = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }.resolve("artifacts/ui-v0.9.0").apply { mkdirs() }
            val preview = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(preview))
                folder.resolve("english-assistant.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { preview.recycle() }
        } }
        image.performTouchInput { swipe(Offset(viewport.width - 20, 220f), Offset(20f, 220f), 300) }
        compose.waitForIdle()
        assertEquals(2, ocr.calls)
        compose.onNodeWithText("英雄").assertDoesNotExist()
        image.performTouchInput { click(Offset(mapped.centerX(), mapped.centerY())) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("城市").assertIsDisplayed()
        compose.onNodeWithTag("close-english-card").performClick()
        image.performTouchInput { click(Offset(2f, 2f)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("toggle-english-assistant").performClick()
        compose.onNodeWithTag("english-assistant-overlay").assertDoesNotExist()
        image.performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals("Turning a page with assistant disabled does not run OCR", 2, ocr.calls)
    }
}
