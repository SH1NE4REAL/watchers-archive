package org.watchersarchive.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceResolverTest {
    private fun asset(url: String) = SourceAsset("asset", "post:1", null, url, null, null, null,
        SourceAssetStatus.METADATA_ONLY, "", "")

    @Test fun canonicalGetComicsHttpLinkFollowsItsNormal302ToAFile() = runBlocking {
        val requested = mutableListOf<String>()
        val resolver = SourceResolver(probe = { url -> requested += url
            if (url.startsWith("https://getcomics.org/")) SourceHeaderReply(302, "https://files.example.test/file.cbz")
            else SourceHeaderReply(206, contentType = "application/zip") })
        val result = resolver.resolve(asset("http://getcomics.org/dls/opaque"))
        assertEquals(listOf("https://getcomics.org/dls/opaque", "https://files.example.test/file.cbz"), requested)
        assertEquals("https://files.example.test/file.cbz", result.candidateUrl)
        assertEquals(SourceAssetStatus.METADATA_ONLY, result.status)
        assertNull(result.verifiedETag)
    }

    @Test fun declaredCbrIsStillResolvedSoMagicCanSelectTheRar4Reader() = runBlocking {
        val result = SourceResolver(probe = { url -> if (url.startsWith("https://getcomics.org/"))
            SourceHeaderReply(302, "https://files.example.test/comic.cbr") else SourceHeaderReply(206) })
            .resolve(asset("https://getcomics.org/dls/token"))
        assertEquals("https://files.example.test/comic.cbr", result.candidateUrl)
        assertEquals(SourceAssetStatus.METADATA_ONLY, result.status)
    }

    @Test fun pixeldrainPublicPermissionIsRequiredBeforeUsingItsDocumentedApi() = runBlocking {
        val urls = mutableListOf<String>()
        val resolver = SourceResolver(metadata = { url ->
            urls += url
            """{"id":"abcd1234","name":"comic.cbz","size":1234,"can_download":true,"availability":"available"}"""
        })
        val result = resolver.resolve(asset("https://pixeldrain.com/u/abcd1234"))
        assertEquals(listOf("https://pixeldrain.com/api/file/abcd1234/info"), urls)
        assertEquals("https://pixeldrain.com/api/file/abcd1234", result.candidateUrl)
        assertEquals(SourceAssetStatus.METADATA_ONLY, result.status)
        assertEquals("comic.cbz", result.fileName)
        assertEquals("1234", result.declaredSize)
        assertNull(result.verifiedUrl)
    }

    @Test fun pixeldrainDeniedOrUnavailableFilesNeverResolveToDownloads() = runBlocking {
        val denied = SourceResolver(metadata = { """{"id":"abcd1234","can_download":false}""" })
            .resolve(asset("https://pixeldrain.com/u/abcd1234"))
        assertEquals(SourceAssetStatus.NEEDS_USER_ACTION, denied.status)
        val unavailable = SourceResolver(metadata = { """{"id":"abcd1234","can_download":true,"availability":"deleted"}""" })
            .resolve(asset("https://pixeldrain.com/u/abcd1234"))
        assertEquals(SourceAssetStatus.UNAUTHORIZED, unavailable.status)
    }

    @Test fun htmlLandingAndAuthenticationAreReportedWithoutTryingToBypassThem() = runBlocking {
        val gated = SourceResolver(probe = { SourceHeaderReply(200, contentType = "text/html") })
            .resolve(asset("https://getcomics.org/dls/token"))
        assertEquals(SourceAssetStatus.NEEDS_USER_ACTION, gated.status)
        val forbidden = SourceResolver(probe = { SourceHeaderReply(403) })
            .resolve(asset("https://getcomics.org/dls/token"))
        assertEquals(SourceAssetStatus.UNAUTHORIZED, forbidden.status)
    }

    @Test fun knownLandingPageIsNotMistakenForAFileOrReadByTheResolver() = runBlocking {
        var requests = 0
        val result = SourceResolver(probe = { requests++; SourceHeaderReply(206) })
            .resolve(asset("https://vikingfile.com/f/abcd"))
        assertEquals(SourceAssetStatus.NEEDS_USER_ACTION, result.status)
        assertEquals(0, requests)
    }

    @Test fun redirectLoopAndUntrustedCleartextCannotResolve() = runBlocking {
        val loop = SourceResolver(probe = { SourceHeaderReply(302, "https://getcomics.org/dls/token") })
            .resolve(asset("https://getcomics.org/dls/token"))
        assertEquals(SourceAssetStatus.NEEDS_USER_ACTION, loop.status)
        val downgrade = SourceResolver(probe = { SourceHeaderReply(302, "http://files.example.test/file.cbz") })
            .resolve(asset("https://getcomics.org/dls/token"))
        assertEquals(SourceAssetStatus.UNAUTHORIZED, downgrade.status)
        val mixedCaseDowngrade = SourceResolver(probe = { SourceHeaderReply(302, "http://127.0.0.1/file.cbz") })
            .resolve(asset("HTTPS://files.example.test/share"))
        assertEquals(SourceAssetStatus.UNAUTHORIZED, mixedCaseDowngrade.status)
        assertNull(safeSourceUrl("http://files.example.test/file.cbz"))
    }

    @Test fun ordinaryPublicRedirectsKeepFinalFileMetadataWithoutClaimingReady() = runBlocking {
        val urls = mutableListOf<String>()
        val result = SourceResolver(probe = { url -> urls += url
            if (url.endsWith("/share")) SourceHeaderReply(302, "https://files.example.test/object")
            else SourceHeaderReply(206, contentType = "application/octet-stream", contentLength = 1,
                contentDisposition = "attachment; filename*=UTF-8''Book%20One.cbz", contentRange = "bytes 0-0/123456")
        }).resolve(asset("https://files.example.test/share"))
        assertEquals(2, urls.size)
        assertEquals("https://files.example.test/object", result.candidateUrl)
        assertEquals("Book One.cbz", result.fileName)
        assertEquals("CBZ", result.declaredFormat)
        assertEquals("123456", result.declaredSize)
        assertEquals(SourceAssetStatus.METADATA_ONLY, result.status)
        assertNull(result.verifiedUrl)
    }

    @Test fun aRealFullFileResponseRemainsAvailableForExplicitOfflineDownload() = runBlocking {
        var pages = 0
        val result = SourceResolver(probe = { SourceHeaderReply(200, contentType = "application/zip", contentLength = 1_100_000_000,
            contentDisposition = "attachment; filename=\"Book.cbz\"") }, page = { pages++; error("No archive body may be opened") })
            .resolve(asset("https://files.example.test/download"))
        assertEquals(SourceAssetStatus.RANGE_UNSUPPORTED, result.status)
        assertEquals("https://files.example.test/download", result.candidateUrl)
        assertEquals("1100000000", result.declaredSize)
        assertEquals("Book.cbz", result.fileName)
        assertEquals(0, pages)
        assertNull(result.verifiedUrl)
    }

    @Test fun anHtmlResponseWithArchiveExtensionIsStillNotAFile() = runBlocking {
        var pages = 0
        val result = SourceResolver(probe = { SourceHeaderReply(200, contentType = "text/html") }, page = { pages++; "" })
            .resolve(asset("https://files.example.test/not-actually-a-file.cbz"))
        assertEquals(SourceAssetStatus.NEEDS_USER_ACTION, result.status)
        assertEquals(0, pages)
    }

    @Test fun mediaFireNormalPublicButtonCanResolveEvenFromAFilePremiumPath() = runBlocking {
        val share = "https://www.mediafire.com/file_premium/abcd/comic.cbz/file"
        val direct = "https://download123.mediafire.com/public-token/abcd/comic.cbz"
        val probes = mutableListOf<String>()
        val pages = mutableListOf<String>()
        val result = SourceResolver(probe = { url -> probes += url
            if (url == share) SourceHeaderReply(200, contentType = "text/html")
            else SourceHeaderReply(206, contentType = "application/zip", contentRange = "bytes 0-0/7777")
        }, page = { url -> pages += url; """<main><a id="downloadButton" download="Comic Book.cbz" href="$direct">Download</a></main>""" })
            .resolve(asset(share))
        assertEquals(listOf(share), pages)
        assertEquals(listOf(share, direct), probes)
        assertEquals(direct, result.candidateUrl)
        assertEquals("Comic Book.cbz", result.fileName)
        assertEquals("7777", result.declaredSize)
        assertEquals(SourceAssetStatus.METADATA_ONLY, result.status)
    }

    @Test fun mediaFireVerificationIsNotBypassedEvenIfPageContainsAButton() = runBlocking {
        var probes = 0
        val result = SourceResolver(probe = { probes++; SourceHeaderReply(200, contentType = "text/html") }, page = {
            """<main><div class="g-recaptcha" data-sitekey="test"></div><a id="downloadButton" href="https://download1.mediafire.com/file.cbz">Download</a></main>"""
        }).resolve(asset("https://www.mediafire.com/file/abcd/comic.cbz/file"))
        assertEquals(SourceAssetStatus.NEEDS_USER_ACTION, result.status)
        assertEquals(1, probes)
    }

    @Test fun mediaFireForbiddenPageIsNotReadAndFakeOffsiteButtonsAreNotFollowed() = runBlocking {
        var pages = 0
        val share = "https://www.mediafire.com/file/abcd/comic.cbz/file"
        val forbidden = SourceResolver(probe = { SourceHeaderReply(403) }, page = { pages++; "" }).resolve(asset(share))
        assertEquals(SourceAssetStatus.UNAUTHORIZED, forbidden.status)
        assertEquals(0, pages)
        for (href in listOf("javascript:downloadFile()", "https://mediafire.com.evil.test/book.cbz", "https://www.mediafire.com/login")) {
            val result = SourceResolver(probe = { SourceHeaderReply(200, contentType = "text/html") }, page = {
                """<a id="downloadButton" href="$href">Download</a>"""
            }).resolve(asset(share))
            assertEquals(SourceAssetStatus.NEEDS_USER_ACTION, result.status)
        }
    }

    @Test fun mediaFirePageSizeIsBoundedBeforeParsing() = runBlocking {
        val result = SourceResolver(probe = { SourceHeaderReply(200, contentType = "text/html") }, page = { "x".repeat(512 * 1024 + 1) })
            .resolve(asset("https://www.mediafire.com/file/abcd/comic.cbz/file"))
        assertEquals(SourceAssetStatus.NEEDS_USER_ACTION, result.status)
    }

    @Test fun pixeldrainPublicSpeedLimitDoesNotBecomeAnAccessDenial() = runBlocking {
        val result = SourceResolver(metadata = {
            """{"id":"abcd1234","can_download":true,"availability":"available","name":"book.cbz","size":1234,"download_speed_limit":1050000}"""
        }).resolve(asset("https://pixeldrain.com/u/abcd1234"))
        assertEquals(SourceAssetStatus.METADATA_ONLY, result.status)
        assertEquals("https://pixeldrain.com/api/file/abcd1234", result.candidateUrl)
    }

    @Test fun pixeldrainQuotaRequiresExplicitEvidenceAndConnectionFailureDoesNotGuessIt() = runBlocking {
        val denied = SourceResolver(metadata = { """{"id":"abcd1234","can_download":false,"message":"download_limit exceeded"}""" })
            .resolve(asset("https://pixeldrain.com/u/abcd1234"))
        assertEquals(SourceAssetStatus.NEEDS_USER_ACTION, denied.status)
        assertTrue(denied.reason.contains("额度"))
        val disconnected = SourceResolver(metadata = { throw java.io.IOException("fixture reset") })
            .resolve(asset("https://pixeldrain.com/u/abcd1234"))
        assertEquals(SourceAssetStatus.TRANSIENT_ERROR, disconnected.status)
        assertFalse(disconnected.reason.contains("额度"))
    }

    @Test fun realHeaderProbeDoesNotWaitForOrReadAnIgnoredMultiGigabyteRangeBody() = runBlocking {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.concurrent.atomic.AtomicReference<java.net.Socket?>()
        val request = java.util.concurrent.atomic.AtomicReference("")
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    accepted.set(socket)
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val headers = StringBuilder(input.readLine().orEmpty())
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers.append('\n').append(line)
                    }
                    request.set(headers.toString())
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=book.cbz\r\n" +
                            "Content-Length: 2000000000\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    // No body is ever supplied. A metadata resolver must finish
                    // after reading headers instead of consuming this response.
                    runCatching { input.read() }
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        try {
            val result = SourceResolver().resolve(asset("http://127.0.0.1:${server.localPort}/file"))
            assertEquals(SourceAssetStatus.RANGE_UNSUPPORTED, result.status)
            assertEquals("2000000000", result.declaredSize)
            assertTrue(request.get().contains("Range: bytes=0-0", true))
            assertNull(result.verifiedLength)
        } finally {
            accepted.get()?.close(); server.close(); worker.join(5_000)
        }
    }
}
