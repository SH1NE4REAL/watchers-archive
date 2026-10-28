package org.watchersarchive.app

import org.watchersarchive.core.ArchiveByteSource
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException
import org.watchersarchive.core.CancellationToken
import org.watchersarchive.core.ReaderLimits
import org.watchersarchive.core.RemoteIdentity
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** The downloader owns the writer lease while this validates its private partial file. */
class PrivateArchiveFileSource(
    private val file: File,
    private val limits: ReaderLimits = ReaderLimits(),
) : ArchiveByteSource, Closeable {
    private var input: RandomAccessFile? = null
    private var identity: RemoteIdentity? = null
    private var closed = false

    @Synchronized override fun discover(token: CancellationToken): RemoteIdentity {
        token.throwIfCancelled()
        check(!closed) { "文件校验会话已关闭" }
        val current = currentIdentity()
        identity?.let { if (it != current) changed() }
        identity = current
        if (input == null) input = RandomAccessFile(file, "r")
        token.throwIfCancelled()
        return current
    }

    @Synchronized override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray {
        token.throwIfCancelled()
        if (closed || identity != this.identity || currentIdentity() != identity) changed()
        if (start < 0 || length !in 1..limits.maxRangeBytes || start > identity.length - length.toLong()) {
            throw ArchiveException(ArchiveError.INVALID_LENGTH, "离线文件校验范围无效")
        }
        val reader = input ?: throw ArchiveException(ArchiveError.OBJECT_CHANGED, "文件校验会话未打开")
        val bytes = ByteArray(length)
        reader.seek(start)
        var offset = 0
        while (offset < length) {
            token.throwIfCancelled()
            val count = reader.read(bytes, offset, minOf(16 * 1024, length - offset))
            if (count < 0) throw ArchiveException(ArchiveError.INVALID_LENGTH, "下载文件不完整")
            if (count == 0) continue
            offset += count
        }
        token.throwIfCancelled()
        if (currentIdentity() != identity) changed()
        return bytes
    }

    private fun currentIdentity(): RemoteIdentity {
        if (!file.isFile || file.length() !in 22..limits.maxRemoteBytes) {
            throw ArchiveException(ArchiveError.INVALID_LENGTH, "下载文件大小无效")
        }
        val token = "${file.canonicalPath}|${file.length()}|${file.lastModified()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        return RemoteIdentity(file.toURI().toString(), file.length(), "\"private-$digest\"", file.lastModified().toString())
    }

    private fun changed(): Nothing = throw ArchiveException(ArchiveError.OBJECT_CHANGED, "待校验的下载文件发生变化")

    @Synchronized override fun close() {
        if (!closed) { closed = true; try { input?.close() } finally { input = null } }
    }
}
