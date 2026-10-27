package org.watchersarchive.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32

/** Fixture was generated from four self-created PNGs by installed WinRAR 6.11, -ma4 -m3 -s-. */
object RarArchiveIntegration {
    private val fixture = Base64.getDecoder().decode("UmFyIRoHAM+QcwAADQAAAAAAAAA1k3QgkDAArAAAAAsEAAACeSSy1rpTW10dMwsAIAAAAHBhZ2UtMDEucG5nALAkIzUJlAFMvZgJe5Yb8GwMGCMXSUaKIMWDl6i9g4TDJEFEYbwBQQZsEHVZoxQaqD4HhBk6GNTs8GNHGDeA4MbQ2n3w/S5w5fnjv0PvAfvDPodMyeNA6qcCUQFkCoQwSxSplAiu729UzRaEFLPw7vtfDQI0l1630Y4bG41Iwu7fWYGXbd4lXtqqh+VbwK1cs86/5nzU7U8PXZP6kccmOk9j09o/ouE+gFiTD7wa+USAU890IJAwAKsAAAAMBAAAAonmIiu6U1tdHTMLACAAAABwYWdlLTAyLnBuZwCwVOA1CVQBTL4JfKiGcOBhgyaWBBBgiIMGq7yL4RwuGbBRY74BQYogwwLVmjFBoiNGSeEEQ0kdHVwY08Gvo42htP3x+l3p2/fneR58Bz4zkN+FK6YWeWCIMFeCmQuxUpQqWRW9TOWNmLiDax6+bn/FOAgaMZ32gr42n3yvdnyB5jtsQ0ejhoq0/n7rR5Go8c6ic/Bw6Cr2KknqQQQ4rTaWnI1WqrME+gFcSD3t5tz6zSV0IJAwAKwAAAALBAAAAvpzp3S6U1tdHTMLACAAAABwYWdlLTAzLnBuZwCw+eQ2CZQBTL2YCXrlg7Lp4FE0FBcDToLFhgV6i9g4XDRB/EtBqgxQRggskGaMUGq+CJos2CaDNHYNtPBvBMbQ2n3w/S5w5fnjv0PvAfvDPob8CV00ygYCENHeEmQuw0hSWURW9LGSJkrDFZxauji2EsFE5VWd7poB81JWY/Hs16PqPEP+YvSt8s6ic2/w6jDWq7kPXztP5nR+o+/Bi0VYPL/M966JPoB3EheLWXa8gHqxdCCQMACpAAAAbwUAAAIt0ziCulNbXR0zCwAgAAAAcGFnZS0wNC5wbmcAsOC3NwlUGRC9mAl+zdEV0NGOyYrTKIwaP5M3VxhpijdYot0Q1BERmtEYo5BHwBGaM2CCKIyfAWOzQRZM0xhuzzw/Zd6Hb98c5TleeH8yGbpLrR1geFy0yVkn09CuRFEq7e8WH2FMKShXcP57Ci8JQK7ahz89/fSNhqSpmuznGnVR9MAM6I2ofwTFqbi19YZs/0MsuRPqweohCP+WE3Hdy93wtp6QKy2TZRs4/yDEPXsAQAcA")
    @JvmStatic fun main(args: Array<String>) = run()

    fun run() {
        ComicArchiveReaders.clearMemoryCache()
        val normal = MemorySource(fixture.size.toLong(), listOf(0L to fixture))
        ComicArchiveReaders.create(normal).use { reader ->
            val index = reader.open()
            check(index.pages.size == 4)
            index.pages.indices.forEach { page ->
                val image = reader.readPage(index, page)
                check(image.size > 100 && image[0] == 0x89.toByte())
                val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(image))
                check(decoded != null && decoded.width == 240 && decoded.height == 360)
                decoded.flush()
            }
        }
        println("PASS rar4: four compressed RAR4 pages extract and decode, bytes=${normal.bytesRead}")

        // Insert a virtual 3 GiB non-image stored entry. Indexing must seek past it.
        val mainEnd = 7 + u16(fixture, 12)
        val gap = 3L * 1024 * 1024 * 1024
        val name = "padding.bin".toByteArray()
        val dummy = ByteBuffer.allocate(40 + name.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); put(0x74); putShort(0x8100.toShort()); putShort((40 + name.size).toShort())
            putInt(gap.toInt()); putInt(gap.toInt()); put(2); putInt(0); putInt(0); put(20); put(0x30)
            putShort(name.size.toShort()); putInt(0x20); putInt(0); putInt(0); put(name)
        }.array()
        updateHeaderCrc(dummy)
        val tailAt = mainEnd + dummy.size + gap
        val sparse = MemorySource(tailAt + fixture.size - mainEnd,
            listOf(0L to fixture.copyOfRange(0, mainEnd), mainEnd.toLong() to dummy, tailAt to fixture.copyOfRange(mainEnd, fixture.size)))
        ComicArchiveReaders.create(sparse).use { reader ->
            val index = reader.open()
            check(index.identity.length > 3_000_000_000L && index.pages.size == 4)
            check(index.pages.all { it.localHeaderOffset > gap })
            check(reader.readPage(index, 3).isNotEmpty())
        }
        check(sparse.bytesRead < 150_000L) { "Unexpected bulk transfer ${sparse.bytesRead}" }
        println("PASS rar4: virtual 3GiB gap skipped; bytes=${sparse.bytesRead}; allocated=${sparse.allocatedBytes}")

        val solid = fixture.copyOf().apply { this[10] = (this[10].toInt() or 8).toByte(); updateHeaderCrc(this, 7, u16(this, 12)) }
        expect(ArchiveError.UNSUPPORTED_ARCHIVE) { ComicArchiveReaders.create(MemorySource(solid.size.toLong(), listOf(0L to solid))).use { it.open() } }
        println("PASS rar4: solid archives rejected before decoding")

        val encrypted = fixture.copyOf().apply { this[10] = (this[10].toInt() or 0x80).toByte(); updateHeaderCrc(this, 7, u16(this, 12)) }
        expect(ArchiveError.UNSUPPORTED_ARCHIVE) { ComicArchiveReaders.create(MemorySource(encrypted.size.toLong(), listOf(0L to encrypted))).use { it.open() } }
        println("PASS rar4: encrypted archives rejected before decoding")

        val v5 = byteArrayOf(0x52,0x61,0x72,0x21,0x1a,0x07,0x01,0)
        expect(ArchiveError.UNSUPPORTED_ARCHIVE) { ComicArchiveReaders.create(MemorySource(8, listOf(0L to v5))) }
        println("PASS rar4: RAR5 rejected explicitly")

        val cancelled = CancellationToken().apply { cancel() }
        val noReads = MemorySource(fixture.size.toLong(), listOf(0L to fixture))
        expect(ArchiveError.CANCELLED) { ComicArchiveReaders.create(noReads, token = cancelled) }
        check(noReads.bytesRead == 0L)
        println("PASS rar4: pre-cancelled request performs no reads")

        ComicArchiveReaders.clearMemoryCache()
        val interruptible = MemorySource(fixture.size.toLong(), listOf(0L to fixture))
        ComicArchiveReaders.create(interruptible).use { reader ->
            val index = reader.open()
            val token = CancellationToken()
            interruptible.afterRead = { token.cancel() }
            expect(ArchiveError.CANCELLED) { reader.readPage(index, 0, token) }
            interruptible.afterRead = null
            check(reader.readPage(index, 0).isNotEmpty())
        }
        println("PASS rar4: cancellation returns no page and a fresh token can retry")

        ComicArchiveReaders.clearMemoryCache()
        val first = MemorySource(fixture.size.toLong(), listOf(0L to fixture))
        ComicArchiveReaders.create(first).use { val index = it.open(); it.readPage(index, 0) }
        val reused = MemorySource(fixture.size.toLong(), listOf(0L to fixture))
        ComicArchiveReaders.create(reused).use { val index = it.open(); it.readPage(index, 0) }
        check(first.bytesRead > fixture.size && reused.bytesRead < 64)
        // Same URL and length but different version must never reuse old blocks.
        val changed = MemorySource(solid.size.toLong(), listOf(0L to solid))
        expect(ArchiveError.UNSUPPORTED_ARCHIVE) { ComicArchiveReaders.create(changed).use { it.open() } }
        check(changed.bytesRead > 8)
        ComicArchiveReaders.clearMemoryCache()
        println("PASS rar4: validation-to-reader cache reuses identity-bound blocks; changed versions are rejected")
        expect(ArchiveError.UNSUPPORTED_ARCHIVE) { BoundedPpmAllocator().startSubAllocator(256) }
        expect(ArchiveError.UNSUPPORTED_ARCHIVE) { BoundedPpmAllocator().startSubAllocator(1) }
        println("PASS rar4: PPM allocation is rejected before allocating even 1 MiB")

        val performance = largeStoredArchive()
        fun profile(maximumRange: Int): Triple<Int, Long, Long> {
            ComicArchiveReaders.clearMemoryCache()
            val source = MemorySource(performance.first, performance.second)
            val limits = ReaderLimits(maxRangeBytes = maximumRange)
            ComicArchiveReaders.create(source, limits).use { reader ->
                val index = reader.open()
                check(index.pages.size == 1)
                source.readCount = 0
                source.bytesRead = 0
                source.perReadDelayMillis = 8
                val start = System.nanoTime()
                val page = reader.readPage(index, 0)
                val elapsedMillis = (System.nanoTime() - start) / 1_000_000
                check(page.contentEquals(performance.third))
                val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(page))
                check(decoded != null && decoded.width == 1024 && decoded.height == 768)
                decoded.flush()
                check(source.maximumReadLength <= maximumRange)
                return Triple(source.readCount, source.bytesRead, elapsedMillis)
            }
        }
        // A 64 KiB source cap reproduces the former page block strategy. An
        // otherwise identical source permits the new bounded 1 MiB page reads.
        val baseline = profile(64 * 1024)
        val optimized = profile(1024 * 1024)
        check(baseline.first >= 36 && optimized.first <= 4)
        check(optimized.first * 8 < baseline.first)
        check(optimized.second <= performance.third.size + 2L * 1024 * 1024)
        println("PASS rar4: large-page round trips old64KiB=${baseline.first}/${baseline.second}B new1MiB=${optimized.first}/${optimized.second}B; simulated8msPerRead=${baseline.third}ms/${optimized.third}ms")

        ComicArchiveReaders.clearMemoryCache()
        val lowRange = MemorySource(fixture.size.toLong(), listOf(0L to fixture))
        ComicArchiveReaders.create(lowRange, ReaderLimits(maxRangeBytes = 128)).use { reader ->
            val index = reader.open()
            check(reader.readPage(index, 0).isNotEmpty())
        }
        check(lowRange.maximumReadLength <= 128)
        println("PASS rar4: small source range caps are respected for both headers and pages")

        ComicArchiveReaders.clearMemoryCache()
        val truncatedBacking = MemorySource(performance.first, performance.second)
        val truncatedSource = object : ArchiveByteSource by truncatedBacking {
            override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray {
                val response = truncatedBacking.readRange(identity, start, length, token)
                return if (length > 64 * 1024) response.copyOf(response.size - 1) else response
            }
        }
        ComicArchiveReaders.create(truncatedSource).use { reader ->
            val index = reader.open()
            expect(ArchiveError.CORRUPT_ARCHIVE) { reader.readPage(index, 0) }
        }
        println("PASS rar4: truncated large range never returns a page")

        ComicArchiveReaders.clearMemoryCache()
        val largeCancelled = MemorySource(performance.first, performance.second)
        ComicArchiveReaders.create(largeCancelled).use { reader ->
            val index = reader.open()
            val token = CancellationToken()
            largeCancelled.afterRead = { token.cancel() }
            expect(ArchiveError.CANCELLED) { reader.readPage(index, 0, token) }
            largeCancelled.afterRead = null
            check(reader.readPage(index, 0).contentEquals(performance.third))
        }
        println("PASS rar4: cancelled large page is not delivered and a fresh request can retry")
        println("RarArchiveIntegration: 13 tests passed")
    }

    /** 2.36 MB self-generated PNG plus a virtual 64 MiB non-image gap. */
    private fun largeStoredArchive(): Triple<Long, List<Pair<Long, ByteArray>>, ByteArray> {
        val width = 1024
        val height = 768
        val raw = ByteArray((width * 3 + 1) * height)
        java.util.Random(7).nextBytes(raw)
        repeat(height) { raw[it * (width * 3 + 1)] = 0 }
        val compressed = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.DeflaterOutputStream(output).use { it.write(raw) }
        }.toByteArray()
        fun chunk(type: String, data: ByteArray): ByteArray {
            val name = type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(name); update(data) }.value
            return ByteBuffer.allocate(data.size + 12).order(ByteOrder.BIG_ENDIAN)
                .putInt(data.size).put(name).put(data).putInt(crc.toInt()).array()
        }
        val ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10) +
            chunk("IHDR", ihdr) + chunk("IDAT", compressed) + chunk("IEND", ByteArray(0))
        fun storedHeader(name: String, size: Int, crc: Long): ByteArray {
            val encoded = name.toByteArray(Charsets.UTF_8)
            return ByteBuffer.allocate(32 + encoded.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0); put(0x74); putShort(0x8000.toShort()); putShort((32 + encoded.size).toShort())
                putInt(size); putInt(size); put(2); putInt(crc.toInt()); putInt(0); put(20); put(0x30)
                putShort(encoded.size.toShort()); putInt(0x20); put(encoded)
            }.array().also { updateHeaderCrc(it) }
        }
        val gap = 64 * 1024 * 1024
        val mainEnd = 7 + u16(fixture, 12)
        val prefix = fixture.copyOfRange(0, mainEnd) +
            storedHeader("page-001.png", image.size, CRC32().apply { update(image) }.value) + image +
            storedHeader("padding.bin", gap, 0)
        val end = fixture.copyOfRange(fixture.size - 7, fixture.size)
        val endOffset = prefix.size.toLong() + gap
        return Triple(endOffset + end.size, listOf(0L to prefix, endOffset to end), image)
    }

    private fun u16(b:ByteArray, at:Int) = (b[at].toInt() and 255) or ((b[at+1].toInt() and 255) shl 8)
    private fun updateHeaderCrc(bytes:ByteArray,start:Int=0,size:Int=bytes.size) {
        val crc=CRC32().apply { update(bytes,start+2,size-2) }.value.toInt()
        bytes[start]=(crc and 255).toByte();bytes[start+1]=((crc ushr 8) and 255).toByte()
    }
    private fun expect(code:ArchiveError, body:()->Unit) {
        try { body(); error("Expected $code") } catch(e:ArchiveException) { check(e.code==code) { "Expected $code, got ${e.code}: ${e.message}" } }
    }
    private class MemorySource(val size:Long, val segments:List<Pair<Long,ByteArray>>) : ArchiveByteSource {
        var bytesRead=0L
        var readCount=0
        var maximumReadLength=0
        var perReadDelayMillis=0L
        var afterRead: (() -> Unit)? = null
        val allocatedBytes=segments.sumOf { it.second.size }
        private val fingerprint = java.security.MessageDigest.getInstance("SHA-256").apply {
            update(size.toString().toByteArray())
            segments.forEach { (at, bytes) -> update(at.toString().toByteArray()); update(bytes) }
        }.digest().joinToString("") { "%02x".format(it) }
        private val id=RemoteIdentity("fixture://rar4",size,"\"$fingerprint\"",null)
        override fun discover(token:CancellationToken):RemoteIdentity { token.throwIfCancelled(); return id }
        override fun readRange(identity:RemoteIdentity,start:Long,length:Int,token:CancellationToken):ByteArray {
            token.throwIfCancelled();check(identity==id);require(start>=0 && length>0 && length<=4*1024*1024 && start<=size-length)
            val result=ByteArray(length)
            segments.forEach { (at,data) ->
                val from=maxOf(start,at);val end=minOf(start+length,at+data.size)
                if(from<end)data.copyInto(result,(from-start).toInt(),(from-at).toInt(),(end-at).toInt())
            }
            readCount++
            maximumReadLength=maxOf(maximumReadLength,length)
            if (perReadDelayMillis > 0) Thread.sleep(perReadDelayMillis)
            bytesRead+=length;afterRead?.invoke();return result
        }
    }
}
