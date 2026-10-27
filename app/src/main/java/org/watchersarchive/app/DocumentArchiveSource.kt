package org.watchersarchive.app

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import org.watchersarchive.core.ArchiveByteSource
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException
import org.watchersarchive.core.CancellationToken
import org.watchersarchive.core.ReaderLimits
import org.watchersarchive.core.RemoteIdentity
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest

/**
 * Reads only ranges of the explicitly selected SAF document. No local staging
 * copy is made, even for multi-GB archives. Pipe/cloud providers that cannot seek
 * are rejected. Keep the document unchanged during a session: every read checks
 * its descriptor's size, device/inode and modification/change timestamps.
 *
 * The caller owns persistable URI permission and closes this source on both
 * failed opens and session exit. This supports the same CBZ/ZIP formats as core;
 * it does not add RAR/CBR or encrypted/split archive support.
 */
class DocumentArchiveSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val limits: ReaderLimits = ReaderLimits(),
) : ArchiveByteSource, Closeable {
    private var descriptor: ParcelFileDescriptor? = null
    private var closed = false

    init {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only a selected content document is accepted" }
    }

    @Synchronized
    override fun discover(token: CancellationToken): RemoteIdentity {
        token.throwIfCancelled()
        val fd = descriptor ?: openDescriptor().also { descriptor = it }
        val version = currentIdentity(fd)
        token.throwIfCancelled()
        return version
    }

    @Synchronized
    override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray {
        token.throwIfCancelled()
        if (start < 0 || length <= 0 || length > limits.maxRangeBytes || start > identity.length - length.toLong()) {
            throw ArchiveException(ArchiveError.INVALID_LENGTH, "本地漫画读取范围超出边界或单次上限")
        }
        val fd = descriptor ?: throw ArchiveException(ArchiveError.OBJECT_CHANGED, "本地阅读会话已结束，请重新打开漫画")
        if (currentIdentity(fd) != identity) changed()
        val bytes = ByteArray(length)
        var offset = 0
        try {
            while (offset < length) {
                token.throwIfCancelled()
                val count = try {
                    Os.pread(fd.fileDescriptor, bytes, offset, minOf(16 * 1024, length - offset), start + offset)
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EINTR) continue else throw error
                }
                if (count <= 0) throw ArchiveException(ArchiveError.INVALID_LENGTH, "本地漫画已截断，未提交不完整页面")
                offset += count
            }
            token.throwIfCancelled()
            if (currentIdentity(fd) != identity) changed()
            return bytes
        } catch (error: ErrnoException) {
            token.throwIfCancelled()
            throw ArchiveException(ArchiveError.NETWORK, "无法读取已选择的本地漫画，请确认文件仍可访问", error)
        }
    }

    private fun openDescriptor(): ParcelFileDescriptor {
        check(!closed) { "DocumentArchiveSource is closed" }
        val opened = try { resolver.openFileDescriptor(uri, "r") }
        catch (error: Exception) { throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "无法打开本地漫画，请重新选择文件并授予访问权限", error) }
            ?: throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "所选文档没有可读取的文件句柄")
        try {
            val stat = Os.fstat(opened.fileDescriptor)
            if (!OsConstants.S_ISREG(stat.st_mode)) throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "此文件来源不支持随机读取，请先将漫画保存到手机本地存储")
            // A positional one-byte read proves seekability without moving a shared
            // provider cursor or copying the complete document into cache.
            if (stat.st_size > 0 && Os.pread(opened.fileDescriptor, ByteArray(1), 0, 1, 0L) != 1) {
                throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "此文档不能按页随机读取")
            }
            return opened
        } catch (error: Throwable) {
            opened.close()
            if (error is ArchiveException) throw error
            throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "此文件来源不能按页随机读取", error)
        }
    }

    private fun currentIdentity(fd: ParcelFileDescriptor): RemoteIdentity {
        check(!closed) { "DocumentArchiveSource is closed" }
        val stat = try { Os.fstat(fd.fileDescriptor) }
        catch (error: ErrnoException) { throw ArchiveException(ArchiveError.OBJECT_CHANGED, "本地漫画句柄已失效，请重新打开", error) }
        if (stat.st_size < 22 || stat.st_size > limits.maxRemoteBytes) {
            throw ArchiveException(ArchiveError.INVALID_LENGTH, "本地漫画长度无效或超出读取上限")
        }
        val fingerprint = listOf(uri.toString(), stat.st_dev, stat.st_ino, stat.st_size,
            timestamp(stat, "st_mtim", stat.st_mtime), timestamp(stat, "st_ctim", stat.st_ctime)).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return RemoteIdentity(uri.toString(), stat.st_size, "\"local-$digest\"", stat.st_mtime.toString())
    }

    /** Nanosecond StructTimespec is available on recent Android; retain API26 support. */
    private fun timestamp(stat: StructStat, field: String, fallback: Long): String = runCatching {
        val value = stat.javaClass.getField(field).get(stat)
        val seconds = value.javaClass.getField("tv_sec").getLong(value)
        val nanos = value.javaClass.getField("tv_nsec").getLong(value)
        "$seconds:$nanos"
    }.getOrElse { fallback.toString() }

    private fun changed(): Nothing = throw ArchiveException(ArchiveError.OBJECT_CHANGED, "本地漫画在阅读过程中发生变化，请重新打开")

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        descriptor?.close()
        descriptor = null
    }
}
