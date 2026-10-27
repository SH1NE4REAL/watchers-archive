package org.watchersarchive.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Flow tests inject local engines; they do not claim phone OCR/model downloads succeeded. */
class PageTranslationTest {
    private open class FakeEngine(var available: Boolean = false) : PageTranslationEngine<String> {
        var downloads = 0
        var recognitions = 0
        var translations = 0
        var closed = false
        var requestedWifiOnly: Boolean? = null
        var recognized = listOf("Hello, Spider-Man!", "Look out!")
        override suspend fun modelAvailable() = available
        override suspend fun downloadModel(wifiOnly: Boolean) { downloads++; requestedWifiOnly = wifiOnly; available = true }
        override suspend fun recognize(input: String): List<String> { recognitions++; return recognized }
        override suspend fun translate(english: String): String { translations++; return "中文：$english" }
        override fun close() { closed = true }
    }

    @Test fun openingAPageOnlyChecksModelAndNeverRunsOcrOrDownloads() = runBlocking {
        val engine = FakeEngine()
        val controller = PageTranslationController(engine)
        try {
            controller.openPage("archive-v1:crc1")
            assertEquals(PageTranslationState.ModelRequired, controller.state.value)
            assertEquals(0, engine.downloads); assertEquals(0, engine.recognitions); assertEquals(0, engine.translations)
        } finally { controller.close() }
    }

    @Test fun translateWithoutDownloadConsentCannotStartAMissingModel() = runBlocking {
        val engine = FakeEngine()
        val controller = PageTranslationController(engine)
        try {
            controller.openPage("a:1"); controller.translatePage("a:1", "image", allowModelDownload = false)
            assertEquals(PageTranslationState.ModelRequired, controller.state.value)
            assertEquals(0, engine.downloads); assertEquals(0, engine.recognitions)
        } finally { controller.close() }
    }

    @Test fun explicitConsentDownloadsThenProducesPairedOriginalAndChinese() = runBlocking {
        val engine = FakeEngine()
        val controller = PageTranslationController(engine)
        try {
            controller.openPage("a:1"); controller.translatePage("a:1", "image", allowModelDownload = true)
            val result = controller.state.value as PageTranslationState.Complete
            assertEquals("a:1", result.page.pageKey)
            assertEquals(2, result.page.blocks.size)
            assertEquals("Hello, Spider-Man!", result.page.blocks.first().original)
            assertEquals("中文：Hello, Spider-Man!", result.page.blocks.first().chinese)
            assertEquals(1, engine.downloads); assertEquals(true, engine.requestedWifiOnly)
        } finally { controller.close() }
    }

    @Test fun alreadyInstalledModelsTranslateWithoutNetworkDownload() = runBlocking {
        val engine = FakeEngine(available = true)
        val controller = PageTranslationController(engine)
        try {
            controller.openPage("a:1"); controller.translatePage("a:1", "image", allowModelDownload = false)
            assertTrue(controller.state.value is PageTranslationState.Complete)
            assertEquals(0, engine.downloads)
        } finally { controller.close() }
    }

    @Test fun cachedIdentityDoesNotReprocessAndDifferentArchiveVersionCannotReuseIt() = runBlocking {
        val engine = FakeEngine(available = true)
        val controller = PageTranslationController(engine)
        try {
            controller.openPage("etag-v1:page-crc"); controller.translatePage("etag-v1:page-crc", "image", false)
            controller.openPage("etag-v1:page-crc")
            assertTrue(controller.state.value is PageTranslationState.Complete)
            assertEquals(1, engine.recognitions)
            controller.openPage("etag-v2:page-crc")
            assertEquals(PageTranslationState.ReadyToTranslate, controller.state.value)
            assertEquals(1, engine.recognitions)
        } finally { controller.close() }
    }

    @Test fun switchingPagesCannotPublishAnOldRecognitionResult() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val engine = object : FakeEngine(available = true) {
            override suspend fun recognize(input: String): List<String> {
                started.complete(Unit)
                return withContext(NonCancellable) { finish.await(); listOf("old page text") }
            }
        }
        val controller = PageTranslationController(engine)
        try { withTimeout(5_000) {
            controller.openPage("old")
            val old = launch { controller.translatePage("old", "image", false) }
            started.await()
            controller.openPage("new")
            finish.complete(Unit); old.join()
            assertEquals(PageTranslationState.ReadyToTranslate, controller.state.value)
            assertEquals(0, engine.translations)
        } } finally { finish.complete(Unit); controller.close() }
    }

    @Test fun downloadFailureIsVisibleAndDoesNotRunOcr() = runBlocking {
        val engine = object : FakeEngine() {
            override suspend fun downloadModel(wifiOnly: Boolean) { throw IOException("network test failure") }
        }
        val controller = PageTranslationController(engine)
        try {
            controller.openPage("a:1"); controller.translatePage("a:1", "image", true)
            val failure = controller.state.value as PageTranslationState.Failed
            assertTrue(failure.message.contains("模型尚未下载成功"))
            assertEquals(0, engine.recognitions)
        } finally { controller.close() }
    }

    @Test fun noRecognizedTextDoesNotInventATranslation() = runBlocking {
        val engine = FakeEngine(available = true).apply { recognized = listOf(" ", "") }
        val controller = PageTranslationController(engine)
        try {
            controller.openPage("a:1"); controller.translatePage("a:1", "image", false)
            assertEquals(PageTranslationState.NoText, controller.state.value)
            assertEquals(0, engine.translations)
        } finally { controller.close() }
    }

    @Test fun textAndMemoryCacheAreBounded() = runBlocking {
        val engine = FakeEngine(available = true).apply { recognized = List(80) { "x".repeat(3_000) } }
        val controller = PageTranslationController(engine)
        try {
            controller.openPage("large"); controller.translatePage("large", "image", false)
            val page = (controller.state.value as PageTranslationState.Complete).page
            assertTrue(page.truncated)
            assertTrue(page.blocks.size <= 60)
            assertTrue(page.blocks.sumOf { it.original.length } <= 16_000)
        } finally { controller.close() }
        val cache = PageTranslationCache(maximumPages = 1, maximumCharacters = 100)
        cache.put(TranslatedPage("one", listOf(TranslatedPageBlock("en", "中")), false))
        cache.put(TranslatedPage("two", listOf(TranslatedPageBlock("en", "中")), false))
        assertNull(cache.get("one")); assertNotNull(cache.get("two"))
    }

    @Test fun closeCancelsTheCurrentPageAndClosesClients() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val engine = object : FakeEngine(available = true) {
            override suspend fun recognize(input: String): List<String> {
                try { started.complete(Unit); awaitCancellation() }
                finally { cancelled.complete(Unit) }
            }
        }
        val controller = PageTranslationController(engine)
        withTimeout(5_000) {
            controller.openPage("a:1")
            val job = launch { controller.translatePage("a:1", "image", false) }
            started.await(); controller.close(); cancelled.await(); job.join()
            assertTrue(engine.closed)
            assertFalse(controller.state.value is PageTranslationState.Complete)
        }
    }
}
