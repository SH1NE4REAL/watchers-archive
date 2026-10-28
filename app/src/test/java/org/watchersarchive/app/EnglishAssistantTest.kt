package org.watchersarchive.app

import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishAssistantTest {
    private val bitmap get() = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
    private fun token(id: String, text: String, left: Float = 10f, line: String = "line-1") =
        EnglishToken(id, text, RectF(left, 12f, left + 35, 40f), "slow down hero", line)
    private fun page(key: String, tokens: List<EnglishToken> = listOf(token("0", "hero"))) =
        EnglishPageTokens(key, 300, 200, tokens, false)

    private inner class FakeOcr(private val tokens: List<EnglishToken> = listOf(token("0", "hero"))) : EnglishOcrEngine {
        var calls = 0
        var closed = false
        override suspend fun recognize(bitmap: Bitmap, pageKey: String): EnglishPageTokens {
            calls++
            return page(pageKey, tokens)
        }
        override fun close() { closed = true }
    }
    private class FakeDictionary : EnglishDictionaryProvider {
        var calls = 0
        override suspend fun lookup(token: EnglishToken, page: EnglishPageTokens): EnglishDictionaryEntry {
            calls++
            return EnglishDictionaryEntry(token.text, "/ˈhɪəroʊ/", null, "英雄；勇士", "名词", token.lineText)
        }
    }

    @Test fun openingCurrentPageRecognizesOnlyOnceAndClickShowsDictionaryCardWithoutPersistence() = runBlocking {
        val ocr = FakeOcr(); val dictionary = FakeDictionary()
        val controller = EnglishAssistantController(ocr, dictionary)
        try {
            controller.openPage("page-1", bitmap)
            val ready = controller.state.value as EnglishAssistantState.Ready
            assertEquals(1, ocr.calls)
            controller.lookup(ready.page.tokens.single())
            val selected = controller.state.value as EnglishAssistantState.Selected
            assertEquals("hero", selected.entry.original)
            assertEquals("英雄；勇士", selected.entry.meaning)
            assertEquals(1, dictionary.calls)
            controller.openPage("page-1", bitmap)
            assertEquals(1, ocr.calls)
        } finally { controller.close() }
        assertTrue(ocr.closed)
    }

    @Test fun emptyDictionaryReturnsExplicitUnknownMessageAndNoInventedTranslation() = runBlocking {
        val controller = EnglishAssistantController(FakeOcr(), EmptyEnglishDictionaryProvider)
        try {
            controller.openPage("page", bitmap)
            controller.lookup((controller.state.value as EnglishAssistantState.Ready).page.tokens.single())
            val selected = controller.state.value as EnglishAssistantState.Selected
            assertEquals("暂未取得词典解释", selected.entry.meaning)
            assertEquals("hero", selected.entry.original)
            assertNull(selected.entry.contextMeaning)
        } finally { controller.close() }
    }

    @Test fun constructingAndDisablingDoesNotStartOcrOrLookupAndTogglingRetainsTextCache() = runBlocking {
        val ocr = FakeOcr(); val dictionary = FakeDictionary()
        val controller = EnglishAssistantController(ocr, dictionary)
        try {
            assertEquals(EnglishAssistantState.Idle, controller.state.value)
            controller.disable()
            assertEquals(0, ocr.calls); assertEquals(0, dictionary.calls)
            controller.openPage("one", bitmap)
            controller.disable()
            assertEquals(EnglishAssistantState.Idle, controller.state.value)
            controller.openPage("one", bitmap)
            assertEquals(1, ocr.calls)
            assertEquals(0, dictionary.calls)
        } finally { controller.close() }
    }

    @Test fun aLateNonCooperativeLookupCannotReplaceTheNextPagesState() = runBlocking {
        val started = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        val dictionary = object : EnglishDictionaryProvider {
            override suspend fun lookup(token: EnglishToken, page: EnglishPageTokens): EnglishDictionaryEntry = withContext(NonCancellable) {
                started.complete(Unit); released.await()
                EnglishDictionaryEntry("old-page-answer", meaning = "迟到的结果")
            }
        }
        val controller = EnglishAssistantController(FakeOcr(), dictionary)
        controller.openPage("one", bitmap)
        val word = controller.state.value.pageOrNull()!!.tokens.single()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { controller.lookup(word, "one") }
        try {
            started.await()
            controller.openPage("two", bitmap)
            released.complete(Unit); pending.join()
            assertEquals("two", (controller.state.value as EnglishAssistantState.Ready).page.pageKey)
        } finally { released.complete(Unit); pending.join(); controller.close() }
    }

    @Test fun aNewWordCancelsTheOldLookupAndCanBeSelectedWithoutClosingTheCard() = runBlocking {
        val words = listOf(token("0", "slow"), token("1", "down", 50f))
        val started = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        val dictionary = object : EnglishDictionaryProvider {
            override suspend fun lookup(token: EnglishToken, page: EnglishPageTokens): EnglishDictionaryEntry {
                if (token.id == "0") withContext(NonCancellable) { started.complete(Unit); released.await() }
                return EnglishDictionaryEntry(token.text, meaning = "definition:${token.text}")
            }
        }
        val controller = EnglishAssistantController(FakeOcr(words), dictionary)
        controller.openPage("page", bitmap)
        val old = launch(start = CoroutineStart.UNDISPATCHED) { controller.lookup(words[0]) }
        try {
            started.await()
            controller.lookup(words[1])
            released.complete(Unit); old.join()
            assertEquals("down", (controller.state.value as EnglishAssistantState.Selected).token.text)
            controller.lookup(words[1])
            assertEquals("down", (controller.state.value as EnglishAssistantState.Selected).entry.original)
        } finally { released.complete(Unit); old.join(); controller.close() }
    }

    @Test fun disableAndCloseRejectLateLookupResults() = runBlocking {
        for (close in listOf(false, true)) {
            val started = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
            val controller = EnglishAssistantController(FakeOcr(), object : EnglishDictionaryProvider {
                override suspend fun lookup(token: EnglishToken, page: EnglishPageTokens): EnglishDictionaryEntry = withContext(NonCancellable) {
                    started.complete(Unit); released.await(); EnglishDictionaryEntry(token.text, meaning = "late")
                }
            })
            controller.openPage("page", bitmap)
            val word = controller.state.value.pageOrNull()!!.tokens.single()
            val old = launch(start = CoroutineStart.UNDISPATCHED) { controller.lookup(word) }
            try {
                started.await()
                if (close) controller.close() else controller.disable()
                released.complete(Unit); old.join()
                assertEquals(EnglishAssistantState.Idle, controller.state.value)
            } finally { released.complete(Unit); old.join(); controller.close() }
        }
    }

    @Test fun disableRejectsLateRecognitionAndDoesNotCacheCancelledPages() = runBlocking {
        val started = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        val engine = object : EnglishOcrEngine {
            var calls = 0; var closed = false
            override suspend fun recognize(bitmap: Bitmap, pageKey: String): EnglishPageTokens {
                calls++
                if (calls == 1) withContext(NonCancellable) { started.complete(Unit); released.await() }
                return page(pageKey)
            }
            override fun close() { closed = true }
        }
        val controller = EnglishAssistantController(engine)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { controller.openPage("page", bitmap) }
        try {
            started.await(); controller.disable()
            released.complete(Unit); first.join()
            assertEquals(EnglishAssistantState.Idle, controller.state.value)
            controller.openPage("page", bitmap)
            assertEquals(2, engine.calls)
            controller.close()
            assertTrue(engine.closed)
            assertTrue(runCatching { controller.openPage("another", bitmap) }.isFailure)
            assertEquals(EnglishAssistantState.Idle, controller.state.value)
        } finally { released.complete(Unit); first.join(); controller.close() }
    }

    @Test fun aForgedOrOldPageSelectionDoesNotReachTheDictionary() = runBlocking {
        val dictionary = FakeDictionary(); val controller = EnglishAssistantController(FakeOcr(), dictionary)
        try {
            controller.openPage("page", bitmap)
            val word = controller.state.value.pageOrNull()!!.tokens.single()
            controller.lookup(word.copy(text = "fabricated"))
            controller.lookup(word, "old-page")
            assertEquals(0, dictionary.calls)
            assertTrue(controller.state.value is EnglishAssistantState.Ready)
        } finally { controller.close() }
    }

    @Test fun dismissingAPendingSelectionReturnsToThePageAndIgnoresItsLateAnswer() = runBlocking {
        val started = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        val controller = EnglishAssistantController(FakeOcr(), object : EnglishDictionaryProvider {
            override suspend fun lookup(token: EnglishToken, page: EnglishPageTokens): EnglishDictionaryEntry = withContext(NonCancellable) {
                started.complete(Unit); released.await(); EnglishDictionaryEntry(token.text)
            }
        })
        controller.openPage("page", bitmap)
        val word = controller.state.value.pageOrNull()!!.tokens.single()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { controller.lookup(word) }
        try {
            started.await(); controller.dismissSelection()
            released.complete(Unit); pending.join()
            assertTrue(controller.state.value is EnglishAssistantState.Ready)
        } finally { released.complete(Unit); pending.join(); controller.close() }
    }

    @Test fun phraseSelectionUsesContiguousWordsOfOneLineAndPreservesTheirGeometry() = runBlocking {
        val words = listOf(token("0", "slow"), token("1", "down", 50f), token("2", "hero", 90f), token("3", "elsewhere", 5f, "other-line"))
        val page = page("page", words)
        val phrase = checkNotNull(phraseFromRange(page, "1", "0"))
        assertEquals("slow down", phrase.text)
        assertEquals("slow down hero", phrase.lineText)
        assertEquals(RectF(10f, 12f, 85f, 40f), phrase.bounds)
        assertNull(phraseFromRange(page, "0", "3"))
        assertNull(phraseFromRange(page, "unknown", "1"))
        val controller = EnglishAssistantController(FakeOcr(words), FakeDictionary())
        try {
            controller.openPage("page", bitmap)
            controller.lookup(phrase)
            assertEquals("slow down", (controller.state.value as EnglishAssistantState.Selected).entry.original)
        } finally { controller.close() }
        val longLine = page("long", (0..12).map { token("$it", "word", it * 4f) })
        assertNull(phraseFromRange(longLine, "0", "12"))
    }

    @Test fun cacheEvictsByBothPageCountAndTextBudgetWithoutStoringBitmaps() {
        val cache = EnglishTokenCache(maxPages = 2, maxCharacters = 50)
        cache.put(page("one")); cache.put(page("two"))
        assertNotNull(cache.get("one"))
        cache.put(page("three"))
        assertNull(cache.get("two")); assertNotNull(cache.get("one")); assertNotNull(cache.get("three"))
        cache.put(page("huge", listOf(token("huge", "x".repeat(60)))))
        assertNull(cache.get("huge"))
        cache.clear()
        assertNull(cache.get("one"))
    }
}
