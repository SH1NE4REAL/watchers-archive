package org.watchersarchive.app

import org.junit.Assert.*
import org.junit.Test

class CatalogSearchAliasesTest {
    @Test fun chineseNameRetainsYearAndIssueInformation() {
        assertEquals(listOf("Spider-Man 2020 #1"), CatalogSearchAliases.expandedTerms("蜘蛛侠 2020 #1"))
        assertEquals(listOf("Secret Wars 1984"), CatalogSearchAliases.expandedTerms("秘密战争1984"))
        assertEquals(listOf("Spider-Man #1"), CatalogSearchAliases.expandedTerms("蜘蛛侠第１期"))
    }

    @Test fun longestKnownNameWinsWithoutCollapsingDistinctSeries() {
        assertEquals(listOf("Amazing Spider-Man 2018"), CatalogSearchAliases.expandedTerms("神奇蜘蛛侠2018"))
        assertEquals(listOf("Immortal Hulk"), CatalogSearchAliases.expandedTerms("不朽浩克"))
        assertEquals(listOf("Hulk"), CatalogSearchAliases.expandedTerms("浩克"))
    }

    @Test fun explicitTraditionalAliasesAndLatinCaseAreRecognized() {
        assertEquals(listOf("Infinity Gauntlet"), CatalogSearchAliases.expandedTerms("無限手套"))
        assertEquals(listOf("X-Men 2024"), CatalogSearchAliases.expandedTerms("ｘ戰警 2024"))
        assertEquals(listOf("Avengers"), CatalogSearchAliases.expandedTerms("復仇者聯盟"))
    }

    @Test fun shortPrefixesAreBoundedAndUnknownChineseIsNotInvented() {
        assertEquals(listOf("Spider-Man"), CatalogSearchAliases.expandedTerms("蜘蛛"))
        assertEquals(listOf("Avengers"), CatalogSearchAliases.expandedTerms("复仇"))
        assertTrue(CatalogSearchAliases.expandedTerms("神").isEmpty())
        assertTrue(CatalogSearchAliases.expandedTerms("无限战争").isEmpty())
        assertTrue(CatalogSearchAliases.expandedTerms("未收录的新故事").isEmpty())
    }

    @Test fun unknownQualifiersArePreservedRatherThanSilentlyDiscarded() {
        assertEquals(listOf("未知 Spider-Man 2020"), CatalogSearchAliases.expandedTerms("未知蜘蛛侠 2020"))
        assertEquals(listOf("Civil War Vol. 2"), CatalogSearchAliases.expandedTerms("内战第2卷"))
    }

    @Test fun entriesNeverClaimVerifiedChineseEditionsWithoutPublisherEvidence() {
        assertEquals(15, CatalogSearchAliases.entries.size)
        assertTrue(CatalogSearchAliases.entries.all { it.evidence == NameAliasEvidence.COMMON })
        assertTrue(CatalogSearchAliases.entries.all { it.sourceUrl.startsWith("https://www.marvel.com/search?query=") })
        assertTrue(CatalogSearchAliases.entries.all { it.evidenceNote.contains("未核实") })
        assertTrue(CatalogSearchAliases.expandedTerms("SPIDER-MAN").isEmpty())
        assertTrue(CatalogSearchAliases.expandedTerms("a".repeat(121)).isEmpty())
    }
}
