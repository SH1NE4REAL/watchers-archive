package org.watchersarchive.core

import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Bounded random-access ZIP/ZIP64 reader. It writes no files and never fetches the whole archive. */
class ZipArchiveReader(
    private val source: HttpRangeSource,
    private val limits: ReaderLimits = ReaderLimits(),
) {
    fun open(token: CancellationToken = CancellationToken()): ArchiveIndex {
        val identity = source.discover(token)
        if (identity.length < 22) corrupt("文件过短，不是 ZIP 归档")
        var transferred = 1L
        fun fetch(offset: Long, size: Int): ByteArray {
            if (size <= 0 || transferred > limits.maxArchiveOpenTransferBytes - size.toLong()) {
                limit("打开归档所需传输超出预算")
            }
            transferred += size
            return readAt(identity, offset, size, token)
        }

        val tailSize = minOf(identity.length, 65_557L).toInt()
        val tailStart = identity.length - tailSize
        val tail = fetch(tailStart, tailSize)
        var eocd = -1
        for (i in tail.size - 22 downTo 0) {
            if (u32(tail, i) == 0x06054b50L && i + 22L + u16(tail, i + 20) == tail.size.toLong()) {
                eocd = i
                break
            }
        }
        if (eocd < 0) unsupported("未找到 ZIP EOCD；不支持 CBR/RAR、分卷或尾随数据")
        val eocdOffset = tailStart + eocd
        val disk = u16(tail, eocd + 4)
        val directoryDisk = u16(tail, eocd + 6)
        val diskEntries = u16(tail, eocd + 8)
        val totalEntries = u16(tail, eocd + 10)
        if (disk != 0 || directoryDisk != 0 || diskEntries != totalEntries) unsupported("不支持分卷 ZIP")
        var count = totalEntries.toLong()
        var directorySize = u32(tail, eocd + 12)
        var directoryOffset = u32(tail, eocd + 16)
        var isZip64 = totalEntries == 0xffff || directorySize == 0xffffffffL || directoryOffset == 0xffffffffL
        var recordsStart = eocdOffset
        // A locator may also be present when ZIP64 fields happen not to overflow.
        if (eocdOffset >= 20) {
            val locatorOffset = eocdOffset - 20
            val locator = if (locatorOffset >= tailStart) tail.copyOfRange((locatorOffset - tailStart).toInt(), eocd)
                else fetch(locatorOffset, 20)
            if (u32(locator, 0) == 0x07064b50L) {
                isZip64 = true
                if (u32(locator, 4) != 0L || u32(locator, 16) != 1L) unsupported("不支持分卷 ZIP64")
                val zip64Offset = u64(locator, 8)
                if (zip64Offset > locatorOffset - 56) corrupt("ZIP64 EOCD 偏移无效")
                val record = fetch(zip64Offset, 56)
                if (u32(record, 0) != 0x06064b50L) corrupt("ZIP64 EOCD 签名无效")
                val recordLength = u64(record, 4)
                if (recordLength < 44 || zip64Offset > locatorOffset - 12 || recordLength != locatorOffset - zip64Offset - 12) {
                    corrupt("ZIP64 EOCD 长度无效")
                }
                if (u32(record, 16) != 0L || u32(record, 20) != 0L || u64(record, 24) != u64(record, 32)) {
                    unsupported("不支持分卷 ZIP64")
                }
                count = u64(record, 32)
                directorySize = u64(record, 40)
                directoryOffset = u64(record, 48)
                recordsStart = zip64Offset
            } else if (isZip64) corrupt("缺少 ZIP64 locator")
        } else if (isZip64) corrupt("缺少 ZIP64 locator")
        if (count <= 0 || count > limits.maxEntries) limit("归档条目数量超出限制或为空")
        if (directorySize <= 0 || directorySize > limits.maxDirectoryBytes) limit("中央目录超出限制")
        if (directoryOffset > recordsStart || directorySize > recordsStart - directoryOffset) corrupt("中央目录越界")
        val directory = fetch(directoryOffset, directorySize.toInt())
        val pages = ArrayList<ArchivePage>()
        var position = 0
        repeat(count.toInt()) {
            token.throwIfCancelled()
            requireBytes(directory, position, 46)
            if (u32(directory, position) != 0x02014b50L) corrupt("中央目录条目签名无效")
            val flags = u16(directory, position + 8)
            val method = u16(directory, position + 10)
            val crc = u32(directory, position + 16)
            var compressed = u32(directory, position + 20)
            var uncompressed = u32(directory, position + 24)
            val nameLength = u16(directory, position + 28)
            val extraLength = u16(directory, position + 30)
            val commentLength = u16(directory, position + 32)
            var diskStart = u16(directory, position + 34).toLong()
            var localOffset = u32(directory, position + 42)
            if (nameLength == 0 || nameLength > 4096) limit("归档文件名长度超出限制")
            val recordSize = 46 + nameLength + extraLength + commentLength
            requireBytes(directory, position, recordSize)
            val rawName = directory.copyOfRange(position + 46, position + 46 + nameLength)
            val name = decodeName(rawName, flags)
            val extraStart = position + 46 + nameLength
            val extraEnd = extraStart + extraLength
            val needUncompressed = uncompressed == 0xffffffffL
            val needCompressed = compressed == 0xffffffffL
            val needOffset = localOffset == 0xffffffffL
            val needDisk = diskStart == 0xffffL
            if (needUncompressed || needCompressed || needOffset || needDisk) {
                isZip64 = true
                var cursor = extraStart
                var found = false
                while (cursor < extraEnd) {
                    if (cursor > extraEnd - 4) corrupt("ZIP extra field 被截断")
                    val type = u16(directory, cursor)
                    val extraSize = u16(directory, cursor + 2)
                    cursor += 4
                    if (extraSize > extraEnd - cursor) corrupt("ZIP extra field 长度无效")
                    if (type == 1) {
                        if (found) corrupt("重复 ZIP64 extra field")
                        found = true
                        var valueAt = cursor
                        fun next64(): Long {
                            if (valueAt > cursor + extraSize - 8) corrupt("ZIP64 extra field 缺少字段")
                            return u64(directory, valueAt).also { valueAt += 8 }
                        }
                        if (needUncompressed) uncompressed = next64()
                        if (needCompressed) compressed = next64()
                        if (needOffset) localOffset = next64()
                        if (needDisk) {
                            if (valueAt > cursor + extraSize - 4) corrupt("ZIP64 extra field 缺少磁盘字段")
                            diskStart = u32(directory, valueAt)
                        }
                    }
                    cursor += extraSize
                }
                if (!found) corrupt("缺少 ZIP64 extra field")
            }
            if (diskStart != 0L) unsupported("不支持分卷 ZIP")
            if (localOffset > directoryOffset - 30) corrupt("本地文件头偏移越界")
            if (!name.endsWith("/") && isImageName(name)) {
                if ((flags and 0x41) != 0) unsupported("不支持加密 ZIP")
                if (method != 0 && method != 8) throw ArchiveException(ArchiveError.UNSUPPORTED_COMPRESSION, "不支持 ZIP 压缩方法 $method")
                if (compressed <= 0 || compressed > limits.maxCompressedPageBytes || uncompressed <= 0 || uncompressed > limits.maxPageBytes) {
                    limit("漫画页面的压缩或解压大小超出限制")
                }
                if (compressed + uncompressed + minOf(compressed, limits.maxRangeBytes.toLong()) > limits.maxWorkingBytes) {
                    limit("漫画页面所需内存超出工作预算")
                }
                if (method == 0 && compressed != uncompressed) corrupt("STORE 条目压缩前后长度不同")
                pages.add(ArchivePage(name, localOffset, compressed, uncompressed, crc, method, flags, rawName))
            }
            position += recordSize
        }
        // This prototype deliberately rejects central-directory encryption/signatures/extensions.
        if (position != directory.size) unsupported("中央目录含不支持的额外记录")
        if (pages.isEmpty()) throw ArchiveException(ArchiveError.UNSUPPORTED_IMAGE, "归档中没有支持的 PNG/JPEG 页面")
        if (pages.map { it.name }.toSet().size != pages.size) corrupt("归档包含重复页面名称")
        pages.sortWith { a, b -> naturalCompare(a.name, b.name) }
        return ArchiveIndex(identity, pages.toList(), directoryOffset, isZip64)
    }

    fun readPage(
        index: ArchiveIndex,
        pageIndex: Int,
        token: CancellationToken = CancellationToken(),
    ): ByteArray {
        token.throwIfCancelled()
        val page = index.pages.getOrNull(pageIndex) ?: throw ArchiveException(ArchiveError.INVALID_LENGTH, "页码超出范围")
        if (page.compressedSize > limits.maxCompressedPageBytes || page.uncompressedSize > limits.maxPageBytes ||
            page.compressedSize <= 0 || page.uncompressedSize <= 0 ||
            page.compressedSize + page.uncompressedSize + minOf(page.compressedSize, limits.maxRangeBytes.toLong()) > limits.maxWorkingBytes) {
            limit("页面超出读取预算")
        }
        val header = readAt(index.identity, page.localHeaderOffset, 30, token)
        if (u32(header, 0) != 0x04034b50L) corrupt("本地文件头签名无效")
        if (u16(header, 6) != page.flags || u16(header, 8) != page.method) corrupt("本地文件头与中央目录不一致")
        val nameLength = u16(header, 26)
        val extraLength = u16(header, 28)
        if (nameLength != page.rawName.size) corrupt("本地文件头名称长度不一致")
        val dataStart = checkedAdd(page.localHeaderOffset, 30L + nameLength + extraLength)
        if (dataStart > index.centralDirectoryOffset || page.compressedSize > index.centralDirectoryOffset - dataStart) {
            corrupt("页面数据跨越中央目录边界")
        }
        val localName = readAt(index.identity, page.localHeaderOffset + 30, nameLength, token)
        if (!localName.contentEquals(page.rawName)) corrupt("本地文件头页面名称不一致")
        if ((page.flags and 8) == 0) {
            if (u32(header, 14) != page.crc32) corrupt("本地文件头 CRC 不一致")
            var localCompressed = u32(header, 18)
            var localUncompressed = u32(header, 22)
            if (localCompressed == 0xffffffffL || localUncompressed == 0xffffffffL) {
                if (extraLength == 0) corrupt("本地文件头缺少 ZIP64 extra field")
                val extra = readAt(index.identity, page.localHeaderOffset + 30 + nameLength, extraLength, token)
                var cursor = 0
                var found = false
                while (cursor < extra.size) {
                    if (cursor > extra.size - 4) corrupt("本地 extra field 被截断")
                    val kind = u16(extra, cursor)
                    val size = u16(extra, cursor + 2)
                    cursor += 4
                    if (size > extra.size - cursor) corrupt("本地 extra field 长度无效")
                    if (kind == 1) {
                        if (found) corrupt("本地 ZIP64 extra field 重复")
                        found = true
                        var valueAt = cursor
                        if (localUncompressed == 0xffffffffL) {
                            if (valueAt > cursor + size - 8) corrupt("本地 ZIP64 解压大小缺失")
                            localUncompressed = u64(extra, valueAt)
                            valueAt += 8
                        }
                        if (localCompressed == 0xffffffffL) {
                            if (valueAt > cursor + size - 8) corrupt("本地 ZIP64 压缩大小缺失")
                            localCompressed = u64(extra, valueAt)
                        }
                    }
                    cursor += size
                }
                if (!found) corrupt("本地文件头缺少 ZIP64 extra field")
            }
            if (localCompressed != page.compressedSize || localUncompressed != page.uncompressedSize) corrupt("本地文件头长度不一致")
        }
        val compressed = readAt(index.identity, dataStart, page.compressedSize.toInt(), token)
        val image = when (page.method) {
            0 -> compressed
            8 -> inflate(compressed, page.uncompressedSize.toInt(), token)
            else -> throw ArchiveException(ArchiveError.UNSUPPORTED_COMPRESSION, "不支持 ZIP 压缩方法 ${page.method}")
        }
        token.throwIfCancelled()
        if (image.size.toLong() != page.uncompressedSize) corrupt("解压后页面长度不一致")
        val crc = CRC32()
        // Check in chunks so cancellation also works during CRC on a large page.
        var position = 0
        while (position < image.size) {
            token.throwIfCancelled()
            val size = minOf(16 * 1024, image.size - position)
            crc.update(image, position, size)
            position += size
        }
        if (crc.value != page.crc32) throw ArchiveException(ArchiveError.CRC_MISMATCH, "页面 CRC32 校验失败")
        checkImageDimensions(image)
        token.throwIfCancelled()
        return image
    }

    private fun readAt(identity: RemoteIdentity, offset: Long, length: Int, token: CancellationToken): ByteArray {
        if (offset < 0 || length <= 0 || offset > identity.length - length.toLong()) corrupt("读取范围越界")
        if (length <= limits.maxRangeBytes) return source.readRange(identity, offset, length, token)
        val result = ByteArray(length)
        var position = 0
        while (position < length) {
            token.throwIfCancelled()
            val size = minOf(limits.maxRangeBytes, length - position)
            val block = source.readRange(identity, offset + position, size, token)
            block.copyInto(result, position)
            position += size
        }
        return result
    }

    private fun inflate(compressed: ByteArray, expectedSize: Int, token: CancellationToken): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val result = ByteArray(expectedSize)
            var position = 0
            while (!inflater.finished() && position < expectedSize) {
                token.throwIfCancelled()
                val size = inflater.inflate(result, position, minOf(16 * 1024, expectedSize - position))
                position += size
                if (size == 0 && !inflater.finished()) corrupt("DEFLATE 数据不足或无法继续解压")
            }
            if (position != expectedSize) corrupt("DEFLATE 输出长度与声明不一致")
            // A stream can fill the output buffer before consuming its final DEFLATE terminator.
            if (!inflater.finished()) {
                val overrun = ByteArray(1)
                if (inflater.inflate(overrun) != 0 || !inflater.finished()) corrupt("DEFLATE 输出超过声明大小")
            }
            if (inflater.remaining != 0) corrupt("DEFLATE 压缩数据包含尾随字节")
            return result
        } catch (e: DataFormatException) {
            throw ArchiveException(ArchiveError.CORRUPT_ARCHIVE, "无效 DEFLATE 数据", e)
        } finally {
            inflater.end()
        }
    }

    private fun checkImageDimensions(image: ByteArray) {
        val dimensions: Pair<Long, Long> = when {
            image.size >= 24 && image.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)) -> {
                if (big32(image, 8) != 13L || String(image, 12, 4, Charsets.US_ASCII) != "IHDR") corrupt("PNG IHDR 无效")
                big32(image, 16) to big32(image, 20)
            }
            image.size >= 4 && image[0] == 0xff.toByte() && image[1] == 0xd8.toByte() -> jpegDimensions(image)
            else -> throw ArchiveException(ArchiveError.UNSUPPORTED_IMAGE, "页面不是支持的 PNG/JPEG 图片")
        }
        val (width, height) = dimensions
        if (width <= 0 || height <= 0 || width > limits.maxImageDimension || height > limits.maxImageDimension ||
            width > limits.maxImagePixels / height) limit("图片尺寸或像素数超出限制")
    }

    private fun jpegDimensions(image: ByteArray): Pair<Long, Long> {
        var position = 2
        while (position < image.size) {
            if ((image[position].toInt() and 0xff) != 0xff) corrupt("JPEG 标记无效")
            while (position < image.size && image[position] == 0xff.toByte()) position++
            if (position >= image.size) break
            val marker = image[position++].toInt() and 0xff
            if (marker == 0xda || marker == 0xd9) break
            if (marker == 0x01 || marker in 0xd0..0xd8) continue
            if (position > image.size - 2) corrupt("JPEG 段被截断")
            val size = big16(image, position)
            if (size < 2 || size > image.size - position) corrupt("JPEG 段长度无效")
            if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                if (size < 8) corrupt("JPEG SOF 段无效")
                return big16(image, position + 5).toLong() to big16(image, position + 3).toLong()
            }
            position += size
        }
        corrupt("JPEG 中没有有效尺寸信息")
    }

    private fun decodeName(raw: ByteArray, flags: Int): String {
        val charset = if ((flags and 0x800) != 0) Charsets.UTF_8 else runCatching { Charset.forName("CP437") }.getOrDefault(Charsets.ISO_8859_1)
        val name = String(raw, charset).replace('\\', '/')
        if (name.startsWith('/') || name.split('/').any { it == ".." } || name.indexOf('\u0000') >= 0) corrupt("不安全的归档路径")
        return name
    }

    private fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg")

    private fun naturalCompare(a: String, b: String): Int {
        var left = 0
        var right = 0
        while (left < a.length && right < b.length) {
            if (a[left].isDigit() && b[right].isDigit()) {
                var leftEnd = left
                var rightEnd = right
                while (leftEnd < a.length && a[leftEnd].isDigit()) leftEnd++
                while (rightEnd < b.length && b[rightEnd].isDigit()) rightEnd++
                val leftNumber = a.substring(left, leftEnd).trimStart('0').ifEmpty { "0" }
                val rightNumber = b.substring(right, rightEnd).trimStart('0').ifEmpty { "0" }
                if (leftNumber.length != rightNumber.length) return leftNumber.length.compareTo(rightNumber.length)
                val comparison = leftNumber.compareTo(rightNumber)
                if (comparison != 0) return comparison
                left = leftEnd
                right = rightEnd
            } else {
                val comparison = a[left].lowercaseChar().compareTo(b[right].lowercaseChar())
                if (comparison != 0) return comparison
                left++
                right++
            }
        }
        val comparison = (a.length - left).compareTo(b.length - right)
        return if (comparison != 0) comparison else a.compareTo(b)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        requireBytes(bytes, offset, 2)
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        requireBytes(bytes, offset, 4)
        var result = 0L
        for (i in 0..3) result = result or ((bytes[offset + i].toLong() and 0xff) shl (i * 8))
        return result
    }

    private fun u64(bytes: ByteArray, offset: Int): Long {
        requireBytes(bytes, offset, 8)
        if ((bytes[offset + 7].toInt() and 0x80) != 0) limit("ZIP64 无符号偏移超出 Long 范围")
        return u32(bytes, offset) or (u32(bytes, offset + 4) shl 32)
    }

    private fun big16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun big32(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[offset + i].toLong() and 0xff) }

    private fun requireBytes(bytes: ByteArray, offset: Int, count: Int) {
        if (offset < 0 || count < 0 || offset > bytes.size - count) corrupt("归档结构被截断")
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) corrupt("归档偏移溢出")
        return left + right
    }

    private fun corrupt(message: String): Nothing = throw ArchiveException(ArchiveError.CORRUPT_ARCHIVE, message)
    private fun unsupported(message: String): Nothing = throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, message)
    private fun limit(message: String): Nothing = throw ArchiveException(ArchiveError.LIMIT_EXCEEDED, message)
}
