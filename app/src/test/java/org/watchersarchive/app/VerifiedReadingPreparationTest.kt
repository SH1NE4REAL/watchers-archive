package org.watchersarchive.app

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VerifiedReadingPreparationTest {
    private val now = Instant.parse("2026-10-27T12:00:00Z")
    private fun record() = CatalogSourceRecord("getcomics", "post:99", "https://getcomics.org/marvel/preparation-fixture/",
        "Synthetic preparation fixture", null, null, null, null, now.toString(), emptyList(), null, null, "CBZ", SourceConfidence.MEDIUM)
    private fun asset() = SourceAsset("asset-99", "post:99", "example.test", "https://example.test/book.cbz", "book.cbz", "CBZ", null,
        SourceAssetStatus.READY, "Validated fixture", now.minusSeconds(60).toString(), "https://example.test/book.cbz", "\"v1\"", 100, 3)
    private fun snapshot(asset: SourceAsset = asset()) = SourceAssetInspection(record().canonicalUrl, listOf(asset), true)
    private fun comic(asset: SourceAsset = asset()) = DemoComic("post:99", "Fixture", "", "book.cbz", isTestContent = false,
        sourceUrl = asset.verifiedUrl, sourceAssetStatus = SourceAssetStatus.READY, sourceRecordId = "post:99",
        verifiedETag = asset.verifiedETag, verifiedLength = asset.verifiedLength)

    @Test fun recentReadySkipsAllArticleAndHostValidationCallbacks() = runBlocking {
        var checks = 0
        val saved = snapshot()
        val ready = VerifiedReadingPreparation.resolve(record(), saved, now) { checks++; error("Warm prepare must not repeat validation") }
        assertSame(saved.assets.single(), ready)
        assertEquals(0, checks)
    }

    @Test fun expiredFutureAndMalformedDatesRequireFreshVerification() = runBlocking {
        var checks = 0
        for (checked in listOf(now.minusSeconds(600).toString(), now.plusSeconds(1).toString(), "not-a-date")) {
            val refreshed = asset().copy(id = "refreshed")
            assertSame(refreshed, VerifiedReadingPreparation.resolve(record(), snapshot(asset().copy(checkedAt = checked)), now) { checks++; refreshed })
        }
        assertEquals(3, checks)
    }

    @Test fun sourceArticleAndObjectMetadataMustStillMatchTheRecord() {
        assertNull(VerifiedReadingPreparation.reusableAsset(record(), snapshot().copy(articleUrl = "https://getcomics.org/marvel/another/"), now))
        assertNull(VerifiedReadingPreparation.reusableAsset(record(), snapshot(asset().copy(sourceRecordId = "post:100")), now))
        assertNull(VerifiedReadingPreparation.reusableAsset(record().copy(sourceModifiedAt = now.toString()), snapshot(), now))
        assertNull(VerifiedReadingPreparation.reusableAsset(record().copy(source = "local"), snapshot(), now))
        val fallbackId = asset().copy(sourceRecordId = "article:${record().canonicalUrl}")
        assertSame(fallbackId, VerifiedReadingPreparation.reusableAsset(record(), snapshot(fallbackId), now))
    }

    @Test fun onlyCompleteStrongVersionedReadyMetadataCanUseTheShortcut() {
        val invalid = listOf(asset().copy(status = SourceAssetStatus.METADATA_ONLY), asset().copy(verifiedETag = "W/\"v1\""),
            asset().copy(verifiedUrl = "file:///private/book.cbz"), asset().copy(verifiedLength = 0), asset().copy(verifiedPageCount = null),
            asset().copy(verifiedPageCount = 20_001))
        invalid.forEach { assertNull(VerifiedReadingPreparation.reusableAsset(record(), snapshot(it), now)) }
        assertNull(VerifiedReadingPreparation.reusableAsset(record(), snapshot().copy(canStartRemoteReader = false), now))
    }

    @Test fun failedOpenInvalidatesOnlyTheMatchingVersionAndRetainsOtherSources() {
        val other = asset().copy(id = "other", verifiedUrl = "https://example.test/other.cbz", candidateUrl = "https://example.test/other.cbz")
        val saved = snapshot().copy(assets = listOf(asset(), other))
        val updated = VerifiedReadingPreparation.invalidate(saved, comic())
        assertEquals(SourceAssetStatus.EXPIRED, updated.assets.first().status)
        assertNull(updated.assets.first().verifiedETag)
        assertTrue(updated.canStartRemoteReader)
        assertSame(other, updated.assets.last())
        val newVersion = snapshot(asset().copy(verifiedETag = "\"v2\""))
        assertSame(newVersion, VerifiedReadingPreparation.invalidate(newVersion, comic()))
    }

    @Test fun cancellationNeverTurnsAnUnfinishedRefreshIntoAWarmResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var delivered = false
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            VerifiedReadingPreparation.resolve(record(), null, now) { entered.complete(Unit); awaitCancellation() }
            delivered = true
        }
        entered.await(); work.cancelAndJoin()
        assertFalse(delivered)
        assertTrue(work.isCancelled)
    }

    @Test fun realLoopbackColdValidationIsNotRepeatedOnWarmPreparation() = runBlocking {
        PreparationRangeFixture().use { fixture ->
            var saved: SourceAssetInspection? = null
            var validatedPages = 0
            val adapter = GetComicsCatalogService(now = { now.toString() }, decodeImage = { it.contentEquals(fixture.png) },
                onValidatedPage = { _, _ -> validatedPages++ })
            val record = record().copy(canonicalUrl = fixture.articleUrl)
            suspend fun prepare(): SourceAsset = VerifiedReadingPreparation.resolve(record, saved, now) {
                val inspected = adapter.inspectArticle(record.canonicalUrl)
                val ready = adapter.validateAsset(inspected.assets.single())
                assertTrue(isVerifiedReady(ready))
                saved = inspected.copy(assets = listOf(ready), canStartRemoteReader = true)
                ready
            }
            val first = prepare()
            val coldRequests = fixture.requests.get()
            assertEquals(1, fixture.articleRequests.get())
            assertTrue(coldRequests >= 6)
            assertEquals(1, validatedPages)
            assertEquals(first, prepare())
            assertEquals(coldRequests, fixture.requests.get())
            assertEquals(1, fixture.articleRequests.get())
            assertEquals(1, validatedPages)
            println("PREPARATION_HTTP cold=$coldRequests warm=${fixture.requests.get() - coldRequests} firstPageValidations=$validatedPages")
        }
    }
}

/** Small self-generated archive; no source site, copyrighted artwork or full large-file transfer. */
internal class PreparationRangeFixture(private val blockFirstBody: Boolean = false) : Closeable {
    val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQVDIGAACuAGcVHqFfAAAAAElFTkSuQmCC")
    val archive = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
        repeat(3) { zip.putNextEntry(ZipEntry("test-$it.png")); zip.write(png); zip.closeEntry() }
    } }.toByteArray()
    @Volatile var eTag = "\"v1\""
    val requests = AtomicInteger()
    val articleRequests = AtomicInteger()
    val firstBodyStarted = CompletableDeferred<Unit>()
    val releaseBody = CountDownLatch(1)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    val fileUrl get() = "http://127.0.0.1:${server.localPort}/book.cbz"
    val articleUrl get() = "http://127.0.0.1:${server.localPort}/article"
    private val accept = Thread {
        while (!server.isClosed) {
            val client = try { server.accept() } catch (_: Exception) { break }
            workers.submit { client.use { socket ->
                runCatching {
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@runCatching
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    requests.incrementAndGet()
                    val output = socket.getOutputStream()
                    if (path == "/article") {
                        articleRequests.incrementAndGet()
                        val html = "<article id='post-99'><a href='$fileUrl'>Synthetic file</a></article>".toByteArray()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        output.write(html)
                    } else {
                        val range = headers.getValue("range").removePrefix("bytes=").split('-')
                        val start = range[0].toInt(); val end = range[1].toInt()
                        val count = end - start + 1
                        if (headers["if-match"]?.let { it != eTag } == true) {
                            output.write("HTTP/1.1 412 Precondition Failed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            output.write(("HTTP/1.1 206 Partial Content\r\nETag: $eTag\r\nContent-Range: bytes $start-$end/${archive.size}\r\nContent-Length: $count\r\nConnection: close\r\n\r\n").toByteArray())
                            output.flush()
                            if (blockFirstBody && start == 0 && end == 0) {
                                firstBodyStarted.complete(Unit)
                                releaseBody.await(5, TimeUnit.SECONDS)
                            }
                            output.write(archive, start, count)
                        }
                    }
                    output.flush()
                }
            } }
        }
    }.apply { isDaemon = true; start() }
    override fun close() { releaseBody.countDown(); server.close(); accept.join(1000); workers.shutdownNow() }
}
