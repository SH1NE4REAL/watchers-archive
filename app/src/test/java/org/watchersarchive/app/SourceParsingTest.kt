package org.watchersarchive.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceParsingTest {
    private val clock = "2026-10-27T01:00:00Z"
    private val service = GetComicsCatalogService(now = { clock }, decodeImage = { true })

    @Test fun wordPressSeparatesSizeSynopsisAndDecodesEntities() {
        val json = """[{
          "id": 409443,
          "link":"https://getcomics.org/marvel/blade/",
          "date_gmt":"2026-10-24T14:37:59", "modified_gmt":"2026-10-24T15:00:00",
          "title":{"rendered":"Blade &#8211; Crescent City Blues &#35;1 (1998)"},
          "excerpt":{"rendered":"<p>Year : 1998 | Size : 77 MB Deacon Frost has taken over New Orleans&#8217; Mafia.</p>"},
          "content":{"rendered":"<p>Format: CBZ</p><img data-src='/share/uploads/cover.jpg'>"},
          "categories":[3]
        }]"""
        val record = service.parseWordPressPosts(json, "https://getcomics.org/wp-json/wp/v2/posts").single()
        assertEquals("post:409443", record.externalId)
        assertEquals("Blade – Crescent City Blues #1 (1998)", record.title)
        assertEquals("77 MB", record.sizeLabel)
        assertEquals("1998", record.yearLabel)
        assertEquals("Deacon Frost has taken over New Orleans’ Mafia.", record.synopsis)
        assertEquals("https://getcomics.org/share/uploads/cover.jpg", record.coverUrl)
        assertEquals("2026-10-24T14:37:59Z", record.sourcePublishedAt)
        assertEquals(clock, record.firstDiscoveredAt)
    }

    @Test fun rssCdataHasSamePostIdentityAsWordPressAndRealImageUrl() {
        val rss = """<?xml version="1.0"?><rss xmlns:wp="http://wordpress.org/export/1.2/"><channel><item>
          <title><![CDATA[Blade &#8211; Issue #1]]></title>
          <link>https://getcomics.org/marvel/blade/</link>
          <guid isPermaLink="false">https://getcomics.org/?p=409443</guid>
          <wp:post-id>409443</wp:post-id><pubDate>Sat, 24 Oct 2026 14:37:59 +0000</pubDate>
          <category><![CDATA[Marvel Comics]]></category>
          <description><![CDATA[<img src="https://getcomics.org/cover.jpg"><p>Year : 1998 | Size : 2.5 GB Description here.</p>]]></description>
        </item></channel></rss>"""
        val record = service.parseRss(rss, "https://getcomics.org/feed/").single()
        assertEquals("post:409443", record.externalId)
        assertEquals("Blade – Issue #1", record.title)
        assertEquals("https://getcomics.org/cover.jpg", record.coverUrl)
        assertEquals("2.5 GB", record.sizeLabel)
        assertEquals("Description here.", record.synopsis)
        assertEquals("2026-10-24T14:37:59Z", record.sourcePublishedAt)
    }

    @Test fun nullFieldsDoNotBecomeLiteralNullAndUnknownSizeDoesNotAbsorbSynopsis() {
        val json = """[{"id":5,"link":"https://getcomics.org/marvel/test/","date_gmt":null,"title":{"rendered":"Pack"},"excerpt":{"rendered":"Year : 2026 | Size : - MB This is the synopsis."}}]"""
        val record = service.parseWordPressPosts(json, "https://getcomics.org").single()
        assertNull(record.sourcePublishedAt)
        assertNull(record.coverUrl)
        assertNull(record.sizeLabel)
        assertEquals("This is the synopsis.", record.synopsis)
    }

    @Test fun legacyGetComicsHttpCoversAndDownloadRoutesUseCanonicalHttps() {
        val json = """[{"id":12,"link":"https://getcomics.org/marvel/test/","title":{"rendered":"Test"},
          "yoast_head_json":{"og_image":[{"url":"http://getcomics.org/share/uploads/cover.jpg"}]},
          "content":{"rendered":"<a href='http://getcomics.org/dls/opaque'>Download</a>"}}]"""
        val record = service.parseWordPressPosts(json, "https://getcomics.org/wp-json/wp/v2/posts").single()
        assertEquals("https://getcomics.org/share/uploads/cover.jpg", record.coverUrl)
        val assets = service.inspectArticleHtml(record.canonicalUrl, "<a href='http://getcomics.org/dls/opaque'>Download</a>").assets
        assertEquals("https://getcomics.org/dls/opaque", assets.single().candidateUrl)
        assertEquals(SourceAssetStatus.METADATA_ONLY, assets.single().status)
    }

    @Test fun articleCandidatesResolveRelativeLinksAndKeepGatedHostsUnavailable() {
        val article = """<article id="post-12">
          <a href='/dls/opaque'>Download</a><a href='https://vikingfile.com/f/abc'>Host</a>
          <a href='https://datanodes.to/abc/test.cbr'>RAR file</a><a href='https://example.com/test.cbz'>CBZ</a>
          <a href='https://evilvikingfile.com/f/not-a-known-host'>Ignored</a>
        </article>"""
        val inspection = service.inspectArticleHtml("https://getcomics.org/marvel/test/", article)
        assertEquals(4, inspection.assets.size)
        assertFalse(inspection.canStartRemoteReader)
        assertEquals(1, inspection.assets.count { it.status == SourceAssetStatus.NEEDS_USER_ACTION })
        assertEquals(0, inspection.assets.count { it.status == SourceAssetStatus.UNSUPPORTED_FORMAT })
        assertEquals(3, inspection.assets.count { it.status == SourceAssetStatus.METADATA_ONLY })
    }

    @Test fun searchIsSentToRemoteCategoryInsteadOfOnlyFilteringLatestSnapshot() = runBlocking {
        var requested = ""
        val adapter = GetComicsCatalogService(now = { clock }, decodeImage = { true }, fetchText = { requested = it; "[]" })
        adapter.fetchWordPressPosts(search = "Secret Wars", page = 2)
        assertTrue(requested.contains("categories=3"))
        assertTrue(requested.contains("page=2"))
        assertTrue(requested.contains("search=Secret+Wars"))
    }

    @Test fun officialGuidePreservesContentSectionsAndExcludesNavigationLinks() {
        val html = """<html><head><meta content='A guide &amp; its issues' name='description'></head><body>
          <nav><a href='/comics/issue/999/nav'>Navigation</a></nav>
          <main><h1>Official Event Guide</h1>
            <h2>Main Event</h2><a href='/comics/issue/1/main'>#1</a><a href='/comics/issue/1/main'>duplicate cover</a>
            <h2>Companion Stories</h2><a href='/comics/issue/2/companion'>#2</a>
            <footer><a href='/comics/issue/888/footer'>Footer</a></footer>
          </main></body></html>"""
        val guide = OfficialMarvelRecommendationService().parseGuide("https://www.marvel.com/comics/guides/5/event", html, clock)
        assertEquals(listOf("Main Event", "Companion Stories"), guide.sections.map { it.title })
        assertEquals(listOf("1", "2"), guide.sections.flatMap { it.issues }.map { it.issueExternalId })
        assertEquals(listOf(1, 1), guide.sections.flatMap { it.issues }.map { it.order })
        assertEquals("A guide & its issues", guide.description)
    }

    @Test(expected = java.io.IOException::class)
    fun officialGuideWithNoVerifiedIssueLinksIsNotInvented() {
        OfficialMarvelRecommendationService().parseGuide("https://www.marvel.com/comics/guides/5/event", "<main><h1>Blocked</h1><p>Please verify</p></main>")
    }
}
