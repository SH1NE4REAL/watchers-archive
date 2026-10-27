package org.watchersarchive.app

import org.watchersarchive.core.ArchiveByteSource
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException
import org.watchersarchive.core.CancellationToken
import org.watchersarchive.core.HttpRangeSource
import org.watchersarchive.core.ReaderLimits
import org.watchersarchive.core.RemoteIdentity
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/** Blocking IO primitive for an explicit long-running Android download worker. */
class OfflineDownloadEngine(
    private val store: OfflineDownloadStore,
    private val limits: ReaderLimits = ReaderLimits(),
    private val sourceFactory: (OfflineDownloadSpec) -> ArchiveByteSource = { HttpRangeSource(it.url, limits) },
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val reserveBytes: Long = 64L * 1024 * 1024,
    private val chunkBytes: Int = minOf(limits.maxRangeBytes, 4 * 1024 * 1024),
) {
    init { require(chunkBytes in 1..limits.maxRangeBytes && reserveBytes >= 0) }

    fun download(
        spec: OfflineDownloadSpec,
        token: CancellationToken = CancellationToken(),
        onProgress: (OfflineDownloadStatus) -> Unit = {},
    ): CompletedOfflineDownload {
        token.throwIfCancelled()
        store.completed(spec.comicId)?.takeIf { it.spec.assetKey == spec.assetKey }?.let { return it }
        store.exclusive(spec).use {
            store.enqueue(spec)
            store.completed(spec.comicId)?.takeIf { it.spec.assetKey == spec.assetKey }?.let { return it }
            var committed = store.downloaded(spec)
            val partial = store.partial(spec)
            committed = minOf(committed, if (partial.isFile) partial.length() else 0L)
            var source: ArchiveByteSource? = null
            try {
                source = sourceFactory(spec)
                val identity = source.discover(token)
                if (identity.eTag != spec.eTag || identity.length != spec.length) {
                    throw ArchiveException(ArchiveError.OBJECT_CHANGED, "下载来源文件已变化，请重新打开漫画详情")
                }
                // Check total remaining storage before any large allocation or file expansion.
                ensureSpace(spec.length - committed)
                RandomAccessFile(partial, "rw").use { output ->
                    committed = minOf(committed, output.length())
                    output.setLength(committed)
                    output.seek(committed)
                    store.update(spec, OfflineDownloadState.DOWNLOADING, committed)
                    onProgress(checkNotNull(store.status(spec.comicId)))
                    while (committed < spec.length) {
                        token.throwIfCancelled()
                        val count = minOf(chunkBytes.toLong(), spec.length - committed).toInt()
                        ensureSpace(count.toLong())
                        val bytes = source.readRange(identity, committed, count, token)
                        if (bytes.size != count) throw ArchiveException(ArchiveError.INVALID_LENGTH, "下载片段不完整，未保存损坏内容")
                        token.throwIfCancelled()
                        try {
                            output.write(bytes)
                            output.fd.sync()
                        } catch (error: Exception) {
                            runCatching { output.setLength(committed) }
                            throw error
                        }
                        // Persist the durable checkpoint before allowing this offset to be reused.
                        val next = committed + count
                        store.update(spec, OfflineDownloadState.DOWNLOADING, next)
                        committed = next
                        onProgress(checkNotNull(store.status(spec.comicId)))
                    }
                    token.throwIfCancelled()
                    output.fd.sync()
                }
                token.throwIfCancelled()
                return store.finish(spec).also { onProgress(checkNotNull(store.status(spec.comicId))) }
            } catch (error: Throwable) {
                // Bytes beyond a persisted checkpoint are never used for resume.
                runCatching { if (partial.isFile && partial.length() > committed) RandomAccessFile(partial, "rw").use { it.setLength(committed) } }
                val cancelled = token.isCancelled || error is java.util.concurrent.CancellationException ||
                    error is InterruptedException || (error is ArchiveException && error.code == ArchiveError.CANCELLED)
                runCatching {
                    store.update(spec, if (cancelled) OfflineDownloadState.PAUSED else OfflineDownloadState.FAILED, committed,
                        if (cancelled) null else friendlyMessage(error))
                    onProgress(checkNotNull(store.status(spec.comicId)))
                }
                throw error
            } finally { (source as? Closeable)?.close() }
        }
    }

    private fun ensureSpace(required: Long) {
        val available = freeSpace(store.directory)
        if (available < reserveBytes || required > available - reserveBytes) {
            throw IllegalStateException("手机剩余空间不足，请释放空间后继续下载")
        }
    }
    private fun friendlyMessage(error: Throwable): String = when ((error as? ArchiveException)?.code) {
        ArchiveError.OBJECT_CHANGED -> "来源文件已变化，请重新打开详情后下载"
        ArchiveError.RANGE_NOT_SUPPORTED -> "此来源暂不支持安全下载，请换一部漫画或稍后重试"
        ArchiveError.IDENTITY_REQUIRED -> "此来源缺少可靠版本信息，暂时无法下载"
        ArchiveError.NETWORK, ArchiveError.HTTP_ERROR -> "下载暂时中断，可稍后继续"
        ArchiveError.INVALID_LENGTH, ArchiveError.INVALID_CONTENT_RANGE -> "来源返回的内容不完整，可稍后重试"
        else -> if (error is IllegalStateException) error.message ?: "下载失败，请稍后重试" else "下载失败，请稍后重试"
    }
}

/** Immutable private offline bytes retain the verified remote ETag without any network dependency. */
class OfflineArchiveSource(
    private val download: CompletedOfflineDownload,
    private val limits: ReaderLimits = ReaderLimits(),
) : ArchiveByteSource, Closeable {
    private val closed = AtomicBoolean(false)
    private val lease = download.store.acquireReading(download)
    private var input: RandomAccessFile? = null
    private val identity = RemoteIdentity(download.file.toURI().toString(), download.spec.length, download.spec.eTag, download.modified.toString())

    @Synchronized override fun discover(token: CancellationToken): RemoteIdentity {
        token.throwIfCancelled(); checkCurrent()
        if (input == null) input = RandomAccessFile(download.file, "r")
        return identity
    }
    @Synchronized override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray {
        token.throwIfCancelled(); checkCurrent()
        if (identity != this.identity || start < 0 || length !in 1..limits.maxRangeBytes || start > identity.length - length) {
            throw ArchiveException(ArchiveError.INVALID_LENGTH, "离线漫画读取范围无效")
        }
        val file = input ?: RandomAccessFile(download.file, "r").also { input = it }
        val bytes = ByteArray(length)
        file.seek(start)
        var offset = 0
        while (offset < length) {
            token.throwIfCancelled()
            val count = file.read(bytes, offset, minOf(16 * 1024, length - offset))
            if (count < 0) throw ArchiveException(ArchiveError.INVALID_LENGTH, "离线漫画文件不完整，请重新下载")
            offset += count
        }
        token.throwIfCancelled(); checkCurrent()
        return bytes
    }
    private fun checkCurrent() {
        if (closed.get() || !download.file.isFile || download.file.length() != identity.length || download.file.lastModified() != download.modified) {
            throw ArchiveException(ArchiveError.OBJECT_CHANGED, "离线漫画已变化，请重新打开或重新下载")
        }
    }
    @Synchronized override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { input?.close() } finally { input = null; lease.close() }
        }
    }
}
