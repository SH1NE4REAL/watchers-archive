package org.watchersarchive.app

import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MarvelReadingGlossaryTest {
    private fun page(vararg words: String): EnglishPageTokens = EnglishPageTokens("page", 800, 1200,
        words.mapIndexed { index, word -> EnglishToken("word:$index", word,
            RectF(index * 45f, 20f, index * 45f + 40, 45f), words.joinToString(" "), "line") }, false)

    @Test fun cyclopsShowsTheUsersPreferredNameAsACandidateWithoutChangingOriginalText() {
        val page = page("CYCLOPS!")
        val token = page.tokens.single()
        val match = MarvelReadingGlossary.matches(token, page).single()
        assertEquals("Cyclops", match.term.englishName)
        assertEquals("镭射眼", match.term.chineseName)
        assertEquals(MarvelChineseEvidence.USER_PREFERRED, match.term.chineseEvidence)
        assertEquals("CYCLOPS!", match.matchedText)
        assertEquals("CYCLOPS!", token.text)
        assertTrue(match.term.note.contains("普通词义"))
        assertFalse(match.correctedOcr)
    }

    @Test fun fullIdentityAliasCanBeFoundWhenEitherWordIsTapped() {
        val page = page("Scott", "Summers", "speaks")
        for (index in 0..1) {
            val match = MarvelReadingGlossary.matches(page.tokens[index], page).single()
            assertEquals("cyclops", match.term.id)
            assertEquals("Scott Summers", match.matchedText)
        }
        assertTrue(MarvelReadingGlossary.matches(page.tokens[2], page).isEmpty())
    }

    @Test fun multiwordNamesPreferTheLongestRegisteredPhraseInTheSameLine() {
        val page = page("The", "THING!", "and", "Scarlet", "Witch")
        assertEquals("The THING!", MarvelReadingGlossary.matches(page.tokens[1], page).single().matchedText)
        assertEquals("scarlet-witch", MarvelReadingGlossary.matches(page.tokens[4], page).single().term.id)
    }

    @Test fun tappingTheDoesNotExpandToTheThingButExplicitlySelectingTheNameDoes() {
        val page = page("The", "Thing")
        assertTrue(MarvelReadingGlossary.matches(page.tokens.first(), page).isEmpty())
        val selected = checkNotNull(phraseFromRange(page, page.tokens.first().id, page.tokens.last().id))
        assertEquals("thing", MarvelReadingGlossary.matches(selected, page).single().term.id)
        assertEquals("The Thing", MarvelReadingGlossary.matches(selected, page).single().matchedText)
    }

    @Test fun punctuationWidthCaseAndPossessivesDoNotHideRegisteredNames() {
        for (name in listOf("S.H.I.E.L.D.", "shield", "Ｓ．Ｈ．Ｉ．Ｅ．Ｌ．Ｄ．")) {
            val page = page(name)
            assertEquals("shield", MarvelReadingGlossary.matches(page.tokens.single(), page).single().term.id)
        }
        val spider = page("SPIDER—MAN’S")
        assertEquals("spider-man", MarvelReadingGlossary.matches(spider.tokens.single(), spider).single().term.id)
        val cyclops = page("Cyclops's")
        assertEquals("cyclops", MarvelReadingGlossary.matches(cyclops.tokens.single(), cyclops).single().term.id)
    }

    @Test fun ordinaryWordsRemainAmbiguousEvenInCapsAndTitleHintsAreOnlyWeakEvidence() {
        val page = page("A", "STORM", "is", "coming")
        val plain = MarvelReadingGlossary.matches(page.tokens[1], page).single()
        assertEquals("暴风女", plain.term.chineseName)
        assertNull(plain.contextHint)
        assertTrue(plain.term.note.contains("普通词义"))
        val inXMen = MarvelReadingGlossary.matches(page.tokens[1], page, "X-Men by Jonathan Hickman").single()
        assertTrue(inXMen.contextHint!!.contains("仍需结合原句"))
    }

    @Test fun substringsOcrTyposAndUnregisteredNamesAreNotInvented() {
        for (word in listOf("anything", "brainstorm", "television", "beastly", "Cycl0ps", "Clyclops", "UnknownHero")) {
            val page = page(word)
            assertTrue(word, MarvelReadingGlossary.matches(page.tokens.single(), page).isEmpty())
        }
    }

    @Test fun namesNeverJoinAcrossSeparateOcrLines() {
        val original = page("Iron", "Man")
        val page = original.copy(tokens = original.tokens.mapIndexed { index, token -> token.copy(lineId = "line-$index") })
        assertTrue(MarvelReadingGlossary.matches(page.tokens[1], page).isEmpty())
    }

    @Test fun aSelectedPhraseCanOfferSeveralCandidatesButNeverMoreThanThree() {
        val page = page("Cyclops", "Storm", "Beast", "Vision")
        val phrase = checkNotNull(phraseFromRange(page, page.tokens.first().id, page.tokens.last().id))
        val matches = MarvelReadingGlossary.matches(phrase, page)
        assertEquals(3, matches.size)
        assertEquals(3, matches.map { it.term.id }.distinct().size)
        assertTrue(matches.all { it.matchedText in page.tokens.map(EnglishToken::text) })
    }

    @Test fun staleOrForgedTokenCannotMatchAnotherPage() {
        val page = page("Cyclops")
        assertTrue(MarvelReadingGlossary.matches(page.tokens.single().copy(text = "Storm"), page).isEmpty())
        assertTrue(MarvelReadingGlossary.matches(page.tokens.single().copy(id = "old-page-word"), page).isEmpty())
    }

    @Test fun theSmallGlossaryKeepsSourceAndTranslationEvidenceDistinct() {
        val entries = MarvelReadingGlossary.entries
        assertEquals(20, entries.size)
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        assertEquals(1, entries.count { it.chineseEvidence == MarvelChineseEvidence.USER_PREFERRED })
        assertTrue(entries.all { it.englishSourceUrl.startsWith("https://www.marvel.com/") })
        assertTrue(entries.all { it.chineseEvidence in setOf(MarvelChineseEvidence.COMMON, MarvelChineseEvidence.USER_PREFERRED) })
        assertTrue(entries.any { it.kind == MarvelNameKind.TEAM })
        assertTrue(entries.any { it.kind == MarvelNameKind.ORGANIZATION })
        assertTrue(entries.any { it.kind == MarvelNameKind.PLACE })
    }

    @Test fun bulkNamesDoNotTurnCommonFunctionWordsIntoCharacterCandidates() {
        for (word in listOf("A", "IT", "ONE", "they", "THE")) {
            val page = page(word)
            val requested = mutableListOf<String>()
            val matches = MarvelReadingGlossary.matches(page.tokens.single(), page, extraFind = { names ->
                requested += names
                emptyList()
            })
            assertTrue(word, requested.isEmpty())
            assertTrue(word, matches.isEmpty())
        }
    }

    @Test fun bulkNamesUseLongCompleteNamesWithoutDiscardingDifferentVersions() {
        val page = page("He", "Who", "Remains")
        val one = MarvelReadingTerm("source:one", "He Who Remains", "遗留之人", emptyList(), MarvelNameKind.CHARACTER,
            "https://www.wikidata.org/wiki/Q100", MarvelChineseEvidence.COMMUNITY, "Fixture", universe = "MCU")
        val other = one.copy(id = "source:other", chineseName = "He Who Remains", hasChineseName = false,
            chineseEvidence = MarvelChineseEvidence.UNTRANSLATED, universe = "Earth-616")
        val matches = MarvelReadingGlossary.matches(page.tokens.first(), page, extraFind = { names ->
            assertTrue(names.contains("he who remains"))
            assertFalse(names.contains("he"))
            listOf(other, one)
        })
        assertEquals(listOf("source:one", "source:other"), matches.map { it.term.id })
        assertTrue(matches.all { it.matchedText == "He Who Remains" })
        assertTrue(matches.all { it.contextHint!!.contains("仍需结合原句") })
    }
}
