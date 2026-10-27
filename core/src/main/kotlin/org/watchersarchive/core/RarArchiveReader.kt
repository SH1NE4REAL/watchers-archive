package org.watchersarchive.core

import com.github.junrar.Archive
import com.github.junrar.unpack.ComprDataIO
import com.github.junrar.unpack.Unpack
import com.github.junrar.unpack.ppm.ModelPPM
import com.github.junrar.unpack.ppm.SubAllocator
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.rarfile.FileHeader
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import java.io.OutputStream
import java.util.LinkedHashMap
import java.util.zip.CRC32

/**
 * RAR4 only, with independent files: solid, encrypted and multi-volume archives
 * are rejected before Junrar is constructed. Header scanning seeks past packed
 * data; the channel caches at most 2 MiB and caps transferred bytes per operation.
 * No temporary archive or whole-file download is created.
 */
class RarArchiveReader(
    private val source: ArchiveByteSource,
    private val limits: ReaderLimits = ReaderLimits(),
) : ComicArchiveReader {
    companion object {
        fun clearMemoryCache() = SharedRarBlocks.clear()
    }
    private var archive: Archive? = null
    private var channel: BoundedRarChannel? = null
    private var openedIndex: ArchiveIndex? = null
    private var pageHeaders: List<FileHeader> = emptyList()
    private var closed = false
    private var indexMemoryBytes = 0L

    @Synchronized override fun open(token: CancellationToken): ArchiveIndex {
        check(!closed) { "Reader is closed" }
        openedIndex?.let { token.throwIfCancelled(); return it }
        val identity = source.discover(token)
        val input = BoundedRarChannel(source, identity, limits)
        channel = input
        input.begin(token, limits.maxArchiveOpenTransferBytes, headerMode = true)
        try {
            val expectedOffsets = scanHeaders(input, identity)
            input.setPosition(0)
            val manager = VolumeManager { owner, previous ->
                if (previous != null) null else object : Volume {
                    override fun getChannel(): SeekableReadOnlyByteChannel = input
                    override fun getLength(): Long = identity.length
                    override fun getArchive(): Archive = owner
                }
            }
            val parsed = Archive(manager, null, null)
            archive = parsed
            input.failure?.let { throw it }
            if (parsed.mainHeader == null || parsed.mainHeader.isEncrypted || parsed.mainHeader.isSolid || parsed.mainHeader.isMultiVolume) {
                unsupported("不支持加密、固实或分卷 RAR4")
            }
            val headers = parsed.fileHeaders
            if (headers.size != expectedOffsets.size || headers.map { it.positionInFile } != expectedOffsets) corrupt("RAR 文件头索引不完整")
            val pages = headers.filter { !it.isDirectory && imageName(it.fileName) }.sortedWith { a, b -> naturalCompare(a.fileName, b.fileName) }
            if (pages.isEmpty()) throw ArchiveException(ArchiveError.UNSUPPORTED_IMAGE, "归档中没有支持的 PNG/JPEG 页面")
            if (pages.map { it.fileName }.toSet().size != pages.size) corrupt("RAR 页面名称重复")
            pages.forEach { header ->
                safeName(header.fileName)
                if (header.isEncrypted || header.isSolid || header.isSplitBefore || header.isSplitAfter) unsupported("不支持加密、固实或分卷 RAR4")
                if (header.fullPackSize <= 0 || header.fullPackSize > limits.maxCompressedPageBytes || header.fullUnpackSize <= 0 || header.fullUnpackSize > limits.maxPageBytes) limit("RAR 页面大小超出限制")
                if (header.fullUnpackSize + indexMemoryBytes + 6L * 1024 * 1024 > limits.maxWorkingBytes) limit("RAR 页面所需内存超出限制")
                val method = header.unpMethod.toInt() and 0xff
                val version = header.unpVersion.toInt() and 0xff
                if (method !in 0x30..0x35 || (method != 0x30 && version !in setOf(15, 20, 26, 29, 36))) unsupported("不支持此 RAR 压缩版本")
                if (method == 0x30 && header.fullPackSize != header.fullUnpackSize) corrupt("RAR STORE 条目长度不一致")
            }
            pageHeaders = pages
            installCodecGuard(parsed)
            val result = ArchiveIndex(identity, pages.map { header ->
                ArchivePage(header.fileName.replace('\\', '/'), header.positionInFile, header.fullPackSize, header.fullUnpackSize,
                    header.fileCRC.toLong() and 0xffffffffL, 1000 + (header.unpMethod.toInt() and 0xff), 0, header.fileName.toByteArray(Charsets.UTF_8))
            }, identity.length, false)
            openedIndex = result
            return result
        } catch (error: Throwable) {
            val reason = input.failure ?: unwrap(error)
            close()
            throw reason
        }
    }

    /** Junrar 7.5.5 otherwise accepts archive-controlled PPM allocations up to 256 MB. */
    private fun installCodecGuard(parsed: Archive) {
        try {
            val data = Archive::class.java.getDeclaredField("dataIO").apply { isAccessible = true }.get(parsed) as ComprDataIO
            val unpack = Unpack(data)
            val ppm = Unpack::class.java.getDeclaredField("ppm").apply { isAccessible = true }.get(unpack) as ModelPPM
            // The output estimate, retained headers, 4 MiB LZ window and 2 MiB
            // block cache are already reserved. Reject PPM entirely in this
            // release rather than permit an unbounded native/library allocation.
            val guard = BoundedPpmAllocator()
            ModelPPM::class.java.getDeclaredField("subAlloc").apply { isAccessible = true }.set(ppm, guard)
            if (ppm.subAlloc !== guard) unsupported("无法启用 RAR 内存保护")
            Archive::class.java.getDeclaredField("unpack").apply { isAccessible = true }.set(parsed, unpack)
        } catch (error: ArchiveException) { throw error }
        catch (error: Throwable) {
            throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "当前环境无法启用 RAR 解压内存保护", error)
        }
    }

    @Synchronized override fun readPage(index: ArchiveIndex, pageIndex: Int, token: CancellationToken): ByteArray {
        check(!closed) { "Reader is closed" }
        token.throwIfCancelled()
        if (openedIndex !== index && openedIndex != index) corrupt("RAR 阅读索引不属于当前文件")
        val header = pageHeaders.getOrNull(pageIndex) ?: throw ArchiveException(ArchiveError.INVALID_LENGTH, "页码超出范围")
        val input = checkNotNull(channel)
        if (source.discover(token) != index.identity) throw ArchiveException(ArchiveError.OBJECT_CHANGED, "漫画文件版本已改变，请重新打开")
        input.begin(token, limits.maxCompressedPageBytes.toLong() + 128 * 1024, headerMode = false)
        val result = ByteArray(header.fullUnpackSize.toInt())
        var written = 0
        val crc = CRC32()
        val output = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                token.throwIfCancelled()
                if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
                if (length > result.size - written) limit("RAR 页面解压超出声明长度")
                bytes.copyInto(result, written, offset, offset + length)
                crc.update(bytes, offset, length)
                written += length
            }
        }
        try {
            checkNotNull(archive).extractFile(header, output)
            input.failure?.let { throw it }
            token.throwIfCancelled()
            if (written != result.size) corrupt("RAR 页面解压长度不完整")
            if (crc.value != (header.fileCRC.toLong() and 0xffffffffL)) throw ArchiveException(ArchiveError.CRC_MISMATCH, "RAR 页面 CRC32 校验失败")
            validateImage(result, limits)
            return result
        } catch (error: Throwable) {
            input.failure?.let { throw it }
            throw unwrap(error)
        }
    }

    /** Strictly validate the header chain; Junrar intentionally tolerates some damaged headers. */
    private fun scanHeaders(input: BoundedRarChannel, identity: RemoteIdentity): List<Long> {
        if (!input.exactAt(0, 7).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0))) unsupported("不是受支持的 RAR4 文件")
        var at = 7L
        var main = false
        var headerBytes = 0L
        val fileOffsets = mutableListOf<Long>()
        var count = 0
        while (at < identity.length) {
            if (++count > limits.maxEntries + 16) limit("RAR 文件头数量超出限制")
            val base = input.exactAt(at, 7)
            val type = base[2].toInt() and 0xff
            val flags = u16(base, 3)
            val size = u16(base, 5)
            if (size < 7 || size > 32_767 || at > identity.length - size) corrupt("RAR 文件头长度无效")
            headerBytes += size
            if (headerBytes > limits.maxDirectoryBytes) limit("RAR 文件头总大小超出限制")
            // Junrar retains byte arrays, decoded names and per-header objects.
            // Reserve conservatively for these as well as the output/page buffers.
            indexMemoryBytes = headerBytes * 6 + count * 128L
            if (indexMemoryBytes > limits.maxWorkingBytes / 2) limit("RAR 文件头内存估计超出限制")
            val header = input.exactAt(at, size)
            val crc = CRC32().apply { update(header, 2, header.size - 2) }.value.toInt() and 0xffff
            if (crc != u16(header, 0)) corrupt("RAR 文件头 CRC 无效")
            var packed = if ((flags and 0x8000) != 0) { if (size < 11) corrupt("RAR 块被截断"); u32(header, 7) } else 0L
            when (type) {
                0x73 -> {
                    if (main || at != 7L || size < 13) corrupt("RAR 主文件头无效")
                    main = true
                    if ((flags and 0x0089) != 0) unsupported("不支持加密、固实或分卷 RAR4")
                }
                0x74 -> {
                    if (!main || size < 32) corrupt("RAR 文件头不完整")
                    if ((flags and 0x0017) != 0) unsupported("不支持加密、固实或分卷 RAR4")
                    val nameSize = u16(header, 26)
                    val nameStart = if ((flags and 0x0100) != 0) 40 else 32
                    if (nameSize <= 0 || nameSize > 4096 || nameStart > size - nameSize) corrupt("RAR 文件名长度无效")
                    packed = u32(header, 7)
                    if ((flags and 0x0100) != 0) {
                        if (size < 40) corrupt("RAR 大文件头不完整")
                        val high = u32(header, 32)
                        if (high > 0x7fffffffL) limit("RAR 文件偏移超出范围")
                        packed += high shl 32
                    }
                    if (packed < 0 || packed > identity.length - at - size) corrupt("RAR 压缩数据越界")
                    fileOffsets += at
                }
                0x7b -> {
                    if (!main || (flags and 1) != 0) unsupported("不支持分卷 RAR4")
                    return fileOffsets
                }
                0x75, 0x76, 0x77, 0x78, 0x79, 0x7a -> { if (!main) corrupt("RAR 主文件头缺失") }
                else -> unsupported("RAR 包含暂不支持的文件头类型")
            }
            if (packed > identity.length - at - size) corrupt("RAR 块越界")
            at += size + packed
        }
        corrupt("RAR 归档缺少结束记录")
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        runCatching { archive?.close() }
        channel?.close()
        archive = null
        pageHeaders = emptyList()
    }

    private fun unwrap(error: Throwable): ArchiveException {
        var cause: Throwable? = error
        repeat(8) {
            if (cause is ArchiveException) return cause as ArchiveException
            cause = cause?.cause
        }
        return ArchiveException(ArchiveError.CORRUPT_ARCHIVE, "RAR4 解压失败或文件损坏", error)
    }
}

/** Public to core regression tests; never delegates to the unbounded allocator. */
internal class BoundedPpmAllocator : SubAllocator() {
    override fun startSubAllocator(sizeMiB: Int): Boolean = unsupported("当前暂不支持使用 PPM 压缩的 RAR4，请选择普通 RAR4 或 CBZ")
}

/** Small in-memory read-through blocks; never creates a sparse/full temporary file. */
private class BoundedRarChannel(private val source: ArchiveByteSource, val identity: RemoteIdentity, val limits: ReaderLimits) : SeekableReadOnlyByteChannel {
    private var activeToken = CancellationToken()
    private var budget = 0L
    private var consumed = 0L
    private var blockSize = 512
    private var closed = false
    var failure: ArchiveException? = null
        private set
    private var cursor = 0L

    fun begin(token: CancellationToken, maximum: Long, headerMode: Boolean) { activeToken = token; budget = maximum; consumed = 0; blockSize = if (headerMode) 512 else 64 * 1024; failure = null }
    fun exactAt(at: Long, count: Int): ByteArray { setPosition(at); val result = ByteArray(count); readFully(result, count); return result }
    override fun getPosition(): Long = cursor
    override fun setPosition(pos: Long) { if (pos < 0 || pos > identity.length) corrupt("RAR seek 越界"); cursor = pos }
    override fun read(): Int { val byte = ByteArray(1); return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff }
    override fun read(buffer: ByteArray, off: Int, count: Int): Int {
        try {
            if (closed) corrupt("RAR 通道已关闭")
            activeToken.throwIfCancelled()
            if (off < 0 || count < 0 || off > buffer.size - count) throw IndexOutOfBoundsException()
            if (count == 0) return 0
            if (cursor >= identity.length) return -1
            var copied = 0
            val wanted = minOf(count.toLong(), identity.length - cursor).toInt()
            while (copied < wanted) {
                activeToken.throwIfCancelled()
                val start = cursor / blockSize * blockSize
                val length = minOf(blockSize.toLong(), identity.length - start).toInt()
                val key = RarBlockKey(identity.url, identity.eTag, identity.length, start, length, blockSize == 512)
                val block = SharedRarBlocks.get(key) ?: run {
                    if (consumed > budget - length) limit("RAR 本次读取超出传输预算")
                    val bytes = source.readRange(identity, start, length, activeToken)
                    if (bytes.size != length) corrupt("RAR 范围响应长度不正确")
                    consumed += length
                    SharedRarBlocks.put(key, bytes)
                    bytes
                }
                val inside = (cursor - start).toInt()
                val n = minOf(wanted - copied, block.size - inside)
                block.copyInto(buffer, off + copied, inside, inside + n)
                copied += n; cursor += n
            }
            return copied
        } catch (error: ArchiveException) { failure = error; throw error }
    }
    override fun readFully(buffer: ByteArray, count: Int): Int {
        if (count < 0 || count > buffer.size) throw IndexOutOfBoundsException()
        var at = 0
        while (at < count) {
            val read = read(buffer, at, count - at)
            if (read < 0) corrupt("RAR 数据被截断")
            at += read
        }
        return at
    }
    override fun close() { closed = true }
}

private data class RarBlockKey(val url: String, val version: String, val fileLength: Long, val offset: Long, val size: Int, val header: Boolean)

/** Reserve headers separately so decoding a 2 MB page cannot evict its entire index. */
private object SharedRarBlocks {
    private val headers = LinkedHashMap<RarBlockKey, ByteArray>(16, .75f, true)
    private val pages = LinkedHashMap<RarBlockKey, ByteArray>(16, .75f, true)
    private var headerBytes = 0
    private var pageBytes = 0
    @Synchronized fun get(key: RarBlockKey): ByteArray? = (if (key.header) headers else pages)[key]
    @Synchronized fun put(key: RarBlockKey, bytes: ByteArray) {
        val map = if (key.header) headers else pages
        val limit = if (key.header) 512 * 1024 else 1536 * 1024
        var occupied = if (key.header) headerBytes else pageBytes
        map.remove(key)?.let { occupied -= it.size }
        while (occupied + bytes.size > limit && map.isNotEmpty()) {
            val iterator = map.entries.iterator(); val oldest = iterator.next(); occupied -= oldest.value.size; iterator.remove()
        }
        if (bytes.size <= limit) { map[key] = bytes; occupied += bytes.size }
        if (key.header) headerBytes = occupied else pageBytes = occupied
    }
    @Synchronized fun clear() { headers.clear(); pages.clear(); headerBytes = 0; pageBytes = 0 }
}

private fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
private fun u32(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { value, offset -> value or ((bytes[at + offset].toLong() and 0xff) shl (8 * offset)) }
private fun corrupt(message: String): Nothing = throw ArchiveException(ArchiveError.CORRUPT_ARCHIVE, message)
private fun unsupported(message: String): Nothing = throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, message)
private fun limit(message: String): Nothing = throw ArchiveException(ArchiveError.LIMIT_EXCEEDED, message)
private fun safeName(name: String) { if (name.length > 4096 || name.startsWith('/') || name.contains('\u0000') || name.replace('\\', '/').split('/').any { it == ".." }) corrupt("RAR 页面名称不安全") }
private fun imageName(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg")
private fun naturalCompare(a: String, b: String): Int {
    val left = Regex("[0-9]+|[^0-9]+").findAll(a.lowercase()).map { it.value }.toList()
    val right = Regex("[0-9]+|[^0-9]+").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(left.size, right.size)) {
        val l = left[i]; val r = right[i]
        val compare = if (l.first().isDigit() && r.first().isDigit()) {
            val ln = l.trimStart('0').ifEmpty { "0" }; val rn = r.trimStart('0').ifEmpty { "0" }
            if (ln.length != rn.length) ln.length.compareTo(rn.length) else ln.compareTo(rn)
        } else l.compareTo(r)
        if (compare != 0) return compare
    }
    return left.size.compareTo(right.size)
}

private fun validateImage(bytes: ByteArray, limits: ReaderLimits) {
    fun be32(at: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 0xff) }
    val dimensions = if (bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))) {
        if (be32(8) != 13L || String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") corrupt("PNG IHDR 无效")
        be32(16) to be32(20)
    } else if (bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) {
        var at = 2
        var result: Pair<Long, Long>? = null
        while (at < bytes.size && result == null) {
            if (bytes[at] != 0xff.toByte()) corrupt("JPEG 标记无效")
            while (at < bytes.size && bytes[at] == 0xff.toByte()) at++
            if (at >= bytes.size) break
            val marker = bytes[at++].toInt() and 0xff
            if (marker == 0xda || marker == 0xd9) break
            if (marker == 1 || marker in 0xd0..0xd8) continue
            if (at > bytes.size - 2) corrupt("JPEG 段被截断")
            val size = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
            if (size < 2 || size > bytes.size - at) corrupt("JPEG 段长度无效")
            if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                if (size < 8) corrupt("JPEG SOF 被截断")
                val width = ((bytes[at + 5].toInt() and 0xff) shl 8) or (bytes[at + 6].toInt() and 0xff)
                val height = ((bytes[at + 3].toInt() and 0xff) shl 8) or (bytes[at + 4].toInt() and 0xff)
                result = width.toLong() to height.toLong()
            }
            at += size
        }
        result ?: throw ArchiveException(ArchiveError.UNSUPPORTED_IMAGE, "JPEG 缺少尺寸信息")
    } else throw ArchiveException(ArchiveError.UNSUPPORTED_IMAGE, "RAR 页面不是 PNG/JPEG")
    if (dimensions.first <= 0 || dimensions.second <= 0 || dimensions.first > limits.maxImageDimension || dimensions.second > limits.maxImageDimension || dimensions.first > limits.maxImagePixels / dimensions.second) limit("图片尺寸超出限制")
}
