package org.watchersarchive.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceResolverTest {
    private fun asset(url: String) = SourceAsset("asset", "post:1", null, url, null, null, null,
        SourceAssetStatus.METADATA_ONLY, "", "")

    @Test fun canonicalGetComicsHttpLinkFollowsItsNormal302ToAFile() = runBlocking {
        val requested = mutableListOf<String>()
        val resolver = SourceResolver(probe = { url -> requested += url; SourceHeaderReply(302, "https://files.example.test/file.cbz") })
        val result = resolver.resolve(asset("http://getcomics.org/dls/opaque"))
        assertEquals(listOf("https://getcomics.org/dls/opaque"), requested)
        assertEquals("https://files.example.test/file.cbz", result.candidateUrl)
        assertEquals(SourceAssetStatus.METADATA_ONLY, result.status)
        assertNull(result.verifiedETag)
    }

    @Test fun declaredCbrIsStillResolvedSoMagicCanSelectTheRar4Reader() = runBlocking {
        val result = SourceResolver(probe = { SourceHeaderReply(302, "https://files.example.test/comic.cbr") })
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
        assertNull(safeSourceUrl("http://files.example.test/file.cbz"))
    }
}
