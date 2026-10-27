package org.watchersarchive.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tests the local/random-access contract without requiring Android or reading user files. */
object LocalArchiveSourceIntegration {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGP4z8AARAwQCgAf7gP9i18U1AAAAABJRU5ErkJggg==")

    fun run() {
        val directory = Files.createTempDirectory("watchers-local-source-")
        try {
            val file = directory.resolve("synthetic.cbz")
            val archive = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    repeat(4) { page ->
                        zip.putNextEntry(ZipEntry("page-${page + 1}.png")); zip.write(png); zip.closeEntry()
                    }
                }
            }.toByteArray()
            Files.write(file, archive)
            ChannelFixture(file).use { source ->
                val reader = ZipArchiveReader(source)
                val index = reader.open()
                check(index.pages.size == 4)
                index.pages.indices.forEach { check(reader.readPage(index, it).contentEquals(png)) }
                check(source.largestRead <= 65_557)
            }
            println("PASS local-source: real file positional reads decode four CBZ pages without staging copy")

            ChannelFixture(file).use { source ->
                val identity = source.discover()
                source.changed = true
                expect(ArchiveError.OBJECT_CHANGED) { source.readRange(identity, 0, 1) }
            }
            println("PASS local-source: changed representation is rejected")

            ChannelFixture(file).use { source ->
                val cancelled = CancellationToken().apply { cancel() }
                expect(ArchiveError.CANCELLED) { ZipArchiveReader(source).open(cancelled) }
                check(source.bytesRead == 0L)
            }
            println("PASS local-source: pre-cancelled read performs no I/O")

            val virtual = Zip64Fixture()
            val reader = ZipArchiveReader(virtual)
            val index = reader.open()
            check(index.isZip64 && index.pages.single().localHeaderOffset > 0xffffffffL)
            check(reader.readPage(index, 0).contentEquals(png))
            check(virtual.bytesRead < 100_000)
            println("PASS local-source: ZIP64 >4GiB offset, ${virtual.bytesRead} bytes read, ${virtual.allocatedBytes} fixture bytes allocated")
            val padded = Zip64Fixture()
            ComicArchiveReaders.create(padded).use { reader ->
                val detected = reader.open()
                check(detected.isZip64 && reader.readPage(detected, 0).contentEquals(png))
            }
            check(padded.bytesRead < 200_000)
            println("PASS local-source: format factory recognizes padded ZIP64 using bounded EOCD tail")
            println("LocalArchiveSourceIntegration: 5 tests passed")
        } finally {
            Files.list(directory).use { stream -> stream.forEach { Files.delete(it) } }
            Files.delete(directory)
        }
    }

    private class ChannelFixture(path: java.nio.file.Path) : ArchiveByteSource, Closeable {
        private val channel = FileChannel.open(path, StandardOpenOption.READ)
        private val identity = RemoteIdentity(path.toUri().toString(), channel.size(), "\"local-fixture-v1\"", null)
        var changed = false
        var bytesRead = 0L
        var largestRead = 0
        override fun discover(token: CancellationToken): RemoteIdentity { token.throwIfCancelled(); return identity }
        override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray {
            token.throwIfCancelled()
            if (changed || identity != this.identity) throw ArchiveException(ArchiveError.OBJECT_CHANGED, "fixture changed")
            require(start >= 0 && length > 0 && length <= 4 * 1024 * 1024 && start <= channel.size() - length)
            val output = ByteArray(length)
            val buffer = ByteBuffer.wrap(output)
            var position = start
            while (buffer.hasRemaining()) {
                token.throwIfCancelled()
                val read = channel.read(buffer, position)
                check(read > 0)
                position += read
                bytesRead += read
            }
            largestRead = maxOf(largestRead, length)
            return output
        }
        override fun close() { channel.close() }
    }

    private class Zip64Fixture : ArchiveByteSource {
        private val name = "page-1.png".toByteArray(Charsets.US_ASCII)
        private val crc = CRC32().apply { update(png) }.value
        private val localOffset = 0x100000000L + 512
        private val local = little(30 + name.size + png.size) {
            putInt(0x04034b50); putShort(45); putShort(0); putShort(0); putShort(0); putShort(0)
            putInt(crc.toInt()); putInt(png.size); putInt(png.size); putShort(name.size.toShort()); putShort(0); put(name); put(png)
        }
        private val centralOffset = localOffset + local.size
        private val central = little(46 + name.size + 12) {
            putInt(0x02014b50); putShort(45); putShort(45); putShort(0); putShort(0); putShort(0); putShort(0)
            putInt(crc.toInt()); putInt(png.size); putInt(png.size); putShort(name.size.toShort()); putShort(12)
            putShort(0); putShort(0); putShort(0); putInt(0); putInt(-1); put(name)
            putShort(1); putShort(8); putLong(localOffset)
        }
        private val end64Offset = centralOffset + central.size
        private val trailer = little(56 + 20 + 22) {
            putInt(0x06064b50); putLong(44); putShort(45); putShort(45); putInt(0); putInt(0)
            putLong(1); putLong(1); putLong(central.size.toLong()); putLong(centralOffset)
            putInt(0x07064b50); putInt(0); putLong(end64Offset); putInt(1)
            putInt(0x06054b50); putShort(0); putShort(0); putShort(-1); putShort(-1); putInt(-1); putInt(-1); putShort(0)
        }
        private val segments = listOf(localOffset to local, centralOffset to central, end64Offset to trailer)
        private val identity = RemoteIdentity("local-fixture://zip64", end64Offset + trailer.size, "\"zip64-v1\"", null)
        var bytesRead = 0L
        val allocatedBytes = segments.sumOf { it.second.size }
        override fun discover(token: CancellationToken): RemoteIdentity { token.throwIfCancelled(); return identity }
        override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray {
            token.throwIfCancelled(); check(identity == this.identity)
            require(start >= 0 && length > 0 && length <= 4 * 1024 * 1024 && start <= identity.length - length)
            val result = ByteArray(length)
            segments.forEach { (at, data) ->
                val begin = maxOf(start, at); val end = minOf(start + length, at + data.size)
                if (begin < end) data.copyInto(result, (begin - start).toInt(), (begin - at).toInt(), (end - at).toInt())
            }
            bytesRead += length
            return result
        }
    }

    private fun little(size: Int, build: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(build).array()

    private fun expect(code: ArchiveError, operation: () -> Unit) {
        try { operation(); error("Expected $code") }
        catch (error: ArchiveException) { check(error.code == code) }
    }
}
