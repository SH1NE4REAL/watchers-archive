package org.watchersarchive.app

import org.junit.Assert.*
import org.junit.Test
import org.watchersarchive.core.PersistentLibrary
import java.nio.file.Files

class ShelfRemovalTest {
    @Test fun removingFromAllShelvesKeepsComicProgressBookmarksAndNotes() {
        val root = Files.createTempDirectory("watchers-shelf-removal-").toFile()
        try {
            val file = root.resolve("shelves.properties")
            ShelfStore(file).apply { createShelf("大事件"); add("默认书架", "post:1"); add("大事件", "post:1") }
            PersistentLibrary(root.resolve("library")).use { library ->
                library.registerComic("post:1", "A real catalogue title", "https://example.org/article", false)
                library.setProgress("post:1", 3); library.addBookmark("post:1", 2); library.setNote("post:1", 2, "keep this note")
            }
            ShelfStore(file).remove("全部", "post:1")
            assertTrue(ShelfStore(file).allItems().isEmpty())
            PersistentLibrary(root.resolve("library")).use { library ->
                assertEquals("post:1", library.comics().single().id)
                assertEquals(3, library.progress("post:1")); assertEquals(setOf(2), library.bookmarks("post:1"))
                assertEquals("keep this note", library.note("post:1", 2))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun movingMembershipIsDurableAndDoesNotLeaveTheBookInBothCategories() {
        val root = Files.createTempDirectory("watchers-shelf-removal-").toFile()
        try {
            val file = root.resolve("shelves.properties")
            ShelfStore(file).apply { add("默认书架", "post:1"); move(null, "事件", "post:1") }
            assertEquals(setOf("post:1"), ShelfStore(file).items("事件"))
            assertTrue(ShelfStore(file).items("默认书架").isEmpty())
            ShelfStore(file).move("事件", "默认书架", "post:1")
            assertTrue(ShelfStore(file).items("事件").isEmpty())
            assertEquals(setOf("post:1"), ShelfStore(file).items("默认书架"))
        } finally { root.deleteRecursively() }
    }
}
