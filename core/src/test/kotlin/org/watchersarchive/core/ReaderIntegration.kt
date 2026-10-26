package org.watchersarchive.core

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Executable JVM integration checks against tools/range_server.py; no test dependency needed. */
object ReaderIntegration {
    @JvmStatic
    fun main(args: Array<String>) = run(args.firstOrNull() ?: "http://127.0.0.1:8765")

    fun run(baseUrl: String) {
        val traces = mutableListOf<RangeTrace>()
        var passed = 0
        reset(baseUrl)
        fun test(name: String, body: () -> Unit) {
            body()
            passed++
            println("PASS reader: $name")
        }
        fun source(path: String, limits: ReaderLimits = ReaderLimits()) = HttpRangeSource(
            baseUrl.trimEnd('/') + path, limits,
        ) { trace ->
            synchronized(traces) { traces.add(trace) }
            println("RANGE ${trace.start}-${trace.endInclusive} HTTP=${trace.statusCode} Content-Range=${trace.contentRange} bytes=${trace.receivedBytes} ${trace.outcome}")
        }

        test("standard CBZ: 4 pages, STORE/DEFLATE, actual PNG decode") {
            val reader = ZipArchiveReader(source("/standard.cbz"))
            val index = reader.open()
            check(!index.isZip64 && index.pages.size == 4)
            for (i in index.pages.indices) {
                val bytes = reader.readPage(index, i)
                val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("ImageIO failed")
                check(decoded.width == 480 && decoded.height == 720)
                println("DECODE page=${i + 1} name=${index.pages[i].name} bytes=${bytes.size} pixels=${decoded.width}x${decoded.height}")
                decoded.flush()
            }
        }
        test("ZIP64: real >4GiB offsets, <100KiB transferred, actual page decode") {
            val before = traces.size
            val reader = ZipArchiveReader(source("/zip64.cbz"))
            val index = reader.open()
            check(index.isZip64 && index.identity.length > 0xffffffffL)
            check(index.pages.all { it.localHeaderOffset > 0xffffffffL })
            for (i in index.pages.indices) {
                val bytes = reader.readPage(index, i)
                val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("ZIP64 image decode failed")
                check(decoded.width == 480 && decoded.height == 720)
                decoded.flush()
            }
            val received = traces.drop(before).sumOf { it.receivedBytes }
            check(received < 100_000L) { "ZIP64 read exceeded small Range budget: $received" }
            println("ZIP64 logicalBytes=${index.identity.length} transferredBytes=$received")
        }
        test("ignored Range: 200 rejected before reading body") {
            val before = traces.size
            expect(ArchiveError.RANGE_NOT_SUPPORTED) { ZipArchiveReader(source("/ignore.cbz")).open() }
            val rejected = traces.drop(before)
            check(rejected.size == 1 && rejected.single().receivedBytes == 0L)
        }
        test("incorrect Content-Range rejected without consuming body") {
            val before = traces.size
            expect(ArchiveError.INVALID_CONTENT_RANGE) { ZipArchiveReader(source("/bad-range.cbz")).open() }
            check(traces.drop(before).sumOf { it.receivedBytes } == 0L)
        }
        test("network truncation does not return incomplete page/index") {
            expect(ArchiveError.INVALID_LENGTH, ArchiveError.NETWORK) { ZipArchiveReader(source("/truncated.cbz")).open() }
        }
        test("object ETag changes: If-Match 412 aborts archive open") {
            expect(ArchiveError.OBJECT_CHANGED) { ZipArchiveReader(source("/changing.cbz")).open() }
        }
        test("active cancellation interrupts HTTP and returns no page/index") {
            val token = CancellationToken()
            val started = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val worker = Thread {
                try {
                    val reader = ZipArchiveReader(source("/slow.cbz"))
                    started.countDown()
                    reader.open(token)
                    failure.set(AssertionError("Slow open completed before cancellation"))
                } catch (e: Throwable) {
                    failure.set(e)
                } finally {
                    finished.countDown()
                }
            }
            worker.start()
            check(started.await(2, TimeUnit.SECONDS))
            Thread.sleep(80)
            token.cancel()
            check(finished.await(3, TimeUnit.SECONDS)) { "Cancellation did not disconnect request" }
            val error = failure.get()
            check(error is ArchiveException && error.code == ArchiveError.CANCELLED) { "Unexpected cancellation: $error" }
        }
        test("directory limits reject allocation before oversized read") {
            val limits = ReaderLimits(maxDirectoryBytes = 22)
            expect(ArchiveError.LIMIT_EXCEEDED) { ZipArchiveReader(source("/standard.cbz", limits), limits).open() }
        }
        test("pixel limit enforced before image decode") {
            val limits = ReaderLimits(maxImagePixels = 100)
            val reader = ZipArchiveReader(source("/standard.cbz", limits), limits)
            expect(ArchiveError.LIMIT_EXCEEDED) { reader.readPage(reader.open(), 0) }
        }
        test("pre-cancelled token performs no network request") {
            val before = traces.size
            val token = CancellationToken().also { it.cancel() }
            expect(ArchiveError.CANCELLED) { ZipArchiveReader(source("/standard.cbz")).open(token) }
            check(before == traces.size)
        }
        maliciousFixtures { name, body -> test(name, body) }
        println("ReaderIntegration: $passed tests passed; clientBytesConsumed=${traces.sumOf { it.receivedBytes }}; diskCacheWrites=0 (reader is memory-only)")
    }

    private fun maliciousFixtures(test: (String, () -> Unit) -> Unit) {
        val image = BufferedImage(8, 10, BufferedImage.TYPE_INT_RGB)
        val png = ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()
        image.flush()
        fun zip(name: String = "page.png", method: Int = ZipEntry.STORED): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                val entry = ZipEntry(name)
                entry.method = method
                if (method == ZipEntry.STORED) {
                    entry.size = png.size.toLong()
                    entry.compressedSize = png.size.toLong()
                    entry.crc = CRC32().also { it.update(png) }.value
                }
                zip.putNextEntry(entry)
                zip.write(png)
                zip.closeEntry()
            }
            return out.toByteArray()
        }
        val standard = zip()
        val badCrc = standard.copyOf().also { data ->
            val nameSize = little16(data, 26)
            val extraSize = little16(data, 28)
            val at = 30 + nameSize + extraSize + 40
            data[at] = (data[at].toInt() xor 1).toByte()
        }
        val badMethod = standard.copyOf().also { data ->
            data[8] = 99
            val central = findSignature(data, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
            data[central + 10] = 99
        }
        val encrypted = standard.copyOf().also { data ->
            data[6] = 1
            val central = findSignature(data, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
            data[central + 8] = 1
        }
        val fixtures = mapOf(
            "/crc" to badCrc,
            "/method" to badMethod,
            "/encrypted" to encrypted,
            "/traversal" to zip("../page.png"),
            "/descriptor" to zip(method = ZipEntry.DEFLATED),
            "/notzip" to ByteArray(128) { 0x52 },
            "/weak" to standard,
            "/missing" to standard,
            "/overrun" to standard,
            "/redirect" to standard,
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                if (path == "/redirect") {
                    exchange.responseHeaders.set("Location", "/descriptor")
                    exchange.sendResponseHeaders(302, -1)
                } else {
                    val data = fixtures[path] ?: error("Unknown fixture")
                    val range = Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(exchange.requestHeaders.getFirst("Range"))!!
                    val start = range.groupValues[1].toInt()
                    val end = range.groupValues[2].toInt()
                    exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${data.size}")
                    if (path != "/missing") exchange.responseHeaders.set("ETag", if (path == "/weak") "W/\"fixture\"" else "\"fixture\"")
                    // Zero length selects chunked mode: allows an extra-byte protocol violation.
                    exchange.sendResponseHeaders(206, if (path == "/overrun") 0L else (end - start + 1).toLong())
                    exchange.responseBody.use { output ->
                        output.write(data, start, end - start + 1)
                        if (path == "/overrun") output.write(42)
                    }
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        fun reader(path: String) = ZipArchiveReader(HttpRangeSource("http://127.0.0.1:${server.address.port}$path"))
        try {
            test("CRC mismatch rejected") {
                val reader = reader("/crc")
                expect(ArchiveError.CRC_MISMATCH) { reader.readPage(reader.open(), 0) }
            }
            test("unsupported ZIP compression rejected") { expect(ArchiveError.UNSUPPORTED_COMPRESSION) { reader("/method").open() } }
            test("encrypted ZIP rejected") { expect(ArchiveError.UNSUPPORTED_ARCHIVE) { reader("/encrypted").open() } }
            test("unsafe archive path rejected") { expect(ArchiveError.CORRUPT_ARCHIVE) { reader("/traversal").open() } }
            test("non-ZIP/CBR rejected explicitly") { expect(ArchiveError.UNSUPPORTED_ARCHIVE) { reader("/notzip").open() } }
            test("weak or missing ETag rejected") {
                expect(ArchiveError.IDENTITY_REQUIRED) { reader("/weak").open() }
                expect(ArchiveError.IDENTITY_REQUIRED) { reader("/missing").open() }
            }
            test("chunked range overrun rejected") { expect(ArchiveError.INVALID_LENGTH) { reader("/overrun").open() } }
            test("redirect and DEFLATE data-descriptor ZIP decode") {
                val reader = reader("/redirect")
                val bytes = reader.readPage(reader.open(), 0)
                check(bytes.contentEquals(png))
            }
        } finally {
            server.stop(0)
        }
    }

    private fun expect(vararg codes: ArchiveError, body: () -> Unit) {
        try {
            body()
            error("Expected one of ${codes.toList()}")
        } catch (e: ArchiveException) {
            check(e.code in codes) { "Expected ${codes.toList()}, got ${e.code}: ${e.message}" }
        }
    }

    private fun reset(baseUrl: String) {
        val connection = URL(baseUrl.trimEnd('/') + "/reset").openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        try {
            check(connection.responseCode == 200)
            connection.inputStream.close()
        } finally {
            connection.disconnect()
        }
    }

    private fun little16(data: ByteArray, at: Int): Int = (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)

    private fun findSignature(data: ByteArray, signature: ByteArray): Int {
        for (position in 0..data.size - signature.size) {
            if (signature.indices.all { data[position + it] == signature[it] }) return position
        }
        error("Signature missing")
    }
}
