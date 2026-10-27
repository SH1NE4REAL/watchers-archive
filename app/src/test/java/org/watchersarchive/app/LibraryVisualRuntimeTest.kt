package org.watchersarchive.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Host-rendered layout inspection with generated TEST covers; these are not phone screenshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryVisualRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val books = (1..18).map { number ->
        CatalogSourceRecord("getcomics", "visual-$number", "https://example.test/comic/$number/",
            "TEST COMIC ${number.toString().padStart(2, '0')}", "Self-generated layout fixture.",
            "https://example.test/cover/$number.png", "2026-10-27T00:00:00Z", null,
            "2026-10-27T00:00:00Z", emptyList(), "2026", "77 MB", "CBZ", SourceConfidence.MEDIUM)
    }

    private val covers by lazy {
        listOf(0xFFB82335.toInt(), 0xFF244766.toInt(), 0xFFAA6B24.toInt()).map { background ->
            Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                canvas.drawColor(background)
                paint.color = AndroidColor.WHITE
                paint.alpha = 28
                canvas.drawCircle(120f, 75f, 82f, paint)
                paint.alpha = 255
                paint.textSize = 20f
                paint.isFakeBoldText = true
                canvas.drawText("TEST", 16f, 35f, paint)
                paint.textSize = 13f
                canvas.drawText("GENERATED", 16f, 55f, paint)
                canvas.drawText("COVER", 16f, 73f, paint)
                paint.strokeWidth = 3f
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(80f, 145f, 39f, paint)
                canvas.drawLine(56f, 145f, 104f, 145f, paint)
                canvas.drawLine(80f, 121f, 80f, 169f, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = 11f
                canvas.drawText("LAYOUT FIXTURE", 16f, 220f, paint)
            }
        }
    }

    private fun repository(): ReaderRepository {
        val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        return object : ReaderRepository by actual {
            override suspend fun getComicsRecords() = books
            override suspend fun refreshGetComics() = books
            override suspend fun savedOfficialGuides() = emptyList<OfficialGuide>()
            override suspend fun savedOfficialCharacters() = emptyList<OfficialCharacterProfile>()
            override suspend fun offlineDownloads() = emptyList<OfflineDownloadStatus>()
            override suspend fun cacheSnapshot() = CacheSnapshot(0, 0, 500_000_000, 0)
            override suspend fun searchGetComics(query: String, page: Int) = books.filter { it.title.contains(query, true) }
            override suspend fun searchCatalogue(query: String, page: Int) = CatalogSearchPage(searchGetComics(query, page), false)
            override suspend fun eventCover(collection: RecommendationCollection) = books.first().coverUrl
            override fun peekCover(url: String) = covers[(url.hashCode() and Int.MAX_VALUE) % covers.size]
            override suspend fun loadCover(url: String) = peekCover(url)
            override suspend fun shelfNames() = listOf("默认书架", "大事件")
            override suspend fun shelfItems(shelf: String): Set<String> = when (shelf) {
                "全部" -> books.take(8).map { it.externalId }.toSet()
                "大事件" -> books.take(2).map { it.externalId }.toSet()
                else -> books.drop(2).take(6).map { it.externalId }.toSet()
            }
        }
    }

    @Test fun compactLibraryRetainsFixedControlsAndRendersRecentEventsAndShelf() {
        val repository = repository()
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFBE2834),
                background = Color(0xFFF4F4F5), surface = Color.White,
                secondaryContainer = Color(0xFFF9E8E9))) { ArchiveApp(repository) }
        }
        compose.waitForIdle()
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("The compact-device fixture uses 320dp at mdpi", 320f, viewport.width, 1f)
        val search = compose.onNodeWithTag("catalogue-search").fetchSemanticsNode().boundsInRoot
        val tabs = listOf("recent", "events", "official").map {
            compose.onNodeWithTag("library-tab:$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        tabs.forEach { bounds ->
            assertTrue("Section button has positive width", bounds.width > 0)
            assertTrue("Section button remains inside compact viewport", bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue("Section controls appear beneath the fixed search", bounds.top >= search.bottom)
        }
        tabs.zipWithNext().forEach { (left, right) ->
            assertEquals(left.top, right.top, 1f)
            assertTrue("Section buttons do not overlap horizontally", left.right <= right.left + 1f)
        }
        for (label in listOf("观察者书库", "我的书架", "设置")) {
            val bounds = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Navigation label remains within viewport: $label", bounds.left >= viewport.left && bounds.right <= viewport.right)
        }
        captureForInspection("01-recent")
        compose.onNodeWithTag("discovery-list").performScrollToNode(hasText("TEST COMIC 18"))
        val afterScroll = compose.onNodeWithTag("catalogue-search").fetchSemanticsNode().boundsInRoot
        assertEquals(search.top, afterScroll.top, 1f)
        assertEquals(search.bottom, afterScroll.bottom, 1f)
        compose.onNodeWithTag("library-tab:events").performClick()
        compose.onNodeWithTag("event-card:civil-war").assertIsDisplayed()
        captureForInspection("02-events")
        compose.onNodeWithText("我的书架").performClick()
        compose.onNodeWithTag("shelf-folder:大事件").assertIsDisplayed()
        compose.onNodeWithTag("shelf-book:visual-3").assertIsDisplayed()
        captureForInspection("03-shelf")
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("setting-keep-screen-on").assertIsDisplayed()
        captureForInspection("04-settings")
        compose.onNodeWithTag("open-storage-management").performScrollTo().performClick()
        compose.onNodeWithText("临时阅读内容").assertIsDisplayed()
        captureForInspection("05-storage")
    }

    /** Rendering is diagnostic only: unsupported host capture cannot mask behavior-test results. */
    private fun captureForInspection(name: String) {
        compose.waitForIdle()
        val output = runCatching { projectRoot().resolve("artifacts/ui-v0.7.0").apply { mkdirs() } }
            .getOrElse { println("HOST_RENDER_UNAVAILABLE: ${it.message}"); return }
        var bitmap: Bitmap? = null
        val result = runCatching {
            compose.runOnIdle {
                val view = if (name == "05-storage") {
                    val globalClass = Class.forName("android.view.WindowManagerGlobal")
                    val global = globalClass.getDeclaredMethod("getInstance").invoke(null)
                    val roots = globalClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
                    roots.filterIsInstance<android.view.View>().last { it.isShown && it.width > 0 && it.height > 0 }
                } else compose.activity.window.decorView
                check(view.width > 0 && view.height > 0) { "Decor view was not measured" }
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(checkNotNull(bitmap)))
            }
            val captured = checkNotNull(bitmap)
            val colors = mutableSetOf<Int>()
            for (y in 0 until captured.height step 3) for (x in 0 until captured.width step 3) colors += captured.getPixel(x, y)
            check(colors.size >= 12) { "Host capture did not render sufficient UI detail (${colors.size} sampled colors)" }
            output.resolve("$name.png").outputStream().use { stream ->
                check(captured.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encoding failed" }
            }
        }
        runCatching {
            output.resolve("render-status.txt").appendText(if (result.isSuccess) "$name: HOST_RENDER_OK\n"
                else "$name: HOST_RENDER_UNAVAILABLE: ${result.exceptionOrNull()?.javaClass?.simpleName}: ${result.exceptionOrNull()?.message}\n")
        }
        bitmap?.recycle()
    }

    private fun projectRoot(): File = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
}
