package org.watchersarchive.app

import android.graphics.RectF
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h720dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class EnglishAssistantCardHostTest {
    @get:Rule val compose = createComposeRule()
    private val token = EnglishToken("word", "Cyclops", RectF(40f, 600f, 130f, 626f), "Cyclops, come here.", "line")
    private val page = EnglishPageTokens("page-1", 320, 720, listOf(token), false)
    private fun entry(marvel: Boolean = false) = EnglishDictionaryEntry("Cyclops", "/ˈsaɪklɒps/", meaning = "独眼巨人", partOfSpeech = "名词",
        englishDefinition = "One-eyed giant in mythology.", context = token.lineText,
        marvelNames = if (marvel) listOf(MarvelNameMatch(MarvelReadingGlossary.entries.first { it.id == "cyclops" }, "Cyclops")) else emptyList())

    @Test fun compactCardAvoidsLowerSelectedWordAndExpandsOnlyOnRequest() {
        compose.setContent { MaterialTheme { EnglishAssistantCardHost(EnglishAssistantState.Selected(page, token, entry()),
            token.bounds, true, false, null, {}, {}, {}, {}) } }
        val card = compose.onNodeWithTag("english-assistant-card").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("english-assistant-card-host").fetchSemanticsNode().boundsInRoot
        assertTrue(card.height <= minOf(160f, viewport.height * .25f) + 1)
        assertTrue(card.bottom <= token.bounds.top - 12f)
        compose.onNodeWithText("独眼巨人").assertIsDisplayed()
        compose.onNodeWithText("音标：/ˈsaɪklɒps/").assertDoesNotExist()
        compose.onNodeWithTag("english-expand-card").performClick()
        compose.onNodeWithText("音标：/ˈsaɪklɒps/").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("词性：名词").performScrollTo().assertIsDisplayed()
    }

    @Test fun lookupCompletionKeepsTheSameCardSideAndManualMoveHasAnAutomaticReset() {
        var state by mutableStateOf<EnglishAssistantState>(EnglishAssistantState.LookingUp(page, token))
        var selected by mutableStateOf(token.bounds)
        compose.setContent { MaterialTheme { EnglishAssistantCardHost(state, selected, true, false, null, {}, {}, {}, {}) } }
        val first = compose.onNodeWithTag("english-assistant-card").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state = EnglishAssistantState.Selected(page, token, entry()) }
        val completed = compose.onNodeWithTag("english-assistant-card").fetchSemanticsNode().boundsInRoot
        assertEquals(first, completed)
        compose.onNodeWithTag("english-move-card").performClick()
        val manuallyLower = compose.onNodeWithTag("english-assistant-card").fetchSemanticsNode().boundsInRoot
        assertTrue(manuallyLower.top > first.top)
        compose.runOnIdle { selected = RectF(20f, 560f, 140f, 590f) }
        assertEquals(manuallyLower, compose.onNodeWithTag("english-assistant-card").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("english-auto-card").performClick()
        val automatic = compose.onNodeWithTag("english-assistant-card").fetchSemanticsNode().boundsInRoot
        assertTrue(automatic.top < manuallyLower.top)
        assertTrue(automatic.bottom <= selected.top - 12f)
    }

    @Test fun upperWordUsesLowerCardAndZoomGeometryCanMoveItToTheOtherSide() {
        var selected by mutableStateOf(RectF(40f, 80f, 130f, 106f))
        compose.setContent { MaterialTheme { EnglishAssistantCardHost(EnglishAssistantState.Selected(page, token, entry()),
            selected, true, false, null, {}, {}, {}, {}) } }
        val lower = compose.onNodeWithTag("english-assistant-card").fetchSemanticsNode().boundsInRoot
        assertTrue(lower.top >= selected.bottom + 12f)
        compose.runOnIdle { selected = RectF(40f, 590f, 130f, 626f) }
        val upper = compose.onNodeWithTag("english-assistant-card").fetchSemanticsNode().boundsInRoot
        assertTrue(upper.top < lower.top)
        assertTrue(upper.bottom <= selected.top - 12f)
    }

    @Test fun marvelCandidateLeadsTheSummaryWhileOrdinaryDictionarySenseStaysInDetails() {
        compose.setContent { MaterialTheme { EnglishAssistantCardHost(EnglishAssistantState.Selected(page, token, entry(marvel = true)),
            token.bounds, true, false, null, {}, {}, {}, {}) } }
        compose.onNodeWithText("漫威专名候选：镭射眼").assertIsDisplayed()
        compose.onNodeWithText("独眼巨人").assertDoesNotExist()
        compose.onNodeWithTag("english-expand-card").performClick()
        compose.onNodeWithText("普通词义").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("独眼巨人").performScrollTo().assertIsDisplayed()
    }
}
