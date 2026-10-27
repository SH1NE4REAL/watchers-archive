package org.watchersarchive.app

import org.junit.Assert.*
import org.junit.Test
import org.watchersarchive.core.PersistentLibrary
import java.io.IOException
import java.nio.file.Files

class ShelfDeletionTest {
    @Test fun deletingCategoryReturnsBooksAndKeepsFilesAndPersistentUserData() {
        val root = Files.createTempDirectory("watchers-category-deletion-").toFile()
        try {
            val file = root.resolve("shelves.properties")
            val sourceFile = root.resolve("user-comic.cbz").apply { writeText("user-owned source file") }
            ShelfStore(file).apply {
                add("默认书架", "post:0")
                createShelf("大事件"); add("大事件", "post:1"); add("大事件", "post:2")
                createShelf("收藏"); add("收藏", "post:2")
            }
            PersistentLibrary(root.resolve("library")).use { library ->
                library.registerComic("post:1", "Preserved catalogue record", sourceFile.toURI().toString(), false)
                library.setProgress("post:1", 3)
                library.addBookmark("post:1", 2)
                library.setNote("post:1", 2, "Preserved note")
            }
            ShelfStore(file).deleteShelf("大事件")
            val reopened = ShelfStore(file)
            assertFalse(reopened.shelves().contains("大事件"))
            assertEquals(setOf("post:0", "post:1", "post:2"), reopened.items("默认书架"))
            assertEquals(setOf("post:2"), reopened.items("收藏"))
            assertEquals("user-owned source file", sourceFile.readText())
            PersistentLibrary(root.resolve("library")).use { library ->
                assertEquals("post:1", library.comics().single().id)
                assertEquals(3, library.progress("post:1"))
                assertEquals(setOf(2), library.bookmarks("post:1"))
                assertEquals("Preserved note", library.note("post:1", 2))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyAndMissingCategoriesAreSafeButReservedShelvesCannotBeDeleted() {
        val root = Files.createTempDirectory("watchers-category-deletion-").toFile()
        try {
            val file = root.resolve("shelves.properties")
            val shelves = ShelfStore(file)
            shelves.add("默认书架", "post:1")
            shelves.createShelf("空分类")
            shelves.deleteShelf("空分类")
            shelves.deleteShelf("空分类")
            for (name in listOf("默认书架", "全部", "")) {
                try { shelves.deleteShelf(name); fail("Reserved or empty category must be rejected") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(listOf("默认书架"), ShelfStore(file).shelves())
            assertEquals(setOf("post:1"), ShelfStore(file).items("默认书架"))
        } finally { root.deleteRecursively() }
    }

    @Test fun failedDeletionCommitPreservesBothMemoryAndTheLastDiskSnapshot() {
        val root = Files.createTempDirectory("watchers-category-deletion-").toFile()
        try {
            val file = root.resolve("shelves.properties")
            val shelves = ShelfStore(file).apply { createShelf("大事件"); add("大事件", "post:1") }
            val blockedPending = root.resolve("shelves.properties.pending")
            assertTrue(blockedPending.mkdir()) // A deterministic write failure, not a permission change.
            try { shelves.deleteShelf("大事件"); fail("Commit must fail") }
            catch (_: IOException) { }
            assertTrue(shelves.shelves().contains("大事件"))
            assertEquals(setOf("post:1"), shelves.items("大事件"))
            assertTrue(shelves.items("默认书架").isEmpty())
            assertTrue(blockedPending.delete())
            val reopened = ShelfStore(file)
            assertTrue(reopened.shelves().contains("大事件"))
            assertEquals(setOf("post:1"), reopened.items("大事件"))
        } finally { root.deleteRecursively() }
    }
}
