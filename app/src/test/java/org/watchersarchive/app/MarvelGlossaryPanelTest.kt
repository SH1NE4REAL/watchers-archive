package org.watchersarchive.app

import android.graphics.RectF
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** UI regressions use supplied source records; no network or OCR is invoked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h720dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MarvelGlossaryPanelTest {
    @get:Rule val compose = createComposeRule()
    private val token = EnglishToken("word", "Phoenix", RectF(20f, 20f, 110f, 40f), "Phoenix", "line")
    private val page = EnglishPageTokens("glossary-page", 320, 720, listOf(token), false)

    private fun term(id: String, english: String, chinese: String? = null) = MarvelReadingTerm(
        id = id,
        englishName = english,
        // Match the resource reader's English display fallback and explicit flag.
        chineseName = chinese ?: english,
        aliases = emptyList(),
        kind = MarvelNameKind.CHARACTER,
        englishSourceUrl = "https://www.wikidata.org/wiki/$id",
        chineseEvidence = if (chinese == null) MarvelChineseEvidence.UNTRANSLATED else MarvelChineseEvidence.COMMUNITY,
        note = "Community metadata, not verified publisher translations.",
        hasChineseName = chinese != null,
    )

    private fun entry(vararg terms: MarvelReadingTerm) = EnglishDictionaryEntry(
        original = token.text,
        phonetic = "/ˈfiːnɪks/",
        meaning = "凤凰；不死鸟",
        partOfSpeech = "名词",
        sourceLabel = "普通词典测试来源",
        sourceUrl = "https://example.test/dictionary/phoenix",
        marvelNames = terms.map { MarvelNameMatch(it, token.text) },
    )

    private fun show(entry: EnglishDictionaryEntry, onOpenUri: (String) -> Unit = {}) {
        compose.setContent {
            var compact by remember { mutableStateOf(true) }
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) = onOpenUri(uri)
            }) {
                MaterialTheme {
                    EnglishAssistantCard(
                        state = EnglishAssistantState.Selected(page, token, entry),
                        speechReady = true,
                        downloadingModel = false,
                        modelNotice = null,
                        onSpeak = {}, onDownloadModel = {}, onPhrase = {}, onDismiss = {},
                        compact = compact,
                        onToggleExpanded = { compact = !compact },
                    )
                }
            }
        }
    }

    @Test fun compactSummaryCountsChineseNamesAndDoesNotPromoteUntranslatedFallbacks() {
        show(entry(
            term("Q101", "Phoenix untranslated variant"),
            term("Q102", "Phoenix", "凤凰女"),
            term("Q103", "Phoenix alternate identity", "凤凰"),
        ))

        compose.onNodeWithText("漫威专名候选：凤凰女 等 2 项").assertIsDisplayed()
        compose.onNodeWithText("Phoenix untranslated variant").assertDoesNotExist()
        compose.onNodeWithText("凤凰；不死鸟").assertDoesNotExist()
        compose.onNodeWithText("生词本", substring = true).assertDoesNotExist()
    }

    @Test fun compactEnglishOnlyCandidatesKeepOrdinaryMeaningAndSayChineseIsMissing() {
        show(entry(term("Q201", "Phoenix untranslated variant")))

        compose.onNodeWithText("凤凰；不死鸟").assertIsDisplayed()
        compose.onNodeWithText("有漫威英文实体候选，中文名待补，展开查看。").assertIsDisplayed()
        compose.onNodeWithText("漫威专名候选：", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Phoenix untranslated variant").assertDoesNotExist()
        compose.onNodeWithText("生词本", substring = true).assertDoesNotExist()
    }

    @Test fun expandingKeepsChineseEvidenceUniverseAliasesAndAnIndependentSourceLink() {
        val candidate = term("Q301", "Phoenix", "凤凰女").copy(
            universe = "Marvel Comics / Earth-616",
            chineseAliases = listOf("Phoenix", "鳳凰女", "琴·葛蕾"),
        )
        var opened: String? = null
        show(entry(candidate)) { opened = it }
        compose.onNodeWithTag("english-expand-card").performClick()

        compose.onNodeWithText("凤凰女 · Phoenix").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("角色 · 社区中文名").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("资料范围：Marvel Comics / Earth-616").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("其他中文名称：鳳凰女、琴·葛蕾").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("英文实体资料").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(candidate.englishSourceUrl, opened) }
        compose.onNodeWithText("普通词义").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("凤凰；不死鸟").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("普通词典测试来源").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://example.test/dictionary/phoenix", opened) }
        compose.onNodeWithText("官方中文名", substring = true).assertDoesNotExist()
        compose.onNodeWithText("生词本", substring = true).assertDoesNotExist()
    }

    @Test fun expandedUntranslatedEntityUsesEnglishOnceAndKeepsOrdinaryDictionarySense() {
        val candidate = term("Q401", "Phoenix untranslated variant").copy(universe = "MCU", chineseAliases = listOf("Phoenix"))
        show(entry(candidate))
        compose.onNodeWithTag("english-expand-card").performClick()

        compose.onNodeWithText("Phoenix untranslated variant").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Phoenix untranslated variant · Phoenix untranslated variant").assertDoesNotExist()
        compose.onNodeWithText("角色 · 中文名待补").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("资料范围：MCU").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("其他中文名称：", substring = true).assertDoesNotExist()
        compose.onNodeWithText("普通词义").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("凤凰；不死鸟").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("生词本", substring = true).assertDoesNotExist()
    }
}
