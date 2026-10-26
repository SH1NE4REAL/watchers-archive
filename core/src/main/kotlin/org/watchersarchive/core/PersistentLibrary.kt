package org.watchersarchive.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.Properties

data class LibraryComic(val id: String, val title: String, val sourceUrl: String, val testContent: Boolean)

/** Persistent user records live outside the disposable cache directory. */
class PersistentLibrary(val directory: File) : Closeable {
    private val file = File(directory, "library.properties")
    private val pending = File(directory, "library.properties.pending")
    private val properties = Properties()
    private val lockChannel: FileChannel
    private val processLock: FileLock
    private var closed = false

    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create library directory")
        lockChannel = FileChannel.open(File(directory, ".library.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        processLock = try {
            lockChannel.tryLock() ?: throw IOException("Library already in use")
        } catch (e: OverlappingFileLockException) {
            lockChannel.close(); throw IOException("Library already has an active manager", e)
        } catch (e: Throwable) { lockChannel.close(); throw e }
        try {
            if (file.exists()) file.inputStream().use { properties.load(it) }
            // Pending content was not committed. The previous atomic generation remains authoritative.
            if (pending.exists() && !pending.delete()) throw IOException("Cannot recover pending library update")
        } catch (e: Throwable) { processLock.release(); lockChannel.close(); throw e }
    }

    private fun key(id: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))
    private fun ensureOpen() = check(!closed) { "PersistentLibrary is closed" }

    @Synchronized fun registerComic(id: String, title: String, sourceUrl: String, testContent: Boolean = true) = update {
        val prefix = "comic.${key(id)}."
        setProperty(prefix + "id", id)
        setProperty(prefix + "title", title)
        setProperty(prefix + "url", sourceUrl)
        setProperty(prefix + "test", testContent.toString())
    }

    @Synchronized fun comics(): List<LibraryComic> {
        ensureOpen()
        return properties.stringPropertyNames().filter { it.startsWith("comic.") && it.endsWith(".id") }
            .map { idKey ->
                val prefix = idKey.removeSuffix("id")
                LibraryComic(properties.getProperty(idKey), properties.getProperty(prefix + "title", ""),
                    properties.getProperty(prefix + "url", ""), properties.getProperty(prefix + "test", "true").toBoolean())
            }.sortedBy { it.id }
    }

    @Synchronized fun progress(comicId: String): Int {
        ensureOpen(); return properties.getProperty("progress.${key(comicId)}", "0").toInt().coerceAtLeast(0)
    }
    @Synchronized fun setProgress(comicId: String, zeroBasedPage: Int) {
        require(zeroBasedPage >= 0)
        update { setProperty("progress.${key(comicId)}", zeroBasedPage.toString()) }
    }
    @Synchronized fun bookmarks(comicId: String): Set<Int> {
        ensureOpen()
        return properties.getProperty("bookmarks.${key(comicId)}", "").split(',')
            .filter { it.isNotEmpty() }.map { it.toInt() }.toSortedSet()
    }
    @Synchronized fun addBookmark(comicId: String, zeroBasedPage: Int) {
        require(zeroBasedPage >= 0)
        val values = bookmarks(comicId) + zeroBasedPage
        update { setProperty("bookmarks.${key(comicId)}", values.sorted().joinToString(",")) }
    }
    @Synchronized fun removeBookmark(comicId: String, zeroBasedPage: Int) {
        val values = bookmarks(comicId) - zeroBasedPage
        update { setProperty("bookmarks.${key(comicId)}", values.sorted().joinToString(",")) }
    }
    @Synchronized fun setNote(comicId: String, zeroBasedPage: Int, note: String) {
        require(zeroBasedPage >= 0)
        update { setProperty("note.${key(comicId)}.$zeroBasedPage", note) }
    }
    @Synchronized fun note(comicId: String, zeroBasedPage: Int): String {
        ensureOpen(); return properties.getProperty("note.${key(comicId)}.$zeroBasedPage", "")
    }

    private fun update(change: Properties.() -> Unit) {
        ensureOpen()
        val previous = Properties().also { it.putAll(properties) }
        try {
            properties.change()
            pending.outputStream().use { output ->
                properties.store(output, "The Watcher's Archive persistent test library")
                output.flush()
                // FileOutputStream.flush alone does not make a crash-safe generation.
                (output as java.io.FileOutputStream).fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            properties.clear(); properties.putAll(previous)
            throw e
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        processLock.release()
        lockChannel.close()
    }
}
