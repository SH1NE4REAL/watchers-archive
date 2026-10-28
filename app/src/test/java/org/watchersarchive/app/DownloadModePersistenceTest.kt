package org.watchersarchive.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.watchersarchive.core.*
import java.security.MessageDigest

class DownloadModePersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun queuedDiscoveryHasNoFileSizeAndCannotRecoverAsACompletedEmptyArchive() {
        val store = OfflineDownloadStore(temporary.newFolder())
        val request = OfflineDownloadSpec("pending", "Pending book", "https://example.test/article", "", 0,
            mode = OfflineTransferMode.DISCOVER, verifyRemoteETag = false)
        store.enqueue(request)
        val restarted = OfflineDownloadStore(store.directory)
        assertEquals(0, restarted.status("pending")!!.totalBytes)
        assertNull(restarted.completed("pending"))
        assertEquals(OfflineTransferMode.DISCOVER, restarted.spec("pending")?.mode)
        assertFalse(restarted.status("pending")?.state == OfflineDownloadState.COMPLETED)
    }

    @Test fun rangeFilesFromEarlierVersionsKeepTheSameIdentityAndRemainReadable() {
        val store = OfflineDownloadStore(temporary.newFolder())
        val spec = OfflineDownloadSpec("legacy", "Old book", "https://example.test/book.cbz", "\"old-v1\"", 128)
        val expected = MessageDigest.getInstance("SHA-256").digest("${spec.comicId}\u0000${spec.url}\u0000${spec.eTag}\u0000${spec.length}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val source = object : ArchiveByteSource {
            override fun discover(token: CancellationToken) = RemoteIdentity(spec.url, spec.length, spec.eTag, null)
            override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken) = ByteArray(length) { 7 }
        }
        val completed = OfflineDownloadEngine(store, sourceFactory = { source }, freeSpace = { Long.MAX_VALUE }).download(spec)
        assertEquals("$expected.archive", completed.file.name)
        val metadata = store.directory.listFiles()!!.single { it.name.endsWith(".json") }
        val oldFormat = JSONObject(metadata.readText()).apply { remove("mode"); remove("verifyRemoteETag") }
        metadata.writeText(oldFormat.toString())
        val restarted = OfflineDownloadStore(store.directory)
        assertEquals(OfflineTransferMode.RANGE, restarted.spec("legacy")!!.mode)
        assertTrue(restarted.spec("legacy")!!.verifyRemoteETag)
        assertNotNull(restarted.completed("legacy"))
    }
}
