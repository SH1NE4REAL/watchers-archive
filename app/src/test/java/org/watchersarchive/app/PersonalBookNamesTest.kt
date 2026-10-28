package org.watchersarchive.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PersonalBookNamesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun threeImportedBooksKeepIndependentPersonalNamesAfterReload() {
        val file = temporary.newFolder().resolve("personal-book-names.properties")
        val names = PersonalBookNames(file)
        names.set("local:first", "  我的小蜘蛛  ")
        names.set("local:second", "我的内战合集")
        names.set("local:third", "📚 X 战警：第一册")
        val reloaded = PersonalBookNames(file)
        assertEquals("我的小蜘蛛", reloaded.title("local:first"))
        assertEquals("我的内战合集", reloaded.title("local:second"))
        assertEquals("📚 X 战警：第一册", reloaded.title("local:third"))
        assertEquals(3, reloaded.titles().size)
    }

    @Test fun resetOnlyRemovesTheSelectedPersonalLabel() {
        val file = temporary.newFolder().resolve("names.properties")
        val names = PersonalBookNames(file)
        names.set("post:1", "第一本")
        names.set("post:2", "第二本")
        names.set("post:1", null)
        assertNull(PersonalBookNames(file).title("post:1"))
        assertEquals("第二本", PersonalBookNames(file).title("post:2"))
        names.set("post:2", "   ")
        assertTrue(PersonalBookNames(file).titles().isEmpty())
    }

    @Test fun sourceRefreshKeepsOriginalNameAndIdentityWhileThePersonalLabelSurvives() {
        val root = temporary.newFolder()
        val namesFile = root.resolve("names.properties")
        val metadataFile = root.resolve("catalog.properties")
        val original = record("local:first", "Original filename")
        val source = root.resolve("original.cbz").apply { writeText("test archive sentinel") }
        val names = PersonalBookNames(namesFile)
        CatalogMetadataStore(metadataFile).upsertAll(listOf(original))
        names.set(original.externalId, "自己的书名")
        CatalogMetadataStore(metadataFile).upsertAll(listOf(original.copy(title = "Refreshed source title")))
        val refreshed = CatalogMetadataStore(metadataFile).records().single()
        val shown = refreshed.copy(customTitle = PersonalBookNames(namesFile).title(refreshed.externalId))
        assertEquals("自己的书名", shown.displayTitle)
        assertEquals("Refreshed source title", shown.title)
        assertEquals(original.externalId, shown.externalId)
        assertEquals(original.canonicalUrl, shown.canonicalUrl)
        assertEquals("test archive sentinel", source.readText())
        assertEquals("Refreshed source title", shown.copy(customTitle = null).displayTitle)
    }

    @Test fun failurePreservesTheCommittedNameInMemoryAndOnDisk() {
        val root = temporary.newFolder()
        val file = root.resolve("names.properties")
        val names = PersonalBookNames(file)
        names.set("local:book", "原自定义名")
        val blocked = root.resolve("names.properties.pending").apply { mkdir() }
        try { names.set("local:book", "新自定义名"); fail("Expected snapshot write failure") }
        catch (_: IOException) { }
        assertEquals("原自定义名", names.title("local:book"))
        assertTrue(blocked.delete())
        assertEquals("原自定义名", PersonalBookNames(file).title("local:book"))
    }

    @Test fun boundedSingleLineUnicodeNamesRejectInvalidInputWithoutChangingTheSavedValue() {
        val file = temporary.newFolder().resolve("names.properties")
        val names = PersonalBookNames(file)
        names.set("book", "🕷️".repeat(60)) // 120 code points, not 120 UTF-16 code units.
        val preserved = names.title("book")
        for (invalid in listOf("a".repeat(121), "书名\n第二行", "\t书名", "书名\u0000", "书名\u2028第二行")) {
            try { names.set("book", invalid); fail("Invalid custom title must be rejected") }
            catch (_: IllegalArgumentException) { }
            assertEquals(preserved, names.title("book"))
        }
        assertEquals(preserved, PersonalBookNames(file).title("book"))
    }

    @Test fun similarlyNamedBooksAndUnusualIdsDoNotMergeOrBecomeFilePaths() {
        val root = temporary.newFolder()
        val names = PersonalBookNames(root.resolve("names.properties"))
        names.set("local:a", "同一个名字")
        names.set("post:a", "同一个名字")
        names.set("../../outside", "仍在私有索引")
        assertEquals(3, names.titles().size)
        assertEquals("仍在私有索引", names.title("../../outside"))
        assertFalse(root.parentFile.resolve("outside").exists())
        assertEquals(setOf("names.properties"), root.listFiles()!!.map { it.name }.toSet())
    }

    private fun record(id: String, title: String) = CatalogSourceRecord("local", id, "content://test.provider/$id", title,
        null, null, null, null, "2026-10-28T00:00:00Z", emptyList(), null, null, "CBZ", SourceConfidence.VERIFIED)
}
