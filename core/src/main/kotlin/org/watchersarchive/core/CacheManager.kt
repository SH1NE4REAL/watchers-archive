package org.watchersarchive.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CacheFullException(message: String) : IOException(message)

/** A single budget for pages, ranges, thumbnails and all temporary writes.
 *
 * The directory is exclusively locked for this manager's lifetime, including
 * across processes. Multiple callers share this instance. Accounting rounds
 * each payload up to a 4 KiB allocation unit and reserves a directory/lock
 * margin. There is no sidecar index file, so the logical payload plus margin
 * is the budgeted quantity. Android filesystem allocation still
 * requires device validation; the logical-byte limit is independently bounded.
 * No HTTP client or image-loader disk cache may write outside this manager.
 */
class CacheManager(val directory: File, val budgetBytes: Long = HARD_LIMIT) : Closeable {
    companion object {
        const val HARD_LIMIT = 500_000_000L
        private const val ALLOCATION_UNIT = 4096L
        private const val METADATA_MARGIN = 4096L
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun allocation(length: Long): Long {
            require(length >= 0 && length <= HARD_LIMIT)
            return if (length == 0L) 0 else ((length - 1) / ALLOCATION_UNIT + 1) * ALLOCATION_UNIT
        }
    }

    data class Snapshot(
        val limitBytes: Long,
        val committedBytes: Long,
        val temporaryBytes: Long,
        val reservedBytes: Long,
        val overheadBytes: Long,
        val fileCount: Int,
        val pinnedFiles: Int,
    ) {
        val accountedBytes: Long get() = committedBytes + temporaryBytes + reservedBytes + overheadBytes
        val availableBytes: Long get() = limitBytes - accountedBytes
    }

    private val mutex = ReentrantLock()
    private val pins = mutableMapOf<String, Int>()
    private val writers = mutableSetOf<CacheWrite>()
    private val lockChannel: FileChannel
    private val processLock: FileLock
    private var closed = false
    private val overhead = minOf(METADATA_MARGIN, budgetBytes)

    init {
        require(budgetBytes in 1..HARD_LIMIT) { "Cache budget must be in 1..$HARD_LIMIT" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create cache directory")
        lockChannel = FileChannel.open(File(directory, ".cache.lock").toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        processLock = try {
            lockChannel.tryLock() ?: throw IOException("Cache is already in use by another process")
        } catch (e: OverlappingFileLockException) {
            lockChannel.close()
            throw IOException("Cache already has an active manager", e)
        } catch (e: Throwable) {
            lockChannel.close()
            throw e
        }
        try {
            // A process crash leaves only uncommitted .part files. They are never readable entries.
            files().filter { it.name.endsWith(".part") }.forEach { deleteChecked(it) }
            // Reconcile the actual directory, not a possibly stale sidecar index.
            makeRoom(0)
        } catch (e: Throwable) {
            processLock.release()
            lockChannel.close()
            throw e
        }
    }

    fun snapshot(): Snapshot = mutex.withLock { ensureOpen(); snapshotLocked() }

    /** Acquiring a page marks this comic as read. The returned lease pins it until closed. */
    fun acquire(key: String, comicId: String): CacheLease? = mutex.withLock {
        ensureOpen()
        val file = entryFile(key, comicId)
        if (!file.isFile) return null
        if (!file.setLastModified(System.currentTimeMillis())) throw IOException("Cannot record cache reading time")
        lease(file)
    }

    fun put(key: String, comicId: String, bytes: ByteArray, cancellationCheck: () -> Unit = {}): CacheLease {
        beginWrite(key, comicId, bytes.size.toLong()).use { write ->
            var offset = 0
            while (offset < bytes.size) {
                cancellationCheck()
                val count = minOf(16 * 1024, bytes.size - offset)
                write.write(bytes, offset, count)
                offset += count
            }
            cancellationCheck()
            return write.commit()
        }
    }

    /** Reserve the complete output before opening a temporary file. */
    fun beginWrite(key: String, comicId: String, expectedBytes: Long): CacheWrite = mutex.withLock {
        ensureOpen()
        if (expectedBytes < 0 || expectedBytes > HARD_LIMIT) throw CacheFullException("Invalid cache write size")
        val target = entryFile(key, comicId)
        if (target.exists()) throw IOException("Entry already exists; acquire it first")
        if (writers.any { it.target == target }) throw IOException("Entry already has an active writer")
        val reserve = allocation(expectedBytes)
        // A concurrent writer needs its own temporary-file bookkeeping margin.
        // Charge that margin when another writer is already active so a second
        // reservation cannot consume the last rounded block.
        makeRoom(reserve + if (writers.isEmpty()) 0L else METADATA_MARGIN)
        val temporary = File(directory, "${target.name}.${java.util.UUID.randomUUID()}.part")
        val writer = CacheWrite(target, temporary, expectedBytes, reserve)
        writers += writer
        writer
    }

    /** Active readers and writers are never removed. Returns reclaimed accounting bytes. */
    fun clearUnpinned(): Long = mutex.withLock {
        ensureOpen()
        var reclaimed = 0L
        entries().filter { (pins[it.name] ?: 0) == 0 }.forEach {
            val charged = allocation(it.length())
            deleteChecked(it)
            reclaimed += charged
        }
        reclaimed
    }

    override fun close() = mutex.withLock {
        if (closed) return
        check(pins.isEmpty()) { "Close cache leases before closing CacheManager" }
        writers.toList().forEach { it.abortLocked() }
        closed = true
        processLock.release()
        lockChannel.close()
    }

    private fun ensureOpen() = check(!closed) { "CacheManager is closed" }
    private fun files(): List<File> = directory.listFiles()?.toList() ?: throw IOException("Cannot list cache")
    private fun entries() = files().filter { it.isFile && it.name.endsWith(".cache") }
    private fun entryFile(key: String, comicId: String) = File(directory, "${hash(comicId)}.${hash(key)}.cache")
    private fun deleteChecked(file: File) { if (!file.delete() && file.exists()) throw IOException("Cannot remove cache file ${file.name}") }

    private fun snapshotLocked(): Snapshot {
        val committed = entries()
        val temp = files().filter { it.isFile && it.name.endsWith(".part") }.sumOf { allocation(it.length()) }
        val reserved = writers.sumOf { (it.reservation - allocation(it.written)).coerceAtLeast(0) }
        // Unexpected files count as occupied too; only the zero-byte lock is excluded.
        val unknown = files().filter { it.name != ".cache.lock" && !it.name.endsWith(".cache") && !it.name.endsWith(".part") }
        val unknownBytes = unknown.sumOf { if (it.isFile) allocation(it.length()) else HARD_LIMIT }
        return Snapshot(budgetBytes, committed.sumOf { allocation(it.length()) }, temp,
            reserved, overhead + unknownBytes, committed.size, pins.size)
    }

    private fun makeRoom(required: Long) {
        if (required > budgetBytes - overhead) throw CacheFullException("Entry exceeds cache budget")
        if (snapshotLocked().availableBytes >= required) return
        // Evict an entire least-recently-read comic together. A pinned comic is skipped.
        val groups = entries().groupBy { it.name.substringBefore('.') }.values
            .sortedBy { group -> group.maxOf { it.lastModified() } }
        for (group in groups) {
            if (group.any { (pins[it.name] ?: 0) > 0 }) continue
            for (file in group) deleteChecked(file)
            if (snapshotLocked().availableBytes >= required) return
        }
        throw CacheFullException("Cache full: remaining entries are in use or reserved")
    }

    private fun lease(file: File): CacheLease {
        pins[file.name] = (pins[file.name] ?: 0) + 1
        return CacheLease(file)
    }

    inner class CacheLease internal constructor(val file: File) : Closeable {
        private var released = false
        fun readBytes(maximumBytes: Int = 32 * 1024 * 1024): ByteArray = mutex.withLock {
            check(!released) { "Cache lease is closed" }
            require(maximumBytes > 0)
            if (file.length() > maximumBytes) throw IOException("Cached payload exceeds memory read limit")
            file.readBytes()
        }
        override fun close() = mutex.withLock {
            if (!released) {
                released = true
                val count = checkNotNull(pins[file.name]) - 1
                if (count == 0) pins.remove(file.name) else pins[file.name] = count
            }
        }
    }

    inner class CacheWrite internal constructor(
        internal val target: File,
        private val temporary: File,
        val expectedBytes: Long,
        internal val reservation: Long,
    ) : OutputStream() {
        private var stream = FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        internal var written = 0L
        private var done = false

        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) = mutex.withLock {
            ensureOpen()
            check(!done) { "Cache write is closed" }
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            if (length.toLong() > expectedBytes - written) throw IOException("Write exceeds reservation")
            val buffer = java.nio.ByteBuffer.wrap(bytes, offset, length)
            try {
                while (buffer.hasRemaining()) {
                    val count = stream.write(buffer)
                    written += count
                }
            } catch (e: Throwable) { abortLocked(); throw e }
        }

        fun commit(): CacheLease = mutex.withLock {
            ensureOpen()
            check(!done) { "Cache write is closed" }
            if (written != expectedBytes) throw IOException("Incomplete cache write: $written/$expectedBytes")
            try {
                stream.force(true)
                stream.close()
                // Same-directory atomic rename: never duplicate the reserved payload.
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                target.setLastModified(System.currentTimeMillis())
                done = true
                writers.remove(this)
                lease(target)
            } catch (e: Throwable) { abortLocked(); throw e }
        }

        internal fun abortLocked() {
            if (done) return
            stream.close()
            deleteChecked(temporary)
            done = true
            writers.remove(this)
        }
        override fun close() = mutex.withLock { abortLocked() }
    }
}
