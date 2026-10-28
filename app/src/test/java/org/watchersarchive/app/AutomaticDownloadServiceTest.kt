package org.watchersarchive.app

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException

class AutomaticDownloadServiceTest {
    private val record = CatalogSourceRecord("getcomics", "comic", "https://getcomics.org/marvel/generated/",
        "Generated fixture", null, null, null, null, "2026-10-28T00:00:00Z", emptyList(), null, null, null, SourceConfidence.MEDIUM)
    private fun asset(id: String) = SourceAsset(id, record.externalId, "example.test", "https://example.test/$id.cbz",
        "$id.cbz", "CBZ", null, SourceAssetStatus.METADATA_ONLY, "", "2026-10-28T00:00:00Z")
    private fun spec(asset: SourceAsset, mode: OfflineTransferMode = OfflineTransferMode.RANGE) =
        OfflineDownloadSpec(record.externalId, record.title, asset.candidateUrl, "\"version-${asset.id}\"", 256, asset.fileName, mode)
    private fun inspection(assets: List<SourceAsset>) = SourceAssetInspection(record.canonicalUrl, assets, false)

    @Test fun automaticallySkipsQuotaAndExpiredSourcesThenSwitchesAfterTransferFailure() = runBlocking {
        val sources = listOf("quota", "expired", "range", "full").map(::asset)
        val resolved = mutableListOf<String>(); val prepared = mutableListOf<OfflineDownloadSpec>(); val transferred = mutableListOf<String>()
        val snapshots = mutableListOf<SourceAssetInspection>()
        val progress = mutableListOf<OfflineDownloadStatus>()
        val service = AutomaticDownloadService(record, { inspection(sources) }, { source ->
            resolved += source.id
            when (source.id) {
                "quota" -> source.copy(status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "免费额度已用完。")
                "expired" -> source.copy(status = SourceAssetStatus.EXPIRED, reason = "链接已失效。")
                else -> source
            }
        }, { source -> spec(source, if (source.id == "full") OfflineTransferMode.FULL else OfflineTransferMode.RANGE) },
            { prepared += it }, { selected, _ ->
                transferred += selected.url
                if (selected.url.contains("range")) throw ArchiveException(ArchiveError.NETWORK, "fixture")
            }, { snapshots += it }, { progress += it })
        val selected = service.download()
        assertEquals(OfflineTransferMode.FULL, selected.mode)
        assertEquals(sources.map { it.id }, resolved)
        assertEquals(listOf(sources[2].candidateUrl, sources[3].candidateUrl), transferred)
        assertEquals(2, prepared.size)
        assertTrue(progress.all { it.state == OfflineDownloadState.QUEUED && it.totalBytes == 0L })
        assertFalse(snapshots.last().canStartRemoteReader)
        assertEquals(4, snapshots.last().assets.size)
    }

    @Test fun aSuccessfulExistingVersionResumesBeforeFetchingTheArticle() = runBlocking {
        val existing = spec(asset("existing")); var inspections = 0; var transfers = 0
        val service = AutomaticDownloadService(record, { inspections++; inspection(emptyList()) }, { it }, { spec(it) }, {}, { selected, _ ->
            assertEquals(existing, selected); transfers++
        })
        assertEquals(existing, service.download(existing))
        assertEquals(1, transfers); assertEquals(0, inspections)
    }

    @Test fun failedExistingVersionDoesNotTransferAnIdenticalMirrorAgain() = runBlocking {
        val old = asset("old"); val next = asset("next"); val existing = spec(old)
        val transfers = mutableListOf<String>(); val events = mutableListOf<String>()
        val service = AutomaticDownloadService(record, { events += "inspect"; inspection(listOf(old, next)) }, { it }, { spec(it) }, {}, { selected, _ ->
            transfers += selected.url; events += "transfer"
            if (selected.url == old.candidateUrl) throw ArchiveException(ArchiveError.NETWORK, "fixture")
        })
        assertEquals(next.candidateUrl, service.download(existing).url)
        assertEquals(listOf("transfer", "inspect", "transfer"), events)
        assertEquals(listOf(old.candidateUrl, next.candidateUrl), transfers)
    }

    @Test fun diskFailuresStopInsteadOfDownloadingEveryMirror() = runBlocking {
        val sources = listOf(asset("one"), asset("two")); var resolutions = 0
        val service = AutomaticDownloadService(record, { inspection(sources) }, { resolutions++; it }, { spec(it) }, {}, { _, _ ->
            throw IOException("fixture disk full")
        })
        assertTrue(runCatching { service.download() }.exceptionOrNull() is IOException)
        assertEquals(1, resolutions)
    }

    @Test fun insufficientSpaceAndOccupiedFilesAreTerminalLocalErrors() = runBlocking {
        var transfers = 0
        val service = AutomaticDownloadService(record, { inspection(listOf(asset("one"), asset("two"))) }, { it }, { spec(it) },
            { throw IllegalStateException("手机空间不足") }, { _, _ -> transfers++ })
        assertTrue(runCatching { service.download() }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, transfers)
    }

    @Test fun coroutineCancellationStopsBeforeTryingAnotherSource() = runBlocking {
        var resolved = 0
        val service = AutomaticDownloadService(record, { inspection(listOf(asset("one"), asset("two"))) }, {
            resolved++; throw CancellationException("fixture cancelled")
        }, { spec(it) }, {}, { _, _ -> })
        assertTrue(runCatching { service.download() }.exceptionOrNull() is CancellationException)
        assertEquals(1, resolved)
    }

    @Test fun coreCancellationStopsRatherThanBeingTreatedAsADeadMirror() = runBlocking {
        var resolved = 0
        val service = AutomaticDownloadService(record, { inspection(listOf(asset("one"), asset("two"))) }, { resolved++; it }, { spec(it) }, {}, { _, _ ->
            throw ArchiveException(ArchiveError.CANCELLED, "fixture cancelled")
        })
        val failure = runCatching { service.download() }.exceptionOrNull()
        assertEquals(ArchiveError.CANCELLED, (failure as ArchiveException).code)
        assertEquals(1, resolved)
    }

    @Test fun duplicateSourceLinksAndLargeCandidateListsHaveABoundedAttemptCount() = runBlocking {
        val sources = (1..30).flatMap { listOf(asset("$it"), asset("$it")) }
        var resolved = 0; var probed = 0
        val service = AutomaticDownloadService(record, { inspection(sources) }, {
            resolved++; it.copy(status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "需要网页验证。")
        }, { probed++; spec(it) }, {}, { _, _ -> })
        val failure = runCatching { service.download() }.exceptionOrNull() as ComicSourceException
        assertEquals(12, resolved); assertEquals(0, probed)
        assertTrue(failure.message!!.contains("12"))
    }

    @Test fun localArchiveValidationFailureAdvancesToANewIsolatedSpec() = runBlocking {
        val sources = listOf(asset("bad"), asset("good")); val prepared = mutableListOf<OfflineDownloadSpec>()
        val service = AutomaticDownloadService(record, { inspection(sources) }, { it }, { spec(it) }, { prepared += it }, { selected, _ ->
            if (selected.url.contains("bad")) throw ArchiveException(ArchiveError.CORRUPT_ARCHIVE, "fixture invalid zip")
        })
        assertEquals(sources[1].candidateUrl, service.download().url)
        assertEquals(2, prepared.map { it.assetKey }.distinct().size)
    }

    @Test fun discoveryRequestsAreNeverTransferredAsFiles() = runBlocking {
        val pending = OfflineDownloadSpec(record.externalId, record.title, record.canonicalUrl, "", 0,
            mode = OfflineTransferMode.DISCOVER, verifyRemoteETag = false)
        var inspected = 0; var transferred = 0
        val service = AutomaticDownloadService(record, { inspected++; inspection(listOf(asset("file"))) }, { it }, { spec(it) }, {}, { selected, _ ->
            assertNotEquals(OfflineTransferMode.DISCOVER, selected.mode); transferred++
        })
        service.download(pending)
        assertEquals(1, inspected); assertEquals(1, transferred)
    }
}
