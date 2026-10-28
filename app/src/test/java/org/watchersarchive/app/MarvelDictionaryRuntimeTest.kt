package org.watchersarchive.app

import android.content.Context
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real packaged ordinary dictionary plus the distinct Marvel-name candidate layer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MarvelDictionaryRuntimeTest {
    private class NoModel : EnglishDefinitionTranslator {
        var downloads = 0
        override suspend fun modelReady() = false
        override suspend fun downloadModel(wifiOnly: Boolean) { downloads++ }
        override suspend fun translate(text: String): String = error("A plain lookup must not translate or download")
        override fun close() { }
    }
    private fun token(word: String) = EnglishToken("term", word, RectF(10f, 10f, 100f, 30f), "$word is here.", "line")

    @Test fun cyclopsOffersThePreferredCharacterNameAndKeepsItsActualOrdinaryDefinition() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val translator = NoModel()
        val dictionary = BundleEnglishDictionary(context.assets)
        val ordinary = checkNotNull(dictionary.lookup("Cyclops"))
        val provider = PublicEnglishDictionaryProvider(context, translator, dictionary, "X-Men (2022)")
        try {
            val term = token("CYCLOPS")
            val page = EnglishPageTokens("page", 300, 600, listOf(term), false)
            val result = checkNotNull(provider.lookup(term, page))
            assertEquals("镭射眼", result.marvelNames.first().term.chineseName)
            assertNotEquals("镭射眼", result.meaning)
            assertTrue(result.meaning.contains(checkNotNull(ordinary.chinese).replace("\\n", "\n").lineSequence().first()))
            assertEquals("CYCLOPS", result.original)
            assertEquals(0, translator.downloads)
        } finally { provider.close() }
    }

    @Test fun unknownNamesUseTheOrdinaryDictionaryAndStormRemainsAnAmbiguousCandidate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = PublicEnglishDictionaryProvider(context, NoModel())
        try {
            val weather = token("storm")
            val result = checkNotNull(provider.lookup(weather, EnglishPageTokens("weather",300,600,listOf(weather),false)))
            assertTrue(result.marvelNames.any { it.term.chineseName == "暴风女" })
            assertNotEquals("暴风女", result.meaning)
            val ordinary = token("pencil")
            assertTrue(checkNotNull(provider.lookup(ordinary, EnglishPageTokens("plain",300,600,listOf(ordinary),false))).marvelNames.isEmpty())
        } finally { provider.close() }
    }
}
