package org.watchersarchive.core

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Limits are checked before allocating, downloading, or decompressing untrusted data. */
data class ReaderLimits(
    val maxRangeBytes: Int = 4 * 1024 * 1024,
    val maxDirectoryBytes: Int = 8 * 1024 * 1024,
    val maxCompressedPageBytes: Int = 24 * 1024 * 1024,
    val maxPageBytes: Int = 32 * 1024 * 1024,
    val maxWorkingBytes: Long = 64L * 1024 * 1024,
    val maxImagePixels: Long = 20_000_000,
    val maxImageDimension: Int = 16_384,
    val maxEntries: Int = 20_000,
    val maxRemoteBytes: Long = 64L * 1024 * 1024 * 1024,
    val maxArchiveOpenTransferBytes: Long = 12L * 1024 * 1024,
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 10_000,
) {
    init {
        require(maxRangeBytes > 0 && maxDirectoryBytes >= 22)
        require(maxCompressedPageBytes > 0 && maxPageBytes > 0 && maxWorkingBytes > 0)
        require(maxImagePixels > 0 && maxImageDimension > 0 && maxEntries > 0)
        require(maxRemoteBytes >= 22 && maxArchiveOpenTransferBytes >= 22)
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
    }
}

enum class ArchiveError {
    RANGE_NOT_SUPPORTED, INVALID_CONTENT_RANGE, INVALID_LENGTH, OBJECT_CHANGED,
    IDENTITY_REQUIRED, HTTP_ERROR, NETWORK, CANCELLED, UNSUPPORTED_ARCHIVE,
    CORRUPT_ARCHIVE, LIMIT_EXCEEDED, UNSUPPORTED_COMPRESSION, UNSUPPORTED_IMAGE,
    CRC_MISMATCH,
}

class ArchiveException(val code: ArchiveError, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** HTTP uses a strong ETag; a local source uses its own opaque file-version token. */
data class RemoteIdentity(
    val url: String,
    val length: Long,
    val eTag: String,
    val lastModified: String?,
)

data class ArchivePage(
    val name: String,
    val localHeaderOffset: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val crc32: Long,
    val method: Int,
    val flags: Int,
    val rawName: ByteArray,
) {
    /** Stable within one archive version; progress also records archive identity. */
    val stableId: String get() = "$name:$crc32:$uncompressedSize"
}

data class ArchiveIndex(
    val identity: RemoteIdentity,
    val pages: List<ArchivePage>,
    val centralDirectoryOffset: Long,
    val isZip64: Boolean,
)

data class RangeTrace(
    val url: String,
    val start: Long,
    val endInclusive: Long,
    val statusCode: Int?,
    val contentRange: String?,
    /** Bytes consumed by this client, including one overrun-detection byte if supplied. */
    val receivedBytes: Long,
    val outcome: String,
)

/** Cancellation actively disconnects current HTTP reads, not only the next loop iteration. */
class CancellationToken {
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private val listeners = mutableSetOf<() -> Unit>()

    val isCancelled: Boolean get() = cancelled.get()

    fun throwIfCancelled() {
        if (isCancelled) throw ArchiveException(ArchiveError.CANCELLED, "读取已取消")
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        val callbacks = synchronized(lock) { listeners.toList().also { listeners.clear() } }
        callbacks.forEach { runCatching { it() } }
    }

    fun onCancel(callback: () -> Unit): Closeable {
        val invokeNow = synchronized(lock) {
            if (cancelled.get()) true else { listeners.add(callback); false }
        }
        if (invokeNow) callback()
        return Closeable { synchronized(lock) { listeners.remove(callback) } }
    }
}
