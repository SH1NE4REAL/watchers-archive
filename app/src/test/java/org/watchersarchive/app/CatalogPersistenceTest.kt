package org.watchersarchive.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CatalogPersistenceTest {
    private fun record(id: String = "post:42", discovered: String = "2026-10-26T10:00:00Z") = CatalogSourceRecord(
        "getcomics", id, "https://getcomics.org/marvel/example/", "Example – #1", "Synopsis", null,
        "2026-10-25T00:00:00Z", null, discovered, listOf("3"), "2026", "77 MB", "CBZ", SourceConfidence.MEDIUM)

    @Test fun coldStartCreatesDefaultShelfAndRejectsEmptyOrReservedNames() {
        val root = Files.createTempDirectory("watchers-store-test-").toFile()
        try {
            val store = ShelfStore(root.resolve("shelves.properties"))
            assertEquals(listOf("默认书架"), store.shelves())
            for (name in listOf("", "   ", "全部")) {
                try { store.createShelf(name); fail("Invalid name must be rejected") } catch (_: IllegalArgumentException) { }
            }
            assertEquals(listOf("默认书架"), ShelfStore(root.resolve("shelves.properties")).shelves())
        } finally { root.deleteRecursively() }
    }

    @Test fun shelfMembershipAndSourceMetadataSurviveCacheClearAndReopen() {
        val root = Files.createTempDirectory("watchers-store-test-").toFile()
        try {
            val metadata = root.resolve("persistent/catalog.properties")
            val shelves = root.resolve("persistent/shelves.properties")
            CatalogMetadataStore(metadata).upsertAll(listOf(record()))
            ShelfStore(shelves).apply { createShelf("大事件"); add("大事件", "post:42"); add("大事件", "post:42") }
            root.resolve("cache/page.tmp").apply { parentFile.mkdirs(); writeText("disposable page") }
            root.resolve("cache").deleteRecursively()
            assertEquals(setOf("post:42"), ShelfStore(shelves).allItems())
            assertEquals(setOf("post:42"), ShelfStore(shelves).items("大事件"))
            assertEquals("77 MB", CatalogMetadataStore(metadata).records().single().sizeLabel)
        } finally { root.deleteRecursively() }
    }

    @Test fun metadataRefreshPreservesFirstDiscoveryAndUpdatesChangedFields() {
        val root = Files.createTempDirectory("watchers-store-test-").toFile()
        try {
            val file = root.resolve("catalog.properties")
            CatalogMetadataStore(file).upsertAll(listOf(record()))
            CatalogMetadataStore(file).upsertAll(listOf(record(discovered = "2026-10-27T10:00:00Z").copy(title = "New title")))
            val result = CatalogMetadataStore(file).records().single()
            assertEquals("New title", result.title)
            assertEquals("2026-10-26T10:00:00Z", result.firstDiscoveredAt)
        } finally { root.deleteRecursively() }
    }

    @Test fun officialSnapshotsAndVerifiedAssetIdentitySurviveRestart() {
        val root = Files.createTempDirectory("watchers-store-test-").toFile()
        try {
            val file = root.resolve("snapshots.json")
            val asset = SourceAsset("a", "post:42", "example.org", "https://example.org/file.cbz", "file.cbz", "CBZ", "5000",
                SourceAssetStatus.READY, "verified", "2026-10-27T00:00:00Z", "https://example.org/file.cbz", "\"v1\"", 5000, 4)
            val guide = OfficialGuide("1", "Guide", "Official description", "https://www.marvel.com/comics/guides/1/test", "2026-10-27",
                listOf(OfficialGuideSection(1, "Chapter", listOf(OfficialGuideIssue(1, "12", "https://www.marvel.com/comics/issue/12/test", "Official link")))))
            CatalogSnapshots(file).apply {
                saveInspection("post:42", SourceAssetInspection(record().canonicalUrl, listOf(asset), true))
                saveGuide(guide)
                saveCharacter(OfficialCharacterProfile("spider-man", "Spider-Man", "https://www.marvel.com/characters/spider-man", null, emptyList(), emptyList(), "2026-10-27"))
            }
            val reopened = CatalogSnapshots(file)
            assertEquals(guide, reopened.guides().single())
            assertEquals("Spider-Man", reopened.characters().single().name)
            assertTrue(reopened.inspection("post:42")!!.canStartRemoteReader)
            assertEquals("\"v1\"", reopened.inspection("post:42")!!.assets.single().verifiedETag)
            assertFalse(isVerifiedReady(asset.copy(verifiedETag = null)))
            assertFalse(isVerifiedReady(asset.copy(verifiedPageCount = null)))
        } finally { root.deleteRecursively() }
    }
}
