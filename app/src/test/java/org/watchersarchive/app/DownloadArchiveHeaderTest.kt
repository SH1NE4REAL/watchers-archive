package org.watchersarchive.app

import org.junit.Assert.*
import org.junit.Test
import org.watchersarchive.core.ArchiveException

class DownloadArchiveHeaderTest {
    @Test fun rejectsRar5AndUnreadableRar4FlagsBeforeWholeDownload() {
        assertTrue(runCatching { checkDownloadArchiveHeader(byteArrayOf(0x52,0x61,0x72,0x21,0x1a,0x07,0x01,0x00)) }.exceptionOrNull() is ArchiveException)
        for (flags in listOf(1,8,128)) {
            val header = ByteArray(32)
            byteArrayOf(0x52,0x61,0x72,0x21,0x1a,0x07,0x00).copyInto(header)
            header[9] = 0x73
            header[10] = flags.toByte()
            assertTrue(runCatching { checkDownloadArchiveHeader(header) }.exceptionOrNull() is ArchiveException)
        }
        checkDownloadArchiveHeader(byteArrayOf(0x50,0x4b,0x03,0x04))
        checkDownloadArchiveHeader(ByteArray(32))
    }
    @Test fun rejectsHtmlButKeepsUnknownZip64PrefixesForCompleteValidation() {
        assertTrue(runCatching { checkDownloadArchiveHeader("  <!DOCTYPE html>".toByteArray()) }.exceptionOrNull() is ArchiveException)
        checkDownloadArchiveHeader(byteArrayOf(1,2,3,4))
    }
}
