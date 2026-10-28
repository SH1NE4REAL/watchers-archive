package org.watchersarchive.app

import android.graphics.RectF
import androidx.compose.material3.MaterialTheme
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
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EnglishAssistantPanelTest {
    @get:Rule val compose = createComposeRule()
    private val token = EnglishToken("hero", "hero", RectF(10f, 10f, 50f, 30f), "hero saves", "line")
    private val page = EnglishPageTokens("page-1", 100, 200,
        listOf(token, EnglishToken("saves", "saves", RectF(55f,10f,95f,30f), "hero saves", "line")), false)

    @Test fun cardHasRequestedFieldsAndSpeechActionWithoutVocabularyButton() {
        var spoken: String? = null
        val entry = EnglishDictionaryEntry("hero", "/ˈhɪəroʊ/", meaning = "英雄；勇士", partOfSpeech = "名词",
            context = token.lineText, contextMeaning = "英雄救援", sourceLabel = "测试词典", sourceUrl = "https://example.test")
        compose.setContent { MaterialTheme { EnglishAssistantCard(EnglishAssistantState.Selected(page, token, entry),
            true, false, null, { spoken = it }, {}, {}, {}) } }
        compose.onNodeWithText("音标：/ˈhɪəroʊ/").assertIsDisplayed()
        compose.onNodeWithText("词性：名词").assertIsDisplayed()
        compose.onNodeWithText("英雄；勇士").assertIsDisplayed()
        compose.onNodeWithTag("english-pronounce").performClick()
        assertEquals("hero", spoken)
        compose.onNodeWithText("生词本", substring = true).assertDoesNotExist()
    }

    @Test fun optionalModelNeedsAnExplicitClickAndPhraseIsSelectedFromTheSameLine() {
        var downloads = 0
        var wifiOnly: Boolean? = null
        var phrase: EnglishToken? = null
        val entry = EnglishDictionaryEntry("hero", meaning = "英雄", requiresModel = true)
        compose.setContent { MaterialTheme { EnglishAssistantCard(EnglishAssistantState.Selected(page, token, entry),
            true, false, null, {}, { downloads++; wifiOnly = it }, { phrase = it }, {}) } }
        assertEquals(0, downloads)
        compose.onNodeWithTag("english-select-phrase").performClick()
        assertEquals("hero saves", phrase?.text)
        compose.onNodeWithTag("download-english-model").performScrollTo().performClick()
        assertEquals(1, downloads)
        assertEquals(true, wifiOnly)
    }

    @Test fun lookupShowsPendingCardAndMissingFieldsHaveHonestPlaceholders() {
        compose.setContent { MaterialTheme { EnglishAssistantCard(EnglishAssistantState.LookingUp(page, token),
            false, false, null, {}, {}, {}, {}) } }
        compose.onNodeWithText("正在查词…").assertIsDisplayed()
        compose.onNodeWithTag("english-pronounce").assertIsNotEnabled()
        compose.onNodeWithText("生词本", substring = true).assertDoesNotExist()
    }
}
