package org.watchersarchive.app

import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException

/** Reject known unreadable content before spending a whole mirror's transfer allowance. */
internal fun checkDownloadArchiveHeader(prefix: ByteArray) {
    val rar5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)
    if (prefix.size >= 8 && prefix.copyOfRange(0, 8).contentEquals(rar5))
        throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "当前阅读器暂不支持 RAR5，正在尝试其他来源")
    val rar4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)
    if (prefix.size >= 14 && prefix.copyOfRange(0, 7).contentEquals(rar4) && prefix[9] == 0x73.toByte()) {
        val flags = (prefix[10].toInt() and 255) or ((prefix[11].toInt() and 255) shl 8)
        if (flags and (0x0001 or 0x0008 or 0x0080) != 0)
            throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "此来源是分卷、固实或加密归档，正在尝试其他来源")
    }
    val text = prefix.toString(Charsets.ISO_8859_1).trimStart().lowercase(java.util.Locale.ROOT)
    if (text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<?xml"))
        throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "来源返回网页，正在尝试其他来源")
    // ZIP64/SFX can have a prefix; unknown magic is still checked by the complete
    // local archive parser rather than being incorrectly rejected here.
}
