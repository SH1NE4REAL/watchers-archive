package org.watchersarchive.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NetworkIndependenceTest {
    @Test fun realComicWithMissingSourceNeverFallsBackToTheComputerTestEndpoint() = runBlocking {
        val repository = AndroidRepository(ApplicationProvider.getApplicationContext())
        val original = repository.baseUrl
        val before = repository.traceLines()
        try {
            repository.baseUrl = "http://127.0.0.1:1"
            val comic = DemoComic("missing-real-source", "真实来源缺失测试", "", "fixture.cbz",
                isTestContent = false, sourceAssetStatus = SourceAssetStatus.READY,
                verifiedETag = "\"fixture-v1\"", verifiedLength = 100)
            val failure = runCatching { repository.open(comic) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals("漫画来源不可用，请返回详情重新打开。", failure?.message)
            assertEquals("No request is issued to the configured local fixture endpoint", before, repository.traceLines())
        } finally { repository.baseUrl = original }
    }
}
