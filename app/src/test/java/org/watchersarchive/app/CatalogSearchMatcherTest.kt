package org.watchersarchive.app

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CatalogSearchMatcherTest {
    private fun book(id: String, title: String, source: String = "getcomics") = CatalogSourceRecord(
        source, id, "https://example.test/$id", title, "Spider-Man synopsis is not a title", null,
        null, null, "2026-10-27T00:00:00Z", emptyList(), null, null, "CBZ", SourceConfidence.MEDIUM)
    private fun titles(query: String, vararg titles: String): List<String> =
        CatalogSearchMatcher.rank(titles.mapIndexed { index, title -> book("$index", title) }, query).map { it.title }

    @Test fun foldsCaseWidthSpacesAndPunctuationWithRootLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("iron man 2015", CatalogSearchMatcher.normalized("  ＩＲＯＮ—ＭＡＮ （２０１５）  "))
            assertEquals(listOf("IRON-MAN (2015)"), titles("iron man", "IRON-MAN (2015)", "Thor"))
        } finally { Locale.setDefault(old) }
    }

    @Test fun compactHyphensAndWordPrefixesMatchWithoutRewritingTitles() {
        assertEquals(listOf("The Amazing Spider-Man #2 (2015)"), titles("SpIdErMaN", "The Amazing Spider-Man #2 (2015)", "Avengers"))
        assertEquals(listOf("Spider-Man #2"), titles("spid man", "Spider-Man #2", "Superman #2"))
        assertEquals("spider man 2", CatalogSearchMatcher.remoteQuery("SPIDER—MAN #2"))
    }

    @Test fun boundedEnglishTyposAndTranspositionsMatchCandidates() {
        assertEquals(listOf("Avengers (2018)"), titles("avengres", "Avengers (2018)", "Adventure Time"))
        assertEquals(listOf("Spider-Man #2"), titles("spidr man", "Spider-Man #2", "Superman #2"))
        assertEquals(listOf("Spider-Man #2"), titles("spidermen", "Spider-Man #2", "Superman #2"))
        assertEquals(listOf("Avengers"), titles("qvengers", "Avengers", "Venom"))
    }

    @Test fun exactMatchesRankAbovePrefixesAndTypos() {
        assertEquals(listOf("Avengers", "Avengers Assemble", "Avengres", "Avenger"),
            titles("Avengers", "Avengres", "Avengers Assemble", "Avenger", "Avengers"))
    }

    @Test fun issueAndYearNumbersCannotFuzzilyMatchDifferentNumbers() {
        assertEquals(listOf("Spider-Man #1 (2015)"), titles("spider man #1 2015",
            "Spider-Man #10 (2015)", "Spider-Man #1 (1984)", "Spider-Man #1 (2015)", "Spider-Man Vol. 1 #10 (2015)"))
        assertEquals(listOf("Spider-Man #001"), titles("spider man #1", "Spider-Man #001", "Spider-Man #1.5"))
        assertEquals(listOf("Spider-Man #1.5"), titles("spider man #1.5", "Spider-Man #1", "Spider-Man #1.05", "Spider-Man #1.5"))
    }

    @Test fun aChineseQueryUsesOnlyExactCharactersOrProvidedAliases() {
        val books = listOf(book("one", "Civil War #1 (2006)"), book("two", "内战：前奏"), book("three", "内站"))
        assertEquals(listOf("two"), CatalogSearchMatcher.rank(books, "内战").map { it.externalId })
        assertEquals(setOf("one", "two"), CatalogSearchMatcher.rank(books, "内战", listOf("Civil War")).map { it.externalId }.toSet())
        assertTrue(CatalogSearchMatcher.rank(books, "内占").isEmpty())
    }

    @Test fun aliasExpansionsCannotDiscardRequestedYearsOrIssueNumbers() {
        val books = listOf(book("old", "Secret Wars #1 (1984)"), book("new", "Secret Wars #1 (2015)"), book("other", "Secret Wars #10 (2015)"))
        assertEquals(listOf("new"), CatalogSearchMatcher.rank(books, "秘密战争 #1 2015", listOf("Secret Wars")).map { it.externalId })
    }

    @Test fun explicitChineseIssueNumbersCannotMatchAnEditionNumberOrAnotherIssue() {
        val books = listOf(book("one", "Spider-Man #1 (2015)"), book("padded", "Spider-Man #001 (2015)"),
            book("ten", "Spider-Man #10 (2015)"), book("volume-one", "Spider-Man Vol. 1 #10 (2015)"),
            book("old", "Spider-Man #1 (1984)"))
        assertEquals(setOf("one", "padded", "old"), CatalogSearchMatcher.rank(books, "蜘蛛侠第１期", listOf("Spider-Man #1")).map { it.externalId }.toSet())
        assertEquals(setOf("one", "padded"), CatalogSearchMatcher.rank(books, "蜘蛛侠 第 ００１ 话 2015", listOf("Spider-Man #1 2015")).map { it.externalId }.toSet())
        val decimalIssues = listOf(book("decimal", "Spider-Man #1.5"), book("whole", "Spider-Man #1"), book("different", "Spider-Man #1.05"))
        assertEquals(listOf("decimal"), CatalogSearchMatcher.rank(decimalIssues, "蜘蛛侠第１.５話", listOf("Spider-Man #1.5")).map { it.externalId })
    }

    @Test fun chineseIssueMarkersOnTitlesHaveTheSameStrictMeaningAsHashes() {
        val books = listOf(book("one", "蜘蛛侠第1期"), book("ten", "蜘蛛侠第10期"), book("volume-one", "蜘蛛侠 第1卷 第10話"))
        assertEquals(listOf("one"), CatalogSearchMatcher.rank(books, "Spider-Man #1", listOf("蜘蛛侠")).map { it.externalId })
    }

    @Test fun sameTitlesStaySeparateAcrossPostsSourcesAndEditions() {
        val records = listOf(book("one", "Secret Wars (1984)"), book("two", "Secret Wars (1984)"),
            book("one", "Secret Wars (1984)", "local"), book("one", "Secret Wars (1984)"), book("new", "Secret Wars (2015)"))
        val result = CatalogSearchMatcher.rank(records, "secret wars")
        assertEquals(4, result.size)
        assertEquals(2, result.count { it.externalId == "one" })
        assertTrue(result.any { it.title.contains("2015") })
    }

    @Test fun ignoresSynopsisAndDoesNotGuessVeryShortOrUnrelatedWords() {
        assertTrue(CatalogSearchMatcher.rank(listOf(book("thor", "Thor")), "spider").isEmpty())
        assertTrue(titles("cat", "Cut", "Cataclysm").contains("Cataclysm"))
        assertFalse(titles("cat", "Cut").contains("Cut"))
        assertTrue(titles("civil", "Silver Surfer").isEmpty())
    }

    @Test fun inputsAndCandidateCountHaveHardBounds() {
        assertTrue(titles("x".repeat(121), "x".repeat(121)).isEmpty())
        assertTrue(titles(" ！？ ", "Avengers").isEmpty())
        val records = (0..CatalogSearchMatcher.MAX_CANDIDATES).map { book("$it", "Avengers") }
        assertEquals(CatalogSearchMatcher.MAX_CANDIDATES, CatalogSearchMatcher.rank(records, "avengers").size)
    }
}
