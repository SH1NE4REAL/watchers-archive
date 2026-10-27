package org.watchersarchive.core

import java.io.Closeable

/** Common bounded page reader; a reader never owns or stages the complete source. */
interface ComicArchiveReader : Closeable {
    fun open(token: CancellationToken = CancellationToken()): ArchiveIndex
    fun readPage(index: ArchiveIndex, pageIndex: Int, token: CancellationToken = CancellationToken()): ByteArray
    override fun close() { }
}

object ComicArchiveReaders {
    fun create(source: ArchiveByteSource, limits: ReaderLimits = ReaderLimits(), token: CancellationToken = CancellationToken()): ComicArchiveReader {
        val identity = source.discover(token)
        if (identity.length < 8) throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "文件过短，不是漫画归档")
        val magic = source.readRange(identity, 0, 8, token)
        return when {
            magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte() -> ZipArchiveReader(source, limits)
            magic.copyOfRange(0, 7).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)) -> RarArchiveReader(source, limits)
            magic.copyOfRange(0, 7).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01)) ->
                throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "当前尚不支持 RAR5/CBR5，请选择 CBZ 或 RAR4 文件")
            // ZIP64/SFX-like files can legally have padding before their local
            // records. Probe only the bounded EOCD tail, then let the strict ZIP
            // reader validate the complete central directory and page CRC.
            else -> {
                if (identity.length >= 22) {
                    val length = minOf(identity.length, 65_557L).toInt()
                    val tail = source.readRange(identity, identity.length - length, length, token)
                    for (at in tail.size - 22 downTo 0) {
                        if (tail[at] == 0x50.toByte() && tail[at + 1] == 0x4b.toByte() && tail[at + 2] == 0x05.toByte() && tail[at + 3] == 0x06.toByte()) {
                            val comment = (tail[at + 20].toInt() and 0xff) or ((tail[at + 21].toInt() and 0xff) shl 8)
                            if (at + 22L + comment == tail.size.toLong()) return ZipArchiveReader(source, limits)
                        }
                    }
                }
                throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "来源返回网页或不支持的漫画文件")
            }
        }
    }

    /** Clears bounded process-only block buffers; persistent records are untouched. */
    fun clearMemoryCache() = RarArchiveReader.clearMemoryCache()
}
